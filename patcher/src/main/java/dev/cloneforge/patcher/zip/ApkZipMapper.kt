package dev.cloneforge.patcher.zip

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater

/*
 * Raw, byte-level ZIP reader/writer used by the patcher.
 *
 * Why hand-rolled: APKs sent to PackageInstaller need exact control over the
 * compression method and the data offset alignment of uncompressed entries
 * (spec 7.1):
 *
 *     P = (A - (O_data mod A)) mod A
 *
 * where O_data = O_LFH + 30 + L_name + L_extra and A is 16384 for .so entries
 * and 4 for resources.arsc. Generic java.util.zip streams cannot insert
 * padding into the local file header extra field, so archives are rebuilt
 * here entry by entry.
 */

/** One entry as parsed from the source ZIP central directory. */
class RawZipEntry internal constructor(
    val name: String,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    /** Absolute offset of the entry file data in the source APK. */
    val dataOffset: Long,
    val dosTime: Int,
    val dosDate: Int,
    val versionMadeBy: Int,
    val versionNeeded: Int,
    val internalAttributes: Int,
    val externalAttributes: Long,
) {
    val isStored: Boolean get() = method == ApkZipMapper.METHOD_STORED
}

/** A brand new entry to append to the output archive. Supports either in-memory byte array or direct file streaming. */
class NewEntry(
    val name: String,
    val raw: ByteArray? = null,
    val file: File? = null,
    val method: Int,
    /** Data offset alignment boundary in the output archive (0 = no padding). */
    val alignment: Int,
) {
    constructor(name: String, raw: ByteArray, method: Int, alignment: Int) :
        this(name, raw, null, method, alignment)

    constructor(name: String, file: File, method: Int, alignment: Int) :
        this(name, null, file, method, alignment)
}

/** Minimal little-endian writer. */
internal class LeWriter(private val out: OutputStream) : Closeable {

    private fun writeByte(value: Int) {
        out.write(value and 0xFF)
    }

    fun writeShort(value: Int) {
        writeByte(value)
        writeByte(value shr 8)
    }

    fun writeInt(value: Long) {
        writeByte(value.toInt())
        writeByte(value.toInt() shr 8)
        writeByte(value.toInt() shr 16)
        writeByte(value.toInt() shr 24)
    }

    fun writeFully(bytes: ByteArray) {
        out.write(bytes)
    }

    fun writeFully(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
    }

    fun writeZeros(count: Long) {
        val zeros = ByteArray(minOf(count, 64L * 1024).toInt())
        var remaining = count
        while (remaining > 0) {
            val chunk = minOf(remaining, zeros.size.toLong()).toInt()
            out.write(zeros, 0, chunk)
            remaining -= chunk
        }
    }

    override fun close() {
        out.close()
    }
}

internal class CdRecord(
    val name: String,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val lfhOffset: Long,
    val dosTime: Int,
    val dosDate: Int,
    val versionMadeBy: Int,
    val versionNeeded: Int,
    val internalAttributes: Int,
    val externalAttributes: Long,
)

object ApkZipMapper {

    const val METHOD_STORED = 0
    const val METHOD_DEFLATED = 8

    private const val SIG_LFH = 0x04034b50
    private const val SIG_CD = 0x02014b50
    private const val SIG_EOCD = 0x06054b50

    private const val LFH_FIXED = 30
    private const val CD_FIXED = 46
    private const val EOCD_FIXED = 22

    private const val FLAG_UTF8 = 0x0800

    /** Generic fixed timestamps so rebuilds are deterministic. */
    private const val DOS_TIME = 0x6000 // 00:00:00
    private const val DOS_DATE = 0x4F7D // 2025-01-01

    private const val DEFLATER_LEVEL = 6

    private val DEX_NAME_REGEX = Regex("^classes(\\d*)\\.dex$")

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Parses the central directory of [apkFile]. Returns one [RawZipEntry] per record. */
    fun listEntries(apkFile: File): List<RawZipEntry> {
        RandomAccessFile(apkFile, "r").use { raf ->
            val eocd = locateEocd(raf)
            val result = ArrayList<RawZipEntry>(eocd.entryCount)
            raf.seek(eocd.cdOffset)
            repeat(eocd.entryCount) {
                val header = ByteBuffer.allocate(CD_FIXED).order(ByteOrder.LITTLE_ENDIAN)
                raf.readFully(header.array())
                val sig = u32(header, 0)
                if (sig != SIG_CD.toLong()) {
                    throw IOException("Bad central directory record signature in ${apkFile.name}")
                }
                val nameLen = u16(header, 28)
                val extraLen = u16(header, 30)
                val commentLen = u16(header, 32)
                val name = readString(raf, nameLen)
                skipFully(raf, (extraLen + commentLen).toLong())
                val nextCdOffset = raf.filePointer

                val lfhOffset = u32(header, 42)
                // The local header is authoritative for name/extra lengths; the CD
                // may disagree in archives written with data descriptors.
                val tail = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                raf.seek(lfhOffset + 26)
                raf.readFully(tail.array())
                val lfhNameLen = u16(tail, 0)
                val lfhExtraLen = u16(tail, 2)
                raf.seek(nextCdOffset)

                result.add(
                    RawZipEntry(
                        name = name,
                        method = u16(header, 10),
                        crc = u32(header, 16),
                        compressedSize = u32(header, 20),
                        uncompressedSize = u32(header, 24),
                        dataOffset = lfhOffset + LFH_FIXED + lfhNameLen + lfhExtraLen,
                        dosTime = u16(header, 12),
                        dosDate = u16(header, 14),
                        versionMadeBy = u16(header, 4),
                        versionNeeded = u16(header, 6),
                        internalAttributes = u16(header, 36),
                        externalAttributes = u32(header, 38),
                    )
                )
            }
            return result
        }
    }

    /**
     * Highest "classesN.dex" index found in [entries]. `classes.dex` counts as 1,
     * `classes2.dex` as 2, and so on. Returns 0 when the archive has no dex yet.
     */
    fun highestDexIndex(entries: List<RawZipEntry>): Int {
        var max = 0
        for (entry in entries) {
            val match = DEX_NAME_REGEX.find(entry.name.substringAfterLast('/')) ?: continue
            val digits = match.groupValues[1]
            val idx = if (digits.isEmpty()) 1 else digits.toIntOrNull() ?: continue
            if (idx > max) max = idx
        }
        return max
    }

    /** "classes.dex" for 1, "classes2.dex" for 2, ... */
    fun dexNameForIndex(index: Int): String {
        require(index >= 1) { "Dex index must be >= 1" }
        return if (index == 1) "classes.dex" else String.format(Locale.US, "classes%d.dex", index)
    }

    private class EocdInfo(val cdOffset: Long, val entryCount: Int)

    private fun locateEocd(raf: RandomAccessFile): EocdInfo {
        val length = raf.length()
        if (length < EOCD_FIXED) throw IOException("File too small to be a ZIP archive")

        val searchSize = minOf(EOCD_FIXED + 65535L, length).toInt()
        val buf = ByteArray(searchSize)
        raf.seek(length - searchSize)
        raf.readFully(buf)
        val wrapped = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

        for (i in searchSize - EOCD_FIXED downTo 0) {
            val sig = wrapped.getInt(i).toLong() and 0xFFFFFFFFL
            if (sig != SIG_EOCD.toLong()) continue
            val commentLen = wrapped.getShort(i + 20).toInt() and 0xFFFF
            if (i + EOCD_FIXED + commentLen == searchSize) {
                val cdOffset = wrapped.getInt(i + 16).toLong() and 0xFFFFFFFFL
                val entryCount = wrapped.getShort(i + 10).toInt() and 0xFFFF
                return EocdInfo(cdOffset, entryCount)
            }
        }
        throw IOException("End of central directory not found")
    }

    private fun readString(raf: RandomAccessFile, nameLen: Int): String {
        val bytes = ByteArray(nameLen)
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipFully(raf: RandomAccessFile, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = raf.skipBytes(minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
            if (skipped <= 0) throw EOFException("Truncated central directory")
            remaining -= skipped
        }
    }

    private fun u32(buf: ByteBuffer, offset: Int): Long = buf.getInt(offset).toLong() and 0xFFFFFFFFL
    private fun u16(buf: ByteBuffer, offset: Int): Int = buf.getShort(offset).toInt() and 0xFFFF

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Rebuilds the APK [sourceApk] into [outputFile], writing every entry
     * through fresh local file headers with computed data alignment.
     *
     * Entry order: surviving source entries (byte-for-byte preserved payloads),
     * then [replaced] entries, then [appended] entries. Dropped names ([remove])
     * should include stale META-INF signature pairs so the final signature is
     * rebuilt from scratch.
     */
    fun rebuild(
        sourceApk: File,
        outputFile: File,
        remove: (name: String) -> Boolean = { false },
        replaced: Map<String, ByteArray> = emptyMap(),
        appended: List<NewEntry> = emptyList(),
    ) {
        val entries = listEntries(sourceApk)

        val copyList = ArrayList<RawZipEntry>(entries.size)
        val replacementList = ArrayList<RawZipEntry>(entries.size)
        for (entry in entries) {
            if (remove(entry.name)) continue
            if (replaced.containsKey(entry.name)) replacementList.add(entry) else copyList.add(entry)
        }

        if (outputFile.parentFile != null) outputFile.parentFile!!.mkdirs()
        val tmp = File(
            outputFile.parentFile ?: sourceApk.parentFile ?: File("."),
            outputFile.name + ".tmp_" + System.nanoTime()
        )

        RandomAccessFile(sourceApk, "r").use { src ->
            val out = LeWriter(java.io.BufferedOutputStream(tmp.outputStream(), 256 * 1024))
            try {
                var offset = 0L
                val records = ArrayList<CdRecord>(entries.size + appended.size)

                // 1. Surviving source entries: raw compressed payload copied
                //    verbatim from source, headers freshly written so alignment
                //    padding can be recomputed.
                for (e in copyList) {
                    val nameBytes = e.name.toByteArray(Charsets.UTF_8)
                    val alignment = alignmentForName(e.name, e.method)
                    val pad = alignmentPadding(offset + LFH_FIXED + nameBytes.size, alignment)
                    writeCopiedLfh(out, nameBytes, pad, e)
                    if (pad > 0) out.writeZeros(pad)
                    streamSource(src, e.dataOffset, e.compressedSize, out)
                    records.add(recordOf(e, e.method, e.crc, e.compressedSize, e.uncompressedSize, offset))
                    offset += LFH_FIXED + nameBytes.size + pad + e.compressedSize
                }

                // 2. Replaced source entries: fresh payload bytes, method forced
                //    to STORED for .png/.so (they are already-compressed formats
                //    or need mmap alignment), otherwise preserving the source method.
                for (e in replacementList) {
                    val raw = replaced.getValue(e.name)
                    val method = replacedMethod(e.name, e.method)
                    offset = writeFreshEntry(out, records, method, raw, e.name, offset,
                        alignmentForName(e.name, method), e)
                }

                // 3. Appended entries.
                for (spec in appended) {
                    offset = writeAppendedEntry(out, records, spec, offset)
                }

                // 4. Central directory + EOCD.
                val cdOffset = offset
                val cdSize = writeCentralDirectory(out, records)
                writeEocd(out, records, cdOffset, cdSize)
            } finally {
                out.close()
            }
        }

        if (!renameFile(tmp, outputFile)) {
            tmp.delete()
            throw IOException("Unable to finalize rebuilt APK at $outputFile")
        }
    }

    /** Writes a local file header for a copied-from-source entry. */
    private fun writeCopiedLfh(
        out: LeWriter,
        nameBytes: ByteArray,
        extraLen: Long,
        source: RawZipEntry,
    ) {
        out.writeInt(SIG_LFH.toLong())
        out.writeShort(source.versionNeeded.coerceIn(10, 63))
        out.writeShort(FLAG_UTF8)
        out.writeShort(source.method)
        out.writeShort(source.dosTime)
        out.writeShort(source.dosDate)
        out.writeInt(source.crc)
        out.writeInt(source.compressedSize)
        out.writeInt(source.uncompressedSize)
        out.writeShort(nameBytes.size)
        out.writeShort(extraLen.toInt())
        out.writeFully(nameBytes)
    }

    /** Writes a complete freshly-produced entry; appends its CD record; returns end offset. */
    private fun writeFreshEntry(
        out: LeWriter,
        records: MutableList<CdRecord>,
        method: Int,
        raw: ByteArray,
        name: String,
        startOffset: Long,
        alignment: Int,
        meta: RawZipEntry?,
    ): Long {
        val crc = crcOf(raw)
        val payload: ByteArray
        val compressedSize: Long
        if (method == METHOD_DEFLATED) {
            payload = deflate(raw)
            compressedSize = payload.size.toLong()
        } else {
            payload = raw
            compressedSize = raw.size.toLong()
        }
        val uncompressedSize = raw.size.toLong()
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val pad = alignmentPadding(startOffset + LFH_FIXED + nameBytes.size, alignment)

        if (meta != null) {
            // Preserve the source entry's timestamps/attrs.
            out.writeInt(SIG_LFH.toLong())
            out.writeShort(meta.versionNeeded.coerceIn(10, 63))
            out.writeShort(FLAG_UTF8)
            out.writeShort(method)
            out.writeShort(meta.dosTime)
            out.writeShort(meta.dosDate)
            out.writeInt(crc)
            out.writeInt(compressedSize)
            out.writeInt(uncompressedSize)
            out.writeShort(nameBytes.size)
            out.writeShort(pad.toInt())
        } else {
            out.writeInt(SIG_LFH.toLong())
            out.writeShort(20)
            out.writeShort(FLAG_UTF8)
            out.writeShort(method)
            out.writeShort(DOS_TIME)
            out.writeShort(DOS_DATE)
            out.writeInt(crc)
            out.writeInt(compressedSize)
            out.writeInt(uncompressedSize)
            out.writeShort(nameBytes.size)
            out.writeShort(pad.toInt())
        }
        out.writeFully(nameBytes)
        if (pad > 0) out.writeZeros(pad)
        out.writeFully(payload)

        records.add(
            CdRecord(
                name = name,
                method = method,
                crc = crc,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                lfhOffset = startOffset,
                dosTime = meta?.dosTime ?: DOS_TIME,
                dosDate = meta?.dosDate ?: DOS_DATE,
                versionMadeBy = meta?.versionMadeBy ?: 0x031E, // Unix, ZIP 3.0
                versionNeeded = meta?.versionNeeded?.coerceIn(10, 63) ?: 20,
                internalAttributes = meta?.internalAttributes ?: 0,
                externalAttributes = meta?.externalAttributes ?: 0L,
            )
        )
        return startOffset + LFH_FIXED + nameBytes.size + pad + compressedSize
    }

    private fun writeAppendedEntry(
        out: LeWriter,
        records: MutableList<CdRecord>,
        spec: NewEntry,
        startOffset: Long,
    ): Long {
        val file = spec.file
        if (file != null) {
            val alignment = maxOf(spec.alignment, alignmentForName(spec.name, spec.method))
            val uncompressedSize = file.length()
            val nameBytes = spec.name.toByteArray(Charsets.UTF_8)
            val pad = alignmentPadding(startOffset + LFH_FIXED + nameBytes.size, alignment)
            val crc = crcOfFile(file)
            val compressedSize = uncompressedSize // Streaming METHOD_STORED

            out.writeInt(SIG_LFH.toLong())
            out.writeShort(20)
            out.writeShort(FLAG_UTF8)
            out.writeShort(spec.method)
            out.writeShort(DOS_TIME)
            out.writeShort(DOS_DATE)
            out.writeInt(crc)
            out.writeInt(compressedSize)
            out.writeInt(uncompressedSize)
            out.writeShort(nameBytes.size)
            out.writeShort(pad.toInt())
            out.writeFully(nameBytes)
            if (pad > 0) out.writeZeros(pad)

            val buf = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    out.writeFully(buf, 0, n)
                }
            }

            records.add(
                CdRecord(
                    name = spec.name,
                    method = spec.method,
                    crc = crc,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    lfhOffset = startOffset,
                    dosTime = DOS_TIME,
                    dosDate = DOS_DATE,
                    versionMadeBy = 0x031E,
                    versionNeeded = 20,
                    internalAttributes = 0,
                    externalAttributes = 0L,
                )
            )
            return startOffset + LFH_FIXED + nameBytes.size + pad + compressedSize
        } else {
            val raw = spec.raw ?: ByteArray(0)
            return writeFreshEntry(
                out, records, spec.method, raw, spec.name, startOffset,
                maxOf(spec.alignment, alignmentForName(spec.name, spec.method)), null
            )
        }
    }

    private fun crcOfFile(file: File): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    private fun recordOf(
        e: RawZipEntry,
        method: Int,
        crc: Long,
        compressedSize: Long,
        uncompressedSize: Long,
        lfhOffset: Long,
    ): CdRecord = CdRecord(
        name = e.name,
        method = method,
        crc = crc,
        compressedSize = compressedSize,
        uncompressedSize = uncompressedSize,
        lfhOffset = lfhOffset,
        dosTime = e.dosTime,
        dosDate = e.dosDate,
        versionMadeBy = e.versionMadeBy,
        versionNeeded = e.versionNeeded,
        internalAttributes = e.internalAttributes,
        externalAttributes = e.externalAttributes,
    )

    private fun writeCentralDirectory(out: LeWriter, records: List<CdRecord>): Long {
        var size = 0L
        for (r in records) {
            val nameBytes = r.name.toByteArray(Charsets.UTF_8)
            out.writeInt(SIG_CD.toLong())
            out.writeShort(r.versionMadeBy)
            out.writeShort(r.versionNeeded)
            out.writeShort(FLAG_UTF8)
            out.writeShort(r.method)
            out.writeShort(r.dosTime)
            out.writeShort(r.dosDate)
            out.writeInt(r.crc)
            out.writeInt(r.compressedSize)
            out.writeInt(r.uncompressedSize)
            out.writeShort(nameBytes.size)
            out.writeShort(0) // extra len
            out.writeShort(0) // comment len
            out.writeShort(0) // disk start
            out.writeShort(r.internalAttributes)
            out.writeInt(r.externalAttributes)
            out.writeInt(r.lfhOffset)
            out.writeFully(nameBytes)
            size += CD_FIXED + nameBytes.size
        }
        return size
    }

    private fun writeEocd(out: LeWriter, records: List<CdRecord>, cdOffset: Long, cdSize: Long) {
        out.writeInt(SIG_EOCD.toLong())
        out.writeShort(0) // disk number
        out.writeShort(0) // cd disk
        out.writeShort(records.size)
        out.writeShort(records.size)
        out.writeInt(cdSize)
        out.writeInt(cdOffset)
        out.writeShort(0) // comment len
    }

    private fun alignmentForName(name: String, method: Int): Int {
        if (method != METHOD_STORED) return 0
        if (name.endsWith(".so")) return 16384
        if (name == "resources.arsc") return 4
        return 0
    }

    /**
     * Spec 7.1: P = (A - (O_data mod A)) mod A, expressed as local-file-header
     * extra-field padding bytes so the entry data starts aligned.
     */
    private fun alignmentPadding(oData: Long, alignment: Int): Long {
        if (alignment <= 1) return 0
        val a = alignment.toLong()
        return (a - (oData % a)) % a
    }

    private fun replacedMethod(name: String, original: Int): Int {
        return if (name.endsWith(".png") || name.endsWith(".so")) METHOD_STORED else original
    }

    private fun streamSource(src: RandomAccessFile, offsetInSource: Long, size: Long, sink: LeWriter) {
        src.seek(offsetInSource)
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
            src.readFully(buffer, 0, chunk)
            sink.writeFully(buffer, 0, chunk)
            remaining -= chunk
        }
    }

    private fun crcOf(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(DEFLATER_LEVEL, true)
        val out = ByteArrayOutputStream(maxOf(256, raw.size / 2))
        val buf = ByteArray(16 * 1024)
        try {
            deflater.setInput(raw)
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    /** Rename with graceful fallback for filesystems where target may exist. */
    internal fun renameFile(tmp: File, target: File): Boolean {
        if (target.exists() && !target.delete()) return false
        if (tmp.renameTo(target)) return true
        return try {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            true
        } catch (_: Exception) {
            false
        }
    }
}

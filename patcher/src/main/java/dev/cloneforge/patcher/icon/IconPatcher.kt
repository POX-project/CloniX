package dev.cloneforge.patcher.icon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import dev.cloneforge.patcher.zip.ApkZipMapper
import dev.cloneforge.patcher.zip.RawZipEntry
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Best-effort, never-throwing launcher icon recoloring (spec 1.2 phase 3).
 *
 * Operates at the raw ZIP level on the clone APK before signing: matching
 * mipmap/drawable PNG bitmaps are decoded, hue-rotated via a saturation-preserving
 * ColorMatrix rotation, re-encoded as PNG and swapped into the rebuilt ZIP
 * (compression method and alignment rules of the zip mapper are preserved so
 * the central directory stays consistent).
 */
object IconPatcher {

    private const val TAG = "CloneForgeIcon"

    private val ICON_NAME_HINT = Regex(
        ".*/(mipmap|drawable)[^/]*/.*(ic_launcher|launcher_icon|app_icon|ic_app|app_logo|ic_logo).*\\.png$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Applies a hue rotation (in degrees) to launcher icon PNG entries of [apkFile]
     * in place. Never throws; failures are logged and leave the APK untouched.
     *
     * @param entries explicit entry names to recolor; when empty, the patcher
     *                discovers PNG entries under mipmap and drawable icon names.
     */
    fun applyIconHueShift(apkFile: File, entries: List<String>, hueDeg: Float) {
        runCatching {
            if (hueDeg == 0f || !apkFile.isFile) return
            val allEntries: List<RawZipEntry> = ApkZipMapper.listEntries(apkFile)
            val wanted: Set<String> = if (entries.isNotEmpty()) {
                entries.toSet().intersect(allEntries.map { it.name }.toSet())
            } else {
                discoverIconEntryNames(allEntries)
            }
            if (wanted.isEmpty()) return

            val matrix = hueRotationMatrix(hueDeg)
            val replaced = HashMap<String, ByteArray>()

            for (entry in allEntries) {
                val name = entry.name
                if (name !in wanted) continue
                if (!name.endsWith(".png") || name.endsWith(".9.png")) continue
                val bytes = readEntryBytes(apkFile, entry)
                    ?: continue
                val shifted = recolorPng(bytes, matrix) ?: continue
                replaced[name] = shifted
            }
            if (replaced.isEmpty()) return

            // Rebuild the APK in place (temp file + atomic rename); matching PNG
            // entry bytes are re-deflated by the zip mapper while all other
            // entries are streamed through untouched.
            val tmp = File(apkFile.parentFile, apkFile.name + ".icon_tmp_" + System.nanoTime())
            ApkZipMapper.rebuild(
                sourceApk = apkFile,
                outputFile = tmp,
                remove = { name -> name.startsWith("META-INF/") && isSignatureEntry(name) },
                replaced = replaced,
                appended = emptyList()
            )
            if (!ApkZipMapper.renameFile(tmp, apkFile)) {
                tmp.delete()
                Log.w(TAG, "Unable to swap hue-shifted APK into place: $apkFile")
            }
        }.onFailure { t ->
            Log.w(TAG, "Icon hue shift skipped: ${t.message}")
        }
    }

    /** Discovery of launcher icon PNG entries (best effort). */
    fun discoverIconEntryNames(entries: List<RawZipEntry>): Set<String> {
        val result = HashSet<String>()
        for (entry in entries) {
            val name = entry.name
            if (!name.endsWith(".png") || name.endsWith(".9.png")) continue
            val lower = name.lowercase()
            if (lower.startsWith("res/mipmap") || ICON_NAME_HINT.matches(name)) {
                result.add(name)
            }
        }
        return result
    }

    private fun isSignatureEntry(name: String): Boolean {
        return name.endsWith(".SF") || name.endsWith(".RSA") ||
            name.endsWith(".DSA") || name.endsWith(".EC")
    }

    private fun readEntryBytes(apkFile: File, entry: RawZipEntry): ByteArray? {
        return runCatching {
            java.io.RandomAccessFile(apkFile, "r").use { raf ->
                val bytes = ByteArray(entry.compressedSize.toInt())
                raf.seek(entry.dataOffset)
                raf.readFully(bytes)
                bytes
            }
        }.getOrNull()
    }

    /**
     * Decodes the PNG, applies the hue rotation in ARGB_8888 space and
     * re-encodes as PNG. Returns null when decoding fails.
     */
    private fun recolorPng(pngBytes: ByteArray, matrix: ColorMatrix): ByteArray? {
        return runCatching {
            val source = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return null
            try {
                val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(source, 0f, 0f, paint)

                val encoded = java.io.ByteArrayOutputStream()
                output.compress(Bitmap.CompressFormat.PNG, 100, encoded)
                output.recycle()
                encoded.toByteArray()
            } finally {
                source.recycle()
            }
        }.getOrNull()
    }

    /** Site-preserving hue rotation matrix (classic gskinner formulation). */
    internal fun hueRotationMatrix(degrees: Float): ColorMatrix {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lumR = 0.213f
        val lumG = 0.715f
        val lumB = 0.072f
        val mat = floatArrayOf(
            // R row
            lumR + c * (1 - lumR) + s * (-lumR),
            lumG + c * (-lumG) + s * (-lumG),
            lumB + c * (-lumB) + s * (1 - lumB),
            0f, 0f,
            // G row
            lumR + c * (-lumR) + s * (0.143f),
            lumG + c * (1 - lumG) + s * (0.140f),
            lumB + c * (-lumB) + s * (-0.283f),
            0f, 0f,
            // B row
            lumR + c * (-lumR) + s * (-(1 - lumR)),
            lumG + c * (-lumG) + s * lumG,
            lumB + c * (1 - lumB) + s * lumB,
            0f, 0f,
            // A row untouched
            0f, 0f, 0f, 1f, 0f
        )
        return ColorMatrix(mat)
    }
}

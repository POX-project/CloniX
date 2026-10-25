package dev.cloneforge.patcher.ingest

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Result of Phase 1 ingestion (spec section 2): staged copies of the original
 * base APK plus every split APK, the original X.509 DER certificate and the raw
 * V2/V3 APK Signing Block of the original base APK.
 */
data class IngestedAppBundle(
    val originalPackageName: String,
    val versionCode: Long,
    val versionName: String,
    val baseApk: File,
    val splitApks: List<File>,
    val originalCertDer: ByteArray,
    val rawSigningBlock: ByteArray?,
    val stagingDir: File
)

/**
 * Ingests an installed package (base + splits) into a private staging
 * directory and extracts signature material from it.
 */
class ApkIngestor(private val context: Context) {

    companion object {
        private const val SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L // "APK Sig "
        private const val SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L // "Block 42"
        private const val EOCD_SIGNATURE = 0x06054b50
    }

    fun ingestInstalledPackage(targetPackage: String, cloneIndex: Int): IngestedAppBundle {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNING_CERTIFICATES or
            PackageManager.GET_SIGNATURES

        @Suppress("DEPRECATION")
        val pkgInfo: PackageInfo = pm.getPackageInfo(targetPackage, flags)
        val appInfo: ApplicationInfo = pkgInfo.applicationInfo
            ?: throw IllegalStateException("ApplicationInfo is null for $targetPackage")

        val stagingDir = File(context.cacheDir, "staging/${targetPackage}_cf${cloneIndex.coerceAtLeast(0)}").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        // 1. Copy base.apk
        val sourceBase = File(appInfo.sourceDir)
        val stagedBase = File(stagingDir, "base.apk")
        sourceBase.copyTo(stagedBase, overwrite = true)

        // 2. Copy all split APKs (ABI splits, density splits, dynamic feature splits)
        val stagedSplits = mutableListOf<File>()
        appInfo.splitSourceDirs?.forEach { splitPath ->
            val srcSplit = File(splitPath)
            val dstSplit = File(stagingDir, srcSplit.name)
            srcSplit.copyTo(dstSplit, overwrite = true)
            stagedSplits.add(dstSplit)
        }

        // 3. Extract raw X.509 DER certificate bytes
        val certBytes = extractCertificateBytes(pkgInfo)

        // 4. Extract raw V2/V3 APK Signing Block from the original base.apk
        val signingBlock = extractRawApkSigningBlock(sourceBase)

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }

        return IngestedAppBundle(
            originalPackageName = targetPackage,
            versionCode = versionCode,
            versionName = pkgInfo.versionName ?: "1.0",
            baseApk = stagedBase,
            splitApks = stagedSplits,
            originalCertDer = certBytes,
            rawSigningBlock = signingBlock,
            stagingDir = stagingDir
        )
    }

    private fun extractCertificateBytes(pkgInfo: PackageInfo): ByteArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            if (signingInfo != null) {
                val signers = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                if (!signers.isNullOrEmpty()) {
                    return signers[0].toByteArray()
                }
            }
        }
        @Suppress("DEPRECATION")
        val legacySigs = pkgInfo.signatures
        if (!legacySigs.isNullOrEmpty()) {
            return legacySigs[0].toByteArray()
        }
        throw IllegalStateException("Unable to extract X.509 certificate for ${pkgInfo.packageName}")
    }

    /**
     * Extracts the entire raw APK Signing Block (including 8-byte header size, ID-value pairs,
     * 8-byte footer size, and 16-byte magic) from the original APK so native pread64/openat
     * hooks can synthesize or redirect signature block inspections.
     */
    fun extractRawApkSigningBlock(apkFile: File): ByteArray? {
        RandomAccessFile(apkFile, "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength < 22) return null

            val maxCommentLength = minOf(65535L, fileLength - 22)
            val searchBufSize = (maxCommentLength + 22).toInt()
            val searchBuf = ByteBuffer.allocate(searchBufSize).order(ByteOrder.LITTLE_ENDIAN)

            raf.seek(fileLength - searchBufSize)
            raf.readFully(searchBuf.array())

            var eocdOffsetInBuf = -1
            for (i in searchBufSize - 22 downTo 0) {
                if (searchBuf.getInt(i) == EOCD_SIGNATURE) {
                    val commentLen = searchBuf.getShort(i + 20).toInt() and 0xFFFF
                    if (i + 22 + commentLen == searchBufSize) {
                        eocdOffsetInBuf = i
                        break
                    }
                }
            }
            if (eocdOffsetInBuf == -1) return null

            val centralDirOffset = searchBuf.getInt(eocdOffsetInBuf + 16).toLong() and 0xFFFFFFFFL
            if (centralDirOffset < 32) return null

            // Read 24 bytes immediately before Central Directory
            val footerBuf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            raf.seek(centralDirOffset - 24)
            raf.readFully(footerBuf.array())

            val blockSizeExcludingInitial8 = footerBuf.getLong(0)
            val magicLo = footerBuf.getLong(8)
            val magicHi = footerBuf.getLong(16)

            if (magicLo != SIG_BLOCK_MAGIC_LO || magicHi != SIG_BLOCK_MAGIC_HI) {
                return null
            }

            val totalBlockSize = (blockSizeExcludingInitial8 + 8).toInt()
            val sigBlockOffset = centralDirOffset - totalBlockSize
            if (sigBlockOffset < 0) return null

            val rawBlock = ByteArray(totalBlockSize)
            raf.seek(sigBlockOffset)
            raf.readFully(rawBlock)
            return rawBlock
        }
    }
}

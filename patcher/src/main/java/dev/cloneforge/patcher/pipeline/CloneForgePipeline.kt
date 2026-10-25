package dev.cloneforge.patcher.pipeline

import android.content.Context
import android.os.Build
import android.util.Log
import dev.cloneforge.patcher.CfConstants
import dev.cloneforge.patcher.icon.IconPatcher
import dev.cloneforge.patcher.ingest.ApkIngestor
import dev.cloneforge.patcher.inject.PayloadInjector
import dev.cloneforge.patcher.installer.ClonePackageInstaller
import dev.cloneforge.patcher.manifest.ManifestAndArscMutator
import dev.cloneforge.patcher.sign.ApkAlignAndSignEngine
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Blocking result of a full clone build.
 */
data class CloneResult(
    val mutatedPackage: String?,
    val sessionId: Int,
    val success: Boolean,
    val message: String
)

/**
 * CloneForge builder pipeline orchestrator (spec 1.2, phases 1-6):
 *
 *  1. ingest        - spec 2 (ingestion, split resolution, signing block)
 *  2. mutate        - spec 3 (manifest + resources.arsc mutation of base & splits)
 *  3. payload       - spec 4/6.2/6.3 (dex, cloneforge assets, native libs)
 *  4. icon          - launcher hue shift (spec 1.2 phase 3)
 *  5. sign          - spec 7.2 (aligned V1/V2/V3 signing)
 *  6. install       - spec 7.3 (atomic multi-APK PackageInstaller session)
 *
 * The blocking API is called from the app layer on a background thread.
 */
class CloneForgePipeline(
    private val context: Context,
    private val onProgress: (percent: Int, message: String) -> Unit
) {

    companion object {
        private const val TAG = "CloneForgePipeline"

        private val ABI_PREFERENCE = CfConstants.PREFERRED_ABIS

        private const val PROGRESS_INGEST = 5
        private const val PROGRESS_MUTATE = 25
        private const val PROGRESS_PAYLOAD = 50
        private const val PROGRESS_ICON = 60
        private const val PROGRESS_SIGN = 80
        private const val PROGRESS_INSTALL = 95
        private const val PROGRESS_DONE = 100
    }

    /** Builds and installs a clone of [targetPackage]. Blocking call. */
    fun buildAndInstall(
        targetPackage: String,
        cloneIndex: Int,
        appLabel: String,
        hueShiftDeg: Float,
        silent: Boolean
    ): CloneResult {
        val cloneSuffix = CfConstants.cloneSuffix(cloneIndex)
        val mutatedPackage = "$targetPackage.$cloneSuffix"
        var stagingDir: File? = null
        try {
            // ------------------------------------------------------------
            // Phase 1: Ingestion (spec 2)
            // ------------------------------------------------------------
            progress(PROGRESS_INGEST, String.format(Locale.US, "Ingesting %s", targetPackage))
            val bundle = ApkIngestor(context).ingestInstalledPackage(targetPackage, cloneIndex)
            stagingDir = bundle.stagingDir

            // Keep a pristine copy of the ORIGINAL, unmutated base.apk for the
            // native openat() redirect hook (spec 6.2 rule 1) before mutation.
            val originApkBin = File(stagingDir, "origin_apk.bin")
            bundle.baseApk.copyTo(originApkBin, overwrite = true)

            // ------------------------------------------------------------
            // Phase 2: Manifest + resources.arsc mutation (spec 3)
            // ------------------------------------------------------------
            progress(PROGRESS_MUTATE, "Mutating manifest")
            val mutator = ManifestAndArscMutator()
            val mutation = mutator.mutateBaseApk(
                baseApkFile = bundle.baseApk,
                origPkg = bundle.originalPackageName,
                clonePkg = mutatedPackage,
                cloneSuffix = cloneSuffix,
                customAppLabel = appLabel
            )
            val authorityMap = mutation.authorityMap.toMutableMap()
            val permissionMap = mutation.permissionMap.toMutableMap()

            // Splits are only touched at the manifest level; their ZIP entries
            // otherwise stay untouched.
            for (split in bundle.splitApks) {
                mutator.mutateSplitApk(
                    splitApkFile = split,
                    origPkg = bundle.originalPackageName,
                    clonePkg = mutatedPackage,
                    cloneSuffix = cloneSuffix,
                    authorityMap = authorityMap,
                    permissionMap = permissionMap
                )
            }

            // ------------------------------------------------------------
            // Phase 3: Assemble payload directory (spec 4, 6.2)
            // ------------------------------------------------------------
            progress(35, "Assembling compat runtime payload")
            val payloadDir = File(stagingDir, "payload")
            val payloadDex = writeRuntimePayloadDex(payloadDir)
            val configJson = buildConfigJson(
                bundle.originalPackageName, mutatedPackage, cloneSuffix,
                authorityMap, permissionMap, payloadDir
            )
            val originCert = File(payloadDir, "origin_cert.der")
            originCert.outputStream().use { output -> output.write(bundle.originalCertDer) }

            val nativeLibs = collectNativeLibs(payloadDir)

            // ------------------------------------------------------------
            // Phase 4: Icon hue shift + payload injection (spec 4.1 / 6.2 / 7.1)
            // ------------------------------------------------------------
            progress(PROGRESS_ICON, "Applying launcher icon adjustments")
            val effectiveHue = if (hueShiftDeg < 0f) {
                // Auto mode: rotate 120 degrees per clone instance.
                (cloneIndex.coerceAtLeast(0) % 30) * CfConstants.DEFAULT_HUE_DEG
            } else {
                hueShiftDeg
            }
            if (effectiveHue != 0f) {
                IconPatcher.applyIconHueShift(bundle.baseApk, emptyList(), effectiveHue)
                for (split in bundle.splitApks) {
                    IconPatcher.applyIconHueShift(split, emptyList(), effectiveHue)
                }
            }

            progress(PROGRESS_PAYLOAD, "Injecting runtime payload")
            val injectedBase = File(stagingDir, "base.injected.apk")
            val extraAssets = mapOf(
                CfConstants.APK_ASSET_CONFIG to configJson,
                CfConstants.APK_ASSET_CERT to originCert,
                CfConstants.APK_ASSET_ORIGIN_APK to originApkBin,
            )
            PayloadInjector.inject(
                targetApk = bundle.baseApk,
                outApk = injectedBase,
                payloadDex = payloadDex,
                extraAssets = extraAssets,
                nativeLibs = nativeLibs
            )
            val injectedSplits = bundle.splitApks.map { it } // splits zip-untouched

            // ------------------------------------------------------------
            // Phase 5: V1/V2/V3 signing with persistent per-target key (spec 7.2)
            // ------------------------------------------------------------
            progress(PROGRESS_SIGN, "Signing clone APK")
            val engine = ApkAlignAndSignEngine(File(context.filesDir, "cloneforge/keystore"))
            val aliasKey = aliasForPkg(bundle.originalPackageName)
            val signerConfig = engine.getOrCreateSignerConfig(aliasKey)

            val signedDir = File(stagingDir, "signed").apply { mkdirs() }
            val signedBase = File(signedDir, "base.apk")
            engine.alignAndSignApk(injectedBase, signedBase, signerConfig, minSdkVersion = 28)
            val signedSplits = ArrayList<File>(injectedSplits.size)
            for (split in injectedSplits) {
                val signedSplit = File(signedDir, split.name)
                engine.alignAndSignApk(split, signedSplit, signerConfig, minSdkVersion = 28)
                signedSplits.add(signedSplit)
            }

            // ------------------------------------------------------------
            // Phase 6: Atomic multi-APK install session (spec 7.3)
            // ------------------------------------------------------------
            progress(PROGRESS_INSTALL, "Installing…")
            val commit = ClonePackageInstaller(context).installCloneBundle(
                mutatedPackageName = mutatedPackage,
                signedBaseApk = signedBase,
                signedSplitApks = signedSplits,
                silent = silent
            )
            progress(PROGRESS_DONE, "Clone installation session committed")

            return CloneResult(mutatedPackage, commit.sessionId, true,
                "Clone $mutatedPackage submitted for installation")
        } catch (t: Throwable) {
            Log.e(TAG, "Clone build failed: ${t.message}", t)
            return CloneResult(mutatedPackage, -1, false, t.message ?: "Clone build failed")
        } finally {
            // Staging (including the signed APK bytes already streamed into the
            // session) can be discarded whether the build succeeded or not.
            stagingDir?.deleteRecursively()
        }
    }

    /** Forwards progress to the caller; never throws. */
    private fun progress(percent: Int, message: String) {
        runCatching { onProgress(percent.coerceIn(0, 100), message) }
    }

    // --------------------------------------------------------------
    // Payload assembly
    // --------------------------------------------------------------

    /**
     * Loads cloneforge/runtime-payload.dex from the host app's assets
     * (packed by CI) into the staging payload directory.
     */
    private fun writeRuntimePayloadDex(payloadDir: File): File {
        val outFile = File(payloadDir, "runtime-payload.dex")
        val assets = context.assets
        val assetList = try {
            assets.list(CfConstants.ASSET_ROOT)?.toList() ?: emptyList()
        } catch (ignored: IOException) {
            emptyList()
        }
        if (!assetList.contains("runtime-payload.dex")) {
            throw IllegalStateException(
                "Missing asset cloneforge/runtime-payload.dex - the CI pipeline packs " +
                    "the precompiled compatibility runtime into the host APK; rebuild the app."
            )
        }
        outFile.parentFile?.mkdirs()
        assets.open(CfConstants.ASSET_RUNTIME_DEX).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        return outFile
    }

    private fun buildConfigJson(
        origPkg: String,
        clonePkg: String,
        cloneSuffix: String,
        authorityMap: Map<String, String>,
        permissionMap: Map<String, String>,
        payloadDir: File,
    ): File {
        val authorityJson = JSONObject()
        for ((k, v) in authorityMap) authorityJson.put(k, v)
        val permissionJson = JSONObject()
        for ((k, v) in permissionMap) permissionJson.put(k, v)
        val config = JSONObject()
            .put("engine_version", CfConstants.ENGINE_VERSION)
            .put("orig_pkg", origPkg)
            .put("clone_pkg", clonePkg)
            .put("clone_suffix", cloneSuffix)
            .put("authority_map", authorityJson)
            .put("permission_map", permissionJson)
        val outFile = File(payloadDir, "config.json")
        outFile.parentFile?.mkdirs()
        outFile.writeText(config.toString(), Charsets.UTF_8)
        return outFile
    }

    /**
     * Picks native libraries for the current device ABI. Preference order is
     * arm64-v8a > armeabi-v7a > x86_64, intersected with Build.SUPPORTED_ABIS.
     * Libraries come from host assets at cloneforge/native/<abi>/lib*.so.
     */
    private fun collectNativeLibs(payloadDir: File): Map<String, List<File>> {
        val result = LinkedHashMap<String, List<File>>()
        val supported = Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        val ordered = (ABI_PREFERENCE.filter { it in supported } +
            supported.filter { it !in ABI_PREFERENCE })
        val assets = context.assets

        for (abi in ordered) {
            val assetDir = "${CfConstants.ASSET_NATIVE_ROOT}/$abi"
            val libs = try {
                (assets.list(assetDir)?.toList() ?: emptyList()).filter { it.endsWith(".so") }
            } catch (ignored: IOException) {
                emptyList()
            }
            if (libs.isEmpty()) continue
            val dir = File(payloadDir, "native/$abi").apply { mkdirs() }
            val files = ArrayList<File>(libs.size)
            for (lib in libs) {
                val file = File(dir, lib)
                assets.open("$assetDir/$lib").use { input ->
                    file.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                files.add(file)
            }
            result[abi] = files
            // Only inject one primary ABI plus compatible others actually
            // present in the host assets; stop after the first preferred match
            // to keep clone APK size small.
            break
        }
        return result
    }

    /** Keystore alias derived from the original package name (keystore-legal chars only). */
    private fun aliasForPkg(pkg: String): String =
        pkg.map { c -> if (c == '.' || c == '-') '_' else c }.joinToString("")
}

package dev.cloneforge.patcher.inject

import dev.cloneforge.patcher.zip.ApkZipMapper
import dev.cloneforge.patcher.zip.NewEntry
import java.io.File

/**
 * Injects the CloneForge compatibility runtime into an already mutated base APK:
 *
 *  - appends the payload dex as the next classesN.dex (spec 4.1 multi-dex strategy),
 *  - adds the assets cloneforge payload files (config.json, origin_cert.der, origin_apk.bin),
 *  - adds lib/ABI .so files stored uncompressed with a 16384-byte data offset
 *    alignment boundary (spec 7.1).
 *
 * All existing META-INF signature pairs (.SF, .RSA, .DSA, .EC) are dropped so the
 * final signature is rebuilt cleanly by ApkAlignAndSignEngine.
 */
object PayloadInjector {

    private val SIGNATURE_SUFFIXES = listOf(".SF", ".RSA", ".DSA", ".EC")

    /**
     * @param targetApk   mutated (but unsigned) base APK to read
     * @param outApk      destination APK with the payload injected
     * @param payloadDex  runtime-payload.dex (pre-compiled by CI)
     * @param extraAssets entry-name to file mapping of assets to add, e.g.
     *                    "assets/cloneforge/config.json" mapping to the config file
     * @param nativeLibs  per-ABI list of .so files, injected under lib/<abi>/
     */
    fun inject(
        targetApk: File,
        outApk: File,
        payloadDex: File,
        extraAssets: Map<String, File>,
        nativeLibs: Map<String, List<File>>,
    ) {
        val entries = ApkZipMapper.listEntries(targetApk)
        val existingNames = entries.map { it.name }.toHashSet()

        val appended = ArrayList<NewEntry>()

        // (a) Append the runtime dex as classes<N+1>.dex, aligned to 4 bytes.
        if (payloadDex.isFile) {
            val highest = ApkZipMapper.highestDexIndex(entries)
            val dexBytes = payloadDex.readBytes()
            appended.add(
                NewEntry(ApkZipMapper.dexNameForIndex(highest + 1), dexBytes,
                    ApkZipMapper.METHOD_STORED, 4)
            )
        }

        // (b) assets/cloneforge payload entries - deflate them (config.json and
        // the origin APK copy compress well).
        for ((entryName, file) in extraAssets) {
            if (!file.isFile) continue
            appended.add(NewEntry(entryName, file.readBytes(), ApkZipMapper.METHOD_DEFLATED, 0))
        }

        // (c) lib/ABI .so entries - stored uncompressed with 16 KB alignment.
        for ((abi, libs) in nativeLibs) {
            for (file in libs) {
                if (!file.isFile) continue
                val entryName = "lib/$abi/${file.name}"
                appended.add(
                    NewEntry(entryName, file.readBytes(), ApkZipMapper.METHOD_STORED, 16384)
                )
            }
        }

        // Drop old V1 signature pairs; the final signature is rebuilt by apksig.
        val remove: (String) -> Boolean = { name ->
            name.startsWith("META-INF/") &&
                SIGNATURE_SUFFIXES.any { name.endsWith(it) }
        }

        // Guard: never append a duplicate entry name (ZIP readers pick one and
        // PackageInstaller scans would complain).
        val clash = appended.firstOrNull { existingNames.contains(it.name) }
        require(clash == null) {
            "Cannot add duplicate entry: ${clash!!.name} already exists in ${targetApk.name}"
        }

        outApk.parentFile?.mkdirs()
        ApkZipMapper.rebuild(targetApk, outApk, remove = remove, appended = appended)
    }
}

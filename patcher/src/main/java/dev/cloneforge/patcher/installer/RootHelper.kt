package dev.cloneforge.patcher.installer

import android.util.Log
import java.io.File

/**
 * Root installation helper using Magisk/APatch/KernelSU su / pm CLI.
 * Stages APKs in /data/local/tmp with 0777 permissions so system_server can read them,
 * allowing silent background installation of single-APK or multi-APK clone bundles.
 */
object RootHelper {

    private const val TAG = "RootHelper"

    /**
     * Checks if root access (su with uid=0) is available and granted.
     */
    fun isRootAvailable(): Boolean {
        return runCatching {
            val suBins = listOf("su", "/system/bin/su", "/system/xbin/su", "/data/adb/ap/bin/su")
            for (bin in suBins) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf(bin, "-c", "id"))
                    val exitCode = process.waitFor()
                    val output = process.inputStream.bufferedReader().readText()
                    if (exitCode == 0 && output.contains("uid=0")) {
                        Log.i(TAG, "Root detected via $bin: $output")
                        return true
                    }
                } catch (_: Exception) {
                    // Try next binary
                }
            }
            false
        }.getOrDefault(false)
    }

    /**
     * Installs [baseApk] and optional [splitApks] silently via su.
     * Uses /data/local/tmp so system_server can parse the APKs without EACCES.
     */
    fun installApk(baseApk: File, splitApks: List<File> = emptyList()): Boolean {
        val tmpDir = "/data/local/tmp/clonix_inst_${System.currentTimeMillis()}"
        return runCatching {
            val suCmd = getSuCommand() ?: "su"

            // 1. Create temp directory in /data/local/tmp and make it accessible to system_server
            execSu(suCmd, "mkdir -p '$tmpDir' && chmod 777 '$tmpDir'")

            // 2. Copy base APK
            val targetBase = "$tmpDir/base.apk"
            execSu(suCmd, "cp '${baseApk.absolutePath}' '$targetBase' && chmod 777 '$targetBase'")

            // Copy splits if any
            val targetSplits = mutableListOf<String>()
            for ((idx, split) in splitApks.withIndex()) {
                val targetSplit = "$tmpDir/split_$idx.apk"
                execSu(suCmd, "cp '${split.absolutePath}' '$targetSplit' && chmod 777 '$targetSplit'")
                targetSplits.add(targetSplit)
            }

            val success = if (targetSplits.isEmpty()) {
                val cmd = "pm install -r -d -t '$targetBase'"
                Log.i(TAG, "Executing single-APK root install: $cmd")
                val (exitCode, output) = execSu(suCmd, cmd)
                Log.i(TAG, "pm install exitCode=$exitCode, output=$output")
                exitCode == 0 && (output.contains("Success") || !output.contains("Failure"))
            } else {
                Log.i(TAG, "Executing multi-APK root install session for ${targetSplits.size + 1} APKs")
                val (_, createOut) = execSu(suCmd, "pm install-create -r -d -t")
                val match = Regex("\\[(\\d+)\\]").find(createOut)
                if (match == null) {
                    Log.e(TAG, "Failed to parse session ID from: $createOut")
                    false
                } else {
                    val sessionId = match.groupValues[1]
                    val all = listOf(targetBase) + targetSplits
                    var writeOk = true
                    for ((idx, apkPath) in all.withIndex()) {
                        val splitName = if (idx == 0) "base" else "split_$idx"
                        val (wExit, _) = execSu(suCmd, "pm install-write $sessionId $splitName '$apkPath'")
                        if (wExit != 0) {
                            writeOk = false
                            break
                        }
                    }
                    if (writeOk) {
                        val (cExit, cOut) = execSu(suCmd, "pm install-commit $sessionId")
                        Log.i(TAG, "install-commit exitCode=$cExit, output=$cOut")
                        cExit == 0 && cOut.contains("Success")
                    } else {
                        execSu(suCmd, "pm install-abandon $sessionId")
                        false
                    }
                }
            }

            // Clean up staging directory
            execSu(suCmd, "rm -rf '$tmpDir'")
            success
        }.getOrElse { t ->
            Log.e(TAG, "Root install threw exception", t)
            runCatching { execSu("su", "rm -rf '$tmpDir'") }
            false
        }
    }

    /**
     * Uninstalls [packageName] silently via su.
     */
    fun uninstallPackage(packageName: String): Boolean {
        return runCatching {
            val suCmd = getSuCommand() ?: "su"
            val (exitCode, output) = execSu(suCmd, "pm uninstall '$packageName'")
            Log.i(TAG, "Root uninstall $packageName: exitCode=$exitCode, output=$output")
            exitCode == 0 && output.contains("Success")
        }.getOrDefault(false)
    }

    private fun getSuCommand(): String? {
        val suBins = listOf("su", "/system/bin/su", "/system/xbin/su", "/data/adb/ap/bin/su")
        for (bin in suBins) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(bin, "-c", "id"))
                if (p.waitFor() == 0) return bin
            } catch (_: Exception) {}
        }
        return null
    }

    private fun execSu(suBin: String, shellCmd: String): Pair<Int, String> {
        val process = Runtime.getRuntime().exec(arrayOf(suBin, "-c", shellCmd))
        val output = process.inputStream.bufferedReader().readText() +
                process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return Pair(exitCode, output)
    }
}

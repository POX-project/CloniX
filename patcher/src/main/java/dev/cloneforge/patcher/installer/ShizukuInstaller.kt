package dev.cloneforge.patcher.installer

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File
import java.lang.reflect.Method

/**
 * Shizuku / ADB silent installer bridge (Spec §7.3 & Roadmap #2).
 *
 * Utilizes Shizuku's privileged shell (uid 2000 / adb) to execute package
 * installations silently without user confirmation prompts, device owner requirements,
 * or root access.
 *
 * Streams APK bytes directly via stdin into `pm install-write -S <bytes> <session> <name> -`
 * to eliminate disk staging overhead and avoid SELinux access violations.
 */
object ShizukuInstaller {

    private const val TAG = "ShizukuInstaller"

    private val newProcessMethod: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    /**
     * Executes a process with Shizuku's privileged shell environment (uid 2000).
     */
    fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        return newProcessMethod.invoke(null, cmd, env, dir) as Process
    }

    /**
     * Checks if the Shizuku server binder is alive and responding.
     */
    fun isShizukuAvailable(): Boolean {
        return runCatching {
            Shizuku.pingBinder()
        }.getOrDefault(false)
    }

    /**
     * Checks if Shizuku is alive and this application has been granted permission.
     */
    fun isShizukuPermissionGranted(): Boolean {
        return runCatching {
            if (!isShizukuAvailable()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * Requests Shizuku authorization from the user if Shizuku is running.
     */
    fun requestPermission(requestCode: Int = 1001) {
        runCatching {
            if (isShizukuAvailable() && !isShizukuPermissionGranted()) {
                Shizuku.requestPermission(requestCode)
            }
        }.onFailure { t ->
            Log.w(TAG, "Failed to request Shizuku permission", t)
        }
    }

    /**
     * Installs [baseApk] and optional [splitApks] silently via Shizuku's privileged shell.
     * Uses atomic session streaming via `pm install-create / install-write / install-commit`.
     */
    fun installApk(baseApk: File, splitApks: List<File> = emptyList()): Boolean {
        if (!isShizukuPermissionGranted()) {
            Log.e(TAG, "Cannot install via Shizuku: permission not granted or service offline")
            return false
        }

        return runCatching {
            // 1. Create install session
            Log.i(TAG, "Creating Shizuku install session (base + ${splitApks.size} splits)...")
            val createProc = newProcess(
                arrayOf("pm", "install-create", "-r", "-d", "-t"),
                null,
                null
            )
            val createOut = createProc.inputStream.bufferedReader().readText()
            val createExit = createProc.waitFor()

            if (createExit != 0) {
                Log.e(TAG, "pm install-create failed (exit=$createExit): $createOut")
                return false
            }

            val match = Regex("\\[(\\d+)\\]").find(createOut)
            if (match == null) {
                Log.e(TAG, "Failed to parse session ID from pm install-create: $createOut")
                return false
            }
            val sessionId = match.groupValues[1]
            Log.i(TAG, "Shizuku install session created: id=$sessionId")

            var writeSuccess = true

            // 2. Stream base APK via stdin
            writeSuccess = streamApkToSession(sessionId, "base", baseApk)

            // 3. Stream split APKs if any
            if (writeSuccess) {
                for ((idx, split) in splitApks.withIndex()) {
                    val splitName = "split_$idx"
                    if (!streamApkToSession(sessionId, splitName, split)) {
                        writeSuccess = false
                        break
                    }
                }
            }

            // 4. Commit or abandon session
            if (writeSuccess) {
                Log.i(TAG, "Committing Shizuku install session $sessionId...")
                val commitProc = newProcess(
                    arrayOf("pm", "install-commit", sessionId),
                    null,
                    null
                )
                val commitOut = commitProc.inputStream.bufferedReader().readText()
                val commitExit = commitProc.waitFor()
                Log.i(TAG, "pm install-commit exitCode=$commitExit, output=$commitOut")
                val success = commitExit == 0 && commitOut.contains("Success")
                if (!success) {
                    abandonSession(sessionId)
                }
                success
            } else {
                abandonSession(sessionId)
                false
            }
        }.getOrElse { t ->
            Log.e(TAG, "Shizuku install threw exception", t)
            false
        }
    }

    /**
     * Uninstalls [packageName] silently via Shizuku.
     */
    fun uninstallPackage(packageName: String): Boolean {
        if (!isShizukuPermissionGranted()) {
            Log.e(TAG, "Cannot uninstall via Shizuku: permission not granted or service offline")
            return false
        }
        return runCatching {
            val proc = newProcess(
                arrayOf("pm", "uninstall", packageName),
                null,
                null
            )
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            Log.i(TAG, "Shizuku uninstall $packageName: exitCode=$exitCode, output=$output")
            exitCode == 0 && output.contains("Success")
        }.getOrDefault(false)
    }

    private fun streamApkToSession(sessionId: String, splitName: String, apkFile: File): Boolean {
        return runCatching {
            val fileSize = apkFile.length()
            Log.i(TAG, "Streaming $splitName ($fileSize bytes) into session $sessionId...")
            val writeProc = newProcess(
                arrayOf("pm", "install-write", "-S", fileSize.toString(), sessionId, splitName, "-"),
                null,
                null
            )

            // Pipe APK bytes into process stdin
            writeProc.outputStream.use { out ->
                apkFile.inputStream().use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
                out.flush()
            }

            val writeOut = writeProc.inputStream.bufferedReader().readText()
            val writeExit = writeProc.waitFor()
            if (writeExit != 0 || (!writeOut.contains("Success") && writeOut.contains("Failure"))) {
                Log.e(TAG, "Failed writing $splitName (exit=$writeExit): $writeOut")
                false
            } else {
                Log.i(TAG, "Successfully wrote $splitName into session $sessionId")
                true
            }
        }.getOrElse { t ->
            Log.e(TAG, "Exception streaming $splitName to session $sessionId", t)
            false
        }
    }

    private fun abandonSession(sessionId: String) {
        runCatching {
            val abandonProc = newProcess(
                arrayOf("pm", "install-abandon", sessionId),
                null,
                null
            )
            abandonProc.waitFor()
        }
    }
}

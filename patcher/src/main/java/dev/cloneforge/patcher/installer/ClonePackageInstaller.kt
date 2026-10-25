package dev.cloneforge.patcher.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.cloneforge.patcher.CfConstants
import java.io.File

/**
 * Result of committing a multi-APK install session.
 */
data class InstallCommit(
    val sessionId: Int,
    val packageName: String
)

/**
 * Atomic multi-APK PackageInstaller dispatcher (spec 7.3).
 *
 * All signed APKs (base + splits) go into a single session and are committed
 * atomically. The commit status is reported as a broadcast with action
 * [CfConstants.INSTALL_COMMIT_ACTION]; the caller must register a runtime
 * (dynamic) BroadcastReceiver matching [CfConstants.EXTRA_SESSION_ID] /
 * [CfConstants.EXTRA_STATUS] before calling [installCloneBundle].
 */
class ClonePackageInstaller(private val context: Context) {

    fun installCloneBundle(
        mutatedPackageName: String,
        signedBaseApk: File,
        signedSplitApks: List<File>,
        silent: Boolean,
    ): InstallCommit {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(mutatedPackageName)
            if (silent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.use { activeSession ->
            val allApks = listOf(signedBaseApk) + signedSplitApks
            for (apkFile in allApks) {
                apkFile.inputStream().use { input ->
                    activeSession.openWrite(apkFile.name, 0, apkFile.length()).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                        activeSession.fsync(output)
                    }
                }
            }

            val callbackIntent = Intent(CfConstants.INSTALL_COMMIT_ACTION).apply {
                setPackage(context.packageName)
                putExtra(CfConstants.EXTRA_MUTATED_PACKAGE, mutatedPackageName)
                putExtra(CfConstants.EXTRA_SESSION_ID, sessionId)
            }

            // The system attachments EXTRA_STATUS to this PendingIntent, so it
            // must be mutable (official PackageInstaller receiver guidance).
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                pendingFlags
            )

            activeSession.commit(pendingIntent.intentSender)
        }

        return InstallCommit(sessionId, mutatedPackageName)
    }
}

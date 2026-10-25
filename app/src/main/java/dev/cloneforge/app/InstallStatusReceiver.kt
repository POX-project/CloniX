package dev.cloneforge.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat

/**
 * Manifest-declared receiver used as the target of the PackageInstaller
 * PendingIntent (ACTION_INSTALL_COMMIT). Statuses come back on this receiver:
 *
 * - [PackageInstaller.STATUS_PENDING_USER_ACTION] (0): pull the confirm-dialog
 *   Intent out of EXTRA_INTENT and launch it so the user can approve the
 *   install (standard non-device-owner flow).
 * - [PackageInstaller.STATUS_SUCCESS] (-1): post "clone installed".
 * - anything else: surface [PackageInstaller.EXTRA_STATUS_MESSAGE] as failure.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkgContext = context.applicationContext
        CloneForgeApp.ensureChannels(pkgContext)
        val notifications = NotificationManagerCompat.from(pkgContext)

        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val appLabel = intent.getStringExtra(EXTRA_SESSION_LABEL).orEmpty()
        val notifId = if (sessionId > 0) 5000 + sessionId % 10_000 else ID_FALLBACK

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "install confirm required (session=$sessionId)")

                val confirm = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_INTENT, Intent::class.java
                )?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) {
                    try {
                        pkgContext.startActivity(confirm)
                    } catch (t: Throwable) {
                        Log.w(TAG, "cannot open install confirm dialog", t)
                    }
                }
                post(
                    notifications,
                    notifId,
                    NotificationCompat.Builder(pkgContext, CloneForgeApp.CHANNEL_INSTALL_JOBS)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle(
                            pkgContext.getString(R.string.notif_confirm_title, appLabel)
                        )
                        .setContentText(pkgContext.getString(R.string.notif_confirm_text))
                        .setAutoCancel(true)
                        .build()
                )
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "clone install succeeded (session=$sessionId)")
                post(
                    notifications,
                    notifId,
                    NotificationCompat.Builder(pkgContext, CloneForgeApp.CHANNEL_INSTALL_JOBS)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle(
                            pkgContext.getString(R.string.notif_install_success_title, appLabel)
                        )
                        .setContentText(pkgContext.getString(R.string.notif_install_success_text))
                        .setAutoCancel(true)
                        .setContentIntent(openApp(pkgContext))
                        .build()
                )
                CloneBus.post(
                    CloneBus.CloneProgressEvent(
                        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty(),
                        labelPrefix = appLabel,
                        cloneIndex = 0,
                        totalClones = 0,
                        percent = 100,
                        message = pkgContext.getString(R.string.notif_install_success_title, appLabel),
                        finished = true,
                        success = true,
                        mutatedPackage =
                            intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME),
                        sessionId = sessionId
                    )
                )
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: pkgContext.getString(R.string.notif_install_failed_generic)
                Log.w(TAG, "clone install failed (session=$sessionId): $message")
                post(
                    notifications,
                    notifId,
                    NotificationCompat.Builder(pkgContext, CloneForgeApp.CHANNEL_INSTALL_JOBS)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle(
                            pkgContext.getString(R.string.notif_install_failed_title, appLabel)
                        )
                        .setContentText(message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                        .setAutoCancel(true)
                        .build()
                )
                CloneBus.post(
                    CloneBus.CloneProgressEvent(
                        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty(),
                        labelPrefix = appLabel,
                        cloneIndex = 0,
                        totalClones = 0,
                        percent = -1,
                        message = message,
                        finished = true,
                        success = false,
                        mutatedPackage = null,
                        sessionId = sessionId
                    )
                )
            }
        }
    }

    private fun post(manager: NotificationManagerCompat, id: Int, notification: android.app.Notification) {
        try {
            manager.notify(id, notification)
        } catch (t: Throwable) {
            // POST_NOTIFICATIONS may be rejected; the UI still knows via CloneBus.
            Log.w(TAG, "cannot post install-status notification", t)
        }
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        private const val TAG = "InstallStatusReceiver"

        /** Foreground-clone install session status action; targets this receiver explicitly. */
        const val ACTION_INSTALL_COMMIT = "dev.cloneforge.ACTION_INSTALL_COMMIT"

        /** Convenience session-routing extras the pipeline may include. */
        const val EXTRA_SESSION_ID = "dev.cloneforge.EXTRA_SESSION_ID"
        const val EXTRA_SESSION_LABEL = "dev.cloneforge.EXTRA_SESSION_LABEL"
        const val EXTRA_TARGET_PACKAGE = "dev.cloneforge.EXTRA_TARGET_PACKAGE"

        private const val ID_FALLBACK = 5001
    }
}

package dev.cloneforge.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Thin Application class: owns a process-wide [CoroutineScope] and makes sure the
 * notification channels used by [CloneService] and [InstallStatusReceiver] exist.
 */
class CloneForgeApp : Application() {

    /** Application-scoped coroutines; never tied to an Activity or Service lifecycle. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_CLONE_JOBS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CLONE_JOBS,
                    getString(R.string.notif_channel_clone_jobs),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.notif_channel_clone_jobs_desc) }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_INSTALL_JOBS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_INSTALL_JOBS,
                    getString(R.string.notif_channel_install_jobs),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = getString(R.string.notif_channel_install_jobs_desc) }
            )
        }
    }

    companion object {
        const val CHANNEL_CLONE_JOBS = "clone_jobs"
        const val CHANNEL_INSTALL_JOBS = "install_jobs"

        /** Safe to call from any component; idempotent. */
        fun ensureChannels(context: android.content.Context) {
            (context.applicationContext as? CloneForgeApp)?.createNotificationChannels()
        }
    }
}

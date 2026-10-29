package dev.cloneforge.app

import android.app.Notification
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.cloneforge.patcher.pipeline.CloneForgePipeline
import dev.cloneforge.patcher.pipeline.CloneResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service (dataSync) that drives [CloneForgePipeline] for every
 * clone index the user requested and reports progress through [CloneBus].
 * Supports batch cloning across multiple apps sequentially.
 */
class CloneService : LifecycleService() {

    private val notificationManager by lazy { NotificationManagerCompat.from(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        CloneForgeApp.ensureChannels(this)
        goForeground()

        val targets: ArrayList<String> = intent?.getStringArrayListExtra(EXTRA_TARGET_PACKAGES)
            ?: intent?.getStringExtra(EXTRA_TARGET_PACKAGE)?.let { arrayListOf(it) }
            ?: arrayListOf()

        if (targets.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val labelPrefix = intent?.getStringExtra(EXTRA_APP_LABEL)?.takeIf { it.isNotBlank() }
        val cloneCount = intent?.getIntExtra(EXTRA_CLONE_COUNT, 1)?.coerceAtLeast(1) ?: 1
        val addNumberName = intent?.getBooleanExtra(EXTRA_ADD_NUMBER_NAME, true) ?: true
        val addNumberIcon = intent?.getBooleanExtra(EXTRA_ADD_NUMBER_ICON, true) ?: true
        val hueEnabled = intent?.getBooleanExtra(EXTRA_HUE_ENABLED, true) ?: true
        val rootInstall = intent?.getBooleanExtra(EXTRA_ROOT_INSTALL, false) ?: false
        val silent = intent?.getBooleanExtra(EXTRA_SILENT, false) ?: false

        lifecycleScope.launch(Dispatchers.IO) {
            runClones(
                startId = startId,
                targetPackages = targets,
                labelPrefix = labelPrefix,
                cloneCount = cloneCount,
                addNumberName = addNumberName,
                addNumberIcon = addNumberIcon,
                hueEnabled = hueEnabled,
                rootInstall = rootInstall,
                silent = silent
            )
        }
        return START_NOT_STICKY
    }

    private suspend fun runClones(
        startId: Int,
        targetPackages: List<String>,
        labelPrefix: String?,
        cloneCount: Int,
        addNumberName: Boolean,
        addNumberIcon: Boolean,
        hueEnabled: Boolean,
        rootInstall: Boolean,
        silent: Boolean
    ) {
        val history = CloneHistoryStore(this)
        val totalApps = targetPackages.size
        try {
            for ((pkgIdx, targetPackage) in targetPackages.withIndex()) {
                val effectivePrefix = if (totalApps == 1 && !labelPrefix.isNullOrBlank()) {
                    labelPrefix
                } else {
                    defaultLabel(targetPackage)
                }

                for (index in 1..cloneCount) {
                    runCatching {
                        buildOneClone(
                            history = history,
                            targetPackage = targetPackage,
                            labelPrefix = effectivePrefix,
                            index = index,
                            cloneCount = cloneCount,
                            appIndex = pkgIdx + 1,
                            totalApps = totalApps,
                            addNumberName = addNumberName,
                            addNumberIcon = addNumberIcon,
                            hueEnabled = hueEnabled,
                            rootInstall = rootInstall,
                            silent = silent
                        )
                    }.onFailure { t ->
                        Log.e(TAG, "clone #$index of $targetPackage crashed", t)
                        CloneBus.post(
                            CloneBus.CloneProgressEvent(
                                targetPackage = targetPackage,
                                labelPrefix = effectivePrefix,
                                cloneIndex = index,
                                totalClones = cloneCount,
                                percent = -1,
                                message = t.message ?: getString(R.string.notif_install_failed_generic),
                                finished = true,
                                success = false,
                                mutatedPackage = null,
                                sessionId = -1
                            )
                        )
                    }
                }
            }
        } finally {
            withContext(Dispatchers.Main.immediate) {
                stopSelfResult(startId)
            }
        }
    }

    private suspend fun buildOneClone(
        history: CloneHistoryStore,
        targetPackage: String,
        labelPrefix: String,
        index: Int,
        cloneCount: Int,
        appIndex: Int,
        totalApps: Int,
        addNumberName: Boolean,
        addNumberIcon: Boolean,
        hueEnabled: Boolean,
        rootInstall: Boolean,
        silent: Boolean
    ) {
        val contentLabel = if (addNumberName) "$labelPrefix #$index" else labelPrefix
        val badgeText = if (addNumberIcon) "#$index" else null
        val hueShiftDeg = if (hueEnabled) ((index * 137.5f) % 360f) else 0f

        val appTag = if (totalApps > 1) "[$appIndex/$totalApps] " else ""

        val pipeline = CloneForgePipeline(applicationContext) { percent, message ->
            val pct = percent.coerceIn(0, 100)
            CloneBus.post(CloneBus.CloneProgressEvent(
                targetPackage = targetPackage,
                labelPrefix = labelPrefix,
                cloneIndex = index,
                totalClones = cloneCount,
                percent = pct,
                message = "$appTag$message",
                finished = false,
                success = false,
                mutatedPackage = null,
                sessionId = -1
            ))
            updateProgressNotification(pct, contentLabel, message, index, cloneCount, appIndex, totalApps)
        }

        CloneBus.post(CloneBus.CloneProgressEvent(
            targetPackage = targetPackage,
            labelPrefix = labelPrefix,
            cloneIndex = index,
            totalClones = cloneCount,
            percent = 0,
            message = "$appTag${getString(R.string.clone_step_starting)}",
            finished = false,
            success = false,
            mutatedPackage = null,
            sessionId = -1
        ))

        val result: CloneResult = pipeline.buildAndInstall(
            targetPackage = targetPackage,
            cloneIndex = index,
            appLabel = contentLabel,
            hueShiftDeg = hueShiftDeg,
            silent = silent,
            badgeEnabled = addNumberIcon,
            useRoot = rootInstall
        )
        withContext(Dispatchers.Main.immediate) {
            CloneBus.post(CloneBus.CloneProgressEvent(
                targetPackage = targetPackage,
                labelPrefix = labelPrefix,
                cloneIndex = index,
                totalClones = cloneCount,
                percent = 100,
                message = "$appTag${result.message}",
                finished = true,
                success = result.success,
                mutatedPackage = result.mutatedPackage,
                sessionId = result.sessionId,
                apkPath = result.apkPath
            ))

            if (result.success) {
                val mutated = result.mutatedPackage ?: defaultMutated(targetPackage, index)
                history.addClone(
                    originalPackage = targetPackage,
                    clonePackage = mutated,
                    cloneLabel = contentLabel,
                    apkPath = result.apkPath.orEmpty()
                )
                notifyCompletion(
                    notificationId = completionId(result.sessionId, index),
                    title = getString(R.string.notif_clone_installed_title, contentLabel),
                    text = mutated
                )
            } else {
                notifyCompletion(
                    notificationId = completionId(result.sessionId, index),
                    title = getString(R.string.notif_clone_failed_title, contentLabel),
                    text = result.message
                )
            }
        }
    }

    // ---- notifications ------------------------------------------------------

    private fun goForeground() {
        val notification = NotificationFactory(this).build(
            title = getString(R.string.notif_clone_running_title),
            text = getString(R.string.notif_preparing),
            channel = CloneForgeApp.CHANNEL_CLONE_JOBS,
            ongoing = true,
            progress = -1
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_PROGRESS_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_PROGRESS_ID, notification)
        }
    }

    private fun updateProgressNotification(
        percent: Int,
        contentLabel: String,
        message: String,
        index: Int,
        cloneCount: Int,
        appIndex: Int,
        totalApps: Int
    ) {
        val title = if (totalApps > 1) {
            "Batch Cloning ($appIndex of $totalApps apps)"
        } else {
            getString(R.string.notif_clone_running_title)
        }
        val subtitle = if (cloneCount > 1) {
            getString(
                R.string.notif_clone_progress_arg,
                index, cloneCount, contentLabel
            ) + " · " + message
        } else {
            "$contentLabel · $message"
        }

        val notification = NotificationFactory(this).build(
            title = title,
            text = subtitle,
            channel = CloneForgeApp.CHANNEL_CLONE_JOBS,
            ongoing = true,
            progress = percent.coerceIn(0, 100)
        )
        notificationManager.notify(NOTIF_PROGRESS_ID, notification)
    }

    private fun notifyCompletion(notificationId: Int, title: String, text: String) {
        val notification = NotificationFactory(this)
            .build(title, text, CloneForgeApp.CHANNEL_INSTALL_JOBS, ongoing = false, progress = -1)
        runCatching { notificationManager.notify(notificationId, notification) }
            .onFailure { Log.w(TAG, "cannot post install notification", it) }
    }

    private fun defaultLabel(targetPackage: String): String = try {
        val ai: ApplicationInfo = packageManager.getApplicationInfo(targetPackage, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        targetPackage
    }

    companion object {
        private const val TAG = "CloneService"

        // Extras sent by MainActivity.
        const val EXTRA_TARGET_PACKAGE = "dev.cloneforge.EXTRA_TARGET_PACKAGE"
        const val EXTRA_TARGET_PACKAGES = "dev.cloneforge.EXTRA_TARGET_PACKAGES"
        const val EXTRA_APP_LABEL = "dev.cloneforge.EXTRA_APP_LABEL"
        const val EXTRA_CLONE_COUNT = "dev.cloneforge.EXTRA_CLONE_COUNT"
        const val EXTRA_ADD_NUMBER_NAME = "dev.cloneforge.EXTRA_ADD_NUMBER_NAME"
        const val EXTRA_ADD_NUMBER_ICON = "dev.cloneforge.EXTRA_ADD_NUMBER_ICON"
        const val EXTRA_HUE_ENABLED = "dev.cloneforge.EXTRA_HUE_ENABLED"
        const val EXTRA_BADGE_ENABLED = "dev.cloneforge.EXTRA_BADGE_ENABLED"
        const val EXTRA_ROOT_INSTALL = "dev.cloneforge.EXTRA_ROOT_INSTALL"
        const val EXTRA_SILENT = "dev.cloneforge.EXTRA_SILENT"

        const val NOTIF_PROGRESS_ID = 2001

        private val completionIds = AtomicInteger(3000)

        fun defaultMutated(targetPackage: String, index: Int): String =
            "$targetPackage.cf" + index.toString().padStart(3, '0')

        private fun completionId(sessionId: Int, index: Int): Int =
            if (sessionId > 0) 3000 + sessionId % 10_000 else completionIds.incrementAndGet()
    }
}

/**
 * Builds the service notifications: linking to MainActivity, styled for the
 * `clone_jobs` / `install_jobs` channels created in [CloneForgeApp].
 */
private class NotificationFactory(context: CloneService) {
    private val appContext = context.applicationContext

    fun build(
        title: String,
        text: String,
        channel: String,
        ongoing: Boolean,
        progress: Int
    ): Notification {
        val builder = NotificationCompat.Builder(appContext, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(openAppPointer(appContext))
        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        }
        return builder.build()
    }

    private fun openAppPointer(context: android.content.Context): android.app.PendingIntent =
        android.app.PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
}

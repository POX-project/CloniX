package dev.cloneforge.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Simple process-wide event bus for clone progress.
 *
 * [CloneService] publishes here; [MainActivity] collects and can react
 * (e.g. refresh the "Your clones" section) without any explicit wiring.
 * Chosen over a Broadcast Intent to avoid the deprecated LocalBroadcastManager.
 */
object CloneBus {

    data class CloneProgressEvent(
        val targetPackage: String,
        val labelPrefix: String,
        val cloneIndex: Int,
        val totalClones: Int,
        /** Pipeline percent 0..100, or -1 while unknown. */
        val percent: Int,
        val message: String,
        /** True once this clone finished (success or failure). */
        val finished: Boolean,
        val success: Boolean,
        val mutatedPackage: String?,
        val sessionId: Int,
        val apkPath: String? = null
    )

    private val _events = MutableSharedFlow<CloneProgressEvent>(
        replay = 4,
        extraBufferCapacity = 64
    )

    /** Hot stream of progress/finish events; late collectors get the last 4 events. */
    val events: SharedFlow<CloneProgressEvent> = _events

    private val _latest = MutableStateFlow<CloneProgressEvent?>(null)

    /** Last event emitted, for quick state inspection. */
    val latest: StateFlow<CloneProgressEvent?> = _latest

    fun post(event: CloneProgressEvent) {
        _latest.value = event
        _events.tryEmit(event)
    }

    fun clear() {
        _latest.value = null
    }
}

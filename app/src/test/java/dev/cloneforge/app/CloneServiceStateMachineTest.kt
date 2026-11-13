package dev.cloneforge.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloneServiceStateMachineTest {

    @Before
    fun setUp() {
        CloneBus.clear()
    }

    @After
    fun tearDown() {
        CloneBus.clear()
    }

    @Test
    fun initialState_hasNoActiveOrReplayedEvents() {
        assertNull(CloneBus.latest.value)
    }

    @Test
    fun progressTransition_updatesLatestAndEmitsToSubscribers() = runTest {
        val collected = mutableListOf<CloneBus.CloneProgressEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            CloneBus.events.collect { collected.add(it) }
        }

        val progressEvent = CloneBus.CloneProgressEvent(
            targetPackage = "com.target.app",
            labelPrefix = "App",
            cloneIndex = 1,
            totalClones = 3,
            percent = 45,
            message = "Mutating manifest and resources...",
            finished = false,
            success = false,
            mutatedPackage = null,
            sessionId = -1
        )

        CloneBus.post(progressEvent)

        assertEquals(progressEvent, CloneBus.latest.value)
        assertEquals(1, collected.size)
        assertEquals(45, collected[0].percent)
        assertFalse(collected[0].finished)

        job.cancel()
    }

    @Test
    fun terminalSuccessTransition_signalsCompletionWithMutatedPackage() = runTest {
        val collected = mutableListOf<CloneBus.CloneProgressEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            CloneBus.events.collect { collected.add(it) }
        }

        val successEvent = CloneBus.CloneProgressEvent(
            targetPackage = "com.target.app",
            labelPrefix = "App",
            cloneIndex = 1,
            totalClones = 1,
            percent = 100,
            message = "Clone installed successfully",
            finished = true,
            success = true,
            mutatedPackage = "com.target.app.cf001",
            sessionId = 42,
            apkPath = "/data/local/tmp/clone.apk"
        )

        CloneBus.post(successEvent)

        assertEquals(successEvent, CloneBus.latest.value)
        assertEquals(1, collected.size)
        assertTrue(collected[0].finished)
        assertTrue(collected[0].success)
        assertEquals("com.target.app.cf001", collected[0].mutatedPackage)

        job.cancel()
    }

    @Test
    fun terminalFailureTransition_signalsFailureWithoutMutatedPackage() = runTest {
        val failureEvent = CloneBus.CloneProgressEvent(
            targetPackage = "com.target.app",
            labelPrefix = "App",
            cloneIndex = 2,
            totalClones = 2,
            percent = -1,
            message = "INSTALL_FAILED_INVALID_APK",
            finished = true,
            success = false,
            mutatedPackage = null,
            sessionId = -1
        )

        CloneBus.post(failureEvent)

        val latest = CloneBus.latest.value
        assertNotNull(latest)
        assertTrue(latest!!.finished)
        assertFalse(latest.success)
        assertNull(latest.mutatedPackage)
    }

    @Test
    fun clear_resetsLatestAndReplayBuffer() = runTest {
        CloneBus.post(
            CloneBus.CloneProgressEvent(
                targetPackage = "com.target.app",
                labelPrefix = "App",
                cloneIndex = 1,
                totalClones = 1,
                percent = 100,
                message = "Done",
                finished = true,
                success = true,
                mutatedPackage = "com.target.app.cf001",
                sessionId = 1
            )
        )

        assertNotNull(CloneBus.latest.value)

        CloneBus.clear()

        assertNull(CloneBus.latest.value)
        assertEquals(0, CloneBus.events.replayCache.size)
    }
}

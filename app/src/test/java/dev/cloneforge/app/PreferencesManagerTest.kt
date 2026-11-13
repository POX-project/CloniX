package dev.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PreferencesManagerTest {

    private lateinit var fakeContext: FakeContext
    private lateinit var prefsManager: PreferencesManager

    @Before
    fun setUp() {
        fakeContext = FakeContext()
        prefsManager = PreferencesManager(fakeContext)
    }

    @Test
    fun booleanPreferences_defaultToTrue() {
        assertTrue(prefsManager.isNumberInNameEnabled)
        assertTrue(prefsManager.isNumberInIconEnabled)
        assertTrue(prefsManager.isAutoHueEnabled)
        assertTrue(prefsManager.isBadgeIconsEnabled)
    }

    @Test
    fun installModes_defaultToSafeFalseWithoutHardwareBlocking() {
        // Must default to false and not execute blocking su shell or IPC checks
        assertFalse(prefsManager.isRootInstallEnabled)
        assertFalse(prefsManager.isShizukuInstallEnabled)
    }

    @Test
    fun preferenceValues_persistCorrectly() {
        prefsManager.isNumberInNameEnabled = false
        prefsManager.isAutoHueEnabled = false
        prefsManager.isRootInstallEnabled = true

        assertFalse(prefsManager.isNumberInNameEnabled)
        assertFalse(prefsManager.isAutoHueEnabled)
        assertTrue(prefsManager.isRootInstallEnabled)
    }

    @Test
    fun concurrentModifications_threadSafe() {
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until 50) {
                        prefsManager.isNumberInNameEnabled = (i % 2 == 0)
                        prefsManager.isAutoHueEnabled = (i % 2 != 0)
                        val _ = prefsManager.isNumberInNameEnabled
                        val _ = prefsManager.isAutoHueEnabled
                    }
                } catch (e: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Concurrent preference updates timed out", doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(0, errorCount.get())
    }
}

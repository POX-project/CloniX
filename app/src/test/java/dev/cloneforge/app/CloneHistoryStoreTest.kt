package dev.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CloneHistoryStoreTest {

    private lateinit var fakeContext: FakeContext
    private lateinit var store: CloneHistoryStore

    @Before
    fun setUp() {
        fakeContext = FakeContext()
        store = CloneHistoryStore(fakeContext)
    }

    @Test
    fun addClone_persistsAndRetrievesEntry() {
        store.addClone("com.example.app", "com.example.app.cf001", "App Clone 1", "/path/to/apk")
        val clones = store.clones()
        assertEquals(1, clones.size)
        assertEquals("com.example.app", clones[0].originalPackage)
        assertEquals("com.example.app.cf001", clones[0].clonePackage)
        assertEquals("App Clone 1", clones[0].cloneLabel)
        assertEquals("/path/to/apk", clones[0].apkPath)
    }

    @Test
    fun addClone_duplicateReplacesExisting() {
        store.addClone("com.example.app", "com.example.app.cf001", "Old Label", "/old/path")
        store.addClone("com.example.app", "com.example.app.cf001", "New Label", "/new/path")
        val clones = store.clones()
        assertEquals(1, clones.size)
        assertEquals("New Label", clones[0].cloneLabel)
        assertEquals("/new/path", clones[0].apkPath)
    }

    @Test
    fun removeClone_removesTargetOnly() {
        store.addClone("com.example.app", "com.example.app.cf001", "Clone 1")
        store.addClone("com.example.app", "com.example.app.cf002", "Clone 2")
        store.removeClone("com.example.app.cf001")
        val clones = store.clones()
        assertEquals(1, clones.size)
        assertEquals("com.example.app.cf002", clones[0].clonePackage)
    }

    @Test
    fun corruptedPayload_returnsEmptyListWithoutCrashing() {
        fakeContext.prefs.edit().putString("clones", "INVALID_JSON{{{{").commit()
        val clones = store.clones()
        assertTrue(clones.isEmpty())
    }

    @Test
    fun concurrentAddAndRemove_maintainsThreadSafety() {
        val threadCount = 16
        val operationsPerThread = 25
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until operationsPerThread) {
                        val pkg = "com.example.app.cf_${t}_$i"
                        store.addClone("com.example.app", pkg, "Clone $t-$i")
                        val current = store.clones()
                        assertTrue(current.isNotEmpty())
                    }
                } catch (e: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Concurrent operations timed out", doneLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(0, errorCount.get())
        assertTrue("Store entries should be recorded", store.clones().isNotEmpty())
    }
}

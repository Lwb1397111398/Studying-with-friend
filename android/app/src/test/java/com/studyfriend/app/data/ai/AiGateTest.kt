package com.studyfriend.app.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 体验增量轮：AiGate.awaitBegin 排队语义——空闲即取 / 占用超时 false / 释放后接力 / 挂起可取消 */
class AiGateTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun awaitBegin_freeLockAcquiresImmediately() = runBlocking {
        val g = AiGate()
        assertTrue(g.awaitBegin("讲解", 1_000))
        assertEquals("讲解", g.label.value)
        g.end()
        assertNull(g.label.value)
    }

    @Test
    fun awaitBegin_heldLockTimesOutFalse() = runBlocking {
        val g = AiGate()
        assertTrue(g.tryBegin("粗读"))
        val started = System.currentTimeMillis()
        assertFalse(g.awaitBegin("讲解", 150))
        assertTrue("超时应真实等待", System.currentTimeMillis() - started >= 140)
        assertEquals("超时不得改写持有者标签", "粗读", g.label.value)
        g.end()
    }

    @Test
    fun awaitBegin_acquiresAfterHolderReleases() = runBlocking {
        val g = AiGate()
        assertTrue(g.tryBegin("总结"))
        val acquired = CompletableDeferred<Boolean>()
        scope.launch { acquired.complete(g.awaitBegin("总览", 10_000)) }
        delay(100) // 确保已在排队挂起
        g.end()
        assertTrue(withTimeout(2_000) { acquired.await() })
        assertEquals("接力后标签换人", "总览", g.label.value)
        g.end()
    }

    @Test
    fun awaitBegin_cancellableWhileWaitingNoHalfAcquire() = runBlocking {
        val g = AiGate()
        assertTrue(g.tryBegin("总结"))
        val job = scope.launch { g.awaitBegin("总览", 60_000) }
        delay(100)
        job.cancelAndJoin() // stop 语义：取消排队

        // 锁未被半获得：释放后 tryBegin 仍可直取
        g.end()
        assertTrue(g.tryBegin("讲解"))
        assertEquals("讲解", g.label.value)
        g.end()
    }
}

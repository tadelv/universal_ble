package com.navideck.universal_ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class AndroidDirectConnectQueueTest {
    @Test
    fun connectionAdmissionScenarios() = AndroidDirectConnectQueueScenarios.runAll()

    @Test
    fun deviceIdsAreCaseInsensitiveInsideTheAdmissionOwner() {
        val queue = AndroidDirectConnectQueue(
            post = { },
            onStartFailure = { _, _ -> },
        )

        queue.enqueue("aa:bb:cc:dd:ee:ff") { }

        assertTrue(queue.contains("AA:BB:CC:DD:EE:FF"))
        assertFailsWith<IllegalStateException> {
            queue.enqueue("Aa:Bb:Cc:Dd:Ee:Ff") { }
        }

        queue.cancel("AA:bb:CC:dd:EE:ff")
        assertFalse(queue.contains("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun staleAttemptTokenCannotCancelSameAddressReplacement() {
        val posted = ArrayDeque<() -> Unit>()
        val queue = AndroidDirectConnectQueue(
            post = { posted.add(it) },
            onStartFailure = { _, _ -> },
        )

        val old = queue.enqueue("scale") { queue.completeWithoutGatt(it) }
        posted.removeFirst().invoke()
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, old.state)

        val replacement = queue.enqueue("SCALE") { }
        assertEquals(AndroidDirectConnectQueue.AttemptState.ADMITTED, replacement.state)
        assertFalse(queue.cancel(old))
        assertTrue(queue.isActive(replacement))
        assertTrue(queue.contains("scale"))
    }

    @Test
    fun requestIdCancelsOnlyItsExactAttempt() {
        val posted = ArrayDeque<() -> Unit>()
        val queue = AndroidDirectConnectQueue(
            post = { posted.add(it) },
            onStartFailure = { _, _ -> },
        )

        val old = queue.enqueue("scale", "old") { queue.completeWithoutGatt(it) }
        assertNull(queue.cancel("scale", "stale"))
        assertTrue(queue.isActive(old))
        assertSame(old, queue.cancel("SCALE", "old"))

        val replacement = queue.enqueue("scale", "replacement") { }
        assertNull(queue.cancel("scale", "old"))
        assertTrue(queue.isActive(replacement))
    }

    @Test
    fun reentrantFailureCallbackIsDeliveredExactlyOnce() {
        lateinit var queue: AndroidDirectConnectQueue
        val callbacks = mutableListOf<Long>()
        queue = AndroidDirectConnectQueue(
            post = { it() },
            onStartFailure = { attempt, _ ->
                callbacks.add(attempt.generation)
                queue.fail(attempt, IllegalStateException("reentrant failure"))
            },
        )

        val attempt = queue.enqueue("scale") {
            throw IllegalStateException("connectGatt failed")
        }

        assertEquals(listOf(attempt.generation), callbacks)
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, attempt.state)
        assertNull(queue.activeDeviceId)
    }

    @Test
    fun boundFailureBecomesCancellingBeforeFailureCallback() {
        lateinit var queue: AndroidDirectConnectQueue
        val owner = Any()
        val observedStates = mutableListOf<AndroidDirectConnectQueue.AttemptState>()
        queue = AndroidDirectConnectQueue(
            post = { it() },
            onStartFailure = { attempt, _ ->
                observedStates.add(attempt.state)
                queue.fail(attempt, IllegalStateException("duplicate failure"))
            },
        )

        val attempt = queue.enqueue("scale") {
            queue.bind(it, owner)
            throw IllegalStateException("failure after native allocation")
        }

        assertEquals(
            listOf(AndroidDirectConnectQueue.AttemptState.CANCELLING),
            observedStates,
        )
        assertEquals(AndroidDirectConnectQueue.AttemptState.CANCELLING, attempt.state)
        assertTrue(queue.contains("scale"))
        assertTrue(queue.isCancelled(owner))
        assertTrue(queue.complete(owner))
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, attempt.state)
    }

    @Test
    fun throwingFailureCallbackStillReleasesUnboundLane() {
        val posted = ArrayDeque<() -> Unit>()
        val started = mutableListOf<String>()
        val queue = AndroidDirectConnectQueue(
            post = { posted.add(it) },
            onStartFailure = { _, _ -> throw IllegalStateException("listener failed") },
        )

        val failed = queue.enqueue("A") {
            throw IllegalStateException("start failed")
        }
        queue.enqueue("B") {
            started.add("B")
            queue.completeWithoutGatt(it)
        }

        assertFailsWith<IllegalStateException> { posted.removeFirst().invoke() }
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, failed.state)
        assertEquals("B", queue.activeDeviceId)

        posted.removeFirst().invoke()
        assertEquals(listOf("B"), started)
        assertNull(queue.activeDeviceId)
    }

    @Test
    fun throwingBoundFailureCallbackRetainsNativeBarrier() {
        val posted = ArrayDeque<() -> Unit>()
        val owner = Any()
        val queue = AndroidDirectConnectQueue(
            post = { posted.add(it) },
            onStartFailure = { _, _ -> throw IllegalStateException("listener failed") },
        )

        val failed = queue.enqueue("A") {
            queue.bind(it, owner)
            throw IllegalStateException("failure after native allocation")
        }
        val waiting = queue.enqueue("B") { }

        assertFailsWith<IllegalStateException> { posted.removeFirst().invoke() }
        assertEquals(AndroidDirectConnectQueue.AttemptState.CANCELLING, failed.state)
        assertEquals(AndroidDirectConnectQueue.AttemptState.QUEUED, waiting.state)
        assertEquals("A", queue.activeDeviceId)
        assertEquals(1, queue.pendingCount)

        assertTrue(queue.complete(owner))
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, failed.state)
        assertEquals(AndroidDirectConnectQueue.AttemptState.ADMITTED, waiting.state)
    }

    @Test
    fun repeatedCancellationOfBoundAttemptIsIdempotent() {
        val posted = ArrayDeque<() -> Unit>()
        val owner = Any()
        val queue = AndroidDirectConnectQueue(
            post = { posted.add(it) },
            onStartFailure = { _, _ -> },
        )

        val first = queue.enqueue("A") { queue.bind(it, owner) }
        val second = queue.enqueue("B") { }
        posted.removeFirst().invoke()

        assertTrue(queue.cancel(first))
        assertTrue(queue.cancel(first))
        assertEquals(AndroidDirectConnectQueue.AttemptState.CANCELLING, first.state)
        assertEquals(AndroidDirectConnectQueue.AttemptState.QUEUED, second.state)
        assertEquals("A", queue.activeDeviceId)

        assertTrue(queue.complete(owner))
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, first.state)
        assertEquals(AndroidDirectConnectQueue.AttemptState.ADMITTED, second.state)
    }

    @Test
    fun recoveryBlockedDrainCancelsWaitersButRetainsExactNativeOwner() {
        val posted = ArrayDeque<() -> Unit>()
        val owner = Any()
        val queue = AndroidDirectConnectQueue(
            post = { posted.add(it) },
            onStartFailure = { _, _ -> },
        )

        val active = queue.enqueue("A") { queue.bind(it, owner) }
        val firstWaiting = queue.enqueue("B") { }
        val secondWaiting = queue.enqueue("C") { }
        posted.removeFirst().invoke()
        assertEquals(AndroidDirectConnectQueue.AttemptState.NATIVE_PENDING, active.state)

        val cancelled = queue.cancelPending()

        assertEquals(listOf(firstWaiting, secondWaiting), cancelled)
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, firstWaiting.state)
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, secondWaiting.state)
        assertEquals(0, queue.pendingCount)
        assertTrue(queue.owns(owner))
        assertTrue(queue.isActive(active))
        assertEquals("A", queue.activeDeviceId)
    }

    @Test
    fun explicitStatesTrackQueuedNativePendingCancellingAndTerminal() {
        val posted = ArrayDeque<() -> Unit>()
        val owner = Any()
        val queue = AndroidDirectConnectQueue(
            post = { posted.add(it) },
            onStartFailure = { _, _ -> },
        )

        val first = queue.enqueue("A") { queue.bind(it, owner) }
        val second = queue.enqueue("B") { queue.completeWithoutGatt(it) }
        assertEquals(AndroidDirectConnectQueue.AttemptState.ADMITTED, first.state)
        assertEquals(AndroidDirectConnectQueue.AttemptState.QUEUED, second.state)

        posted.removeFirst().invoke()
        assertEquals(AndroidDirectConnectQueue.AttemptState.NATIVE_PENDING, first.state)

        assertTrue(queue.cancel(first))
        assertEquals(AndroidDirectConnectQueue.AttemptState.CANCELLING, first.state)
        assertTrue(queue.complete(owner))
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, first.state)
        assertEquals(AndroidDirectConnectQueue.AttemptState.ADMITTED, second.state)

        posted.removeFirst().invoke()
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, second.state)
        assertNull(queue.activeDeviceId)
    }

    @Test
    fun clearAdvancesEpochAndFencesOldAttemptToken() {
        val queue = AndroidDirectConnectQueue(
            post = { },
            onStartFailure = { _, _ -> },
        )

        val old = queue.enqueue("scale") { }
        val oldEpoch = old.epoch
        assertEquals(oldEpoch, queue.epoch)

        queue.clear()
        assertEquals(AndroidDirectConnectQueue.AttemptState.TERMINAL, old.state)
        assertEquals(oldEpoch + 1, queue.epoch)

        val replacement = queue.enqueue("SCALE") { }
        assertEquals(oldEpoch + 1, replacement.epoch)
        assertFalse(queue.cancel(old))
        assertTrue(queue.isActive(replacement))
    }
}

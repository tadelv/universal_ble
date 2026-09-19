package com.navideck.universal_ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class AndroidDirectConnectQueueOwnershipTest {
    private val posted = ArrayDeque<() -> Unit>()
    private val started = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private val owners = mutableMapOf<String, Any>()
    private val queue = AndroidDirectConnectQueue(
        post = { posted.add(it) },
        onStartFailure = { attempt, _ -> failures.add(attempt.deviceId) },
    )

    private fun connect(id: String) = queue.enqueue(id) { attempt ->
        val owner = Any()
        owners[id] = owner
        started.add(id)
        queue.bind(attempt, owner)
    }

    private fun pump() {
        var count = 0
        while (posted.isNotEmpty()) {
            check(++count < 100) { "Unbounded dispatch" }
            posted.removeFirst().invoke()
        }
    }

    @Test
    fun cancelBeforePostedStart() {
        connect("scale")
        connect("DE1")
        queue.cancel("scale")
        pump()
        assertEquals(listOf("DE1"), started)
    }

    @Test
    fun delayedStartFailureReleasesUnboundAttempt() {
        lateinit var delayed: AndroidDirectConnectQueue.Attempt
        queue.enqueue("scale") { delayed = it }
        connect("DE1")
        pump()
        queue.fail(delayed, IllegalStateException("adapter unavailable"))
        pump()
        assertEquals(listOf("scale"), failures)
        assertEquals(listOf("DE1"), started)
    }

    @Test
    fun alreadyConnectedDoesNotOccupyLane() {
        queue.enqueue("scale") { queue.completeWithoutGatt(it) }
        connect("DE1")
        pump()
        assertEquals(listOf("DE1"), started)
    }

    @Test
    fun clearInvalidatesDelayedStartAndFailure() {
        lateinit var old: AndroidDirectConnectQueue.Attempt
        queue.enqueue("scale") { old = it }
        pump()
        queue.clear()
        connect("scale")
        pump()
        assertFalse(queue.isActive(old))
        queue.fail(old, IllegalStateException("old cooldown"))
        queue.completeWithoutGatt(old)
        assertTrue(failures.isEmpty())
        assertEquals("scale", queue.activeDeviceId)
    }

    @Test
    fun cancellingMiddlePreservesFifo() {
        connect("A")
        connect("B")
        connect("C")
        queue.cancel("B")
        pump()
        queue.complete(owners.getValue("A"))
        pump()
        assertEquals(listOf("A", "C"), started)
    }

    @Test
    fun repeatedRecoveryDoesNotAccumulateRequests() {
        repeat(500) {
            val id = if (it % 2 == 0) "DE1" else "scale"
            connect(id)
            pump()
            queue.complete(owners.getValue(id))
            pump()
            assertEquals(null, queue.activeDeviceId)
            assertEquals(0, queue.pendingCount)
        }
        assertTrue(queue.clear().isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun existingConnectionSatisfiesQueuedAttempt() {
        connect("DE1")
        connect("scale")
        pump()
        queue.completeExistingConnection("scale")
        assertFalse(queue.contains("scale"))
        assertEquals("DE1", queue.activeDeviceId)
        queue.complete(owners.getValue("DE1"))
        pump()
        assertEquals(listOf("DE1"), started)
    }

    @Test
    fun existingConnectionReleasesUnboundCooldown() {
        queue.enqueue("scale") { }
        connect("DE1")
        pump()
        queue.completeExistingConnection("scale")
        pump()
        assertEquals(listOf("DE1"), started)
    }

    @Test
    fun addressAloneCannotReleaseBoundAttempt() {
        connect("scale")
        connect("DE1")
        pump()
        queue.completeExistingConnection("scale")
        pump()
        assertEquals(listOf("scale"), started)
        queue.complete(owners.getValue("scale"))
        pump()
        assertEquals(listOf("scale", "DE1"), started)
    }
}

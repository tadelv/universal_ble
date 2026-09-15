package com.navideck.universal_ble

internal object AndroidDirectConnectQueueScenarios {
    private class Harness {
        val posted = ArrayDeque<() -> Unit>()
        val started = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val owners = mutableMapOf<String, Any>()
        val nativeConnecting = mutableSetOf<Any>()
        var peakNativeConnecting = 0
        val queue = AndroidDirectConnectQueue(
            post = { posted.add(it) },
            onStartFailure = { attempt, _ -> failures.add(attempt.deviceId) },
        )

        fun connect(id: String): AndroidDirectConnectQueue.Attempt = queue.enqueue(id) { attempt ->
            val owner = Any()
            check(nativeConnecting.isEmpty()) { "injected GATT 133: overlapping native connects" }
            nativeConnecting.add(owner)
            peakNativeConnecting = maxOf(peakNativeConnecting, nativeConnecting.size)
            owners[id] = owner
            started.add(id)
            queue.bind(attempt, owner)
        }

        fun finish(id: String) {
            val owner = checkNotNull(owners[id])
            nativeConnecting.remove(owner)
            queue.complete(owner)
        }

        fun pump() {
            var count = 0
            while (posted.isNotEmpty()) {
                check(++count < 100) { "unbounded dispatch" }
                posted.removeFirst().invoke()
            }
        }
    }

    fun serializesDualRecovery() {
        val h = Harness()
        h.connect("DE1")
        h.connect("scale")
        h.pump()
        check(h.started == listOf("DE1"))
        check(h.queue.activeDeviceId == "DE1")
        check(h.queue.pendingCount == 1)
        h.finish("DE1")
        check(h.started == listOf("DE1"))
        h.pump()
        check(h.started == listOf("DE1", "scale"))
        check(h.failures.isEmpty())
        check(h.peakNativeConnecting == 1)
    }

    fun machineLossDuringScaleConnect() {
        val h = Harness()
        h.connect("scale")
        h.pump()
        h.connect("DE1")
        h.pump()
        check(h.started == listOf("scale"))
        h.finish("scale")
        h.pump()
        check(h.started == listOf("scale", "DE1"))
        check(h.failures.isEmpty())
    }

    fun queuedTimeoutNeverStarts() {
        val h = Harness()
        h.connect("DE1")
        h.connect("scale")
        h.pump()
        h.queue.cancel("scale")
        h.finish("DE1")
        h.pump()
        check(h.started == listOf("DE1"))
        check(!h.queue.contains("scale"))
    }

    fun cancelBeforePostedStart() {
        val h = Harness()
        h.connect("scale")
        h.connect("DE1")
        h.queue.cancel("scale")
        h.pump()
        check(h.started == listOf("DE1"))
    }

    fun cancelDuringReconnectCooldown() {
        val h = Harness()
        var delayed: (() -> Unit)? = null
        h.queue.enqueue("scale") { attempt ->
            delayed = {
                if (h.queue.isActive(attempt)) error("cancelled cooldown created a GATT")
            }
        }
        h.connect("DE1")
        h.pump()
        h.queue.cancel("scale")
        h.pump()
        checkNotNull(delayed).invoke()
        check(h.started == listOf("DE1"))
    }

    fun cancelActiveWaitsForNativeClose() {
        val h = Harness()
        h.connect("scale")
        h.connect("DE1")
        h.pump()
        val scaleGatt = checkNotNull(h.owners["scale"])
        h.queue.cancel("scale")
        check(h.queue.isCancelled(scaleGatt))
        check(h.queue.contains("scale"))
        h.pump()
        check(h.started == listOf("scale"))
        h.finish("scale")
        h.pump()
        check(h.started == listOf("scale", "DE1"))
    }

    fun lateConnectedAfterCancellationDoesNotRelease() {
        val h = Harness()
        h.connect("scale")
        h.connect("DE1")
        h.pump()
        val scaleGatt = checkNotNull(h.owners["scale"])
        h.queue.cancel("scale")
        if (!h.queue.isCancelled(scaleGatt)) h.queue.complete(scaleGatt)
        h.pump()
        check(h.started == listOf("scale"))
        h.finish("scale")
        h.pump()
        check(h.started.last() == "DE1")
    }

    fun staleGattCannotReleaseReplacement() {
        val h = Harness()
        h.connect("scale")
        h.pump()
        val oldGatt = checkNotNull(h.owners["scale"])
        h.finish("scale")
        h.connect("scale")
        h.connect("DE1")
        h.pump()
        check(!h.queue.complete(oldGatt))
        h.pump()
        check(h.started == listOf("scale", "scale"))
        h.finish("scale")
        h.pump()
        check(h.started.last() == "DE1")
    }

    fun startFailureReleasesUnboundAttempt() {
        val h = Harness()
        h.queue.enqueue("scale") { throw IllegalStateException("connectGatt failed") }
        h.connect("DE1")
        h.pump()
        check(h.failures == listOf("scale"))
        check(h.started == listOf("DE1"))
    }

    fun delayedStartFailureReleasesUnboundAttempt() {
        val h = Harness()
        lateinit var delayed: AndroidDirectConnectQueue.Attempt
        h.queue.enqueue("scale") { delayed = it }
        h.connect("DE1")
        h.pump()
        h.queue.fail(delayed, IllegalStateException("adapter unavailable"))
        h.pump()
        check(h.failures == listOf("scale"))
        check(h.started == listOf("DE1"))
    }

    fun startFailureAfterBindingRequiresClose() {
        val h = Harness()
        val owner = Any()
        h.queue.enqueue("scale") {
            h.queue.bind(it, owner)
            throw IllegalStateException("failure after GATT creation")
        }
        h.connect("DE1")
        h.pump()
        check(h.failures == listOf("scale"))
        check(h.started.isEmpty())
        check(h.queue.complete(owner))
        h.pump()
        check(h.started == listOf("DE1"))
    }

    fun alreadyConnectedDoesNotOccupyLane() {
        val h = Harness()
        h.queue.enqueue("scale") { h.queue.completeWithoutGatt(it) }
        h.connect("DE1")
        h.pump()
        check(h.started == listOf("DE1"))
    }

    fun duplicateRequestRejected() {
        val h = Harness()
        h.connect("DE1")
        check(runCatching { h.connect("DE1") }.exceptionOrNull() is IllegalStateException)
        h.connect("scale")
        check(runCatching { h.connect("scale") }.exceptionOrNull() is IllegalStateException)
        h.pump()
        check(h.started == listOf("DE1"))
        check(h.queue.pendingCount == 1)
    }

    fun clearInvalidatesPostedStarts() {
        val h = Harness()
        h.connect("old-DE1")
        h.connect("old-scale")
        check(h.queue.clear().toSet() == setOf("old-DE1", "old-scale"))
        h.connect("new-DE1")
        h.pump()
        check(h.started == listOf("new-DE1"))
    }

    fun clearInvalidatesDelayedStartAndFailure() {
        val h = Harness()
        lateinit var old: AndroidDirectConnectQueue.Attempt
        h.queue.enqueue("scale") { old = it }
        h.pump()
        h.queue.clear()
        h.connect("scale")
        h.pump()
        check(!h.queue.isActive(old))
        h.queue.fail(old, IllegalStateException("old cooldown"))
        h.queue.completeWithoutGatt(old)
        check(h.failures.isEmpty())
        check(h.queue.activeDeviceId == "scale")
    }

    fun clearIgnoresLateGattFromOldEpoch() {
        val h = Harness()
        h.connect("scale")
        h.pump()
        val oldGatt = checkNotNull(h.owners["scale"])
        h.queue.clear()
        h.nativeConnecting.clear()
        h.connect("DE1")
        h.connect("scale")
        h.pump()
        check(!h.queue.complete(oldGatt))
        check(h.queue.activeDeviceId == "DE1")
        check(h.queue.pendingCount == 1)
    }

    fun cancellingMiddlePreservesFifo() {
        val h = Harness()
        h.connect("A")
        h.connect("B")
        h.connect("C")
        h.queue.cancel("B")
        h.pump()
        h.finish("A")
        h.pump()
        check(h.started == listOf("A", "C"))
    }

    fun unconfirmedNativeAttemptRetainsAdmission() {
        val h = Harness()
        h.connect("A")
        h.connect("B")
        h.pump()
        h.queue.cancel("A")
        h.queue.cancel("B")
        h.connect("C")
        h.pump()
        check(h.started == listOf("A"))
        check(h.queue.pendingCount == 1)
        h.finish("A")
        h.pump()
        check(h.started == listOf("A", "C"))
    }

    fun independentPluginInstances() {
        val first = Harness()
        val second = Harness()
        first.connect("A")
        second.connect("B")
        first.pump()
        second.pump()
        first.queue.clear()
        check(second.queue.activeDeviceId == "B")
    }

    fun repeatedRecoveryDoesNotAccumulateRequests() {
        val h = Harness()
        repeat(500) {
            val id = if (it % 2 == 0) "DE1" else "scale"
            h.connect(id)
            h.pump()
            h.finish(id)
            h.pump()
            check(h.queue.activeDeviceId == null)
            check(h.queue.pendingCount == 0)
        }
        check(h.queue.clear().isEmpty())
        check(h.failures.isEmpty())
    }

    fun existingConnectionSatisfiesQueuedAttempt() {
        val h = Harness()
        h.connect("DE1")
        h.connect("scale")
        h.pump()
        h.queue.completeExistingConnection("scale")
        check(!h.queue.contains("scale"))
        check(h.queue.activeDeviceId == "DE1")
        h.finish("DE1")
        h.pump()
        check(h.started == listOf("DE1"))
    }

    fun existingConnectionReleasesUnboundCooldown() {
        val h = Harness()
        h.queue.enqueue("scale") { }
        h.connect("DE1")
        h.pump()
        h.queue.completeExistingConnection("scale")
        h.pump()
        check(h.started == listOf("DE1"))
    }

    fun addressAloneCannotReleaseBoundAttempt() {
        val h = Harness()
        h.connect("scale")
        h.connect("DE1")
        h.pump()
        h.queue.completeExistingConnection("scale")
        h.pump()
        check(h.started == listOf("scale"))
        h.finish("scale")
        h.pump()
        check(h.started == listOf("scale", "DE1"))
    }

    fun runAll() {
        val cases = listOf(
            ::serializesDualRecovery, ::machineLossDuringScaleConnect,
            ::queuedTimeoutNeverStarts, ::cancelBeforePostedStart,
            ::cancelDuringReconnectCooldown, ::cancelActiveWaitsForNativeClose,
            ::lateConnectedAfterCancellationDoesNotRelease, ::staleGattCannotReleaseReplacement,
            ::startFailureReleasesUnboundAttempt, ::delayedStartFailureReleasesUnboundAttempt,
            ::startFailureAfterBindingRequiresClose, ::alreadyConnectedDoesNotOccupyLane,
            ::duplicateRequestRejected, ::clearInvalidatesPostedStarts,
            ::clearInvalidatesDelayedStartAndFailure, ::clearIgnoresLateGattFromOldEpoch,
            ::cancellingMiddlePreservesFifo, ::unconfirmedNativeAttemptRetainsAdmission,
            ::independentPluginInstances, ::repeatedRecoveryDoesNotAccumulateRequests,
            ::existingConnectionSatisfiesQueuedAttempt, ::existingConnectionReleasesUnboundCooldown,
            ::addressAloneCannotReleaseBoundAttempt,
        )
        cases.forEach { test ->
            test()
            println("PASS ${test.name}")
        }
        println("${cases.size} native connection admission scenarios passed")
    }
}

fun main() = AndroidDirectConnectQueueScenarios.runAll()

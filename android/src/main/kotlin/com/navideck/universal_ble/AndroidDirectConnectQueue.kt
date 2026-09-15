package com.navideck.universal_ble

/** Main-thread-confined admission for direct central connection attempts, not GATT commands. */
internal class AndroidDirectConnectQueue(
    private val post: (() -> Unit) -> Unit,
    private val onStartFailure: (Attempt, Exception) -> Unit,
) {
    internal class Attempt internal constructor(
        val deviceId: String,
        val generation: Long,
        internal val start: (Attempt) -> Unit,
    ) {
        internal var nativeOwner: Any? = null
        internal var cancelled = false
    }

    private val pending = ArrayDeque<Attempt>()
    private var active: Attempt? = null
    private var nextGeneration = 0L

    val activeDeviceId: String? get() = active?.deviceId
    val activeGeneration: Long? get() = active?.generation
    val pendingCount: Int get() = pending.size

    fun contains(deviceId: String): Boolean =
        active?.deviceId == deviceId || pending.any { it.deviceId == deviceId }

    fun enqueue(deviceId: String, start: (Attempt) -> Unit): Attempt {
        check(!contains(deviceId)) { "Connection already scheduled for $deviceId" }
        val attempt = Attempt(deviceId, ++nextGeneration, start)
        pending.add(attempt)
        startNext()
        return attempt
    }

    fun isActive(attempt: Attempt): Boolean = active === attempt && !attempt.cancelled

    fun bind(attempt: Attempt, nativeOwner: Any) {
        check(isActive(attempt)) { "Cannot bind a retired connection attempt" }
        check(attempt.nativeOwner == null) { "Connection attempt already owns a GATT" }
        attempt.nativeOwner = nativeOwner
    }

    fun isCancelled(nativeOwner: Any): Boolean =
        active?.let { it.nativeOwner === nativeOwner && it.cancelled } == true

    fun complete(nativeOwner: Any): Boolean {
        val attempt = active ?: return false
        if (attempt.nativeOwner !== nativeOwner) return false
        active = null
        startNext()
        return true
    }

    fun completeWithoutGatt(attempt: Attempt) {
        if (active !== attempt || attempt.nativeOwner != null) return
        active = null
        startNext()
    }

    fun completeExistingConnection(deviceId: String) {
        pending.removeAll { it.deviceId == deviceId }
        val attempt = active ?: return
        if (attempt.deviceId == deviceId) completeWithoutGatt(attempt)
    }

    fun cancel(deviceId: String) {
        pending.removeAll {
            if (it.deviceId != deviceId) return@removeAll false
            it.cancelled = true
            true
        }
        val attempt = active ?: return
        if (attempt.deviceId != deviceId) return
        attempt.cancelled = true
        // Once connectGatt ran, cancellation is not evidence of native teardown.
        if (attempt.nativeOwner == null) completeWithoutGatt(attempt)
    }

    fun fail(attempt: Attempt, error: Exception) {
        if (!isActive(attempt)) return
        try {
            onStartFailure(attempt, error)
        } finally {
            // A failure after GATT creation must retain admission until that GATT closes.
            completeWithoutGatt(attempt)
        }
    }

    fun clear(): List<String> {
        val attempts = listOfNotNull(active) + pending
        attempts.forEach { it.cancelled = true }
        active = null
        pending.clear()
        return attempts.map { it.deviceId }
    }

    private fun startNext() {
        if (active != null) return
        val attempt = pending.removeFirstOrNull() ?: return
        active = attempt
        try {
            post {
                if (isActive(attempt)) {
                    try {
                        attempt.start(attempt)
                    } catch (error: Exception) {
                        fail(attempt, error)
                    }
                }
            }
        } catch (error: Exception) {
            fail(attempt, error)
        }
    }
}

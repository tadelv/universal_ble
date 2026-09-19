package com.navideck.universal_ble

/** Main-thread-confined admission for direct central connection attempts, not GATT commands. */
internal class AndroidDirectConnectQueue(
    private val post: (() -> Unit) -> Unit,
    private val onStartFailure: (Attempt, Exception) -> Unit,
) {
    internal enum class AttemptState {
        QUEUED,
        ADMITTED,
        NATIVE_PENDING,
        CANCELLING,
        TERMINAL,
    }

    internal class Attempt internal constructor(
        val deviceId: String,
        internal val deviceKey: String,
        val generation: Long,
        val epoch: Long,
        internal val start: (Attempt) -> Unit,
    ) {
        internal var nativeOwner: Any? = null
        internal var cancelled = false
            private set
        internal var state = AttemptState.QUEUED
            private set
        internal var failureReported = false
            private set

        internal fun admit() {
            check(state == AttemptState.QUEUED) { "Connection attempt is not queued" }
            state = AttemptState.ADMITTED
        }

        internal fun bind(nativeOwner: Any) {
            check(state == AttemptState.ADMITTED) { "Connection attempt is not ready to bind" }
            check(this.nativeOwner == null) { "Connection attempt already owns a GATT" }
            this.nativeOwner = nativeOwner
            state = AttemptState.NATIVE_PENDING
        }

        internal fun beginCancellation() {
            cancelled = true
            state = if (nativeOwner == null) AttemptState.TERMINAL else AttemptState.CANCELLING
        }

        internal fun beginFailure(): Boolean {
            if (failureReported || state !in setOf(AttemptState.ADMITTED, AttemptState.NATIVE_PENDING)) {
                return false
            }
            failureReported = true
            if (nativeOwner == null) {
                state = AttemptState.TERMINAL
            } else {
                cancelled = true
                state = AttemptState.CANCELLING
            }
            return true
        }

        internal fun terminate() {
            state = AttemptState.TERMINAL
        }
    }

    private val pending = ArrayDeque<Attempt>()
    private var active: Attempt? = null
    private var nextGeneration = 0L
    private var currentEpoch = 0L

    val activeDeviceId: String? get() = active?.deviceId
    val activeGeneration: Long? get() = active?.generation
    val activeEpoch: Long? get() = active?.epoch
    val activeState: AttemptState? get() = active?.state
    val pendingCount: Int get() = pending.size
    val epoch: Long get() = currentEpoch

    fun contains(deviceId: String): Boolean {
        val key = key(deviceId)
        return active?.deviceKey == key || pending.any { it.deviceKey == key }
    }

    fun enqueue(deviceId: String, start: (Attempt) -> Unit): Attempt {
        val key = key(deviceId)
        check(!contains(key)) { "Connection already scheduled for $deviceId" }
        val attempt = Attempt(
            deviceId = deviceId,
            deviceKey = key,
            generation = ++nextGeneration,
            epoch = currentEpoch,
            start = start,
        )
        pending.add(attempt)
        startNext()
        return attempt
    }

    /** True while this exact attempt still owns the admission lane and is allowed to proceed. */
    fun isActive(attempt: Attempt): Boolean =
        active === attempt && attempt.state in setOf(AttemptState.ADMITTED, AttemptState.NATIVE_PENDING)

    fun bind(attempt: Attempt, nativeOwner: Any) {
        check(active === attempt && attempt.epoch == currentEpoch) {
            "Cannot bind a retired connection attempt"
        }
        attempt.bind(nativeOwner)
    }

    fun isCancelled(nativeOwner: Any): Boolean =
        active?.let {
            it.nativeOwner === nativeOwner && it.state == AttemptState.CANCELLING
        } == true

    /**
     * Complete the lane by exact native-client identity.
     *
     * This is used both for successful native establishment and for confirmed close of a
     * cancelling/failed attempt. Address equality alone is deliberately insufficient.
     */
    fun complete(nativeOwner: Any): Boolean {
        val attempt = active ?: return false
        if (attempt.nativeOwner !== nativeOwner) return false
        attempt.terminate()
        active = null
        startNext()
        return true
    }

    fun completeWithoutGatt(attempt: Attempt) {
        if (active !== attempt || attempt.nativeOwner != null) return
        if (attempt.state != AttemptState.ADMITTED) return
        attempt.terminate()
        active = null
        startNext()
    }

    /**
     * A connection established by another path can satisfy a queued/unbound direct attempt.
     * A bound native attempt is never released by address alone.
     */
    fun completeExistingConnection(deviceId: String) {
        val deviceKey = key(deviceId)
        pending.removeAll {
            if (it.deviceKey != deviceKey) return@removeAll false
            it.terminate()
            true
        }
        val attempt = active ?: return
        if (attempt.deviceKey == deviceKey &&
            attempt.nativeOwner == null &&
            attempt.state == AttemptState.ADMITTED
        ) {
            completeWithoutGatt(attempt)
        }
    }

    /** Cancel the current/queued attempt for [deviceId]. */
    fun cancel(deviceId: String) {
        val deviceKey = key(deviceId)
        pending.removeAll {
            if (it.deviceKey != deviceKey) return@removeAll false
            it.beginCancellation()
            true
        }
        val attempt = active ?: return
        if (attempt.deviceKey != deviceKey) return
        cancel(attempt)
    }

    /**
     * Cancel only [attempt]. This is the generation-safe seam for caller deadlines.
     * A stale timeout cannot cancel a newer same-address attempt through this overload.
     */
    fun cancel(attempt: Attempt): Boolean {
        val pendingIndex = pending.indexOfFirst { it === attempt }
        if (pendingIndex >= 0) {
            pending.removeAt(pendingIndex).beginCancellation()
            return true
        }
        if (active !== attempt || attempt.epoch != currentEpoch) return false
        if (attempt.state == AttemptState.CANCELLING) return true
        if (attempt.state == AttemptState.TERMINAL) return false

        attempt.beginCancellation()
        // Once connectGatt ran, cancellation is not evidence of native teardown.
        if (attempt.nativeOwner == null) {
            active = null
            startNext()
        }
        return true
    }

    /**
     * Report native-start failure exactly once.
     *
     * State is transitioned before invoking [onStartFailure], so a re-entrant callback cannot
     * report the same failure twice. A bound client moves to CANCELLING and retains admission
     * until that exact native owner is closed; an unbound failure releases the lane after the
     * callback returns (or throws).
     */
    fun fail(attempt: Attempt, error: Exception) {
        if (active !== attempt || attempt.epoch != currentEpoch) return
        if (!attempt.beginFailure()) return
        val holdsNativeOwner = attempt.nativeOwner != null
        if (!holdsNativeOwner) active = null
        try {
            onStartFailure(attempt, error)
        } finally {
            if (!holdsNativeOwner) startNext()
        }
    }

    /**
     * Invalidate the current adapter/plugin epoch.
     *
     * This is only safe at an epoch boundary where the owner is separately tearing down all
     * native clients. Ordinary cancellation must use [cancel] and preserve a bound owner barrier.
     */
    fun clear(): List<String> {
        val attempts = listOfNotNull(active) + pending
        currentEpoch++
        attempts.forEach {
            it.beginCancellation()
            it.terminate()
        }
        active = null
        pending.clear()
        return attempts.map { it.deviceId }
    }

    private fun startNext() {
        if (active != null) return
        val attempt = pending.removeFirstOrNull() ?: return
        attempt.admit()
        active = attempt
        try {
            post {
                if (isActive(attempt) && attempt.state == AttemptState.ADMITTED) {
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

    private fun key(deviceId: String): String = deviceId.uppercase()
}

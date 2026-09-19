package com.navideck.universal_ble

import java.util.IdentityHashMap

/**
 * Main-thread-confined recovery barrier for native GATT close failures.
 *
 * A failed close is not teardown confirmation. The owner stays blocked while this helper retries
 * cleanup a bounded number of times. Exhausting automatic retries keeps the owner blocked; a later
 * explicit close attempt or adapter/plugin epoch reset is required to recover.
 */
internal class AndroidGattCloseRecovery<T : Any>(
    private val postDelayed: (delayMs: Long, task: () -> Unit) -> Boolean,
    private val retryDelayMs: Long = 250L,
    private val maxAutomaticAttempts: Int = 3,
    private val onBlocked: (owner: T, reason: String, attempt: Int, error: Exception) -> Unit,
    private val onRecovered: (owner: T, reason: String, attempts: Int) -> Unit,
) {
    internal enum class Result {
        CLOSED,
        BLOCKED,
    }

    private class Entry<T : Any>(
        val owner: T,
        val reason: String,
        val close: () -> Unit,
        val epoch: Long,
    ) {
        var attempts = 0
        var retryPosted = false
    }

    private val blocked = IdentityHashMap<T, Entry<T>>()
    private var currentEpoch = 0L

    val blockedCount: Int get() = blocked.size
    val hasBlockedOwners: Boolean get() = blocked.isNotEmpty()
    val epoch: Long get() = currentEpoch

    fun isBlocked(owner: T): Boolean = blocked.containsKey(owner)

    /**
     * Attempt to close [owner]. Native/cache ownership is retired only by [onRecovered], which is
     * invoked for every confirmed close (first attempt or a later retry).
     */
    fun close(owner: T, reason: String, action: () -> Unit): Result {
        val existing = blocked[owner]
        val entry = existing ?: Entry(owner, reason, action, currentEpoch).also {
            blocked[owner] = it
        }
        if (entry.epoch != currentEpoch) return Result.BLOCKED

        entry.attempts++
        return try {
            entry.close()
            blocked.remove(owner)
            onRecovered(owner, entry.reason, entry.attempts)
            Result.CLOSED
        } catch (error: Exception) {
            if (entry.attempts == 1) {
                onBlocked(owner, entry.reason, entry.attempts, error)
            }
            scheduleRetry(entry)
            Result.BLOCKED
        }
    }

    /** Retry an already blocked owner immediately, for example from a later native callback. */
    fun retry(owner: T): Result? {
        val entry = blocked[owner] ?: return null
        return close(owner, entry.reason, entry.close)
    }

    /**
     * Invalidate all unresolved owners at an adapter/plugin epoch boundary.
     * Posted retries become harmless because they capture the old epoch and entry identity.
     */
    fun clearEpoch(): List<T> {
        currentEpoch++
        val owners = blocked.keys.toList()
        blocked.clear()
        return owners
    }

    private fun scheduleRetry(entry: Entry<T>) {
        if (entry.attempts >= maxAutomaticAttempts || entry.retryPosted) return
        entry.retryPosted = true
        val expectedEpoch = entry.epoch
        val posted = postDelayed(retryDelayMs) {
            if (expectedEpoch != currentEpoch) return@postDelayed
            if (blocked[entry.owner] !== entry) return@postDelayed
            entry.retryPosted = false
            if (entry.attempts >= maxAutomaticAttempts) return@postDelayed
            close(entry.owner, entry.reason, entry.close)
        }
        if (!posted) entry.retryPosted = false
    }
}

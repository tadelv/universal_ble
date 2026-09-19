package com.navideck.universal_ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class AndroidGattCloseRecoveryTest {
    @Test
    fun closeFailureKeepsOwnerBlockedUntilExactCloseSucceeds() {
        val tasks = ArrayDeque<() -> Unit>()
        val owner = Any()
        var closes = 0
        val blocked = mutableListOf<Int>()
        val recovered = mutableListOf<Int>()
        val recovery = AndroidGattCloseRecovery<Any>(
            postDelayed = { _, task -> tasks.add(task); true },
            onBlocked = { _, _, attempt, _ -> blocked.add(attempt) },
            onRecovered = { _, _, attempts -> recovered.add(attempts) },
        )

        assertEquals(
            AndroidGattCloseRecovery.Result.BLOCKED,
            recovery.close(owner, "cancelled connect") {
                closes++
                if (closes < 2) throw IllegalStateException("close failed")
            },
        )
        assertTrue(recovery.isBlocked(owner))
        assertEquals(listOf(1), blocked)
        assertEquals(1, tasks.size)

        tasks.removeFirst().invoke()

        assertFalse(recovery.isBlocked(owner))
        assertEquals(0, recovery.blockedCount)
        assertEquals(2, closes)
        assertEquals(listOf(2), recovered)
    }

    @Test
    fun exhaustedAutomaticRetriesRemainRecoveryBlocked() {
        val tasks = ArrayDeque<() -> Unit>()
        val owner = Any()
        var closes = 0
        val recovery = AndroidGattCloseRecovery<Any>(
            postDelayed = { _, task -> tasks.add(task); true },
            maxAutomaticAttempts = 3,
            onBlocked = { _, _, _, _ -> },
            onRecovered = { _, _, _ -> },
        )

        recovery.close(owner, "disconnect") {
            closes++
            throw IllegalStateException("still owned by native stack")
        }
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()

        assertEquals(3, closes)
        assertTrue(recovery.hasBlockedOwners)
        assertTrue(recovery.isBlocked(owner))
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun immediateRetryDoesNotDuplicatePostedAutomaticRetry() {
        val tasks = ArrayDeque<() -> Unit>()
        val owner = Any()
        var closes = 0
        val recovery = AndroidGattCloseRecovery<Any>(
            postDelayed = { _, task -> tasks.add(task); true },
            maxAutomaticAttempts = 3,
            onBlocked = { _, _, _, _ -> },
            onRecovered = { _, _, _ -> },
        )

        recovery.close(owner, "disconnect") {
            closes++
            throw IllegalStateException("close failed")
        }
        recovery.retry(owner)

        assertEquals(2, closes)
        assertEquals(1, tasks.size)

        recovery.retry(owner)

        assertEquals(3, closes)
        assertEquals(1, tasks.size)

        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()

        assertEquals(3, closes)
        assertTrue(recovery.isBlocked(owner))
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun laterNativeCallbackCanRetryAfterAutomaticBudgetWasExhausted() {
        val tasks = ArrayDeque<() -> Unit>()
        val owner = Any()
        var fail = true
        val recovery = AndroidGattCloseRecovery<Any>(
            postDelayed = { _, task -> tasks.add(task); true },
            maxAutomaticAttempts = 2,
            onBlocked = { _, _, _, _ -> },
            onRecovered = { _, _, _ -> },
        )

        recovery.close(owner, "disconnect") {
            if (fail) throw IllegalStateException("close failed")
        }
        tasks.removeFirst().invoke()
        assertTrue(recovery.isBlocked(owner))

        fail = false
        assertEquals(AndroidGattCloseRecovery.Result.CLOSED, recovery.retry(owner))
        assertFalse(recovery.isBlocked(owner))
        assertNull(recovery.retry(owner))
    }

    @Test
    fun epochResetInvalidatesPostedRetryWithoutTouchingNewOwner() {
        val tasks = ArrayDeque<() -> Unit>()
        val oldOwner = Any()
        val newOwner = Any()
        var oldCloses = 0
        var newCloses = 0
        val recovery = AndroidGattCloseRecovery<Any>(
            postDelayed = { _, task -> tasks.add(task); true },
            onBlocked = { _, _, _, _ -> },
            onRecovered = { _, _, _ -> },
        )

        recovery.close(oldOwner, "old epoch") {
            oldCloses++
            throw IllegalStateException("old close failed")
        }
        val oldEpoch = recovery.epoch
        assertEquals(listOf(oldOwner), recovery.clearEpoch())
        assertEquals(oldEpoch + 1, recovery.epoch)

        assertEquals(
            AndroidGattCloseRecovery.Result.CLOSED,
            recovery.close(newOwner, "new epoch") { newCloses++ },
        )
        tasks.removeFirst().invoke()

        assertEquals(1, oldCloses)
        assertEquals(1, newCloses)
        assertFalse(recovery.hasBlockedOwners)
    }

    @Test
    fun failedSchedulerDoesNotPretendCleanupSucceeded() {
        val owner = Any()
        val recovery = AndroidGattCloseRecovery<Any>(
            postDelayed = { _, _ -> false },
            onBlocked = { _, _, _, _ -> },
            onRecovered = { _, _, _ -> },
        )

        assertEquals(
            AndroidGattCloseRecovery.Result.BLOCKED,
            recovery.close(owner, "handler rejected retry") {
                throw IllegalStateException("close failed")
            },
        )
        assertTrue(recovery.isBlocked(owner))
        assertTrue(recovery.hasBlockedOwners)
    }
}

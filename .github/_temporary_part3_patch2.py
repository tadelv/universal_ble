from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)

plugin_path = Path("android/src/main/kotlin/com/navideck/universal_ble/UniversalBlePlugin.kt")
text = plugin_path.read_text()

text = replace_once(
    text,
    '''    private fun scheduleDisconnectFallback(gatt: BluetoothGatt) {
        if (!ownedGatts.containsKey(gatt) || pendingDisconnectFallbacks.containsKey(gatt)) return
''',
    '''    private fun scheduleDisconnectFallback(
        gatt: BluetoothGatt,
        delayMs: Long = disconnectCallbackGraceMs,
    ) {
        if (!ownedGatts.containsKey(gatt) || pendingDisconnectFallbacks.containsKey(gatt)) return
''',
    "fallback delay parameter",
)
text = replace_once(
    text,
    '''        val posted = mainThreadHandler?.postDelayed(fallback, disconnectCallbackGraceMs) == true
''',
    '''        val posted = mainThreadHandler?.postDelayed(fallback, delayMs) == true
''',
    "fallback uses requested delay",
)
text = replace_once(
    text,
    '''        if (stillCurrent) {
            cleanUpConnection(gatt)
            connectTimestamps.remove(deviceId.connectionKey())
        }
''',
    '''        if (stillCurrent) {
            disconnectGattBestEffort(gatt, "disconnect-fallback-schedule-failed")
            cleanUpConnection(gatt)
            connectTimestamps.remove(deviceId.connectionKey())
        }
''',
    "scheduler failure best effort disconnect",
)
text = replace_once(
    text,
    '''        val elapsed = SystemClock.elapsedRealtime() - (connectTimestamps[connectionKey] ?: 0L)
        val remaining = minConnectDisconnectGapMs - elapsed
        if (remaining > 0) {
            UniversalBleLogger.logDebug(
                "Delaying disconnect of $deviceId by ${remaining}ms (connect-disconnect gap)"
            )
            mainThreadHandler?.postDelayed({ cleanConnection(gatt) }, remaining)
        } else {
            cleanConnection(gatt)
        }
''',
    '''        val elapsed = SystemClock.elapsedRealtime() - (connectTimestamps[connectionKey] ?: 0L)
        val remaining = minConnectDisconnectGapMs - elapsed
        // Fence new native establishments immediately. The callback fallback itself is delayed
        // until after both the existing connect/disconnect spacing and its callback grace period.
        scheduleDisconnectFallback(
            gatt,
            maxOf(remaining, 0L) + disconnectCallbackGraceMs,
        )
        if (remaining > 0) {
            UniversalBleLogger.logDebug(
                "Delaying disconnect of $deviceId by ${remaining}ms (connect-disconnect gap)"
            )
            mainThreadHandler?.postDelayed({
                if (ownedGatts.containsKey(gatt)) cleanConnection(gatt)
            }, remaining)
        } else {
            cleanConnection(gatt)
        }
''',
    "disconnect request fencing",
)
plugin_path.write_text(text)


test_path = Path("android/src/test/kotlin/com/navideck/universal_ble/AndroidDirectConnectPluginTest.kt")
test = test_path.read_text()
test = replace_once(
    test,
    '''        f.plugin.disconnect(scale)
        f.advance(4_000)

        assertTrue(f.ownedGatts().containsKey(original))
''',
    '''        f.plugin.disconnect(scale)
        f.advance(2_000)
        f.advance(2_000)

        assertTrue(f.ownedGatts().containsKey(original))
''',
    "forced close failure timing",
)
test = replace_once(
    test,
    '''    @Test
    fun adapterOffCancelsQueuedAndAlreadyPostedAdmission() = withFixture { f ->
''',
    '''    @Test
    fun disconnectRequestFencesPeerConnectBeforeNativeDisconnectRuns() = withFixture { f ->
        f.connect(scale)
        f.pump()
        f.connected(scale)
        f.pump()

        f.plugin.disconnect(scale)

        assertFailsWith<FlutterError> { f.connect(machine) }
        assertEquals(listOf(scale), f.created)
    }

    @Test
    fun healthyConnectedPeerRemainsIdempotentWhileOtherGattIsTearingDown() = withFixture { f ->
        f.connect(machine)
        f.pump()
        f.connected(machine)
        f.pump()
        f.connect(scale)
        f.pump()
        f.connected(scale)
        f.pump()

        f.plugin.disconnect(scale)
        f.connect(machine)
        f.pump()

        assertEquals(listOf(machine, scale), f.created)
        verify(f.gatts.getValue(machine), never()).disconnect()
        verify(f.gatts.getValue(machine), never()).close()
    }

    @Test
    fun adapterOffCancelsQueuedAndAlreadyPostedAdmission() = withFixture { f ->
''',
    "disconnect fence regression tests",
)
test_path.write_text(test)


doc_path = Path("doc/android-direct-connect-admission.md")
doc = doc_path.read_text()
doc = replace_once(
    doc,
    '''Pending fallback tasks are cancelled at adapter/plugin epoch reset. While an exact disconnect
fallback is outstanding, new native establishments are recovery-blocked, but idempotent access to a
different already-connected healthy peer remains allowed.
''',
    '''The teardown fence is installed as soon as explicit `disconnect()` is requested, including while
the existing 2-second connect/disconnect spacing is still being honored; this closes the race where
a peer connect could otherwise enter before native disconnect starts. Pending fallback tasks are
cancelled at adapter/plugin epoch reset. New native establishments remain recovery-blocked while the
exact teardown is unresolved, but idempotent access to a different already-connected healthy peer
remains allowed.
''',
    "disconnect fence documentation",
)
doc_path.write_text(doc)

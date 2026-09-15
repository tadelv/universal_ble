from pathlib import Path

p = Path('android/src/test/kotlin/com/navideck/universal_ble/UniversalBlePluginTest.kt')
s = p.read_text()

def replace_once(old, new, label):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    s = s.replace(old, new, 1)

replace_once(
    'import org.mockito.ArgumentMatchers.anyList\n',
    'import org.mockito.ArgumentMatchers.anyList\nimport org.mockito.ArgumentMatchers.anyLong\n',
    'anyLong import',
)
replace_once(
    '''        plugin.setField("mainThreadHandler", handler)\n        plugin.setField("bluetoothManager", manager)\n        plugin.setField("context", context)\n        `when`(manager.adapter).thenReturn(adapter)\n        `when`(adapter.isEnabled).thenReturn(true)\n        `when`(adapter.getRemoteDevice(deviceId)).thenReturn(device)\n''',
    '''        plugin.setField("mainThreadHandler", handler)\n        plugin.setField("bluetoothManager", manager)\n        plugin.setField("context", context)\n        `when`(handler.postDelayed(any(Runnable::class.java), anyLong())).thenReturn(true)\n        `when`(manager.adapter).thenReturn(adapter)\n        `when`(adapter.isEnabled).thenReturn(true)\n        `when`(adapter.getRemoteDevice(deviceId)).thenReturn(device)\n''',
    'mixed case handler scheduling',
)
replace_once(
    '''            assertEquals(1_000L, connectTimestamps[deviceId.connectionKey()])\n            verify(handler).postDelayed(any(Runnable::class.java), eq(1_500L))\n            verify(gatt, never()).disconnect()\n''',
    '''            assertEquals(1_000L, connectTimestamps[deviceId.connectionKey()])\n            verify(handler).postDelayed(any(Runnable::class.java), eq(3_500L))\n            verify(handler).postDelayed(any(Runnable::class.java), eq(1_500L))\n            verify(gatt, never()).disconnect()\n''',
    'mixed case delay assertions',
)
p.write_text(s)

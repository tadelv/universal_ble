# Android direct connection admission

Related: decentespresso/decaid#871. This is a candidate recovery fix, not proof
that overlapping connects caused the original dual-device link loss.

## Why this boundary

`UniversalBle.connect()` bypasses the Dart command queues. Selecting
`QueueType.perDevice`, or changing a GATT queue's reset policy, does not serialize
native connection establishment. Decaid can start machine and scale connects
independently, including during discovery and recovery. A Dart-only mutex would
also need cancellation ownership: an outer `Future.timeout()` does not cancel a
queued closure or the native attempt it eventually starts.

The Android plugin now owns a FIFO for direct (`autoConnect=false`) attempts.
This is deliberately not a global GATT command queue and not a retry engine.
The host still owns retry policy and its existing end-to-end connection deadline.

## Ownership and cancellation

- Admission is owned by one plugin instance and confined to its main handler.
  Device IDs are normalized by the existing `connectionKey()` function.
- Only one direct connection is unresolved at a time. Success releases admission;
  a failed or cancelled attempt releases only after its actual `BluetoothGatt`
  client is closed. Established peers remain connected and their GATT traffic is
  not queued behind another device's connection attempt.
- A queued disconnect removes that attempt before `connectGatt` can run. The
  public Dart connect timeout already calls native disconnect, so waiting for
  admission consumes the caller's existing timeout and cannot connect later.
- Cancellation after GATT creation retains admission during the existing
  connect/disconnect minimum gap. A late connected callback is not published for
  that cancelling attempt. Cleanup closes this unused client even if Android
  still reports connected, rather than relying on another callback to release it.
- Attempt identity fences posted admission and delayed cooldown work. GATT object
  identity fences completion. A stale callback cannot release a new generation.
- Adapter-off and engine-detach clear admission before closing native clients;
  old handler tasks cannot start connections in a later adapter generation.
- A failure before GATT creation releases admission and is reported through the
  existing connection-failure event. A native close exception does not falsely
  establish teardown or permit another unresolved direct connect.

Existing per-device reconnect cooldowns are retained. There is no adapter toggle,
cache refresh, native automatic retry, notification heartbeat, or machine/scale
priority encoded in this library. FIFO avoids starving the scale behind repeated
machine attempts. `autoConnect=true` remains OS-managed and outside this FIFO;
temporary service-discovery attachments to already system-connected peripherals
are also unchanged. This is not an adapter-wide exclusivity guarantee against
other apps, plugin instances, or autonomous background connections.

## Diagnostics

At INFO, admission logs show the admitted/waiting device, blocking device,
attempt generation, pending count, and owned GATT count. DEBUG adds creation and
close events with GATT object identity. Capture native `UniversalBle` logcat with
logging enabled; these diagnostics are not claimed to be included automatically
in Decaid's Dart-only field log upload. Native status callbacks and these events
should be correlated with Decaid's per-device queue and notification-age logs.

## Verification and merge gate

`scripts/test_android_direct_connect_queue.sh` compiles and runs 23 deterministic
Kotlin/JVM scenarios with no Android SDK dependency. The fixture rejects
concurrent native starts with an injected GATT-133-style failure. It covers both
connection orders, queued/active cancellation, cooldown, stale callbacks, start
failure, adapter generations, and 500 recovery cycles. Removing the active-owner
guard makes the regression suite fail. The same scenarios are exposed to the
normal Kotlin test runner; `AndroidDirectConnectPluginTest` additionally exercises
real plugin methods with mocked Android GATT clients and handler dispatch.

The standalone suite was run with Kotlin 1.9.0 and Java 21 targeting JVM 1.8.
Android/Mockito integration tests, Flutter tests, and Android hardware validation
were not run in the implementation environment, which lacks Flutter and the
Android SDK. Kotlin syntax parsing is not an Android build or type check.

Before merging, run the Android integration suite and Decaid's analysis/full
Flutter suite. Compare baseline and candidate on the affected Android 10 device
with the original-scale protocol fix included in both builds. Test dual-device
soak, repeated sleep/wake, each peripheral lost separately, near-simultaneous
loss, 133 recovery, cancellation during cooldown, and adapter off/on. Record
attempt counts, failure status, native client lifetime, notification gaps and
recovery duration. Require the healthy peer to remain connected and usable.

A caller can exhaust its timeout while waiting for another unavailable device;
this patch does not extend that deadline or count a deferred attempt as success.
Repeated native teardown failures still need an explicit surfaced recovery path,
not an unsafe automatic unlock. If initial timeouts continue despite serialized
connects, investigate radio/firmware/notification starvation separately rather
than widening this FIFO into a global reset policy.

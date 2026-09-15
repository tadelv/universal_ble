# Android direct connection admission

Related: decentespresso/decaid#871 and tadelv/universal_ble#26. This is a
candidate recovery fix, not proof that overlapping connects caused the original
dual-device link loss.

## Why this boundary

`UniversalBle.connect()` bypasses the Dart command queues. Selecting
`QueueType.perDevice`, or changing a GATT queue's reset policy, does not serialize
native connection establishment. Decaid can start machine and scale connects
independently, including during discovery and recovery. A Dart-only mutex would
also need cancellation ownership: an outer `Future.timeout()` does not cancel a
queued closure or the native attempt it eventually starts.

The Android plugin owns a FIFO for direct (`autoConnect=false`) attempts. This is
deliberately not a global GATT command queue and not a retry engine. The host
still owns retry policy and its existing end-to-end connection deadline.

## State-machine contract

Each direct request has a normalized device key, monotonically increasing attempt
generation, adapter/plugin epoch, explicit state and (after `connectGatt`) exact
native-owner identity. The helper normalizes IDs itself in addition to the plugin
boundary, so mixed-case callers cannot create parallel logical owners.

| State | Meaning | May release admission? |
| --- | --- | --- |
| `QUEUED` | Waiting behind another unresolved direct attempt. | Only cancellation, an existing connection satisfying it, or epoch invalidation removes it. |
| `ADMITTED` | Owns the lane; its posted/cooldown start may execute. No native client is bound yet. | Success via an already-established connection, cancellation, or pre-allocation start failure. |
| `NATIVE_PENDING` | `connectGatt` returned and this attempt owns that exact native client. | Native success or confirmed close of that exact client. Address equality is insufficient. |
| `CANCELLING` | Cancellation/failure happened after native allocation. | Only confirmed close of the exact native client, or a whole adapter/plugin epoch invalidation. |
| `TERMINAL` | Attempt no longer owns or can acquire admission. | Already released. Late work is fenced out. |

`clear()` is an epoch transition, not ordinary cancellation. It increments the
queue epoch, retires queued/active attempts and is only called when the plugin is
separately tearing down all central state (adapter off or engine detach). A stale
attempt from an earlier epoch cannot bind, fail or cancel a replacement attempt.

## Ownership and cancellation

- Admission is owned by one plugin instance and confined to its main handler.
- Only one direct connection is unresolved at a time. Success releases admission;
  a failed or cancelled attempt releases only after its actual `BluetoothGatt`
  client is closed. Established peers remain connected and their GATT traffic is
  not queued behind another device's connection attempt.
- A queued disconnect removes that attempt before `connectGatt` can run. The
  public Dart connect timeout already calls native disconnect, so waiting for
  admission consumes the caller's existing timeout and cannot connect later.
- The helper exposes both device-key cancellation for the current plugin API and
  exact-attempt cancellation for deadline/ownership plumbing. The latter is the
  generation-safe seam: a late timeout token cannot cancel a newer same-address
  replacement.
- Cancellation after GATT creation moves the attempt to `CANCELLING` and retains
  admission during the existing connect/disconnect minimum gap. A late connected
  callback is not published for that cancelling attempt. Cleanup closes this
  unused client even if Android still reports connected, rather than relying on
  another callback to release it.
- Attempt identity fences posted admission and delayed cooldown work. GATT object
  identity fences native completion. A stale callback cannot release a new
  generation.
- Start failure is transitioned before the failure callback is delivered. A
  re-entrant failure callback therefore cannot report or settle the same attempt
  twice. If the callback itself throws, an unbound failed attempt still releases
  its lane; a bound failed attempt stays `CANCELLING` until its native client is
  actually closed.
- Adapter-off and engine-detach invalidate the epoch before closing native
  clients; old handler tasks cannot start connections in a later epoch.
- A native close exception does not falsely establish teardown or permit another
  unresolved direct connect during an ordinary cancellation.

Existing per-device reconnect cooldowns are retained. There is no adapter toggle,
cache refresh, native automatic retry, notification heartbeat, or machine/scale
priority encoded in this library. FIFO avoids starving the scale behind repeated
machine attempts. `autoConnect=true` remains OS-managed and outside this FIFO;
temporary service-discovery attachments to already system-connected peripherals
are also unchanged. This is not an adapter-wide exclusivity guarantee against
other apps, plugin instances, or autonomous background connections.

## Deadline and liveness boundary

Admission waiting consumes the existing Dart `connect()` deadline. The current
public cancellation route is device-address based, while the helper now exposes
an exact `Attempt` cancellation seam for the native integration work. A timeout
never means native teardown succeeded: if a bound client has not closed, it keeps
the lane in `CANCELLING`.

Waiting callers retain their own bounded Dart deadlines and can be removed before
they start. If the active native owner cannot be closed, later direct attempts
must fail/expire rather than bypassing the ownership barrier. Turning that
condition into a distinct caller-visible `recovery-blocked` result, instead of a
generic timeout, belongs to the native/API integration package tracked by
`tadelv/universal_ble#27`; the state machine deliberately does not invent a safe
unlock.

## Diagnostics

At INFO, admission logs show the admitted/waiting device, blocking device,
attempt generation, pending count, and owned GATT count. DEBUG adds creation and
close events with GATT object identity. The state machine also exposes active
state/epoch for focused native diagnostics. Capture native `UniversalBle` logcat
with logging enabled; these diagnostics are not claimed to be included
automatically in Decaid's Dart-only field log upload. Native status callbacks and
these events should be correlated with Decaid's per-device queue and
notification-age logs.

## Verification and merge gate

`scripts/test_android_direct_connect_queue.sh` compiles and runs the original 23
deterministic Kotlin/JVM recovery scenarios with no Android SDK dependency. The
fixture rejects concurrent native starts with an injected GATT-133-style failure.
It covers both connection orders, queued/active cancellation, cooldown, stale
callbacks, start failure, adapter generations, and 500 recovery cycles. Removing
the active-owner guard makes the regression suite fail.

`AndroidDirectConnectQueueTest` adds state-machine edge regressions for
case-insensitive duplicate IDs, exact stale-token cancellation, explicit
state/epoch transitions, re-entrant failure delivery, bound-failure cancellation,
and a throwing failure callback. `AndroidDirectConnectPluginTest` exercises real
plugin methods with mocked Android GATT clients and handler dispatch.

The repository PR workflow runs `flutter analyze`, Flutter tests and
`:universal_ble:testDebugUnitTest` from the example Android build, so a green
current PR test job is Android compilation/unit-test evidence rather than only a
standalone syntax check. Physical BLE behavior still requires the affected-device
gate.

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

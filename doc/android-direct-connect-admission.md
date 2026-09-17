# Android direct connection admission

Related: decentespresso/decaid#871, tadelv/universal_ble#26 and
`tadelv/universal_ble#27`. This is a candidate recovery fix, not proof that
overlapping connects caused the original dual-device link loss.

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
cache refresh, native automatic connection retry, notification heartbeat, or
machine/scale priority encoded in this library. FIFO avoids starving the scale
behind repeated machine attempts. `autoConnect=true` remains OS-managed and
outside this FIFO. This is not an adapter-wide exclusivity guarantee against
other apps, plugin instances, or autonomous background connections.

## Part 3: native GATT lifecycle audit

The Android owner has four GATT-allocation cases:

1. direct `connect(autoConnect=false)` — this is Decaid's Android machine/scale
   path and is owned by the admission state machine;
2. delayed reconnect after the existing disconnect/connect cooldown — still the
   same direct attempt and generation;
3. `autoConnect=true` — an OS-managed mode intentionally outside direct
   admission; Decaid does not currently request this mode;
4. temporary service-discovery attachments created by
   `getSystemDevices(withServices: non-empty)` for already system-connected
   devices. Decaid's Android discovery currently calls `getSystemDevices` with an
   empty service list, so this temporary-GATT path is not exercised by the #871
   recovery flow. It remains explicitly outside the direct-admission guarantee.

This audit constrains the claim: the candidate serializes Decaid's direct Android
connection establishment inside one plugin instance. It does not serialize GATT
clients opened by other apps, other plugin instances, autonomous Android
`autoConnect`, or the optional temporary service-discovery attachment path.

### Teardown confirmation

Calling `disconnect()`, observing `BluetoothProfile.STATE_DISCONNECTED`, or
returning from a Dart disconnect Future is not the same as disposing the native
`BluetoothGatt` client. The direct admission lane is released only after the
exact bound native owner successfully closes.

Part 3 adds `AndroidGattCloseRecovery` for the exceptional case where
`BluetoothGatt.close()` itself throws. The recovery barrier:

- keeps the exact owner blocked instead of pretending teardown succeeded;
- retries **cleanup only**, never the connection, on a bounded schedule;
- stays blocked after the automatic retry budget is exhausted;
- allows a later native callback/explicit cleanup attempt to retry that exact
  owner and release only after a successful close;
- invalidates posted retries at adapter/plugin epoch reset; and
- never uses an adapter toggle, bond removal, cache refresh, or native 133
  connection retry as an implicit escape hatch.

When native teardown is recovery-blocked, queued direct attempts can be retired
without releasing the active native owner. This provides the native hook for
caller-visible `RECOVERY_BLOCKED` completion rather than making every waiter burn
its full connection timeout. Wiring that completion through the concrete plugin
callback path is part of the #27 integration and must preserve the exact-owner
barrier.

A cache entry for a current GATT must not be discarded before successful close:
otherwise a close exception loses the address-to-client handle while the native
client remains owned. The plugin integration must retire cache and `ownedGatts`
state only after close succeeds, except at an adapter/engine epoch boundary where
the whole plugin/native epoch is being invalidated.

### Connection callbacks

A `STATE_CONNECTED` callback is success only when `status == GATT_SUCCESS`, the
GATT object is still current, and its attempt has not been cancelled. A callback
with a non-success status must not publish `connected=true` or release admission
as a successful establishment. Stale same-address callbacks are fenced by GATT
object identity.

## Deadline and liveness boundary

Admission waiting consumes the existing Dart `connect()` deadline. The current
public cancellation route is device-address based, while the helper exposes an
exact `Attempt` cancellation seam for native integration. A timeout never means
native teardown succeeded: if a bound client has not closed, it keeps the lane in
`CANCELLING`.

Waiting callers retain their own bounded Dart deadlines and can be removed before
they start. If the active native owner cannot be closed, later direct attempts
must fail/expire rather than bypassing the ownership barrier. The native layer
must surface a distinct recovery-blocked result to affected waiters/new attempts;
it must not silently unlock merely because the original caller timed out.

## Diagnostics

At INFO, admission logs show the admitted/waiting device, blocking device,
attempt generation, pending count, and owned GATT count. DEBUG adds creation and
close events with GATT object identity. Part 3 additionally requires close
attempt/retry outcome, recovery-blocked/recovered state, raw native connection
status and process-monotonic event time. The state machine exposes active
state/epoch so these entries can identify the admission owner without relying on
address equality.

Capture native `UniversalBle` logcat with logging enabled; these diagnostics are
not claimed to be included automatically in Decaid's Dart-only field log upload.
Native status callbacks and these events should be correlated with Decaid's
per-device queue and notification-age logs.

## Verification and merge gate

Queue unit tests cover ownership, FIFO cancellation, stale tokens, adapter
epochs, start failures, and repeated recovery. The retained standalone ownership
cases now run through `AndroidDirectConnectQueueOwnershipTest` in the existing
Android test job.

`AndroidGattCloseRecoveryTest` covers close failure followed by recovery,
automatic-retry exhaustion that stays blocked, later explicit recovery after the
budget is exhausted, epoch invalidation of posted retries, and scheduler failure
without false success. `AndroidDirectConnectPluginTest` exercises real plugin
methods with mocked Android GATT clients and handler dispatch; #27 requires its
integration cases to include non-success CONNECTED callbacks, missing disconnect
callbacks, close failure/recovery and no resource growth across repeated cycles.

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

### Established-link disconnect callback fallback

An explicit disconnect of an already-established direct link first preserves the normal Android
callback path. If Android still reports the exact owned GATT as connected after `disconnect()`, the
plugin arms a 2-second callback grace timer. A real `STATE_DISCONNECTED` callback cancels that timer.
If no callback arrives, the timer force-closes only the captured GATT object, publishes a bounded
disconnect result, and routes any throwing `close()` through `AndroidGattCloseRecovery`.

The timer is identity-fenced. If the original owner was already retired it is a no-op. If a same-
address replacement somehow became current, the fallback may close the exact stale old object but
never disconnects or evicts the replacement; `removeCacheIfCurrent()` remains identity-checked.
The teardown fence is installed as soon as explicit `disconnect()` is requested, including while
the existing 2-second connect/disconnect spacing is still being honored; this closes the race where
a peer connect could otherwise enter before native disconnect starts. Pending fallback tasks are
cancelled at adapter/plugin epoch reset. New native establishments remain recovery-blocked while the
exact teardown is unresolved, but idempotent access to a different already-connected healthy peer
remains allowed.

A caller can exhaust its timeout while waiting for another unavailable device;
this patch does not extend that deadline or count a deferred attempt as success.
If initial timeouts continue despite serialized connects, investigate
radio/firmware/notification starvation separately rather than widening this FIFO
into a global reset policy.

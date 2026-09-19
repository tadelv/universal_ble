# Android GATT lifecycle review for Decaid #871

Tracking: `tadelv/universal_ble#27`, stacked implementation PR #28.

This note records the native integration findings that are specific to part 3. The direct-admission state machine itself is documented in `android-direct-connect-admission.md`.

## Confirmed integration rules

- A `STATE_CONNECTED` callback is successful only when `status == BluetoothGatt.GATT_SUCCESS`, the `BluetoothGatt` object is still current and the attempt has not been cancelled.
- Ordinary teardown retains the address-to-GATT cache and `ownedGatts` entry until `BluetoothGatt.close()` succeeds. A thrown `close()` is a recovery-blocked state, not teardown confirmation.
- Close recovery retries cleanup only. It never starts a connection, toggles the adapter, refreshes the GATT cache or changes bonds.
- Waiting direct attempts are failed/retired when teardown becomes recovery-blocked. They cannot bypass the exact unresolved native owner.
- After the bounded automatic close-retry budget, the owner remains blocked. A later callback or explicit cleanup may retry the same owner; adapter/engine epoch invalidation is the only unconditional ownership reset.
- Adapter-off and engine-detach invalidate both admission and close-recovery epochs before old native callbacks/retries can mutate a later epoch.
- Established peer GATT traffic is not put behind the direct-connect FIFO and a failure on one device does not deliberately disconnect a healthy peer.

## GATT allocation audit

Decaid's Android recovery path uses direct `UniversalBle.connect(..., autoConnect: false)`, including the existing per-device reconnect cooldown path. Those clients are covered by direct admission.

`autoConnect=true` remains OS-managed and outside the FIFO. Decaid does not currently request it for the machine/scale path.

`getSystemDevices(withServices: non-empty)` can create temporary service-discovery GATT clients, but Decaid's Android discovery calls `getSystemDevices(withServices: [])`; therefore that attachment path is not allocated by the #871 flow and is not part of the direct-admission guarantee.

## Regression expectations

Android unit coverage must prove at least:

- non-success `STATE_CONNECTED` is rejected and its client closes before a waiting peer starts;
- queued and active cancellation cannot create a late client;
- a close exception retains cache/native ownership and blocks new direct establishment;
- a later successful close releases exactly that owner;
- exhausted close retries remain blocked instead of unlocking unsafely;
- adapter epoch reset fences posted retries;
- stale same-address callbacks cannot mutate a replacement;
- repeated failure/recovery cycles return owned-GATT state to zero after quiescence.

Hardware validation remains separate in decentespresso/decaid#877. A synthetic 133 or mocked close exception validates lifecycle invariants, not the field root cause.

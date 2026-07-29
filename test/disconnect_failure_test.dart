import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:universal_ble/universal_ble.dart';

import 'universal_ble_test_mock.dart';

class _DisconnectFailurePlatform extends UniversalBlePlatformMock {
  Object? disconnectError;
  bool emitConnectionEvent = true;
  bool eventState = false;

  @override
  Future<BleConnectionState> getConnectionState(String deviceId) async =>
      BleConnectionState.connected;

  @override
  Future<void> disconnect(String deviceId) async {
    if (disconnectError case final error?) throw error;
    if (emitConnectionEvent) updateConnection(deviceId, eventState);
  }
}

void main() {
  late _DisconnectFailurePlatform platform;

  setUp(() {
    platform = _DisconnectFailurePlatform();
    UniversalBle.setInstance(platform);
  });

  test('propagates native disconnect failures', () async {
    platform.disconnectError = StateError('failed');

    await expectLater(
      UniversalBle.disconnect(
        'device',
        timeout: const Duration(milliseconds: 20),
      ),
      throwsA(isA<ConnectionException>()),
    );
  });

  test('propagates missing disconnect events', () async {
    platform.emitConnectionEvent = false;

    await expectLater(
      UniversalBle.disconnect(
        'device',
        timeout: const Duration(milliseconds: 20),
      ),
      throwsA(isA<TimeoutException>()),
    );
  });

  test('fails when the connection remains active', () async {
    platform.eventState = true;

    await expectLater(
      UniversalBle.disconnect(
        'device',
        timeout: const Duration(milliseconds: 20),
      ),
      throwsA(isA<ConnectionException>()),
    );
  });
}

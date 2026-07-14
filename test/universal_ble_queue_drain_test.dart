import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:universal_ble/universal_ble.dart';

import 'universal_ble_test_mock.dart';

class _QueueDrainMockPlatform extends UniversalBlePlatformMock {
  /// deviceIds whose writeValue call should hang forever.
  final Set<String> hangingWrites = {};

  final List<String> disconnectCalls = [];
  final List<({String deviceId, String characteristic})> completedWrites = [];

  @override
  Future<BleConnectionState> getConnectionState(String deviceId) async =>
      BleConnectionState.connected;

  @override
  Future<void> connect(
    String deviceId, {
    Duration? connectionTimeout,
    bool autoConnect = false,
  }) async {
    // Never completes the connection — simulates a hung connect attempt.
  }

  @override
  Future<void> disconnect(String deviceId) async {
    disconnectCalls.add(deviceId);
    updateConnection(deviceId, false);
  }

  @override
  Future<void> writeValue(
    String deviceId,
    String service,
    String characteristic,
    Uint8List value,
    BleOutputProperty bleOutputProperty,
  ) async {
    if (hangingWrites.contains(deviceId)) {
      await Completer<void>().future; // never completes
    }
    completedWrites.add((deviceId: deviceId, characteristic: characteristic));
  }
}

void main() {
  const service = "180a";
  const characteristic = "2a29";
  final value = Uint8List.fromList([1]);

  late _QueueDrainMockPlatform mock;

  Future<void> write(
    String deviceId, {
    Duration timeout = const Duration(seconds: 5),
  }) {
    return UniversalBle.write(
      deviceId,
      service,
      characteristic,
      value,
      timeout: timeout,
    );
  }

  setUp(() {
    mock = _QueueDrainMockPlatform();
    UniversalBle.setInstance(mock);
    UniversalBle.queueType = QueueType.perDevice;
  });

  tearDown(() {
    UniversalBle.clearQueue();
    UniversalBle.queueType = QueueType.global;
  });

  group('queue drain on disconnect', () {
    test('pending commands fail immediately when the device disconnects',
        () async {
      mock.hangingWrites.add('device-a');

      // First write occupies the queue head (hangs), second is pending.
      final inFlight = write('device-a', timeout: const Duration(seconds: 1));
      final pending = write('device-a');

      await pumpEventQueue();
      mock.updateConnection('device-a', false);

      // Pending command fails right away with deviceDisconnected — it must
      // NOT wait out its own 5s timeout.
      await expectLater(
        pending.timeout(const Duration(milliseconds: 500)),
        throwsA(
          isA<UniversalBleException>().having(
            (e) => e.code,
            'code',
            UniversalBleErrorCode.deviceDisconnected,
          ),
        ),
      );

      // The in-flight command cannot be cancelled; it fails via its timeout.
      await expectLater(inFlight, throwsA(isA<TimeoutException>()));
    });

    test('drain only affects the disconnected device', () async {
      mock.hangingWrites.add('device-a');

      final pendingA =
          write('device-a', timeout: const Duration(seconds: 1)).then(
        (_) => 'completed',
        onError: (_) => 'failed',
      );
      final pendingB = write('device-b');

      await pumpEventQueue();
      mock.updateConnection('device-a', false);

      expect(await pendingB.then((_) => 'completed'), 'completed');
      expect(
        mock.completedWrites,
        contains((
          deviceId: 'device-b',
          characteristic: BleUuidParser.string(characteristic),
        )),
      );
      expect(await pendingA, 'failed');
    });
  });

  group('connect timeout', () {
    test('cancels the pending native connect attempt', () async {
      await expectLater(
        UniversalBle.connect(
          'device-a',
          timeout: const Duration(milliseconds: 200),
        ),
        throwsA(isA<TimeoutException>()),
      );

      // The timed-out attempt must be cancelled natively, otherwise the OS
      // can complete it later with nobody listening (zombie link).
      expect(mock.disconnectCalls, ['device-a']);
    });
  });

  group('disconnect is not queued', () {
    test('disconnect completes even when the device queue is stalled',
        () async {
      mock.hangingWrites.add('device-a');

      // Stall the device queue and stack a pending command behind it.
      // Expectations are attached up front: the drain errors fire while
      // disconnect() is still awaited below.
      final inFlight = expectLater(
        write('device-a', timeout: const Duration(seconds: 1)),
        throwsA(isA<TimeoutException>()),
      );
      final pending = expectLater(
        write('device-a'),
        throwsA(
          isA<UniversalBleException>().having(
            (e) => e.code,
            'code',
            UniversalBleErrorCode.deviceDisconnected,
          ),
        ),
      );
      await pumpEventQueue();

      // Disconnect must not wait behind the stalled queue.
      await UniversalBle.disconnect(
        'device-a',
        timeout: const Duration(milliseconds: 500),
      );
      expect(mock.disconnectCalls, ['device-a']);

      // The disconnect event drained the pending command.
      await pending;
      await inFlight;
    });
  });
}

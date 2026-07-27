import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:universal_ble/universal_ble.dart';

import 'universal_ble_test_mock.dart';

class _QueueDrainMockPlatform extends UniversalBlePlatformMock {
  /// deviceIds whose writeValue call should hang forever.
  final Set<String> hangingWrites = {};
  final Map<String, Completer<void>> writeBlockers = {};

  final List<String> disconnectCalls = [];
  final List<String> startedWrites = [];
  final List<({String deviceId, String characteristic})> completedWrites = [];

  @override
  Future<BleConnectionState> getConnectionState(String deviceId) async =>
      BleConnectionState.connected;

  @override
  Future<void> connect(
    String deviceId, {
    Duration? connectionTimeout,
    bool autoConnect = false,
    ConnectionPlatformConfig? platformConfig,
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
    startedWrites.add(deviceId);
    if (hangingWrites.contains(deviceId)) {
      await Completer<void>().future; // never completes
    }
    await writeBlockers[deviceId]?.future;
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
    String? queueId,
  }) {
    return UniversalBle.write(
      deviceId,
      service,
      characteristic,
      value,
      timeout: timeout,
      queueId: queueId,
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

    test('matches disconnect device IDs case-insensitively', () async {
      mock.hangingWrites.add('DEVICE-A');

      final inFlight = write('DEVICE-A', timeout: const Duration(seconds: 1));
      final pending = write('DEVICE-A');

      await pumpEventQueue();
      mock.updateConnection('device-a', false);

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

  group('queue timeout barrier', () {
    test('faults until explicitly recovered and ignores late completion', () async {
      final blocker = Completer<void>();
      mock.writeBlockers['device-a'] = blocker;

      final timedOut = write(
        'device-a',
        timeout: const Duration(milliseconds: 50),
      );
      final pending = expectLater(
        write('device-a'),
        throwsA(
          isA<UniversalBleException>().having(
            (e) => e.code,
            'code',
            UniversalBleErrorCode.operationCancelled,
          ),
        ),
      );
      await expectLater(timedOut, throwsA(isA<TimeoutException>()));
      await pending;
      await expectLater(
        write('device-a'),
        throwsA(
          isA<UniversalBleException>().having(
            (e) => e.code,
            'code',
            UniversalBleErrorCode.operationCancelled,
          ),
        ),
      );

      var diagnostics = UniversalBle.getQueueDiagnostics('device-a');
      expect(diagnostics.state, QueueLifecycleState.faulted);
      expect(diagnostics.pendingOperations, 0);
      expect(diagnostics.activeOperations, 1);
      expect(mock.startedWrites, ['device-a']);

      blocker.complete();
      await pumpEventQueue();
      diagnostics = UniversalBle.getQueueDiagnostics('device-a');
      expect(diagnostics.state, QueueLifecycleState.faulted);
      expect(diagnostics.activeOperations, 0);
      expect(mock.startedWrites, ['device-a']);

      UniversalBle.clearQueueWithError(
        'device-a',
        error: UniversalBleException(
          code: UniversalBleErrorCode.operationCancelled,
          message: 'application recovered the queue',
        ),
      );
      mock.writeBlockers.remove('device-a');
      await write('device-a');
      expect(
        UniversalBle.getQueueDiagnostics('device-a').state,
        QueueLifecycleState.running,
      );
      expect(mock.startedWrites, ['device-a', 'device-a']);
    });

    test('per-device fault is isolated and disconnect recovers it', () async {
      final blocker = Completer<void>();
      mock.writeBlockers['device-a'] = blocker;

      await expectLater(
        write('device-a', timeout: const Duration(milliseconds: 50)),
        throwsA(isA<TimeoutException>()),
      );
      await write('device-b');
      expect(mock.startedWrites, ['device-a', 'device-b']);

      mock.updateConnection('device-a', false);
      await pumpEventQueue();
      mock.writeBlockers.remove('device-a');
      await write('device-a');
      expect(mock.startedWrites, ['device-a', 'device-b', 'device-a']);
      blocker.complete();
    });

    test('global queue fault blocks every queued device', () async {
      UniversalBle.queueType = QueueType.global;
      final blocker = Completer<void>();
      mock.writeBlockers['device-a'] = blocker;

      await expectLater(
        write('device-a', timeout: const Duration(milliseconds: 50)),
        throwsA(isA<TimeoutException>()),
      );
      await expectLater(
        write('device-b'),
        throwsA(isA<UniversalBleException>()),
      );
      expect(mock.startedWrites, ['device-a']);
      expect(
        UniversalBle.getQueueDiagnostics('global').state,
        QueueLifecycleState.faulted,
      );
      blocker.complete();
    });

    test('custom queue fault remains isolated from device queues', () async {
      final blocker = Completer<void>();
      mock.writeBlockers['device-a'] = blocker;

      await expectLater(
        write(
          'device-a',
          queueId: 'custom',
          timeout: const Duration(milliseconds: 50),
        ),
        throwsA(isA<TimeoutException>()),
      );
      mock.writeBlockers.remove('device-a');
      await write('device-b');
      await expectLater(
        write('device-b', queueId: 'custom'),
        throwsA(isA<UniversalBleException>()),
      );
      expect(mock.startedWrites, ['device-a', 'device-b']);
      blocker.complete();
    });

    test('clear-all and platform replacement remove faulted queues', () async {
      var blocker = Completer<void>();
      mock.writeBlockers['device-a'] = blocker;
      await expectLater(
        write('device-a', timeout: const Duration(milliseconds: 50)),
        throwsA(isA<TimeoutException>()),
      );

      UniversalBle.clearQueue();
      mock.writeBlockers.remove('device-a');
      await write('device-a');
      blocker.complete();

      blocker = Completer<void>();
      mock.writeBlockers['device-a'] = blocker;
      await expectLater(
        write('device-a', timeout: const Duration(milliseconds: 50)),
        throwsA(isA<TimeoutException>()),
      );
      final replacement = _QueueDrainMockPlatform();
      UniversalBle.setInstance(replacement);
      await write('device-a');
      expect(replacement.startedWrites, ['device-a']);
      blocker.complete();
    });
  });

  group('QueueType.none', () {
    test('does not provide a timeout recovery barrier', () async {
      UniversalBle.queueType = QueueType.none;
      final blocker = Completer<void>();
      mock.writeBlockers['device-a'] = blocker;

      await expectLater(
        write('device-a', timeout: const Duration(milliseconds: 50)),
        throwsA(isA<TimeoutException>()),
      );
      mock.writeBlockers.remove('device-a');
      await write('device-a');
      expect(mock.startedWrites, ['device-a', 'device-a']);
      expect(
        UniversalBle.getQueueDiagnostics('device-a').state,
        QueueLifecycleState.notFound,
      );
      blocker.complete();
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

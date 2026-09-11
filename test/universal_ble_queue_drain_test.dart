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
  final List<Completer<void>> writeStartMarkers = [];
  final List<({String deviceId, String characteristic})> completedWrites = [];

  /// Authoritative link state. Absent entries report
  /// [BleConnectionState.connected], so a test states only the disconnect it
  /// needs.
  final Map<String, BleConnectionState> connectionStates = {};

  /// Holds the authoritative link probe open when set.
  Completer<BleConnectionState>? connectionStateBlocker;

  @override
  Future<BleConnectionState> getConnectionState(String deviceId) {
    final blocker = connectionStateBlocker;
    if (blocker != null) return blocker.future;
    return Future.value(
      connectionStates[deviceId.toLowerCase()] ?? BleConnectionState.connected,
    );
  }

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
    disconnectDevice(deviceId);
  }

  /// Records the authoritative link state for [deviceId] as disconnected and
  /// publishes the connection update.
  void disconnectDevice(String deviceId) {
    connectionStates[deviceId.toLowerCase()] = BleConnectionState.disconnected;
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
    if (writeStartMarkers.isNotEmpty) {
      writeStartMarkers.removeAt(0).complete();
    }
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
    UniversalBle.onQueueUpdate = null;
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
      mock.disconnectDevice('device-a');

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
      mock.disconnectDevice('device-a');

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

    test(
      'stale disconnect update does not cancel queued work on a live link',
      () async {
        final blocker = Completer<void>();
        mock.writeBlockers['device-a'] = blocker;

        // First write occupies the queue head, second stays pending.
        final inFlight = write('device-a');
        final pending = write('device-a');
        var pendingSettled = false;
        final pendingOutcome = pending
            .then<String>((_) => 'completed', onError: (_) => 'failed')
            .whenComplete(() => pendingSettled = true);

        await pumpEventQueue();
        expect(
          UniversalBle.getQueueDiagnostics('device-a').state,
          QueueDiagnosticsState.running,
        );

        // Late disconnect for a link the platform still reports connected.
        mock.connectionStates['device-a'] = BleConnectionState.connected;
        mock.updateConnection('device-a', false);
        await pumpEventQueue();

        expect(
          pendingSettled,
          isFalse,
          reason: 'a stale update must not settle the current queue',
        );
        expect(
          UniversalBle.getQueueDiagnostics('device-a').state,
          QueueDiagnosticsState.running,
          reason: 'a stale update must not dispose a live queue',
        );
        expect(
          mock.startedWrites,
          ['device-a'],
          reason:
              'confirming a live link must not dispatch the next command while '
              'one is still running',
        );

        blocker.complete();
        await inFlight;
        expect(await pendingOutcome, 'completed');
        expect(mock.completedWrites, hasLength(2));
      },
    );

    test(
      'genuine disconnect still cancels queued work when the link probe is slow',
      () async {
        final writeBlocker = Completer<void>();
        mock.writeBlockers['device-a'] = writeBlocker;
        final probe = Completer<BleConnectionState>();
        mock.connectionStateBlocker = probe;

        // First write occupies the queue head, second stays pending.
        final inFlight = write('device-a');
        final pending = write('device-a');
        final pendingOutcome = pending.then<String>(
          (_) => 'completed',
          onError: (_) => 'failed',
        );

        await pumpEventQueue();
        expect(mock.startedWrites, ['device-a']);

        mock.updateConnection('device-a', false);
        await pumpEventQueue();

        // The in-flight write finishes while the link probe is still pending.
        writeBlocker.complete();
        await inFlight;
        await pumpEventQueue();
        expect(
          mock.startedWrites,
          ['device-a'],
          reason:
              'an unconfirmed disconnect must hold the queue instead of '
              'letting the next command dispatch',
        );

        probe.complete(BleConnectionState.disconnected);
        await pumpEventQueue();
        expect(await pendingOutcome, 'failed');
        expect(mock.startedWrites, ['device-a']);
      },
    );

    test(
      'a command issued while the disconnect is unconfirmed does not dispatch',
      () async {
        final probe = Completer<BleConnectionState>();
        mock.connectionStateBlocker = probe;

        mock.updateConnection('device-a', false);
        await pumpEventQueue();

        // No queue existed when the event arrived: the queue created now must
        // still start held.
        final pending = write('device-a');
        final pendingOutcome = pending.then<String>(
          (_) => 'completed',
          onError: (_) => 'failed',
        );
        await pumpEventQueue();
        expect(
          mock.startedWrites,
          isEmpty,
          reason: 'an unconfirmed link must not dispatch new work',
        );

        probe.complete(BleConnectionState.disconnected);
        await pumpEventQueue();
        expect(await pendingOutcome, 'failed');
        expect(mock.startedWrites, isEmpty);
      },
    );

    test('a superseded probe cannot settle the queue', () async {
      final first = Completer<BleConnectionState>();
      mock.connectionStateBlocker = first;
      mock.updateConnection('device-a', false);
      await pumpEventQueue();

      final second = Completer<BleConnectionState>();
      mock.connectionStateBlocker = second;
      mock.updateConnection('device-a', false);
      await pumpEventQueue();

      // The older probe resolves as connected first: the newest hold still owns
      // the queue, so nothing may dispatch yet.
      first.complete(BleConnectionState.connected);
      await pumpEventQueue();
      final pending = write('device-a');
      final pendingOutcome = pending.then<String>(
        (_) => 'completed',
        onError: (_) => 'failed',
      );
      await pumpEventQueue();
      expect(
        mock.startedWrites,
        isEmpty,
        reason: 'a superseded probe must not release the hold',
      );

      second.complete(BleConnectionState.connected);
      await pumpEventQueue();
      expect(await pendingOutcome, 'completed');
      expect(mock.startedWrites, ['device-a']);
    });

    test(
      'queued work resumes when a slow link probe proves the link is alive',
      () async {
        final writeBlocker = Completer<void>();
        mock.writeBlockers['device-a'] = writeBlocker;
        final probe = Completer<BleConnectionState>();
        mock.connectionStateBlocker = probe;

        final inFlight = write('device-a');
        final pending = write('device-a');

        await pumpEventQueue();
        mock.updateConnection('device-a', false);
        await pumpEventQueue();

        writeBlocker.complete();
        await inFlight;
        await pumpEventQueue();
        expect(
          mock.completedWrites.where((entry) => entry.deviceId == 'device-a'),
          hasLength(1),
        );

        probe.complete(BleConnectionState.connected);
        await expectLater(pending, completes);
        expect(
          mock.completedWrites.where((entry) => entry.deviceId == 'device-a'),
          hasLength(2),
        );
      },
    );

    test(
      'a reconnect in progress does not dispatch queued work from the previous link',
      () async {
        // First write occupies the queue head (hangs); the second is pending.
        final blocker = Completer<void>();
        mock.writeBlockers['device-a'] = blocker;

        final inFlight = write('device-a');
        final pending = write('device-a');

        await pumpEventQueue();
        expect(mock.startedWrites, ['device-a']);

        // The expectation is attached before the update fires: the drain settles
        // the pending command while the queue is pumped below.
        final pendingOutcome = expectLater(
          pending.timeout(const Duration(milliseconds: 500)),
          throwsA(
            isA<UniversalBleException>().having(
              (e) => e.code,
              'code',
              UniversalBleErrorCode.deviceDisconnected,
            ),
          ),
        );

        // A reconnect is in progress: the platform reports `connecting`.
        mock.connectionStates['device-a'] = BleConnectionState.connecting;
        mock.updateConnection('device-a', false);
        await pumpEventQueue();
        await pendingOutcome;

        // Connecting does not preserve the previous link's queued work, and no
        // second GATT command may start merely because the replacement link is
        // connecting.
        expect(mock.startedWrites, ['device-a']);

        // The queue was cleared rather than left paused.
        expect(
          UniversalBle.getQueueDiagnostics('device-a').state,
          QueueDiagnosticsState.notFound,
        );

        blocker.complete();
        await inFlight;

        // The cleared queue must not wedge later work.
        await write('device-a');
        expect(mock.startedWrites, ['device-a', 'device-a']);
      },
    );

    test('drain only affects the disconnected device', () async {
      mock.hangingWrites.add('device-a');

      final pendingA =
          write('device-a', timeout: const Duration(seconds: 1)).then(
        (_) => 'completed',
        onError: (_) => 'failed',
      );
      final pendingB = write('device-b');

      await pumpEventQueue();
      mock.disconnectDevice('device-a');

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
      expect(diagnostics.state, QueueDiagnosticsState.faulted);
      expect(diagnostics.pendingOperations, 0);
      expect(diagnostics.activeOperations, 1);
      expect(mock.startedWrites, ['device-a']);

      blocker.complete();
      await pumpEventQueue();
      diagnostics = UniversalBle.getQueueDiagnostics('device-a');
      expect(diagnostics.state, QueueDiagnosticsState.faulted);
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
        QueueDiagnosticsState.running,
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

      mock.disconnectDevice('device-a');
      await pumpEventQueue();
      mock.writeBlockers.remove('device-a');
      await write('device-a');
      expect(mock.startedWrites, ['device-a', 'device-b', 'device-a']);
      blocker.complete();
    });

    test('global queue fault blocks every device and public id recovers it',
        () async {
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
        UniversalBle.getQueueDiagnostics(UniversalBle.globalQueueId).state,
        QueueDiagnosticsState.faulted,
      );

      UniversalBle.clearQueue(UniversalBle.globalQueueId);
      mock.writeBlockers.remove('device-a');
      await write('device-b');
      expect(mock.startedWrites, ['device-a', 'device-b']);
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

    test(
      'setInstance callback commands use only the replacement platform',
      () async {
        final oldStarted = Completer<void>();
        final oldBlocker = Completer<void>();
        mock.writeStartMarkers.add(oldStarted);
        mock.writeBlockers['device-a'] = oldBlocker;
        final oldWrite = write('device-a');
        final oldPending = expectLater(
          write('device-a'),
          throwsA(
            isA<UniversalBleException>().having(
              (e) => e.code,
              'code',
              UniversalBleErrorCode.operationCancelled,
            ),
          ),
        );
        await oldStarted.future;

        final replacement = _QueueDrainMockPlatform();
        final replacementStarted = Completer<void>();
        final replacementNextStarted = Completer<void>();
        final replacementBlocker = Completer<void>();
        replacement.writeStartMarkers.addAll([
          replacementStarted,
          replacementNextStarted,
        ]);
        replacement.writeBlockers['device-a'] = replacementBlocker;

        Future<void>? callbackWrite;
        UniversalBle.onQueueUpdate = (id, remaining) {
          if (id == 'device-a' && remaining == 0 && callbackWrite == null) {
            callbackWrite = write('device-a');
          }
        };

        UniversalBle.setInstance(replacement);

        expect(mock.startedWrites, ['device-a']);
        expect(replacement.startedWrites, ['device-a']);
        await oldPending;
        await replacementStarted.future;

        final replacementNext = expectLater(write('device-a'), completes);
        oldBlocker.complete();
        await oldWrite;
        mock.updateConnection('device-a', false);
        await pumpEventQueue();
        expect(replacement.startedWrites, ['device-a']);

        replacementBlocker.complete();
        await replacementNextStarted.future;
        await callbackWrite;
        await replacementNext;
        expect(replacement.startedWrites, ['device-a', 'device-a']);
      },
    );

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
        QueueDiagnosticsState.notFound,
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

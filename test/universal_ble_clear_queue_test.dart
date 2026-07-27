import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:universal_ble/universal_ble.dart';

import 'universal_ble_test_mock.dart';

class _QueueClearMockPlatform extends UniversalBlePlatformMock {
  final Map<String, Completer<void>> blockers = {};

  @override
  Future<void> writeValue(
    String deviceId,
    String service,
    String characteristic,
    Uint8List value,
    BleOutputProperty bleOutputProperty,
  ) async {
    await blockers[deviceId]?.future;
  }
}

void main() {
  const service = '180a';
  const characteristic = '2a29';
  final value = Uint8List.fromList([1]);

  late _QueueClearMockPlatform mock;

  Future<void> write(String deviceId, {String? queueId}) => UniversalBle.write(
    deviceId,
    service,
    characteristic,
    value,
    queueId: queueId,
  );

  setUp(() {
    mock = _QueueClearMockPlatform();
    UniversalBle.setInstance(mock);
    UniversalBle.queueType = QueueType.perDevice;
  });

  tearDown(() {
    UniversalBle.clearQueue();
    UniversalBle.queueType = QueueType.global;
  });

  test('legacy clearQueue clears all queues with the default error', () async {
    final releaseA = Completer<void>();
    final releaseB = Completer<void>();
    mock.blockers.addAll({'device-a': releaseA, 'device-b': releaseB});

    final inFlightA = write('device-a');
    final inFlightB = write('device-b');
    final pendingA = write('device-a');
    final pendingB = write('device-b');
    await pumpEventQueue();

    final cancelledA = expectLater(
      pendingA,
      throwsA(
        isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Queue Cancelled'),
        ),
      ),
    );
    final cancelledB = expectLater(
      pendingB,
      throwsA(
        isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Queue Cancelled'),
        ),
      ),
    );

    UniversalBle.clearQueue();

    await Future.wait([cancelledA, cancelledB]);
    releaseA.complete();
    releaseB.complete();
    await Future.wait([inFlightA, inFlightB]);
  });

  test(
    'legacy clearQueue clears a selected queue with the default error',
    () async {
      final release = Completer<void>();
      mock.blockers['device-a'] = release;

      final inFlight = write('device-a');
      final pending = write('device-a');
      await pumpEventQueue();
      final cancelled = expectLater(
        pending,
        throwsA(
          isA<Exception>().having(
            (e) => e.toString(),
            'message',
            contains('Queue Cancelled'),
          ),
        ),
      );

      UniversalBle.clearQueue('device-a');

      await cancelled;
      release.complete();
      await inFlight;
    },
  );

  test('structured clear result is available through the public API', () {
    final error = UniversalBleException(
      code: UniversalBleErrorCode.operationCancelled,
      message: 'reset',
    );

    final summary = UniversalBle.clearQueueWithResult(
      id: 'UNKNOWN',
      error: error,
    );

    expect(summary.pendingCancelled, 0);
    expect(summary.activeOperations, 0);
    expect(summary.queues.single.queueId, 'unknown');
    expect(summary.queues.single.queueType, QueueType.perDevice);
    expect(summary.queues.single.state, QueueLifecycleState.notFound);
    expect(
      summary.queues.single.errorCode,
      UniversalBleErrorCode.operationCancelled,
    );
  });

  test(
    'typed clear preserves the error, in-flight work, and other queues',
    () async {
      final releaseA = Completer<void>();
      final releaseB = Completer<void>();
      final releaseGlobal = Completer<void>();
      final releaseCustom = Completer<void>();
      mock.blockers.addAll({
        'device-a': releaseA,
        'device-b': releaseB,
        'device-global': releaseGlobal,
        'device-custom': releaseCustom,
      });

      final inFlightA = write('device-a');
      final inFlightB = write('device-b');
      final inFlightGlobal = write('device-global', queueId: 'global');
      final inFlightCustom = write('device-custom', queueId: 'custom');
      final pendingA1 = write('device-a');
      final pendingA2 = write('device-a');
      final pendingB = write('device-b');
      final pendingGlobal = write('device-global', queueId: 'global');
      final pendingCustom = write('device-custom', queueId: 'custom');
      await pumpEventQueue();

      final error = UniversalBleException(
        code: UniversalBleErrorCode.operationCancelled,
        message: 'Cancelled because the application reset the BLE queue',
        details: {'reason': 'reset'},
      );
      final cancelled = [pendingA1, pendingA2]
          .map(
            (future) => expectLater(
              future,
              throwsA(
                allOf(
                  same(error),
                  isA<UniversalBleException>()
                      .having(
                        (e) => e.code,
                        'code',
                        UniversalBleErrorCode.operationCancelled,
                      )
                      .having((e) => e.message, 'message', error.message)
                      .having((e) => e.details, 'details', error.details),
                ),
              ),
            ),
          )
          .toList();

      UniversalBle.clearQueueWithError('device-a', error: error);

      await Future.wait(cancelled);
      releaseA.complete();
      releaseB.complete();
      releaseGlobal.complete();
      releaseCustom.complete();
      await Future.wait([
        inFlightA,
        inFlightB,
        inFlightGlobal,
        inFlightCustom,
        pendingB,
        pendingGlobal,
        pendingCustom,
      ]);
    },
  );
}

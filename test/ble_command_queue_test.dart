import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:universal_ble/src/utils/ble_command_queue.dart';
import 'package:universal_ble/universal_ble.dart';

void main() {
  group('BleCommandQueue', () {
    test('global queue executes commands sequentially', () async {
      final commandQueue = BleCommandQueue();
      final order = <int>[];

      final firstStarted = Completer<void>();
      final releaseFirst = Completer<void>();
      final secondStarted = Completer<void>();

      final first = commandQueue.queueCommand(() async {
        firstStarted.complete();
        await releaseFirst.future;
        order.add(1);
      });
      final second = commandQueue.queueCommand(() async {
        secondStarted.complete();
        order.add(2);
      });

      await firstStarted.future;
      expect(order, isEmpty);

      releaseFirst.complete();
      await first;
      await secondStarted.future;
      await second;

      expect(order, [1, 2]);
    });

    test('null queueId uses the global queue', () async {
      final commandQueue = BleCommandQueue();
      final order = <int>[];

      final firstStarted = Completer<void>();
      final release = Completer<void>();
      final secondStarted = Completer<void>();

      final first = commandQueue.queueCommand(() async {
        firstStarted.complete();
        await release.future;
        order.add(1);
      }, queueId: null);
      final second = commandQueue.queueCommand(() async {
        secondStarted.complete();
        order.add(2);
      }, queueId: null);

      await firstStarted.future;
      expect(order, isEmpty);

      release.complete();
      await first;
      await secondStarted.future;
      await second;

      expect(order, [1, 2]);
    });

    test(
      'custom queueId creates an independent queue in global mode',
      () async {
        final commandQueue = BleCommandQueue();
        final order = <String>[];

        final releaseDefault = Completer<void>();
        final releaseCustom = Completer<void>();

        commandQueue.queueCommand(() async {
          await releaseDefault.future;
          order.add('default');
        }, queueId: null);
        commandQueue.queueCommand(() async {
          await releaseCustom.future;
          order.add('custom');
        }, queueId: 'tilta');

        await pumpEventQueue();
        expect(order, isEmpty);

        releaseCustom.complete();
        await pumpEventQueue();

        expect(order, ['custom']);

        releaseDefault.complete();
        await pumpEventQueue();

        expect(order, ['custom', 'default']);
      },
    );

    test('perDevice queue isolates commands by device', () async {
      final commandQueue = BleCommandQueue(queueType: QueueType.perDevice);
      final order = <String>[];

      final releaseA = Completer<void>();
      final releaseB = Completer<void>();
      final deviceBStarted = Completer<void>();

      commandQueue.queueCommand(() async {
        await releaseA.future;
        order.add('device-a');
      }, deviceId: 'device-a');
      commandQueue.queueCommand(() async {
        deviceBStarted.complete();
        await releaseB.future;
        order.add('device-b');
      }, deviceId: 'device-b');

      await deviceBStarted.future;
      expect(order, isEmpty);

      releaseB.complete();
      await pumpEventQueue();
      expect(order, ['device-b']);

      releaseA.complete();
      await pumpEventQueue();
      expect(order, ['device-b', 'device-a']);
    });

    test('perDevice queueId overrides device routing', () async {
      final commandQueue = BleCommandQueue(queueType: QueueType.perDevice);
      final order = <String>[];

      final releaseShared = Completer<void>();
      final secondStarted = Completer<void>();

      commandQueue.queueCommand(
        () async {
          await releaseShared.future;
          order.add('first');
        },
        deviceId: 'device-a',
        queueId: 'shared',
      );
      commandQueue.queueCommand(
        () async {
          secondStarted.complete();
          order.add('second');
        },
        deviceId: 'device-b',
        queueId: 'shared',
      );

      await pumpEventQueue();
      expect(order, isEmpty);

      releaseShared.complete();
      await secondStarted.future;
      await pumpEventQueue();

      expect(order, ['first', 'second']);
    });

    test('QueueType.none runs commands without queueing', () async {
      final commandQueue = BleCommandQueue(queueType: QueueType.none);
      final order = <int>[];

      final firstStarted = Completer<void>();
      final secondStarted = Completer<void>();
      final releaseFirst = Completer<void>();

      commandQueue.queueCommand(() async {
        firstStarted.complete();
        await releaseFirst.future;
        order.add(1);
      });
      commandQueue.queueCommand(() async {
        secondStarted.complete();
        order.add(2);
      });

      await firstStarted.future;
      await secondStarted.future;

      expect(order, [2]);

      releaseFirst.complete();
      await pumpEventQueue();

      expect(order, [2, 1]);
    });

    test('queueCommandWithoutTimeout bypasses global timeout', () async {
      final commandQueue = BleCommandQueue()
        ..timeout = const Duration(milliseconds: 10);

      await expectLater(
        commandQueue.queueCommand(
          () => Future<void>.delayed(const Duration(milliseconds: 100)),
        ),
        throwsA(isA<TimeoutException>()),
      );

      await expectLater(
        commandQueue.queueCommandWithoutTimeout(
          () => Future<void>.delayed(const Duration(milliseconds: 30)),
        ),
        completes,
      );
    });

    test('onQueueUpdate reports remaining items per queue id', () async {
      final commandQueue = BleCommandQueue();
      final updates = <String, List<int>>{};

      commandQueue.onQueueUpdate = (id, remaining) {
        updates.putIfAbsent(id, () => []).add(remaining);
      };

      final release = Completer<void>();
      final started = Completer<void>();

      final first = commandQueue.queueCommand(() async {
        started.complete();
        await release.future;
      }, queueId: 'tilta');
      commandQueue.queueCommand(() async {}, queueId: 'tilta');
      commandQueue.queueCommand(() async {}, queueId: 'tilta');

      await started.future;
      expect(updates['tilta'], contains(3));

      release.complete();
      await first;
      await pumpEventQueue();

      expect(updates['tilta']!.last, 0);
    });

    test('clear-all detaches queues before zero callbacks', () async {
      final commandQueue = BleCommandQueue();
      final started = Completer<void>();
      final release = Completer<void>();
      final active = commandQueue.queueCommand(() async {
        started.complete();
        await release.future;
      }, queueId: 'old');
      await started.future;

      Future<int>? replacement;
      commandQueue.onQueueUpdate = (id, remaining) {
        if (id == 'old' && remaining == 0 && replacement == null) {
          replacement = commandQueue.queueCommand(
            () async => 7,
            queueId: 'old',
          );
        }
      };

      commandQueue.clearQueue(null);

      expect(replacement, isNotNull);
      expect(await replacement, 7);
      expect(
        commandQueue.clearQueue('old').queues.single.state,
        QueueLifecycleState.cleared,
      );
      release.complete();
      await active;
    });

    test('cleared queue cannot update its replacement', () async {
      final commandQueue = BleCommandQueue();
      final updates = <int>[];
      commandQueue.onQueueUpdate = (id, remaining) {
        if (id == 'device-a') updates.add(remaining);
      };
      final oldStarted = Completer<void>();
      final releaseOld = Completer<void>();
      final old = commandQueue.queueCommand(() async {
        oldStarted.complete();
        await releaseOld.future;
      }, queueId: 'device-a');
      await oldStarted.future;
      commandQueue.clearQueue('device-a');

      final newStarted = Completer<void>();
      final releaseNew = Completer<void>();
      final replacement = commandQueue.queueCommand(() async {
        newStarted.complete();
        await releaseNew.future;
      }, queueId: 'device-a');
      await newStarted.future;

      releaseOld.complete();
      await old;
      await pumpEventQueue();
      expect(updates.last, 1);

      releaseNew.complete();
      await replacement;
      await pumpEventQueue();
      expect(updates.last, 0);
    });

    test('clearQueue reports active and pending work and final zero', () async {
      final commandQueue = BleCommandQueue(queueType: QueueType.perDevice);
      final updates = <int>[];
      final release = Completer<void>();
      final started = Completer<void>();
      commandQueue.onQueueUpdate = (id, remaining) {
        if (id == 'device-a') updates.add(remaining);
      };

      final active = commandQueue.queueCommand(() async {
        started.complete();
        await release.future;
      }, deviceId: 'device-a');
      final pending = [
        commandQueue.queueCommand(() async {}, deviceId: 'device-a'),
        commandQueue.queueCommand(() async {}, deviceId: 'device-a'),
      ];
      final cancelled = pending.map(
        (future) => expectLater(future, throwsA(isA<Exception>())),
      );

      await started.future;
      final summary = commandQueue.clearQueue('device-a');

      expect(summary.pendingCancelled, 2);
      expect(summary.activeOperations, 1);
      expect(summary.queues, hasLength(1));
      expect(summary.queues.single.queueId, 'device-a');
      expect(summary.queues.single.queueType, QueueType.perDevice);
      expect(summary.queues.single.state, QueueLifecycleState.cleared);
      expect(
        summary.queues.single.reason,
        QueueClearReason.defaultCancellation,
      );
      expect(summary.queues.single.errorCode, isNull);
      expect(updates.last, 0);

      await Future.wait(cancelled);
      release.complete();
      await active;
    });

    test('clearQueue reports an unknown queue without throwing', () {
      final commandQueue = BleCommandQueue(queueType: QueueType.perDevice);

      final summary = commandQueue.clearQueue('MISSING');

      expect(summary.pendingCancelled, 0);
      expect(summary.activeOperations, 0);
      expect(summary.queues.single.queueId, 'missing');
      expect(summary.queues.single.state, QueueLifecycleState.notFound);
    });

    test('clearQueue reports the queue type used at creation', () async {
      final commandQueue = BleCommandQueue(queueType: QueueType.global);
      final started = Completer<void>();
      final release = Completer<void>();
      final active = commandQueue.queueCommand(() async {
        started.complete();
        await release.future;
      }, queueId: 'old');
      await started.future;
      commandQueue.queueType = QueueType.perDevice;

      final summary = commandQueue.clearQueue('old');

      expect(summary.queues.single.queueType, QueueType.global);
      release.complete();
      await active;
    });

    test('clearQueue reports a known empty queue as cleared', () async {
      final commandQueue = BleCommandQueue();
      await commandQueue.queueCommand(() async {}, queueId: 'empty');

      final summary = commandQueue.clearQueue('empty');

      expect(summary.pendingCancelled, 0);
      expect(summary.activeOperations, 0);
      expect(summary.queues.single.state, QueueLifecycleState.cleared);
    });

    test(
      'clearQueue cancels pending commands for a specific queue id',
      () async {
        final commandQueue = BleCommandQueue();
        final order = <String>[];

        final release = Completer<void>();
        final started = Completer<void>();

        commandQueue.queueCommand(() async {
          started.complete();
          await release.future;
          order.add('in-flight');
        }, queueId: 'tilta');
        final pending = commandQueue.queueCommand(() async {
          order.add('pending');
          return 'pending';
        }, queueId: 'tilta');

        await started.future;
        commandQueue.clearQueue('tilta');

        await expectLater(
          pending,
          throwsA(
            isA<Exception>().having(
              (e) => e.toString(),
              'message',
              contains('Queue Cancelled'),
            ),
          ),
        );

        release.complete();
        await pumpEventQueue();

        expect(order, ['in-flight']);
      },
    );

    test('clearQueue without id clears all queues', () async {
      final commandQueue = BleCommandQueue();
      final releaseDefault = Completer<void>();
      final releaseCustom = Completer<void>();
      final defaultStarted = Completer<void>();
      final customStarted = Completer<void>();

      commandQueue.queueCommand(() async {
        defaultStarted.complete();
        await releaseDefault.future;
      }, queueId: null);
      commandQueue.queueCommand(() async {
        customStarted.complete();
        await releaseCustom.future;
      }, queueId: 'tilta');

      final pendingDefault = commandQueue.queueCommand(
        () async {},
        queueId: null,
      );
      final pendingCustom = commandQueue.queueCommand(
        () async {},
        queueId: 'tilta',
      );

      await defaultStarted.future;
      await customStarted.future;

      commandQueue.clearQueue(null);

      await expectLater(pendingDefault, throwsA(isA<Exception>()));
      await expectLater(pendingCustom, throwsA(isA<Exception>()));

      releaseDefault.complete();
      releaseCustom.complete();
    });

    test(
      'clear-all reports deterministic per-queue and aggregate counts',
      () async {
        final commandQueue = BleCommandQueue();
        final releases = {
          'zeta': Completer<void>(),
          'alpha': Completer<void>(),
        };
        final started = {'zeta': Completer<void>(), 'alpha': Completer<void>()};
        final active = <Future<void>>[];
        final pending = <Future<void>>[];
        final updates = <String, int>{};
        commandQueue.onQueueUpdate = (id, remaining) => updates[id] = remaining;

        for (final id in ['zeta', 'alpha']) {
          active.add(
            commandQueue.queueCommand(() async {
              started[id]!.complete();
              await releases[id]!.future;
            }, queueId: id),
          );
          pending.add(commandQueue.queueCommand(() async {}, queueId: id));
        }
        final cancelled = pending.map(
          (future) => expectLater(future, throwsA(isA<Exception>())),
        );
        await Future.wait(started.values.map((completer) => completer.future));

        final error = UniversalBleException(
          code: UniversalBleErrorCode.operationCancelled,
          message: 'reset',
        );
        final summary = commandQueue.clearQueue(null, error: error);

        expect(summary.queues.map((result) => result.queueId), [
          'alpha',
          'zeta',
        ]);
        expect(summary.pendingCancelled, 2);
        expect(summary.activeOperations, 2);
        expect(updates, {'alpha': 0, 'zeta': 0});
        expect(
          summary.queues.map((result) => result.reason),
          everyElement(QueueClearReason.suppliedError),
        );
        expect(
          summary.queues.map((result) => result.errorCode),
          everyElement(UniversalBleErrorCode.operationCancelled),
        );

        await Future.wait(cancelled);
        for (final release in releases.values) {
          release.complete();
        }
        await Future.wait(active);
      },
    );

    test('clearQueue surfaces a custom error to pending commands', () async {
      final commandQueue = BleCommandQueue();
      final release = Completer<void>();
      final started = Completer<void>();

      commandQueue.queueCommand(() async {
        started.complete();
        await release.future;
      }, queueId: 'tilta');
      final pending = commandQueue.queueCommand(() async {}, queueId: 'tilta');

      await started.future;
      commandQueue.clearQueue('tilta', error: StateError('device gone'));

      await expectLater(
        pending,
        throwsA(
          isA<StateError>().having((e) => e.message, 'message', 'device gone'),
        ),
      );

      release.complete();
    });

    test('new commands recreate a cleared queue id', () async {
      final commandQueue = BleCommandQueue();

      commandQueue.clearQueue(BleCommandQueue.globalQueueId);

      expect(await commandQueue.queueCommand(() async => 7), 7);
    });
  });
}

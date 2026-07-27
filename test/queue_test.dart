import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:universal_ble/src/queue.dart';

void main() {
  group('Queue', () {
    test('executes commands sequentially in order', () async {
      final queue = Queue();
      final order = <int>[];

      final firstStarted = Completer<void>();
      final releaseFirst = Completer<void>();
      final secondStarted = Completer<void>();

      final future1 = queue.add(() async {
        firstStarted.complete();
        await releaseFirst.future;
        order.add(1);
        return 1;
      });
      final future2 = queue.add(() async {
        secondStarted.complete();
        order.add(2);
        return 2;
      });

      await firstStarted.future;
      expect(order, isEmpty);

      releaseFirst.complete();
      await future1;
      await secondStarted.future;
      await future2;

      expect(order, [1, 2]);
    });

    test('completes void commands with null', () async {
      final queue = Queue();

      await queue.add(() async {});

      expect(await queue.add(() async => null), isNull);
    });

    test('returns command results', () async {
      final queue = Queue();

      expect(await queue.add(() async => 42), 42);
      expect(await queue.add(() async => 'ok'), 'ok');
    });

    test('propagates command errors', () async {
      final queue = Queue();

      final future = queue.add(() async {
        throw StateError('failed');
      });

      await expectLater(future, throwsA(isA<StateError>()));
    });

    test('synchronous closure failure reports error and cleans up', () async {
      final queue = Queue();

      final failed = queue.add<int>(() {
        throw StateError('sync-boom');
      });

      await expectLater(failed, throwsA(isA<StateError>()));

      // No unresolved operations should linger after a sync throw.
      final result = queue.dispose();
      expect(result.activeOperations, 0);
      expect(result.pendingCancelled, 0);
    });

    test(
      'synchronous closure failure allows next command to proceed',
      () async {
        final queue = Queue();

        // First command throws synchronously — swallow the error.
        queue.add<int>(() => throw StateError('boom')).catchError((_) => 0);

        // Second command must still execute normally.
        expect(await queue.add(() async => 42), 42);
      },
    );

    test('times out slow commands', () async {
      final queue = Queue();

      final future = queue.add(
        () => Future<void>.delayed(const Duration(seconds: 5)),
        const Duration(milliseconds: 50),
      );

      await expectLater(future, throwsA(isA<TimeoutException>()));
    });

    test('reports remaining items via onRemainingItemsUpdate', () async {
      final queue = Queue();
      final remaining = <int>[];

      queue.onRemainingItemsUpdate = remaining.add;

      final release = Completer<void>();
      final started = Completer<void>();

      final first = queue.add(() async {
        started.complete();
        await release.future;
      });
      queue.add(() async {});
      queue.add(() async {});

      await started.future;
      expect(remaining, contains(3));

      release.complete();
      await first;
      await pumpEventQueue();

      expect(remaining.last, 0);
    });

    test('dispose completes pending commands with error', () async {
      final queue = Queue();
      final started = Completer<void>();

      queue.add(() async {
        started.complete();
        await Future<void>.delayed(const Duration(seconds: 5));
      });

      final pending = queue.add(() async => 'pending');

      await started.future;
      queue.dispose();

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
    });

    test('dispose reports pending and active operations', () async {
      final queue = Queue();
      final started = Completer<void>();
      final release = Completer<void>();

      final active = queue.add(() async {
        started.complete();
        await release.future;
      });
      final pending = [queue.add(() async {}), queue.add(() async {})];
      final cancelled = pending.map(
        (future) => expectLater(future, throwsA(isA<Exception>())),
      );

      await started.future;
      final result = queue.dispose();

      expect(result.pendingCancelled, 2);
      expect(result.activeOperations, 1);
      await Future.wait(cancelled);
      release.complete();
      await active;
    });

    test('dispose reports a timed-out native Future as unresolved', () async {
      final queue = Queue();
      final release = Completer<void>();

      final timedOut = queue.add(
        () async => release.future,
        const Duration(milliseconds: 10),
      );
      await expectLater(timedOut, throwsA(isA<TimeoutException>()));

      final result = queue.dispose();

      expect(result.pendingCancelled, 0);
      expect(result.activeOperations, 1);
      release.complete();
      await pumpEventQueue();
    });

    test('dispose prevents adding new commands', () {
      final queue = Queue()..dispose();

      expect(
        () => queue.add(() async {}),
        throwsA(
          isA<Exception>().having(
            (e) => e.toString(),
            'message',
            contains('Queue Cancelled'),
          ),
        ),
      );
    });

    // --- Unresolved-operation tracking (memory-retention regression) ---

    test('detached operation does not affect replacement queue', () async {
      final release1 = Completer<void>();
      final release2 = Completer<void>();

      // Queue 1: start a command that will time out.
      final queue1 = Queue();
      final timedOut1 = queue1.add(
        () async => release1.future,
        const Duration(milliseconds: 10),
      );
      await expectLater(timedOut1, throwsA(isA<TimeoutException>()));
      queue1.dispose();

      // Queue 2: replacement queue (same conceptual device).
      final queue2 = Queue();
      final order = <String>[];
      final started2 = Completer<void>();

      final second = queue2.add(() async {
        order.add('second-start');
        started2.complete();
        await release2.future;
        order.add('second-end');
      });

      await started2.future;

      // Complete the old underlying future — must not touch queue2.
      release1.complete();
      await pumpEventQueue();
      expect(order, ['second-start']);

      release2.complete();
      await second;
      expect(order, ['second-start', 'second-end']);
    });

    test('repeated timeout and clear tracks correct counts', () async {
      for (var i = 0; i < 3; i++) {
        final queue = Queue();
        final release = Completer<void>();

        final timedOut = queue.add(
          () async => release.future,
          const Duration(milliseconds: 10),
        );
        await expectLater(timedOut, throwsA(isA<TimeoutException>()));

        final result = queue.dispose();
        expect(result.activeOperations, 1);
        expect(result.pendingCancelled, 0);

        // Complete old future after clear.
        release.complete();
        await pumpEventQueue();
      }
    });

    test('in-flight command completes after dispose', () async {
      final queue = Queue();
      final release = Completer<void>();
      final started = Completer<void>();

      final inFlight = queue.add(() async {
        started.complete();
        await release.future;
        return 'done';
      });

      final pending = queue.add(() async => 'pending');
      unawaited(pending.catchError((_) => 'ignored'));

      await started.future;
      queue.dispose();

      release.complete();
      expect(await inFlight, 'done');
    });
  });
}

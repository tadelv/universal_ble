import 'package:universal_ble/src/queue.dart';
import 'package:universal_ble/universal_ble.dart';

/// Set queue type and queue commands
class BleCommandQueue {
  QueueType queueType;
  Duration? timeout = const Duration(seconds: 10);
  OnQueueUpdate? onQueueUpdate;
  final Map<String, Queue> _queueMap = {};
  static const String globalQueueId = 'global';

  BleCommandQueue({this.queueType = QueueType.global});

  Future<T> queueCommand<T>(
    Future<T> Function() command, {
    String? deviceId,
    Duration? timeout,
    String? queueId,
    String? coalesceKey,
  }) {
    Duration? timeoutDuration = timeout ?? this.timeout;
    if (timeoutDuration == null) {
      return queueCommandWithoutTimeout(
        command,
        deviceId: deviceId,
        queueId: queueId,
        coalesceKey: coalesceKey,
      );
    }
    return switch (queueType) {
      QueueType.global => _queue(queueId).add(
        command,
        timeoutDuration,
        coalesceKey,
      ),
      QueueType.perDevice => _queue(queueId ?? deviceId?.toLowerCase()).add(
        command,
        timeoutDuration,
        coalesceKey,
      ),
      QueueType.none => command().timeout(timeoutDuration),
    };
  }

  Future<T> queueCommandWithoutTimeout<T>(
    Future<T> Function() command, {
    String? deviceId,
    String? queueId,
    String? coalesceKey,
  }) {
    return switch (queueType) {
      QueueType.global => _queue(queueId).add(
        command,
        null,
        coalesceKey,
      ),
      QueueType.perDevice => _queue(queueId ?? deviceId?.toLowerCase()).add(
        command,
        null,
        coalesceKey,
      ),
      QueueType.none => command(),
    };
  }

  Queue _queue(String? id) {
    final queueKey = id ?? globalQueueId;
    return _queueMap[queueKey] ?? _newQueue(queueKey);
  }

  Queue _newQueue(String id) {
    final queue = Queue();
    queue.onRemainingItemsUpdate = (int items) {
      try {
        onQueueUpdate?.call(id, items);
      } catch (_) {}
    };
    _queueMap[id] = queue;
    return queue;
  }

  QueueClearSummary clearQueue(String? id, {Object? error}) {
    final reason = error == null
        ? QueueClearReason.defaultCancellation
        : QueueClearReason.suppliedError;
    final errorCode = error is UniversalBleException ? error.code : null;

    if (id == null) {
      final entries = _queueMap.entries.toList()
        ..sort((a, b) => a.key.compareTo(b.key));
      final results = [
        for (final entry in entries)
          _clearResult(entry.key, entry.value, reason, errorCode, error),
      ];
      _queueMap.clear();
      return QueueClearSummary(results);
    }

    final queueKey = _queueMap.containsKey(id) ? id : id.toLowerCase();
    final queue = _queueMap.remove(queueKey);
    if (queue == null) {
      return QueueClearSummary([
        QueueClearResult(
          queueId: queueKey,
          queueType: queueType,
          pendingCancelled: 0,
          activeOperations: 0,
          reason: reason,
          errorCode: errorCode,
          state: QueueLifecycleState.notFound,
        ),
      ]);
    }
    return QueueClearSummary([
      _clearResult(queueKey, queue, reason, errorCode, error),
    ]);
  }

  QueueClearResult _clearResult(
    String queueId,
    Queue queue,
    QueueClearReason reason,
    UniversalBleErrorCode? errorCode,
    Object? error,
  ) {
    final result = queue.dispose(error);
    return QueueClearResult(
      queueId: queueId,
      queueType: queueType,
      pendingCancelled: result.pendingCancelled,
      activeOperations: result.activeOperations,
      reason: reason,
      errorCode: errorCode,
      state: QueueLifecycleState.cleared,
    );
  }
}

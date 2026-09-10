import 'package:universal_ble/src/queue.dart';
import 'package:universal_ble/universal_ble.dart';

/// Set queue type and queue commands
class BleCommandQueue {
  QueueType queueType;
  Duration? timeout = const Duration(seconds: 10);
  OnQueueUpdate? onQueueUpdate;
  final Map<String, ({Queue queue, QueueType type})> _queueMap = {};
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

  String _queueKey(String id) =>
      _queueMap.containsKey(id) ? id : id.toLowerCase();

  /// Hold [id]'s queue without deciding the outcome of its pending items.
  /// Returns whether a queue existed; a missing queue needs no hold because
  /// nothing can be dispatched from it.
  bool pauseQueue(String? id) {
    final entry = id == null ? null : _queueMap[_queueKey(id)];
    if (entry == null) return false;
    entry.queue.pause();
    return true;
  }

  /// Resume [id]'s queue after a hold proves the connection is still live.
  void resumeQueue(String? id) {
    if (id == null) return;
    _queueMap[_queueKey(id)]?.queue.resume();
  }

  Queue _queue(String? id) {
    final queueKey = id ?? globalQueueId;
    return _queueMap[queueKey]?.queue ?? _newQueue(queueKey);
  }

  Queue _newQueue(String id) {
    final queue = Queue(
      timeoutError: UniversalBleException(
        code: UniversalBleErrorCode.operationCancelled,
        message: 'Command cancelled: queue faulted after operation timeout',
      ),
    );
    queue.onRemainingItemsUpdate = (int items) {
      try {
        onQueueUpdate?.call(id, items);
      } catch (_) {}
    };
    _queueMap[id] = (queue: queue, type: queueType);
    return queue;
  }

  QueueDiagnostics getQueueDiagnostics(String id) {
    final queueKey = _queueKey(id);
    final entry = _queueMap[queueKey];
    if (entry == null) {
      return QueueDiagnostics(
        queueId: queueKey,
        queueType: queueType,
        pendingOperations: 0,
        activeOperations: 0,
        state: QueueDiagnosticsState.notFound,
      );
    }
    return QueueDiagnostics(
      queueId: queueKey,
      queueType: entry.type,
      pendingOperations: entry.queue.pendingOperations,
      activeOperations: entry.queue.activeOperations,
      state: entry.queue.isFaulted
          ? QueueDiagnosticsState.faulted
          : QueueDiagnosticsState.running,
    );
  }

  QueueClearSummary clearQueue(String? id, {Object? error}) {
    final reason = error == null
        ? QueueClearReason.defaultCancellation
        : QueueClearReason.suppliedError;
    final errorCode = error is UniversalBleException ? error.code : null;

    if (id == null) {
      final entries = _queueMap.entries.toList()
        ..sort((a, b) => a.key.compareTo(b.key));
      _queueMap.clear();
      final results = [
        for (final entry in entries)
          _clearResult(
            entry.key,
            entry.value.queue,
            entry.value.type,
            reason,
            errorCode,
            error,
          ),
      ];
      return QueueClearSummary(results);
    }

    final queueKey = _queueKey(id);
    final queueEntry = _queueMap.remove(queueKey);
    if (queueEntry == null) {
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
      _clearResult(
        queueKey,
        queueEntry.queue,
        queueEntry.type,
        reason,
        errorCode,
        error,
      ),
    ]);
  }

  QueueClearResult _clearResult(
    String queueId,
    Queue queue,
    QueueType queueType,
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

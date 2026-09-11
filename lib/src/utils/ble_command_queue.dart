import 'package:universal_ble/src/queue.dart';
import 'package:universal_ble/universal_ble.dart';

/// Set queue type and queue commands
class BleCommandQueue {
  QueueType queueType;
  Duration? timeout = const Duration(seconds: 10);
  OnQueueUpdate? onQueueUpdate;
  final Map<String, ({Queue queue, QueueType type})> _queueMap = {};
  final Map<String, int> _holds = {};
  int _lastHoldToken = 0;
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

  /// Holds [id]'s queue while a disconnect is still unconfirmed and returns the
  /// token that owns the hold.
  ///
  /// The newest hold owns the queue, so an older confirmation can neither resume
  /// nor clear it. A queue created while the hold is live starts paused: a
  /// command issued during the confirmation window must not dispatch onto a link
  /// that may already be gone.
  int holdQueue(String id) {
    final queueKey = _queueKey(id);
    final token = ++_lastHoldToken;
    _holds[queueKey] = token;
    _queueMap[queueKey]?.queue.pause();
    return token;
  }

  /// Whether [token] still owns [id]'s hold.
  bool ownsQueueHold(String id, int token) => _holds[_queueKey(id)] == token;

  /// Releases the hold owned by [token] and resumes [id]'s queue. A superseded
  /// hold is ignored.
  void releaseQueueHold(String id, int token) {
    final queueKey = _queueKey(id);
    if (_holds[queueKey] != token) return;
    _holds.remove(queueKey);
    _queueMap[queueKey]?.queue.resume();
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
    if (_holds.containsKey(id)) queue.pause();
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
      _holds.clear();
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
    _holds.remove(queueKey);
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

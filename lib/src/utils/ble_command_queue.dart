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
      QueueType.perDevice => _queue(queueId ?? deviceId).add(
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
      QueueType.perDevice => _queue(queueId ?? deviceId).add(
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

  void clearQueue(String? id, {Object? error}) {
    if (id == null) {
      _queueMap.forEach((k, v) => v.dispose(error));
      _queueMap.clear();
    } else {
      _queueMap[id]?.dispose(error);
      _queueMap.remove(id);
    }
  }
}

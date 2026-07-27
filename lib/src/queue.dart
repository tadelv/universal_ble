import 'dart:async';

/// Original Author: Ryan Knell (https://github.com/rknell/dart_queue)

/// Queue to execute Futures in order.
/// It awaits each future before executing the next one.
///
/// Supports optional coalescing: items with the same [coalesceKey] replace
/// earlier pending items, preventing queue bloat from rapid repeated writes
/// to the same BLE characteristic. Pass [coalesceKey] to [add] to opt in.
class Queue {
  final Set<int> _activeItems = {};
  final Set<int> _unresolvedItems = {};
  int _lastProcessId = 0;
  bool _isCancelled = false;
  final List<_QueuedFuture> _nextCycle = [];
  Function(int)? onRemainingItemsUpdate;

  Future<T> add<T>(Future<T> Function() closure, [Duration? timeout, String? coalesceKey]) {
    if (_isCancelled) throw Exception('Queue Cancelled');
    if (coalesceKey != null) {
      _cancelWhere((item) => item.coalesceKey == coalesceKey);
    }
    final completer = Completer<T>();
    _nextCycle.add(_QueuedFuture<T>(closure, completer, timeout, coalesceKey: coalesceKey));
    _updateRemainingItems();
    if (_activeItems.isEmpty) _queueUpNext();
    return completer.future;
  }

  /// Cancel all pending items and stop accepting new ones.
  /// Pending items complete with [error], or a generic
  /// `Queue Cancelled` exception when none is given.
  ///
  /// Returns counts captured before clearing. Active operations keep running.
  ({int pendingCancelled, int activeOperations}) dispose([Object? error]) {
    final pendingCancelled = _nextCycle.length;
    final activeOperations = _unresolvedItems.length;
    for (final item in _nextCycle) {
      item.completer.completeError(error ?? Exception('Queue Cancelled'));
    }
    _nextCycle.removeWhere((item) => item.completer.isCompleted);
    _isCancelled = true;
    final remainingItemsUpdate = onRemainingItemsUpdate;
    onRemainingItemsUpdate = null;
    remainingItemsUpdate?.call(0);
    return (
      pendingCancelled: pendingCancelled,
      activeOperations: activeOperations,
    );
  }

  /// Cancel pending (not yet executing) items matching [test].
  void _cancelWhere(bool Function(_QueuedFuture) test) {
    for (final item in _nextCycle.toList()) {
      if (test(item)) {
        item.completer.completeError(Exception('Replaced by newer write'));
        _nextCycle.remove(item);
      }
    }
    _updateRemainingItems();
  }

  void _queueUpNext() {
    if (_nextCycle.isNotEmpty && !_isCancelled && _activeItems.length <= 1) {
      final processId = _lastProcessId;
      _activeItems.add(processId);
      _unresolvedItems.add(processId);
      final item = _nextCycle.first;
      _lastProcessId++;
      _nextCycle.remove(item);
      item.onComplete = () async {
        _activeItems.remove(processId);
        _updateRemainingItems();
        _queueUpNext();
      };
      item.onUnderlyingComplete = () => _unresolvedItems.remove(processId);
      unawaited(item.execute());
    }
  }

  void _updateRemainingItems() {
    int remainingQueueItems = _nextCycle.length + _activeItems.length;
    onRemainingItemsUpdate?.call(remainingQueueItems);
  }
}

class _QueuedFuture<T> {
  final Completer completer;
  final Future<T> Function() closure;
  Function? onComplete;
  Function? onUnderlyingComplete;
  final Duration? timeout;
  final String? coalesceKey;

  _QueuedFuture(this.closure, this.completer, this.timeout,
      {this.onComplete, this.coalesceKey});

  Future<void> execute() async {
    Future<T> runUnderlying() async {
      try {
        return await closure();
      } finally {
        onUnderlyingComplete?.call();
      }
    }

    try {
      T result;
      if (timeout != null) {
        result = await runUnderlying().timeout(timeout!);
      } else {
        result = await runUnderlying();
      }
      if (result != null) {
        completer.complete(result);
      } else {
        completer.complete(null);
      }
      await Future.microtask(() {});
    } catch (e, stack) {
      completer.completeError(e, stack);
    } finally {
      if (onComplete != null) onComplete?.call();
    }
  }
}

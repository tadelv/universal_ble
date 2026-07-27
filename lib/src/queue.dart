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
  final Map<int, _OperationToken> _unresolvedTokens = {};
  int _lastProcessId = 0;
  bool _isCancelled = false;
  final List<_QueuedFuture> _nextCycle = [];
  Function(int)? onRemainingItemsUpdate;

  Future<T> add<T>(
    Future<T> Function() closure, [
    Duration? timeout,
    String? coalesceKey,
  ]) {
    if (_isCancelled) throw Exception('Queue Cancelled');
    if (coalesceKey != null) {
      _cancelWhere((item) => item.coalesceKey == coalesceKey);
    }
    final completer = Completer<T>();
    _nextCycle.add(
      _QueuedFuture<T>(closure, completer, timeout, coalesceKey: coalesceKey),
    );
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
    final activeOperations = _unresolvedTokens.length;
    for (final item in _nextCycle) {
      item.completer.completeError(error ?? Exception('Queue Cancelled'));
    }
    _nextCycle.clear();
    for (final token in _unresolvedTokens.values) {
      token.detach();
    }
    _unresolvedTokens.clear();
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
      final item = _nextCycle.first;
      _lastProcessId++;
      _nextCycle.remove(item);
      final token = _OperationToken(processId, this);
      item._token = token;
      _unresolvedTokens[processId] = token;
      unawaited(item.execute());
    }
  }

  void _updateRemainingItems() {
    int remainingQueueItems = _nextCycle.length + _activeItems.length;
    onRemainingItemsUpdate?.call(remainingQueueItems);
  }
}

/// Detachable handle that tracks one in-flight operation.
///
/// The async frame of [runUnderlying] captures only this token and a local
/// copy of the command closure — never the full [_QueuedFuture] or [Queue].
///
/// After [detach] the token becomes a no-op; completing the underlying native
/// future does not reach back into a queue that has already been disposed.
class _OperationToken {
  final int processId;
  Queue? _queue;

  _OperationToken(this.processId, this._queue);

  void underlyingComplete() {
    _queue?._unresolvedTokens.remove(processId);
  }

  void dartComplete() {
    final q = _queue;
    if (q == null) return;
    q._activeItems.remove(processId);
    q._updateRemainingItems();
    q._queueUpNext();
  }

  void detach() {
    _queue = null;
  }
}

class _QueuedFuture<T> {
  final Completer completer;
  Future<T> Function()? _closure;
  _OperationToken? _token;
  final Duration? timeout;
  final String? coalesceKey;

  _QueuedFuture(
    Future<T> Function() closure,
    this.completer,
    this.timeout, {
    this.coalesceKey,
  }) : _closure = closure;

  Future<void> execute() async {
    final token = _token;
    final closure = _closure;

    Future<T> runUnderlying() async {
      try {
        return await closure!();
      } finally {
        token?.underlyingComplete();
      }
    }

    try {
      T result;
      if (timeout != null) {
        result = await runUnderlying().timeout(timeout!);
      } else {
        result = await runUnderlying();
      }
      if (!completer.isCompleted) {
        if (result != null) {
          completer.complete(result);
        } else {
          completer.complete(null);
        }
      }
      await Future.microtask(() {});
    } catch (e, stack) {
      if (!completer.isCompleted) {
        completer.completeError(e, stack);
      }
    } finally {
      _closure = null;
      token?.dartComplete();
    }
  }
}

import 'dart:async';

/// Original Author: Ryan Knell (https://github.com/rknell/dart_queue)

/// Queue to execute Futures in order.
/// It awaits each future before executing the next one.
///
/// Supports optional coalescing: items with the same [coalesceKey] replace
/// earlier pending items, preventing queue bloat from rapid repeated writes
/// to the same BLE characteristic. Pass [coalesceKey] to [add] to opt in.
class Queue {
  final Object? timeoutError;
  final Set<int> _activeItems = {};
  final Map<int, _OperationToken> _unresolvedTokens = {};
  int _lastProcessId = 0;
  _QueueState _state = _QueueState.running;
  bool _paused = false;
  final List<_QueuedFuture> _nextCycle = [];
  Function(int)? onRemainingItemsUpdate;

  Queue({this.timeoutError});

  bool get isFaulted => _state == _QueueState.faulted;
  int get pendingOperations => _nextCycle.length;
  int get activeOperations => _unresolvedTokens.length;

  Future<T> add<T>(
    Future<T> Function() closure, [
    Duration? timeout,
    String? coalesceKey,
  ]) {
    if (_state == _QueueState.faulted) {
      throw timeoutError ?? Exception('Queue faulted after operation timeout');
    }
    if (_state == _QueueState.disposed) throw Exception('Queue Cancelled');
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
    _state = _QueueState.disposed;
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

  void _fault() {
    if (_state != _QueueState.running) return;
    _state = _QueueState.faulted;
    final error =
        timeoutError ?? Exception('Queue faulted after operation timeout');
    for (final item in _nextCycle) {
      item.completer.completeError(error);
    }
    _nextCycle.clear();
  }

  /// Stop dispatching pending items without deciding their outcome.
  ///
  /// Used when a disconnect still has to be confirmed against the platform:
  /// holding the queue keeps the next item from starting on a link that may
  /// already be gone. [resume] restores dispatch, [dispose] cancels the
  /// pending items.
  void pause() => _paused = true;

  /// Resume dispatch of pending items.
  void resume() {
    if (!_paused) return;
    _paused = false;
    _updateRemainingItems();
    if (_state == _QueueState.running) _queueUpNext();
  }

  void _queueUpNext() {
    if (_paused) return;
    if (_nextCycle.isNotEmpty &&
        _state == _QueueState.running &&
        _activeItems.length <= 1) {
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
/// The tracking async frame ([_QueuedFuture._trackUnderlying]) captures only
/// the already-created [Future] and this token — never the command closure,
/// [_QueuedFuture], or [Queue].
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

  void dartComplete({bool timedOut = false}) {
    final q = _queue;
    if (q == null) return;
    q._activeItems.remove(processId);
    if (timedOut) q._fault();
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

  /// Track [underlying] completion without capturing the command closure.
  ///
  /// The async frame of this helper retains only the already-created
  /// [Future] and the detachable [_OperationToken]. It never sees
  /// [_QueuedFuture], the command closure, the [Queue], or queue
  /// callbacks.
  static Future<T> _trackUnderlying<T>(
    Future<T> underlying,
    _OperationToken? token,
  ) async {
    try {
      return await underlying;
    } finally {
      token?.underlyingComplete();
    }
  }

  Future<void> execute() async {
    final token = _token;
    var closure = _closure;
    _closure = null;

    // Invoke the closure *before* entering any long-running tracking async
    // frame.  A synchronous throw is caught here so the completer and token
    // are cleaned up without leaving a dangling async frame.
    late final Future<T> underlying;
    try {
      underlying = closure!();
    } catch (e, stack) {
      token?.underlyingComplete();
      token?.dartComplete();
      if (!completer.isCompleted) {
        completer.completeError(e, stack);
      }
      return;
    } finally {
      closure = null;
    }

    var timedOut = false;
    try {
      T result;
      if (timeout != null) {
        result = await _trackUnderlying(underlying, token).timeout(
          timeout!,
          onTimeout: () {
            timedOut = true;
            throw TimeoutException('Future not completed', timeout);
          },
        );
      } else {
        result = await _trackUnderlying(underlying, token);
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
      token?.dartComplete(timedOut: timedOut);
    }
  }
}

enum _QueueState { running, faulted, disposed }

import 'package:universal_ble/src/models/queue_type.dart';
import 'package:universal_ble/src/universal_ble.g.dart';

/// Why pending operations were cancelled by a queue clear.
enum QueueClearReason { defaultCancellation, suppliedError }

/// The queue's lifecycle state after a clear attempt.
enum QueueLifecycleState { cleared, notFound }

/// Structured, payload-free diagnostics for one queue clear.
class QueueClearResult {
  final String queueId;
  final QueueType queueType;
  final int pendingCancelled;

  /// Operations already executing whose native Futures remain unresolved.
  final int activeOperations;
  final QueueClearReason reason;
  final UniversalBleErrorCode? errorCode;
  final QueueLifecycleState state;

  const QueueClearResult({
    required this.queueId,
    required this.queueType,
    required this.pendingCancelled,
    required this.activeOperations,
    required this.reason,
    required this.errorCode,
    required this.state,
  });
}

/// Per-queue and aggregate diagnostics from a queue clear operation.
class QueueClearSummary {
  final List<QueueClearResult> queues;

  QueueClearSummary(List<QueueClearResult> queues)
    : queues = List.unmodifiable(queues);

  int get pendingCancelled =>
      queues.fold(0, (total, result) => total + result.pendingCancelled);

  int get activeOperations =>
      queues.fold(0, (total, result) => total + result.activeOperations);
}

/// Reason a scan failed to start or was aborted by the platform.
/// Mirrors Android `ScanCallback.SCAN_FAILED_*` codes; other platforms
/// currently never report scan failures.
enum ScanFailureReason {
  alreadyStarted,
  applicationRegistrationFailed,
  internalError,
  featureUnsupported,
  outOfHardwareResources,

  /// Android throttles apps that start more than 5 scans per 30 seconds.
  /// Subsequent scans silently return no results — back off before retrying.
  scanningTooFrequently,
  unknown,
}

/// A platform scan failure (e.g. Android `ScanCallback.onScanFailed`).
class ScanFailure {
  /// Raw platform error code.
  final int errorCode;

  /// Symbolic name of [errorCode] (e.g. "SCAN_FAILED_SCANNING_TOO_FREQUENTLY").
  final String message;

  const ScanFailure(this.errorCode, this.message);

  ScanFailureReason get reason => switch (errorCode) {
        1 => ScanFailureReason.alreadyStarted,
        2 => ScanFailureReason.applicationRegistrationFailed,
        3 => ScanFailureReason.internalError,
        4 => ScanFailureReason.featureUnsupported,
        5 => ScanFailureReason.outOfHardwareResources,
        6 => ScanFailureReason.scanningTooFrequently,
        _ => ScanFailureReason.unknown,
      };

  @override
  String toString() => 'ScanFailure($errorCode, $message)';
}

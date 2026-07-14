import 'package:flutter_test/flutter_test.dart';
import 'package:universal_ble/universal_ble.dart';

import 'universal_ble_test_mock.dart';

class _ScanFailureMockPlatform extends UniversalBlePlatformMock {}

void main() {
  group('ScanFailure', () {
    test('maps Android error codes to reasons', () {
      expect(
        const ScanFailure(1, 'SCAN_FAILED_ALREADY_STARTED').reason,
        ScanFailureReason.alreadyStarted,
      );
      expect(
        const ScanFailure(6, 'SCAN_FAILED_SCANNING_TOO_FREQUENTLY').reason,
        ScanFailureReason.scanningTooFrequently,
      );
      expect(
        const ScanFailure(42, 'ErrorCode: 42').reason,
        ScanFailureReason.unknown,
      );
    });

    test('updateScanFailure feeds scanFailureStream and callback', () async {
      final mock = _ScanFailureMockPlatform();
      UniversalBle.setInstance(mock);

      ScanFailure? callbackFailure;
      UniversalBle.onScanFailure = (failure) => callbackFailure = failure;

      final streamed = UniversalBle.scanFailureStream.first;
      mock.updateScanFailure(
        const ScanFailure(6, 'SCAN_FAILED_SCANNING_TOO_FREQUENTLY'),
      );

      final failure = await streamed;
      expect(failure.reason, ScanFailureReason.scanningTooFrequently);
      expect(callbackFailure?.errorCode, 6);
    });
  });

  group('clearGattCache', () {
    test('throws notSupported on platforms without an implementation',
        () async {
      UniversalBle.setInstance(_ScanFailureMockPlatform());

      await expectLater(
        UniversalBle.clearGattCache('device-a'),
        throwsA(
          isA<UniversalBleException>().having(
            (e) => e.code,
            'code',
            UniversalBleErrorCode.notSupported,
          ),
        ),
      );
    });
  });
}

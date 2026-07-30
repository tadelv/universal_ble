import 'dart:async';
import 'dart:io';

import 'package:bluez/bluez.dart';
import 'package:dbus/dbus.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:universal_ble/src/universal_ble_linux/universal_ble_linux.dart';
import 'package:universal_ble/universal_ble.dart';

class _AdapterObject extends DBusObject {
  _AdapterObject() : super(DBusObjectPath('/org/bluez/hci0'));

  int removeDeviceCalls = 0;

  @override
  Map<String, Map<String, DBusValue>> get interfacesAndProperties => {
    'org.bluez.Adapter1': {
      'Address': DBusString('00:11:22:33:44:55'),
      'AddressType': DBusString('public'),
      'Name': DBusString('hci0'),
      'Powered': DBusBoolean(true),
      'Discovering': DBusBoolean(false),
    },
  };

  @override
  Future<DBusMethodResponse> handleMethodCall(DBusMethodCall call) async {
    if (call.interface != 'org.bluez.Adapter1') {
      return DBusMethodErrorResponse.unknownInterface();
    }
    if (call.name == 'RemoveDevice') removeDeviceCalls++;
    return DBusMethodSuccessResponse();
  }
}

class _DeviceObject extends DBusObject {
  _DeviceObject({required this.paired, required this.connected})
    : super(DBusObjectPath('/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF'));

  bool paired;
  bool connected;
  bool servicesResolved = false;

  @override
  Map<String, Map<String, DBusValue>> get interfacesAndProperties => {
    'org.bluez.Device1': {
      'Adapter': DBusObjectPath('/org/bluez/hci0'),
      'Address': DBusString('AA:BB:CC:DD:EE:FF'),
      'AddressType': DBusString('public'),
      'Name': DBusString('DE1'),
      'Connected': DBusBoolean(connected),
      'Paired': DBusBoolean(paired),
      'ServicesResolved': DBusBoolean(servicesResolved),
      'RSSI': DBusInt16(-40),
      'UUIDs': DBusArray.string(const []),
      'ManufacturerData': DBusDict(
        DBusSignature('q'),
        DBusSignature('v'),
        const {},
      ),
      'ServiceData': DBusDict.stringVariant(const {}),
    },
  };

  Future<void> setConnected(bool value) async {
    connected = value;
    await emitPropertiesChanged(
      'org.bluez.Device1',
      changedProperties: {'Connected': DBusBoolean(value)},
    );
  }

  @override
  Future<DBusMethodResponse> handleMethodCall(DBusMethodCall call) async {
    if (call.interface != 'org.bluez.Device1') {
      return DBusMethodErrorResponse.unknownInterface();
    }
    if (call.name == 'Connect') await setConnected(true);
    if (call.name == 'Disconnect') await setConnected(false);
    return DBusMethodSuccessResponse();
  }
}

void main() {
  late DBusServer server;
  late DBusAddress address;
  late DBusClient bluezService;
  late DBusObject manager;
  late _AdapterObject adapter;
  late _DeviceObject device;
  late StreamController<BluezOwnerChange> owners;
  late UniversalBleLinux plugin;
  var clientsCreated = 0;

  Future<void> pumpUntil(bool Function() condition) async {
    for (var i = 0; i < 100 && !condition(); i++) {
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
    expect(condition(), isTrue);
  }

  setUp(() async {
    server = DBusServer();
    address = await server.listenAddress(
      DBusAddress.unix(dir: Directory.systemTemp),
    );
    bluezService = DBusClient(address);
    await bluezService.requestName('org.bluez');
    manager = DBusObject(DBusObjectPath('/'), isObjectManager: true);
    await bluezService.registerObject(manager);
    adapter = _AdapterObject();
    device = _DeviceObject(paired: false, connected: true);
    await bluezService.registerObject(adapter);
    await bluezService.registerObject(device);
    owners = StreamController<BluezOwnerChange>.broadcast();
    plugin = UniversalBleLinux(
      clientFactory: () {
        clientsCreated++;
        return BlueZClient(bus: DBusClient(address));
      },
      ownerChanges: owners.stream,
      currentOwner: () async => ':1.1',
    );
  });

  tearDown(() async {
    await plugin.dispose();
    await owners.close();
    await bluezService.close();
    await server.close();
  });

  test('owner replacement rebuilds the same adapter path', () async {
    expect(
      await plugin.getBluetoothAvailabilityState(),
      AvailabilityState.poweredOn,
    );
    expect(clientsCreated, 1);

    final availability = <AvailabilityState>[];
    final connections = <bool>[];
    final availabilitySub = plugin.availabilityStream.listen(availability.add);
    final connectionSub = plugin
        .connectionStream('AA:BB:CC:DD:EE:FF')
        .listen(connections.add);

    owners.add(const BluezOwnerChange(':1.1', null));
    await pumpUntil(() => connections.contains(false));
    owners.add(const BluezOwnerChange(null, ':1.2'));
    await pumpUntil(() => clientsCreated == 2 && plugin.isInitialized);

    expect(availability, contains(AvailabilityState.unknown));
    expect(
      await plugin.getBluetoothAvailabilityState(),
      AvailabilityState.poweredOn,
    );
    await availabilitySub.cancel();
    await connectionSub.cancel();
  }, skip: Platform.isWindows);

  test(
    'rapid owner replacements keep only the latest runtime',
    () async {
      expect(
        await plugin.getBluetoothAvailabilityState(),
        AvailabilityState.poweredOn,
      );

      for (var i = 2; i <= 10; i++) {
        owners.add(BluezOwnerChange(':1.${i - 1}', ':1.$i'));
      }
      await pumpUntil(() => clientsCreated == 2 && plugin.isInitialized);
      await Future<void>.delayed(const Duration(milliseconds: 100));

      expect(clientsCreated, 2);
      expect(
        await plugin.getBluetoothAvailabilityState(),
        AvailabilityState.poweredOn,
      );
    },
    skip: Platform.isWindows,
  );

  test(
    'service discovery reports disconnect before caller timeout',
    () async {
      await plugin.getBluetoothAvailabilityState();
      final discovery = plugin.discoverServices('AA:BB:CC:DD:EE:FF', false);
      await device.setConnected(false);

      await expectLater(
        discovery,
        throwsA(
          isA<UniversalBleException>().having(
            (error) => error.code,
            'code',
            UniversalBleErrorCode.deviceDisconnected,
          ),
        ),
      );
    },
    skip: Platform.isWindows,
  );

  test(
    'cache reset removes unpaired devices and preserves paired devices',
    () async {
      await plugin.getBluetoothAvailabilityState();
      await plugin.clearGattCache('AA:BB:CC:DD:EE:FF');
      expect(adapter.removeDeviceCalls, 1);

      await plugin.dispose();
      await bluezService.unregisterObject(device);
      device = _DeviceObject(paired: true, connected: true);
      await bluezService.registerObject(device);
      plugin = UniversalBleLinux(
        clientFactory: () => BlueZClient(bus: DBusClient(address)),
        ownerChanges: owners.stream,
        currentOwner: () async => ':1.2',
      );
      await plugin.getBluetoothAvailabilityState();
      await plugin.clearGattCache('AA:BB:CC:DD:EE:FF');
      expect(adapter.removeDeviceCalls, 1);
    },
    skip: Platform.isWindows,
  );

  test(
    'adapter removal emits disconnected before device eviction',
    () async {
      await plugin.getBluetoothAvailabilityState();
      final updates = <bool>[];
      final subscription = plugin
          .connectionStream('AA:BB:CC:DD:EE:FF')
          .listen(updates.add);
      await device.setConnected(true);
      await pumpUntil(() => updates.contains(true));

      await manager.emitInterfacesRemoved(adapter.path, const [
        'org.bluez.Adapter1',
      ]);
      await pumpUntil(() => updates.contains(false));

      expect(updates, [true, false]);
      await subscription.cancel();
    },
    skip: Platform.isWindows,
  );

  test(
    'repeated scans reuse the device property subscription',
    () async {
      await plugin.getBluetoothAvailabilityState();
      final updates = <bool>[];
      final subscription = plugin
          .connectionStream('AA:BB:CC:DD:EE:FF')
          .listen(updates.add);

      for (var i = 0; i < 3; i++) {
        await plugin.startScan();
        await plugin.stopScan();
      }
      await device.setConnected(false);
      await pumpUntil(() => updates.isNotEmpty);
      await Future<void>.delayed(const Duration(milliseconds: 100));

      expect(updates, [false]);
      await subscription.cancel();
    },
    skip: Platform.isWindows,
  );

  test(
    'runtime loss during service discovery reports disconnected',
    () async {
      await plugin.getBluetoothAvailabilityState();
      final discovery = plugin.discoverServices('AA:BB:CC:DD:EE:FF', false);
      owners.add(const BluezOwnerChange(':1.1', null));

      await expectLater(
        discovery,
        throwsA(
          isA<UniversalBleException>().having(
            (error) => error.code,
            'code',
            UniversalBleErrorCode.deviceDisconnected,
          ),
        ),
      );
    },
    skip: Platform.isWindows,
  );
}

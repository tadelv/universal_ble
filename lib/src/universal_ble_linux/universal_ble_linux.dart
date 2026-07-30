import 'dart:async';

import 'package:bluez/bluez.dart';
import 'package:dbus/dbus.dart';
import 'package:flutter/services.dart';
import 'package:universal_ble/src/models/model_exports.dart';
import 'package:universal_ble/src/utils/universal_ble_error_parser.dart';
import 'package:universal_ble/src/utils/universal_ble_filter_util.dart';
import 'package:universal_ble/src/universal_ble.g.dart';
import 'package:universal_ble/src/interfaces/universal_ble_platform_interface.dart';
import 'package:universal_ble/src/utils/universal_logger.dart';
import 'package:universal_ble/src/universal_ble_exceptions.dart';

class BluezOwnerChange {
  const BluezOwnerChange(this.oldOwner, this.newOwner);

  final String? oldOwner;
  final String? newOwner;
}

class UniversalBleLinux extends UniversalBlePlatform {
  UniversalBleLinux({
    BlueZClient Function()? clientFactory,
    Stream<BluezOwnerChange>? ownerChanges,
    Future<String?> Function()? currentOwner,
  }) : _clientFactory = clientFactory ?? BlueZClient.new {
    if (ownerChanges != null && currentOwner != null) {
      _ownerChanges = ownerChanges;
      _currentOwner = currentOwner;
      return;
    }
    final bus = DBusClient.system();
    _ownerBus = bus;
    _ownerChanges = bus.nameOwnerChanged
        .where((event) => event.name == 'org.bluez')
        .map((event) => BluezOwnerChange(event.oldOwner, event.newOwner));
    _currentOwner = () => bus.getNameOwner('org.bluez');
  }

  static UniversalBleLinux? _instance;
  static UniversalBleLinux get instance => _instance ??= UniversalBleLinux();

  bool isInitialized = false;

  final BlueZClient Function() _clientFactory;
  late final Stream<BluezOwnerChange> _ownerChanges;
  late final Future<String?> Function() _currentOwner;
  DBusClient? _ownerBus;
  BlueZClient? _client;
  String? _owner;
  BluezOwnerChange? _pendingOwnerChange;
  Future<void>? _ownerChangeDrain;
  late final UniversalBleFilterUtil _bleFilter = UniversalBleFilterUtil();
  BlueZAdapter? _activeAdapter;
  String? _selectedAdapterAddress;
  final Map<String, BlueZAdapter> _adapters = {};
  StreamSubscription<BluezOwnerChange>? _ownerSubscription;
  StreamSubscription<BlueZAdapter>? _adapterAdded;
  StreamSubscription<BlueZAdapter>? _adapterRemoved;
  StreamSubscription<List<String>>? _adapterProperties;
  StreamSubscription? _deviceAdded;
  StreamSubscription? _deviceRemoved;
  Future<void>? _initializationFuture;
  int _runtimeGeneration = 0;
  bool _isScanActive = false;
  final Map<String, BlueZDevice> _devices = {};
  final Map<String, StreamSubscription> _deviceUpdateStreamSubscriptions = {};
  final Map<String, StreamSubscription> _deviceAdvertisementSubscriptions = {};

  final Map<String, StreamSubscription> _characteristicPropertiesSubscriptions =
      {};

  @override
  Future<AvailabilityState> getBluetoothAvailabilityState() async {
    await _ensureInitialized();

    final adapter = _activeAdapter;
    if (adapter == null) return AvailabilityState.unknown;
    return adapter.powered
        ? AvailabilityState.poweredOn
        : AvailabilityState.poweredOff;
  }

  @override
  Future<bool> enableBluetooth() async {
    await _ensureInitialized();
    if (_activeAdapter?.powered == true) return true;
    try {
      await _activeAdapter?.setPowered(true);
      return _activeAdapter?.powered ?? false;
    } catch (e) {
      UniversalLogger.logError('Error enabling bluetooth: $e');
      return false;
    }
  }

  @override
  Future<bool> disableBluetooth() async {
    await _ensureInitialized();
    var adapter = _activeAdapter;
    if (adapter == null) {
      throw "Adapter not available";
    }
    if (!adapter.powered) return true;
    try {
      await adapter.setPowered(false);
      return !adapter.powered;
    } catch (e) {
      UniversalLogger.logError('Error disabling bluetooth: $e');
      return false;
    }
  }

  @override
  Future<void> startScan({
    ScanFilter? scanFilter,
    PlatformConfig? platformConfig,
  }) async {
    await _ensureInitialized();
    var adapter = _activeAdapter;
    if (adapter == null) {
      throw "Adapter not available";
    }

    await stopScan();

    _bleFilter.scanFilter = scanFilter;
    _isScanActive = true;
    await adapter.startDiscovery();

    for (final device in _client?.devices ?? const <BlueZDevice>[]) {
      await _onDeviceAdd(device, _runtimeGeneration);
    }
  }

  @override
  Future<void> stopScan() async {
    await _ensureInitialized();
    try {
      _isScanActive = false;
      if (_activeAdapter?.discovering == true) {
        await _activeAdapter?.stopDiscovery();
      }
      await Future.wait(
        _deviceAdvertisementSubscriptions.values.map((s) => s.cancel()),
      );
      _deviceAdvertisementSubscriptions.clear();
    } catch (e) {
      UniversalLogger.logError("stopScan error: $e");
    }
  }

  @override
  Future<bool> isScanning() async {
    await _ensureInitialized();
    return _activeAdapter?.discovering == true;
  }

  @override
  Future<BleConnectionState> getConnectionState(String deviceId) async {
    BlueZDevice? device = _getDeviceById(deviceId);
    bool connected = device?.connected ?? false;
    return connected
        ? BleConnectionState.connected
        : BleConnectionState.disconnected;
  }

  @override
  Future<void> connect(
    String deviceId, {
    Duration? connectionTimeout,
    bool autoConnect = false,
    ConnectionPlatformConfig? platformConfig,
  }) async {
    // Note: autoConnect is not directly supported on Linux platform
    // Note: platformConfig only carries Apple-specific options
    final device = _findDeviceById(deviceId);
    if (device.connected) {
      updateConnection(deviceId, true);
      return;
    }
    await device.connect();
  }

  @override
  Future<void> disconnect(String deviceId) async {
    final device = _getDeviceById(deviceId);
    if (device?.connected == true) {
      await device?.disconnect();
    }
    updateConnection(deviceId, false);
  }

  @override
  Future<void> clearGattCache(String deviceId) async {
    final device = _findDeviceById(deviceId);
    final paired = device.paired;
    try {
      if (device.connected) await device.disconnect();
    } catch (e) {
      UniversalLogger.logInfo('clearGattCache disconnect failed: $e');
    }
    await _evictDevice(device);
    if (!paired) await device.adapter.removeDevice(device);
  }

  @override
  Future<List<BleService>> discoverServices(
    String deviceId,
    bool withDescriptors,
  ) async {
    final device = _findDeviceById(deviceId);
    if (!device.servicesResolved) {
      await _waitForServices(device);
      await Future<void>.delayed(const Duration(milliseconds: 500));
      if (!device.connected ||
          !identical(_devices[device.address.toLowerCase()], device)) {
        throw UniversalBleException(
          code: UniversalBleErrorCode.deviceDisconnected,
          message: 'Device disconnected while resolving services',
        );
      }
    }

    List<BleService> services = [];
    for (final service in device.gattServices) {
      String serviceId = service.uuid.toString();

      final characteristics = service.characteristics.map((e) {
        final properties = List<CharacteristicProperty>.from(
          e.flags
              .map((e) => e.toCharacteristicProperty())
              .where((element) => element != null)
              .toList(),
        );
        return BleCharacteristic.withMetaData(
          deviceId: deviceId,
          serviceId: serviceId,
          uuid: e.uuid.toString(),
          properties: properties,
          descriptors: withDescriptors
              ? e.descriptors
                    .map((e) => BleDescriptor(e.uuid.toString()))
                    .toList()
              : [],
        );
      }).toList();
      services.add(BleService(serviceId, characteristics));
    }
    return services;
  }

  BlueZGattCharacteristic _getCharacteristic(
    String deviceId,
    String service,
    String characteristic,
  ) {
    final device = _findDeviceById(deviceId);
    final s = device.gattServices.cast<BlueZGattService?>().firstWhere(
      (s) => s?.uuid.toString() == service,
      orElse: () => null,
    );
    final c = s?.characteristics.cast<BlueZGattCharacteristic?>().firstWhere(
      (c) => c?.uuid.toString() == characteristic,
      orElse: () => null,
    );

    if (c == null) {
      throw UniversalBleException(
        code: UniversalBleErrorCode.characteristicNotFound,
        message: 'Unknown characteristic:$characteristic',
      );
    }
    return c;
  }

  @override
  Future<void> setNotifiable(
    String deviceId,
    String service,
    String characteristic,
    BleInputProperty bleInputProperty,
  ) async {
    UniversalLogger.logDebug(
      "SET_NOTIFY -> $deviceId $service $characteristic input=${bleInputProperty.name}",
      withTimestamp: true,
    );
    final char = _getCharacteristic(deviceId, service, characteristic);
    final device = _findDeviceById(deviceId);
    final generation = _runtimeGeneration;

    String characteristicKey =
        "${deviceId.toLowerCase()}_${service}_$characteristic";

    if (bleInputProperty != BleInputProperty.disabled) {
      if (char.notifying) {
        UniversalLogger.logInfo('$characteristic already notifying');
        return;
      }

      await char.startNotify();

      if (_characteristicPropertiesSubscriptions[characteristicKey] != null) {
        _characteristicPropertiesSubscriptions[characteristicKey]?.cancel();
      }

      _characteristicPropertiesSubscriptions[characteristicKey] = char
          .propertiesChanged
          .listen((List<String> properties) {
            if (generation != _runtimeGeneration ||
                !identical(_devices[deviceId.toLowerCase()], device)) {
              return;
            }
            for (String property in properties) {
              switch (property) {
                case BluezProperty.value:
                  UniversalLogger.logVerbose(
                    "NOTIFY <- $deviceId $service $characteristic len=${char.value.length} data=${char.value}",
                    withTimestamp: true,
                  );
                  updateCharacteristicValue(
                    deviceId,
                    characteristic,
                    Uint8List.fromList(char.value),
                    DateTime.now().millisecondsSinceEpoch,
                  );
                  break;
                default:
                  UniversalLogger.logInfo(
                    "UnhandledCharValuePropertyChange: $property",
                  );
              }
            }
          });
    } else {
      if (char.notifying) await char.stopNotify();
      _characteristicPropertiesSubscriptions
          .remove(characteristicKey)
          ?.cancel();
    }
  }

  @override
  Future<Uint8List> readValue(
    String deviceId,
    String service,
    String characteristic, {
    Duration? timeout,
  }) async {
    UniversalLogger.logDebug(
      "READ -> $deviceId $service $characteristic",
      withTimestamp: true,
    );
    try {
      final c = _getCharacteristic(deviceId, service, characteristic);
      final data = await c.readValue();
      return Uint8List.fromList(data);
    } on BlueZFailedException catch (e) {
      UniversalLogger.logError(
        "READ_FAILED <- $deviceId $service $characteristic ${e.message}",
        withTimestamp: true,
      );
      throw e.toUniversalBleException(
        defaultCode: UniversalBleErrorCode.readFailed,
      );
    }
  }

  @override
  Future<void> writeValue(
    String deviceId,
    String service,
    String characteristic,
    Uint8List value,
    BleOutputProperty bleOutputProperty,
  ) async {
    UniversalLogger.logDebug(
      "WRITE -> $deviceId $service $characteristic len=${value.length} property=${bleOutputProperty.name}",
      withTimestamp: true,
    );
    try {
      final c = _getCharacteristic(deviceId, service, characteristic);
      if (bleOutputProperty == BleOutputProperty.withResponse) {
        await c.writeValue(
          value,
          type: BlueZGattCharacteristicWriteType.request,
        );
      } else {
        await c.writeValue(
          value,
          type: BlueZGattCharacteristicWriteType.command,
        );
      }
    } on BlueZFailedException catch (e) {
      UniversalLogger.logError(
        "WRITE_FAILED <- $deviceId $service $characteristic ${e.message}",
        withTimestamp: true,
      );
      throw e.toUniversalBleException(
        defaultCode: UniversalBleErrorCode.writeFailed,
      );
    }
  }

  @override
  Future<int> requestMtu(String deviceId, int expectedMtu) async {
    final device = _findDeviceById(deviceId);
    if (!device.connected) {
      throw UniversalBleException(
        code: UniversalBleErrorCode.deviceDisconnected,
        message: 'Device not connected',
      );
    }
    for (BlueZGattService service in device.gattServices) {
      for (BlueZGattCharacteristic characteristic in service.characteristics) {
        int? mtu = characteristic.mtu;
        // The value provided by Bluez includes an extra 3 bytes from the GATT header, which needs to be removed.
        if (mtu != null) return mtu - 3;
      }
    }
    throw UniversalBleException(
      code: UniversalBleErrorCode.operationNotSupported,
      message: 'MTU not available',
    );
  }

  @override
  Future<void> requestConnectionPriority(
    String deviceId,
    BleConnectionPriority priority,
  ) {
    throw UniversalBleException(
      code: UniversalBleErrorCode.notSupported,
      message: "requestConnectionPriority is not supported on Linux platform",
    );
  }

  @override
  Future<int> readRssi(String deviceId) async {
    throw UniversalBleException(
      code: UniversalBleErrorCode.notImplemented,
      message: "readRssi is not implemented on Linux platform",
    );
  }

  @override
  Future<bool> pair(String deviceId) async {
    BlueZDevice device = _findDeviceById(deviceId);
    try {
      if (device.paired) return true;
      await device.pair();
      return true;
    } catch (error) {
      updatePairingState(deviceId, false);
      return false;
    }
  }

  @override
  Future<void> unpair(String deviceId) async {
    BlueZDevice device = _findDeviceById(deviceId);
    if (device.paired) {
      // await device.cancelPairing();
      await _activeAdapter?.removeDevice(device);
    }
  }

  @override
  Future<bool> isPaired(String deviceId) async {
    return _findDeviceById(deviceId).paired;
  }

  @override
  Future<List<BleDevice>> getSystemDevices(List<String>? withServices) async {
    await _ensureInitialized();
    List<BlueZDevice> devices = (_client?.devices ?? const <BlueZDevice>[])
        .where((device) => device.adapter.address == _activeAdapter?.address)
        .where((device) => device.connected)
        .toList();
    if (withServices != null && withServices.isNotEmpty) {
      devices = devices.where((device) {
        if (device.servicesResolved) {
          return device.gattServices
              .map((e) => e.uuid.toString())
              .any((service) => withServices.contains(service));
        } else {
          UniversalLogger.logInfo(
            'Skipping: ${device.address}: Services not resolved yet.',
          );
          return false;
        }
      }).toList();
    }
    return devices
        .map((device) => device.toBleDevice(isSystemDevice: true))
        .toList();
  }

  AvailabilityState get _availabilityState {
    final adapter = _activeAdapter;
    if (adapter == null) return AvailabilityState.unknown;
    return adapter.powered
        ? AvailabilityState.poweredOn
        : AvailabilityState.poweredOff;
  }

  /// Find device by id from cache or from client
  /// Throws exception if device not found
  BlueZDevice _findDeviceById(String deviceId) {
    final device = _getDeviceById(deviceId);
    if (device == null) {
      throw UniversalBleException(
        code: UniversalBleErrorCode.deviceNotFound,
        message: 'Unknown deviceId:$deviceId',
      );
    }
    return device;
  }

  /// Get device by id from cache or from client
  BlueZDevice? _getDeviceById(String deviceId) {
    return _devices[deviceId.toLowerCase()];
  }

  Future<void> _ensureInitialized() async {
    await _ensureOwnerMonitoring();
    final ownerChangeDrain = _ownerChangeDrain;
    if (ownerChangeDrain != null) await ownerChangeDrain;
    await _ensureRuntimeInitialized();
  }

  Future<void> _ensureRuntimeInitialized() async {
    if (_owner == null || isInitialized) return;
    final existing = _initializationFuture;
    if (existing != null) return existing;
    late final Future<void> initialization;
    initialization = _initializeRuntime().whenComplete(() {
      if (identical(_initializationFuture, initialization)) {
        _initializationFuture = null;
      }
    });
    _initializationFuture = initialization;
    return initialization;
  }

  Future<void> _ensureOwnerMonitoring() async {
    if (_ownerSubscription != null) return;
    var eventGeneration = 0;
    _ownerSubscription = _ownerChanges.listen((change) {
      eventGeneration++;
      unawaited(_queueOwnerChange(change));
    });
    final lookupGeneration = eventGeneration;
    final owner = await _currentOwner();
    if (lookupGeneration == eventGeneration) {
      await _queueOwnerChange(BluezOwnerChange(null, owner));
    } else {
      await _ownerChangeDrain;
    }
  }

  Future<void> _queueOwnerChange(BluezOwnerChange change) {
    _pendingOwnerChange = change;
    final existing = _ownerChangeDrain;
    if (existing != null) return existing;
    late final Future<void> drain;
    drain = Future<void>(_drainOwnerChanges).whenComplete(() {
      if (identical(_ownerChangeDrain, drain)) _ownerChangeDrain = null;
    });
    _ownerChangeDrain = drain;
    return drain;
  }

  Future<void> _drainOwnerChanges() async {
    while (_pendingOwnerChange != null) {
      final change = _pendingOwnerChange!;
      _pendingOwnerChange = null;
      final nextOwner = change.newOwner?.isEmpty == true
          ? null
          : change.newOwner;
      if (nextOwner == _owner) continue;
      _owner = nextOwner;
      await _teardownRuntime();
      while (_pendingOwnerChange != null) {
        final latest = _pendingOwnerChange!;
        _pendingOwnerChange = null;
        _owner = latest.newOwner?.isEmpty == true ? null : latest.newOwner;
      }
      if (_owner != null) await _ensureRuntimeInitialized();
    }
  }

  Future<void> _initializeRuntime() async {
    final generation = ++_runtimeGeneration;
    final client = _clientFactory();
    _client = client;
    _adapterAdded = client.adapterAdded.listen((adapter) {
      if (generation != _runtimeGeneration) return;
      _adapters[adapter.address] = adapter;
      unawaited(_selectAdapter(generation));
    });
    _adapterRemoved = client.adapterRemoved.listen((adapter) {
      if (generation != _runtimeGeneration) return;
      _adapters.remove(adapter.address);
      unawaited(_selectAdapter(generation));
    });
    _deviceAdded = client.deviceAdded.listen((device) {
      unawaited(_onDeviceAdd(device, generation));
    });
    _deviceRemoved = client.deviceRemoved.listen((device) {
      unawaited(_evictDevice(device));
    });
    try {
      await client.connect();
      if (generation != _runtimeGeneration) return;
      for (final adapter in client.adapters) {
        _adapters[adapter.address] = adapter;
      }
      await _waitForAdapter(client, generation);
      if (generation != _runtimeGeneration) return;
      await _selectAdapter(generation);
      for (final device in client.devices) {
        await _onDeviceAdd(device, generation);
      }
      if (generation != _runtimeGeneration) return;
      isInitialized = true;
      updateAvailability(_availabilityState);
    } catch (e) {
      if (generation == _runtimeGeneration) {
        UniversalLogger.logError('Error initializing: $e');
        await _teardownRuntime();
      }
      rethrow;
    }
  }

  Future<void> _waitForAdapter(BlueZClient client, int generation) async {
    final deadline = DateTime.now().add(const Duration(seconds: 10));
    while (generation == _runtimeGeneration &&
        client.adapters.isEmpty &&
        DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
  }

  Future<void> _selectAdapter(int generation) async {
    if (generation != _runtimeGeneration) return;
    final current = _activeAdapter;
    final previous = _selectedAdapterAddress;
    final adapters = _adapters.values.toList(growable: false);
    final selected =
        current?.powered == true &&
            adapters.any((adapter) => identical(adapter, current))
        ? current
        : adapters.cast<BlueZAdapter?>().firstWhere(
                (adapter) =>
                    adapter?.address == previous && adapter?.powered == true,
                orElse: () => null,
              ) ??
              adapters.cast<BlueZAdapter?>().firstWhere(
                (adapter) => adapter?.powered == true,
                orElse: () => null,
              ) ??
              adapters.cast<BlueZAdapter?>().firstWhere(
                (adapter) => adapter?.address == previous,
                orElse: () => null,
              ) ??
              adapters.cast<BlueZAdapter?>().firstWhere(
                (_) => true,
                orElse: () => null,
              );
    if (identical(selected, current)) {
      updateAvailability(_availabilityState);
      return;
    }
    await _adapterProperties?.cancel();
    if (generation != _runtimeGeneration) return;
    _adapterProperties = null;
    _activeAdapter = selected;
    if (selected != null) {
      _selectedAdapterAddress = selected.address;
      _adapterProperties = selected.propertiesChanged.listen((properties) {
        if (generation != _runtimeGeneration ||
            !identical(_activeAdapter, selected)) {
          return;
        }
        if (properties.contains(BluezProperty.powered)) {
          unawaited(_selectAdapter(generation));
        }
      });
      UniversalLogger.logInfo(
        'BleAdapter: ${selected.name} - ${selected.address}',
      );
    }
    final stale = _devices.values
        .where((device) => device.adapter.address != selected?.address)
        .toList(growable: false);
    for (final device in stale) {
      await _evictDevice(device);
      if (generation != _runtimeGeneration) return;
    }
    updateAvailability(_availabilityState);
  }

  Future<void> _onDeviceAdd(BlueZDevice device, int generation) async {
    if (generation != _runtimeGeneration ||
        device.adapter.address != _activeAdapter?.address) {
      return;
    }
    final key = device.address.toLowerCase();
    final previous = _devices[key];
    if (previous != null && !identical(previous, device)) {
      await _evictDevice(previous);
    }
    if (generation != _runtimeGeneration ||
        device.adapter.address != _activeAdapter?.address) {
      return;
    }
    _devices[key] = device;
    final bleDevice = device.toBleDevice();
    if (_isScanActive &&
        device.rssi != 0 &&
        _bleFilter.shouldAcceptDevice(bleDevice)) {
      updateScanResult(bleDevice);
    }

    if (_isScanActive) _listenForAdvertisements(device, generation);

    _deviceUpdateStreamSubscriptions[key] ??= device.propertiesChanged.listen((
      properties,
    ) {
      if (generation != _runtimeGeneration ||
          !identical(_devices[key], device)) {
        return;
      }
      for (final property in properties) {
        switch (property) {
          case BluezProperty.connected:
            updateConnection(device.address, device.connected);
            break;
          case BluezProperty.paired:
            updatePairingState(device.address, device.paired);
            break;
          case BluezProperty.bonded:
          case BluezProperty.legacyPairing:
          case BluezProperty.servicesResolved:
          case BluezProperty.uuids:
          case BluezProperty.txPower:
          case BluezProperty.address:
          case BluezProperty.addressType:
          case BluezProperty.rssi:
          case BluezProperty.manufacturerData:
          case BluezProperty.serviceData:
            break;
          default:
            UniversalLogger.logInfo(
              "UnhandledDevicePropertyChanged ${device.name} ${device.address}: $property",
            );
        }
      }
    });
  }

  void _listenForAdvertisements(BlueZDevice device, int generation) {
    final key = device.address.toLowerCase();
    _deviceAdvertisementSubscriptions[key] ??= device.propertiesChanged
        .where((e) {
          return e.contains(BluezProperty.rssi) ||
              e.contains(BluezProperty.manufacturerData) ||
              e.contains(BluezProperty.uuids) ||
              e.contains(BluezProperty.serviceData);
        })
        .listen((_) {
          if (generation == _runtimeGeneration &&
              identical(_devices[key], device) &&
              _isScanActive &&
              _bleFilter.shouldAcceptDevice(device.toBleDevice())) {
            updateScanResult(device.toBleDevice());
          }
        });
  }

  Future<void> _evictDevice(BlueZDevice device) async {
    final key = device.address.toLowerCase();
    if (!identical(_devices[key], device)) return;
    if (device.connected) updateConnection(device.address, false);
    final updateSubscription = _deviceUpdateStreamSubscriptions.remove(key);
    final advertisementSubscription = _deviceAdvertisementSubscriptions.remove(
      key,
    );
    final characteristicKeys = _characteristicPropertiesSubscriptions.keys
        .where((entry) => entry.startsWith('${device.address.toLowerCase()}_'))
        .toList(growable: false);
    final characteristicSubscriptions = characteristicKeys
        .map(_characteristicPropertiesSubscriptions.remove)
        .whereType<StreamSubscription>();
    await Future.wait([
      if (updateSubscription != null) updateSubscription.cancel(),
      if (advertisementSubscription != null) advertisementSubscription.cancel(),
      ...characteristicSubscriptions.map(
        (subscription) => subscription.cancel(),
      ),
    ]);
    if (identical(_devices[key], device)) {
      _devices.remove(key);
    }
  }

  Future<void> _teardownRuntime() async {
    _runtimeGeneration++;
    isInitialized = false;
    _initializationFuture = null;
    updateAvailability(AvailabilityState.unknown);
    final connected = _devices.values
        .where((device) => device.connected)
        .toList(growable: false);
    for (final device in connected) {
      updateConnection(device.address, false);
    }
    _isScanActive = false;
    final client = _client;
    final cancellations = <Future<void>>[
      if (_adapterAdded != null) _adapterAdded!.cancel(),
      if (_adapterRemoved != null) _adapterRemoved!.cancel(),
      if (_adapterProperties != null) _adapterProperties!.cancel(),
      if (_deviceAdded != null) _deviceAdded!.cancel(),
      if (_deviceRemoved != null) _deviceRemoved!.cancel(),
      ..._deviceUpdateStreamSubscriptions.values.map((s) => s.cancel()),
      ..._deviceAdvertisementSubscriptions.values.map((s) => s.cancel()),
      ..._characteristicPropertiesSubscriptions.values.map((s) => s.cancel()),
    ];
    _client = null;
    _adapterAdded = null;
    _adapterRemoved = null;
    _adapterProperties = null;
    _deviceAdded = null;
    _deviceRemoved = null;
    _deviceUpdateStreamSubscriptions.clear();
    _deviceAdvertisementSubscriptions.clear();
    _characteristicPropertiesSubscriptions.clear();
    _devices.clear();
    _adapters.clear();
    _activeAdapter = null;
    await Future.wait(cancellations);
    await client?.close();
  }

  Future<void> _waitForServices(BlueZDevice device) async {
    if (!device.connected) {
      throw UniversalBleException(
        code: UniversalBleErrorCode.deviceDisconnected,
        message: 'Device disconnected while resolving services',
      );
    }
    final generation = _runtimeGeneration;
    final completer = Completer<void>();
    late final StreamSubscription<List<String>> subscription;
    subscription = device.propertiesChanged.listen((properties) {
      if (generation != _runtimeGeneration ||
          !identical(_devices[device.address.toLowerCase()], device) ||
          !device.connected) {
        if (!completer.isCompleted) {
          completer.completeError(
            UniversalBleException(
              code: UniversalBleErrorCode.deviceDisconnected,
              message: 'Device disconnected while resolving services',
            ),
          );
        }
      } else if (properties.contains(BluezProperty.servicesResolved) &&
          device.servicesResolved &&
          !completer.isCompleted) {
        completer.complete();
      }
    });
    if (!device.connected ||
        generation != _runtimeGeneration ||
        !identical(_devices[device.address.toLowerCase()], device)) {
      if (!completer.isCompleted) {
        completer.completeError(
          UniversalBleException(
            code: UniversalBleErrorCode.deviceDisconnected,
            message: 'Device disconnected while resolving services',
          ),
        );
      }
    } else if (device.servicesResolved) {
      if (!completer.isCompleted) completer.complete();
    }
    try {
      await completer.future.timeout(
        const Duration(seconds: 10),
        onTimeout: () {
          if (generation != _runtimeGeneration ||
              !identical(_devices[device.address.toLowerCase()], device) ||
              !device.connected) {
            throw UniversalBleException(
              code: UniversalBleErrorCode.deviceDisconnected,
              message: 'Device disconnected while resolving services',
            );
          }
          throw UniversalBleException(
            code: UniversalBleErrorCode.servicesNotResolved,
            message: 'Timed out waiting for BlueZ services',
          );
        },
      );
    } finally {
      await subscription.cancel();
    }
  }

  Future<void> dispose() async {
    await _ownerSubscription?.cancel();
    _ownerSubscription = null;
    _pendingOwnerChange = null;
    await _ownerChangeDrain;
    await _teardownRuntime();
    await _ownerBus?.close();
    _ownerBus = null;
  }
}

class BluezProperty {
  static const String rssi = 'RSSI';
  static const String connected = 'Connected';
  static const String txPower = 'TxPower';
  static const String bonded = 'Bonded';
  static const String manufacturerData = 'ManufacturerData';
  static const String serviceData = 'ServiceData';
  static const String legacyPairing = 'LegacyPairing';
  static const String servicesResolved = 'ServicesResolved';
  static const String paired = 'Paired';
  static const String address = 'Address';
  static const String addressType = 'AddressType';
  static const String modalias = 'Modalias';
  static const String uuids = 'UUIDs';
  static const String value = 'Value';
  static const String powered = 'Powered';
  static const String discoverable = 'Discoverable';
  static const String discovering = 'Discovering';
  static const String propertyClass = 'Class';
}

extension on BlueZGattCharacteristicFlag {
  CharacteristicProperty? toCharacteristicProperty() {
    return switch (this) {
      BlueZGattCharacteristicFlag.broadcast => CharacteristicProperty.broadcast,
      BlueZGattCharacteristicFlag.read => CharacteristicProperty.read,
      BlueZGattCharacteristicFlag.writeWithoutResponse =>
        CharacteristicProperty.writeWithoutResponse,
      BlueZGattCharacteristicFlag.write => CharacteristicProperty.write,
      BlueZGattCharacteristicFlag.notify => CharacteristicProperty.notify,
      BlueZGattCharacteristicFlag.indicate => CharacteristicProperty.indicate,
      BlueZGattCharacteristicFlag.authenticatedSignedWrites =>
        CharacteristicProperty.authenticatedSignedWrites,
      BlueZGattCharacteristicFlag.extendedProperties =>
        CharacteristicProperty.extendedProperties,
      _ => null,
    };
  }
}

extension on BlueZFailedException {
  UniversalBleException toUniversalBleException({
    required UniversalBleErrorCode defaultCode,
  }) {
    // Map BlueZ error code to UniversalBleErrorCode
    UniversalBleErrorCode code = UniversalBleErrorParser.getCode(errorCode);
    if (code == UniversalBleErrorCode.unknownError) {
      code = defaultCode;
    }
    throw UniversalBleException(
      code: code,
      message: message,
      details: errorCode,
    );
  }

  /// Extract error code from message and parse into decimal
  /// example: 'Operation failed with ATT error: 0x90' => 144
  String? get errorCode {
    try {
      RegExp regExp = RegExp(r'0x\w+');
      Match? match = regExp.firstMatch(message);
      String? code = match?.group(0);
      if (code == null) return null;
      int? decimalValue = int.tryParse(code.replaceFirst('0x', ''), radix: 16);
      return decimalValue?.toString() ?? code;
    } catch (e) {
      return null;
    }
  }
}

extension BlueZDeviceExtension on BlueZDevice {
  List<ManufacturerData> get manufacturerDataList => manufacturerData.entries
      .map(
        (MapEntry<BlueZManufacturerId, List<int>> data) =>
            ManufacturerData(data.key.id, Uint8List.fromList(data.value)),
      )
      .toList();

  Map<String, Uint8List> get serviceDataMap {
    try {
      return {
        for (final entry in serviceData.entries)
          entry.key.toString(): Uint8List.fromList(entry.value),
      };
    } catch (e) {
      return <String, Uint8List>{};
    }
  }

  BleDevice toBleDevice({bool? isSystemDevice}) {
    return BleDevice(
      name: name,
      deviceId: address,
      paired: paired,
      rssi: rssi,
      isSystemDevice: isSystemDevice,
      services: uuids.map((e) => e.toString()).toList(),
      manufacturerDataList: manufacturerDataList,
      serviceData: serviceDataMap,
      timestamp: DateTime.now().millisecondsSinceEpoch,
    );
  }
}

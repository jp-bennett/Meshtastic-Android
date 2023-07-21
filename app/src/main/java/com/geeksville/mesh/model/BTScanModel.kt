package com.geeksville.mesh.model

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.*
import android.hardware.usb.UsbManager
import android.net.nsd.NsdServiceInfo
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geeksville.mesh.android.Logging
import com.geeksville.mesh.R
import com.geeksville.mesh.android.*
import com.geeksville.mesh.repository.bluetooth.BluetoothRepository
import com.geeksville.mesh.repository.nsd.NsdRepository
import com.geeksville.mesh.repository.radio.MockInterface
import com.geeksville.mesh.repository.radio.RadioInterfaceService
import com.geeksville.mesh.repository.radio.SerialInterface
import com.geeksville.mesh.repository.usb.UsbRepository
import com.geeksville.mesh.util.anonymize
import com.hoho.android.usbserial.driver.UsbSerialDriver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.regex.Pattern
import javax.inject.Inject

@HiltViewModel
class BTScanModel @Inject constructor(
    private val application: Application,
    private val bluetoothRepository: BluetoothRepository,
    private val usbRepository: UsbRepository,
    private val nsdRepository: NsdRepository,
    private val radioInterfaceService: RadioInterfaceService,
) : ViewModel(), Logging {

    private val context: Context get() = application.applicationContext
    val devices = MutableLiveData<MutableMap<String, DeviceListEntry>>(mutableMapOf())
    private val bleDevices = MutableLiveData<List<BluetoothDevice>>(listOf())
    private val usbDevices = MutableLiveData<Map<String, UsbSerialDriver>>(mapOf())

    init {
        combine(
            bluetoothRepository.state,
            usbRepository.serialDevicesWithDrivers
        ) { ble, usb ->
            bleDevices.value = ble.bondedDevices
            usbDevices.value = usb
        }.onEach { setupScan() }.launchIn(viewModelScope)

        debug("BTScanModel created")
    }

    /**
     * @param fullAddress Interface [prefix] + [address] (example: "x7C:9E:BD:F0:BE:BE")
     */
    open class DeviceListEntry(val name: String, val fullAddress: String, val bonded: Boolean) {
        val prefix get() = fullAddress[0]
        val address get() = fullAddress.substring(1)

        override fun toString(): String {
            return "DeviceListEntry(name=${name.anonymize}, addr=${address.anonymize}, bonded=$bonded)"
        }

        val isBLE: Boolean get() = prefix == 'x'
        val isUSB: Boolean get() = prefix == 's'
        val isTCP: Boolean get() = prefix == 't'
    }

    @SuppressLint("MissingPermission")
    class BLEDeviceListEntry(device: BluetoothDevice) : DeviceListEntry(
        device.name ?: "unnamed-${device.address}", // some devices might not have a name
        "x${device.address}",
        device.bondState == BluetoothDevice.BOND_BONDED
    )

    class USBDeviceListEntry(usbManager: UsbManager, val usb: UsbSerialDriver) : DeviceListEntry(
        usb.device.deviceName,
        SerialInterface.toInterfaceName(usb.device.deviceName),
        SerialInterface.assumePermission || usbManager.hasPermission(usb.device)
    )

    class TCPDeviceListEntry(val service: NsdServiceInfo) : DeviceListEntry(
        service.host.toString().substring(1),
        service.host.toString().replace("/", "t"),
        true
    )

    override fun onCleared() {
        super.onCleared()
        debug("BTScanModel cleared")
    }

    var selectedAddress: String? = null
    val errorText = MutableLiveData<String?>(null)

    fun setErrorText(text: String) {
        errorText.value = text
    }

    private var scanner: BluetoothLeScanner? = null

    val selectedBluetooth: Boolean get() = selectedAddress?.getOrNull(0) == 'x'

    /// Use the string for the NopInterface
    val selectedNotNull: String get() = selectedAddress ?: "n"

    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            val msg = "Unexpected bluetooth scan failure: $errorCode"
            errormsg(msg)
            // error code2 seems to be indicate hung bluetooth stack
            errorText.value = msg
        }

        // For each device that appears in our scan, ask for its GATT, when the gatt arrives,
        // check if it is an eligible device and store it in our list of candidates
        // if that device later disconnects remove it as a candidate
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {

            if (result.device.name.let { it != null && it.matches(Regex(BLE_NAME_PATTERN)) }) {
                val addr = result.device.address
                val fullAddr = "x$addr" // full address with the bluetooth prefix added
                // prevent log spam because we'll get lots of redundant scan results
                val isBonded = result.device.bondState == BluetoothDevice.BOND_BONDED
                val oldDevs = devices.value!!
                val oldEntry = oldDevs[fullAddr]
                if (oldEntry == null || oldEntry.bonded != isBonded) { // Don't spam the GUI with endless updates for non changing nodes
                    val entry = DeviceListEntry(
                        result.device.name,
                        fullAddr,
                        isBonded
                    )
                    addDevice(entry) // Add/replace entry
                }
            }
        }
    }

    private fun addDevice(entry: DeviceListEntry) {
        val oldDevs = devices.value!!
        oldDevs[entry.fullAddress] = entry // Add/replace entry
        devices.value = oldDevs // trigger gui updates
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        // Stop Network Service Discovery (for TCP)
        networkDiscovery?.cancel()

        if (scanner != null) {
            debug("stopping scan")
            try {
                scanner?.stopScan(scanCallback)
            } catch (ex: Throwable) {
                warn("Ignoring error stopping scan, probably BT adapter was disabled suddenly: ${ex.message}")
            } finally {
                scanner = null
                _spinner.value = false
            }
        } else _spinner.value = false
    }

    /**
     * returns true if we could start scanning, false otherwise
     */
    private fun setupScan(): Boolean {
        selectedAddress = radioInterfaceService.getDeviceAddress()

        return if (MockInterface.addressValid(context, usbRepository, "")) {
            warn("Running under emulator/test lab")

            val testnodes = listOf(
                DeviceListEntry("Included simulator", "m", true),
                DeviceListEntry("Complete simulator", "t10.0.2.2", true),
                DeviceListEntry(context.getString(R.string.none), "n", true)
                /* Don't populate fake bluetooth devices, because we don't want testlab inside of google
                to try and use them.

                DeviceListEntry("Meshtastic_ab12", "xaa", false),
                DeviceListEntry("Meshtastic_32ac", "xbb", true) */
            )

            devices.value = (testnodes.map { it.fullAddress to it }).toMap().toMutableMap()
            true
        } else {
            if (scanner == null) {
                val newDevs = mutableMapOf<String, DeviceListEntry>()

                fun addDevice(entry: DeviceListEntry) {
                    newDevs[entry.fullAddress] = entry
                }

                // Include a placeholder for "None"
                addDevice(DeviceListEntry(context.getString(R.string.none), "n", true))

                // Include paired Bluetooth devices
                bleDevices.value?.forEach {
                    addDevice(BLEDeviceListEntry(it))
                }

                // Include Network Service Discovery
                nsdRepository.resolvedList.value.forEach { service ->
                    addDevice(TCPDeviceListEntry(service))
                }

                usbDevices.value?.forEach { (_, d) ->
                    addDevice(USBDeviceListEntry(context.usbManager, d))
                }

                devices.value = newDevs
            } else {
                debug("scan already running")
            }
            true
        }
    }

    private var networkDiscovery: Job? = null
    fun startScan(context: Context?) {
        _spinner.value = true

        // Start Network Service Discovery (find TCP devices)
        networkDiscovery = nsdRepository.networkDiscoveryFlow()
            .onEach { addDevice(TCPDeviceListEntry(it)) }
            .launchIn(viewModelScope)

        if (context != null) startCompanionScan(context) else startClassicScan()
    }

    @SuppressLint("MissingPermission")
    private fun startClassicScan() {
        /// The following call might return null if the user doesn't have bluetooth access permissions
        val bluetoothLeScanner = bluetoothRepository.getBluetoothLeScanner()

        if (bluetoothLeScanner != null) { // could be null if bluetooth is disabled
            debug("starting classic scan")

            // filter and only accept devices that have our service
            val filter =
                ScanFilter.Builder()
                    // Samsung doesn't seem to filter properly by service so this can't work
                    // see https://stackoverflow.com/questions/57981986/altbeacon-android-beacon-library-not-working-after-device-has-screen-off-for-a-s/57995960#57995960
                    // and https://stackoverflow.com/a/45590493
                    // .setServiceUuid(ParcelUuid(BluetoothInterface.BTM_SERVICE_UUID))
                    .build()

            val settings =
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
            bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
            scanner = bluetoothLeScanner
        }
    }

    fun getRemoteDevice(address: String) = bluetoothRepository.getRemoteDevice(address)

    /**
     * @return DeviceListEntry from full Address (prefix + address).
     * If Bluetooth is enabled and BLE Address is valid, get remote device information.
     */
    @SuppressLint("MissingPermission")
    fun getDeviceListEntry(fullAddress: String, bonded: Boolean = false): DeviceListEntry {
        val address = fullAddress.substring(1)
        val device = getRemoteDevice(address)
        return if (device != null && device.name != null) {
            BLEDeviceListEntry(device)
        } else {
            DeviceListEntry(address, fullAddress, bonded)
        }
    }

    private val _spinner = MutableLiveData(false)
    val spinner: LiveData<Boolean> get() = _spinner

    private val _associationRequest = MutableLiveData<IntentSenderRequest?>(null)
    val associationRequest: LiveData<IntentSenderRequest?> get() = _associationRequest

    /**
     * Called immediately after fragment observes CompanionDeviceManager activity result
     */
    fun clearAssociationRequest() {
        _associationRequest.value = null
    }

    @SuppressLint("NewApi")
    private fun associationRequest(): AssociationRequest {
        // To skip filtering based on name and supported feature flags (UUIDs),
        // don't include calls to setNamePattern() and addServiceUuid(),
        // respectively. This example uses Bluetooth.
        // We only look for Mesh (rather than the full name) because NRF52 uses a very short name
        val deviceFilter: BluetoothDeviceFilter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(BLE_NAME_PATTERN))
            // .addServiceUuid(ParcelUuid(BluetoothInterface.BTM_SERVICE_UUID), null)
            .build()

        // The argument provided in setSingleDevice() determines whether a single
        // device name or a list of device names is presented to the user as
        // pairing options.
        return AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()
    }

    @SuppressLint("NewApi")
    private fun startCompanionScan(context: Context) {
        debug("starting companion scan")
        context.companionDeviceManager?.associate(
            associationRequest(),
            @SuppressLint("NewApi")
            object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java", ReplaceWith("onAssociationPending(intentSender)"))
                override fun onDeviceFound(intentSender: IntentSender) {
                    onAssociationPending(intentSender)
                }

                override fun onAssociationPending(chooserLauncher: IntentSender) {
                    debug("CompanionDeviceManager - device found")
                    _spinner.value = false
                    chooserLauncher.let {
                        val request: IntentSenderRequest = IntentSenderRequest.Builder(it).build()
                        _associationRequest.value = request
                    }
                }

                override fun onFailure(error: CharSequence?) {
                    warn("BLE selection service failed $error")
                }
            }, null
        )
    }

    /**
     * Called immediately after activity calls MeshService.changeDeviceAddress
     */
    fun changeSelectedAddress(newAddress: String) {
        selectedAddress = newAddress
        devices.value = devices.value // Force a GUI update
    }

    companion object {
        const val BLE_NAME_PATTERN = BluetoothRepository.BLE_NAME_PATTERN
        const val ACTION_USB_PERMISSION = "com.geeksville.mesh.USB_PERMISSION"
    }
}

package org.openhr.mihrbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.concurrent.ConcurrentHashMap

/** BLE Peripheral role. The service is registered before advertising is started. */
@SuppressLint("MissingPermission") // Runtime permissions are checked by MainActivity before starting.
class HeartRatePeripheral(
    private val context: Context,
    private val report: (String) -> Unit
) {
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisementCallback: AdvertiseCallback? = null
    private lateinit var hrCharacteristic: BluetoothGattCharacteristic
    private val peers = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscriptions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var active = false
    @Volatile var advertising = false
        private set

    val connectedCount: Int get() = peers.size
    val subscribers: Int get() = subscriptions.size

    private val serverCallbacks = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (!active) return
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising(includeName = true)
            else report("心率服务注册失败: GATT=$status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                peers[device.address] = device
                report("客户端已连接（${peers.size}）")
            } else {
                peers.remove(device.address)
                subscriptions.remove(device.address)
                report("客户端断开（剩余 ${peers.size}）")
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid != HrProtocol.CCCD) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                return
            }
            val value = if (subscriptions.contains(device.address))
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            respondRead(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            if (descriptor.uuid != HrProtocol.CCCD || preparedWrite) {
                status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            } else if (offset != 0 || value.size != 2) {
                status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
            } else {
                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                        subscriptions.add(device.address)
                        report("客户端已订阅心率通知（${subscribers}）")
                    }
                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                        subscriptions.remove(device.address)
                        report("客户端已取消心率订阅")
                    }
                    else -> status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == HrProtocol.BODY_SENSOR_LOCATION) {
                respondRead(device, requestId, offset, byteArrayOf(0x02)) // wrist
            } else {
                // The measurement characteristic intentionally has NOTIFY but not READ.
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }
    }

    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, data: ByteArray) {
        if (offset < 0 || offset > data.size) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        } else {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                data.copyOfRange(offset, data.size))
        }
    }

    fun start(): Boolean {
        if (active) return true
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            report("蓝牙未开启或设备不支持")
            return false
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            report("蓝牙硬件未声明支持 BLE 广播")
            return false
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            report("无法获取 BLE 广播器")
            return false
        }
        server = manager.openGattServer(context, serverCallbacks)
        if (server == null) {
            report("打开 GATT Server 失败")
            return false
        }
        active = true
        hrCharacteristic = BluetoothGattCharacteristic(
            HrProtocol.MEASUREMENT, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0
        )
        val cccd = BluetoothGattDescriptor(
            HrProtocol.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        hrCharacteristic.addDescriptor(cccd)
        val location = BluetoothGattCharacteristic(
            HrProtocol.BODY_SENSOR_LOCATION, BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        location.value = byteArrayOf(0x02) // Wrist
        val service = BluetoothGattService(HrProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(hrCharacteristic)
        service.addCharacteristic(location)
        report("注册标准心率服务 0x180D…")
        if (server?.addService(service) != true) {
            report("addService 返回失败")
            stop()
            return false
        }
        return true
    }

    private fun startAdvertising(includeName: Boolean) {
        if (!active) return
        val settings = AdvertiseSettings.Builder()
            .setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(HrProtocol.SERVICE))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(includeName)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (active) {
                    advertising = true
                    report("BLE 广播已启动，心率服务 0x180D")
                }
            }
            override fun onStartFailure(errorCode: Int) {
                advertising = false
                if (active && includeName && errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    report("广播名称过长，尝试不带设备名广播")
                    startAdvertising(includeName = false)
                } else if (active) report("BLE 广播失败：错误码 $errorCode")
            }
        }
        advertisementCallback = callback
        try {
            advertiser?.startAdvertising(settings, data, scanResponse, callback)
        } catch (e: Exception) {
            report("启动广播失败：${e.message}")
        }
    }

    /** Called no faster than once per second, only for subscribed clients. */
    fun notifyHeartRate(bpm: Int) {
        if (!active || !advertising || subscriptions.isEmpty()) return
        val data = HrProtocol.encodeBpm(bpm)
        val gatt = server ?: return
        for (address in subscriptions.toList()) {
            val device = peers[address] ?: continue
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    val code = gatt.notifyCharacteristicChanged(device, hrCharacteristic, false, data)
                    if (code != BluetoothStatusCodes.SUCCESS) report("发送心率通知失败: $code")
                } else {
                    @Suppress("DEPRECATION")
                    hrCharacteristic.value = data
                    @Suppress("DEPRECATION")
                    if (!gatt.notifyCharacteristicChanged(device, hrCharacteristic, false))
                        report("发送心率通知失败")
                }
            } catch (e: Exception) {
                report("心率通知异常：${e.message}")
            }
        }
    }

    fun stop() {
        active = false
        advertising = false
        advertisementCallback?.let { callback ->
            try { advertiser?.stopAdvertising(callback) } catch (_: Exception) { }
        }
        advertisementCallback = null
        try { server?.clearServices() } catch (_: Exception) { }
        try { server?.close() } catch (_: Exception) { }
        server = null
        peers.clear()
        subscriptions.clear()
        report("心率广播已停止")
    }
}
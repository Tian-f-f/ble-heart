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
import java.util.concurrent.atomic.AtomicInteger

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

    data class Diagnostics(
        val cccdReads: Int,
        val cccdWrites: Int,
        val notificationsQueued: Int,
        val notificationsCompleted: Int,
        val notificationsFailed: Int,
        val lastMtu: Int,
        val lastClient: String
    )

    private val cccdReads = AtomicInteger()
    private val cccdWrites = AtomicInteger()
    private val notificationsQueued = AtomicInteger()
    private val notificationsCompleted = AtomicInteger()
    private val notificationsFailed = AtomicInteger()
    @Volatile private var lastMtu = 23
    @Volatile private var lastClient = "--"

    fun diagnostics() = Diagnostics(
        cccdReads.get(), cccdWrites.get(), notificationsQueued.get(),
        notificationsCompleted.get(), notificationsFailed.get(), lastMtu, lastClient
    )

    private fun clientId(device: BluetoothDevice): String =
        "**:**:**:**:" + device.address.takeLast(5)

    private fun bytesHex(value: ByteArray): String =
        value.take(20).joinToString(" ") { (it.toInt() and 255).toString(16).uppercase().padStart(2, '0') } +
            if (value.size > 20) " ..." else ""

    private val serverCallbacks = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (!active) return
            report("HRS 0x180D 注册回调 status=" + status)
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising(includeName = true)
            else report("心率服务注册失败: GATT=$status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            lastClient = clientId(device)
            report("连接状态 client=" + lastClient + " status=" + status + " state=" + newState)
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
            cccdReads.incrementAndGet()
            report("描述符 READ client=" + clientId(device) + " UUID=" + descriptor.uuid +
                " offset=" + offset)
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
            cccdWrites.incrementAndGet()
            report("描述符 WRITE client=" + clientId(device) + " UUID=" + descriptor.uuid +
                " hex=[" + bytesHex(value) + "] offset=" + offset +
                " prepared=" + preparedWrite + " responseNeeded=" + responseNeeded)
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
            if (responseNeeded) {
                val accepted = server?.sendResponse(device, requestId, status, offset, null) == true
                report("CCCD 写响应 status=" + status + " queued=" + accepted)
            } else {
                report("CCCD 无响应写入 status=" + status)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            report("特征 READ client=" + clientId(device) + " UUID=" + characteristic.uuid +
                " offset=" + offset)
            if (characteristic.uuid == HrProtocol.BODY_SENSOR_LOCATION) {
                respondRead(device, requestId, offset, byteArrayOf(0x02)) // wrist
            } else {
                // The measurement characteristic intentionally has NOTIFY but not READ.
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            report("特征 WRITE client=" + clientId(device) + " UUID=" + characteristic.uuid +
                " hex=[" + bytesHex(value) + "] responseNeeded=" + responseNeeded)
            if (responseNeeded) {
                val accepted = server?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null
                ) == true
                report("只读/通知特征拒绝写入，响应已提交=" + accepted)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            report("EXECUTE_WRITE client=" + clientId(device) + " execute=" + execute)
            val status = if (execute) BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                         else BluetoothGatt.GATT_SUCCESS
            server?.sendResponse(device, requestId, status, 0, null)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            lastMtu = mtu
            report("MTU changed client=" + clientId(device) + " mtu=" + mtu)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val count = notificationsCompleted.incrementAndGet()
                if (count == 1 || count % 10 == 0) {
                    report("心率通知已完成 client=" + clientId(device) + " total=" + count)
                }
            } else {
                notificationsFailed.incrementAndGet()
                report("心率通知回调失败 client=" + clientId(device) + " status=" + status)
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
        cccdReads.set(0)
        cccdWrites.set(0)
        notificationsQueued.set(0)
        notificationsCompleted.set(0)
        notificationsFailed.set(0)
        lastMtu = 23
        lastClient = "--"
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
                    if (code == BluetoothStatusCodes.SUCCESS) {
                        val count = notificationsQueued.incrementAndGet()
                        if (count == 1 || count % 10 == 0) {
                            report("心率通知入队 BPM=" + bpm + " total=" + count)
                        }
                    } else {
                        notificationsFailed.incrementAndGet()
                        report("发送心率通知失败: " + code)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    hrCharacteristic.value = data
                    @Suppress("DEPRECATION")
                    if (gatt.notifyCharacteristicChanged(device, hrCharacteristic, false)) {
                        val count = notificationsQueued.incrementAndGet()
                        if (count == 1 || count % 10 == 0) {
                            report("心率通知入队 BPM=" + bpm + " total=" + count)
                        }
                    } else {
                        notificationsFailed.incrementAndGet()
                        report("发送心率通知失败")
                    }
                }
            } catch (e: Exception) {
                notificationsFailed.incrementAndGet()
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
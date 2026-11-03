package org.openhr.mihrbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock

/** Central role for *standard* BLE Heart Rate (0x180D) input.
 *  Xiaomi Band 8 Pro proprietary authentication/heart-rate transport is NOT implemented.
 */
@SuppressLint("MissingPermission")
class StandardHrClient(
    private val context: Context,
    private val report: (String) -> Unit,
    private val onBpm: (Int) -> Unit
) {
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile var inputStatus: String = "尚未连接心率输入设备"
        private set
    @Volatile var lastBpm: Int? = null
        private set
    @Volatile var lastSampleMs: Long = 0L
        private set

    private fun state(s: String) {
        inputStatus = s
        report(s)
    }

    /** Release unsupported devices promptly so Xiaomi Health can reconnect. */
    private fun reject(conn: BluetoothGatt, reason: String) {
        if (gatt !== conn) return
        gatt = null
        lastBpm = null
        lastSampleMs = 0L
        state(reason)
        try { conn.disconnect() } catch (_: Exception) { }
        try { conn.close() } catch (_: Exception) { }
    }

    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(conn: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== conn) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                state("输入端已连接，发现 GATT 服务…")
                if (!conn.discoverServices()) reject(conn, "无法查询输入端服务，连接已释放")
            } else {
                state("输入设备已断开（GATT=$status）")
                gatt = null
                lastBpm = null
                lastSampleMs = 0L
                conn.close()
            }
        }

        override fun onServicesDiscovered(conn: BluetoothGatt, status: Int) {
            if (gatt !== conn) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                reject(conn, "发现输入端服务失败：$status；连接已释放")
                return
            }
            val service = conn.getService(HrProtocol.SERVICE)
            val characteristic = service?.getCharacteristic(HrProtocol.MEASUREMENT)
            if (characteristic == null) {
                reject(conn, "没有 0x180D/0x2A37；私有协议未实现，已断开输入设备")
                return
            }
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                reject(conn, "对端心率不支持 Notification，连接已释放")
                return
            }
            val cccd = characteristic.getDescriptor(HrProtocol.CCCD)
            if (cccd == null || !conn.setCharacteristicNotification(characteristic, true)) {
                reject(conn, "配置心率通知失败，连接已释放")
                return
            }
            val enabled = if (Build.VERSION.SDK_INT >= 33) {
                conn.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                conn.writeDescriptor(cccd)
            }
            if (!enabled) reject(conn, "写入对端 CCCD 失败，连接已释放")
        }

        override fun onDescriptorWrite(conn: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== conn || descriptor.uuid != HrProtocol.CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) state("已订阅真实 BLE 心率通知")
            else reject(conn, "订阅对端心率通知失败：$status；连接已释放")
        }

        override fun onCharacteristicChanged(conn: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (gatt === conn && characteristic.uuid == HrProtocol.MEASUREMENT) consume(value)
        }

        @Deprecated("Legacy callback for Android < 13")
        override fun onCharacteristicChanged(conn: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== conn || characteristic.uuid != HrProtocol.MEASUREMENT) return
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            consume(data)
        }
    }

    private fun consume(data: ByteArray) {
        val bpm = HrProtocol.decodeBpm(data) ?: return
        lastBpm = bpm
        lastSampleMs = SystemClock.elapsedRealtime()
        onBpm(bpm)
    }

    fun connect(address: String) {
        disconnect()
        val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            state("无效的蓝牙设备地址")
            return
        }
        try {
            state("尝试连接 $address …")
            gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callbacks, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) state("connectGatt 未返回连接对象")
        } catch (e: Exception) {
            state("连接失败：${e.message}")
        }
    }

    fun disconnect() {
        val previous = gatt
        gatt = null
        lastSampleMs = 0L
        lastBpm = null
        try { previous?.disconnect() } catch (_: Exception) { }
        try { previous?.close() } catch (_: Exception) { }
        inputStatus = "未连接心率输入设备"
    }
}
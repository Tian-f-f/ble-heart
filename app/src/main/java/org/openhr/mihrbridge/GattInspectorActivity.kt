package org.openhr.mihrbridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

/**
 * Read-only GATT inventory for investigating Xiaomi Band 8 Pro.
 * No pairing/bonding, characteristic reads, descriptor writes, private commands, or notification
 * subscriptions are performed. The connection is short-lived and explicitly released.
 */
@SuppressLint("MissingPermission")
class GattInspectorActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var address: String = ""
    private lateinit var status: TextView
    private lateinit var reportView: TextView
    private var reportText = "还没有可导出的诊断结果。"
    private var finished = false
    private val timeout = Runnable {
        if (!finished) {
            state("连接或服务发现超时（15 秒）。这可能是设备未广播、被小米运动健康占用，或不接受当前连接。")
            release()
        }
    }

    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(conn: BluetoothGatt, code: Int, newState: Int) {
            if (conn !== gatt) return
            if (code == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                state("BLE 已连接；正在枚举 GATT 服务……")
                try {
                    if (!conn.discoverServices()) {
                        state("无法启动服务发现，已断开")
                        release()
                    }
                } catch (e: Exception) {
                    state("服务发现出错：${e.javaClass.simpleName}")
                    release()
                }
            } else {
                state("连接已断开，GATT=$code；STATE=$newState")
                release()
            }
        }

        override fun onServicesDiscovered(conn: BluetoothGatt, code: Int) {
            if (conn !== gatt) return
            if (code != BluetoothGatt.GATT_SUCCESS) {
                state("服务发现失败，GATT=$code")
                release()
                return
            }
            val report = makeReport(conn.services)
            handler.post {
                if (isFinishing || isDestroyed) return@post
                finished = true
                handler.removeCallbacks(timeout)
                reportText = report
                reportView.text = report
                status.text = "服务枚举成功。仅获取 UUID 和属性；不代表已完成小米认证。"
                release()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra("address").orEmpty()
        val bg = Color.rgb(11, 19, 33)
        val muted = Color.rgb(166, 189, 208)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(20, 28, 20, 20)
        }
        root.addView(TextView(this).apply {
            text = "手环 GATT 诊断（只读）"
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "不解绑、不配对、不写入手环私有特征。若小米运动健康正在占用连接，诊断可能超时。"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, 12, 0, 12)
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(71, 216, 207))
            text = "等待连接……"
        }
        root.addView(status)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "复制诊断报告"
            setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("GATT Report", reportText))
                state("报告已复制（设备地址已脱敏）")
            }
        }, LinearLayout.LayoutParams(0, 56.dp(), 1f))
        row.addView(Button(this).apply {
            text = "断开并返回"
            setOnClickListener { release(); finish() }
        }, LinearLayout.LayoutParams(0, 56.dp(), 1f))
        root.addView(row)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        reportView = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 16)
            text = "连接成功后显示全部 GATT 服务、特征、属性与描述符 UUID。"
        }
        scroll.addView(reportView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        AlertDialog.Builder(this)
            .setTitle("开始只读探测？")
            .setMessage("可能短暂占用手环蓝牙连接，影响小米运动健康同步。不会发送配对、解绑、重置或私有写入命令。超时后自动断开。")
            .setNegativeButton("取消") { _, _ -> finish() }
            .setPositiveButton("连接并枚举") { _, _ -> connect() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private fun state(message: String) {
        handler.post { if (!isFinishing && !isDestroyed) status.text = message }
    }

    private fun connect() {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            state("蓝牙地址无效。请重新选择设备。")
            return
        }
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                ?: run { state("手机不支持蓝牙"); return }
            if (!adapter.isEnabled) { state("请先打开蓝牙"); return }
            finished = false
            status.text = "尝试连接 ${maskAddress(address)} ……"
            val conn = adapter.getRemoteDevice(address)
                .connectGatt(this, false, callbacks, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            gatt = conn
            if (conn == null) state("连接请求失败")
            else handler.postDelayed(timeout, 15_000)
        } catch (e: Exception) {
            state("连接异常：${e.javaClass.simpleName}: ${e.message}")
            release()
        }
    }

    private fun makeReport(services: List<BluetoothGattService>): String {
        val standardHr = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val hasHr = services.any { it.uuid == standardHr }
        return buildString {
            appendLine("Mi HR Bridge 0.2.0 · GATT 服务发现报告")
            appendLine("设备地址（脱敏）: ${maskAddress(address)}")
            appendLine("服务数量: ${services.size}")
            appendLine("是否存在标准 0x180D: ${if (hasHr) "是" else "否"}")
            appendLine("采集方式: BLE GATT 服务发现（只读）")
            appendLine("无密码、认证密钥、心率数据或私有报文")
            appendLine("---")
            services.forEachIndexed { i, service ->
                appendLine("S${i + 1} ${shortUuid(service.uuid)}")
                service.characteristics.forEach { characteristic ->
                    appendLine("  C ${shortUuid(characteristic.uuid)}  [${properties(characteristic.properties)}]")
                    characteristic.descriptors.forEach { descriptor ->
                        appendLine("    D ${shortUuid(descriptor.uuid)}")
                    }
                }
            }
            appendLine("---")
            appendLine(if (!hasHr)
                "结果说明: 未发现标准心率服务，需要另行研究小米认证和私有协议；本诊断不能读取真实 BPM。"
            else "结果说明: 存在标准心率服务，可返回首页尝试标准 BLE 输入订阅。")
        }
    }

    private fun shortUuid(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) "0x${s.substring(4, 8).uppercase()} ($s)"
        else s
    }

    private fun properties(p: Int) = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
        if (p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("SIGNED_WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) add("EXTENDED_PROPS")
    }.joinToString("|").ifBlank { "none" }

    private fun maskAddress(a: String): String =
        if (a.length == 17) a.take(8) + ":XX:XX:XX" else "[hidden]"

    private fun release() {
        handler.removeCallbacks(timeout)
        val previous = gatt
        gatt = null
        finished = true
        try { previous?.disconnect() } catch (_: Exception) { }
        try { previous?.close() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
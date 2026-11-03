package org.openhr.mihrbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** BLE discovery only; selecting a device returns its MAC to MainActivity.
 *  Scan does not pair, authenticate, or write to proprietary Xiaomi characteristics.
 */
@SuppressLint("MissingPermission")
class BleScanActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private data class Nearby(val address: String, val name: String, val rssi: Int?)
    private val found = LinkedHashMap<String, Nearby>()
    private val labels = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var info: TextView
    private val endScan = Runnable { stopScan(); info.text = "扫描结束。选择设备尝试标准心率连接。" }
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val old = found[address]
            val name = result.scanRecord?.deviceName ?: try { result.device.name } catch (_: Exception) { null }
            if (old == null || (old.rssi ?: -127) < result.rssi) {
                found[address] = Nearby(address, name?.takeUnless { it.isBlank() } ?: "未命名 BLE 设备", result.rssi)
            }
            if (found.size > 150) return
            runOnUiThread { updateList() }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { info.text = "BLE 扫描失败，错误码 $errorCode" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 28, 18, 18)
            setBackgroundColor(Color.rgb(11, 19, 33))
        }
        val header = TextView(this).apply {
            text = "扫描附近 BLE 设备"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        root.addView(header)
        info = TextView(this).apply {
            text = "注意：小米手环绑定后可能不广播，且未必提供标准心率服务。"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 18, 0, 18)
        }
        root.addView(info)
        val scanButton = Button(this).apply {
            text = "重新扫描（12 秒）"
            setOnClickListener { startScan() }
        }
        root.addView(scanButton)
        root.addView(Button(this).apply {
            text = "查看 Android 已绑定的蓝牙设备"
            setOnClickListener { showBondedDevices() }
        })
        root.addView(Button(this).apply {
            text = "手动输入已知蓝牙地址（无需设备广播）"
            setOnClickListener { enterAddress() }
        })
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val list = ListView(this).apply {
            adapter = this@BleScanActivity.adapter
            setBackgroundColor(Color.WHITE)
            setOnItemClickListener { _, _, index, _ ->
                val device = found.values.sortedByDescending { it.rssi ?: -127 }.getOrNull(index) ?: return@setOnItemClickListener
                select(device.address)
                finish()
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        startScan()
    }

    private fun updateList() {
        labels.clear()
        found.values.sortedByDescending { it.rssi ?: -127 }.forEach { result ->
            labels.add("${result.name}\n${result.address}     ${result.rssi?.let { "RSSI $it dBm" } ?: "系统已绑定"}")
        }
        adapter.notifyDataSetChanged()
        info.text = "已发现 ${found.size} 台 BLE 设备（扫描结束后也可点击）"
    }

    private fun startScan() {
        stopScan()
        found.clear()
        labels.clear()
        adapter.notifyDataSetChanged()
        scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            info.text = "BLE 扫描器不可用；检查蓝牙和附近设备权限"
            return
        }
        info.text = "正在扫描附近设备…"
        try {
            scanner?.startScan(callback)
            handler.postDelayed(endScan, 12_000)
        } catch (e: Exception) {
            info.text = "扫描异常：${e.message}"
        }
    }

    private fun showBondedDevices() {
        stopScan()
        found.clear()
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            adapter?.bondedDevices?.forEach { device ->
                val name = try { device.name } catch (_: Exception) { null }
                found[device.address] = Nearby(device.address, name ?: "已绑定设备", null)
            }
            updateList()
            info.text = "已绑定 ${found.size} 台设备；若手环已从系统取消配对，可能不会列出。点击设备后尝试 GATT 连接。"
        } catch (e: Exception) {
            info.text = "无法读取已绑定列表：${e.javaClass.simpleName}"
        }
    }

    private fun enterAddress() {
        stopScan()
        val input = EditText(this).apply {
            hint = "AA:BB:CC:DD:EE:FF"
            isSingleLine = true
            setPadding(24, 18, 24, 18)
        }
        AlertDialog.Builder(this)
            .setTitle("输入手环蓝牙地址")
            .setMessage("只使用你在手环设置中确认的蓝牙地址。连接不保证成功，尤其是手环没有广播或被小米运动健康占用时。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ ->
                val addr = input.text.toString().trim().uppercase()
                if (BluetoothAdapter.checkBluetoothAddress(addr)) select(addr)
                else info.text = "地址格式无效，请重新输入"
            }.show()
    }

    private fun select(address: String) {
        setResult(RESULT_OK, Intent().putExtra("address", address))
        finish()
    }

    private fun stopScan() {
        handler.removeCallbacks(endScan)
        try { scanner?.stopScan(callback) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }
}
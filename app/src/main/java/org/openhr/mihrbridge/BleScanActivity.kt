package org.openhr.mihrbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
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
    private val found = LinkedHashMap<String, ScanResult>()
    private val labels = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var info: TextView
    private val endScan = Runnable { stopScan(); info.text = "扫描结束。选择设备尝试标准心率连接。" }
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val old = found[address]
            if (old == null || result.rssi > old.rssi) found[address] = result
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
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val list = ListView(this).apply {
            adapter = this@BleScanActivity.adapter
            setBackgroundColor(Color.WHITE)
            setOnItemClickListener { _, _, index, _ ->
                val device = found.values.sortedByDescending { it.rssi }.getOrNull(index) ?: return@setOnItemClickListener
                setResult(RESULT_OK, Intent().putExtra("address", device.device.address))
                finish()
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        startScan()
    }

    private fun updateList() {
        labels.clear()
        found.values.sortedByDescending { it.rssi }.forEach { result ->
            val name = result.scanRecord?.deviceName ?: try { result.device.name } catch (_: Exception) { null }
            val label = name?.takeUnless { it.isBlank() } ?: "未命名 BLE 设备"
            labels.add("$label\n${result.device.address}     RSSI ${result.rssi} dBm")
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

    private fun stopScan() {
        handler.removeCallbacks(endScan)
        try { scanner?.stopScan(callback) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }
}
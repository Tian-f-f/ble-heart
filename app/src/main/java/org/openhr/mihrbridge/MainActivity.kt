package org.openhr.mihrbridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Self-contained Android view UI; no Compose or third-party UI frameworks. */
class MainActivity : Activity() {
    private val page = Color.rgb(11, 19, 33)
    private val cardColor = Color.rgb(22, 35, 55)
    private val white = Color.rgb(236, 245, 253)
    private val muted = Color.rgb(156, 177, 197)
    private val accent = Color.rgb(57, 208, 198)
    private val handler = Handler(Looper.getMainLooper())
    private var bridge: HrBridgeService? = null
    private var bound = false
    private var desiredSource = HrBridgeService.Source.DEMO
    private var pendingInputAddress: String? = null
    private lateinit var rate: TextView
    private lateinit var status: TextView
    private lateinit var connections: TextView
    private lateinit var inputStatus: TextView
    private lateinit var eventStatus: TextView
    private lateinit var diagnosticCounters: TextView
    private lateinit var diagnosticPreview: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var bpmEdit: EditText
    private lateinit var radioGroup: RadioGroup
    private val onConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            bridge = (binder as HrBridgeService.LocalBinder).service()
            val snapshot = bridge?.snapshot()
            val pending = pendingInputAddress
            if (pending != null) {
                pendingInputAddress = null
                desiredSource = HrBridgeService.Source.STANDARD_BLE
                radioGroup.check(103)
                bridge?.connectInput(pending)
            } else if (snapshot?.running == true) {
                desiredSource = snapshot.source
                radioGroup.check(when (desiredSource) {
                    HrBridgeService.Source.DEMO -> 101
                    HrBridgeService.Source.MANUAL -> 102
                    HrBridgeService.Source.STANDARD_BLE -> 103
                })
            } else bridge?.setSource(desiredSource)
            repaint()
        }
        override fun onServiceDisconnected(name: ComponentName) { bridge = null }
    }
    private val refresh = object : Runnable {
        override fun run() {
            repaint()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        render()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, HrBridgeService::class.java), onConnection, BIND_AUTO_CREATE)
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        if (bound) {
            unbindService(onConnection)
            bound = false
        }
        bridge = null
        super.onStop()
    }

    private fun render() {
        val scroll = ScrollView(this).apply { setBackgroundColor(page); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(32))
        }
        scroll.addView(root)
        root.addView(text("Mi HR Bridge", 29f, white, true))
        root.addView(text("蓝牙心率转发 · C506SE 诊断版 0.2.1", 12f, muted).apply {
            setPadding(0, dp(5), 0, dp(18))
        })

        val mainCard = card()
        mainCard.addView(text("输出心率", 14f, muted))
        rate = text("-- BPM", 46f, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(10))
        }
        mainCard.addView(rate)
        serviceStatus = text("转发服务尚未启动", 14f, muted)
        mainCard.addView(serviceStatus)
        status = text("广播：未启动", 13f, muted)
        mainCard.addView(status)
        connections = text("已连接客户端：0 · 心率订阅：0", 13f, muted)
        mainCard.addView(connections)
        root.addView(mainCard)

        root.addView(section("1  启动标准心率服务"))
        root.addView(button("启动 BLE 心率广播", accent, page) {
            checkPermissionsThen(REQ_START)
        })
        root.addView(button("停止广播", cardColor, white) {
            startService(Intent(this, HrBridgeService::class.java).setAction(HrBridgeService.ACTION_STOP))
        })
        root.addView(hint("Windows 电脑或未来的码表应发现标准 0x180D 服务，订阅 0x2A37。设备名通常显示为手机系统蓝牙名称。"))

        root.addView(section("2  选择心率数据来源"))
        radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(option(101, "模拟心率（测试，约 90 BPM）"))
            addView(option(102, "手动输入心率（测试）"))
            addView(option(103, "标准 BLE 心率设备（真实输入）"))
            check(101)
            setOnCheckedChangeListener { _, checkedId ->
                desiredSource = when (checkedId) {
                    102 -> HrBridgeService.Source.MANUAL
                    103 -> HrBridgeService.Source.STANDARD_BLE
                    else -> HrBridgeService.Source.DEMO
                }
                bridge?.setSource(desiredSource)
            }
        }
        root.addView(radioGroup)
        val manualRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bpmEdit = EditText(this).apply {
            setText("80")
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        }
        manualRow.addView(bpmEdit)
        manualRow.addView(button("设定 BPM", accent, page) {
            val value = bpmEdit.text.toString().toIntOrNull()
            if (value == null || value !in 30..220) {
                toast("请输入 30–220 之间的心率")
            } else {
                desiredSource = HrBridgeService.Source.MANUAL
                radioGroup.check(102)
                bridge?.setManualBpm(value)
                toast("测试心率已设置为 $value BPM")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(146), dp(50)) })
        root.addView(manualRow)

        root.addView(section("3  真实 BLE 输入 / 手环探测"))
        root.addView(button("扫描 BLE 设备并选择输入", cardColor, white) {
            checkPermissionsThen(REQ_SCAN)
        })
        root.addView(button("手环 GATT 服务诊断（只读）", cardColor, white) {
            checkPermissionsThen(REQ_DIAG)
        })
        root.addView(button("断开输入设备", cardColor, white) {
            bridge?.disconnectInput()
        })
        inputStatus = text("输入端：未连接", 13f, muted)
        root.addView(inputStatus)
        root.addView(hint("标准 BLE 设备可读取真实心率。新增只读 GATT 诊断可列出手环服务和特征，但不能破解认证或获得真实 BPM。若小米运动健康占用手环，可能无法连接；不要解绑或选择连接新手机。"))

        root.addView(section("4  运行诊断"))
        val logCard = card()
        eventStatus = text("等待操作", 12f, muted)
        logCard.addView(eventStatus)
        root.addView(logCard)
        root.addView(hint("数据源切为真实 BLE 后，超过 5 秒未收到新心率，将停止发送旧数据。需要关闭锁屏清理/省电限制后再测试长期运行稳定性。"))

        root.addView(section("5  C506SE · GATT 交互诊断"))
        val diagCard = card()
        diagnosticCounters = text("CCCD 读/写：0 / 0", 13f, white)
        diagCard.addView(diagnosticCounters)
        diagCard.addView(text("最近事件（完整日志可在下方查看）", 12f, muted).apply {
            setPadding(0, dp(12), 0, dp(6))
        })
        diagnosticPreview = text("暂无 GATT 事件。", 11f, white).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        diagCard.addView(diagnosticPreview)
        root.addView(diagCard)
        root.addView(button("查看完整日志 / 一键复制", accent, page) {
            val data = bridge?.diagnosticReport() ?: "应用服务未连接，暂无诊断数据。"
            val body = ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    text = data
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                })
            }
            AlertDialog.Builder(this)
                .setTitle("Mi HR Bridge · BLE 诊断日志")
                .setView(body)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制报告") { _, _ ->
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Mi HR Bridge GATT", data))
                    toast("诊断报告已复制")
                }
                .show()
        })
        root.addView(hint("记录仅包含 Android 可见的连接、MTU、CCCD 请求和通知状态。"
            + "如没有 CCCD 请求，仍需蓝牙 HCI 抓包才能判断码表是否完成服务发现。"))

        setContentView(scroll)
    }

    private fun repaint() {
        if (!::rate.isInitialized) return
        val sample = bridge?.snapshot()
        if (sample == null) {
            rate.text = "-- BPM"
            serviceStatus.text = "连接应用服务中…"
            return
        }
        val isFresh = sample.bpm != null
        rate.text = if (isFresh) "${sample.bpm} BPM" else "-- BPM"
        rate.setTextColor(if (isFresh) accent else white)
        serviceStatus.text = if (sample.running) "运行中 · ${sourceText(sample.source)}" else "转发服务尚未启动"
        status.text = "BLE 广播：${if (sample.advertising) "已启动" else "未启动"}"
        connections.text = "已连接客户端：${sample.connected} · 心率订阅：${sample.subscribed}"
        inputStatus.text = "输入端：${sample.inputState}"
        eventStatus.text = sample.latestEvent
        val diag = sample.diagnostics
        diagnosticCounters.text = if (diag == null) "诊断统计不可用" else
            "CCCD 读取：" + diag.cccdReads + "    CCCD 写入：" + diag.cccdWrites +
            "\n通知入队：" + diag.notificationsQueued +
            "    发送完成：" + diag.notificationsCompleted +
            "    失败：" + diag.notificationsFailed +
            "\nMTU：" + diag.lastMtu + "    最近客户端：" + diag.lastClient
        val recent = sample.history.lineSequence().filter { it.isNotBlank() }
            .toList().takeLast(12).joinToString("\n")
        val preview = recent.ifEmpty { "暂无 GATT 事件。" }
        if (diagnosticPreview.text.toString() != preview) diagnosticPreview.text = preview
    }

    private fun sourceText(src: HrBridgeService.Source) = when (src) {
        HrBridgeService.Source.DEMO -> "模拟数据"
        HrBridgeService.Source.MANUAL -> "手动测试数据"
        HrBridgeService.Source.STANDARD_BLE -> "真实 BLE 输入"
    }

    private fun checkPermissionsThen(which: Int) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (which == REQ_SCAN || which == REQ_DIAG) permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33 && which == REQ_START) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), which)
        else afterPermission(which)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_START || requestCode == REQ_SCAN || requestCode == REQ_DIAG) {
            // Notification permission is optional; BT permissions are necessary.
            val required = if (Build.VERSION.SDK_INT >= 31) {
                if (requestCode == REQ_SCAN || requestCode == REQ_DIAG) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                else listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            } else emptyList()
            if (required.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
                toast("请授权附近设备（蓝牙）权限")
            else afterPermission(requestCode)
        }
    }

    private fun afterPermission(which: Int) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            toast("本机没有蓝牙适配器")
            return
        }
        if (!adapter.isEnabled) {
            toast("请先开启蓝牙后再次点击")
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        when (which) {
            REQ_START -> {
                val action = Intent(this, HrBridgeService::class.java).setAction(HrBridgeService.ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(action) else startService(action)
                bridge?.setSource(desiredSource)
                toast("正在启动标准 BLE 心率服务")
            }
            REQ_SCAN, REQ_DIAG -> {
                if (which == REQ_SCAN && bridge?.snapshot()?.running != true) {
                    toast("请先启动心率服务，再连接输入设备")
                    return
                }
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, BleScanActivity::class.java),
                    if (which == REQ_DIAG) REQ_DIAG_DEVICE else REQ_DEVICE)
            }
        }
    }

    @Deprecated("Native Activity result API used intentionally without androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DIAG_DEVICE && resultCode == RESULT_OK) {
            val address = data?.getStringExtra("address") ?: return
            startActivity(Intent(this, GattInspectorActivity::class.java).putExtra("address", address))
            return
        }
        if (requestCode == REQ_DEVICE && resultCode == RESULT_OK) {
            val address = data?.getStringExtra("address") ?: return
            AlertDialog.Builder(this)
                .setTitle("连接蓝牙输入设备？")
                .setMessage("仅对支持 0x180D 的 BLE 心率设备有效。若为小米手环，尝试连接可能与小米运动健康发生冲突，但不会自动解绑。")
                .setNegativeButton("取消", null)
                .setPositiveButton("尝试连接") { _, _ ->
                    desiredSource = HrBridgeService.Source.STANDARD_BLE
                    radioGroup.check(103)
                    if (bridge == null) pendingInputAddress = address
                    else bridge?.connectInput(address)
                }.show()
        }
    }

    private fun option(id: Int, name: String): RadioButton = RadioButton(this).apply {
        this.id = id
        text = name
        setTextColor(white)
        textSize = 14f
        setPadding(dp(4), dp(5), 0, dp(5))
        buttonTintList = android.content.res.ColorStateList.valueOf(accent)
    }

    private fun text(value: String, size: Float, color: Int, strong: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (strong) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun hint(value: String): TextView = text(value, 12f, muted).apply {
        setPadding(dp(1), dp(8), dp(1), dp(6))
    }

    private fun section(title: String): TextView = text(title, 16f, white, true).apply {
        setPadding(0, dp(24), 0, dp(10))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cardColor, 18)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun button(title: String, backgroundColor: Int, foregroundColor: Int, click: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        setTextColor(foregroundColor)
        background = rounded(backgroundColor, 12)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(51))
        lp.topMargin = dp(8)
        layoutParams = lp
        setOnClickListener { click() }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }

    companion object { private const val REQ_START = 201; private const val REQ_SCAN = 202; private const val REQ_DEVICE = 203; private const val REQ_DIAG = 204; private const val REQ_DIAG_DEVICE = 205 }
}
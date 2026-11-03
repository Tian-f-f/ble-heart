package org.openhr.mihrbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin
import kotlin.random.Random

/** Foreground service: GATT server (output) + optional GATT client (input). */
class HrBridgeService : Service() {
    enum class Source { DEMO, MANUAL, STANDARD_BLE }
    data class Snapshot(
        val running: Boolean,
        val advertising: Boolean,
        val connected: Int,
        val subscribed: Int,
        val source: Source,
        val bpm: Int?,
        val inputState: String,
        val latestEvent: String
    )

    inner class LocalBinder : Binder() { fun service(): HrBridgeService = this@HrBridgeService }
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var peripheral: HeartRatePeripheral? = null
    private var inputClient: StandardHrClient? = null
    @Volatile private var running = false
    @Volatile private var mode: Source = Source.DEMO
    @Volatile private var manualBpm = 80
    @Volatile private var currentBpm: Int? = null
    @Volatile private var lastEvent = "等待启动"
    private var ticks: Long = 0
    private var lastNotificationMs: Long = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            ticks++
            val bpm = when (mode) {
                Source.DEMO -> (90 + 12.0 * sin(ticks / 6.0) + Random.nextInt(-2, 3)).toInt().coerceIn(60, 160)
                Source.MANUAL -> manualBpm
                Source.STANDARD_BLE -> {
                    val client = inputClient
                    val age = SystemClock.elapsedRealtime() - (client?.lastSampleMs ?: 0L)
                    client?.lastBpm?.takeIf { age < 5000L }
                }
            }
            currentBpm = bpm
            if (bpm != null) {
                // 1 Hz notify, but do not repeat old standard-BLE input without a fresh packet.
                val receivedTime = inputClient?.lastSampleMs ?: 0L
                if (mode != Source.STANDARD_BLE || receivedTime != lastNotificationMs) {
                    peripheral?.notifyHeartRate(bpm)
                    lastNotificationMs = receivedTime
                }
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        peripheral = HeartRatePeripheral(this, ::logEvent)
        inputClient = StandardHrClient(this, ::logEvent) { sample ->
            if (mode == Source.STANDARD_BLE) currentBpm = sample
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
            }
            ACTION_START -> {
                if (!running) {
                    try {
                        val notification = notification()
                        if (Build.VERSION.SDK_INT >= 29) {
                            startForeground(NOTIFICATION_ID, notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                        } else startForeground(NOTIFICATION_ID, notification)
                        startBridge()
                    } catch (e: Exception) {
                        logEvent("启动失败：${e.message}")
                        stopBridge()
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startBridge() {
        if (peripheral?.start() != true) {
            logEvent("无法启动蓝牙外设服务")
            stopBridge()
            stopSelf()
            return
        }
        running = true
        ticks = 0
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
        logEvent("运行中：模式=${mode.name}")
    }

    private fun stopBridge() {
        running = false
        mainHandler.removeCallbacks(ticker)
        inputClient?.disconnect()
        peripheral?.stop()
        currentBpm = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopBridge()
        super.onDestroy()
    }

    fun setSource(newSource: Source) {
        mode = newSource
        currentBpm = null
        lastNotificationMs = 0L
        logEvent("输入模式：${mode.name}")
    }

    fun setManualBpm(bpm: Int) {
        manualBpm = bpm.coerceIn(30, 220)
        if (mode == Source.MANUAL) currentBpm = manualBpm
    }

    @SuppressLint("MissingPermission")
    fun connectInput(address: String) {
        mode = Source.STANDARD_BLE
        inputClient?.connect(address)
    }

    fun disconnectInput() {
        inputClient?.disconnect()
        currentBpm = null
    }

    fun snapshot(): Snapshot = Snapshot(
        running, peripheral?.advertising ?: false,
        peripheral?.connectedCount ?: 0, peripheral?.subscribers ?: 0,
        mode, currentBpm, inputClient?.inputStatus ?: "无输入",
        lastEvent
    )

    private fun logEvent(message: String) {
        lastEvent = message
        Log.i("MiHRBridge", message)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("hr_bridge", "BLE 心率转发", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "hr_bridge")
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Mi HR Bridge 正在运行")
            .setContentText("提供 BLE 标准心率服务 0x180D")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "org.openhr.mihrbridge.START"
        const val ACTION_STOP = "org.openhr.mihrbridge.STOP"
        const val NOTIFICATION_ID = 18213
    }
}
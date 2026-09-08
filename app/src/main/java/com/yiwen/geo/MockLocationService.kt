package com.yiwen.geo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：承载 1Hz 的坐标注入循环。
 *
 * 多 provider 注入：
 * 同时注册并注入 gps + network 两个 mock provider。
 * 很多 App（钉钉/企业微信类）优先读 network provider 或做 GPS/network 交叉校验，
 * 只 mock gps 会被"穿透"；两个都 mock 后无论读哪个拿到的都是目标位置。
 *
 * 状态持久化（修"点通知回主界面后注入点变北京"）：
 * 注入参数实时存 SharedPreferences；进程被系统回收后 START_STICKY 复活时
 * 从存储恢复，而不是用硬编码默认值。
 */
class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "mock_gps"
        const val NOTIF_ID = 1001

        const val EXTRA_MODE = "mode"          // "static" | "stop" | "restore"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_SPEED = "speed"

        private const val PREF = "mock_state"

        var isRunning = false
            private set

        fun saveState(context: Context, mode: String, lat: Double, lng: Double) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("mode", mode).putFloat("lat", lat.toFloat()).putFloat("lng", lng.toFloat())
                .putBoolean("active", true).apply()
        }

        fun loadState(context: Context): Triple<String, Double, Double>? {
            val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (!sp.getBoolean("active", false)) return null
            return Triple(sp.getString("mode", "static") ?: "static",
                sp.getFloat("lat", 39.9042f).toDouble(), sp.getFloat("lng", 116.4074f).toDouble())
        }

        fun clearState(context: Context) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("active", false).apply()
        }
    }

    private lateinit var engine: MockLocationEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    // 屏幕悬浮"虚拟定位中"角标（需 SYSTEM_ALERT_WINDOW，防被后台刷新时用户可感知）
    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var mode = "static"
    @Volatile private var curLat = 0.0; @Volatile private var curLng = 0.0
    @Volatile private var speed = 0f

    override fun onCreate() {
        super.onCreate()
        engine = MockLocationEngine(this)
        makeChannel()
        loadState(this)?.let { (m, la, ln) -> mode = m; curLat = la; curLng = ln }
        showOverlay()
    }

    /** 屏幕右上角常驻"虚拟定位中"角标；无悬浮窗权限则静默跳过（不影响注入） */
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            text = "● 虚拟定位中"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 229, 57, 53))
            textSize = 11f
            setPadding(24, 10, 24, 10)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 不可聚焦、不挡触摸、屏幕外也显示
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 120
        }
        runCatching { windowManager?.addView(tv, params); overlayView = tv }
    }

    private fun hideOverlay() {
        runCatching { overlayView?.let { windowManager?.removeView(it) } }
        overlayView = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        isRunning = true

        when (intent?.getStringExtra(EXTRA_MODE)) {
            "stop" -> {
                MockStatus.userStopped = true
                clearState(this)
                stopSelf()
                return START_NOT_STICKY
            }
            "static" -> {
                mode = "static"
                curLat = intent.getDoubleExtra(EXTRA_LAT, curLat)
                curLng = intent.getDoubleExtra(EXTRA_LNG, curLng)
                speed = intent.getFloatExtra(EXTRA_SPEED, 0f)
                MockStatus.userStopped = false
                saveState(this, mode, curLat, curLng)
                startLoop()
            }
            // "restore" / 空 intent（START_STICKY 或划掉自启）：用 onCreate 恢复的状态继续
            else -> {
                if (curLat == 0.0 && curLng == 0.0) { stopSelf(); return START_NOT_STICKY }
                MockStatus.userStopped = false
                startLoop()
            }
        }
        return START_STICKY
    }

    /** 常驻：从最近任务划掉 App 也立刻自我复活，注入不断 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!MockStatus.userStopped) {
            val restart = Intent(applicationContext, MockLocationService::class.java)
            restart.putExtra(EXTRA_MODE, "restore")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restart)
            } else {
                applicationContext.startService(restart)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            try {
                engine.start()
                MockStatus.lastError = null
            } catch (e: SecurityException) {
                MockStatus.lastError = "mock_not_allowed"
                stopSelf()
                return@launch
            }
            // 100ms 高频注入。
            // 部分定位 SDK（如腾讯 locSDK）查询系统定位缓存存在时间窗口，低频注入时
            // 若 SDK 恰在两次注入间隙读缓存，可能拿到真实值。
            // 高频注入让缓存始终是刚写入的位置；协程 delay 比忙等省电。
            val tickMs = 100L
            while (isActive) {
                try {
                    engine.inject(curLat, curLng, 3.0f, speed)
                    MockStatus.lastError = null
                } catch (e: SecurityException) {
                    MockStatus.lastError = "mock_not_allowed"
                    stopSelf(); return@launch
                } catch (e: IllegalArgumentException) {
                    MockStatus.lastError = "provider_lost"
                    stopSelf(); return@launch
                }
                delay(tickMs)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).putExtra(EXTRA_MODE, "stop"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("定点注入中 %.5f, %.5f".format(curLat, curLng))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun makeChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mock GPS", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        isRunning = false
        hideOverlay()
        loop?.cancel()
        runCatching { engine.stop() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** 服务与 UI 之间的极简状态通道（同进程） */
object MockStatus {
    @Volatile var lastError: String? = null   // "mock_not_allowed" | "provider_lost"
    @Volatile var userStopped = false         // 区分"用户主动停止"与"被杀"
}

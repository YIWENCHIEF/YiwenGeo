package com.yiwen.geo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import kotlin.math.roundToInt

/** 检查本应用是否已被设为"模拟位置应用"（AppOps android:mock_location，API 29+ 可查） */
object MockAppChecker {
    fun isMockAllowed(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return true // 查不了就放行，靠运行时异常兜底
        return runCatching {
            val aom = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = aom.unsafeCheckOpNoThrow(
                "android:mock_location", android.os.Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(true)
    }
}

class MainActivity : ComponentActivity() {
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        pendingStart?.let {
            if (grants.values.any { g -> g }) ContextCompat.startForegroundService(this, it)
        }
        pendingStart = null
        startLocationUpdates()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { realLocationListener?.invoke(loc) }
        @Deprecated("legacy") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    fun startLocationUpdates() {
        // 真机注意：用户可能只授予"大致位置"（COARSE），只查 FINE 会导致监听根本没注册
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            runCatching { lm.requestLocationUpdates(p, 3000L, 0f, locationListener) }
        }
    }

    /** 同步取一个缓存位置，用于回位按钮在监听还没回调时的兜底 */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): Location? {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filterNot { it.isFromMockProvider }      // 注入中缓存读数是 mock，不能当"真实位置"
            .filter { it.accuracy <= 500f }           // 丢弃精度>500m的缓存（冷启动陈旧基站定位）
            .maxByOrNull { it.time }
            ?.let { val w = normalizeToWgs(it); Location(it.provider).apply { latitude = w[0]; longitude = w[1]; accuracy = it.accuracy } }
    }

    fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /** 打开本应用的权限设置页（改"精确/大致位置"只能从这里进） */
    fun openAppPermissionSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }
    }

    /** Android 12+：只授予 COARSE 而未授予 FINE = "大致位置"模式，系统故意加几百米抖动 */
    fun isApproxLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private var pendingStart: Intent? = null

    /** 确保位置权限后启动注入服务（Android 14+ FGS location 类型要求权限已授予） */
    fun requestLocationThen(intent: Intent) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) ContextCompat.startForegroundService(this, intent)
        else {
            pendingStart = intent
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 恢复上次选择的地图源 + 缓存一致性保障（epoch 不符/升级时清一次磁盘瓦片）
        MapSource.restore(this)
        MapSource.ensureCleanCache(this)
        // Bug1修复：清除上次会话强杀残留的 mock provider override——
        // 残留会屏蔽真实定位回调，导致"新进入无法定位，注入一次再停止才恢复"
        MockLocationEngine.cleanupStale(this)
        requestLocationPermission()
        startLocationUpdates()
        // 悬浮角标需要"显示在其他应用上层"权限（无法弹窗授权，只能跳设置页）
        if (!Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }
        setContent { MaterialTheme { YiwenGeoScreen() } }
    }

    override fun onDestroy() {
        runCatching {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        }
        super.onDestroy()
    }
}

/** 服务回调 → Compose 的桥（同进程单例） */
private var realLocationListener: ((Location) -> Unit)? = null

/** 最近一次 GPS（WGS-84）读数，用于判定 network 读数的坐标系 */
private var lastGpsWgs: Pair<GeoPoint, Long>? = null

/** 坐标系粘性判定：null=未定案；true=本机 network 返回 GCJ；false=返回 WGS。
 *  一旦用 GPS 对比定案就锁定整场会话——修复"判定摇摆导致蓝点几百米来回跳" */
private var networkIsGcj: Boolean? = null

/**
 * 统一坐标系到 WGS-84。
 * gps provider 是标准 WGS-84；但国产 ROM（小米/华为/OPPO 等无 GMS 机型）的
 * network provider 返回的是厂商 GCJ-02 火星坐标——若当 WGS 用，绘制时再转一次
 * GCJ 就是双重偏移（几百米）。判定策略（粘性，防摇摆）：
 *   1) 有 30s 内 GPS 读数 → 对比定案并锁定：偏差 100m~2.5km 判 GCJ；<100m 判 WGS；
 *      >2.5km 视为 GPS 可能陈旧，不定案；
 *   2) 未定案时按机型启发式临时处理（不锁定，避免启发式误判被永久固化）。
 */
private fun normalizeToWgs(loc: Location): DoubleArray {
    val la = loc.latitude; val lo = loc.longitude
    if (loc.provider == LocationManager.GPS_PROVIDER) {
        lastGpsWgs = GeoPoint(la, lo) to System.currentTimeMillis()
        return doubleArrayOf(la, lo)
    }
    lastGpsWgs?.let { (g, t) ->
        if (System.currentTimeMillis() - t < 30_000) {
            val d = GeoInterpolator.distanceMeters(la, lo, g.latitude, g.longitude)
            when {
                d in 100.0..2500.0 -> networkIsGcj = true   // 明显偏差 → 锁定GCJ
                d < 100.0 -> networkIsGcj = false           // 与GPS吻合 → 锁定WGS
            }
        }
    }
    val isGcj = networkIsGcj ?: run {
        val cnRom = listOf("xiaomi", "redmi", "huawei", "honor", "oppo", "oneplus", "vivo", "realme")
            .any { android.os.Build.MANUFACTURER.lowercase().contains(it) }
        cnRom && !CoordinateTransformer.outOfChina(la, lo)
    }
    return if (isGcj) CoordinateTransformer.gcj02ToWgs84(la, lo) else doubleArrayOf(la, lo)
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun YiwenGeoScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf("map") }        // "map" | "history"
    // 锚点记忆：恢复上次退出前的位置（无记录才用北京默认）；double→rawBits 保 11 位精度
    var start by remember {
        mutableStateOf(runCatching {
            val sp = context.getSharedPreferences("mock_state", Context.MODE_PRIVATE)
            if (sp.contains("anchor_bits"))
                GeoPoint(
                    Double.fromBits(sp.getLong("anchor_bits", 0)),
                    Double.fromBits(sp.getLong("anchor_bits2", 0)))
            else GeoPoint(39.90420, 116.40740)
        }.getOrDefault(GeoPoint(39.90420, 116.40740)))
    }
    // 锚点每次变化即存（长按/搜索/历史/自动居中都会触发）
    LaunchedEffect(start) {
        context.getSharedPreferences("mock_state", Context.MODE_PRIVATE).edit()
            .putLong("anchor_bits", start.latitude.toRawBits())
            .putLong("anchor_bits2", start.longitude.toRawBits())
            .apply()
    }
    var running by remember { mutableStateOf(MockLocationService.isRunning) }

    var current by remember { mutableStateOf<GeoPoint?>(null) }
    var currentAcc by remember { mutableStateOf(0f) }
    var centerReq by remember { mutableStateOf<Pair<GeoPoint, Double>?>(null) }  // 视角飞行请求
    var centerSeq by remember { mutableStateOf(0) }   // 自增，保证连点回位每次都重触发飞行
    var flySignal by remember { mutableStateOf(0) }

    var showSearch by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // 悬浮角标可见性（持久化设置；隐藏时保活窗口变纯透明而非移除）
    var overlayBadge by remember { mutableStateOf(MockLocationService.overlayBadgeVisible(context)) }
    var showMockGuide by remember { mutableStateOf(false) }
    var selfCheck by remember { mutableStateOf<String?>(null) }
    // 开屏加载：等真实位置就绪（精度≤100m）再进地图，避免"先北京后飞回"跳变；8s超时兜底
    var splashDone by remember { mutableStateOf(false) }
    var firstFix by remember { mutableStateOf(true) }   // 首次定位到达后居中视角一次
    var centeredAcc by remember { mutableStateOf(Float.MAX_VALUE) }  // 上次自动居中采用的精度
    var approxLocation by remember { mutableStateOf(false) }  // Android12+ 仅"大致位置"授权
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<PhotonSearch.Hit>>(emptyList()) }
    var history by remember { mutableStateOf<List<HistoryStore.Entry>>(emptyList()) }
    var noteEditing by remember { mutableStateOf<HistoryStore.Entry?>(null) }  // 备注编辑中
    // 锚点详细地名：防抖600ms反查（长按拖动连续改点时不刷屏请求）
    var anchorName by remember { mutableStateOf("") }
    LaunchedEffect(start.latitude, start.longitude) {
        delay(600)
        anchorName = HistoryStore.reverseName(start.latitude, start.longitude)
    }

    // —— 换源（单源模式，消除混合瓦片割裂感）——
    var sourceMode by remember { mutableStateOf(MapSource.mode) }
    // 本次会话内"不再提醒"：国内/国外分开记
    var dismissedForeign by remember { mutableStateOf(false) }   // 锚点境外→提醒换OSM被拒
    var dismissedDomestic by remember { mutableStateOf(false) }  // 锚点境内→提醒换高德被拒
    val anchorForeign = CoordinateTransformer.outOfChina(start.latitude, start.longitude)
    // 该不该弹提醒：源与锚点区域不匹配，且该区域本次会话未被拒绝过
    val promptToOsm = anchorForeign && sourceMode == MapSource.GAODE && !dismissedForeign
    val promptToGaode = !anchorForeign && sourceMode == MapSource.OSM && !dismissedDomestic
    val showSourcePrompt = promptToOsm || promptToGaode
    fun applySource(mode: String) {
        if (MapSource.mode == mode) return
        MapSource.mode = mode
        MapSource.persist(context)          // 记住选择，重启后源不变
        MapSource.wipeTileCache(context)    // 换源即清空磁盘瓦片，实时重拉当前源
        sourceMode = mode                   // key(sourceMode) 重建 MapView，内存位图全清
    }
    // 小弹窗自动消失（8s），消失即视为本次会话不再提醒该方向
    LaunchedEffect(showSourcePrompt, sourceMode) {
        if (showSourcePrompt) {
            delay(8000)
            if (promptToOsm) dismissedForeign = true else dismissedDomestic = true
        }
    }

    // 自定义抽屉：只能按钮开（右滑手势已禁用），左滑可关
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerWidthPx = with(LocalDensity.current) { 300.dp.toPx() }
    val offset by animateFloatAsState(if (drawerOpen) drawerWidthPx else 0f)

    LaunchedEffect(Unit) {
        realLocationListener = { loc ->
            // 只记录真实读数（过滤掉本应用自己注入的 mock），current 永远代表真实位置，
            // 供"回位"按钮使用；否则注入后 current 会被 mock 污染或冻结。
            if (!loc.isFromMockProvider) {
                // 修复 v2.6 回归（"注入过一次才显示"的根因）：小米等 ROM 的 network
                // provider 常不填 elapsedRealtimeNanos（=0），按差值算 age 会得到
                // "开机至今"的巨大值 → 更新被当陈旧丢弃、居中永不触发。
                // 正确姿势：字段为 0 时回退壁钟 time 判断，两者都不可信则视为新鲜。
                val ageMs = if (loc.elapsedRealtimeNanos > 0) {
                    (android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
                } else if (loc.time > 0) {
                    System.currentTimeMillis() - loc.time
                } else 0L
                // 统一坐标系：gps 读数是标准 WGS-84；国产 ROM 的 network provider
                // 返回厂商 GCJ-02，经 normalizeToWgs 粘性判定统一还原为 WGS。
                val w = normalizeToWgs(loc)
                val cand = GeoPoint(w[0], w[1])
                // 读数一律显示，但做两层防跳（Bug2：蓝点"一会精确一会跳到旁边"）：
                // 1) 明显陈旧(>60s)不覆盖；
                // 2) 离群过滤——偏离当前蓝点 500m~50km 且精度没改善时忽略
                //    （network 基站切换瞬移属此区间；>50km 是真实跨城移动，必须放行，
                //    否则用户到别的城市蓝点会卡死在旧城市）
                val ageOk = ageMs <= 60_000 || current == null
                val jumpOk = current == null || run {
                    val d = GeoInterpolator.distanceMeters(
                        current!!.latitude, current!!.longitude, cand.latitude, cand.longitude)
                    d <= 500.0 || d > 50_000.0 || (loc.accuracy > 0 && loc.accuracy < currentAcc)
                }
                if (ageOk && jumpOk) {
                    current = cand
                    currentAcc = loc.accuracy
                }
                // 首次定位到达：只居中视角（锚点保持记忆值，不被覆盖）；
                // 精度≤100m 才认为"就绪"，结束开屏加载
                val accOk = loc.accuracy == 0f || loc.accuracy <= 100f
                if (!splashDone && ageMs <= 15_000 && accOk) splashDone = true
                if (firstFix && !running && ageMs <= 15_000 && accOk) {
                    firstFix = false
                    centeredAcc = if (loc.accuracy > 0) loc.accuracy else 100f
                    centerReq = current!! to 16.5
                } else if (!firstFix && !running && centeredAcc > 30f &&
                    ageMs <= 15_000 && loc.accuracy > 0 && loc.accuracy <= (centeredAcc / 3)
                ) {
                    // 收敛改善：新读数精度比上次居中好 3 倍 → 再居中一次（自限，收敛后不再动）
                    centeredAcc = loc.accuracy
                    centerReq = current!! to 16.5
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { realLocationListener = null } }

    LaunchedEffect(Unit) {
        var seedTries = 0
        while (true) {
            delay(1000)
            val nowRunning = MockLocationService.isRunning
            running = nowRunning
            approxLocation = (context as? MainActivity)?.isApproxLocation() ?: false
            MockStatus.lastError?.let { err ->
                MockStatus.lastError = null
                if (err == "mock_not_allowed") showMockGuide = true
            }
            if (!nowRunning) history = HistoryStore.load(context)
            // 开屏超时兜底：8 秒还没等到合格定位也进主界面（总比卡 splash 强）
            if (!splashDone && ++seedTries >= 8) splashDone = true
            // 兜底 seed：小米省电模式下 requestLocationUpdates 可能整场不回调，
            // 前 30 秒主动用缓存位置喂 current（监听有产出后不再干预）
            if (current == null && !nowRunning && seedTries < 40) {
                (context as? MainActivity)?.lastKnownLocation()?.let { loc ->
                    val w = normalizeToWgs(loc)
                    current = GeoPoint(w[0], w[1])
                    currentAcc = loc.accuracy
                    splashDone = true
                    if (firstFix) {
                        firstFix = false
                        centeredAcc = if (loc.accuracy > 0) loc.accuracy else 100f
                        centerReq = current!! to 16.5
                    }
                }
            }
        }
    }

    fun beginInjection() {
        if (!MockAppChecker.isMockAllowed(context)) { showMockGuide = true; return }
        val i = Intent(context, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_MODE, "static")
            putExtra(MockLocationService.EXTRA_LAT, start.latitude)
            putExtra(MockLocationService.EXTRA_LNG, start.longitude)
        }
        (context as? MainActivity)?.requestLocationThen(i)
        running = true
        // 历史：仅在点击"开始注入"时记录一条
        scope.launch {
            val name = HistoryStore.reverseName(start.latitude, start.longitude)
            HistoryStore.add(context, start.latitude, start.longitude, "static", name)
        }
    }

    fun stopInjection() {
        context.startService(Intent(context, MockLocationService::class.java)
            .putExtra(MockLocationService.EXTRA_MODE, "stop"))
        running = false
    }

    /**
     * 定位自检：模拟位置授权 / 服务运行 / 设备全部 provider 的读数与 mock 标记。
     * 枚举 allProviders（不止标准三个）：小米等 ROM 可能存在厂商私有 provider，
     * 若 QQ 从私有通道读到 [真实✗] 就是"偶发失败"的漏点。
     */
    @SuppressLint("MissingPermission")
    fun runSelfCheck(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("模拟位置应用授权: ")
            .append(if (MockAppChecker.isMockAllowed(ctx)) "✓ 已授权" else "✗ 未授权（去开发者选项设置）")
            .append("\n")
        sb.append("注入服务: ")
            .append(if (MockLocationService.isRunning) "✓ 运行中" else "○ 未运行")
            .append("\n")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            sb.append("（无位置权限，无法读取 provider 状态）")
            return sb.toString()
        }
        val all = runCatching { lm.allProviders }.getOrNull()?.toList()
            ?: listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER)
        all.forEach { p ->
            val enabled = runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
            val last = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            sb.append("$p: ").append(if (enabled) "启用" else "禁用")
            if (last != null) {
                sb.append("  %.5f,%.5f".format(last.latitude, last.longitude))
                val sat = last.extras?.getInt("satellites", -1) ?: -1
                if (sat >= 0) sb.append(" 卫星$sat")
                sb.append(if (last.isFromMockProvider) " [MOCK✓]" else " [真实✗]")
            } else sb.append(" 无读数")
            sb.append("\n")
        }
        return sb.toString()
    }

    Box(Modifier.fillMaxSize()) {
        // —— 抽屉面板（底层）——
        Column(
            Modifier.fillMaxHeight().width(300.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.background(Color(0xFF4E8A7B)).padding(20.dp)) {
                Icon(Icons.Default.MyLocation, null, tint = Color.White,
                    modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.app_name), color = Color.White,
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("声明：本程序仅供开发人员调试使用，严禁用于伪造打卡、" +
                    "欺骗奖励、绕过业务系统等非法活动，用户不得将本应用用于上述用途，" +
                    "因不当使用造成后果由用户自行承担。",
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.bodySmall)
            }
            DrawerRow(Icons.Default.Map, "地图", page == "map") { page = "map"; drawerOpen = false }
            DrawerRow(Icons.Default.History, "历史", page == "history") {
                page = "history"; history = HistoryStore.load(context); drawerOpen = false
            }
            Spacer(Modifier.weight(1f))
            Text("版本 v" + context.packageManager.getPackageInfo(context.packageName, 0)
                    .versionName + " (YiwenGeo)",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // —— 主内容（可滑出）——
        Box(
            Modifier.fillMaxSize()
                .offset { IntOffset(offset.roundToInt(), 0) }
                .pointerInput(drawerOpen) {
                    if (drawerOpen) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onHorizontalDrag = { _, dx -> total += dx },
                            onDragEnd = { if (total < -60f) drawerOpen = false },
                            onDragCancel = {}
                        )
                    }
                }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { drawerOpen = true }) {
                                Icon(Icons.Default.Menu, "菜单")
                            }
                        },
                        title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                        actions = {
                            IconButton(onClick = { if (!running) showSearch = true }) {
                                Icon(Icons.Default.Search, "搜索")
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, "更多")
                                }
                                DropdownMenu(showMenu, { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("手动输入经纬度") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = { showMenu = false; if (!running) showManual = true })
                                    DropdownMenuItem(
                                        text = { Text("定位自检") },
                                        leadingIcon = { Icon(Icons.Default.HealthAndSafety, null) },
                                        onClick = {
                                            showMenu = false
                                            selfCheck = runSelfCheck(context)
                                        })
                                    DropdownMenuItem(
                                        text = { Text(if (sourceMode == MapSource.GAODE) "切换地图源：OSM（全球）" else "切换地图源：高德（国内高清）") },
                                        leadingIcon = { Icon(Icons.Default.Map, null) },
                                        onClick = {
                                            showMenu = false
                                            applySource(if (sourceMode == MapSource.GAODE) MapSource.OSM else MapSource.GAODE)
                                        })
                                    DropdownMenuItem(
                                        text = { Text("设置模拟位置应用") },
                                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                                        onClick = {
                                            showMenu = false
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                            }
                                        })
                                    DropdownMenuItem(
                                        text = { Text(if (overlayBadge) "隐藏悬浮角标（保活窗口转透明）" else "显示悬浮角标") },
                                        leadingIcon = {
                                            Icon(if (overlayBadge) Icons.Default.VisibilityOff
                                                 else Icons.Default.Visibility, null)
                                        },
                                        onClick = {
                                            showMenu = false
                                            overlayBadge = !overlayBadge
                                            MockLocationService.setOverlayBadgeVisible(context, overlayBadge)
                                            // 注入运行中即时生效；未运行时下次启动服务自然读取设置
                                            if (MockLocationService.isRunning) {
                                                context.startService(
                                                    Intent(context, MockLocationService::class.java)
                                                        .putExtra(MockLocationService.EXTRA_MODE, "overlay"))
                                            }
                                        })
                                }
                            }
                        }
                    )
                }
            ) { pad ->
                if (page == "map") {
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        MapPicker(
                            startWgs = start,
                            // 注入中：蓝点直接显示我们注入的锚点（其它 App 看到的就是它，
                            // 所见即所得）；未注入：显示真实定位。不依赖监听器回送自家 mock。
                            currentWgs = if (running) start else current,
                            currentAcc = if (running) 3f else currentAcc,
                            centerReq = centerReq,
                            centerSeq = centerSeq,
                            flySignal = flySignal,
                            interactive = !running,   // 注入中禁止改锚点
                            sourceMode = sourceMode,
                            onLongPressWgs = { p -> start = p; firstFix = false },  // 用户手动选点后不再自动居中
                            mapViewRef = { }
                        )

                        // 顶部横幅：声明 + 当前经纬度 + 定位精度（原底部卡片取消，不再遮挡屏幕）
                        Column(
                            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                                .background(Color(0xCCFFF3CD)).padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            // 第1行：锚点详细地名（防抖反查）+ 状态
                            Text(
                                (anchorName.ifEmpty { "长按地图设锚点" }) +
                                    (if (running) "  ·  注入中" else ""),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF664D03), maxLines = 1
                            )
                            // 第2行：11位小数经纬度
                            Text(
                                "【经度:%.11f 纬度:%.11f】".format(start.longitude, start.latitude),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF664D03), maxLines = 1
                            )
                            // 第3行：声明
                            Text(
                                "声明：本程序仅供开发人员调试使用，严禁用于非法活动",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8A6D1B), maxLines = 1
                            )
                            // Android 12+ 只给了"大致位置"→ 系统故意抖动几百米，点击去开精确
                            if (approxLocation) {
                                Text(
                                    "⚠ 当前为「大致位置」，定位偏差大。点此开启精确位置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFB00020),
                                    modifier = Modifier.clickable {
                                        (context as? MainActivity)?.openAppPermissionSettings()
                                    }
                                )
                            }
                        }

                        // 换源提醒：不遮挡屏幕的小卡片，8s 自动消失；本次会话内同方向不再提醒
                        if (showSourcePrompt) {
                            Card(
                                Modifier.align(Alignment.TopCenter)
                                    .padding(horizontal = 12.dp, vertical = 96.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xE62F4858))
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text(
                                        if (promptToOsm)
                                            "锚点已在中国境外，高德瓦片无境外覆盖，建议切换 OSM 全球源"
                                        else
                                            "锚点在中国境内，建议切换高德源（中文地名更全、更清晰）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White)
                                    Row(
                                        Modifier.fillMaxWidth().padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                                    ) {
                                        TextButton(onClick = {
                                            if (promptToOsm) dismissedForeign = true else dismissedDomestic = true
                                        }) { Text("忽略", color = Color.White.copy(alpha = 0.7f)) }
                                        TextButton(onClick = {
                                            applySource(if (promptToOsm) MapSource.OSM else MapSource.GAODE)
                                        }) { Text("立即切换", color = Color(0xFF8FD3C8)) }
                                    }
                                }
                            }
                        }

                        // 左下角注入开关（短文案，减少遮挡；红/绿+图标区分状态）
                        ExtendedFloatingActionButton(
                            onClick = { if (running) stopInjection() else beginInjection() },
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 20.dp),
                            containerColor = if (running) Color(0xFFE53935) else Color(0xFF43A047),
                            contentColor = Color.White,
                            icon = {
                                Icon(if (running) Icons.Default.StopCircle else Icons.Default.Place, null)
                            },
                            text = {
                                Text(if (running) "停止" else "注入", fontWeight = FontWeight.Bold)
                            }
                        )

                        // 回位：跟随蓝标位置——注入中蓝标=锚点（回到注入点），
                        // 未注入时蓝标=真实位置（回到真实位置）
                        FloatingActionButton(
                            onClick = {
                                (context as? MainActivity)?.startLocationUpdates()
                                val c = if (running) start else (current
                                    ?: (context as? MainActivity)?.lastKnownLocation()?.let {
                                        GeoPoint(it.latitude, it.longitude)
                                    })
                                if (c != null) {
                                    centerSeq++
                                    centerReq = c to 16.5
                                } else (context as? MainActivity)?.requestLocationPermission()
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp)
                        ) { Icon(Icons.Default.MyLocation, "回位") }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                        item {
                            Text("注入历史", Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        if (history.isEmpty()) item {
                            Text("暂无记录。点击「开始注入」时自动记录。",
                                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        items(history, key = { it.timeMs }) { e ->
                            HistoryRow(
                                e = e,
                                onClick = {
                                    if (!running) {
                                        start = GeoPoint(e.lat, e.lng)
                                        flySignal++
                                        page = "map"
                                    }
                                },
                                onDelete = {
                                    HistoryStore.delete(context, e.timeMs)
                                    history = HistoryStore.load(context)
                                },
                                onEditNote = { noteEditing = e }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            // 遮罩：放在主内容 Box 内部（随内容右移），只盖住右侧内容区；
            // 左侧抽屉面板不被覆盖，可正常点选。点遮罩或左滑关闭。
            if (drawerOpen) {
                var total = 0f
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onHorizontalDrag = { _, dx -> total += dx },
                                onDragEnd = { if (total < -60f) drawerOpen = false },
                                onDragCancel = {}
                            )
                        }
                        .clickable(enabled = true, onClick = { drawerOpen = false })
                )
            }
        }

        // —— 开屏加载页：等真实位置就绪再进地图，避免"先北京后飞回"跳变 ——
        if (!splashDone) {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF264E46)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.MyLocation, null, tint = Color.White,
                    modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.app_name), color = Color.White,
                    style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(color = Color.White,
                    modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text("正在获取真实位置…", color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // 历史备注编辑
    noteEditing?.let { entry ->
        var noteText by remember(entry.timeMs) { mutableStateOf(entry.note) }
        AlertDialog(
            onDismissRequest = { noteEditing = null },
            title = { Text("编辑备注") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.coordText(), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(noteText, { noteText = it },
                        label = { Text("备注（如：公司打卡点）") },
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    HistoryStore.setNote(context, entry.timeMs, noteText.trim())
                    history = HistoryStore.load(context)
                    noteEditing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { noteEditing = null }) { Text("取消") } }
        )
    }

    // 搜索对话框
    if (showSearch) {
        AlertDialog(
            onDismissRequest = { showSearch = false; hits = emptyList() },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSearch = false; hits = emptyList() }) { Text("关闭") } },
            title = { Text("搜索位置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        label = { Text("地点，如：上海虹桥站") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        searching = true
                        // 位置偏置：优先真实当前位置，其次锚点——修复"星巴克返回台南"
                        val bias = current ?: start
                        scope.launch { hits = PhotonSearch.search(query, bias); searching = false }
                    }, enabled = !searching && query.isNotBlank(),
                        modifier = Modifier.align(Alignment.End)) {
                        Text(if (searching) "搜索中…" else "搜索")
                    }
                    hits.forEach { hit ->
                        TextButton(onClick = {
                            start = GeoPoint(hit.lat, hit.lng)
                            flySignal++; showSearch = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(hit.label, maxLines = 2)
                        }
                    }
                }
            }
        )
    }

    // 手动输入经纬度
    if (showManual) {
        var mLat by remember { mutableStateOf(start.latitude.toString()) }
        var mLng by remember { mutableStateOf(start.longitude.toString()) }
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("手动输入经纬度（WGS-84）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mLat, { mLat = it }, label = { Text("纬度") }, singleLine = true)
                    OutlinedTextField(mLng, { mLng = it }, label = { Text("经度") }, singleLine = true)
                    Text("如 39.904200 / 116.407400（故宫）", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val la = mLat.toDoubleOrNull()
                    val ln = mLng.toDoubleOrNull()
                    if (la != null && ln != null) { start = GeoPoint(la, ln); flySignal++ }
                    showManual = false
                }) { Text("定位到该点") }
            },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("取消") } }
        )
    }

    // 注入失败 → 引导设置模拟位置应用
    if (showMockGuide) {
        AlertDialog(
            onDismissRequest = { showMockGuide = false },
            title = { Text("无法注入位置") },
            text = {
                Text("本应用还没有被设为「模拟位置应用」。\n\n" +
                    "请到 系统设置 → 开发者选项 → 选择模拟位置信息应用 → 选择 " +
                    stringResource(R.string.app_name) + "，然后回到本应用重新开始注入。\n" +
                    "（列表里找不到本应用时，请确认使用的是最新 APK 并已重启手机）")
            },
            confirmButton = {
                TextButton(onClick = {
                    showMockGuide = false
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }
                }) { Text("去设置") }
            },
            dismissButton = { TextButton(onClick = { showMockGuide = false }) { Text("知道了") } }
        )
    }

    // 定位自检结果
    selfCheck?.let { report ->
        AlertDialog(
            onDismissRequest = { selfCheck = null },
            title = { Text("定位自检") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(report, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { selfCheck = null }) { Text("关闭") } }
        )
    }
}

@Composable
fun DrawerRow(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.primary else Color.DarkGray)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun HistoryRow(
    e: HistoryStore.Entry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEditNote: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text(e.label.ifEmpty { "未知道路" }, style = MaterialTheme.typography.titleSmall)
        // 详细经纬度标注：11 位小数
        Text(e.coordText(), style = MaterialTheme.typography.bodySmall)
        if (e.note.isNotEmpty()) {
            Text("备注：${e.note}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${e.timeText()} · ${if (e.mode == "route") "路线" else "定点"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEditNote) { Text("备注", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * 全屏地图（高德瓦片，中文，GCJ-02）。对外交互一律 WGS-84。
 * 缩放限制 z4~z19（两端瓦片都存在，极端层级不再空白）。
 * 瓦片双线性过滤消除锯齿毛边。
 * 锚点 = 矢量水滴针（无马赛克边缘）；当前位置 = 蓝点 + 1σ/2σ 精度圈。
 */
@Composable
fun MapPicker(
    startWgs: GeoPoint,
    currentWgs: GeoPoint?,
    currentAcc: Float,
    centerReq: Pair<GeoPoint, Double>?,   // (WGS-84点, 目标缩放) 请求
    centerSeq: Int,                        // 自增序号：同一坐标连点也强制重飞
    flySignal: Int,                        // 视角飞到锚点
    interactive: Boolean,                  // 注入中锁定长按改锚点
    sourceMode: String,                    // 当前瓦片源（变化触发运行时换源）
    onLongPressWgs: (GeoPoint) -> Unit,
    mapViewRef: (MapView) -> Unit
) {
    val context = LocalContext.current
    val currentOnLongPress by rememberUpdatedState(onLongPressWgs)
    val currentInteractive by rememberUpdatedState(interactive)
    // 切源重建 MapView 前保存视角（中心+缩放），重建后恢复，避免换源瞬间跳回默认
    val savedView = remember { mutableStateOf<Pair<GeoPoint, Double>?>(null) }

    // 换源即重建 MapView：osmdroid 6.1 的 TilesOverlay 无公开 API 清空已绘制位图，
    // 热换 provider 会让旧源瓦片残留在屏上（用户截图里中国区域的高德块）。
    // remember(sourceMode) 变化 → 新 MapView 实例，画面彻底换血。
    val mapView = remember(sourceMode) {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = "YiwenGeo/1.0"
            // —— 丝滑根因修复：硬件加速必须在 new MapView 之前设 ——
            // osmdroid 6.1 的 MapView 构造读此标志决定 setLayerType，默认 false →
            // 走 LAYER_TYPE_SOFTWARE 软件渲染整张地图 → 滑动掉帧、fling 动画被拖死。
            isMapViewHardwareAccelerated = true
            // 256px 瓦片解码后每张 ~256KB，256 张 ≈ 64MB 内存上限（v3.1 的 512px
            // 高清瓦片每张 1MB 是 GC 卡顿来源，已退回标准尺寸）。
            setCacheMapTileCount(256)
            // 文件缓存 64MB：fling 甩回旧区域直接命中磁盘，不再重下
            setTileFileSystemCacheMaxBytes(64L * 1024 * 1024)
            tileDownloadThreads = 4
            tileFileSystemThreads = 4
        }
        // 全球混合源：境内高德高清 / 境外 osmfr
        val provider = MapTileProviderBasic(context, GlobalTileSource())
        MapView(context).apply {
            setTileProvider(provider)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            // 连续缩放：关闭整数层级取整，捏合按手势比例实时无级缩放
            setZoomRounding(false)
            // 惯性滑动：快速拖动/缩放抬手后按速度递减滑行停止（Scroller）
            setFlingEnabled(true)
            // 缩放范围：z3~18（高德 z1/z2/z19/z20 实测空白）
            setMinZoomLevel(3.0)
            setMaxZoomLevel(18.0)
            overlays.add(0, MapEventsOverlay(context, object : MapEventsReceiver {
                override fun longPressHelper(p: GeoPoint): Boolean {
                    if (!currentInteractive) return true   // 注入中：吞掉长按但不改点
                    // 随源反算：高德源境内GCJ→WGS；OSM源全球WGS原样
                    val w = MapSource.mapToWgs(p.latitude, p.longitude)
                    currentOnLongPress(GeoPoint(w[0], w[1]))
                    return true
                }
                override fun singleTapConfirmedHelper(p: GeoPoint) = false
            }))
            val anchorIcon = ContextCompat.getDrawable(context, R.drawable.ic_anchor_pin)
            val startMarker = Marker(this).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "锚点"
                anchorIcon?.let { setIcon(it) }
            }
            val myLocation = MyLocationOverlay()
            overlays.add(startMarker); overlays.add(myLocation)
            post {
                // 换源重建：恢复旧视角；首次进入：默认锚点 z12
                savedView.value?.let { (c, z) ->
                    controller.setZoom(z)
                    controller.setCenter(c)
                } ?: run {
                    controller.setZoom(12.0)
                    val g = MapSource.wgsToMap(startWgs.latitude, startWgs.longitude)
                    controller.setCenter(GeoPoint(g[0], g[1]))
                }
            }
            tag = Pair(startMarker, myLocation)
        }.also(mapViewRef)
    }

    // 回位/飞行请求：平滑动画居中并缩放（centerSeq 变化即触发，连点同一坐标也重飞）
    LaunchedEffect(centerReq, centerSeq) {
        centerReq?.let { (p, zoom) ->
            val g = MapSource.wgsToMap(p.latitude, p.longitude)
            // animateTo(点, 目标缩放, 时长ms)：内置插值，起止慢中间快
            mapView.controller.animateTo(GeoPoint(g[0], g[1]), zoom, 700L)
        }
    }

    LaunchedEffect(flySignal) {
        if (flySignal > 0) {
            val g = MapSource.wgsToMap(startWgs.latitude, startWgs.longitude)
            mapView.controller.animateTo(GeoPoint(g[0], g[1]), 15.5, 700L)
        }
    }

    // key(sourceMode)：换源时强制 AndroidView 重新执行 factory（拿到新 MapView）。
    // onRelease 里保存旧视角供新实例恢复；TilesOverlay 无公开清空 API，
    // 只有重建才能彻底清掉旧源已绘制位图（用户截图的残留高德块）。
    key(sourceMode) {
        AndroidView(
            factory = { mapView },
            update = { mv ->
                val tag = mv.tag as Pair<Marker, MyLocationOverlay>
                val sg = MapSource.wgsToMap(startWgs.latitude, startWgs.longitude)
                tag.first.position = GeoPoint(sg[0], sg[1])
                if (currentWgs != null) {
                    val cg = MapSource.wgsToMap(currentWgs.latitude, currentWgs.longitude)
                    tag.second.locationGcj = GeoPoint(cg[0], cg[1])
                    tag.second.accuracyMeters = currentAcc
                } else tag.second.locationGcj = null
                mv.invalidate()
            },
            onRelease = { mv ->
                savedView.value = GeoPoint(mv.mapCenter.latitude, mv.mapCenter.longitude) to mv.zoomLevelDouble
                // onPause 只暂停 overlay；provider 的下载/解码线程池必须 detach 才停。
                // 默认 MapView(context) 构造不置 destroyOnDetach（javap 验证），不显式
                // onDetach 的话：换源后旧 provider 仍在途请求会以旧源身份写入共享 SQLite。
                mv.onPause()
                mv.onDetach()
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

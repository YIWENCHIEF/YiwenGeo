package com.yiwen.geo

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/**
 * Mock 注入引擎 —— 整个虚拟定位原理的核心。
 *
 * Android 的 LocationManager 提供了一套 "Test Provider" API（系统内部称 mock provider）：
 *   1. addTestProvider()        注册虚拟 provider
 *   2. setTestProviderEnabled() 打开它，系统定位结果会包含这个 provider
 *   3. setTestProviderLocation() 每隔 1s 左右推送一个新 Location
 *   4. removeTestProvider()     退出时清理
 *
 * 双 provider 策略：同时 mock "gps" 与 "network" 两个 provider。
 * 钉钉/企业微信等 App 常读
 * network provider 或做交叉校验，只 mock gps 会被穿透暴露真实位置。
 *
 * 前提：用户必须在「开发者选项 → 选择模拟位置应用」里选中本 App。
 * 未选中时 setTestProviderLocation 会抛 SecurityException —— 服务层捕获后
 * 通过 MockStatus 通知 UI 弹引导。
 */
class MockLocationEngine(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // 双 provider：gps + network。
    // 钉钉/企业微信类 App 常读 network，只 mock gps 会被穿透。
    // 注意：passive 不能 addTestProvider（系统派生 provider，注册必抛"already exists"），
    // 但它会自动转发其他 provider 上报的位置——mock gps/network 后 passive 自然也是假位置。
    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )
    private val registered = mutableSetOf<String>()

    fun start() {
        providers.forEach { name ->
            if (name !in registered) {
                val first = runCatching { registerOne(name) }
                val ok = first.isSuccess || runCatching {
                    locationManager.removeTestProvider(name)
                    registerOne(name)
                }.onFailure { android.util.Log.e("MockEngine", "register $name failed", first.exceptionOrNull()) }.isSuccess
                if (!ok) return@forEach
                registered.add(name)
            }
            runCatching { locationManager.setTestProviderEnabled(name, true) }
        }
    }

    // provider 注册参数说明（实测新版 Android addTestProvider 校验
    // powerUsage∈[1,3]、accuracy∈[1,2]，越界值直接抛 IllegalArgumentException，
    // 因此统一钳制到合法区间）：
    //   gps    : requiresSatellite=true, requiresCell=true, supports全部true, power=1, accuracy=2
    //   network: requires/supports全部false, power=1, accuracy=1
    // 部分定位 SDK 会对 gps 读数做"要求卫星"的匹配检查，参数不符会被过滤。
    private fun registerOne(name: String) {
        val isGps = name == LocationManager.GPS_PROVIDER
        locationManager.addTestProvider(
            name,
            false,                       // requiresNetwork
            isGps,                       // requiresSatellite
            isGps,                       // requiresCell
            false,                       // hasMonetaryCost
            isGps,                       // supportsAltitude
            isGps,                       // supportsSpeed
            isGps,                       // supportsBearing
            1,                           // powerRequirement（钳制为 LOW=1）
            if (isGps) 2 else 1          // accuracy（FINE=2 / COARSE=1）
        )
    }

    /** 注入一个坐标到所有已注册 provider。
     *  gps 读数携带完整"真卫星定位"特征：
     *  部分定位 SDK（如腾讯 locSDK）拿到系统 GPS 后会校验卫星数+载噪比
     *  （读数为 0 会被判不可信→降级走基站/WiFi 指纹定位=真实位置）。
     *  maxCn0/meanCn0 取典型良好值，vertical/speed/bearing 精度字段全量补齐，
     *  让融合算法稳定采信 GPS 侧。 */
    fun inject(lat: Double, lng: Double, accuracy: Float, speed: Float) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        providers.forEach { name ->
            if (name in registered) {
                val location = Location(name).apply {
                    this.latitude = lat
                    this.longitude = lng
                    this.speed = speed
                    time = now
                    elapsedRealtimeNanos = elapsed
                    if (name == LocationManager.GPS_PROVIDER) {
                        altitude = 55.0
                        this.accuracy = 2.0f
                        bearing = 1.0f
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            verticalAccuracyMeters = 3.0f
                            speedAccuracyMetersPerSecond = 0.5f
                            bearingAccuracyDegrees = 5.0f
                        }
                        // 真卫星特征 extras（卫星数 + 载噪比）
                        extras = android.os.Bundle().apply {
                            putInt("satellites", 7)
                            putInt("maxCn0", 42)    // 最强信噪比 dB-Hz，真实良好值
                            putInt("meanCn0", 36)   // 平均信噪比
                        }
                    } else {
                        this.accuracy = accuracy
                    }
                }
                // isFromMockProvider 由系统自动标记（API 30+ 手动 set 会抛异常）
                locationManager.setTestProviderLocation(name, location)
            }
        }
    }

    fun stop() {
        registered.toList().forEach { name ->
            runCatching { locationManager.setTestProviderEnabled(name, false) }
            runCatching { locationManager.removeTestProvider(name) }
        }
        registered.clear()
    }

    companion object {
        /**
         * 清除上次会话残留的 mock provider（进程被 force-stop/系统强杀时服务没走
         * onDestroy，注册一直留在系统里）。残留的 override 会屏蔽真实定位回调——
         * 这正是"新进入无法定位，注入一次再停止才恢复"的根因：注入→停止这个动作
         * 恰好把 override 释放了。App 启动时主动清一次。
         */
        fun cleanupStale(context: Context) {
            if (MockLocationService.isRunning) return   // 正在注入，别拆自己的台
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { name ->
                runCatching { lm.removeTestProvider(name) }  // 非 mock provider 会抛异常，忽略
            }
        }
    }
}

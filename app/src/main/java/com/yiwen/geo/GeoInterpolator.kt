package com.yiwen.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 路线模拟的数学部分：在两个 GPS 坐标点之间插值。
 *
 * 为什么不能直接对经纬度做线性插值？地球是球面，经纬度线性平均得到的是
 * 「穿过地心弦的投影」，东西方向跨度大时误差可达数百米。正确做法是球面
 * 线性插值（slerp）：沿两点间的大圆（测地线）按比例取点。
 *
 * bearing（初始方位角）用初等方位角公式（initial bearing），
 * 让注入的 location.bearing 与运动方向一致，地图箭头才不会乱转。
 */
object GeoInterpolator {

    private const val EARTH_R_M = 6371008.8

    /** t∈[0,1]，返回 [lat, lng]（度） */
    fun slerp(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, t: Double): DoubleArray {
        val f1 = Math.toRadians(fromLat); val l1 = Math.toRadians(fromLng)
        val f2 = Math.toRadians(toLat);  val l2 = Math.toRadians(toLng)
        // 笛卡尔单位向量
        val v = sphericalToCartesian(f1, l1)
        val w = sphericalToCartesian(f2, l2)
        val omega = Math.acos((v[0]*w[0] + v[1]*w[1] + v[2]*w[2]).coerceIn(-1.0, 1.0))
        if (omega < 1e-9) return doubleArrayOf(fromLat, fromLng)
        val a = Math.sin((1 - t) * omega) / Math.sin(omega)
        val b = Math.sin(t * omega) / Math.sin(omega)
        val x = a * v[0] + b * w[0]
        val y = a * v[1] + b * w[1]
        val z = a * v[2] + b * w[2]
        val lat = Math.atan2(z, Math.hypot(x, y))
        val lng = Math.atan2(y, x)
        return doubleArrayOf(Math.toDegrees(lat), Math.toDegrees(lng))
    }

    private fun sphericalToCartesian(lat: Double, lng: Double): DoubleArray = doubleArrayOf(
        cos(lat) * cos(lng), cos(lat) * sin(lng), sin(lat)
    )

    /** 初等方位角（度，0=北，顺时针） */
    fun bearing(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Float {
        val f1 = Math.toRadians(fromLat); val l1 = Math.toRadians(fromLng)
        val f2 = Math.toRadians(toLat);  val l2 = Math.toRadians(toLng)
        val y = sin(l2 - l1) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(l2 - l1)
        val deg = Math.toDegrees(atan2(y, x))
        return ((deg + 360.0) % 360.0).toFloat()
    }

    /** 两点间大圆距离（米） */
    fun distanceMeters(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Double {
        val dLat = Math.toRadians(toLat - fromLat)
        val dLng = Math.toRadians(toLng - fromLng)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_R_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}

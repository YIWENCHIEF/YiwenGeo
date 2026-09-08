package com.yiwen.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84（GPS 原始坐标）↔ GCJ-02（国测局加密坐标，高德/腾讯/OSM 中国镜像瓦片用它）
 *
 * 为什么必须做这个转换：
 * 高德瓦片是 GCJ-02 坐标系，而 Mock Location 注入的是 GPS 世界的 WGS-84。
 * 若不转换，长按地图选的点在真实 GPS 里会偏移 100~700 米（区域相关）。
 * 这正是国产虚拟定位 App 的隐藏工作量之一（百度还叠了一层 BD-09）。
 *
 * 算法是公开的近似实现（Krasovsky 椭球参数 + 正弦扰动），
 * 正向精确，反向用迭代逼近，误差 < 1e-6 度（约 0.1 米）。
 */
object CoordinateTransformer {

    private const val PI = Math.PI
    private const val A = 6378245.0            // 长半轴
    private const val EE = 0.00669342162296594323  // 偏心率平方

    /** 中国境外不加密（近似判断） */
    fun outOfChina(lat: Double, lng: Double): Boolean =
        !(lng > 73.66 && lng < 135.05 && lat > 3.86 && lat < 53.55)

    /** 区域感知的绘制变换：WGS-84 → 地图坐标。
     *  混合瓦片源下：境内瓦片是 GCJ 需加密；境外瓦片是 WGS 原样。 */
    fun wgsToMap(lat: Double, lng: Double): DoubleArray =
        if (outOfChina(lat, lng)) doubleArrayOf(lat, lng) else wgs84ToGcj02(lat, lng)

    /** 区域感知的反变换：地图坐标 → WGS-84（长按选点用） */
    fun mapToWgs(lat: Double, lng: Double): DoubleArray =
        if (outOfChina(lat, lng)) doubleArrayOf(lat, lng) else gcj02ToWgs84(lat, lng)

    private fun transformLng(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * y * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }

    private fun transformLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    /** WGS-84 → GCJ-02（用于把 GPS 位置画到高德瓦片上） */
    fun wgs84ToGcj02(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / (A * (1 - EE) / (magic * sqrtMagic) * PI)
        dLng = dLng * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(lat + dLat, lng + dLng)
    }

    /** GCJ-02 → WGS-84（用于把长按高德瓦片得到的坐标还原成 GPS 注入坐标），迭代逼近 */
    fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): DoubleArray {
        if (outOfChina(gcjLat, gcjLng)) return doubleArrayOf(gcjLat, gcjLng)
        var wLat = gcjLat; var wLng = gcjLng
        repeat(5) {
            val (gLat, gLng) = wgs84ToGcj02(wLat, wLng)
            wLat += gcjLat - gLat
            wLng += gcjLng - gLng
        }
        return doubleArrayOf(wLat, wLng)
    }
}

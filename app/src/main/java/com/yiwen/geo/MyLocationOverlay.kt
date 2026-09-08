package com.yiwen.geo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos

/**
 * 当前位置蓝点 + 精度圈（模仿系统地图 App / 参考图2）。
 *
 * 用自定义 Overlay 而不是 Marker：精度圈半径是"米"，必须每帧按当前缩放
 * 换算成屏幕像素重画，Marker 做不到。
 * 双层半透明圆：内圈 = 1×accuracy，外圈 = 2×accuracy，深浅不同表示置信范围。
 */
class MyLocationOverlay : Overlay() {

    var locationGcj: GeoPoint? = null   // 调用方负责 GCJ-02 转换（对齐高德瓦片）
    var accuracyMeters: Float = 0f
    var visible: Boolean = true

    private val outerFill = Paint().apply {
        isAntiAlias = true; style = Paint.Style.FILL
        color = Color.argb(30, 66, 133, 244)
    }
    private val innerFill = Paint().apply {
        isAntiAlias = true; style = Paint.Style.FILL
        color = Color.argb(64, 66, 133, 244)
    }
    private val circleStroke = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f
        color = Color.argb(110, 66, 133, 244)
    }
    private val dotFill = Paint().apply {
        isAntiAlias = true; style = Paint.Style.FILL
        color = Color.rgb(66, 133, 244)
    }
    private val dotBorder = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 5f
        color = Color.WHITE
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !visible) return
        val loc = locationGcj ?: return
        val projection = mapView.projection
        val center = projection.toPixels(loc, null)
        val mPerPx = metersPerPixel(projection, loc.latitude)
        if (mPerPx <= 0f) return
        val r1 = (accuracyMeters / mPerPx).coerceAtMost(4000f)  // 1σ
        val r2 = r1 * 2                                          // 2σ

        if (r1 > 3f) {
            canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), r2, outerFill)
            canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), r2, circleStroke)
            canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), r1, innerFill)
        }
        canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), 20f, dotBorder)
        canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), 15f, dotFill)
    }

    /** Web Mercator：米/像素 = 156543.03392 × cos(lat) / 2^zoom */
    private fun metersPerPixel(p: org.osmdroid.views.Projection, latDeg: Double): Float {
        val zoom = p.zoomLevel
        return (156543.03392 * cos(Math.toRadians(latDeg)) / Math.pow(2.0, zoom.toDouble())).toFloat()
    }
}

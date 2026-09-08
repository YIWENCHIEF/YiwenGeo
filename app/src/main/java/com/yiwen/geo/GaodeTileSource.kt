package com.yiwen.geo

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * 高德瓦片源（免 key、中文标注）。
 *
 * scale=2 高清瓦片：512px 源图覆盖与 256px 相同的地理范围（瓦片坐标不变），
 * 配合 isTilesScaledToDpi 把高 DPI 屏上的拉伸倍率从 ~2.6x 降到 ~1.3x，
 * 消除文字/道路毛边锯齿。
 *
 * 层级范围 z3~z18：实测 z1/z2 与 z19/z20 高德返回 200 但内容是 179 字节的
 * 空白瓦片（"放大到最大/缩小到最小不显示图片"的根因），故在 MapView 限制缩放。
 *
 * 注意：高德瓦片是 GCJ-02 坐标系，长按得到的 GeoPoint 要经
 * CoordinateTransformer 转成 WGS-84 才能注入 GPS 世界（见 MainActivity）。
 */
class GaodeTileSource(satellite: Boolean) : OnlineTileSourceBase(
    if (satellite) "GaodeSatellite" else "GaodeStreet",
    3, 18, 256, ".png",
    if (satellite)
        arrayOf("https://webst01.is.autonavi.com/appmaptile?style=6&scale=2&")
    else
        arrayOf("https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=2&style=8&")
) {
    override fun getTileURLString(tileIdx: Long): String =
        baseUrl + "x=" + MapTileIndex.getX(tileIdx) +
            "&y=" + MapTileIndex.getY(tileIdx) +
            "&z=" + MapTileIndex.getZoom(tileIdx)
}

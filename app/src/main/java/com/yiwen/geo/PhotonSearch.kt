package com.yiwen.geo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 地址搜索：Photon（komoot 托管的 OSM 地理编码，免费无需 key），返回 WGS-84。
 *
 * 针对"很多地名搜不出、匹配过严"的两个修复（均实测验证）：
 * 1. 位置偏置：Photon 支持 lat/lon 参数，结果按离地图中心距离排序——
 *    修复"搜星巴克返回台南"这类无地域约束的乱序问题；
 * 2. 宽松化重试链：原查询 0 结果时，依次剥离中文 POI 常见后缀
 *    （"回龙观地铁站"→"回龙观地铁"→"回龙观"），再逐字截短（≥2字），
 *    命中即停。OSM 对"地铁站/东门"这类后缀常无独立条目，剥离后反而能命中母体。
 */
object PhotonSearch {

    data class Hit(val label: String, val lat: Double, val lng: Double)

    private val SUFFIXES = listOf(
        "地铁站", "地铁口", "地铁", "轻轨站", "公交站", "公交车站", "汽车站", "客运站",
        "火车站", "高铁站", "东站", "西站", "南站", "北东站", "北门", "南门", "东门", "西门",
        "北口", "南口", "东口", "西口", "购物中心", "商业街", "步行街", "广场", "大厦", "大楼",
        "中心", "商城", "医院", "大学", "学校", "公园", "景区", "风景区", "遗址", "站"
    )

    /** 生成宽松化查询序列：原词 → 逐层剥后缀 → 逐字截短（最短2字） */
    fun lenientQueries(q: String): List<String> {
        val out = linkedSetOf(q)
        var cur = q
        // 剥后缀（可能连续剥两层，如"XX地铁站东口"）
        var guard = 0
        while (guard++ < 3) {
            val suf = SUFFIXES.firstOrNull { cur.length > it.length + 1 && cur.endsWith(it) }
                ?: break
            cur = cur.removeSuffix(suf)
            out.add(cur)
        }
        // 逐字截短兜底
        while (cur.length > 2) {
            cur = cur.dropLast(1)
            out.add(cur)
        }
        return out.toList()
    }

    suspend fun search(query: String, near: GeoPoint? = null): List<Hit> {
        for (q in lenientQueries(query)) {
            val hits = queryOnce(q, near)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private suspend fun queryOnce(query: String, near: GeoPoint?) = withContext(Dispatchers.IO) {
        val sb = StringBuilder("https://photon.komoot.io/api/?limit=6&q=")
            .append(URLEncoder.encode(query, "UTF-8"))
        if (near != null) sb.append("&lat=").append(near.latitude).append("&lon=").append(near.longitude)
        runCatching {
            val conn = URL(sb.toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "YiwenGeo/1.0")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val features = JSONObject(body).getJSONArray("features")
            (0 until features.length()).map { i ->
                val feat = features.getJSONObject(i)
                val p = feat.getJSONObject("properties")
                val name = p.optString("name", "")
                val addr = listOf("city", "district", "country")
                    .map { p.optString(it, "") }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                val label = if (name.isNotEmpty()) "$name（$addr）" else addr.ifEmpty { query }
                val c = feat.getJSONObject("geometry").getJSONArray("coordinates")
                Hit(label, c.getDouble(1), c.getDouble(0))  // GeoJSON 是 [lng, lat]
            }
        }.getOrElse { emptyList() }
    }
}

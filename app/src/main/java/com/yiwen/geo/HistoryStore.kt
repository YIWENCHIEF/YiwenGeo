package com.yiwen.geo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 注入历史：JSON 存 SharedPreferences。
 * 每条记录含日期时间、WGS-84 经纬度（显示 11 位小数）、
 * 地名（Photon 反向地理编码）、用户备注；支持删除与编辑备注。
 */
object HistoryStore {

    data class Entry(
        val timeMs: Long,
        val lat: Double,
        val lng: Double,
        val label: String,
        val mode: String,
        var note: String = "",
    ) {
        fun timeText(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timeMs))
        /** 详细经纬度标注（11 位小数） */
        fun coordText(): String =
            "【经度:%.11f 纬度:%.11f】".format(lng, lat)
    }

    private const val PREF = "mock_history"
    private const val KEY = "entries"
    private const val MAX = 100

    fun load(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(o.getLong("t"), o.getDouble("lat"), o.getDouble("lng"),
                    o.optString("label"), o.optString("mode"), o.optString("note"))
            }
        }.getOrElse { emptyList() }
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("t", it.timeMs).put("lat", it.lat)
                .put("lng", it.lng).put("label", it.label)
                .put("mode", it.mode).put("note", it.note))
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, lat: Double, lng: Double, mode: String, label: String) {
        val list = load(context).toMutableList()
        list.add(0, Entry(System.currentTimeMillis(), lat, lng, label, mode))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(context, list)
    }

    /** 删除一条（按时间戳定位） */
    fun delete(context: Context, timeMs: Long) {
        save(context, load(context).filterNot { it.timeMs == timeMs })
    }

    /** 编辑备注 */
    fun setNote(context: Context, timeMs: Long, note: String) {
        save(context, load(context).map {
            if (it.timeMs == timeMs) it.copy(note = note) else it
        })
    }

    /** Photon 反向地理编码（WGS-84 输入），详细地名：门牌街道+小区/村+区+市。
     *  失败返回空串。 */
    suspend fun reverseName(lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(
                "https://photon.komoot.io/reverse?lat=$lat&lon=$lng"
            ).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "YiwenGeo/1.0")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val p = JSONObject(body).getJSONArray("features").getJSONObject(0)
                .getJSONObject("properties")
            // 详细优先：街道+门牌 → 小区/村 → 区 → 市 → 国
            val street = listOf(
                p.optString("street", ""), p.optString("housenumber", "")
            ).filter { it.isNotEmpty() }.joinToString("")
            val place = listOf(
                street, p.optString("locality", ""), p.optString("neighbourhood", ""),
                p.optString("district", ""), p.optString("city", ""), p.optString("country", "")
            ).filter { it.isNotEmpty() }
            place.distinct().joinToString(" ")
        }.getOrElse { "" }
    }
}

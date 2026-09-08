package com.yiwen.geo

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.io.File

/**
 * 全局地图源（单一源，避免低缩放级别下高德/OSM 瓦片混拼的割裂感）。
 *   高德源：境内中文最详；境外基本空白。
 *   OSM 源：全球覆盖；境内中文标注较弱。
 *
 * 换源纪律（修复"上次瓦片残留盖不住当前源"）：
 *   1. 模式持久化——重启后源不变，缓存与源永远一致；
 *   2. 换源即清空全部瓦片缓存（内存随 MapView 重建自动清 + 磁盘 wipeTileCache），
 *      所有瓦片从当前源实时重拉；
 *   3. cache_epoch 标记——升级首启清一次历史脏缓存（v3.3 混合源时代残留）。
 *
 * 坐标纠偏随源联动：只有"高德源+境内"需要 GCJ 变换，OSM 全球都是 WGS-84。
 */
object MapSource {
    const val GAODE = "gaode"
    const val OSM = "osm"

    private const val PREF = "map_source"
    private const val KEY_MODE = "mode"
    private const val KEY_EPOCH = "cache_epoch"
    private const val EPOCH = "v3.7"       // 改这个值可强制所有用户下次启动清一次磁盘缓存

    /**
     * 每个源一个固定唯一名——这是缓存身份。osmdroid 6.1 磁盘缓存是 SQLite
     * （SqlTileWriter），行主键 = (tileSource.name(), z/x/y)。v3.6 之前所有源
     * 共用 "GlobalSingle" 一个名字，高德/OSM 同坐标瓦片落在同一行互相顶掉：
     * 换源后新源查缓存直接命中旧源字节，永不重拉——这就是"上个源瓦片盖不掉"
     * 的根因。名字按构造期固定、不随 mode 变，两源从此物理隔离。
     */
    fun sourceName(mode: String): String = "GlobalSingle_$mode"

    @Volatile
    var mode: String = GAODE

    /** 启动时恢复上次选择的源（onCreate 调用，须早于任何 MapView 构造） */
    fun restore(context: Context) {
        mode = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_MODE, GAODE) ?: GAODE
    }

    fun persist(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode).apply()
    }

    private fun ensureConfigLoaded(context: Context) {
        // 显式 load，保证缓存路径已初始化（与 MapPicker 用同一 pref 名）
        Configuration.getInstance()
            .load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    }

    /**
     * 会话内清空磁盘瓦片缓存（换源时调用；内存缓存随 MapView 重建自然清空）。
     * 6.1 默认磁盘缓存是 SQLite（cache.db，minSdk>=10 恒用 SqlTileWriter），
     * SqlTileWriter.mDb 是**静态已打开连接**——只删目录动不了它（inode 被 fd
     * 持有，WAL 文件同理），必须走 purgeCache() 经连接清空 tiles 表。
     * v3.6 只删目录，正是"换源后旧瓦片仍被命中"的直接原因之一。
     */
    fun wipeTileCache(context: Context) {
        runCatching {
            ensureConfigLoaded(context)
            SqlTileWriter().purgeCache()   // DELETE FROM tiles：整表清空，两源全不留
        }
    }

    /**
     * 启动时清目录（epoch 升级路径专用）：此刻任何 provider 都还没构造、
     * 库未打开，删目录安全且彻底——连 v3.6 时代混存的脏 cache.db 一起换新库。
     */
    private fun wipeTileCacheOnStartup(context: Context) {
        runCatching {
            ensureConfigLoaded(context)
            val dir: File = Configuration.getInstance().osmdroidTileCache
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    /** 升级/换源后的缓存一致性保障：epoch 不匹配则清一次 */
    fun ensureCleanCache(context: Context) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (sp.getString(KEY_EPOCH, "") != EPOCH) {
            wipeTileCacheOnStartup(context)
            sp.edit().putString(KEY_EPOCH, EPOCH).apply()
        }
    }

    fun tileUrl(sourceMode: String, z: Int, x: Int, y: Int): String = when (sourceMode) {
        OSM -> "https://a.tile.openstreetmap.fr/osmfr/$z/$x/$y.png"
        // scale=1（256px 标准瓦片）：v3.1 的 scale=2 是 512px 大图，解码耗时×4、
        // 内存×4，正是滑动 GC 卡顿的来源。丝滑优先，退回标准尺寸。
        else -> "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=$x&y=$y&z=$z"
    }

    /** 绘制变换：WGS-84 → 瓦片坐标。仅"高德源+境内"做 GCJ 加密 */
    fun wgsToMap(lat: Double, lng: Double): DoubleArray =
        if (mode == GAODE && !CoordinateTransformer.outOfChina(lat, lng))
            CoordinateTransformer.wgs84ToGcj02(lat, lng)
        else doubleArrayOf(lat, lng)

    /** 反变换：瓦片坐标 → WGS-84（长按选点用） */
    fun mapToWgs(lat: Double, lng: Double): DoubleArray =
        if (mode == GAODE && !CoordinateTransformer.outOfChina(lat, lng))
            CoordinateTransformer.gcj02ToWgs84(lat, lng)
        else doubleArrayOf(lat, lng)
}

/**
 * 单一源瓦片。构造期锁定源（sourceMode 取自 MapSource.mode 的快照）：
 *   - name = "GlobalSingle_<mode>"：SQLite 行主键含 name，两源从此物理隔离，
 *     换源后新源绝不会命中旧源缓存（v3.6 同名碰撞正是残留根因）；
 *   - URL 也只按锁定的 sourceMode 生成：即使切换瞬间有旧请求在途，也不会
 *     拿新源 URL 去下载、再以旧源身份入库。
 * 换源时上层重建 MapView（新源名+新实例，内存位图全清）+ wipeTileCache
 * （purgeCache 经静态连接清空 SQLite），实时重拉当前源。
 */
class GlobalTileSource(
    private val sourceMode: String = MapSource.mode
) : OnlineTileSourceBase(
    MapSource.sourceName(sourceMode), 3, 18, 256, ".png",
    arrayOf("https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&")
) {
    override fun getTileURLString(tileIdx: Long): String = MapSource.tileUrl(
        sourceMode,
        MapTileIndex.getZoom(tileIdx), MapTileIndex.getX(tileIdx), MapTileIndex.getY(tileIdx)
    )
}

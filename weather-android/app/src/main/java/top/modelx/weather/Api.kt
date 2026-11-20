/**
 * 网络访问层：与后端 REST API 通信，带内存/磁盘缓存、设备标识与服务器地址管理。
 *
 * @author zhenhua
 * @version 1.2
 */
package top.modelx.weather

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors

/** 轻量 HTTP API 层：零三方依赖，HttpURLConnection + org.json，带离线缓存。 */
object Api {
    private lateinit var sp: SharedPreferences
    private lateinit var cache: SharedPreferences
    private val exec = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (!::sp.isInitialized)
            sp = ctx.getSharedPreferences("wxapp", Context.MODE_PRIVATE)
        if (!::cache.isInitialized)
            cache = ctx.getSharedPreferences("apicache", Context.MODE_PRIVATE)
        if (deviceId().isEmpty()) set("deviceId", UUID.randomUUID().toString())
    }

    fun deviceId(): String = sp.getString("deviceId", "") ?: ""
    fun server(): String = (sp.getString("server", "https://weather.modelx.top") ?: "").trimEnd('/')
    fun setServer(v: String) = set("server", v.trim().trimEnd('/'))
    fun set(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun getStr(k: String, d: String = ""): String = sp.getString(k, d) ?: d

    class Resp(val obj: JSONObject?, val arr: JSONArray?, val err: String?,
               val stale: Boolean = false, val cachedAt: Long = 0)

    private fun cacheKey(path: String) = "c_" + (path.hashCode().toLong() and 0xffffffffL)

    private fun cachePut(path: String, dataJson: String) {
        cache.edit().putString(cacheKey(path),
            System.currentTimeMillis().toString() + "|" + dataJson).apply()
    }

    private fun cacheGet(path: String): Pair<Long, String>? {
        val s = cache.getString(cacheKey(path), null) ?: return null
        val i = s.indexOf('|')
        if (i <= 0) return null
        return try { s.substring(0, i).toLong() to s.substring(i + 1) } catch (_: Exception) { null }
    }

    /** 展示用："离线数据 · 更新于 25 分钟前" */
    fun ageText(ts: Long): String {
        val m = (System.currentTimeMillis() - ts) / 60000
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "${m}分钟前"
            m < 60 * 24 -> "${m / 60}小时前"
            else -> "${m / (60 * 24)}天前"
        }
    }

    private fun doReq(method: String, path: String, body: String?, cb: (Resp) -> Unit) {
        exec.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(server() + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "wxapp-android")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val text = stream.bufferedReader().use { it.readText() }
                val env = JSONObject(text)
                main.post {
                    if (env.optInt("code") == 0) {
                        val d = env.opt("data")
                        if (method == "GET" && d != null && d != JSONObject.NULL)
                            cachePut(path, d.toString())
                        cb(Resp(d as? JSONObject, d as? JSONArray, null))
                    } else {
                        cb(Resp(null, null, env.optString("msg", "服务端错误")))
                    }
                }
            } catch (e: Exception) {
                // 网络失败 → 尝试离线缓存
                val hit = cacheGet(path)
                main.post {
                    if (hit != null) {
                        try {
                            val d = if (hit.second.startsWith("[")) JSONArray(hit.second) else JSONObject(hit.second)
                            cb(Resp(d as? JSONObject, d as? JSONArray, null, true, hit.first))
                        } catch (_: Exception) {
                            cb(Resp(null, null, "网络不可达：${e.message}"))
                        }
                    } else {
                        cb(Resp(null, null, "网络不可达：${e.message}"))
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun get(path: String, cb: (Resp) -> Unit) = doReq("GET", path, null, cb)
    fun post(path: String, jsonBody: String, cb: (Resp) -> Unit) = doReq("POST", path, jsonBody, cb)
    fun delete(path: String, cb: (Resp) -> Unit) = doReq("DELETE", path, null, cb)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun isAutoLoc(): Boolean = getStr("autoLoc") == "1"
    fun setAutoLoc(on: Boolean) = set("autoLoc", if (on) "1" else "0")

    // ---- 当前城市（prefs 里存 JSON）----
    fun currentCity(): JSONObject {
        val s = getStr("cityJson")
        if (s.isNotEmpty()) {
            try { return JSONObject(s) } catch (_: Exception) {}
        }
        return JSONObject().put("id", 1).put("name", "北京")
            .put("lat", 39.9075).put("lon", 116.39723)
    }

    fun setCurrentCity(o: JSONObject) = set("cityJson", o.toString())
}

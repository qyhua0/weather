/**
 * 应用主界面：实况天气、24小时曲线、15日预报、空气质量、雷达、城市管理与设置。
 *
 * @author zhenhua
 * @version 1.2
 */
package top.modelx.weather

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private var tabIcons: List<TextView> = emptyList()
    private var tabLabels: List<TextView> = emptyList()
    private var currentTab = -1

    companion object {
        const val REQ_SEARCH = 10
        const val REQ_LOC = 20
        const val REQ_NOTIF = 21

        const val C_BG = "#F3F5F9"
        const val C_PRIMARY = "#2F7CF6"
        const val C_TXT = "#2B3442"
        const val C_SUB = "#8A94A6"

        val TABS = listOf("🌤", "📅", "🛰", "👤")
        val TAB_NAMES = listOf("天气", "15日", "雷达", "我的")

        fun iconFor(t: String?): String = when {
            t == null -> "🌡"
            t.contains("雷") -> "⛈"
            t.contains("雪") -> "❄️"
            t.contains("雨") -> "🌧"
            t.contains("雾") -> "🌫"
            t.contains("霾") -> "😷"
            t.contains("阴") -> "☁️"
            t.contains("多云") || t.contains("晴间") -> "⛅"
            t.contains("晴") -> "☀️"
            else -> "🌡"
        }

        fun levelColor(level: String?): Int = when (level) {
            "red" -> Color.parseColor("#E53935")
            "orange" -> Color.parseColor("#FB8C00")
            "yellow" -> Color.parseColor("#F9A825")
            else -> Color.parseColor("#1E88E5")
        }

        fun aqiColor(aqi: Int): Int = when {
            aqi <= 50 -> Color.parseColor("#43A047")
            aqi <= 100 -> Color.parseColor("#C0CA33")
            aqi <= 150 -> Color.parseColor("#FB8C00")
            aqi <= 200 -> Color.parseColor("#E53935")
            else -> Color.parseColor("#8E24AA")
        }

        val LIFE_EMOJI = mapOf("dressing" to "👕", "uv" to "☀️", "sport" to "🏃",
            "carwash" to "🚗", "cold" to "🤧", "comfort" to "🌡️")
        val LIFE_NAMES = mapOf("dressing" to "穿衣", "uv" to "紫外线", "sport" to "运动",
            "carwash" to "洗车", "cold" to "感冒", "comfort" to "舒适度")

        val AVATAR_COLORS = listOf(0xFF5B8DEF.toInt(), 0xFFEF7A5B.toInt(), 0xFF4CAF7D.toInt(),
            0xFFB986E8.toInt(), 0xFFE8A23D.toInt(), 0xFF4FB3BF.toInt())

        fun weekday(dateStr: String, i: Int): String {
            if (i == 0) return "今天"
            if (i == 1) return "明天"
            return try {
                val cal = java.util.Calendar.getInstance()
                cal.time = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).parse(dateStr)!!
                arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
            } catch (_: Exception) { dateStr.substring(5) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        window.statusBarColor = Color.parseColor(C_PRIMARY)

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor(C_BG))

        contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(Color.WHITE)
        bar.elevation = dpf(12f)
        TABS.forEachIndexed { i, emoji ->
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER
            cell.setPadding(0, dp(7), 0, dp(6))
            val ic = TextView(this).apply { text = emoji; textSize = 17f; gravity = Gravity.CENTER }
            val lb = TextView(this).apply { text = TAB_NAMES[i]; textSize = 10.5f; gravity = Gravity.CENTER }
            cell.addView(ic)
            cell.addView(lb)
            cell.setOnClickListener { selectTab(i) }
            tabIcons = tabIcons + ic
            tabLabels = tabLabels + lb
            bar.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar)

        setContentView(root)
        askFirstRunPerms()
        selectTab(0)
        intent.getStringExtra("q")?.let {
            startActivityForResult(
                Intent(this, CitySearchActivity::class.java).putExtra("q", it), REQ_SEARCH)
        }
    }

    override fun onResume() {
        super.onResume()
        NotifyJobService.schedule(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SEARCH) selectTab(0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun selectTab(i: Int) {
        currentTab = i
        tabIcons.forEachIndexed { idx, tv ->
            tv.setTextColor(if (idx == i) Color.parseColor(C_PRIMARY) else Color.parseColor(C_SUB))
            tv.setTypeface(null, if (idx == i) Typeface.BOLD else Typeface.NORMAL)
        }
        tabLabels.forEachIndexed { idx, tv ->
            tv.setTextColor(if (idx == i) Color.parseColor(C_PRIMARY) else Color.parseColor(C_SUB))
            tv.setTypeface(null, if (idx == i) Typeface.BOLD else Typeface.NORMAL)
        }
        contentFrame.removeAllViews()
        val page = when (i) {
            0 -> buildHome()
            1 -> buildForecast()
            2 -> buildRadar()
            else -> buildMine()
        }
        contentFrame.addView(page)
    }

    // ---------- 定位 ----------

    private var locListener: android.location.LocationListener? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun hasLocPerm(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private var askedThisLaunch = false

    private fun askFirstRunPerms() {
        if (askedThisLaunch) return
        if (Api.getStr("cityJson").isEmpty() && !hasLocPerm()) {
            askedThisLaunch = true
            requestPermissions(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && !NotifyJobService.canNotify(this)) {
            askedThisLaunch = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            locate()
        }
    }

    private fun locate() {
        if (!hasLocPerm()) {
            requestPermissions(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            return
        }
        toast("正在定位…")
        val m = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = try { m.getProviders(true) } catch (_: Exception) { emptyList<String>() }
        if (providers.isEmpty()) { toast("设备定位服务不可用"); return }
        val recent = providers.mapNotNull {
            try { m.getLastKnownLocation(it) } catch (_: Exception) { null }
        }.filter { System.currentTimeMillis() - it.time < 15 * 60_000 }
            .maxByOrNull { it.time }
        if (recent != null) { applyLoc(recent.latitude, recent.longitude); return }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(l: android.location.Location) {
                try { m.removeUpdates(this) } catch (_: Exception) {}
                mainHandler.post { applyLoc(l.latitude, l.longitude) }
            }
        }
        locListener = listener
        var started = false
        for (p in providers) {
            try { m.requestLocationUpdates(p, 0L, 0f, listener); started = true } catch (_: Exception) {}
        }
        if (!started) { toast("定位启动失败"); return }
        mainHandler.postDelayed({
            if (locListener === listener) {
                for (p in providers) { try { m.removeUpdates(listener) } catch (_: Exception) {} }
                locListener = null
                toast("定位超时，请确认已开 GPS 或稍后重试")
            }
        }, 12000)
    }

    private fun applyLoc(lat: Double, lon: Double) {
        if (isFinishing) return
        val la = String.format(java.util.Locale.US, "%.4f", lat)
        val lo = String.format(java.util.Locale.US, "%.4f", lon)
        Api.get("/api/v1/city/nearby?lat=$la&lon=$lo") { r ->
            if (r.obj == null) { toast("附近没有已收录城市，请手动搜索"); return@get }
            Api.setCurrentCity(r.obj)
            Api.setAutoLoc(true)
            Api.post("/api/v1/user/cities", JSONObject().put("deviceId", Api.deviceId())
                .put("cityId", r.obj.optInt("id")).toString()) {}
            toast("已定位到 " + r.obj.optString("name"))
            if (currentTab == 0) selectTab(0)
        }
    }

    // ---------- 通用组件 ----------

    private fun card(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dpf(16f)
        }
        l.elevation = dpf(1.5f)
        l.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(14), 0, dp(14), dp(12))
        l.layoutParams = lp
        return l
    }

    private fun sectionTitle(emoji: String, t: String): LinearLayout {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        val ic = TextView(this).apply { text = emoji; textSize = 13f }
        val tv = TextView(this).apply {
            text = "  $t"; textSize = 13f; letterSpacing = 0.06f
            setTextColor(Color.parseColor(C_SUB))
        }
        row.addView(ic); row.addView(tv)
        return row
    }

    private fun chip(t: String, fg: Int, bg: Int): TextView = TextView(this).apply {
        text = t; textSize = 11f; setTextColor(fg)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply {
            setColor(bg); cornerRadius = dpf(20f)
        }
    }

    private fun circleBtn(emoji: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = emoji; textSize = 15f; gravity = Gravity.CENTER
        setWidth(dp(34)); setHeight(dp(34))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#2BFFFFFF")); shape = GradientDrawable.OVAL
        }
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- 首页 ----------

    private lateinit var homeLl: LinearLayout

    private fun buildHome(): ScrollView {
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor(C_BG))
        homeLl = LinearLayout(this)
        homeLl.orientation = LinearLayout.VERTICAL
        sv.addView(homeLl)
        loadHome()
        return sv
    }

    private fun loadHome() {
        homeLl.removeAllViews()
        val city = Api.currentCity()
        val cid = city.optInt("id", 1)

        // ---- 渐变 hero ----
        val hero = LinearLayout(this)
        hero.orientation = LinearLayout.VERTICAL
        hero.background = GradientDrawable().apply {
            colors = intArrayOf(Color.parseColor("#5B9DFF"), Color.parseColor("#2F6FE0"))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            cornerRadii = floatArrayOf(dpf(0f), dpf(0f), dpf(0f), dpf(0f), dpf(24f), dpf(24f), dpf(24f), dpf(24f))
        }
        hero.setPadding(dp(20), dp(12), dp(16), dp(20))

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        val cityTv = TextView(this).apply {
            text = "📍 ${city.optString("name")}"; textSize = 17f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.04f
        }
        val spacer = View(this)
        val btnLoc = circleBtn("◎") { locate() }
        val btnRef = circleBtn("↻") { selectTab(0) }
        top.addView(cityTv)
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(btnLoc)
        (btnLoc.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
        top.addView(btnRef)

        val bigRow = LinearLayout(this)
        bigRow.gravity = Gravity.BOTTOM
        val iconTv = TextView(this).apply { text = iconFor(null); textSize = 40f }
        val tempTv = TextView(this).apply {
            text = "--°"; textSize = 56f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        bigRow.addView(iconTv)
        bigRow.addView(tempTv)
        val condTv = TextView(this).apply {
            text = "加载中…"; textSize = 15f; setTextColor(Color.parseColor("#EAF2FF"))
        }
        val subTv = TextView(this).apply {
            text = " "; textSize = 12.5f; setTextColor(Color.parseColor("#CCDDF5"))
        }
        val astroTv = TextView(this).apply {
            text = " "; textSize = 12f; setTextColor(Color.parseColor("#B8CEF0"))
        }
        val badgeTv = TextView(this).apply {
            textSize = 11f; setTextColor(Color.parseColor("#FFF3CD"))
            setPadding(dp(10), dp(4), dp(10), dp(4)); visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#40000000")); cornerRadius = dpf(20f)
            }
        }
        fun markStale(r: Api.Resp) {
            if (r.stale && badgeTv.visibility != View.VISIBLE) {
                badgeTv.text = "⚠ 离线数据 · 更新于 " + Api.ageText(r.cachedAt)
                badgeTv.visibility = View.VISIBLE
            }
        }
        hero.addView(top)
        hero.addView(bigRow)
        hero.addView(condTv)
        hero.addView(subTv)
        hero.addView(astroTv)
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dp(8)
        hero.addView(badgeTv, blp)
        homeLl.addView(hero)

        // ---- 预警（✕ 收起为小入口，点击可查看全部；新预警会再次弹醒目条）----
        Api.get("/api/v1/alerts?cityId=$cid") { r ->
            if (r.arr != null && r.arr.length() > 0 && currentTab == 0) {
                val arr = r.arr
                val a = arr.getJSONObject(0)
                val aid = a.optString("alertId", a.optString("title"))
                val sp = getSharedPreferences("wxapp", MODE_PRIVATE)
                fun openUrl(u: String) {
                    if (u.isEmpty()) return
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    } catch (_: Exception) {
                        toast("无法打开链接")
                    }
                }
                fun showAlertsDialog() {
                    val col = LinearLayout(this)
                    col.orientation = LinearLayout.VERTICAL
                    col.setPadding(dp(20), dp(6), dp(20), 0)
                    for (i in 0 until arr.length()) {
                        val ao = arr.getJSONObject(i)
                        val u = ao.optString("url")
                        val tv = TextView(this)
                        tv.text = "⚠️ " + ao.optString("title") + "\n" + ao.optString("issuedAt")
                        tv.textSize = 13.5f
                        tv.setTextColor(Color.parseColor("#3A4656"))
                        tv.setLineSpacing(dp(2).toFloat(), 1.15f)
                        tv.setPadding(0, dp(9), 0, dp(9))
                        tv.setOnClickListener { openUrl(u) }
                        col.addView(tv)
                        if (i < arr.length() - 1) {
                            val dv = View(this)
                            dv.setBackgroundColor(Color.parseColor("#EFF2F7"))
                            col.addView(dv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                        }
                    }
                    val svDlg = ScrollView(this)
                    svDlg.addView(col)
                    android.app.AlertDialog.Builder(this)
                        .setTitle("⚠️ 气象预警（${arr.length()}）")
                        .setView(svDlg)
                        .setPositiveButton("关闭", null)
                        .show()
                }
                fun alertLp(): LinearLayout.LayoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .also { it.setMargins(dp(14), dp(12), dp(14), 0) }
                if (sp.getString("alertDismiss_$cid", "") == aid) {
                    // 已收起 → 小入口，随时可再看
                    val mini = TextView(this)
                    mini.text = "⚠️ 当前有 ${arr.length()} 条气象预警 · 点击查看"
                    mini.textSize = 12f
                    mini.setTextColor(Color.parseColor("#B26A00"))
                    mini.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FFF4DC")); cornerRadius = dpf(12f)
                    }
                    mini.setPadding(dp(14), dp(9), dp(14), dp(9))
                    mini.setOnClickListener { showAlertsDialog() }
                    homeLl.addView(mini, 1, alertLp())
                } else {
                    val bar = LinearLayout(this)
                    bar.gravity = Gravity.CENTER_VERTICAL
                    bar.background = GradientDrawable().apply {
                        setColor(levelColor(a.optString("level"))); cornerRadius = dpf(12f)
                    }
                    bar.setPadding(dp(14), dp(10), dp(6), dp(10))
                    val banner = TextView(this)
                    banner.text = "⚠️  " + a.optString("title")
                    banner.textSize = 13f
                    banner.setTextColor(Color.WHITE)
                    banner.setOnClickListener { showAlertsDialog() }
                    val closeBtn = TextView(this)
                    closeBtn.text = "✕"
                    closeBtn.textSize = 15f
                    closeBtn.setTextColor(Color.parseColor("#DDFFFFFF"))
                    closeBtn.setPadding(dp(12), dp(6), dp(8), dp(6))
                    closeBtn.setOnClickListener {
                        sp.edit().putString("alertDismiss_$cid", aid).apply()
                        homeLl.removeView(bar)
                        val mini = TextView(this)
                        mini.text = "⚠️ 当前有 ${arr.length()} 条气象预警 · 点击查看"
                        mini.textSize = 12f
                        mini.setTextColor(Color.parseColor("#B26A00"))
                        mini.background = GradientDrawable().apply {
                            setColor(Color.parseColor("#FFF4DC")); cornerRadius = dpf(12f)
                        }
                        mini.setPadding(dp(14), dp(9), dp(14), dp(9))
                        mini.setOnClickListener { showAlertsDialog() }
                        homeLl.addView(mini, 1, alertLp())
                    }
                    bar.addView(banner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    bar.addView(closeBtn)
                    homeLl.addView(bar, 1, alertLp())
                }
            }
        }

        // ---- 实时 ----
        Api.get("/api/v1/weather/realtime?cityId=$cid") { r ->
            if (currentTab != 0) return@get
            markStale(r)
            if (r.obj != null) {
                val o = r.obj
                tempTv.text = String.format("%.0f°", o.optDouble("temp", 0.0))
                iconTv.text = iconFor(o.optString("condText"))
                condTv.text = o.optString("condText") + " · " +
                    if (o.optInt("isDay", 1) == 0) "夜间" else "白天"
                subTv.text = String.format("体感 %.1f° · %s%s · 湿度 %d%%",
                    o.optDouble("feelsLike", 0.0), o.optString("windDir"),
                    o.optString("windScale"), o.optInt("humidity"))
                astroTv.text = "🌅 " + o.optString("sunrise") + "   🌇 " + o.optString("sunset")
                if (o.optInt("isDay", 1) == 0) {
                    hero.background = GradientDrawable().apply {
                        colors = intArrayOf(Color.parseColor("#2E4270"), Color.parseColor("#111C36"))
                        orientation = GradientDrawable.Orientation.TOP_BOTTOM
                        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dpf(24f), dpf(24f), dpf(24f), dpf(24f))
                    }
                }
            } else toast(r.err ?: "实时天气获取失败")
        }

        // ---- 分钟级降水 ----
        val nowCard = card()
        nowCard.addView(sectionTitle("🌦", "分钟级降水"))
        val nowSum = TextView(this).apply {
            text = "加载中…"; textSize = 16f; setTextColor(Color.parseColor(C_PRIMARY))
            setTypeface(typeface, Typeface.BOLD)
        }
        val nowSlots = HorizontalScrollView(this)
        val slotLl = LinearLayout(this)
        nowSlots.addView(slotLl)
        nowCard.addView(nowSum, mt(6))
        nowCard.addView(nowSlots, mt(6))
        (nowCard.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        homeLl.addView(nowCard)
        Api.get("/api/v1/weather/nowcast?lat=${city.optDouble("lat")}&lon=${city.optDouble("lon")}") { r ->
            if (currentTab != 0 || r.obj == null) return@get
            markStale(r)
            nowSum.text = r.obj.optString("summary")
            val slots = r.obj.optJSONArray("slots") ?: return@get
            slotLl.removeAllViews()
            for (i in 0 until minOf(slots.length(), 8)) {
                val s = slots.getJSONObject(i)
                val txt = s.optString("text")
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER
                cell.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F4F7FC")); cornerRadius = dpf(10f)
                }
                cell.setPadding(dp(10), dp(6), dp(10), dp(6))
                cell.addView(TextView(this).apply {
                    text = s.optString("time"); textSize = 10.5f
                    setTextColor(Color.parseColor(C_SUB))
                })
                cell.addView(TextView(this).apply {
                    text = (if (txt.contains("雨")) "🌧" else if (txt.contains("雪")) "❄️" else "☁️") +
                        txt.replace("降水", "").replace("无", "")
                        .ifEmpty { "无雨" }
                    textSize = 11f; setTextColor(Color.parseColor(C_TXT))
                })
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.rightMargin = dp(6)
                slotLl.addView(cell, lp)
            }
        }

        // ---- 24 小时 ----
        val hCard = card()
        hCard.addView(sectionTitle("⏱", "24 小时预报"))
        val chart = HourChartView(this)
        val chartLp = mt(4)
        chartLp.height = dp(160)
        hCard.addView(chart, chartLp)
        val hourRow = HorizontalScrollView(this)
        val hourLl = LinearLayout(this)
        hourRow.addView(hourLl)
        hCard.addView(hourRow, mt(2))
        homeLl.addView(hCard)
        Api.get("/api/v1/weather/hourly?cityId=$cid&hours=24") { r ->
            if (currentTab != 0 || r.arr == null) return@get
            markStale(r)
            val temps = ArrayList<Double>(); val pops = ArrayList<Int>()
            hourLl.removeAllViews()
            for (i in 0 until r.arr.length()) {
                val o = r.arr.getJSONObject(i)
                temps.add(o.optDouble("temp"))
                pops.add(o.optInt("pop"))
                if (i % 3 == 0) {
                    val tv = TextView(this)
                    tv.text = o.optString("time").substringAfter(' ').substring(0, 5) +
                        "\n" + iconFor(o.optString("condText")) + "\n" +
                        String.format("%.0f°", o.optDouble("temp")) +
                        (if (o.optInt("pop") > 0) "\n💧" + o.optInt("pop") + "%" else "\n ")
                    tv.textSize = 11f
                    tv.gravity = Gravity.CENTER
                    tv.setTextColor(Color.parseColor("#5A6675"))
                    tv.setPadding(dp(10), dp(4), dp(10), dp(2))
                    hourLl.addView(tv)
                }
            }
            chart.setData(temps, pops)
        }

        // ---- 空气质量 ----
        val aqiCard = card()
        aqiCard.addView(sectionTitle("🍃", "空气质量"))
        val aqiRow = LinearLayout(this)
        aqiRow.gravity = Gravity.CENTER_VERTICAL
        val aqiNum = TextView(this).apply {
            text = "--"; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#9AA5B4"))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2F5FA")); cornerRadius = dpf(12f)
            }
        }
        val aqiRight = LinearLayout(this)
        aqiRight.orientation = LinearLayout.VERTICAL
        aqiRight.setPadding(dp(12), 0, 0, 0)
        val aqiLine = TextView(this).apply { text = "加载中…"; textSize = 14f; setTypeface(typeface, Typeface.BOLD) }
        val aqiAdv = TextView(this).apply { textSize = 11.5f; setTextColor(Color.parseColor(C_SUB)) }
        aqiRight.addView(aqiLine); aqiRight.addView(aqiAdv)
        aqiRow.addView(aqiNum)
        aqiRow.addView(aqiRight, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        aqiCard.addView(aqiRow, mt(6))
        homeLl.addView(aqiCard)
        Api.get("/api/v1/air/realtime?cityId=$cid") { r ->
            if (currentTab != 0 || r.obj == null) return@get
            markStale(r)
            val o = r.obj
            val v = o.optInt("aqi")
            aqiNum.text = v.toString()
            aqiNum.setTextColor(aqiColor(v))
            aqiLine.text = o.optString("level") + " · 首要 " + o.optString("primaryPollutant")
            aqiAdv.text = String.format("PM2.5 %s · PM10 %s\n%s",
                trimNum(o.optDouble("pm25")), trimNum(o.optDouble("pm10")), o.optString("healthAdvice"))
        }

        // ---- 生活指数 ----
        val lifeCard = card()
        lifeCard.addView(sectionTitle("📋", "生活指数"))
        val grid = GridLayout(this)
        grid.columnCount = 3
        lifeCard.addView(grid, mt(4))
        homeLl.addView(lifeCard)
        Api.get("/api/v1/index/life?cityId=$cid") { r ->
            if (currentTab != 0 || r.arr == null) return@get
            grid.removeAllViews()
            for (i in 0 until r.arr.length()) {
                val o = r.arr.getJSONObject(i)
                val type = o.optString("type")
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER
                cell.setPadding(0, dp(9), 0, dp(9))
                cell.addView(TextView(this).apply { text = LIFE_EMOJI[type] ?: "📊"; textSize = 19f })
                cell.addView(TextView(this).apply {
                    text = LIFE_NAMES[type] ?: type; textSize = 11f
                    setTextColor(Color.parseColor(C_SUB))
                })
                cell.addView(TextView(this).apply {
                    text = o.optString("status"); textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (o.optInt("score") >= 70) Color.parseColor("#2E7D32")
                                 else if (o.optInt("score") >= 50) Color.parseColor("#F57F17")
                                 else Color.parseColor("#C62828"))
                })
                val lp = GridLayout.LayoutParams()
                lp.width = 0; lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                grid.addView(cell, lp)
            }
        }

        // ---- 近 5 日 + 全部 ----
        val dCard = card()
        dCard.addView(sectionTitle("📆", "未来天气"))
        homeLl.addView(dCard)
        val more = TextView(this).apply {
            text = "全部 15 天  ›"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor(C_PRIMARY))
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { selectTab(1) }
        }
        Api.get("/api/v1/weather/daily?cityId=$cid&days=15") { r ->
            if (currentTab != 0 || r.arr == null) return@get
            markStale(r)
            var gMin = 100.0; var gMax = -60.0
            for (i in 0 until r.arr.length()) {
                val o = r.arr.getJSONObject(i)
                gMin = minOf(gMin, o.optDouble("tmin", 0.0)); gMax = maxOf(gMax, o.optDouble("tmax", 0.0))
            }
            for (i in 0 until minOf(r.arr.length(), 5)) {
                val o = r.arr.getJSONObject(i)
                dCard.addView(dayRow(o, i, gMin, gMax, null))
            }
            dCard.addView(more)
        }
    }

    private fun mt(v: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(v)
        return lp
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else String.format("%.1f", d)

    /** 一行日预报：周几 | 天气 | 区间条 | 高低。15 日页复用。 */
    private fun dayRow(o: JSONObject, i: Int, gMin: Double, gMax: Double, nowTemp: Double?): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(9), 0, dp(9))
        val wd = LinearLayout(this)
        wd.orientation = LinearLayout.VERTICAL
        wd.addView(TextView(this).apply {
            text = weekday(o.optString("date"), i); textSize = 13f
            setTextColor(if (i == 0) Color.parseColor(C_PRIMARY) else Color.parseColor(C_TXT))
            setTypeface(typeface, if (i == 0) Typeface.BOLD else Typeface.NORMAL)
        })
        wd.addView(TextView(this).apply {
            text = o.optString("date").substring(5); textSize = 10f
            setTextColor(Color.parseColor(C_SUB))
        })
        row.addView(wd, LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT))
        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.addView(TextView(this).apply {
            text = iconFor(o.optString("condText")) + " " + o.optString("condText")
            textSize = 12f; setTextColor(Color.parseColor("#5A6675"))
            maxLines = 1
        })
        val pop = o.optInt("pop")
        if (pop > 0) mid.addView(TextView(this).apply {
            text = "💧 $pop%"; textSize = 10.5f; setTextColor(Color.parseColor("#4A90D9"))
        })
        row.addView(mid, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = DayRangeView(this)
        bar.gMin = gMin; bar.gMax = gMax
        bar.lo = o.optDouble("tmin", 0.0); bar.hi = o.optDouble("tmax", 0.0)
        bar.now = nowTemp
        row.addView(bar, LinearLayout.LayoutParams(0, dp(18), 1f))
        val temps = TextView(this).apply {
            text = String.format("%.0f° / %.0f°", o.optDouble("tmax"), o.optDouble("tmin"))
            textSize = 13f; setTextColor(Color.parseColor(C_TXT))
        }
        row.addView(temps)
        return row
    }

    // ---------- 15 日 ----------

    private fun buildForecast(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor(C_BG))
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        sv.addView(ll)
        val city = Api.currentCity()

        val head = LinearLayout(this)
        head.background = GradientDrawable().apply {
            colors = intArrayOf(Color.parseColor("#5B9DFF"), Color.parseColor("#2F6FE0"))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dpf(24f), dpf(24f), dpf(24f), dpf(24f))
        }
        head.setPadding(dp(20), dp(14), dp(20), dp(22))
        head.addView(TextView(this).apply {
            text = "${city.optString("name")} · 15 天预报"; textSize = 18f
            setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        })
        val sub = TextView(this).apply {
            text = "数据来源 Open-Meteo · 每日更新"; textSize = 11f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#B8CEF0"))
        }
        head.addView(sub, mt(4))
        ll.addView(head)

        val body = card()
        body.setPadding(dp(16), dp(6), dp(16), dp(6))
        ll.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(12); it.bottomMargin = dp(16)
        })
        Api.get("/api/v1/weather/daily?cityId=${city.optInt("id")}&days=15") { r ->
            if (r.arr == null) { toast(r.err ?: "获取失败"); return@get }
            body.removeAllViews()
            var gMin = 100.0; var gMax = -60.0
            for (i in 0 until r.arr.length()) {
                val o = r.arr.getJSONObject(i)
                gMin = minOf(gMin, o.optDouble("tmin", 0.0)); gMax = maxOf(gMax, o.optDouble("tmax", 0.0))
            }
            for (i in 0 until r.arr.length()) {
                val o = r.arr.getJSONObject(i)
                body.addView(dayRow(o, i, gMin, gMax, null))
                if (i < r.arr.length() - 1) {
                    val div = View(this)
                    div.setBackgroundColor(Color.parseColor("#EFF2F7"))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    lp.leftMargin = dp(46)
                    body.addView(div, lp)
                }
            }
        }
        return sv
    }

    // ---------- 雷达 ----------

    private fun buildRadar(): View {
        val frame = FrameLayout(this)
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        frame.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val chip = TextView(this).apply {
            text = "🛰 降水雷达 · RainViewer"
            textSize = 12f; setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66101E36")); cornerRadius = dpf(20f)
            }
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(14), dp(14), 0, 0)
        frame.addView(chip, lp)
        val city = Api.currentCity()
        Api.get("/api/v1/radar") { r ->
            if (r.obj == null) { toast(r.err ?: "雷达数据获取失败"); return@get }
            val frames = r.obj.optJSONArray("frames")
            val urls = ArrayList<String>(); val times = ArrayList<String>()
            if (frames != null) {
                for (i in 0 until frames.length()) {
                    val f = frames.getJSONObject(i)
                    urls.add("\"" + f.optString("tileUrl") + "\"")
                    times.add("\"" + f.optString("time").substringAfter(' ') + "\"")
                }
            }
            val html = RadarPage.buildHtml(urls, times,
                city.optDouble("lat", 35.0), city.optDouble("lon", 110.0))
            wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        return frame
    }

    // ---------- 我的 ----------

    private fun buildMine(): ScrollView {
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor(C_BG))
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        sv.addView(ll)

        // 头部
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.background = GradientDrawable().apply {
            colors = intArrayOf(Color.parseColor("#5B9DFF"), Color.parseColor("#2F6FE0"))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dpf(24f), dpf(24f), dpf(24f), dpf(24f))
        }
        head.setPadding(dp(20), dp(16), dp(20), dp(24))
        head.gravity = Gravity.CENTER_VERTICAL
        val hrow = LinearLayout(this)
        hrow.gravity = Gravity.CENTER_VERTICAL
        hrow.addView(TextView(this).apply { text = "🌤"; textSize = 30f })
        val hcol = LinearLayout(this)
        hcol.orientation = LinearLayout.VERTICAL
        hcol.setPadding(dp(12), 0, 0, 0)
        hcol.addView(TextView(this).apply {
            text = "微天气 v1.2"; textSize = 17f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        hcol.addView(TextView(this).apply {
            text = "无广告 · 无会员 · 数据开放"; textSize = 11.5f
            setTextColor(Color.parseColor("#CCE0FF"))
        })
        hrow.addView(hcol)
        head.addView(hrow)
        ll.addView(head)

        // 我的城市
        val cityCard = card()
        cityCard.addView(sectionTitle("📍", "我的城市"))
        val listLl = LinearLayout(this)
        listLl.orientation = LinearLayout.VERTICAL
        cityCard.addView(listLl, mt(4))
        val addBtn = TextView(this).apply {
            text = "＋ 添加城市"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor(C_PRIMARY))
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F2F7CF6")); cornerRadius = dpf(12f)
                setStroke(dp(1), Color.parseColor("#662F7CF6"))
            }
            setOnClickListener {
                startActivityForResult(Intent(this@MainActivity, CitySearchActivity::class.java), REQ_SEARCH)
            }
        }
        cityCard.addView(addBtn, mt(8))
        (cityCard.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        ll.addView(cityCard)
        lateinit var reloadCities: () -> Unit

        reloadCities = {
            Api.get("/api/v1/user/cities?deviceId=${Api.deviceId()}") { r ->
            if (currentTab != 3) return@get
            listLl.removeAllViews()
            if (r.arr == null || r.arr.length() == 0) {
                listLl.addView(TextView(this).apply {
                    text = "暂无城市，点上方添加"; textSize = 13f
                    setTextColor(Color.parseColor(C_SUB))
                    setPadding(0, dp(6), 0, dp(6))
                })
                return@get
            }
            val cur = Api.currentCity().optInt("id")
            for (i in 0 until r.arr.length()) {
                val o = r.arr.getJSONObject(i)
                val row = LinearLayout(this)
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(8), 0, dp(8))
                val nm = o.optString("name")
                val av = TextView(this).apply {
                    text = nm.take(1); textSize = 14f; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setWidth(dp(36)); setHeight(dp(36))
                    background = GradientDrawable().apply {
                        setColor(AVATAR_COLORS[Math.abs(nm.hashCode()) % AVATAR_COLORS.size])
                        shape = GradientDrawable.OVAL
                    }
                }
                row.addView(av)
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                col.setPadding(dp(12), 0, 0, 0)
                val id = o.optInt("id")
                col.addView(TextView(this).apply {
                    text = nm + (if (id == cur) "  ✓" else "")
                    textSize = 15f
                    setTypeface(typeface, if (id == cur) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (id == cur) Color.parseColor(C_PRIMARY) else Color.parseColor(C_TXT))
                })
                if (o.has("temp") && !o.isNull("temp")) col.addView(TextView(this).apply {
                    text = String.format("%s %.0f°", o.optString("condText"), o.optDouble("temp"))
                    textSize = 11f; setTextColor(Color.parseColor(C_SUB))
                })
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (id != cur) {
                    val del = TextView(this).apply {
                        text = "✕"; textSize = 13f; gravity = Gravity.CENTER
                        setWidth(dp(30)); setHeight(dp(30))
                        setTextColor(Color.parseColor("#9AA6B5"))
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#F1F4F9")); shape = GradientDrawable.OVAL
                        }
                        setOnClickListener {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("删除城市")
                                .setMessage("确定将「$nm」从我的城市移除吗？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除") { _, _ ->
                                    Api.delete("/api/v1/user/cities?deviceId=${Api.deviceId()}&cityId=$id") {
                                        runOnUiThread { reloadCities() }
                                    }
                                }
                                .show()
                        }
                    }
                    val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    dlp.leftMargin = dp(8)
                    row.addView(del, dlp)
                }
                row.setOnClickListener {
                    Api.setCurrentCity(o)
                    toast("已切换到 " + nm)
                    reloadCities()
                }
                listLl.addView(row)
                if (i < r.arr.length() - 1) {
                    val dv = View(this)
                    dv.setBackgroundColor(Color.parseColor("#EFF2F7"))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    lp.leftMargin = dp(48)
                    listLl.addView(dv, lp)
                }
            }
            }
        }
        reloadCities()

        // 通知说明
        val notifyCard = card()
        notifyCard.addView(sectionTitle("🔔", "智能提醒"))
        notifyCard.addView(TextView(this).apply {
            text = "每小时后台检查：新气象预警即时通知；1 小时内有雨雪提前提醒。若收不到通知，请在系统设置 → 通知 中允许「微天气」。"
            textSize = 12.5f; setTextColor(Color.parseColor("#5A6675"))
            setLineSpacing(dp(2).toFloat(), 1.15f)
        })
        ll.addView(notifyCard)

        // 关于
        val about = card()
        about.addView(sectionTitle("ℹ️", "关于"))
        about.addView(TextView(this).apply {
            text = "微天气是一个公益开源项目：无广告、无会员付费、不收集任何个人信息，全部功能永久免费。"
            textSize = 12.5f; setTextColor(Color.parseColor("#5A6675"))
            setLineSpacing(dp(2).toFloat(), 1.15f)
        }, mt(4))
        fun linkRow(label: String, value: String, action: String) {
            val rowL = LinearLayout(this)
            rowL.gravity = Gravity.CENTER_VERTICAL
            rowL.addView(TextView(this).apply {
                text = label; textSize = 12f; setTextColor(Color.parseColor(C_SUB))
                setWidth(dp(64))
            })
            rowL.addView(TextView(this).apply {
                text = value; textSize = 12f; setTextColor(Color.parseColor(C_PRIMARY))
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (action.isNotEmpty()) rowL.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action)))
                } catch (_: Exception) {
                    toast("无法打开链接")
                }
            }
            about.addView(rowL, mt(7))
        }
        linkRow("官网", "weather.modelx.top", "https://weather.modelx.top")
        linkRow("开源", "github.com/qyhua0/weather", "https://github.com/qyhua0/weather")
        linkRow("开发者", "qyhua0@hotmail.com", "mailto:qyhua0@hotmail.com")
        about.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#EFF2F7"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).also { it.topMargin = dp(10) })
        about.addView(TextView(this).apply {
            text = "数据来源：预报 Open-Meteo · 预警 中央气象台 NMC · 雷达 RainViewer · 区划 DataV\n版本 v1.2 · 设备 " + Api.deviceId().substring(0, 8)
            textSize = 11.5f; setTextColor(Color.parseColor(C_SUB))
            setLineSpacing(dp(2).toFloat(), 1.2f)
        }, mt(8))
        ll.addView(about)
        return sv
    }
}

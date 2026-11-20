/**
 * 城市搜索页：全国城市关键字/拼音搜索与添加。
 *
 * @author zhenhua
 * @version 1.2
 */
package top.modelx.weather

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class CitySearchActivity : Activity() {

    private val names = ArrayList<String>()
    private val ids = ArrayList<Int>()
    private lateinit var et: EditText
    private lateinit var lv: ListView
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        window.statusBarColor = Color.parseColor("#2F7CF6")

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#F3F5F9"))

        // 搜索条：白底圆角容器 + 🔍 + 蓝色搜索按钮
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dpf(14f)
        }
        topBar.elevation = dpf(2f)
        topBar.setPadding(dp(14), dp(6), dp(8), dp(6))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(14), dp(12), dp(14), dp(10))
        topBar.layoutParams = lp

        topBar.addView(TextView(this).apply { text = "🔍"; textSize = 15f })

        et = EditText(this)
        et.hint = "城市名或拼音"
        et.textSize = 15f
        et.maxLines = 1
        et.background = null
        et.imeOptions = EditorInfo.IME_ACTION_SEARCH
        et.setOnEditorActionListener { _, _, _ -> doSearch(); true }
        topBar.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val btn = TextView(this)
        btn.text = "搜索"
        btn.textSize = 14f
        btn.setTextColor(Color.WHITE)
        btn.setTypeface(btn.typeface, Typeface.BOLD)
        btn.gravity = Gravity.CENTER
        btn.background = GradientDrawable().apply {
            setColor(Color.parseColor("#2F7CF6")); cornerRadius = dpf(10f)
        }
        btn.setPadding(dp(16), dp(8), dp(16), dp(8))
        btn.setOnClickListener { doSearch() }
        topBar.addView(btn)
        root.addView(topBar)

        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            override fun getView(pos: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val v = super.getView(pos, convertView, parent)
                (v as? TextView)?.apply {
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    textSize = 15f
                    setTextColor(Color.parseColor("#2B3442"))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dpf(12f), dpf(12f), dpf(12f), dpf(12f))
                    }
                }
                return v
            }
        }
        lv = ListView(this)
        lv.adapter = adapter
        lv.divider = null
        lv.dividerHeight = 0
        lv.cacheColorHint = Color.TRANSPARENT
        lv.setOnItemClickListener { _, _, pos, _ -> pick(pos) }
        root.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        intent.getStringExtra("q")?.let { et.setText(it); doSearch() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun doSearch() {
        val q = et.text.toString().trim()
        if (q.isEmpty()) return
        Api.get("/api/v1/geo/search?q=${Api.enc(q)}&limit=10") { r ->
            if (isFinishing) return@get
            names.clear(); ids.clear()
            if (r.arr == null) {
                adapter.notifyDataSetChanged()
                Toast.makeText(this, r.err ?: "无结果", Toast.LENGTH_SHORT).show()
                return@get
            }
            for (i in 0 until r.arr.length()) {
                val o = r.arr.getJSONObject(i)
                ids.add(o.optInt("id"))
                val region = listOf(o.optString("admin1"), o.optString("country"))
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                names.add(o.optString("name") + "　" + region)
            }
            adapter.notifyDataSetChanged()
            if (names.isEmpty()) Toast.makeText(this, "没有匹配城市", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pick(pos: Int) {
        val cid = ids[pos]
        val body = JSONObject().put("deviceId", Api.deviceId()).put("cityId", cid).toString()
        Api.post("/api/v1/user/cities", body) { }
        Api.get("/api/v1/geo/search?q=${Api.enc(names[pos].substringBefore("　"))}&limit=10") { r ->
            if (r.arr != null) {
                for (i in 0 until r.arr.length()) {
                    val o = r.arr.getJSONObject(i)
                    if (o.optInt("id") == cid) {
                        Api.setCurrentCity(o)
                        break
                    }
                }
            }
            setResult(RESULT_OK)
            finish()
        }
    }
}

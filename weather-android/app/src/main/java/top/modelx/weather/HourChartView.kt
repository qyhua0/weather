/**
 * 自定义视图：24 小时温度渐变折线图，含最低/最高点标注。
 *
 * @author zhenhua
 * @version 1.2
 */
package top.modelx.weather

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** 24h 逐时图：渐变面积温度线 + 圆角降水概率柱。 */
class HourChartView(ctx: Context) : View(ctx) {
    var temps: List<Double> = emptyList()
    var pops: List<Int> = emptyList()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8A3D"); strokeWidth = dp(2.5f)
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var fillPaint: Paint? = null
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#59A8CBF9") }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFFFFDFD") }
    private val dotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF7A2E"); style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444"); textSize = sp(11f)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (temps.size < 2) {
            c.drawText("加载中…", dp(12f), dp(30f), textPaint)
            return
        }
        val padL = dp(10f); val padR = dp(10f); val padT = dp(26f); val padB = dp(10f)
        val w = width - padL - padR
        val h = height - padT - padB
        val tMin = temps.min()
        val tMax = temps.max()
        val span = if (tMax - tMin < 0.01) 1.0 else tMax - tMin
        val stepX = w / (temps.size - 1)

        for (i in pops.indices) {
            if (pops[i] <= 0) continue
            val bh = (h * (pops[i] / 100f))
            val x = padL + i * stepX
            val bar = RectF(x - stepX * 0.26f, padT + h - bh, x + stepX * 0.26f, padT + h)
            c.drawRoundRect(bar, dp(2.5f), dp(2.5f), barPaint)
        }

        val path = Path()
        val fp = Path()
        for (i in temps.indices) {
            val x = padL + i * stepX
            val y = padT + h - ((temps[i] - tMin) / span).toFloat() * h
            if (i == 0) { path.moveTo(x, y); fp.moveTo(x, padT + h); path.lineTo(x, y) }
            else { path.lineTo(x, y); fp.lineTo(x, y) }
        }
        fp.lineTo(padL + (temps.size - 1) * stepX, padT + h)
        fp.close()
        if (fillPaint == null) {
            fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, padT, 0f, padT + h,
                    intArrayOf(Color.parseColor("#40FF8A3D"), Color.parseColor("#05FF8A3D")),
                    null, Shader.TileMode.CLAMP)
            }
        }
        c.drawPath(fp, fillPaint!!)
        c.drawPath(path, linePaint)

        val iMax = temps.indexOf(tMax)
        val iMin = temps.indexOf(tMin)
        for (i in temps.indices) {
            val x = padL + i * stepX
            val y = padT + h - ((temps[i] - tMin) / span).toFloat() * h
            c.drawCircle(x, y, dp(2.2f), dotPaint)
            c.drawCircle(x, y, dp(2.2f), dotEdge)
            if (i == iMax) c.drawText(String.format("%.0f°", temps[i]), x - dp(9f), y - dp(9f), textPaint)
            if (i == iMin && iMin != iMax) c.drawText(String.format("%.0f°", temps[i]), x - dp(9f), y + dp(18f), textPaint)
        }
    }

    fun setData(t: List<Double>, p: List<Int>) {
        temps = t; pops = p; fillPaint = null; invalidate()
    }
}

/**
 * 自定义视图：单日温度区间横条，用于 15 日预报列表。
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
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** 15 日页：单日温度区间条（全局量程下的圆角渐变段 + 可选当前温度点）。 */
class DayRangeView(ctx: Context) : View(ctx) {
    var gMin = 0.0
    var gMax = 1.0
    var lo = 0.0
    var hi = 0.0
    var now: Double? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EEF1F6") }
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7043"); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (gMax <= gMin) return
        val r = dp(3f)
        val track = RectF(dp(1f), height / 2f - dp(2f), width - dp(1f), height / 2f + dp(2f))
        c.drawRoundRect(track, r, r, this.track)
        val x0 = dp(1f) + (width - dp(2f)) * ((lo - gMin) / (gMax - gMin)).toFloat()
        val x1 = dp(1f) + (width - dp(2f)) * ((hi - gMin) / (gMax - gMin)).toFloat()
        seg.shader = LinearGradient(x0, 0f, x1, 0f,
            intArrayOf(Color.parseColor("#64B5F6"), Color.parseColor("#FFB74D")), null,
            Shader.TileMode.CLAMP)
        c.drawLine(x0, height / 2f, x1, height / 2f, seg)
        now?.let {
            if (it in gMin..gMax) {
                val xn = dp(1f) + (width - dp(2f)) * ((it - gMin) / (gMax - gMin)).toFloat()
                c.drawCircle(xn, height / 2f, dp(4.5f), dotFill)
                c.drawCircle(xn, height / 2f, dp(4.5f), dotEdge)
            }
        }
    }
}

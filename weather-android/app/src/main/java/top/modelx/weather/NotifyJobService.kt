/**
 * 后台作业服务：每小时检查气象预警与降雨，发送通知提醒。
 *
 * @author zhenhua
 * @version 1.2
 */
package top.modelx.weather

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 后台通知：JobScheduler 每小时轮询
 * 1) 预警 diff：出现新预警 id → 通知
 * 2) 降雨提醒：nowcast 一小时内有雨 → 每小时最多一条
 */
class NotifyJobService : JobService() {

    companion object {
        const val JOB_ID = 4201
        private const val CHANNEL = "wx_alert"

        fun ensureChannel(ctx: Context) {
            if (android.os.Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "天气提醒", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "气象预警与降雨提醒"
                nm.createNotificationChannel(ch)
            }
        }

        fun schedule(ctx: Context) {
            ensureChannel(ctx)
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            // 本机 android.jar 无 getJobInfo/getJobId 可用作去重；用本地时间戳防频繁重建
            val sp = ctx.getSharedPreferences("notify", Context.MODE_PRIVATE)
            val last = sp.getLong("schedAt", 0L)
            val due = System.currentTimeMillis() - last > 30L * 60_000L
            val has = sp.getString("sched", "") == deviceIdOf(ctx)
            if (has && !due) return
            sp.edit().putLong("schedAt", System.currentTimeMillis())
                .putString("sched", deviceIdOf(ctx)).apply()
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, NotifyJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(60L * 60L * 1000L)
                .build()
            js.schedule(job)
        }

        private fun deviceIdOf(ctx: Context): String =
            ctx.getSharedPreferences("wxapp", Context.MODE_PRIVATE).getString("deviceId", "") ?: ""

        fun canNotify(ctx: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 33) return true
            return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

        private fun notify(ctx: Context, id: Int, title: String, text: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = if (android.os.Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
            }
            nm.notify(id, n)
        }
    }

    private val exec = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters?): Boolean {
        exec.execute {
            try {
                checkAlerts()
                checkRain()
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private fun checkAlerts() {
        val city = Api.currentCity()
        val cid = city.optInt("id", 1)
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Api.Resp? = null
        Api.get("/api/v1/alerts?cityId=$cid") { r -> result = r; latch.countDown() }
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val r = result ?: return
        if (r.stale || r.arr == null) return

        val sp = getSharedPreferences("notify", Context.MODE_PRIVATE)
        val seenKey = "seen_$cid"
        val initKey = "init_$cid"
        val seen = (sp.getString(seenKey, "") ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()

        val cur = ArrayList<String>()
        val fresh = ArrayList<JSONObject>()
        for (i in 0 until r.arr.length()) {
            val o = r.arr.getJSONObject(i)
            val id = o.optString("alertId")
            if (id.isEmpty()) continue
            cur.add(id)
            if (!seen.contains(id)) fresh.add(o)
        }
        // 首次记录不发通知，避免装完 App 轰炸
        if (sp.getString(initKey, null) == null) {
            sp.edit().putString(initKey, "1").putString(seenKey, cur.joinToString(",")).apply()
            return
        }
        if (canNotify(this)) {
            for ((i, o) in fresh.take(3).withIndex()) {
                notify(this, 100 + (cid * 7 + i),
                    "气象预警 · " + city.optString("name"), o.optString("title"))
            }
        }
        val merged = (fresh.map { it.optString("alertId") } + seen).take(60)
        sp.edit().putString(seenKey, merged.joinToString(",")).apply()
    }

    private fun checkRain() {
        val city = Api.currentCity()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Api.Resp? = null
        Api.get("/api/v1/weather/nowcast?lat=${city.optDouble("lat")}&lon=${city.optDouble("lon")}") { r ->
            result = r; latch.countDown()
        }
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        val r = result ?: return
        if (r.stale || r.obj == null) return

        val summary = r.obj.optString("summary")
        val hasRain = summary.contains("雨") || summary.contains("雪")
        val hourKey = SimpleDateFormat("yyyy-MM-dd-HH", Locale.CHINA).format(Date())
        val sp = getSharedPreferences("notify", Context.MODE_PRIVATE)
        if (hasRain && sp.getString("rainHour", "") != hourKey) {
            if (canNotify(this)) {
                notify(this, 200, "降雨提醒 · " + city.optString("name"), summary)
            }
            sp.edit().putString("rainHour", hourKey).apply()
        }
    }
}

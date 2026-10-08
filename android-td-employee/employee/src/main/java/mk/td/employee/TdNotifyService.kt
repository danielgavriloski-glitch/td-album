package mk.td.employee

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TdNotifyService : Service() {
    companion object {
        const val PREFS = "td_notify"
        const val KEY_API = "apiKey"
        const val KEY_DB = "databaseURL"
        const val KEY_STUDIO = "studioId"
        const val KEY_UID = "uid"
        const val KEY_REFRESH = "refreshToken"
        const val KEY_NAME = "name"
        private const val SYNC_CHANNEL = "td_employee_sync"
        private const val EVENT_CHANNEL = "td_employee_events"
        private const val SERVICE_ID = 7101
    }

    private lateinit var scheduler: ScheduledExecutorService
    private val polling = AtomicBoolean(false)
    @Volatile private var idToken: String? = null
    @Volatile private var tokenExpiresAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(SERVICE_ID, syncNotification())
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler.scheduleWithFixedDelay({ pollSafe() }, 4, 60, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::scheduler.isInitialized) scheduler.execute { pollSafe() }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::scheduler.isInitialized) scheduler.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(SYNC_CHANNEL, "TD синхронизација", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Ги проверува TD поканите и пораките во позадина"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(EVENT_CHANNEL, "TD Вработени известувања", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Покани, пораки, потврди и исплати"
                enableVibration(true)
            })
        }
    }

    private fun syncNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 7001, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, SYNC_CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("TD Вработени")
            .setContentText("Известувањата се активни")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build()
    }

    private fun eventNotification(title: String, body: String, stableId: Int) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(this, 7002, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, EVENT_CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        nm.notify(stableId, b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setAutoCancel(true).setContentIntent(pi).build())
    }

    private fun pollSafe() {
        if (!polling.compareAndSet(false, true)) return
        try { pollOnce() } catch (_: Throwable) { } finally { polling.set(false) }
    }

    private fun pollOnce() {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val api = p.getString(KEY_API, null) ?: return
        val db = p.getString(KEY_DB, null)?.trimEnd('/') ?: return
        val studio = p.getString(KEY_STUDIO, null) ?: return
        val uid = p.getString(KEY_UID, null) ?: return
        val refresh = p.getString(KEY_REFRESH, null) ?: return
        val token = getIdToken(api, refresh) ?: return

        val inviteUrl = db + "/tdStudios/" + encPath(studio) + "/invites/" + encPath(uid) + ".json?auth=" + url(token)
        val messageUrl = db + "/tdStudios/" + encPath(studio) + "/messages/" + encPath(uid) + ".json?auth=" + url(token)
        val invites = getJson(inviteUrl) ?: JSONObject()
        val messages = getJson(messageUrl) ?: JSONObject()

        val inviteIds = mutableSetOf<String>()
        val confirmedIds = mutableSetOf<String>()
        val declinedIds = mutableSetOf<String>()
        val paymentParts = mutableListOf<String>()
        val it = invites.keys()
        while (it.hasNext()) {
            val id = it.next()
            inviteIds.add(id)
            val inv = invites.optJSONObject(id) ?: continue
            when (inv.optJSONObject("decision")?.optString("status")) {
                "confirmed" -> confirmedIds.add(id)
                "declined" -> declinedIds.add(id)
            }
            val pays = inv.optJSONObject("payments")
            if (pays != null) {
                val pk = mutableListOf<String>()
                val pit = pays.keys()
                while (pit.hasNext()) {
                    val pid = pit.next()
                    val po = pays.optJSONObject(pid)
                    pk.add(pid + ":" + (po?.optDouble("amount", 0.0) ?: 0.0))
                }
                pk.sort()
                paymentParts.add(id + "=" + pk.joinToString(","))
            }
        }
        paymentParts.sort()

        val messageIds = mutableSetOf<String>()
        val mit = messages.keys()
        while (mit.hasNext()) messageIds.add(mit.next())

        compareSet(p, "invite_set", inviteIds) { count ->
            val suppress = p.getLong("suppress_invite_until", 0L) > System.currentTimeMillis()
            if (!suppress) eventNotification("TD Вработени", if (count == 1) "Имаш нова покана од TD Production." else "Имаш " + count + " нови покани од TD Production.", 7201)
        }
        compareSet(p, "message_set", messageIds) { count ->
            eventNotification("Нова информација од TD", if (count == 1) "Имаш нова порака од TD Production." else "Имаш " + count + " нови пораки од TD Production.", 7202)
        }
        compareSet(p, "confirmed_set", confirmedIds) {
            eventNotification("Ангажман потврден", "TD Production потврди ангажман. Отвори ја апликацијата за детали и договор.", 7203)
        }
        compareSet(p, "declined_set", declinedIds) {
            eventNotification("Промена на ангажман", "TD Production испрати промена на една од твоите покани.", 7204)
        }
        compareValue(p, "payments_sig", paymentParts.joinToString("|")) {
            eventNotification("Исплата ажурирана", "Има промена во евиденцијата за твоите исплати.", 7205)
        }
    }

    private fun compareSet(p: android.content.SharedPreferences, key: String, current: Set<String>, onAdded: (Int) -> Unit) {
        val now = current.toList().sorted().joinToString("|")
        if (!p.contains(key)) { p.edit().putString(key, now).apply(); return }
        val old = (p.getString(key, "") ?: "").split('|').filter { it.isNotBlank() }.toSet()
        val added = current - old
        if (added.isNotEmpty()) onAdded(added.size)
        if (now != p.getString(key, "")) p.edit().putString(key, now).apply()
    }

    private fun compareValue(p: android.content.SharedPreferences, key: String, current: String, onChanged: () -> Unit) {
        if (!p.contains(key)) { p.edit().putString(key, current).apply(); return }
        val old = p.getString(key, "") ?: ""
        if (old != current && old.isNotBlank()) onChanged()
        if (old != current) p.edit().putString(key, current).apply()
    }

    private fun getIdToken(apiKey: String, refreshToken: String): String? {
        val now = System.currentTimeMillis()
        val cached = idToken
        if (cached != null && tokenExpiresAt > now + 90000) return cached
        val body = "grant_type=refresh_token&refresh_token=" + url(refreshToken)
        val conn = URL("https://securetoken.googleapis.com/v1/token?key=" + url(apiKey)).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
        val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        conn.disconnect()
        val o = JSONObject(text)
        val token = o.optString("id_token")
        if (token.isBlank()) return null
        idToken = token
        tokenExpiresAt = now + (o.optLong("expires_in", 3600L) * 1000L)
        val newRefresh = o.optString("refresh_token")
        if (newRefresh.isNotBlank() && newRefresh != refreshToken) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_REFRESH, newRefresh).apply()
        }
        return token
    }

    private fun getJson(u: String): JSONObject? {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
        val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        conn.disconnect()
        if (text.isBlank() || text == "null") return JSONObject()
        return JSONObject(text)
    }

    private fun url(v: String): String = URLEncoder.encode(v, "UTF-8")
    private fun encPath(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}

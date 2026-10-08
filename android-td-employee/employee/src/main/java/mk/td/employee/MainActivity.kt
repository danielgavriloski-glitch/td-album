package mk.td.employee

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val nativeSync = object : Runnable {
        override fun run() {
            syncNativeNotificationConfig()
            handler.postDelayed(this, 12000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        @Suppress("DEPRECATION") web.settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION") web.settings.allowUniversalAccessFromFileURLs = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                handler.postDelayed({ syncNativeNotificationConfig() }, 1200)
            }
        }
        web.addJavascriptInterface(Bridge(), "TDNative")
        web.loadUrl("file:///android_asset/index.html")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(nativeSync)
        handler.postDelayed(nativeSync, 1800)
    }

    override fun onPause() {
        handler.removeCallbacks(nativeSync)
        super.onPause()
    }

    private fun syncNativeNotificationConfig() {
        if (!::web.isInitialized) return
        val js = """(function(){
          try{
            var c=JSON.parse(localStorage.getItem('tdEmpCloudV1')||'null');
            var a=JSON.parse(localStorage.getItem('tdEmpAuthV1')||'null');
            var p=JSON.parse(localStorage.getItem('tdEmpProfileV1')||'null');
            if(!c||!a||!p||!a.refreshToken)return null;
            return JSON.stringify({apiKey:c.apiKey,databaseURL:c.databaseURL,studioId:p.studioId,uid:p.uid,refreshToken:a.refreshToken,name:p.name||''});
          }catch(e){return null;}
        })()"""
        web.evaluateJavascript(js) { raw ->
            try {
                if (raw == null || raw == "null") return@evaluateJavascript
                val decoded = JSONObject("{\"v\":" + raw + "}").optString("v")
                if (decoded.isBlank()) return@evaluateJavascript
                configureNotifications(decoded)
            } catch (_: Throwable) { }
        }
    }

    private fun configureNotifications(json: String) {
        try {
            val o = JSONObject(json)
            val api = o.optString("apiKey")
            val db = o.optString("databaseURL")
            val studio = o.optString("studioId")
            val uid = o.optString("uid")
            val refresh = o.optString("refreshToken")
            if (api.isBlank() || db.isBlank() || studio.isBlank() || uid.isBlank() || refresh.isBlank()) return
            getSharedPreferences(TdNotifyService.PREFS, Context.MODE_PRIVATE).edit()
                .putString(TdNotifyService.KEY_API, api)
                .putString(TdNotifyService.KEY_DB, db)
                .putString(TdNotifyService.KEY_STUDIO, studio)
                .putString(TdNotifyService.KEY_UID, uid)
                .putString(TdNotifyService.KEY_REFRESH, refresh)
                .putString(TdNotifyService.KEY_NAME, o.optString("name"))
                .apply()
            val i = Intent(this, TdNotifyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Throwable) { }
    }

    inner class Bridge {
        @JavascriptInterface
        fun notify(title: String, body: String) = runOnUiThread {
            getSharedPreferences(TdNotifyService.PREFS, Context.MODE_PRIVATE).edit()
                .putLong("suppress_invite_until", System.currentTimeMillis() + 90000).apply()
            showNotification(title, body)
            syncNativeNotificationConfig()
        }

        @JavascriptInterface
        fun printContract() = runOnUiThread {
            val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            pm.print("TD Employee", web.createPrintDocumentAdapter("TD Employee"), null)
        }
    }

    private fun showNotification(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "td_employee"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(channelId, "TD Вработени", NotificationManager.IMPORTANCE_HIGH))
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else @Suppress("DEPRECATION") Notification.Builder(this)
        n.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(pi)
        nm.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), n.build())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}

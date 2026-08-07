package com.endfield.checkin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var txtStatus: TextView
    private lateinit var txtTokenInfo: TextView
    private lateinit var btnCheckNow: Button
    private lateinit var btnExtractToken: Button
    private lateinit var editHour: EditText
    private lateinit var editMinute: EditText
    private lateinit var btnSaveTime: Button

    private val handler = Handler(Looper.getMainLooper())
    private var tokenScanRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        txtStatus = findViewById(R.id.txtStatus)
        txtTokenInfo = findViewById(R.id.txtTokenInfo)
        btnCheckNow = findViewById(R.id.btnCheckNow)
        btnExtractToken = findViewById(R.id.btnExtractToken)
        editHour = findViewById(R.id.editHour)
        editMinute = findViewById(R.id.editMinute)
        btnSaveTime = findViewById(R.id.btnSaveTime)

        requestNotificationPermission()
        requestBatteryOptimizationExemption()

        setupWebView()
        loadSavedSettings()
        updateStatusDisplay()

        btnSaveTime.setOnClickListener {
            saveTimeSettingsAndReschedule()
        }

        btnCheckNow.setOnClickListener {
            triggerImmediateCheckIn()
        }

        btnExtractToken.setOnClickListener {
            extractAndSaveCookies()
            injectTokenScannerScript()
        }

        startPeriodicTokenScanner()
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)
        val h = prefs.getInt(CheckInWorker.KEY_TARGET_HOUR, 9)
        val m = prefs.getInt(CheckInWorker.KEY_TARGET_MINUTE, 0)

        editHour.setText(String.format("%02d", h))
        editMinute.setText(String.format("%02d", m))
    }

    private fun saveTimeSettingsAndReschedule() {
        val hStr = editHour.text.toString().trim()
        val mStr = editMinute.text.toString().trim()

        val h = hStr.toIntOrNull() ?: 9
        val m = mStr.toIntOrNull() ?: 0

        if (h !in 0..23 || m !in 0..59) {
            Toast.makeText(this, "시간은 0~23시, 분은 0~59분 사이로 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(CheckInWorker.KEY_TARGET_HOUR, h)
            .putInt(CheckInWorker.KEY_TARGET_MINUTE, m)
            .apply()

        CheckInWorker.schedulePeriodicWork(this, h, m)
        Toast.makeText(this, "매일 $h 시 $m 분으로 백그라운드 출석체크가 예약되었습니다!", Toast.LENGTH_LONG).show()
    }

    private fun setupWebView() {
        // 서드파티 쿠키 및 세션 저장소 완전 허용
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true

        // 안드로이드-자바스크립트 토큰 전달 인터페이스 등록
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                extractAndSaveCookies()
                injectTokenScannerScript()
            }

            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                if (request != null) {
                    val headers = request.requestHeaders
                    val cred = headers["cred"] ?: headers["Cred"] ?: headers["CRED"]
                    if (!cred.isNullOrEmpty() && cred.length > 5) {
                        saveToken(cred, "네트워크 헤더 (cred)")
                    }
                    val cookie = headers["Cookie"] ?: headers["cookie"]
                    if (!cookie.isNullOrEmpty() && cookie.contains("cred=")) {
                        parseAndSaveTokenStr(cookie)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.loadUrl("https://game.skport.com/endfield/sign-in")
    }

    private fun startPeriodicTokenScanner() {
        tokenScanRunnable = object : Runnable {
            override fun run() {
                extractAndSaveCookies()
                injectTokenScannerScript()
                handler.postDelayed(this, 3000)
            }
        }
        handler.postDelayed(tokenScanRunnable!!, 3000)
    }

    private fun injectTokenScannerScript() {
        val js = """
            (function() {
                try {
                    var ck = document.cookie || '';
                    if (window.AndroidBridge && ck) {
                        window.AndroidBridge.processCookie(ck);
                    }
                    for (var i = 0; i < localStorage.length; i++) {
                        var k = localStorage.key(i);
                        var v = localStorage.getItem(k);
                        if (k && v && window.AndroidBridge) {
                            window.AndroidBridge.processStorage(k, v);
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun processCookie(cookieStr: String) {
            parseAndSaveTokenStr(cookieStr)
        }

        @JavascriptInterface
        fun processStorage(key: String, value: String) {
            val lowerKey = key.lowercase()
            if (lowerKey == "cred" || lowerKey == "account_token" || lowerKey == "sk_port_cred") {
                if (value.isNotEmpty() && value.length > 5) {
                    saveToken(value, "localStorage ($key)")
                }
            }
        }
    }

    private fun parseAndSaveTokenStr(str: String) {
        if (str.isEmpty()) return

        val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)

        // cred=... 패턴 우선 정밀 추출
        if (str.contains("cred=")) {
            prefs.edit().putString(CheckInWorker.KEY_FULL_COOKIE, str).apply()
            for (p in str.split(";")) {
                val kv = p.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].equals("cred", ignoreCase = true)) {
                    val rawVal = kv[1].trim()
                    if (rawVal.isNotEmpty() && rawVal.length > 10) {
                        saveToken(rawVal, "Cookie (cred)")
                        return
                    }
                }
            }
        }

        // account_token 키 추출
        val parts = str.split(";")
        for (part in parts) {
            val kv = part.split("=", limit = 2)
            if (kv.size >= 2) {
                val k = kv[0].trim().lowercase()
                val v = kv[1].trim()
                if (k == "account_token" && v.length > 10) {
                    saveToken(v, "Cookie ($k)")
                    break
                }
            }
        }
    }

    private fun saveToken(token: String, source: String) {
        val clean = try {
            java.net.URLDecoder.decode(token, "UTF-8").trim()
        } catch (e: Exception) {
            token.trim()
        }

        if (clean.length < 10) return

        val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(CheckInWorker.KEY_CRED_TOKEN, null)

        if (current != clean) {
            prefs.edit().putString(CheckInWorker.KEY_CRED_TOKEN, clean).apply()
            runOnUiThread {
                txtTokenInfo.text = "토큰 저장됨: ${clean.take(12)}... ($source)"
                txtStatus.text = "상태: SKPORT 로그인 완료"
            }
        }
    }

    private fun extractAndSaveCookies(): String? {
        val cm = CookieManager.getInstance()
        cm.flush()

        val domains = listOf(
            "https://game.skport.com",
            "https://zonai.skport.com",
            "https://skport.com",
            "https://pas.skport.com"
        )

        for (domain in domains) {
            val cookies = cm.getCookie(domain) ?: continue
            parseAndSaveTokenStr(cookies)
        }

        val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(CheckInWorker.KEY_CRED_TOKEN, null)
        if (!saved.isNullOrEmpty()) {
            txtTokenInfo.text = "토큰 감지됨: ${saved.take(12)}..."
            txtStatus.text = "상태: SKPORT 로그인 완료"
        }
        return saved
    }

    private fun triggerImmediateCheckIn() {
        var token = extractAndSaveCookies()
        if (token.isNullOrEmpty()) {
            val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)
            token = prefs.getString(CheckInWorker.KEY_CRED_TOKEN, null)
        }

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "웹뷰에서 SKPORT 로그인을 완료해 주세요. (토큰 감지 대기 중)", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "출석체크 실행 중... (상단 알림을 확인하세요)", Toast.LENGTH_SHORT).show()

        val immediateWork = OneTimeWorkRequestBuilder<CheckInWorker>().build()
        WorkManager.getInstance(this).enqueue(immediateWork)

        webView.postDelayed({
            updateStatusDisplay()
        }, 3000)
    }

    private fun updateStatusDisplay() {
        val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)
        val lastTime = prefs.getString(CheckInWorker.KEY_LAST_CHECK_TIME, "이력 없음")
        val lastMsg = prefs.getString(CheckInWorker.KEY_LAST_CHECK_MSG, "출석 대기 중")
        val savedToken = prefs.getString(CheckInWorker.KEY_CRED_TOKEN, null)

        if (!savedToken.isNullOrEmpty()) {
            txtTokenInfo.text = "토큰 감지됨: ${savedToken.take(12)}..."
        }

        txtStatus.text = "최근 출석: $lastTime\n상태: $lastMsg"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tokenScanRunnable?.let { handler.removeCallbacks(it) }
    }
}




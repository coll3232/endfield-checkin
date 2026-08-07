package com.endfield.checkin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
    private lateinit var btnCheckNow: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        txtStatus = findViewById(R.id.txtStatus)
        btnCheckNow = findViewById(R.id.btnCheckNow)

        // 1. Android 13+ 알림 권한 요청
        requestNotificationPermission()

        // 2. 배터리 최적화 예외 요청 안내 (백그라운드 차단 방지)
        requestBatteryOptimizationExemption()

        setupWebView()
        updateStatusDisplay()

        // 백그라운드 주기 작업 스케줄링 등록 (24시간 주기)
        CheckInWorker.schedulePeriodicWork(this)

        btnCheckNow.setOnClickListener {
            triggerImmediateCheckIn()
        }
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
                } catch (e: Exception) {
                    // 기기별 설정 이동 처리 예외 방지
                }
            }
        }
    }

    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                extractAndSaveCookies()
            }
        }

        webView.loadUrl("https://game.skport.com/endfield/sign-in")
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

        var foundToken: String? = null

        for (domain in domains) {
            val cookies = cm.getCookie(domain) ?: continue
            val cookieMap = cookies.split(";").mapNotNull {
                val parts = it.split("=")
                if (parts.size >= 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()

            // 키 탐색 (대소문자 무관)
            for ((key, value) in cookieMap) {
                val lowerKey = key.lowercase()
                if (lowerKey == "cred" || lowerKey == "account_token" || lowerKey == "sk_token" || lowerKey == "token" || lowerKey == "user_token") {
                    if (value.isNotEmpty()) {
                        foundToken = value
                        break
                    }
                }
            }

            if (foundToken != null) break
        }

        if (foundToken != null) {
            val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(CheckInWorker.KEY_CRED_TOKEN, foundToken).apply()
            txtStatus.text = "상태: SKPORT 로그인 완료 (토큰 정상 감지됨)"
        }

        return foundToken
    }

    private fun triggerImmediateCheckIn() {
        val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(CheckInWorker.KEY_CRED_TOKEN, null)

        // 저장된 토큰이 없으면 실시간으로 웹뷰 쿠키 재추출 시도
        if (token.isNullOrEmpty()) {
            token = extractAndSaveCookies()
        }

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "웹뷰에서 SKPORT 로그인을 완료해 주세요.", Toast.LENGTH_SHORT).show()
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
        txtStatus.text = "최근 출석: $lastTime\n상태: $lastMsg"
    }
}



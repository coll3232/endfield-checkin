package com.endfield.checkin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

        // Android 13 (API 33) 이상 알림 권한 요청
        requestNotificationPermission()

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

    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                extractCookies(url)
            }
        }

        webView.loadUrl("https://game.skport.com/endfield/sign-in")
    }

    private fun extractCookies(url: String?) {
        val cookies = CookieManager.getInstance().getCookie("https://game.skport.com")
        if (!cookies.isNullOrEmpty()) {
            val cookieMap = cookies.split(";").mapNotNull {
                val parts = it.split("=")
                if (parts.size >= 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()

            val cred = cookieMap["cred"] ?: cookieMap["ACCOUNT_TOKEN"]
            if (!cred.isNullOrEmpty()) {
                val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(CheckInWorker.KEY_CRED_TOKEN, cred).apply()
                txtStatus.text = "상태: SKPORT 로그인 완료 (토큰 감지됨)"
            }
        }
    }

    private fun triggerImmediateCheckIn() {
        val prefs = getSharedPreferences(CheckInWorker.PREF_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(CheckInWorker.KEY_CRED_TOKEN, null)

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "웹뷰에서 SKPORT 로그인을 진행해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "출석체크 실행 중... (상단 알림을 확인하세요)", Toast.LENGTH_SHORT).show()
        
        // WorkManager 단발성 태스크 즉시 실행 (시스템 알림 자동 유발)
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


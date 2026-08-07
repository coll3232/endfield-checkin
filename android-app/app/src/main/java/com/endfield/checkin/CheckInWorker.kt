package com.endfield.checkin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Android WorkManager를 활용한 백그라운드 24시간 주기 일일 출석체크 태스크
 * 출석 성공, 이미 완료, 실패 시 안드로이드 시스템 알림 발송
 */
class CheckInWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "백그라운드 출석체크 태스크 시작")
        val prefs = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val credToken = prefs.getString(KEY_CRED_TOKEN, null)
        val fullCookie = prefs.getString(KEY_FULL_COOKIE, null)

        if (credToken.isNullOrEmpty() && fullCookie.isNullOrEmpty()) {
            Log.w(TAG, "저장된 cred 토큰이나 쿠키 정보가 없어 출석체크를 진행할 수 없습니다.")
            val msg = "SKPORT 웹뷰 로그인이 필요합니다."
            saveStatus("NEED_LOGIN", msg)
            sendSystemNotification(
                title = "엔드필드 출석체크 실패 ⚠️",
                message = msg,
                notificationId = NOTIF_ID_FAILED
            )
            return Result.failure()
        }

        val tokenToUse = credToken ?: ""
        val cookieToUse = fullCookie ?: ""

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastCheckDate = prefs.getString(KEY_LAST_CHECK_DATE, "")

        if (todayStr == lastCheckDate && prefs.getString(KEY_LAST_CHECK_STATUS, "") == "SUCCESS") {
            Log.d(TAG, "오늘 이미 출석 완료됨")
            sendSystemNotification(
                title = "엔드필드 출석체크 완료 ℹ️",
                message = "오늘($todayStr) 이미 출석체크가 완료되었습니다.",
                notificationId = NOTIF_ID_ALREADY
            )
            return Result.success()
        }

        return try {
            val (resultType, resultMessage) = performCheckInApi(tokenToUse, cookieToUse)
            when (resultType) {
                ResultType.SUCCESS -> {
                    val msg = "명일방주: 엔드필드 일일 출석체크가 성공했습니다!"
                    saveStatus("SUCCESS", msg)
                    // 3. 출석체크 성공 알림
                    sendSystemNotification(
                        title = "엔드필드 출석체크 성공 🎯",
                        message = msg,
                        notificationId = NOTIF_ID_SUCCESS
                    )
                    Result.success()
                }
                ResultType.ALREADY_CHECKED -> {
                    val msg = "오늘 이미 출석체크가 등록되었습니다."
                    saveStatus("SUCCESS", msg)
                    // 2. 출석체크 이미 된 경우 알림
                    sendSystemNotification(
                        title = "엔드필드 출석체크 완료 ℹ️",
                        message = msg,
                        notificationId = NOTIF_ID_ALREADY
                    )
                    Result.success()
                }
                ResultType.FAILED -> {
                    saveStatus("FAILED", resultMessage)
                    // 1. 출석체크 실패 알림
                    sendSystemNotification(
                        title = "엔드필드 출석체크 실패 ⚠️",
                        message = resultMessage,
                        notificationId = NOTIF_ID_FAILED
                    )
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            val errorMsg = "에러 발생: ${e.localizedMessage}"
            Log.e(TAG, "출석체크 중 오류 발생", e)
            saveStatus("ERROR", errorMsg)
            // 1. 출석체크 실패/에러 알림
            sendSystemNotification(
                title = "엔드필드 출석체크 오류 ❌",
                message = errorMsg,
                notificationId = NOTIF_ID_FAILED
            )
            Result.failure()
        }
    }

    private enum class ResultType {
        SUCCESS, ALREADY_CHECKED, FAILED
    }

    private fun performCheckInApi(credToken: String, fullCookie: String): Pair<ResultType, String> {
        val bindingUrl = URL("https://zonai.skport.com/web/v1/game/endfield/binding")
        var gameRoleHeader = ""
        val userAgentStr = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        var activeCred = try {
            java.net.URLDecoder.decode(credToken, "UTF-8").trim()
        } catch (e: Exception) {
            credToken.trim()
        }

        val prefs = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // ACCOUNT_TOKEN 또는 기존 토큰으로 Gryphline OAuth 교환 시도
        if (activeCred.isNotEmpty()) {
            val exchangedCred = exchangeAccountTokenToCred(activeCred, userAgentStr)
            if (!exchangedCred.isNullOrEmpty()) {
                activeCred = exchangedCred
                prefs.edit().putString(KEY_CRED_TOKEN, activeCred).apply()
                Log.d(TAG, "Gryphline OAuth 교환 성공: new cred=${activeCred.take(15)}...")
            }
        }

        if (activeCred.isEmpty()) {
            return Pair(ResultType.FAILED, "인증 토큰이 비어있습니다. SKPORT 로그인을 진행해 주세요.")
        }

        var cookieHeader = fullCookie.trim()
        if (!cookieHeader.contains("cred=")) {
            cookieHeader = if (cookieHeader.isEmpty()) "cred=$activeCred" else "$cookieHeader; cred=$activeCred"
        }
        if (!cookieHeader.contains("ACCOUNT_TOKEN=")) {
            cookieHeader = "$cookieHeader; ACCOUNT_TOKEN=$activeCred"
        }

        // 1. 바인딩 캐릭터 정보 조회 시도 (sk-game-role 획득)
        try {
            val bConn = bindingUrl.openConnection() as HttpURLConnection
            bConn.requestMethod = "GET"
            bConn.setRequestProperty("Accept", "application/json, text/plain, */*")
            bConn.setRequestProperty("cred", activeCred)
            bConn.setRequestProperty("Cookie", cookieHeader)
            bConn.setRequestProperty("platform", "3")
            bConn.setRequestProperty("v", "1.0.0")
            bConn.setRequestProperty("Origin", "https://game.skport.com")
            bConn.setRequestProperty("Referer", "https://game.skport.com/")
            bConn.setRequestProperty("User-Agent", userAgentStr)
            bConn.connectTimeout = 8000
            bConn.readTimeout = 8000

            if (bConn.responseCode == 200) {
                val bRes = bConn.inputStream.bufferedReader().use { it.readText() }
                val bJson = JSONObject(bRes)
                if (bJson.optInt("code") == 0 && bJson.has("data")) {
                    val list = bJson.getJSONObject("data").optJSONArray("list")
                    if (list != null && list.length() > 0) {
                        val roleObj = list.getJSONObject(0)
                        val roleId = roleObj.optString("roleId", "")
                        val serverId = roleObj.optString("serverId", "")
                        if (roleId.isNotEmpty()) {
                            gameRoleHeader = "3_${roleId}_${serverId}"
                            Log.d(TAG, "감지된 sk-game-role: $gameRoleHeader")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "바인딩 정보 조회 예외: ${e.message}")
        }

        // 2. 출석체크 API POST 호출
        val url = URL("https://zonai.skport.com/web/v1/game/endfield/attendance")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("cred", activeCred)
        conn.setRequestProperty("Cookie", cookieHeader)
        conn.setRequestProperty("platform", "3")
        conn.setRequestProperty("v", "1.0.0")
        conn.setRequestProperty("Origin", "https://game.skport.com")
        conn.setRequestProperty("Referer", "https://game.skport.com/")
        conn.setRequestProperty("User-Agent", userAgentStr)
        if (gameRoleHeader.isNotEmpty()) {
            conn.setRequestProperty("sk-game-role", gameRoleHeader)
        }

        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.doOutput = true

        val jsonInputString = "{}"
        conn.outputStream.use { os ->
            val input = jsonInputString.toByteArray(charset("utf-8"))
            os.write(input, 0, input.size)
        }

        val responseCode = conn.responseCode
        val stream = if (responseCode == 200) conn.inputStream else conn.errorStream
        val responseString = stream?.bufferedReader()?.use { it.readText() } ?: ""
        Log.d(TAG, "출석 API 응답 ($responseCode): $responseString")

        if (responseCode == 200) {
            val json = JSONObject(responseString)
            val code = json.optInt("code", -1)
            val msg = json.optString("message", "")

            return when {
                code == 0 -> Pair(ResultType.SUCCESS, "출석체크 완료")
                msg.contains("already") || code == 10001 -> Pair(ResultType.ALREADY_CHECKED, "이미 출석 완료됨")
                else -> Pair(ResultType.FAILED, "실패 (코드: $code, 메시지: $msg)")
            }
        } else if (responseCode == 401) {
            return Pair(ResultType.FAILED, "인증 실패 (HTTP 401): SKPORT 웹뷰에서 로그아웃 후 다시 로그인해 주세요.")
        }
        return Pair(ResultType.FAILED, "서버 응답 오류 (HTTP $responseCode): $responseString")
    }

    private fun exchangeAccountTokenToCred(accountToken: String, userAgentStr: String): String? {
        try {
            // 1. Gryphline OAuth Grant (ACCOUNT_TOKEN -> code)
            val grantUrl = URL("https://as.gryphline.com/user/oauth2/v2/grant")
            val gConn = grantUrl.openConnection() as HttpURLConnection
            gConn.requestMethod = "POST"
            gConn.setRequestProperty("Content-Type", "application/json")
            gConn.setRequestProperty("User-Agent", userAgentStr)
            gConn.connectTimeout = 8000
            gConn.readTimeout = 8000
            gConn.doOutput = true

            val grantBody = JSONObject().apply {
                put("token", accountToken)
                put("appCode", "skport_web")
                put("type", 0)
            }.toString()

            gConn.outputStream.use { it.write(grantBody.toByteArray(charset("utf-8"))) }

            if (gConn.responseCode == 200) {
                val gRes = gConn.inputStream.bufferedReader().use { it.readText() }
                val gJson = JSONObject(gRes)
                val authCode = gJson.optJSONObject("data")?.optString("code", null)

                if (!authCode.isNullOrEmpty()) {
                    // 2. SKPORT Cred 생성 (code -> cred)
                    val credUrl = URL("https://zonai.skport.com/web/v1/user/auth/generate_cred_by_code")
                    val cConn = credUrl.openConnection() as HttpURLConnection
                    cConn.requestMethod = "POST"
                    cConn.setRequestProperty("Content-Type", "application/json")
                    cConn.setRequestProperty("platform", "3")
                    cConn.setRequestProperty("v", "1.0.0")
                    cConn.setRequestProperty("User-Agent", userAgentStr)
                    cConn.connectTimeout = 8000
                    cConn.readTimeout = 8000
                    cConn.doOutput = true

                    val credBody = JSONObject().apply {
                        put("code", authCode)
                        put("kind", 1)
                    }.toString()

                    cConn.outputStream.use { it.write(credBody.toByteArray(charset("utf-8"))) }

                    if (cConn.responseCode == 200) {
                        val cRes = cConn.inputStream.bufferedReader().use { it.readText() }
                        val cJson = JSONObject(cRes)
                        val newCred = cJson.optJSONObject("data")?.optString("cred", null)
                        if (!newCred.isNullOrEmpty()) {
                            return newCred
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OAuth 토큰 교환 중 예외 발생: ${e.message}")
        }
        return null
    }

    private fun sendSystemNotification(title: String, message: String, notificationId: Int) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android 8.0 (API 26) 이상 채널 생성
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "엔드필드 출석체크 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "엔드필드 일일 출석체크 성공, 중복, 실패 결과를 알립니다."
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(notificationId, builder.build())
    }

    private fun saveStatus(status: String, message: String) {
        val todayStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val prefs = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_CHECK_DATE, SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
            .putString(KEY_LAST_CHECK_TIME, todayStr)
            .putString(KEY_LAST_CHECK_STATUS, status)
            .putString(KEY_LAST_CHECK_MSG, message)
            .apply()
    }

    companion object {
        const val TAG = "EndfieldCheckInWorker"
        const val PREF_NAME = "EndfieldPrefs"
        const val KEY_CRED_TOKEN = "cred_token"
        const val KEY_FULL_COOKIE = "full_cookie"
        const val KEY_LAST_CHECK_DATE = "last_check_date"
        const val KEY_LAST_CHECK_TIME = "last_check_time"
        const val KEY_LAST_CHECK_STATUS = "last_check_status"
        const val KEY_LAST_CHECK_MSG = "last_check_msg"

        private const val CHANNEL_ID = "endfield_checkin_channel"
        private const val NOTIF_ID_SUCCESS = 1001
        private const val NOTIF_ID_ALREADY = 1002
        private const val NOTIF_ID_FAILED = 1003

        const val KEY_TARGET_HOUR = "target_hour"
        const val KEY_TARGET_MINUTE = "target_minute"

        fun schedulePeriodicWork(context: Context, targetHour: Int = 9, targetMinute: Int = 0) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // 오늘/내일 설정한 시:분까지 남은 시간을 딜레이로 계산
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, targetHour)
                set(Calendar.MINUTE, targetMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (target.before(now)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            val initialDelayMs = target.timeInMillis - now.timeInMillis
            Log.d(TAG, "목표 시간 ${targetHour}시 ${targetMinute}분까지 초도 대기 시간: ${initialDelayMs / 1000}초")

            val workRequest = PeriodicWorkRequestBuilder<CheckInWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "EndfieldDailyCheckIn",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }
}


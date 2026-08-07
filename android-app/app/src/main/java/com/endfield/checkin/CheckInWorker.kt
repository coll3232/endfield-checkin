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

        if (credToken.isNullOrEmpty()) {
            Log.w(TAG, "저장된 cred 토큰이 없어 출석체크를 진행할 수 없습니다.")
            val msg = "SKPORT 웹뷰 로그인이 필요합니다."
            saveStatus("NEED_LOGIN", msg)
            // 1. 출석체크 실패 알림 (로그인 필요)
            sendSystemNotification(
                title = "엔드필드 출석체크 실패 ⚠️",
                message = msg,
                notificationId = NOTIF_ID_FAILED
            )
            return Result.failure()
        }

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastCheckDate = prefs.getString(KEY_LAST_CHECK_DATE, "")

        // 2. 이미 오늘 출석체크가 완료된 경우 알림 발송
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
            val (resultType, resultMessage) = performCheckInApi(credToken)
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
                    Result.retry()
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
            Result.retry()
        }
    }

    private enum class ResultType {
        SUCCESS, ALREADY_CHECKED, FAILED
    }

    private fun performCheckInApi(credToken: String): Pair<ResultType, String> {
        val url = URL("https://zonai.skport.com/web/v1/game/endfield/attendance")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("cred", credToken)
        conn.setRequestProperty("platform", "3")
        conn.setRequestProperty("v", "1.0.0")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.doOutput = true

        val jsonInputString = "{}"
        conn.outputStream.use { os ->
            val input = jsonInputString.toByteArray(charset("utf-8"))
            os.write(input, 0, input.size)
        }

        val responseCode = conn.responseCode
        if (responseCode == 200) {
            val responseString = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseString)
            val code = json.optInt("code", -1)
            val msg = json.optString("message", "")

            return when {
                code == 0 -> Pair(ResultType.SUCCESS, "출석체크 완료")
                msg.contains("already") || code == 10001 -> Pair(ResultType.ALREADY_CHECKED, "이미 출석 완료됨")
                else -> Pair(ResultType.FAILED, "실패 (코드: $code, 메시지: $msg)")
            }
        }
        return Pair(ResultType.FAILED, "서버 응답 오류 (HTTP $responseCode)")
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


package com.ssmediaos.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/** Aggregates multipart SMS callbacks and reports exactly one final result per queued job. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getLongExtra("job_id", 0L)
        val requestId = intent.getStringExtra("request_id") ?: return
        val expected = intent.getIntExtra("part_count", 1).coerceAtLeast(1)
        val result = resultCode
        val prefs = context.getSharedPreferences("sms_callback_state", Context.MODE_PRIVATE)
        val doneKey = "${requestId}_done"
        val countKey = "${requestId}_count"
        val failedKey = "${requestId}_failed"
        synchronized(lock) {
            if (prefs.getBoolean(doneKey, false)) return
            if (result != android.app.Activity.RESULT_OK) {
                prefs.edit().putBoolean(doneKey, true).apply()
                val error = when (result) {
                    android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "network_send_failed"
                    android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE -> "no_cellular_service"
                    android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
                    android.telephony.SmsManager.RESULT_ERROR_NULL_PDU -> "sms_encoding_error"
                    else -> "sms_send_failed"
                }
                ResultReporter.report(context.applicationContext, jobId, requestId, false, error, goAsync())
                return
            }
            val count = prefs.getInt(countKey, 0) + 1
            prefs.edit().putInt(countKey, count).apply()
            if (count >= expected) {
                prefs.edit().putBoolean(doneKey, true).apply()
                ResultReporter.report(context.applicationContext, jobId, requestId, true, "", goAsync())
                // Keep a small completion marker to ignore late duplicate broadcasts.
                prefs.edit().remove(countKey).remove(failedKey).apply()
            }
        }
    }

    companion object { private val lock = Any() }
}

object ResultReporter {
    fun report(context: Context, jobId: Long, requestId: String, success: Boolean, error: String, pending: BroadcastReceiver.PendingResult) {
        Thread({
            try {
                val config = SecureConfig.load(context) ?: return@Thread
                val payload = JSONObject().put("job_id", jobId).put("request_id", requestId)
                    .put("success", success).put("error_code", if (error.isBlank()) "device_send_failed" else error)
                    .put("provider_message_id", "android-${System.currentTimeMillis()}")
                var delivered = false
                repeat(3) { attempt ->
                    if (!delivered) {
                        try {
                            val response = ApiClient.post(config.getString("baseUrl"), "/api/v1/sms/device_result.php", config.getString("token"), payload)
                            delivered = response.status in 200..299
                        } catch (_: Exception) { }
                        if (!delivered && attempt < 2) Thread.sleep(1500L * (attempt + 1))
                    }
                }
            } catch (_: Exception) {
            } finally { pending?.finish() }
        }, "ssmediaos-result-callback").start()
    }

}

package com.ssmediaos.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class GatewayService : Service() {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private val channelId = "ssmediaos_gateway_status"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(51, notification("Starting connection…"))
        if (running.compareAndSet(false, true)) {
            worker = Thread({ loop() }, "ssmediaos-sms-poll").apply { name = "SSMediaOS-Gateway"; start() }
        }
        return START_STICKY
    }

    private fun loop() {
        while (running.get()) {
            try {
                val config = SecureConfig.load(this)
                if (config == null) { updateNotification("Configuration missing — open the app"); Thread.sleep(10_000); continue }
                val base = config.getString("baseUrl")
                val token = config.getString("token")
                val reply = ApiClient.post(base, "/api/v1/sms/device_poll.php", token, JSONObject())
                if (reply.status == 200) {
                    val data = reply.json()
                    if (!data.optBoolean("ok")) throw IllegalStateException("server_rejected")
                    val expectedCode = config.optString("deviceCode")
                    val actualCode = data.optJSONObject("device")?.optString("code", "") ?: ""
                    if (expectedCode.isNotBlank() && !expectedCode.equals(actualCode, ignoreCase = true)) {
                        updateNotification("Device code mismatch — check configuration")
                        Thread.sleep(20_000)
                        continue
                    }
                    updateNotification("Connected • waiting for SMS")
                    val job = data.optJSONObject("job")
                    if (job != null) {
                        processJob(job)
                        // Avoid rapidly claiming and submitting many SMS jobs at once.
                        Thread.sleep(8_000)
                    } else Thread.sleep(8_000)
                } else if (reply.status == 401) {
                    updateNotification("Authentication failed — check Device Token")
                    Thread.sleep(20_000)
                } else {
                    updateNotification("Server response ${reply.status} — retrying")
                    Thread.sleep(12_000)
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                updateNotification("Connection issue — retrying")
                try { Thread.sleep(12_000) } catch (_: InterruptedException) { break }
            }
        }
        running.set(false)
    }

    private fun processJob(job: JSONObject) {
        val id = job.optLong("id", 0L)
        val requestId = job.optString("request_id", "")
        val recipient = job.optString("to", "")
        val message = job.optString("message", "")
        if (id <= 0 || !requestId.matches(Regex("^[a-f0-9]{32}$")) || !recipient.matches(Regex("^\\+[1-9][0-9]{7,14}$")) || message.isBlank()) {
            reportResult(id, requestId, false, "invalid_job")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            reportResult(id, requestId, false, "sms_permission_denied")
            updateNotification("SMS permission missing — open the app")
            return
        }
        try {
            val smsManager = defaultSmsManager()
            val parts = smsManager.divideMessage(message)
            if (parts.isEmpty()) { reportResult(id, requestId, false, "empty_sms"); return }
            val sentIntents = ArrayList<PendingIntent>()
            parts.forEachIndexed { index, _ ->
                val callback = Intent(this, SmsSentReceiver::class.java).apply {
                    action = "com.ssmediaos.smsgateway.SMS_SENT"
                    putExtra("job_id", id)
                    putExtra("request_id", requestId)
                    putExtra("part_count", parts.size)
                    putExtra("part_index", index)
                }
                sentIntents.add(PendingIntent.getBroadcast(this, callbackCode(id, index), callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            if (parts.size == 1) smsManager.sendTextMessage(recipient, null, message, sentIntents[0], null)
            else smsManager.sendMultipartTextMessage(recipient, null, parts, sentIntents, null)
            updateNotification("Sending SMS to ${maskNumber(recipient)}")
        } catch (_: SecurityException) {
            reportResult(id, requestId, false, "sms_permission_denied")
        } catch (_: IllegalArgumentException) {
            reportResult(id, requestId, false, "invalid_sms_address")
        } catch (_: Exception) {
            reportResult(id, requestId, false, "sms_manager_error")
        }
    }

    @Suppress("DEPRECATION")
    private fun defaultSmsManager(): SmsManager = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    private fun callbackCode(id: Long, part: Int): Int = ((id xor (id ushr 32)).toInt() * 31) + part
    private fun maskNumber(number: String) = if (number.length > 6) number.take(4) + "•••" + number.takeLast(2) else "number"

    fun reportResult(jobId: Long, requestId: String, success: Boolean, error: String) {
        Thread({
            try {
                val config = SecureConfig.load(this) ?: return@Thread
                val payload = JSONObject().put("job_id", jobId).put("request_id", requestId)
                    .put("success", success).put("error_code", error)
                    .put("provider_message_id", "android-${System.currentTimeMillis()}")
                ApiClient.post(config.getString("baseUrl"), "/api/v1/sms/device_result.php", config.getString("token"), payload)
                updateNotification(if (success) "SMS submitted to mobile network" else "SMS failed: $error")
            } catch (_: Exception) { updateNotification("Could not report SMS result — check connection") }
        }, "ssmediaos-result").start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "SMS Gateway status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows whether the SS MediaOS SMS gateway is connected"
            })
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SS MediaOS SMS Gateway").setContentText(text).setContentIntent(open).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            val builder = Notification.Builder(this)
            builder.setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("SS MediaOS SMS Gateway")
                .setContentText(text).setContentIntent(open).setOngoing(true).build()
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(51, notification(text))
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

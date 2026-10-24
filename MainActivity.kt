package com.ssmediaos.smsgateway

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var baseUrl: EditText
    private lateinit var deviceCode: EditText
    private lateinit var token: EditText
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private var waitingForSettingsPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        SecureConfig.load(this)?.let {
            baseUrl.setText(it.optString("baseUrl"))
            deviceCode.setText(it.optString("deviceCode"))
            token.setText(it.optString("token"))
            status.text = "Configuration saved. SMS service is not necessarily running."
        }
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_ssmediaos_logo)
            contentDescription = "SS MediaOS SMS Gateway logo"
        }
        brand.addView(logo, LinearLayout.LayoutParams(dp(60), dp(60)))
        val title = TextView(this).apply {
            text = "SS MediaOS\nSMS Gateway"
            textSize = 22f
            setTextColor(0xFF123B70.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        brand.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(brand)
        content.addView(label("Connect this phone to your SS MediaOS server"))
        content.addView(label("Server URL (HTTPS)"))
        baseUrl = edit("https://your-domain.com", false)
        content.addView(baseUrl)
        content.addView(label("Device Code (example: SMS-DEVICE-01)"))
        deviceCode = edit("SMS-DEVICE-01", false)
        content.addView(deviceCode)
        content.addView(label("Device Token (copy from Integrations page)"))
        token = edit("Paste device token", true)
        content.addView(token)
        content.addView(label("Keep this phone powered on, connected to the internet, and with an active SIM. The app sends SMS using your mobile network."))
        val save = Button(this).apply { text = "Save Configuration"; setOnClickListener { saveConfig() } }
        content.addView(save, matchWrap())
        startButton = Button(this).apply { text = "Save & Start SMS Service"; setOnClickListener { saveAndStart() } }
        content.addView(startButton, matchWrap())
        content.addView(Button(this).apply {
            text = "Stop SMS Service"
            setOnClickListener { stopService(Intent(this@MainActivity, GatewayService::class.java)); status.text = "SMS service stopped" }
        }, matchWrap())
        content.addView(Button(this).apply {
            text = "Clear Saved Credentials"
            setOnClickListener { stopService(Intent(this@MainActivity, GatewayService::class.java)); SecureConfig.clear(this@MainActivity); baseUrl.text.clear(); deviceCode.text.clear(); token.text.clear(); status.text = "Saved credentials cleared" }
        }, matchWrap())
        status = TextView(this).apply {
            text = "Status: not started"
            textSize = 15f
            setTextColor(0xFF263238.toInt())
            setPadding(0, dp(16), 0, dp(8))
        }
        content.addView(status)
        content.addView(Button(this).apply {
            text = "Open App Settings / Permission Help"
            setOnClickListener { openAppSettings() }
        }, matchWrap())
        content.addView(label("SMS permission is required to send messages. Notification permission is optional on Android 13+. If Android blocks SMS access, open App info and check whether your device offers an Allow restricted settings option. This app cannot bypass Android or Google Play Protect restrictions."))
        content.addView(label("For reliable background operation, keep the phone powered on, maintain an active SIM and internet connection, and review battery/background restrictions in your phone settings."))
        content.addView(Button(this).apply {
            text = "Privacy Policy"
            setOnClickListener { showPolicy("Privacy Policy", PRIVACY_POLICY) }
        }, matchWrap())
        content.addView(Button(this).apply {
            text = "Terms & Conditions"
            setOnClickListener { showPolicy("Terms & Conditions", TERMS_AND_CONDITIONS) }
        }, matchWrap())
        content.addView(label("Read the Privacy Policy and Terms & Conditions before enabling SMS sending."))
        scroll.addView(content)
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(outer)
    }

    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(0xFF37474F.toInt()); setPadding(0, dp(10), 0, dp(5))
    }

    private fun edit(hintText: String, secret: Boolean) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setSingleLine(!secret)
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = getDrawable(android.R.drawable.edit_text)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showPolicy(title: String, body: String) {
        val textView = TextView(this).apply {
            text = body
            textSize = 15f
            setTextColor(0xFF263238.toInt())
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun saveConfig(): Boolean {
        val url = baseUrl.text.toString().trim().trimEnd('/')
        val code = deviceCode.text.toString().trim()
        val secret = token.text.toString().trim()
        val parsed = try { Uri.parse(url) } catch (_: Exception) { null }
        if (parsed == null || parsed.scheme != "https" || parsed.host.isNullOrBlank() || parsed.userInfo != null) {
            baseUrl.error = "Enter a valid HTTPS server URL"; return false
        }
        if (code.isBlank() || code.length > 80) { deviceCode.error = "Enter the registered device code"; return false }
        if (secret.length < 32 || secret.length > 200) { token.error = "Paste the full device token"; return false }
        SecureConfig.save(this, url, code, secret)
        status.text = "Configuration saved securely on this phone."
        Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun saveAndStart() {
        if (!saveConfig()) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            // First ask Android normally. If it is denied/restricted, the callback
            // opens this app's Settings page so the user can review available options.
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 700)
            return
        }
        startGateway()
    }

    private fun openAppSettings() {
        waitingForSettingsPermission = true
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            waitingForSettingsPermission = false
            Toast.makeText(this, "Open Settings → Apps → SS MediaOS SMS Gateway → Permissions", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // When the user returns from Settings after allowing SMS, start immediately.
        if (waitingForSettingsPermission) {
            waitingForSettingsPermission = false
            if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                startGateway()
            } else {
                status.text = "SMS permission is still off. In App info, open Permissions and allow SMS if Android offers it. Some Android versions may also show an Allow restricted settings option in the app-info menu."
            }
        }
    }

    private fun startGateway() {
        val config = SecureConfig.load(this)
        if (config == null) { status.text = "Configuration could not be read. Please save it again."; return }
        val intent = Intent(this, GatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        status.text = "SMS service started. Check the persistent notification for connection status."
        Toast.makeText(this, "SMS service started", Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 700) {
            if (permissions.isNotEmpty() && permissions.indices.all { grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }) startGateway()
            else {
                status.text = "SMS permission was denied or blocked. Opening App Settings; allow SMS if the option is available, then return here. The service will start automatically once permission is granted."
                Toast.makeText(this, "Please review SMS permission in App Settings.", Toast.LENGTH_LONG).show()
                openAppSettings()
            }
        }
    }

    companion object {
        private const val PRIVACY_POLICY = """SS MediaOS SMS Gateway — Privacy Policy

Last updated: 24 October 2026

1. What this app does
This app connects a phone to a server you configure and can send SMS jobs received from that server. Only use it with a server and recipients you are authorised to use.

2. Information processed
You enter a server URL, device code and device token. The app stores this configuration encrypted using Android Keystore-backed encryption. To perform its gateway function, the app communicates with your configured server and may process SMS recipient numbers, message text, delivery/send status, device identifiers you configure, and technical error/status logs. The exact data retained by the server depends on that server's implementation and settings.

3. How information is used
Information is used to authenticate this device to your configured server, receive SMS jobs, send requested messages, report send results, and troubleshoot connection or delivery problems. The app is configured to require HTTPS server URLs.

4. Sharing and retention
Data sent to the configured server is shared with the operator of that server. This app does not establish the server operator's retention or deletion practices; contact that operator for details. Your mobile carrier may process SMS metadata and message content under its own terms. Do not enter sensitive information unless you understand and authorise its processing.

5. Permissions
SMS permission is required for sending SMS. Android may display additional permission or background-operation controls. The app does not bypass Android permission or security controls. You can stop the service and clear saved credentials from the app.

6. Security
Keep your device token secret, use only a trusted HTTPS server, keep Android updated, and revoke/rotate a token if it may have been exposed. No software or network transmission can be guaranteed completely secure.

7. Children and lawful use
This app is intended for authorised device/server operators, not for covert messaging or unsolicited bulk messaging. Follow applicable laws, carrier rules and recipient-consent requirements.

8. Changes and contact
This policy may be updated when the app changes. For privacy requests about server-held data, contact the operator of the configured server. Replace this contact process with your organisation's verified support/privacy contact before public release."""

        private const val TERMS_AND_CONDITIONS = """SS MediaOS SMS Gateway — Terms & Conditions

Last updated: 24 October 2026

1. Acceptance
By using this app, you agree to these terms. If you do not agree, stop the service and uninstall the app.

2. Authorised use
You must own or be authorised to administer the phone, SIM, server account and device token. You are responsible for the server URL and for keeping credentials secure.

3. SMS responsibility
You are responsible for the content, recipient selection, consent, costs, carrier restrictions and legal compliance of all SMS jobs. Do not use the app for spam, harassment, fraud, impersonation, unlawful messaging or messages sent without required permission/consent.

4. Permissions and operation
The app requires Android SMS permission to send messages. Permission must be granted by the device user through Android's normal controls. Service availability depends on Android version, device manufacturer settings, battery restrictions, network, SIM, carrier and server availability. Continuous operation is not guaranteed.

5. Charges and third parties
Your carrier may charge for SMS or data. The app does not control carrier networks, Android security decisions, or third-party server uptime and policies.

6. No warranty
The app is provided on an as-available basis, without a guarantee of uninterrupted service, delivery, compatibility with every device, or fitness for a particular purpose, to the extent permitted by applicable law.

7. Suspension and security
Stop using the app if you suspect unauthorised access. Rotate/revoke exposed tokens and remove saved credentials. The operator may disable server access for misuse or security reasons.

8. Changes and contact
These terms may be updated. Add your verified business/legal name, jurisdiction and support contact before distributing this app publicly. Nothing here limits rights that cannot legally be excluded."""
    }

}

package com.ssmediaos.smsgateway

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores gateway credentials encrypted with an Android Keystore AES-GCM key. */
object SecureConfig {
    private const val PREFS = "gateway_config"
    private const val KEY_ALIAS = "ssmediaos_gateway_key_v1"
    private const val DATA_KEY = "encrypted_config"

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    fun save(ctx: android.content.Context, baseUrl: String, deviceCode: String, token: String) {
        val plain = JSONObject().put("baseUrl", baseUrl.trim().trimEnd('/'))
            .put("deviceCode", deviceCode.trim()).put("token", token.trim()).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain)
        val packed = cipher.iv + encrypted
        prefs(ctx).edit().putString(DATA_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun load(ctx: android.content.Context): JSONObject? {
        return try {
            val stored = prefs(ctx).getString(DATA_KEY, null) ?: return null
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            if (packed.size < 13) return null
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        } catch (_: Exception) { null }
    }

    fun clear(ctx: android.content.Context) { prefs(ctx).edit().remove(DATA_KEY).apply() }
}

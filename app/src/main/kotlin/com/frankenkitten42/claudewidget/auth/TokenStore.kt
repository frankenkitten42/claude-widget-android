package com.frankenkitten42.claudewidget.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure token storage backed by Android Keystore via EncryptedSharedPreferences.
 * Keys are hardware-backed where available and destroyed on app uninstall.
 * Tokens are never written to logs or plain storage.
 */
class TokenStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "claude_widget_tokens",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRES_AT, value).apply()

    val isLoggedIn: Boolean
        get() = accessToken != null

    /** True if token is expired or will expire within 5 minutes. */
    val isExpiredOrExpiring: Boolean
        get() = System.currentTimeMillis() >= (expiresAt - EXPIRY_BUFFER_MS)

    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    fun save(accessToken: String, refreshToken: String, expiresAt: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN  = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT    = "expires_at"
        private const val EXPIRY_BUFFER_MS  = 5 * 60 * 1000L // 5 minutes
    }
}

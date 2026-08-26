package com.circpros.micahpod.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "micahpod_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(username: String, password: String) {
        prefs.edit()
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    fun getUsername(): String? = prefs.getString("username", null)
    fun getPassword(): String? = prefs.getString("password", null)

    /** Raw PHPSESSID value only (not a full Set-Cookie string). */
    fun setPhpSessionId(sessionId: String?) {
        prefs.edit()
            .putString("phpsessid", sessionId)
            // Clear legacy Cookie.toString() blob that often failed to round-trip.
            .remove("session_cookie")
            .apply()
    }

    fun getPhpSessionId(): String? {
        prefs.getString("phpsessid", null)?.takeIf { it.isNotBlank() }?.let { return it }
        // Migrate older installs that stored Cookie.toString().
        val legacy = prefs.getString("session_cookie", null)?.trim().orEmpty()
        if (legacy.isBlank()) return null
        val value = when {
            legacy.startsWith("PHPSESSID=", ignoreCase = true) -> {
                legacy.removePrefix("PHPSESSID=").substringBefore(';').trim()
            }
            '=' !in legacy && ';' !in legacy -> legacy
            else -> legacy.substringAfter("PHPSESSID=", "").substringBefore(';').trim()
                .ifBlank { null }
        }
        if (!value.isNullOrBlank()) {
            setPhpSessionId(value)
            return value
        }
        return null
    }

    @Deprecated("Use setPhpSessionId")
    fun setSessionCookie(cookie: String?) {
        if (cookie.isNullOrBlank()) {
            setPhpSessionId(null)
            return
        }
        val value = cookie.substringAfter("PHPSESSID=", cookie)
            .substringBefore(';')
            .removePrefix("PHPSESSID=")
            .trim()
        setPhpSessionId(value.ifBlank { null })
    }

    @Deprecated("Use getPhpSessionId")
    fun getSessionCookie(): String? = getPhpSessionId()

    fun setCsrfToken(token: String?) {
        prefs.edit().putString("csrf_token", token).apply()
    }

    fun getCsrfToken(): String? = prefs.getString("csrf_token", null)

    fun hasCredentials(): Boolean =
        !getUsername().isNullOrBlank() && !getPassword().isNullOrBlank()

    fun isLoggedIn(): Boolean = !getPhpSessionId().isNullOrBlank() || hasCredentials()

    fun clearSession() {
        prefs.edit()
            .remove("session_cookie")
            .remove("phpsessid")
            .remove("csrf_token")
            .apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
    }
}

package com.circpros.micahpod.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthManager(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

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

    fun setUserRole(role: String?) {
        prefs.edit().putString("user_role", role).apply()
    }

    fun getUserRole(): String? = prefs.getString("user_role", null)

    fun isAdmin(): Boolean = getUserRole().equals("admin", ignoreCase = true)

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

    companion object {
        private const val TAG = "AuthManager"
        private const val PREFS_NAME = "micahpod_auth"
        private const val FALLBACK_PREFS_NAME = "micahpod_auth_fallback"

        private fun createPrefs(context: Context): SharedPreferences {
            return try {
                createEncryptedPrefs(context)
            } catch (first: Exception) {
                Log.e(TAG, "EncryptedSharedPreferences failed; clearing and retrying", first)
                clearEncryptedArtifacts(context)
                try {
                    createEncryptedPrefs(context)
                } catch (retry: Exception) {
                    Log.e(
                        TAG,
                        "EncryptedSharedPreferences retry failed; using plaintext fallback",
                        retry
                    )
                    context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun clearEncryptedArtifacts(context: Context) {
            try {
                context.deleteSharedPreferences(PREFS_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "deleteSharedPreferences failed", e)
            }
            // security-crypto may leave keyset files beside the prefs XML.
            val prefsDir = context.filesDir?.parentFile?.resolve("shared_prefs")
            if (prefsDir != null && prefsDir.isDirectory) {
                prefsDir.listFiles()
                    ?.filter { it.name.startsWith(PREFS_NAME) }
                    ?.forEach { file ->
                        try {
                            if (!file.delete()) {
                                Log.w(TAG, "Could not delete ${file.name}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed deleting ${file.name}", e)
                        }
                    }
            }
        }
    }
}

package com.circpros.micahpod.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class SessionCookieJar(private val authManager: AuthManager) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val session = cookies.firstOrNull { it.name.equals("PHPSESSID", ignoreCase = true) }
        if (session != null && session.value.isNotBlank()) {
            authManager.setPhpSessionId(session.value)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val sessionId = authManager.getPhpSessionId() ?: return emptyList()
        return listOf(
            Cookie.Builder()
                .name("PHPSESSID")
                .value(sessionId)
                .domain(url.host)
                .path("/")
                .httpOnly()
                .build()
        )
    }
}

package com.circpros.micahpod.network

import okhttp3.Interceptor
import okhttp3.Response

class CsrfInterceptor(private val authManager: AuthManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
        if (original.method == "POST") {
            builder.header("Content-Type", "application/json")
            authManager.getCsrfToken()?.let { token ->
                builder.header("X-CSRF-TOKEN", token)
            }
        }
        return chain.proceed(builder.build())
    }
}

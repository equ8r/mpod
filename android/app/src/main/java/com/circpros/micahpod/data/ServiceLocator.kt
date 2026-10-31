package com.circpros.micahpod.data

import android.content.Context
import com.circpros.micahpod.network.ApiService
import com.circpros.micahpod.network.AuthManager
import com.circpros.micahpod.network.CsrfInterceptor
import com.circpros.micahpod.network.SessionCookieJar
import com.circpros.micahpod.network.FlexibleBooleanAdapter
import com.circpros.micahpod.repository.MicahPodRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ServiceLocator {
    private lateinit var appContext: Context
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Boolean::class.java, FlexibleBooleanAdapter())
        .registerTypeAdapter(Boolean::class.javaObjectType, FlexibleBooleanAdapter())
        .create()

    lateinit var authManager: AuthManager
        private set
    lateinit var repository: MicahPodRepository
        private set

    /** Shared episode cache so the service can resolve URIs from episodes loaded by fragments */
    val episodeCache = mutableMapOf<Int, Episode>()

    lateinit var queueManager: com.circpros.micahpod.playback.QueueManager
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        authManager = AuthManager(appContext)

        val cookieJar = SessionCookieJar(authManager)
        val okHttpClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(CsrfInterceptor(authManager))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // Feed refresh / tag extract / recompute can run for a few minutes.
        val longOkHttpClient = okHttpClient.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(200, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://micahyoung.us/micahpod/api/")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(okHttpClient)
            .build()

        val longRetrofit = Retrofit.Builder()
            .baseUrl("https://micahyoung.us/micahpod/api/")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(longOkHttpClient)
            .build()

        val apiService = retrofit.create(ApiService::class.java)
        val longApiService = longRetrofit.create(ApiService::class.java)
        repository = MicahPodRepository(appContext, apiService, longApiService, authManager, gson)
        queueManager = com.circpros.micahpod.playback.QueueManager(gson)
    }
}

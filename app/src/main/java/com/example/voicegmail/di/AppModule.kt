package com.example.voicegmail.di

import com.example.voicegmail.BuildConfig
import com.example.voicegmail.bible.BibleApiService
import com.example.voicegmail.contacts.PeopleApiService
import com.example.voicegmail.gmail.GmailApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideGmailApiService(retrofit: Retrofit): GmailApiService {
        return retrofit.create(GmailApiService::class.java)
    }

    // ── People API (Google Contacts) ──────────────────────────────────────
    //
    // Lives on a different host (`people.googleapis.com`) so it needs its
    // own Retrofit instance, but reuses the shared OkHttpClient so logging
    // and any future interceptors stay consistent.

    @Provides
    @Singleton
    @Named("people")
    fun providePeopleApiRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://people.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun providePeopleApiService(@Named("people") retrofit: Retrofit): PeopleApiService {
        return retrofit.create(PeopleApiService::class.java)
    }

    // ── bible-api.com (no API key) ────────────────────────────────────────

    @Provides
    @Singleton
    @Named("bibleApi")
    fun provideBibleApiRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://bible-api.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideBibleApiService(@Named("bibleApi") retrofit: Retrofit): BibleApiService {
        return retrofit.create(BibleApiService::class.java)
    }
}

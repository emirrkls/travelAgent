package com.emirrkls.phokarta.core.network

import com.emirrkls.phokarta.BuildConfig
import com.emirrkls.phokarta.core.network.api.CollectionApi
import com.emirrkls.phokarta.core.network.api.PlaceApi
import com.emirrkls.phokarta.core.network.api.SavedPlaceApi
import com.emirrkls.phokarta.core.network.api.VisitApi
import com.emirrkls.phokarta.core.network.source.CollectionRemoteDataSource
import com.emirrkls.phokarta.core.network.source.PlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.RetrofitCollectionRemoteDataSource
import com.emirrkls.phokarta.core.network.source.RetrofitPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.RetrofitSavedPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.RetrofitVisitRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SavedPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.VisitRemoteDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    // BASIC only: never log request/response headers or bodies.
                    level = HttpLoggingInterceptor.Level.BASIC
                },
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(json: Json, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.PHOKARTA_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun providePlaceApi(retrofit: Retrofit): PlaceApi = retrofit.create(PlaceApi::class.java)

    @Provides
    @Singleton
    fun provideVisitApi(retrofit: Retrofit): VisitApi = retrofit.create(VisitApi::class.java)

    @Provides
    @Singleton
    fun provideSavedPlaceApi(retrofit: Retrofit): SavedPlaceApi =
        retrofit.create(SavedPlaceApi::class.java)

    @Provides
    @Singleton
    fun provideCollectionApi(retrofit: Retrofit): CollectionApi =
        retrofit.create(CollectionApi::class.java)

    @Provides
    @Singleton
    fun providePlaceRemoteDataSource(
        source: RetrofitPlaceRemoteDataSource,
    ): PlaceRemoteDataSource = source

    @Provides
    @Singleton
    fun provideVisitRemoteDataSource(
        source: RetrofitVisitRemoteDataSource,
    ): VisitRemoteDataSource = source

    @Provides
    @Singleton
    fun provideSavedPlaceRemoteDataSource(
        source: RetrofitSavedPlaceRemoteDataSource,
    ): SavedPlaceRemoteDataSource = source

    @Provides
    @Singleton
    fun provideCollectionRemoteDataSource(
        source: RetrofitCollectionRemoteDataSource,
    ): CollectionRemoteDataSource = source

    @Provides
    @Singleton
    fun provideDemoUserProvider(): DemoUserProvider = DefaultDemoUserProvider()
}

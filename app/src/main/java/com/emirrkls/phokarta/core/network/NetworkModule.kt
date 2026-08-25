package com.emirrkls.phokarta.core.network

import com.emirrkls.phokarta.BuildConfig
import com.emirrkls.phokarta.core.auth.AuthInterceptor
import com.emirrkls.phokarta.core.auth.TokenAuthenticator
import com.emirrkls.phokarta.core.network.api.AuthApi
import com.emirrkls.phokarta.core.network.api.CollectionApi
import com.emirrkls.phokarta.core.network.api.MeApi
import com.emirrkls.phokarta.core.network.api.PlaceApi
import com.emirrkls.phokarta.core.network.api.SavedPlaceApi
import com.emirrkls.phokarta.core.network.api.UserApi
import com.emirrkls.phokarta.core.network.api.VisitApi
import com.emirrkls.phokarta.core.network.api.MediaApi
import com.emirrkls.phokarta.core.network.source.MediaRemoteDataSource
import com.emirrkls.phokarta.core.network.source.RetrofitMediaRemoteDataSource
import com.emirrkls.phokarta.core.network.source.CollectionRemoteDataSource
import com.emirrkls.phokarta.core.network.source.PlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.RetrofitCollectionRemoteDataSource
import com.emirrkls.phokarta.core.network.source.RetrofitPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.RetrofitSavedPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.RetrofitSocialRemoteDataSource
import com.emirrkls.phokarta.core.network.source.RetrofitVisitRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SavedPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SocialRemoteDataSource
import com.emirrkls.phokarta.core.network.source.VisitRemoteDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.inject.Qualifier
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UploadHttpClient

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
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                },
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    @UploadHttpClient
    fun provideUploadOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()

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
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideMeApi(retrofit: Retrofit): MeApi = retrofit.create(MeApi::class.java)

    @Provides
    @Singleton
    fun providePlaceApi(retrofit: Retrofit): PlaceApi = retrofit.create(PlaceApi::class.java)

    @Provides
    @Singleton
    fun provideVisitApi(retrofit: Retrofit): VisitApi = retrofit.create(VisitApi::class.java)

    @Provides
    fun provideMediaApi(retrofit: Retrofit): MediaApi = retrofit.create(MediaApi::class.java)

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
    fun provideUserApi(retrofit: Retrofit): UserApi =
        retrofit.create(UserApi::class.java)

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
    fun provideMediaRemoteDataSource(
        source: RetrofitMediaRemoteDataSource,
    ): MediaRemoteDataSource = source

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
    fun provideSocialRemoteDataSource(
        source: RetrofitSocialRemoteDataSource,
    ): SocialRemoteDataSource = source
}

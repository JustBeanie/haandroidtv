package dev.haquickaccess.tv.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.haquickaccess.tv.data.HomeAssistantGateway
import dev.haquickaccess.tv.data.HomeAssistantRepository
import dev.haquickaccess.tv.data.HomeAssistantSession
import dev.haquickaccess.tv.data.HomeAssistantWebSocket
import dev.haquickaccess.tv.data.SettingsRepository
import dev.haquickaccess.tv.data.SettingsStore
import dev.haquickaccess.tv.platform.HomeChannelGateway
import dev.haquickaccess.tv.platform.HomeChannelPublisher
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds
    @Singleton
    abstract fun bindHomeAssistantGateway(implementation: HomeAssistantWebSocket): HomeAssistantGateway

    @Binds
    @Singleton
    abstract fun bindHomeAssistantSession(implementation: HomeAssistantRepository): HomeAssistantSession

    @Binds
    @Singleton
    abstract fun bindSettingsStore(implementation: SettingsRepository): SettingsStore

    @Binds
    @Singleton
    abstract fun bindHomeChannelGateway(implementation: HomeChannelPublisher): HomeChannelGateway
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(true).build()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    fun provideContext(@ApplicationContext context: Context): Context = context
}

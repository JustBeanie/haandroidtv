package dev.haquickaccess.tv.platform

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.haquickaccess.tv.R
import dev.haquickaccess.tv.data.AppSettings
import dev.haquickaccess.tv.domain.model.HaEntity
import dev.haquickaccess.tv.domain.model.ShortcutBehavior
import javax.inject.Inject
import javax.inject.Singleton

interface HomeChannelGateway {
    fun createOrUpdate(settings: AppSettings, entities: Map<String, HaEntity>): Long
    fun remove(channelId: Long)
}

@Singleton
class HomeChannelPublisher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : HomeChannelGateway {
    @SuppressLint("RestrictedApi") // PreviewProgram.Builder is the AndroidX TV Provider preview-channel API.
    override fun createOrUpdate(settings: AppSettings, entities: Map<String, HaEntity>): Long {
        val channelId = settings.channelId ?: createChannel()
        val resolver = context.contentResolver
        resolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
        settings.homeShortcuts.forEach { shortcut ->
            val entity = entities[shortcut.entityId] ?: return@forEach
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                .setTitle(entity.name)
                .setDescription(descriptionFor(entity, shortcut.behavior))
                .setPosterArtUri(tileArtUri())
                .setIntentUri(deepLinkUri(entity.entityId, shortcut.behavior))
                .setInternalProviderId(entity.entityId)
                .build()
            resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program.toContentValues())
        }
        return channelId
    }

    override fun remove(channelId: Long) {
        context.contentResolver.delete(TvContractCompat.buildChannelUri(channelId), null, null)
    }

    private fun createChannel(): Long {
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName("HA Quick Access")
            .setDescription("Your Home Assistant controls")
            .setAppLinkIntentUri("haquickaccess://control/dashboard".toUri())
            .build()
        val uri = requireNotNull(context.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()))
        val id = ContentUris.parseId(uri)
        ChannelLogoUtils.storeChannelLogo(context, id, tileArtUri())
        return id
    }

    private fun tileArtUri(): Uri = "android.resource://${context.packageName}/${R.drawable.shortcut_tile}".toUri()

    private fun deepLinkUri(entityId: String, behavior: ShortcutBehavior): Uri =
        Uri.Builder()
            .scheme("haquickaccess")
            .authority("control")
            .appendPath(entityId)
            .appendQueryParameter("behavior", behavior.name.lowercase())
            .build()

    private fun descriptionFor(entity: HaEntity, behavior: ShortcutBehavior): String = when (behavior) {
        ShortcutBehavior.TOGGLE -> "${entity.state} · Toggle"
        ShortcutBehavior.FOCUS -> "${entity.state} · Open control"
        ShortcutBehavior.DETAILS -> "${entity.state} · Open details"
    }
}

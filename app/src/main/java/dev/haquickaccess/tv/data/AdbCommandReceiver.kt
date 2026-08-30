package dev.haquickaccess.tv.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import dev.haquickaccess.tv.MainActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import dev.haquickaccess.tv.platform.HomeChannelGateway
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json

/**
 * Handles commands sent by Android's shell user through the protected ADB API.
 * Configuration is imported from a short-lived file so a token is not placed
 * in the shell command line or broadcast extras.
 */
@AndroidEntryPoint
class AdbCommandReceiver : BroadcastReceiver() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var homeChannelGateway: HomeChannelGateway
    @Inject lateinit var tileSnapshotStore: TileSnapshotStore
    @Inject lateinit var json: Json

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                execute(context, intent)
            } catch (exception: IllegalArgumentException) {
                CommandResult(AdbCommandContract.RESULT_INVALID_COMMAND, exception.message ?: "Invalid command")
            } catch (exception: Exception) {
                CommandResult(AdbCommandContract.RESULT_FAILED, exception.message ?: "ADB command failed")
            }
            pendingResult.setResultCode(result.code)
            pendingResult.setResultData(result.message.take(MAX_RESULT_LENGTH))
            pendingResult.finish()
        }
    }

    private suspend fun execute(context: Context, intent: Intent): CommandResult = when (intent.action) {
        AdbCommandContract.ACTION_CONFIGURE -> configure(context, intent)
        AdbCommandContract.ACTION_CLEAR_CONFIGURATION -> clearConfiguration()
        AdbCommandContract.ACTION_QUERY -> query()
        AdbCommandContract.ACTION_CONTROL -> control(context, intent)
        else -> CommandResult(AdbCommandContract.RESULT_INVALID_COMMAND, "Unsupported ADB action")
    }

    private suspend fun configure(context: Context, intent: Intent): CommandResult {
        val file = resolveConfigFile(context, intent.getStringExtra(AdbCommandContract.EXTRA_CONFIG_FILE))
        return try {
            val configuration = readConfiguration(file)
            if (configuration.homeChannelEnabled == true) {
                val requestedShortcuts = configuration.shortcuts
                    ?: settingsRepository.settings.first().homeShortcuts
                require(requestedShortcuts.isNotEmpty()) {
                    "Home channel requires at least one shortcut"
                }
            }
            settingsRepository.applyConfiguration(configuration)
            if (configuration.baseUrl != null || configuration.token != null) {
                tileSnapshotStore.clear()
            }
            launchConfiguredApp(
                context = context,
                refreshHomeChannel = configuration.homeChannelEnabled == true || configuration.shortcuts != null,
            )
            CommandResult(AdbCommandContract.RESULT_OK, "Configuration applied")
        } finally {
            if (file.exists() && !file.delete()) {
                // Do not expose the file path; the path may be copied into logs.
                throw IllegalStateException("Configuration import file could not be removed")
            }
        }
    }

    private suspend fun clearConfiguration(): CommandResult {
        settingsRepository.settings.first().channelId?.let(homeChannelGateway::remove)
        tileSnapshotStore.clear()
        settingsRepository.clearConnection()
        return CommandResult(AdbCommandContract.RESULT_OK, "Configuration cleared")
    }

    private suspend fun query(): CommandResult {
        val settings = settingsRepository.settings.first()
        val response = AdbQueryResponse(
            baseUrl = settings.baseUrl,
            tokenConfigured = settings.tokenEnvelope != null,
            tiles = settings.tiles.sortedBy { it.position }.map { it.entityId },
            shortcuts = settings.homeShortcuts.map {
                AdbShortcut(it.entityId, it.behavior.name.lowercase(Locale.ROOT))
            },
            homeChannelEnabled = settings.homeChannelEnabled,
        )
        return CommandResult(AdbCommandContract.RESULT_OK, json.encodeToString(response))
    }

    private fun control(context: Context, intent: Intent): CommandResult {
        val entityId = requireNotNull(intent.getStringExtra(AdbCommandContract.EXTRA_ENTITY_ID)) {
            "entity_id is required"
        }
        val behavior = intent.getStringExtra(AdbCommandContract.EXTRA_BEHAVIOR)
            ?.lowercase(Locale.ROOT)
            ?: "details"
        val uri = Uri.Builder()
            .scheme("haquickaccess")
            .authority("control")
            .appendPath(entityId)
            .appendQueryParameter("behavior", behavior)
            .build()
        require(LaunchIntentValidator.parse(uri.toString()) != null) { "Invalid control request" }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            setClass(context, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        return CommandResult(AdbCommandContract.RESULT_OK, "Control dispatched")
    }

    private fun launchConfiguredApp(context: Context, refreshHomeChannel: Boolean) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_REFRESH_HOME_CHANNEL, refreshHomeChannel)
        })
    }

    private fun readConfiguration(file: File): AdbConfiguration {
        require(file.isFile) { "Configuration import file was not found" }
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_CONFIG_BYTES) { "Configuration file is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.isNotEmpty()) { "Configuration file is empty" }
        return json.decodeFromString<AdbConfiguration>(String(bytes, Charsets.UTF_8)).also {
            AdbConfigurationValidator.validate(it)
        }
    }

    private fun resolveConfigFile(context: Context, rawPath: String?): File {
        require(!rawPath.isNullOrBlank()) { "config_file is required" }
        val requested = runCatching { File(rawPath).canonicalFile }
            .getOrElse { throw IllegalArgumentException("Invalid configuration file path") }
        require(requested.name == AdbCommandContract.CONFIG_FILE_NAME) {
            "Configuration file has an invalid name"
        }
        val externalRoot = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.getExternalFilesDir(null)
        val allowedRoot = externalRoot?.canonicalFile
        require(allowedRoot != null && isWithin(requested, allowedRoot)) {
            "Configuration file must be in app-specific external storage"
        }
        return requested
    }

    private fun isWithin(file: File, directory: File): Boolean =
        file.path.startsWith(directory.path + File.separator)

    private data class CommandResult(val code: Int, val message: String)

    private companion object {
        const val MAX_CONFIG_BYTES = 64 * 1024
        const val MAX_RESULT_LENGTH = 64 * 1024
    }
}

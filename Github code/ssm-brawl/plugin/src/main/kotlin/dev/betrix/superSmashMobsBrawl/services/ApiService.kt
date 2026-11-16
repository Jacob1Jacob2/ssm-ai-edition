package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.IManageable
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.models.ServerStatus
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * SSM AI Edition: offline replacement for the original Convex-backed API.
 *
 * The upstream plugin required a hosted backend (CONVEX_SITE_URL) and refused to start without it.
 * This version keeps the same method signatures so the rest of the plugin is unchanged, but stores
 * each player's selected kit in plugins/<plugin>/selected-kits.properties and treats every
 * analytics/telemetry call as a no-op.
 */
object ApiService : IManageable, KoinComponent {
    private const val DEFAULT_KIT = "skeleton"

    private val plugin: SuperSmashMobsBrawl by inject()

    private val selectedKits = ConcurrentHashMap<String, String>()

    private val storeFile: File
        get() = File(plugin.dataFolder, "selected-kits.properties")

    override fun setup() {
        selectedKits.clear()
        val file = storeFile
        if (file.exists()) {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            props.forEach { (k, v) -> selectedKits[k.toString()] = v.toString() }
        }
        plugin.logger.info("Offline data store loaded (${selectedKits.size} saved kit selections).")
    }

    override fun teardown() {
        save()
    }

    @Synchronized
    private fun save() {
        val file = storeFile
        file.parentFile?.mkdirs()
        val props = Properties()
        selectedKits.forEach { (k, v) -> props.setProperty(k, v) }
        file.outputStream().use { props.store(it, "SSM AI Edition - selected kits by player UUID") }
    }

    suspend fun playersEnsureDocumentAsync(player: Player) {}

    suspend fun playersSetSelectedKitAsync(player: Player, kitId: String) {
        selectedKits[player.uniqueId.toString()] = kitId
        save()
    }

    /** Pre-sets a kit for a player who hasn't joined yet (used for AI bots). */
    fun setSelectedKit(uuid: java.util.UUID, kitId: String) {
        selectedKits[uuid.toString()] = kitId
    }

    suspend fun playersGetSelectedKitAsync(player: Player): String =
        selectedKits[player.uniqueId.toString()] ?: DEFAULT_KIT

    suspend fun sendPlayerJoinEventAsync(player: Player) {}

    suspend fun sendPlayerLeaveEventAsync(player: Player) {}

    suspend fun serverStatusPostAsync(status: ServerStatus) {}

    suspend fun serverSyncOnlinePlayers(onlinePlayerUuids: List<String>) {}
}

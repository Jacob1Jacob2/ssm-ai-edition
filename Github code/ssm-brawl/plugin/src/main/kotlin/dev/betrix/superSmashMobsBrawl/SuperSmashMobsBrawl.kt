package dev.betrix.superSmashMobsBrawl

import com.github.shynixn.mccoroutine.bukkit.SuspendingJavaPlugin
import dev.betrix.superSmashMobsBrawl.commands.*
import dev.betrix.superSmashMobsBrawl.commands.resolvers.*
import dev.betrix.superSmashMobsBrawl.extensions.hasPassive
import dev.betrix.superSmashMobsBrawl.models.brawlData.KitDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.MinigameDef
import dev.betrix.superSmashMobsBrawl.services.*
import dev.rollczi.litecommands.LiteCommands
import dev.rollczi.litecommands.bukkit.LiteBukkitFactory
import gg.flyte.twilight.Twilight
import gg.flyte.twilight.event.event
import gg.flyte.twilight.extension.feed
import gg.flyte.twilight.twilight
import me.libraryaddict.disguise.DisguiseConfig
import java.util.logging.Logger
import org.bukkit.GameMode
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.inventory.InventoryInteractEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class SuperSmashMobsBrawl : SuspendingJavaPlugin(), KoinComponent {
    private val lang: LangService by inject()
    lateinit var liteCommands: LiteCommands<CommandSender>
    lateinit var twilight: Twilight
    lateinit var axiomLoggerHandler: AxiomLoggerHandler

    override fun onLoad() {
        // Runs before worlds load: clear temp worlds left over from a crash so they don't pile up.
        WorldService.deleteStaleWorlds(server.worldContainer.toPath()) { logger.info(it) }
    }

    override suspend fun onEnableAsync() {
        twilight = twilight(this)

        // Keep the player's name on the mob's own metadata. Armor-stand labels lag
        // behind moving disguises and leave client nametag tools seeing only the mob type.
        DisguiseConfig.setOverrideCustomNames(false)

        axiomLoggerHandler = AxiomLoggerHandler(this)
        Logger.getLogger("").addHandler(axiomLoggerHandler)

        startKoin {
            modules(
                module {
                    single { this@SuperSmashMobsBrawl }
                    single<JavaPlugin> { this@SuperSmashMobsBrawl }
                    single { this@SuperSmashMobsBrawl.logger }
                    single { this@SuperSmashMobsBrawl.axiomLoggerHandler }
                    single { ApiService }
                    single { PlayerDocumentService }
                    single { QueueService }
                    single(createdAtStart = true) { DataService() }
                    single { MinigameService() }
                    single { KitService }
                    single(createdAtStart = true) { LangService(this@SuperSmashMobsBrawl) }
                    single { WorldService }
                    single { HubService }
                    single { ComboTrackerService }
                    single(createdAtStart = true) { HotbarService }
                }
            )
        }

        StatisticsBroadcaster()

        ApiService.setup()
        ClassicChatService.setup()
        BotConversationService.setup()
        WorldService.setup()
        MapVoteService.setup()
        HubService.initialize(this)
        HubProtectionService.registerEvents()
        LobbyBuildService.setup()
        DebugService.initialize(this)
        KitService.setup()
        PlayerDocumentService.setup()
        ComboTrackerService.setup()
        HotbarService.setup()
        runCatching { GameSetupService.setup() }.onFailure { logger.warning("Game setups not loaded: ${it.message}") }
        runCatching { dev.betrix.superSmashMobsBrawl.services.KitStatsService.setup() }.onFailure { logger.warning("Kit stats not loaded: ${it.message}") }
        runCatching { dev.betrix.superSmashMobsBrawl.services.AutoL.setup(dataFolder) }.onFailure { logger.warning("Auto L choices not loaded: ${it.message}") }
        dev.betrix.superSmashMobsBrawl.services.LobbyEdits.setup(dataFolder)
        ScoreboardService.setup()
        CombatLogService.setup()
        dev.betrix.superSmashMobsBrawl.smash.SmashService.setup()
        dev.betrix.superSmashMobsBrawl.services.EnergyHud.setup(this)
        dev.betrix.superSmashMobsBrawl.items.ItemService.setup()
        dev.betrix.superSmashMobsBrawl.services.SandboxTools.setup()
        logger.info("Items update 2026-11-11: ${dev.betrix.superSmashMobsBrawl.items.ArenaItem.entries.size} arena items; Most Wins / Most Kills leaderboard.")
        runCatching { ResourcePackService.setup() }
            .onFailure { logger.severe("Resource pack service failed to start: ${it.message}") }
        MapScanService.setup()
        runCatching { dev.betrix.superSmashMobsBrawl.bots.BotManager.setup() }
            .onFailure { logger.severe("AI bots disabled: ${it.message}") }

        server.motd(lang.t("motd"))

        liteCommands =
            LiteBukkitFactory.builder(this)
                .argument(MinigameDef::class.java, MinigameDefinitionArgument())
                .argument(KitDef::class.java, KitDefArgument())
                .argumentSuggestion(String::class.java, dev.rollczi.litecommands.argument.ArgumentKey.of("npc-direction"),
                    dev.rollczi.litecommands.suggestion.SuggestionResult.of(dev.betrix.superSmashMobsBrawl.services.LobbyBuildService.directions.keys))
                .argumentSuggestion(String::class.java, dev.rollczi.litecommands.argument.ArgumentKey.of("arena-item"),
                    dev.rollczi.litecommands.suggestion.SuggestionResult.of(dev.betrix.superSmashMobsBrawl.items.ArenaItem.entries.map { it.id }))
                .argumentSuggestion(String::class.java, dev.rollczi.litecommands.argument.ArgumentKey.of("crystal-color"),
                    dev.rollczi.litecommands.suggestion.SuggestionResult.of("blue", "green", "yellow", "red"))
                .argumentSuggestion(String::class.java, dev.rollczi.litecommands.argument.ArgumentKey.of("crystal-mode"),
                    dev.rollczi.litecommands.suggestion.SuggestionResult.of("item"))
                .argumentSuggestion(String::class.java, dev.rollczi.litecommands.argument.ArgumentKey.of("auto-what"),
                    dev.rollczi.litecommands.suggestion.SuggestionResult.of("L"))
                .commands(dev.betrix.superSmashMobsBrawl.commands.AutoCommand())
                .commands(QueueCommand())
                .commands(KitCommand())
                .commands(LeaveCommand())
                .commands(dev.betrix.superSmashMobsBrawl.commands.LobbyCommand())
                .commands(DebugCommand())
                .commands(ColorsCommand())
                .commands(ScanMapsCommand())
                .commands(BotsCommand())
                .commands(RemoveBotCommand())
                .commands(dev.betrix.superSmashMobsBrawl.commands.BotSaySomethingCommand())
                .commands(BotPersonalityCommand())
                .commands(dev.betrix.superSmashMobsBrawl.commands.SmashCommand())
                .commands(ChatgptBuildCommand())
                .commands(dev.betrix.superSmashMobsBrawl.commands.ReplayCommand())
                .commands(SpawnCrystalCommand())
                .build()

        event<PlayerDropItemEvent> {
            if (player.gameMode == GameMode.CREATIVE) {
                return@event
            }

            isCancelled = true
        }

        event<FoodLevelChangeEvent> {
            if (entity is Player) {
                val player = entity as Player
                if (!player.hasPassive("hunger")) {
                    player.feed()
                    isCancelled = true
                }
            }
        }

        event<EntityPickupItemEvent> { isCancelled = true }

        // Temporary playtest diagnostics: every click a real player makes while in a game.
        event<org.bukkit.event.player.PlayerInteractEvent>(priority = org.bukkit.event.EventPriority.LOWEST) {
            if (dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player)) return@event
            val held = item ?: player.inventory.itemInMainHand
            val abilityId =
                held.takeIf { it.hasItemMeta() }?.itemMeta?.persistentDataContainer?.get(
                    org.bukkit.NamespacedKey(this@SuperSmashMobsBrawl, "abilityId"),
                    org.bukkit.persistence.PersistentDataType.STRING,
                )
            logger.info(
                "[click] ${player.name} $action hand=$hand item=${held.type} abilityId=$abilityId " +
                    "slot=${player.inventory.heldItemSlot} mode=${player.gameMode} cancelled=$isCancelled"
            )
        }

        // Clear skies everywhere. (In 26.x weather lives in the main world and shows in every map.)
        fun clearWeather(w: org.bukkit.World) {
            w.setStorm(false)
            w.isThundering = false
            w.clearWeatherDuration = 1_000_000
            @Suppress("DEPRECATION") w.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false)
            // The 1.21.6+ locator bar (player dots) takes over the XP bar, which SSM uses for info.
            w.setGameRule(org.bukkit.GameRules.LOCATOR_BAR, false)
        }
        server.worlds.forEach(::clearWeather)
        event<org.bukkit.event.world.WorldLoadEvent> { clearWeather(world) }
        event<org.bukkit.event.weather.WeatherChangeEvent> { if (toWeatherState() && !dev.betrix.superSmashMobsBrawl.smash.SmashService.permitsStorm(world)) isCancelled = true }
        event<org.bukkit.event.weather.ThunderChangeEvent> { if (toThunderState() && !dev.betrix.superSmashMobsBrawl.smash.SmashService.permitsStorm(world)) isCancelled = true }

        // No fire spreading or blocks burning away (ability fire on players still works).
        event<org.bukkit.event.block.BlockBurnEvent> { isCancelled = true }
        event<org.bukkit.event.block.BlockSpreadEvent> {
            if (source.type == org.bukkit.Material.FIRE || source.type == org.bukkit.Material.SOUL_FIRE) isCancelled = true
        }
        event<org.bukkit.event.block.BlockIgniteEvent> {
            if (cause == org.bukkit.event.block.BlockIgniteEvent.IgniteCause.SPREAD ||
                cause == org.bukkit.event.block.BlockIgniteEvent.IgniteCause.LAVA
            ) isCancelled = true
        }

        // SSM has no fall damage anywhere (kits cancel it in games, but not e.g. after a game).
        event<org.bukkit.event.entity.EntityDamageEvent> {
            if (entity is Player && cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
                isCancelled = true
            }
        }

        event<InventoryMoveItemEvent> { isCancelled = true }

        event<PotionSplashEvent> {
            entity.remove()
            isCancelled = true
        }

        event<InventoryInteractEvent> {
            if ((whoClicked as? Player)?.let { LobbyBuildService.isBuilding(it) } != true) isCancelled = true
        }

        logger.info("SSMB started! Classic roster build 2026-11-09: 21 kits, video stats and kit colors.")
    }

    override suspend fun onDisableAsync() {
        logger.info("SSMB shutting down")
        BotConversationService.shutdown()
        dev.betrix.superSmashMobsBrawl.services.BotSkinService.teardown()
        dev.betrix.superSmashMobsBrawl.items.ItemService.teardown()
        dev.betrix.superSmashMobsBrawl.items.CarSteering.stop()
        dev.betrix.superSmashMobsBrawl.smash.SmashService.teardown()

        // Remove bots first so they don't get saved/kicked mid-game.
        runCatching { dev.betrix.superSmashMobsBrawl.services.LobbyBotService.teardown() }
        runCatching { dev.betrix.superSmashMobsBrawl.bots.BotManager.despawnAll() }
            .onFailure { logger.warning("Failed to remove bots: ${it.message}") }
        runCatching { ResourcePackService.teardown() }

        HubService.teardown()
        WorldService.teardown()
        DebugService.teardown()
        KitService.teardown()
        PlayerDocumentService.teardown()
        ComboTrackerService.teardown()
        HotbarService.teardown()

        stopKoin()
    }
}

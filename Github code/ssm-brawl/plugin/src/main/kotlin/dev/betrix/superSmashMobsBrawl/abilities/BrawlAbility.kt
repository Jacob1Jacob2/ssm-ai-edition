package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.Manageable
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.extensions.event
import dev.betrix.superSmashMobsBrawl.extensions.getAs
import dev.betrix.superSmashMobsBrawl.extensions.isSword
import dev.betrix.superSmashMobsBrawl.interfaces.MetadataAccessor
import dev.betrix.superSmashMobsBrawl.models.brawlData.AbilityUsage
import dev.betrix.superSmashMobsBrawl.services.DataService
import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.services.LangService
import dev.betrix.superSmashMobsBrawl.services.MinigameService
import gg.flyte.twilight.scheduler.repeatingTask
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.BlocksAttacks
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

abstract class BrawlAbility(val id: String, val player: Player) : Manageable(), KoinComponent {
    protected val plugin: SuperSmashMobsBrawl by inject()
    private val dataService: DataService by inject()
    protected val minigameService: MinigameService by inject()
    protected val kitService: KitService by inject()
    protected val lang: LangService by inject()

    protected val abilityData by lazy {
        dataService.getAbility(id)
            ?: throw RuntimeException("No ability found in DataService with id $id")
    }

    protected val minigameData
        get() = minigameService.getMinigameForPlayer(player)?.minigameDef

    protected val kitData
        get() = kitService.getKitForPlayer(player)?.let { dataService.getKit(it.id) }

    private val abilityKey = NamespacedKey(plugin, "abilityId")

    protected var lastUsed: Long = 0

    private var lastCheckForCanActivate = true
    val usesEnergy: Boolean get() = id in setOf("inferno", "blizzard", "bouncy_bacon", "baby_bacon_bomb", "phantom_flight")
    var readyNoticeUntil: Long = 0
        private set

    protected lateinit var hotbarItemStack: ItemStack
        private set

    // A scheduler/projectile callback keeps its original cast even after the tool expires.
    private var callbackBoost: Double? = null
    protected val itemBoost: Double get() = callbackBoost ?: dev.betrix.superSmashMobsBrawl.items.ItemService.castScale(player, id)
    protected fun upgradedValue(key: String, fallback: Double) = (metadata.double(key) ?: fallback) * itemBoost
    protected fun upgradedCount(base: Int) = kotlin.math.round(base * itemBoost).toInt().coerceAtLeast(base)
    protected fun <T> withItemBoost(scale: Double, action: () -> T): T {
        val previous = callbackBoost
        callbackBoost = scale
        try { return action() } finally { callbackBoost = previous }
    }
    protected fun <T : dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile> T.upgraded(): T {
        val scale = itemBoost
        abilityUpgrade(scale) { action -> withItemBoost(scale, action) }
        return this
    }
    protected fun repeatingTask(period: Long, action: org.bukkit.scheduler.BukkitRunnable.() -> Unit) = repeatingTask(0, period, action)
    protected fun repeatingTask(delay: Long, period: Long, action: org.bukkit.scheduler.BukkitRunnable.() -> Unit): gg.flyte.twilight.scheduler.TwilightRunnable {
        // Setup-time maintenance jobs intentionally read the current cast (e.g. flight).
        val scale = if (lastUsed == 0L) null else itemBoost
        return gg.flyte.twilight.scheduler.repeatingTask(delay, period, false) {
            if (scale == null) action() else withItemBoost(scale) { action() }
        }
    }
    protected fun delay(ticks: Long, action: org.bukkit.scheduler.BukkitRunnable.() -> Unit): gg.flyte.twilight.scheduler.TwilightRunnable {
        val scale = itemBoost
        return gg.flyte.twilight.scheduler.delay(ticks) { withItemBoost(scale) { action() } }
    }

    protected val elapsedSinceLastActivation: Long
        get() = System.currentTimeMillis() - lastUsed

    protected val metadata: MetadataAccessor =
        object : MetadataAccessor {
            override fun string(key: String): String? = getValue<String>(key)

            override fun double(key: String): Double? = getValue<Double>(key)

            override fun int(key: String): Int? = getValue<Int>(key)

            override fun float(key: String): Float? = getValue<Float>(key)

            override fun long(key: String): Long? = getValue<Long>(key)

            override fun boolean(key: String): Boolean? = getValue<Boolean>(key)

            inline fun <reified T> getValue(name: String): T? {
                try {
                    if (kitData != null) {
                        minigameData?.overrides?.kits?.get(kitData?.id)?.abilities?.get(id)?.let {
                            it.metadata?.getAs<T>(name)?.let { value ->
                                return value
                            }
                        }

                        kitData
                            ?.abilities
                            ?.find { it.id == id }
                            ?.overrides
                            ?.metadata
                            ?.getAs<T>(name)
                            ?.let { value ->
                                return value
                            }
                    }

                    return abilityData.metadata.getAs<T>(name)
                } catch (e: Exception) {
                    e.printStackTrace()
                    return null
                }
            }
        }

    protected fun breakNearbyTerrain(at: org.bukkit.Location) {
        minigameService.getMinigameForPlayer(player)?.takeIf {
            it.getState()==dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS
        }?.let { dev.betrix.superSmashMobsBrawl.smash.SmashService.arena(it).destroy(at,1.65,7) }
    }

    override fun setup() {
        hotbarItemStack = (kitData?.id ?: "hub").let { kit -> dev.betrix.superSmashMobsBrawl.services.KitStatsService.dress(player, kit,
            dev.betrix.superSmashMobsBrawl.services.KitPresentation.abilityItem(kit, id)) }

        val hotBarItemSlot =
            when (player.inventory.getItem(abilityData.itemSlot)) {
                null -> abilityData.itemSlot
                else -> {
                    plugin.logger.warning(
                        "Item slot ${abilityData.itemSlot} was already taken, using first empty slot instead"
                    )
                    player.inventory.firstEmpty()
                }
            }

        player.inventory.setItem(hotBarItemSlot, hotbarItemStack)

        runnables.add(
            repeatingTask(5) {
                if (!usesEnergy && !lastCheckForCanActivate && canActivate(false)) {
                    readyNoticeUntil = System.currentTimeMillis() + 2000
                    lastCheckForCanActivate = true

                    player.sendMessage(
                        lang.t("messages.abilities.readyToActivate") {
                            "abilityId" to abilityData.id
                        }
                    )
                    // Classic recharge "ding": note block pling at its highest note (F#).
                    player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.3f, 2.0f)
                }
            }
        )

        listeners.add(event<PlayerInteractEvent>(player) { onPlayerInteract(this) })
    }

    override fun teardown() {
        if (::hotbarItemStack.isInitialized) {
            player.inventory.remove(hotbarItemStack)
        }

        listeners.forEach { it.unregister() }
        runnables.forEach { it.cancel() }
        jobs.forEach { it.cancel() }
    }

    protected open fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != null && event.hand != EquipmentSlot.HAND) return

        if (!isCorrectActionForUsage(event.action)) {
            return
        }

        val item =
            event.item
                ?: player.inventory.itemInMainHand.takeIf { it.type != Material.AIR }
                ?: return

        if (!isCorrectItemForAbility(item)) {
            return
        }

        consumeClick(event)

        val ok = canActivate()
        // SSM AI Edition: temporary playtest diagnostics for real players (bots are skipped).
        if (!dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player)) {
            plugin.logger.info(
                "[ability] ${player.name} ${event.action} with ${item.type} -> $id " +
                    if (ok) "ACTIVATED" else "on cooldown"
            )
        }
        if (ok) {
            activate()
        }
    }

    /**
     * SSM AI Edition: swallow the click (no block interaction), but let a sword start "blocking".
     * Upstream cancelled the whole event, which also stopped the item being used, so hold-to-use
     * abilities (Egg Blaster, Slime Rocket, Block Toss, Static Laser...) never saw the player
     * blocking and fired nothing for real players.
     */
    protected fun consumeClick(event: PlayerInteractEvent) {
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
        val canBlock = event.item?.hasData(DataComponentTypes.BLOCKS_ATTACKS) == true
        event.setUseItemInHand(
            if (canBlock && event.action.isRightClick) org.bukkit.event.Event.Result.ALLOW
            else org.bukkit.event.Event.Result.DENY
        )
    }

    protected fun isCorrectItemForAbility(item: ItemStack): Boolean {
        if (dev.betrix.superSmashMobsBrawl.smash.SmashService.session(player)?.form()!=null ||
            item.itemMeta?.persistentDataContainer?.has(dev.betrix.superSmashMobsBrawl.smash.SmashItems.POWER)==true) return false
        val abilityId = getAbilityId(item) ?: return false

        return abilityId == id
    }

    protected fun isCorrectActionForUsage(eventAction: Action): Boolean {
        when (abilityData.usage) {
            AbilityUsage.LEFT_CLICK -> {
                if (
                    eventAction == Action.LEFT_CLICK_AIR || eventAction == Action.LEFT_CLICK_BLOCK
                ) {
                    return true
                }
            }

            AbilityUsage.RIGHT_CLICK -> {
                if (
                    eventAction == Action.RIGHT_CLICK_AIR || eventAction == Action.RIGHT_CLICK_BLOCK
                ) {
                    return true
                }
            }
        }

        return false
    }

    protected open fun activate() {
        dev.betrix.superSmashMobsBrawl.items.ItemService.beginCast(player, id)
        setCooldown()
        // Avoid client-side item cooldown visuals affecting combat rhythm; we keep only our timers
        try {
            player.setCooldown(hotbarItemStack, 0)
        } catch (_: Throwable) {}
        player.sendMessage(lang.t("messages.abilities.use.success") { "abilityId" to id })
        // Remember it so kills can be credited "with <ability>" in death messages.
        dev.betrix.superSmashMobsBrawl.services.CombatLogService.abilityUsed(player, displayName)
    }

    /** The ability's name as plain text ("Chicken Missile"). */
    val displayName: String
        get() =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(lang.t("abilities.$id.name"))

    /** 0.0 = just used, 1.0 = ready (for the recharge bar). */
    open fun rechargeProgress(): Double {
        val cooldownMs = effectiveCooldown * 1000.0
        if (cooldownMs <= 0) return 1.0
        return (elapsedSinceLastActivation / cooldownMs).coerceIn(0.0, 1.0)
    }

    /** Seconds until ready (for the recharge bar). */
    open fun rechargeSecondsLeft(): Double =
        ((effectiveCooldown * 1000.0 - elapsedSinceLastActivation) / 1000.0).coerceAtLeast(0.0)

    protected open val effectiveCooldown: Double
        get() {
            // A player may have changed this cooldown on their own copy of the kit (Change Kit Stats).
            val seconds = dev.betrix.superSmashMobsBrawl.services.KitStatsService.cooldown(player, kitData?.id, id) ?: abilityData.cooldown.toDouble()
            return dev.betrix.superSmashMobsBrawl.smash.SmashService.session(player)?.cooldown(seconds) ?: seconds
        }

    fun activateFromSmash() { if(canActivate()) activate() }
    fun readyForBot(): Boolean = canActivate(false)

    protected open fun canActivate(sendMessage: Boolean = true): Boolean {
        if(minigameService.getMinigameForPlayer(player)?.getState()==dev.betrix.superSmashMobsBrawl.minigames.MinigameState.STARTING)return false
        if (isOnCooldown()) {
            if (sendMessage) {
                val component =
                    lang.t("messages.abilities.use.cooldown") {
                        "abilityId" to id
                        "seconds" to "%.1f".format(rechargeSecondsLeft())
                    }
                player.sendMessage(component)
            }
            return false
        }

        return true
    }

    protected fun isOnCooldown(): Boolean {
        val cooldownMs = effectiveCooldown * 1000L
        return elapsedSinceLastActivation < cooldownMs
    }

    protected fun getRemainingCooldown(): Int {
        val cooldownMs = (effectiveCooldown * 1000).toLong()
        return ((cooldownMs - elapsedSinceLastActivation) / 1000).coerceAtLeast(0).toInt()
    }

    private fun getRemainingCooldownTicks(): Int {
        val cooldownMs = (effectiveCooldown * 1000).toLong()
        val remainingMs = (cooldownMs - elapsedSinceLastActivation).coerceAtLeast(0)
        return (remainingMs / 50).toInt() // 1 tick = 50 ms
    }

    protected fun setCooldown(time: Long = System.currentTimeMillis()) {
        lastCheckForCanActivate = usesEnergy
        readyNoticeUntil = 0
        lastUsed = time
    }

    private fun getAbilityId(item: ItemStack?): String? {
        if (item == null || !item.hasItemMeta()) return null

        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(abilityKey, PersistentDataType.STRING)
    }
}

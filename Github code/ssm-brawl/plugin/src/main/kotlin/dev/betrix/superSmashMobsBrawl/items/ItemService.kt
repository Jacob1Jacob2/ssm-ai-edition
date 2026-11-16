package dev.betrix.superSmashMobsBrawl.items

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.events.*
import dev.betrix.superSmashMobsBrawl.minigames.*
import dev.betrix.superSmashMobsBrawl.services.*
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import gg.flyte.twilight.event.event
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.player.*
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import java.util.UUID
import dev.betrix.superSmashMobsBrawl.extensions.disguise

/** Item state is scoped to the actual match and life; nothing can damage the lobby. */
object ItemService : KoinComponent {
    val plugin: SuperSmashMobsBrawl by inject()
    private val games: MinigameService by inject()
    private val data: DataService by inject()
    val KEY = NamespacedKey("ssm", "arena_item")
    val USES = NamespacedKey("ssm", "item_uses")
    private val abilityKey get() = NamespacedKey(plugin, "abilityId")
    private val arenas = mutableMapOf<BrawlMinigame, ItemArena>()
    private val casts = mutableMapOf<Pair<UUID, String>, Double>()
    private val upgrades = mutableMapOf<Pair<UUID, String>, Pair<Int, Material>>()
    private val cooldowns = mutableMapOf<UUID, Int>()
    private val frozen = mutableMapOf<UUID, Int>()
    private val hidden = mutableMapOf<UUID, Int>()
    private val originalInvisible = mutableMapOf<UUID, Boolean>()
    private val shotbowAmmo = mutableSetOf<UUID>()
    private val appleCaps = mutableMapOf<UUID, Double>()
    private val absorptionCaps = mutableMapOf<UUID, Double>()
    private val lives = mutableMapOf<UUID, Any>()
    private var task: org.bukkit.scheduler.BukkitTask? = null

    fun arena(game: BrawlMinigame) = arenas.getOrPut(game) { ItemArena(game) }
    fun arena(player: Player): ItemArena? = games.getMinigameForPlayer(player)
        ?.takeIf { it.getState() == MinigameState.IN_PROGRESS }?.let(::arena)
    /** Where the pickups in this player's match are lying. Never creates an arena. */
    fun pickupLocations(player: Player): List<Location> =
        games.getMinigameForPlayer(player)?.let { arenas[it] }?.pickupLocations() ?: emptyList()
    fun kind(item: ItemStack?): ArenaItem? = item?.itemMeta?.persistentDataContainer
        ?.get(KEY, PersistentDataType.STRING)?.let(ArenaItem::parse)
    fun uses(item: ItemStack?) = item?.itemMeta?.persistentDataContainer?.get(USES, PersistentDataType.INTEGER) ?: 0
    fun frozen(player: Player) = (frozen[player.uniqueId] ?: 0) > Bukkit.getCurrentTick()
    fun hidden(player: Player) = (hidden[player.uniqueId] ?: 0) > Bukkit.getCurrentTick()
    fun appleBonus(player: Player) = appleCaps[player.uniqueId] ?: 0.0

    /** Pickups remain advertised while carried, even when the fighter switches to a kit tool. */
    fun carriedItems(player: Player): List<ArenaItem> {
        val items = mutableListOf<ArenaItem>()
        kind(player.inventory.itemInMainHand)?.let(items::add)
        player.inventory.contents.forEach { held -> kind(held)?.let(items::add) }
        for (slot in 0..8) {
            val held = player.inventory.getItem(slot) ?: continue
            val id = held.itemMeta?.persistentDataContainer?.get(abilityKey, PersistentDataType.STRING) ?: continue
            if (upgrades.containsKey(player.uniqueId to id))
                ArenaItem.entries.firstOrNull { it.tool && it.material == held.type }?.let(items::add)
        }
        return items.distinct()
    }

    fun stack(kind: ArenaItem, count: Int = kind.uses): ItemStack = ItemStack.of(kind.material).apply {
        editMeta {
            it.displayName(Component.text(kind.label, kind.rarity.color).decoration(TextDecoration.ITALIC, false))
            it.persistentDataContainer.set(KEY, PersistentDataType.STRING, kind.id)
            it.persistentDataContainer.set(USES, PersistentDataType.INTEGER, count)
            it.isUnbreakable = true
            it.lore(listOf(Component.text("${kind.rarity.label} · $count ${kind.useLabel}", kind.rarity.color)
                .decoration(TextDecoration.ITALIC, false), Component.text(kind.description, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)))
            it.setEnchantmentGlintOverride(kind == ArenaItem.SHOTBOW || kind == ArenaItem.KNOCKBACK_ROD)
        }
        if (kind.uses > 1) ItemPresentation.durability(this, count, kind.uses)
        if (kind == ArenaItem.RAIN_STAFF) setData(io.papermc.paper.datacomponent.DataComponentTypes.BLOCKS_ATTACKS,
            io.papermc.paper.datacomponent.item.BlocksAttacks.blocksAttacks().build())
    }.let(dev.betrix.superSmashMobsBrawl.utils.AbilityTools::prepare)

    fun adapted(player: Player, requested: ArenaItem): ArenaItem? {
        if (!requested.tool) return requested
        val available = (0..8).mapNotNull { slot ->
            val held = player.inventory.getItem(slot) ?: return@mapNotNull null
            if (held.itemMeta?.persistentDataContainer?.has(abilityKey, PersistentDataType.STRING) != true ||
                dev.betrix.superSmashMobsBrawl.abilities.MysteryAbility.isBorrowed(held)) return@mapNotNull null
            when {
                held.type.name.endsWith("_AXE") -> ArenaItem.DIAMOND_AXE
                held.type.name.endsWith("_SWORD") -> ArenaItem.DIAMOND_SWORD
                held.type.name.endsWith("_SHOVEL") -> ArenaItem.DIAMOND_SHOVEL
                else -> null
            }
        }.distinct()
        return requested.takeIf { it in available } ?: available.firstOrNull()
    }

    fun give(player: Player, requested: ArenaItem): Boolean {
        val kind = adapted(player, requested) ?: return false
        if (kind == ArenaItem.DIAMOND_HELMET) {
            if (arena(player)?.players()?.contains(player) != true || !DiamondHelmet.equip(player)) return false
        } else if (kind.tool) {
            val suffix = kind.material.name.substringAfter("DIAMOND_")
            val slot = (0..8).firstOrNull { player.inventory.getItem(it)?.type?.name?.endsWith("_$suffix") == true &&
                !dev.betrix.superSmashMobsBrawl.abilities.MysteryAbility.isBorrowed(player.inventory.getItem(it)) &&
                player.inventory.getItem(it)?.itemMeta?.persistentDataContainer?.has(abilityKey, PersistentDataType.STRING) == true } ?: return false
            val item = player.inventory.getItem(slot)!!
            val id = item.itemMeta.persistentDataContainer.get(abilityKey, PersistentDataType.STRING)!!
            val key = player.uniqueId to id
            upgrades[key] = kind.uses to (upgrades[key]?.second ?: item.type)
            item.type = kind.material
            item.editMeta { meta -> meta.setEnchantmentGlintOverride(false) }
            ItemPresentation.durability(item, kind.uses, kind.uses)
            player.inventory.setItem(slot, dev.betrix.superSmashMobsBrawl.utils.AbilityTools.prepare(item))
        } else {
            // Check the whole inventory, including the offhand, not just the selected slot.
            if (player.inventory.contents.any { held ->
                kind(held)?.let { !it.tool && it != ArenaItem.DIAMOND_HELMET } == true
            }) return false
            val slot = (0..8).firstOrNull { player.inventory.getItem(it)?.type?.isAir != false } ?: return false
            player.inventory.setItem(slot, stack(kind).also { it.type = ItemPresentation.material(kind, player) })
            if (kind == ArenaItem.SHOTBOW) ensureShotbowAmmo(player)
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, .65f, 1f)
        player.sendMessage(Component.text("Item> ", NamedTextColor.BLUE).append(Component.text(kind.label, kind.rarity.color)))
        return true
    }

    /** Invoked only after a successful ability activation, never on a cooldown click. */
    fun beginCast(player: Player, id: String) {
        val key = player.uniqueId to id
        val upgrade = upgrades[key]
        casts[key] = if (upgrade != null) 1.2 else 1.0
        if (upgrade != null) {
            if (upgrade.first > 1) {
                upgrades[key] = upgrade.first - 1 to upgrade.second
                for (slot in 0..8) {
                    val item = player.inventory.getItem(slot) ?: continue
                    if (item.itemMeta?.persistentDataContainer?.get(abilityKey, PersistentDataType.STRING) == id) {
                        ItemPresentation.durability(item, upgrade.first - 1, 4); player.inventory.setItem(slot,item)
                    }
                }
            }
            else {
                upgrades.remove(key)
                for (slot in 0..8) {
                    val item = player.inventory.getItem(slot) ?: continue
                    if (item.itemMeta?.persistentDataContainer?.get(abilityKey, PersistentDataType.STRING) == id) {
                        item.type = upgrade.second; ItemPresentation.clearDurability(item); item.editMeta { it.setEnchantmentGlintOverride(false) }
                        player.inventory.setItem(slot, dev.betrix.superSmashMobsBrawl.utils.AbilityTools.prepare(item))
                    }
                }
            }
        }
    }
    fun nextCastScale(player: Player, id: String) = if (upgrades.containsKey(player.uniqueId to id)) 1.2 else 1.0
    fun castScale(player: Player, id: String) = casts[player.uniqueId to id] ?: 1.0

    fun freeze(player: Player, ticks: Int) { frozen[player.uniqueId] = Bukkit.getCurrentTick() + ticks; player.allowFlight = false }
    fun hide(player: Player, ticks: Int) {
        val wasHidden=hidden(player)
        originalInvisible.putIfAbsent(player.uniqueId, player.isInvisible)
        player.isInvisible = true
        player.disguise?.setInvisible(true)
        me.libraryaddict.disguise.DisguiseAPI.getDisguise(player)?.let {
            it.watcher.isInvisible = true; it.setHideArmorFromSelf(true); it.setHideHeldItemFromSelf(true)
        }
        hidden[player.uniqueId] = maxOf(hidden[player.uniqueId] ?: 0, Bukkit.getCurrentTick() + ticks)
        Bukkit.getOnlinePlayers().filter { it != player }.forEach { it.hideEntity(plugin, player) }
        if(!wasHidden)player.sendMessage(dev.betrix.superSmashMobsBrawl.utils.CombatFeedback.condition(true))
    }
    private fun reveal(player: Player) {
        val wasHidden=hidden.remove(player.uniqueId)!=null
        originalInvisible.remove(player.uniqueId)?.let { player.isInvisible = it }
        if (wasHidden) {
            player.disguise?.setInvisible(false)
            me.libraryaddict.disguise.DisguiseAPI.getDisguise(player)?.let {
                it.watcher.isInvisible = false; it.setHideArmorFromSelf(false); it.setHideHeldItemFromSelf(false)
            }
        }
        Bukkit.getOnlinePlayers().forEach { it.showEntity(plugin, player) }
        if(wasHidden && player.isOnline)player.sendMessage(dev.betrix.superSmashMobsBrawl.utils.CombatFeedback.condition(false))
    }
    fun addAbsorption(player: Player) {
        val attr = player.getAttribute(org.bukkit.attribute.Attribute.MAX_ABSORPTION)
        absorptionCaps.putIfAbsent(player.uniqueId, attr?.baseValue ?: 0.0)
        appleCaps[player.uniqueId] = appleBonus(player) + 10.0
        attr?.baseValue = maxOf(attr?.baseValue ?: 0.0, player.absorptionAmount + 10.0)
        player.absorptionAmount += 10.0
    }

    fun spawnInFront(player: Player, kind: ArenaItem): String {
        val arena = arena(player) ?: return "Start a match first to spawn and test items."
        if (player !in arena.players()) return "Only living fighters can spawn items."
        val at = player.eyeLocation.add(player.eyeLocation.direction.multiply(2.0))
        val wall = player.world.rayTraceBlocks(player.eyeLocation, player.eyeLocation.direction, 2.0)
        if (wall != null) { at.x = wall.hitPosition.x; at.y = wall.hitPosition.y; at.z = wall.hitPosition.z; at.subtract(player.eyeLocation.direction.multiply(.4)) }
        return if (arena.spawn(kind, at)) "${kind.label} spawned in front of you." else "Items cannot be spawned in this arena right now."
    }

    fun use(player: Player, left: Boolean = false, target: Player? = null): Boolean {
        val arena = arena(player) ?: return false
        if (player !in arena.players()) return false
        val item = player.inventory.itemInMainHand
        val kind = kind(item) ?: return false
        val actualTarget = target ?: if (left && kind == ArenaItem.KNOCKBACK_ROD) arena.enemies(player).filter {
            it.boundingBox.clone().expand(.25).rayTrace(player.eyeLocation.toVector(), player.eyeLocation.direction, 4.0) != null
        }.minByOrNull { it.location.distanceSquared(player.location) } else null
        if (left != (kind == ArenaItem.SMASHER || kind == ArenaItem.KNOCKBACK_ROD && actualTarget != null)) return false
        val now = Bukkit.getCurrentTick()
        if (now < (cooldowns[player.uniqueId] ?: 0)) return false
        val cost = if (kind == ArenaItem.KNOCKBACK_ROD && !left) 2 else 1
        if (uses(item) < cost) return false
        if (!arena.effects.use(player, kind, actualTarget)) return false
        consume(player, cost)
        cooldowns[player.uniqueId] = now + when (kind) { ArenaItem.RAIN_STAFF -> 2; ArenaItem.MISSILE_BATTERY -> 2; else -> 8 }
        return true
    }
    /**
     * A bot's Player Grapple. Players cast a real fishing hook; a bot has no hand to cast with, so
     * this applies the same catch to an opponent it can see within the hook's range.
     */
    fun botGrapple(player: Player, victim: Player): Boolean {
        val arena = arena(player) ?: return false
        if (kind(player.inventory.itemInMainHand) != ArenaItem.PLAYER_GRAPPLE || player !in arena.players()) return false
        if (victim !in arena.enemies(player) || KitService.getKitForPlayer(victim)?.isInvincible() == true) return false
        if (victim.location.distanceSquared(player.location) > 15 * 15 || !player.hasLineOfSight(victim)) return false
        if (!consume(player, 1)) return false
        arena.effects.grapple(player, victim)
        return true
    }

    /** A bot's Crafty Bomb: placed on the free block at or beside [at], as a player's click would. */
    fun botPlaceCrafty(player: Player, at: Location): Boolean {
        val arena = arena(player) ?: return false
        if (kind(player.inventory.itemInMainHand) != ArenaItem.CRAFTY_BOMB) return false
        val base = at.block.location
        val spots = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1).map { (x, z) -> base.clone().add(x.toDouble(), 0.0, z.toDouble()) }
        return spots.any { arena.effects.placeCrafty(player, it) } && consume(player, 1)
    }

    fun consume(player: Player, amount: Int): Boolean {
        val item = player.inventory.itemInMainHand
        val kind = kind(item) ?: return false
        val count = uses(item)
        if (count < amount) return false
        player.inventory.setItemInMainHand(if (count == amount) ItemStack.empty() else stack(kind, count - amount).also { it.type = item.type })
        if (kind == ArenaItem.SHOTBOW && count == amount) clearShotbowAmmo(player)
        return true
    }

    private fun ensureShotbowAmmo(player: Player) {
        if (player.inventory.contains(Material.ARROW)) return
        val slot = (9..35).firstOrNull { player.inventory.getItem(it)?.type?.isAir != false } ?: return
        player.inventory.setItem(slot, ItemStack.of(Material.ARROW).also { it.editMeta { meta ->
            meta.persistentDataContainer.set(NamespacedKey("ssm","shotbow_ammo"), PersistentDataType.BYTE,1)
        } }); shotbowAmmo += player.uniqueId
    }
    private fun clearShotbowAmmo(player: Player) {
        if (!shotbowAmmo.remove(player.uniqueId)) return
        for (slot in 0..35) if (player.inventory.getItem(slot)?.itemMeta?.persistentDataContainer?.has(NamespacedKey("ssm","shotbow_ammo")) == true)
            player.inventory.setItem(slot,null)
    }
    fun clear(player: Player) {
        clearShotbowAmmo(player)
        DiamondHelmet.clear(player)
        reveal(player); frozen.remove(player.uniqueId); cooldowns.remove(player.uniqueId); lives.remove(player.uniqueId)
        upgrades.keys.removeIf { it.first == player.uniqueId }; casts.keys.removeIf { it.first == player.uniqueId }
        if (appleCaps.remove(player.uniqueId) != null) {
            player.absorptionAmount = 0.0
            absorptionCaps.remove(player.uniqueId)?.let { player.getAttribute(org.bukkit.attribute.Attribute.MAX_ABSORPTION)?.baseValue = it }
        }
        for (slot in 0 until 36) if (kind(player.inventory.getItem(slot)) != null) player.inventory.setItem(slot, null)
    }
    fun close(game: BrawlMinigame) {
        arenas.remove(game)?.close()
        game.allPlayers().mapNotNull { it.player }.forEach(::clear)
    }

    fun setup() {
        event<org.bukkit.event.entity.EntityDamageByEntityEvent>(priority = EventPriority.LOWEST) {
            val owner = ItemEffects.cloneOwners[entity.uniqueId] ?: return@event
            isCancelled = true
            val attacker = damager as? Player ?: return@event
            val arena = arena(owner) ?: return@event
            if (attacker !in arena.enemies(owner)) return@event
            BrawlDamageEvent(entity as org.bukkit.entity.LivingEntity, Damager.DamagerLivingEntity(attacker),
                KitService.getKitForPlayer(attacker)?.getMeleeDamage() ?: 4.0).callEvent()
        }
        event<BrawlDamageEvent>(priority = EventPriority.HIGH) {
            val owner = ItemEffects.cloneOwners[victim.uniqueId] ?: return@event
            val attacker = (damager as? Damager.DamagerLivingEntity)?.livingEntity as? Player ?: return@event
            val arena = arena(owner) ?: return@event
            if (isCancelled || attacker !in arena.enemies(owner) || victim.noDamageTicks > 0 || !victim.isValid) return@event
            appliedDamage = damage.coerceIn(0.0,victim.health)
            if (damage >= victim.health) victim.remove() else { victim.health -= appliedDamage; victim.noDamageTicks = 10; victim.playHurtAnimation(0f) }
        }
        event<BrawlDamageEvent>(priority = EventPriority.MONITOR) {
            val p = victim as? Player ?: return@event
            val attacker = (damager as? Damager.DamagerLivingEntity)?.livingEntity
            if (!isCancelled && appliedDamage > 0 && attacker != null && attacker != p) DiamondHelmet.hit(p)
            val bonus = appleBonus(p)
            if (appliedDamage > 0 && bonus > 0) {
                appleCaps[p.uniqueId] = (bonus - appliedDamage).coerceAtLeast(0.0)
                val base = absorptionCaps[p.uniqueId] ?: 0.0
                p.getAttribute(org.bukkit.attribute.Attribute.MAX_ABSORPTION)?.baseValue = maxOf(base + appleBonus(p), p.absorptionAmount)
            }
        }
        event<PlayerInteractEvent>(priority = EventPriority.LOWEST) {
            if (hand != EquipmentSlot.HAND || kind(item) == null) return@event
            setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
            val nativeUse = kind(item) in setOf(ArenaItem.SHOTBOW,ArenaItem.PLAYER_GRAPPLE)
            setUseItemInHand(if ((nativeUse || kind(item) == ArenaItem.RAIN_STAFF) && action.isRightClick)
                org.bukkit.event.Event.Result.ALLOW else org.bukkit.event.Event.Result.DENY)
            if (kind(item) == ArenaItem.CRAFTY_BOMB && action.isRightClick) {
                val spot = clickedBlock?.getRelative(blockFace) ?: return@event
                if (arena(player)?.effects?.placeCrafty(player,spot.location) == true) consume(player,1)
            } else if (!nativeUse) use(player, action.isLeftClick)
        }
        event<io.papermc.paper.event.player.PrePlayerAttackEntityEvent>(priority = EventPriority.LOWEST) {
            if (kind(player.inventory.itemInMainHand) == null) return@event
            isCancelled = true
            use(player, true, attacked as? Player)
        }
        event<io.papermc.paper.event.player.PlayerStopUsingItemEvent>(priority = EventPriority.LOWEST) {
            if (kind(item) != ArenaItem.SHOTBOW) return@event
            // Paper fires this before BowItem consumes its arrow and plays its unconditional
            // vanilla shooting sound. End native use here; only our wooden volley runs.
            (player as org.bukkit.craftbukkit.entity.CraftPlayer).handle.stopUsingItem()
            val arena = arena(player) ?: return@event
            val force = net.minecraft.world.item.BowItem.getPowerForTime(ticksHeldFor)
            if (player in arena.players() && kind(player.inventory.itemInMainHand) == ArenaItem.SHOTBOW && force >= .1f) {
                arena.effects.shotbow(player, force.toDouble())
                consume(player, 1)
            }
        }
        event<EntityShootBowEvent>(priority = EventPriority.LOWEST) {
            if (kind(bow) == null) return@event
            isCancelled = true; projectile.remove(); setConsumeItem(false)
            val player = entity as? Player ?: return@event
            if (kind(bow) == ArenaItem.SHOTBOW && force >= .1f && arena(player)?.players()?.contains(player) == true) {
                arena(player)!!.effects.shotbow(player, force.toDouble()); consume(player,1)
            }
        }
        event<PlayerFishEvent>(priority = EventPriority.HIGHEST) {
            if (kind(player.inventory.itemInMainHand) != ArenaItem.PLAYER_GRAPPLE) return@event
            val arena = arena(player) ?: run { isCancelled=true; return@event }
            if (state == PlayerFishEvent.State.CAUGHT_ENTITY) {
                isCancelled=true; hook.remove()
                val victim = caught as? Player ?: return@event
                if (victim !in arena.enemies(player) || KitService.getKitForPlayer(victim)?.isInvincible() == true) return@event
                if (consume(player,1)) arena.effects.grapple(player,victim)
            } else if (state == PlayerFishEvent.State.CAUGHT_FISH) { isCancelled=true; hook.remove() }
        }
        event<PlayerDropItemEvent>(priority = EventPriority.HIGHEST) {
            val discarded = kind(itemDrop.itemStack) ?: return@event
            if (discarded.tool || discarded == ArenaItem.DIAMOND_HELMET || arena(player)?.players()?.contains(player) != true) {
                isCancelled = true
                return@event
            }
            // The native drop has already removed this stack from the selected slot.
            // Override the general hotbar lock, then discard its entity before it enters the world.
            isCancelled = false
            itemDrop.remove()
            if (discarded == ArenaItem.SHOTBOW) clearShotbowAmmo(player)
        }
        event<PlayerItemDamageEvent> {
            if (kind(item) != null || item.itemMeta?.persistentDataContainer?.has(abilityKey) == true) isCancelled = true
        }
        event<PlayerItemConsumeEvent> {
            if (kind(item) == null) return@event
            isCancelled = true
        }
        event<org.bukkit.event.entity.EntityCombustEvent>(priority=EventPriority.HIGHEST) {
            if (entity.uniqueId in ItemEffects.cloneOwners) { isCancelled=true; entity.fireTicks=0 }
        }
        event<org.bukkit.event.entity.EntityDamageEvent>(priority=EventPriority.HIGHEST) {
            if (entity.uniqueId in ItemEffects.cloneOwners) isCancelled=true
        }
        event<PlayerSwapHandItemsEvent> { if (kind(mainHandItem) != null || kind(offHandItem) != null) isCancelled = true }
        event<PlayerQuitEvent> { clear(player) }
        event<PlayerChangedWorldEvent> { clear(player) }
        event<BrawlDeathEvent>(priority = EventPriority.MONITOR) { clear(player) }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            games.activeMinigames().filter { it.getState() == MinigameState.IN_PROGRESS && it.minigameDef.id == "super_smash_mobs" }.forEach { game ->
                val arena = arena(game)
                arena.players().forEach { player ->
                    val life = KitService.getKitForPlayer(player) ?: return@forEach
                    if (lives[player.uniqueId] != null && lives[player.uniqueId] !== life) clear(player)
                    lives[player.uniqueId] = life
                    DiamondHelmet.refresh(player)
                    if (frozen(player)) player.allowFlight = false
                    if (player.uniqueId in hidden && !hidden(player)) reveal(player)
                    // Bots decide for themselves when an item is worth using (BotBrain.considerItem).
                }
                arena.step()
            }
            arenas.keys.filter { it.getState() != MinigameState.IN_PROGRESS }.toList().forEach(::close)
        }, 1L, 1L)
    }
    fun teardown() { task?.cancel(); arenas.keys.toList().forEach(::close) }
}

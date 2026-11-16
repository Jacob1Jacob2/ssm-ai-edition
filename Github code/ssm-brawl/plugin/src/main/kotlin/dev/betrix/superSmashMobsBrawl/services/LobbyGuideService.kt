package dev.betrix.superSmashMobsBrawl.services

import gg.flyte.twilight.event.event
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import java.util.UUID
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.*
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.*
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Persistent guide NPCs are separate from selectable combat kits. */
object LobbyGuideService {
    private lateinit var plugin: JavaPlugin
    private val removed = mutableSetOf<String>()
    private val labels = mutableMapOf<UUID, List<TextDisplay>>()
    private val activeGuides = mutableMapOf<Pair<UUID, String>, UUID>()
    private var registered = false
    private val LABEL = NamespacedKey("ssm", "guide_label")
    private var animationTick = 0

    private fun labelsFor(mob: LivingEntity): List<TextDisplay> {
        labels[mob.uniqueId]?.takeIf { it.all(Entity::isValid) }?.let { return it }
        labels.remove(mob.uniqueId)?.forEach(Entity::remove)
        return (0..2).map { row -> mob.world.spawn(mob.location.clone().add(0.0, mob.height + .3 + (2-row)*.3, 0.0), TextDisplay::class.java) {
            it.persistentDataContainer.set(LABEL, PersistentDataType.STRING, mob.uniqueId.toString())
            it.isPersistent = false; it.billboard = Display.Billboard.CENTER
            it.brightness = Display.Brightness(15, 15); it.isShadowed = true; it.viewRange = 1.5f
        } }.also { labels[mob.uniqueId] = it }
    }

    private fun animateLabels() {
        animationTick++
        labels.entries.removeIf { (id, displays) ->
            val mob = Bukkit.getEntity(id) as? LivingEntity
            if (mob == null || !mob.isValid) { displays.forEach(Entity::remove); true }
            else {
                val carl = guideId(mob) == "carl"
                val bright = animationTick % 8 < 4
                val headingColor = if (carl) {
                    if (bright) NamedTextColor.AQUA else NamedTextColor.DARK_AQUA
                } else {
                    if (bright) NamedTextColor.YELLOW else NamedTextColor.GOLD
                }
                val lines = listOf(
                    Component.text(if(carl) "New items!" else "4 Smash Crystals", headingColor),
                    Component.text("PUNCH TO OPEN", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD),
                    Component.text(if(carl) "Carl the Creeper" else "Peppa the Pig", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                displays.forEachIndexed { row, display ->
                    display.text(lines[row])
                    display.teleport(mob.location.clone().add(0.0, mob.height + .3 + (2-row)*.3, 0.0))
                }
                false
            }
        }
    }
    val KEY = NamespacedKey("ssm", "lobby_guide")
    fun guideId(entity: Entity) = entity.persistentDataContainer.get(KEY, PersistentDataType.STRING)

    fun initialize(plugin: JavaPlugin, world: World) {
        this.plugin = plugin
        world.entities.filter { it.persistentDataContainer.has(LABEL) }.forEach(Entity::remove)
        if (!registered) {
            Bukkit.getScheduler().runTaskTimer(plugin, Runnable { animateLabels() }, 1L, 5L)
            removed.addAll(plugin.config.getStringList("lobby-guides.removed"))
        }
        // The central lobby platform and its two neighboring lawns.
        val positions = mapOf("carl" to Location(world, -3.5, 65.0, -1.5, 0f, 0f),
            "peppa" to Location(world, 6.5, 65.0, -1.5, 0f, 0f))
        positions.forEach { (id, fallback) ->
            (plugin.config.getLocation("lobby-guides.positions.$id")?.takeIf { it.world == world } ?: fallback).chunk.load()
        }
        positions.forEach { (id, at) ->
            if (id in removed) return@forEach
            val existing = world.livingEntities.filter { guideId(it) == id || (id == "peppa" && guideId(it) == "sam") }
            val saved = plugin.config.getLocation("lobby-guides.positions.$id")?.takeIf { it.world == world }
                ?: existing.firstOrNull()?.location ?: at
            if (id == "peppa") existing.filter { it !is Pig }.forEach(Entity::remove)
            val npc = existing.firstOrNull { it.isValid } ?: world.spawnEntity(
                requireNotNull(LobbyBuildService.surfaceUnder(saved)) { "No ground for lobby guide $id" },
                if (id == "carl") EntityType.CREEPER else EntityType.PIG) as LivingEntity
            existing.filter { it != npc }.forEach(Entity::remove)
            npc.persistentDataContainer.set(KEY, PersistentDataType.STRING, id)
            npc.teleport(saved)
            activeGuides[world.uid to id] = npc.uniqueId
            restore(npc)
            rememberPosition(npc)
        }
        if (registered) return
        registered = true
        event<PlayerInteractEntityEvent>(priority = EventPriority.HIGHEST) {
            val id = guideId(rightClicked) ?: return@event
            isCancelled = true
            if (hand == EquipmentSlot.HAND && HubService.isPlayerInHub(player) && !LobbyBuildService.select(player, rightClicked)) open(player, id)
        }
        event<PrePlayerAttackEntityEvent>(priority = EventPriority.HIGHEST) {
            val id = guideId(attacked) ?: return@event
            isCancelled = true
            if (!HubService.isPlayerInHub(player)) return@event
            if (player.isOp && LobbyBuildService.isBuilding(player) && player.isSneaking) {
                remove(attacked)
                player.sendMessage(Component.text("Removed $id. Use its build egg to place it again.", NamedTextColor.GRAY))
            } else if (!LobbyBuildService.select(player, attacked)) open(player, id)
        }
        event<EntityDamageEvent>(priority = EventPriority.HIGHEST) { if (guideId(entity) != null) isCancelled = true }
        event<EntityCombustEvent>(priority = EventPriority.HIGHEST) { if (guideId(entity) != null) isCancelled = true }
        event<EntityTransformEvent>(priority = EventPriority.HIGHEST) { if (guideId(entity) != null) isCancelled = true }
        event<ExplosionPrimeEvent>(priority = EventPriority.HIGHEST) { if (guideId(entity) != null) isCancelled = true }
        event<org.bukkit.event.world.EntitiesLoadEvent> {
            val loaded = entities.toList()
            Bukkit.getScheduler().runTask(plugin, Runnable { loaded.filterIsInstance<LivingEntity>().forEach(::restore) })
        }
    }

    fun place(id: String, at: Location): LivingEntity {
        require(id == "carl" || id == "peppa")
        val grounded = requireNotNull(LobbyBuildService.surfaceUnder(at))
        val old = at.world.livingEntities.filter { guideId(it) == id || (id == "peppa" && guideId(it) == "sam") }
        val mob = at.world.spawnEntity(grounded, if (id == "carl") EntityType.CREEPER else EntityType.PIG) as LivingEntity
        mob.persistentDataContainer.set(KEY, PersistentDataType.STRING, id)
        removed.remove(id)
        activeGuides[mob.world.uid to id] = mob.uniqueId
        restore(mob)
        old.forEach(Entity::remove)
        rememberPosition(mob)
        saveRemoved()
        return mob
    }

    fun remove(entity: Entity) {
        val id = guideId(entity) ?: return
        removed.add(id)
        activeGuides.remove(entity.world.uid to id)
        labels.remove(entity.uniqueId)?.forEach(Entity::remove)
        entity.remove()
        saveRemoved()
    }

    private fun saveRemoved() {
        plugin.config.set("lobby-guides.removed", removed.toList())
        plugin.saveConfig()
    }

    fun rememberPosition(entity: Entity) {
        if (entity.world.name != "ssm_lobby") return
        val id = guideId(entity) ?: return
        plugin.config.set("lobby-guides.positions.$id", entity.location)
        plugin.saveConfig()
    }

    private fun restore(mob: LivingEntity) {
        val id = guideId(mob) ?: return
        if (id in removed) { mob.remove(); return }
        if (!HubService.isWorldHub(mob.world)) return
        val active = activeGuides[mob.world.uid to id]
        if (active != null && active != mob.uniqueId) { mob.remove(); return }
        activeGuides[mob.world.uid to id] = mob.uniqueId
        LobbyNpcPresentation.configure(mob)
        if (mob is Creeper) mob.isPowered = true
        if (mob is Slime) mob.size = 4
        mob.customName(Component.text(if (id == "carl") "Carl — Item Guide" else "Peppa — Smash Guide", NamedTextColor.GREEN))
        mob.isCustomNameVisible = false
        labelsFor(mob)
    }

    private fun open(player: Player, id: String) {
        if (id == "carl") GuideMenus.items(player) else GuideMenus.smash(player)
        GuideMenus.ding(player)
    }
}

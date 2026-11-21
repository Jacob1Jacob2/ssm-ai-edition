package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.hotbar.HubHotbarPreset
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import gg.flyte.twilight.event.event
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Builder eggs create persistent, stationary kit selectors in the editable lobby. */
object LobbyBuildService : KoinComponent {
    private val lang: LangService by inject()
    private val plugin: SuperSmashMobsBrawl by inject()
    private val builders = mutableSetOf<UUID>()
    private val selected = mutableMapOf<UUID, UUID>()
    private val facing = mutableMapOf<UUID, Float>()
    val directions = linkedMapOf("north" to 180f, "northeast" to -135f, "east" to -90f,
        "southeast" to -45f, "south" to 0f, "southwest" to 45f, "west" to 90f, "northwest" to 135f)
    private val poseVersion = NamespacedKey("ssm", "npc_pose_v5")

    fun select(player: Player, entity: Entity): Boolean {
        if (!player.isOp || !isBuilding(player) || !HubService.isPlayerInHub(player)) return false
        if (kitId(entity) == null && LobbyGuideService.guideId(entity) == null) return false
        selected[player.uniqueId] = entity.uniqueId
        player.sendMessage("Selected ${kitId(entity) ?: LobbyGuideService.guideId(entity)}. /chatgptbuild face north|northeast|east|southeast|south|southwest|west|northwest; /chatgptbuild move <platform x> <platform y> <platform z>.")
        return true
    }

    fun face(player: Player, direction: String) {
        if (!player.isOp || !isBuilding(player)) { player.sendMessage("Enable /chatgptbuild first."); return }
        val yaw = directions[direction.lowercase().replace("-", "").replace("_", "")]
            ?: run { player.sendMessage("Choose ${directions.keys.joinToString(", ")}."); return }
        facing[player.uniqueId] = yaw
        selected[player.uniqueId]?.let(Bukkit::getEntity)?.let { entity ->
            if (entity is LivingEntity) LobbyNpcPresentation.face(entity, yaw) else entity.setRotation(yaw, 0f)
            if (entity is LivingEntity) LobbyNpcPresentation.configure(entity)
            LobbyGuideService.rememberPosition(entity)
        }
        player.sendMessage("Selected NPC and new eggs now face ${direction.lowercase()}.")
    }

    fun move(player: Player, x: Int, y: Int, z: Int) {
        if (!player.isOp || !isBuilding(player) || !HubService.isPlayerInHub(player)) return
        val entity = selected[player.uniqueId]?.let(Bukkit::getEntity)
        if (entity == null || entity.world != player.world) { player.sendMessage("Click a placed NPC to select it first."); return }
        if (y < player.world.minHeight || y >= player.world.maxHeight - 1) return
        val platform = player.world.getBlockAt(x, y, z)
        val top = platform.collisionShape.boundingBoxes.filter { .5 >= it.minX && .5 <= it.maxX && .5 >= it.minZ && .5 <= it.maxZ }.maxOfOrNull { it.maxY }
        if (top == null) { player.sendMessage("Those coordinates must be the platform block, not air."); return }
        val at = Location(player.world, x + .5, y + top, z + .5, facing[player.uniqueId] ?: entity.location.yaw, 0f)
        entity.teleport(at)
        if (entity is LivingEntity) LobbyNpcPresentation.face(entity, at.yaw) else entity.setRotation(at.yaw, 0f)
        if (entity is LivingEntity) LobbyNpcPresentation.configure(entity)
        LobbyGuideService.rememberPosition(entity)
        player.sendMessage("NPC centered on platform $x, $y, $z.")
    }
    val KIT_KEY = NamespacedKey("ssm", "lobby_kit")
    val EGG_KEY = NamespacedKey("ssm", "lobby_kit_egg")
    val mobs = linkedMapOf(
        "skeleton" to EntityType.SKELETON, "iron_golem" to EntityType.IRON_GOLEM,
        "spider" to EntityType.SPIDER, "slime" to EntityType.SLIME,
        "squid" to EntityType.SQUID, "creeper" to EntityType.CREEPER,
        "enderman" to EntityType.ENDERMAN, "snowman" to EntityType.SNOW_GOLEM,
        "wolf" to EntityType.WOLF, "magma_cube" to EntityType.MAGMA_CUBE,
        "witch" to EntityType.WITCH, "wither_skeleton" to EntityType.WITHER_SKELETON,
        "zombie" to EntityType.ZOMBIE, "cow" to EntityType.COW,
        "horse" to EntityType.SKELETON_HORSE, "pig" to EntityType.PIG,
        "blaze" to EntityType.BLAZE, "chicken" to EntityType.CHICKEN,
        "guardian" to EntityType.GUARDIAN, "sheep" to EntityType.SHEEP,
        "villager" to EntityType.VILLAGER,
        "mooshroom" to EntityType.MOOSHROOM, "goat" to EntityType.GOAT,
        "phantom" to EntityType.PHANTOM, "polar_bear" to EntityType.POLAR_BEAR,
    )
    val futureKits = emptySet<String>()

    fun isBuilding(player: Player) = player.uniqueId in builders
    private fun kitId(entity: Entity) = entity.persistentDataContainer.get(KIT_KEY, PersistentDataType.STRING)
    private fun name(id: String): Component {
        val title = lang.t("gui.kitSelection.kitName") { "kitId" to id }
            .decoration(TextDecoration.ITALIC, false)
        return if (id in futureKits) title.append(Component.text(" — Coming Soon", NamedTextColor.GRAY)
            .decoration(TextDecoration.BOLD, false)) else title
    }

    fun egg(id: String): ItemStack {
        val type = when (id) { "carl" -> EntityType.CREEPER; "peppa" -> EntityType.PIG; else -> mobs.getValue(id) }
        return ItemStack.of(Material.valueOf("${type.name}_SPAWN_EGG")).apply {
            editMeta {
                it.displayName(if (id == "carl" || id == "peppa") Component.text(if (id == "carl") "Carl — Item Guide" else "Peppa — Smash Guide", NamedTextColor.GREEN) else name(id))
                it.lore(listOf(Component.text("Place a kit selector on a platform", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
                it.persistentDataContainer.set(EGG_KEY, PersistentDataType.STRING, id)
            }
        }
    }

    fun toggle(player: Player) {
        if (!player.isOp) { player.sendMessage("Only operators can use /chatgptbuild."); return }
        if (!HubService.isPlayerInHub(player)) { player.sendMessage("Use /chatgptbuild in the lobby. Leave your match first."); return }
        if (isBuilding(player)) {
            finish(player)
            player.sendMessage("Build mode off. Your lobby changes are saved.")
        } else {
            builders += player.uniqueId
            // (From here on the lobby may not be what the backup has: see LobbyEdits.)
            LobbyEdits.mark()
            HotbarService.clearHotbar(player)
            player.closeInventory()
            player.inventory.clear()
            player.gameMode = GameMode.CREATIVE
            mobs.keys.forEachIndexed { slot, id -> player.inventory.setItem(slot, egg(id)) }
            player.inventory.setItem(mobs.size, egg("carl"))
            player.inventory.setItem(mobs.size + 1, egg("peppa"))
            player.inventory.heldItemSlot = 0
            player.sendMessage("Build mode on. Eggs stand on the center of the block you click. Click a placed NPC to select it for editing.")
            player.sendMessage("Sneak + left-click a placed mob to remove it. Use /chatgptbuild again to save and exit.")
        }
    }

    fun finish(player: Player) {
        if (!builders.remove(player.uniqueId)) return
        selected.remove(player.uniqueId); facing.remove(player.uniqueId)
        player.isFlying = false
        player.gameMode = GameMode.SURVIVAL
        player.inventory.clear()
        LobbyEdits.mark()
        if (HubService.isPlayerInHub(player)) {
            player.world.save()
            HotbarService.applyPreset(player, HubHotbarPreset)
        }
    }

    fun save(player: Player) {
        if (!player.isOp || !HubService.isPlayerInHub(player)) {
            player.sendMessage("Only operators in the lobby can save lobby builds."); return
        }
        player.world.save()
        LobbyEdits.mark()
        player.sendMessage("Lobby saved. Mob placements also survive restarts.")
    }

    fun place(id: String, at: Location): LivingEntity {
        require(HubService.isWorldHub(at.world))
        val grounded = requireNotNull(surfaceUnder(at)) { "A kit selector needs a platform beneath it" }
        val mob = at.world.spawnEntity(grounded, mobs.getValue(id)) as LivingEntity
        mob.persistentDataContainer.set(KIT_KEY, PersistentDataType.STRING, id)
        mob.persistentDataContainer.set(poseVersion, PersistentDataType.BYTE, 1)
        LobbyNpcPresentation.configure(mob)
        if (mob is Slime) mob.size = 2
        if (mob is org.bukkit.entity.Wolf) mob.variant = org.bukkit.entity.Wolf.Variant.PALE
        if (mob is Sheep) mob.color = DyeColor.WHITE
        mob.customName(name(id))
        mob.isCustomNameVisible = true
        return mob
    }

    /** Use the actual collision surface, including slabs, stairs, carpet and snow layers. */
    fun surfaceUnder(at: Location): Location? {
        val x = at.x - at.blockX
        val z = at.z - at.blockZ
        val bottom = maxOf(at.world.minHeight, kotlin.math.floor(at.y - 8.0).toInt())
        for (y in kotlin.math.floor(at.y + 0.00001).toInt() downTo bottom) {
            val block = at.world.getBlockAt(at.blockX, y, at.blockZ)
            val top = block.collisionShape.boundingBoxes
                .filter { x >= it.minX && x <= it.maxX && z >= it.minZ && z <= it.maxZ }
                .map { y + it.maxY }
                .filter { it <= at.y + 0.00001 && it >= at.y - 8.0 }
                .maxOrNull() ?: continue
            return at.clone().apply { this.y = top }
        }
        return null
    }

    fun settleSelector(entity: Entity): Boolean {
        if (kitId(entity) == null || !entity.isValid || !HubService.isWorldHub(entity.world)) return false
        val before = entity.location
        val grounded = surfaceUnder(before) ?: return false
        if (kotlin.math.abs(before.y - grounded.y) < 0.00001) return false
        return entity.teleport(grounded)
    }

    fun settleSelectors(entities: Collection<Entity>) {
        entities.forEach { entity ->
            if (entity is Chicken && HubService.isWorldHub(entity.world) &&
                entity.customName()?.let { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it) }?.equals("barbie", true) == true) {
                entity.remove(); return@forEach
            }
            val id = kitId(entity)
            if (id in mobs && HubService.isWorldHub(entity.world)) {
                if (entity is Goat && !entity.persistentDataContainer.has(poseVersion)) {
                    // Repair the old sideways goat once, matching the adjacent platform row.
                    // Face into the lobby along the nearest cardinal axis. The old nearest-NPC
                    // migration could inherit an idle mob's unrelated head direction.
                    val at = entity.location
                    at.yaw = if (kotlin.math.abs(at.x) >= kotlin.math.abs(at.z)) {
                        if (at.x > 0) 90f else 270f
                    } else if (at.z > 0) 180f else 0f
                    at.pitch = 0f
                    LobbyNpcPresentation.face(entity, at.yaw)
                    entity.persistentDataContainer.set(poseVersion, PersistentDataType.BYTE, 1)
                }
                if (entity is LivingEntity) LobbyNpcPresentation.configure(entity)
                entity.customName(name(id!!))
                entity.isCustomNameVisible = true
            }
        }
        val repaired = entities.count(::settleSelector)
        if (repaired > 0) plugin.logger.info("Grounded $repaired lobby kit selector(s) on their platform surfaces.")
    }

    fun setup() {
        event<PlayerInteractEvent>(priority = EventPriority.HIGHEST) {
            val id = item?.itemMeta?.persistentDataContainer?.get(EGG_KEY, PersistentDataType.STRING) ?: return@event
            isCancelled = true // Never let a builder egg create an ordinary wandering mob.
            if (hand != EquipmentSlot.HAND || action != Action.RIGHT_CLICK_BLOCK || !isBuilding(player) || !player.isOp) return@event
            if (!HubService.isPlayerInHub(player) || (id !in mobs && id !in setOf("carl", "peppa"))) return@event
            val at = clickedBlock!!.location.add(0.5, 1.0, 0.5)
            at.yaw = facing[player.uniqueId] ?: (kotlin.math.round((player.location.yaw + 180f) / 90f) * 90f)
            if (surfaceUnder(at) == null) {
                player.sendMessage("Place the egg above a solid platform.")
                return@event
            }
            val placed = if (id == "carl" || id == "peppa") LobbyGuideService.place(id, at) else place(id, at)
            select(player, placed)
            player.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.35f, 1.5f)
        }
        event<PlayerInteractEntityEvent>(priority = EventPriority.HIGHEST) {
            val id = kitId(rightClicked) ?: return@event
            isCancelled = true
            if (hand != EquipmentSlot.HAND || !HubService.isPlayerInHub(player)) return@event
            if (select(player, rightClicked)) return@event
            if (id in futureKits) {
                player.sendMessage(name(id))
                return@event
            }
            val kit = KitService.getKitData(id) ?: return@event
            KitService.openKitStats(player, kit)
        }
        event<EntityDamageEvent>(priority = EventPriority.HIGHEST) {
            if (kitId(entity) != null) isCancelled = true
        }
        // Selectors are invulnerable, so listen before vanilla rejects their damage.
        event<PrePlayerAttackEntityEvent>(priority = EventPriority.HIGHEST) {
            val id = kitId(attacked) ?: return@event
            isCancelled = true
            if (player.isOp && isBuilding(player) && player.isSneaking && HubService.isPlayerInHub(player)) {
                attacked.remove()
                player.sendMessage("Kit selector removed.")
            } else if (select(player, attacked)) {
                return@event
            } else if (HubService.isPlayerInHub(player) && !isBuilding(player)) {
                if (id in futureKits) { player.sendMessage(name(id)); return@event }
                val kit = KitService.getKitData(id) ?: return@event
                KitService.playerSelectKit(player, kit)
                player.sendMessage(lang.t("messages.kits.select.success") { "kitId" to id })
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, .3f, 2f)
            }
        }
        event<EntityCombustEvent>(priority = EventPriority.HIGHEST) { if (kitId(entity) != null) isCancelled = true }
        event<EntityTransformEvent>(priority = EventPriority.HIGHEST) { if (kitId(entity) != null) isCancelled = true }
        event<EntityDropItemEvent>(priority = EventPriority.HIGHEST) {
            if (kitId(entity) != null) isCancelled = true
        }
        event<org.bukkit.event.world.EntitiesLoadEvent> {
            val loaded = entities.toList()
            // Repair saved selectors after their chunk finishes loading and the hub is registered.
            Bukkit.getScheduler().runTask(plugin, Runnable { settleSelectors(loaded) })
        }
        event<PlayerChangedWorldEvent> { if (isBuilding(player)) finish(player) }
        event<PlayerQuitEvent> {
            selected.remove(player.uniqueId); facing.remove(player.uniqueId)
            if (builders.remove(player.uniqueId)) {
                player.gameMode = GameMode.SURVIVAL
                player.inventory.clear()
                player.world.save()
                LobbyEdits.mark()
            }
        }
    }
}

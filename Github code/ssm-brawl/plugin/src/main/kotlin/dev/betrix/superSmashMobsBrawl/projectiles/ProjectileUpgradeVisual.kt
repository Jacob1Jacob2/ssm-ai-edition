package dev.betrix.superSmashMobsBrawl.projectiles

import dev.betrix.superSmashMobsBrawl.items.ItemService
import dev.betrix.superSmashMobsBrawl.services.ResourcePackService
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

/** How big the picture of an enlarged thrown item is drawn, with nothing of the server in it. */
object ProjectileSprite {
    /** A thrown item's picture is half a block wide at its normal size. */
    const val WIDTH = 0.5
    /** The widest the picture gets, as a share of its distance from the thrower's eyes (about 15 degrees of the view). */
    const val FILL = 0.26

    /**
     * The size to draw a picture of size [full] at, [away] blocks from the eyes of whoever threw
     * it: its full size once it has room, less right in front of them, never less than an
     * ordinary egg (or than [full], if that is smaller still).
     */
    fun scale(full: Double, away: Double): Double = minOf(full, maxOf(1.0, FILL * away / WIDTH))
}

/** Vanilla projectile geometry, scaled without changing its travel or collision entity. */
internal object ProjectileUpgradeVisual {
    fun create(entity: Entity, scale: Double): ItemDisplay? {
        if (scale == 1.0) return null
        when (entity) {
            is LivingEntity -> entity.getAttribute(Attribute.SCALE)?.let { it.baseValue *= scale }
            is ItemDisplay -> entity.transformation = entity.transformation.also { it.scale.mul(scale.toFloat()) }
        }
        if (entity !is Arrow && entity !is ThrowableProjectile) return null
        val display = entity.world.spawn(entity.location, ItemDisplay::class.java) {
            it.isVisibleByDefault = false
            it.isPersistent = false
            it.setGravity(false)
            it.itemDisplayTransform = if (entity is Arrow) ItemDisplay.ItemDisplayTransform.NONE else ItemDisplay.ItemDisplayTransform.GROUND
            it.billboard = if (entity is Arrow) Display.Billboard.FIXED else Display.Billboard.CENTER
            it.teleportDuration = 1
            it.setItemStack(if (entity is ThrowableProjectile) entity.item.clone() else ItemStack.of(Material.ARROW).apply {
                editMeta { meta -> meta.itemModel = NamespacedKey("ssm", "upgraded_arrow") }
            })
        }
        update(display, entity, scale)
        val late = HashSet<java.util.UUID>()
        entity.world.players.filter { entity !is Arrow || ResourcePackService.hasPack(it) }.forEach {
            it.hideEntity(ItemService.plugin, entity)
            if (entity is ThrowableProjectile && inFace(it, entity.location)) late += it.uniqueId
            else it.showEntity(ItemService.plugin, display)
        }
        if (late.isNotEmpty()) unseen[display] = late
        return display
    }

    /**
     * A thrown egg or snowball leaves from just in front of the thrower's eyes, and the game does
     * not draw its own for the first moment of the flight for exactly that reason. The enlarged
     * picture is a separate entity the game knows nothing about, so it was drawn from the first
     * tick: a sprite bigger than the screen, in the face of whoever threw it, once for every egg.
     * So it is shown to a player only when it is [NEAR] blocks from their eyes (one tick into an
     * egg's flight), or has been flying for [LATEST] ticks. And for the first few blocks it is
     * drawn no bigger than [ProjectileSprite.FILL] of its distance from the thrower's eyes, so
     * what the thrower sees of a big egg is a big egg and not a wall. Nothing about the flight
     * itself changes.
     */
    private const val NEAR = 1.6
    private const val LATEST = 6
    private val unseen = java.util.WeakHashMap<ItemDisplay, MutableSet<java.util.UUID>>()

    private fun inFace(viewer: Player, at: Location) =
        viewer.world == at.world && viewer.eyeLocation.distanceSquared(at) < NEAR * NEAR

    fun update(display: ItemDisplay, arrow: Entity, scale: Double, direction: org.bukkit.util.Vector = arrow.velocity) {
        if (arrow !is Arrow) {
            val at = arrow.location
            val thrower = ((arrow as? Projectile)?.shooter as? Player)?.takeIf { it.isOnline && it.world == at.world }
            val drawn = if (thrower == null) scale else ProjectileSprite.scale(scale, thrower.eyeLocation.distance(at))
            display.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(drawn.toFloat()), Quaternionf())
            display.teleport(arrow.location.apply { yaw = 0f; pitch = 0f })
            val late = unseen[display] ?: return
            late.removeIf { id ->
                val viewer = Bukkit.getPlayer(id)?.takeIf { it.isOnline } ?: return@removeIf true
                if (arrow.ticksLived < LATEST && inFace(viewer, at)) return@removeIf false
                viewer.showEntity(ItemService.plugin, display)
                if (dev.betrix.superSmashMobsBrawl.bots.BotManager.debug && (arrow as? Projectile)?.shooter == viewer) {
                    val away = viewer.eyeLocation.distance(at)
                    ItemService.plugin.logger.info("[sprite] ${display.itemStack.type.name.lowercase()} x$scale shown to its thrower ${viewer.name} " +
                        "after ${arrow.ticksLived} ticks, ${"%.1f".format(away)} blocks away, drawn x${"%.2f".format(drawn)} " +
                        "(${"%.0f".format(100 * ProjectileSprite.WIDTH * drawn / away)}% of its distance wide)")
                }
                true
            }
            if (late.isEmpty()) unseen.remove(display)
            return
        }
        val v = direction
        if (v.lengthSquared() < 0.000001) return
        val rotation = Quaternionf().rotationTo(Vector3f(1f, 0f, 0f), Vector3f(v.x.toFloat(), v.y.toFloat(), v.z.toFloat()).normalize())
        display.transformation = Transformation(Vector3f(), rotation, Vector3f((0.9 * scale).toFloat()), Quaternionf())
        display.teleport(arrow.location.apply { yaw = 0f; pitch = 0f })
    }

    fun addAssets(files: MutableMap<String, ByteArray>) {
        // Entity textures are not stitched into the item atlas automatically.
        files["assets/ssm/textures/item/upgraded_arrow.png"] = requireNotNull(
            ProjectileUpgradeVisual::class.java.getResourceAsStream("/pack/arrow.png")).use { it.readBytes() }
        // Geometry/UV proportions from the 26.3 client's ArrowModel: two crossed
        // 16x4 planes, a 4x4 tail, and the vanilla 0.9 model scale applied above.
        files["assets/ssm/items/upgraded_arrow.json"] = """{"model":{"type":"minecraft:model","model":"ssm:item/upgraded_arrow"}}""".toByteArray()
        files["assets/ssm/models/item/upgraded_arrow.json"] = """{
          "textures":{"arrow":"ssm:item/upgraded_arrow","particle":"minecraft:item/arrow"},
          "elements":[
            {"from":[-4,6,8],"to":[12,10,8],"shade":false,"rotation":{"origin":[8,8,8],"axis":"x","angle":45},"faces":{"north":{"uv":[0,0,8,2.5],"texture":"#arrow"},"south":{"uv":[8,0,0,2.5],"texture":"#arrow"}}},
            {"from":[-4,8,6],"to":[12,8,10],"shade":false,"rotation":{"origin":[8,8,8],"axis":"x","angle":45},"faces":{"up":{"uv":[0,0,8,2.5],"texture":"#arrow"},"down":{"uv":[8,0,0,2.5],"texture":"#arrow"}}},
            {"from":[-3,6,6],"to":[-3,10,10],"shade":false,"rotation":{"origin":[-3,8,8],"axis":"x","angle":45},"faces":{"east":{"uv":[2.5,2.5,5,5],"texture":"#arrow"},"west":{"uv":[5,2.5,2.5,5],"texture":"#arrow"}}}
          ]} """.toByteArray()
    }
}

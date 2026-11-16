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
        entity.world.players.filter { entity !is Arrow || ResourcePackService.hasPack(it) }.forEach {
            it.hideEntity(ItemService.plugin, entity)
            it.showEntity(ItemService.plugin, display)
        }
        return display
    }

    fun update(display: ItemDisplay, arrow: Entity, scale: Double, direction: org.bukkit.util.Vector = arrow.velocity) {
        if (arrow !is Arrow) {
            display.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(scale.toFloat()), Quaternionf())
            display.teleport(arrow.location.apply { yaw = 0f; pitch = 0f })
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

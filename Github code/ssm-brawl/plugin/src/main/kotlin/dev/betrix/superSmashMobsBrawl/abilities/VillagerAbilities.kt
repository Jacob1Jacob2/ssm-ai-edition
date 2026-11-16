package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.disguises.VillagerDisguise
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import gg.flyte.twilight.event.event
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import kotlin.random.Random

enum class VillagerArt(val damage: Double, val armor: Double, val speed: Float, val dye: Material) {
    NORMAL(6.0, 5.0, 0.2f, Material.WHITE_DYE),
    BUTCHER(7.0, 4.5, 0.2f, Material.RED_DYE),
    BLACKSMITH(5.0, 7.5, 0.17f, Material.GRAY_DYE),
    SPEEDSTER(4.0, 4.0, 0.3f, Material.LIME_DYE);
    val title get() = when(this) {NORMAL->"Normal";BUTCHER->"Attack";BLACKSMITH->"Defense";SPEEDSTER->"Speed"}
}

class VillagerArtsAbility(player: Player) : ClassicKitAbility("villager_arts", player) {
    var activeArt = VillagerArt.NORMAL
        private set
    var preparedArt = VillagerArt.BUTCHER
        private set
    private val originalSpeed = player.walkSpeed
    private val readyAt=mutableMapOf<VillagerArt,Long>()
    private var generation=0

    override fun setup() {
        super.setup()
        indicator()
        listeners.add(event<PlayerDropItemEvent> {
            if (this.player != this@VillagerArtsAbility.player || !isCorrectItemForAbility(itemDrop.itemStack)) return@event
            isCancelled = true
            applyPreparedArt()
        })
    }

    override fun activate() {
        if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"villager:3"))return
        setCooldown()
        preparedArt = when (preparedArt) {
            VillagerArt.BUTCHER -> VillagerArt.BLACKSMITH
            VillagerArt.BLACKSMITH -> VillagerArt.SPEEDSTER
            else -> VillagerArt.BUTCHER
        }
        indicator()
        player.sendMessage(Component.text("Game> ", NamedTextColor.BLUE)
            .append(Component.text("Prepared ", NamedTextColor.GRAY))
            .append(Component.text(preparedArt.title, NamedTextColor.GREEN))
            .append(Component.text(". Press drop with the shovel to activate.", NamedTextColor.GRAY)))
        player.playSound(player.location, Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 1.2f)
    }

    fun applyPreparedArt() {
        if (player.gameMode != GameMode.SURVIVAL || game == null) return
        if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"villager:3"))return
        val now=System.currentTimeMillis()
        if(now<readyAt.getOrDefault(preparedArt,0L)) {
            player.sendMessage(Component.text("Recharge> ",NamedTextColor.BLUE).append(
                Component.text("${preparedArt.title} is still recharging.",NamedTextColor.GRAY)))
            return
        }
        dev.betrix.superSmashMobsBrawl.items.ItemService.beginCast(player, id)
        readyAt[preparedArt]=now+15000
        activeArt = preparedArt
        updateAppearance()
        val cast=++generation
        val duration = (200 * itemBoost).toInt()
        val color=when(activeArt) {VillagerArt.BUTCHER->Color.RED;VillagerArt.BLACKSMITH->Color.GRAY;else->Color.LIME}
        dev.betrix.superSmashMobsBrawl.utils.ClassicEffects.fireworkBurst(player.location.add(0.0,1.0,0.0),color)
        loop {
            if(cast!=generation)return@loop false
            if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"villager:3"))return@loop false
            player.exp=(1.0-it/duration.toDouble()).coerceIn(0.0,1.0).toFloat()
            if(it==duration-40 || it==duration-20)player.playSound(player.location,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1f,0.3f)
            if(it<duration)true else {
                activeArt=VillagerArt.NORMAL;updateAppearance()
                player.playSound(player.location,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1f,0.5f)
                false
            }
        }
        player.sendMessage(Component.text("Game> ", NamedTextColor.BLUE)
            .append(Component.text("You activated ", NamedTextColor.GRAY))
            .append(Component.text(activeArt.title, NamedTextColor.GREEN))
            .append(Component.text(".", NamedTextColor.GRAY)))
    }

    fun perfection() {
        generation++;activeArt=VillagerArt.NORMAL
        player.walkSpeed=VillagerArt.SPEEDSTER.speed
        player.inventory.armorContents=arrayOf(Material.DIAMOND_BOOTS,Material.DIAMOND_LEGGINGS,Material.IRON_CHESTPLATE,Material.AIR).map{ItemStack.of(it)}.toTypedArray()
        player.inventory.setItem(3,ItemStack.of(Material.MAGENTA_DYE).apply {editMeta {it.displayName(Component.text("Perfection",NamedTextColor.LIGHT_PURPLE))}})
    }
    private fun updateAppearance() {
        player.walkSpeed = activeArt.speed
        val armor = when (activeArt) {
            VillagerArt.NORMAL -> arrayOf(Material.CHAINMAIL_BOOTS, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_CHESTPLATE, Material.AIR)
            VillagerArt.BUTCHER -> arrayOf(Material.GOLDEN_BOOTS, Material.GOLDEN_LEGGINGS, Material.GOLDEN_CHESTPLATE, Material.AIR)
            VillagerArt.BLACKSMITH -> arrayOf(Material.DIAMOND_BOOTS, Material.DIAMOND_LEGGINGS, Material.IRON_CHESTPLATE, Material.AIR)
            VillagerArt.SPEEDSTER -> arrayOf(Material.AIR, Material.AIR, Material.DIAMOND_CHESTPLATE, Material.AIR)
        }
        player.inventory.armorContents = armor.map { ItemStack.of(it) }.toTypedArray()
        (player.disguise as? VillagerDisguise)?.setArt(activeArt)
        indicator()
    }

    private fun indicator() {
        player.inventory.setItem(3, ItemStack.of(activeArt.dye).apply {
            val meta = itemMeta
            meta.displayName(Component.text("${activeArt.title} Art", NamedTextColor.GREEN))
            meta.lore(listOf(Component.text("Prepared: ${preparedArt.title} â€” drop the shovel to activate", NamedTextColor.GRAY)))
            itemMeta = meta
        })
    }
    override fun teardown() {
        player.walkSpeed = originalSpeed
        player.inventory.setItem(3, null)
        super.teardown()
    }
}

class SonicHurrAbility(player: Player) : ClassicKitAbility("sonic_hurr", player) {
    override fun activate() { super.activate(); sonic() }
    fun sonic(multiplier:Double=1.0, extraRange:Double=0.0) {
        val at = player.eyeLocation
        val direction = at.direction
        player.world.playSound(at, Sound.ENTITY_VILLAGER_AMBIENT, 1.5f, 1.2f)
        player.world.spawnParticle(Particle.ANGRY_VILLAGER, at, 1, 0.3, 0.3, 0.3, 0.0)
        val range = upgradedValue("range", 6.0)*multiplier+extraRange
        targets(at, range).forEach { target ->
            val delta = target.eyeLocation.toVector().subtract(at.toVector())
            if (delta.lengthSquared() < 0.01 || delta.normalize().dot(direction) >= 0.55) {
                if (player.hasLineOfSight(target)) hit(target, (metadata.double("damage") ?: 6.0)*multiplier, 2.0*multiplier)
            }
        }
        val right=direction.clone().crossProduct(Vector(0,1,0)).let {if(it.lengthSquared()<.001)Vector(1,0,0)else it.normalize()}
        val up=right.clone().crossProduct(direction).normalize()
        for(step in 0..(range*12).toInt()) {
            val distance=step/12.0;val angle=distance*5
            val point=at.clone().add(direction.clone().multiply(distance))
                .add(right.clone().multiply(kotlin.math.cos(angle)*(.15+distance*.12)))
                .add(up.clone().multiply(kotlin.math.sin(angle)*(.15+distance*.12)))
            player.world.spawnParticle(Particle.CLOUD,point,1,0.0,0.0,0.0,0.0)
        }
    }
}

class TradeScatterAbility(player: Player) : ClassicKitAbility("trade_scatter", player) {
    override fun activate() {
        super.activate()
        val art = (ownerKit?.getAbility("villager_arts") as? VillagerArtsAbility)?.activeArt ?: VillagerArt.NORMAL
        val forward = player.eyeLocation.direction
        val movement = if (art == VillagerArt.BUTCHER || art == VillagerArt.BLACKSMITH) -0.9 else 1.1
        player.velocity = forward.clone().multiply(movement * itemBoost)
        val firing = forward.clone().multiply(if (movement > 0) -1.0 else 1.0)
        val material = when (art) {
            VillagerArt.BUTCHER -> Material.BEEF
            VillagerArt.BLACKSMITH -> Material.IRON_INGOT
            VillagerArt.SPEEDSTER -> Material.FEATHER
            else -> Material.EMERALD
        }
        val hits = mutableMapOf<java.util.UUID, Int>()
        player.world.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.5f, 1f)
        repeat(metadata.int("pellets") ?: 12) {
            var at = player.eyeLocation.add(firing)
            val velocity = firing.clone().add(Vector(Random.nextDouble(-0.3, 0.3), Random.nextDouble(-0.3, 0.3), Random.nextDouble(-0.3, 0.3))).normalize().multiply(itemBoost)
            val pellet = item(at, material)
            loop(finish = { remove(pellet) }) { age ->
                if (age >= 25) false
                else {
                    val collision = collision(at, velocity, 0.25)
                    if (collision != null) {
                        collision.second?.let { target ->
                            val count = hits.getOrDefault(target.uniqueId, 0)
                            if (count < 4 && hit(target, metadata.double("damagePerPellet") ?: 1.5, if (count == 0) 1.3 else 0.0, hitSound = false)) {
                                hits[target.uniqueId] = count + 1
                                player.playSound(player.eyeLocation,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,.7f,1.4f)
                            }
                        }
                        false
                    } else {
                        at = at.add(velocity)
                        pellet.teleport(at)
                        pellet.velocity = Vector()
                        val trail=when(art) {VillagerArt.BUTCHER->Particle.FLAME;VillagerArt.BLACKSMITH->Particle.FIREWORK;VillagerArt.SPEEDSTER->Particle.ENCHANT;else->Particle.HAPPY_VILLAGER}
                        at.world.spawnParticle(trail,at,1,0.0,0.0,0.0,0.0)
                        velocity.y -= 0.025
                        true
                    }
                }
            }
        }
    }
}

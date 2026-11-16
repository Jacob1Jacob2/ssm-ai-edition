package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.utils.PolarFishProgress
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

class PolarBearFishAbility(player: Player) : ClassicKitAbility("fish_gun", player) {
    val progress = PolarFishProgress()
    private class Volley(val salmon: Boolean, var remaining: Int, var hit: Boolean = false)
    private val volleys = ArrayDeque<Volley>()
    override val effectiveCooldown get() = progress.cooldown
    override fun setup() {
        super.setup()
        runnables.add(repeatingTask(1) {
            // The damage number above the bar belongs to CombatLogService, so only set its fill.
            if (ownerKit?.id == "polar_bear") player.exp = progress.progress
        })
    }
    fun startUltra() { volleys.clear(); progress.startUltra(); lastUsed = 0; player.exp = 0f }
    fun endUltra() { volleys.clear(); progress.endUltra(); player.exp = progress.progress }
    fun fireUltra(): Boolean {
        if (!progress.ultra || !canActivate(false)) return false
        activate(); return true
    }
    override fun activate() {
        super.activate()
        val salmon = progress.ultra
        val source = if (salmon) "Salmon Stream" else displayName
        val count = progress.fishCount
        val volley = Volley(salmon, count).also(volleys::addLast)
        fun resolved(hit: Boolean) {
            volley.hit = volley.hit || hit
            volley.remaining--
            // A far-away earlier volley can finish after a close later hit. Resolve in cast order.
            while (volleys.firstOrNull()?.remaining == 0) {
                val completed = volleys.removeFirst()
                progress.resolveVolley(completed.hit, completed.salmon)
            }
            player.exp = progress.progress
        }
        val direction = player.eyeLocation.direction.normalize()
        player.world.playSound(player.location, Sound.ENTITY_FISHING_BOBBER_THROW, .8f, 1.15f)
        repeat(count) { index ->
            // Same randomized 3D scatter as Ink Shotgun, with one guaranteed central projectile.
            val spread = if (index == 0) Vector() else Vector(Math.random() - .5, Math.random() - .5, Math.random() - .5).let {
                if (it.lengthSquared() < .000001) Vector(.15, 0.0, 0.0) else it.normalize().multiply(.15)
            }
            val velocity = direction.clone().add(spread).normalize().multiply(upgradedValue("projectileSpeed", 1.6))
            val fish = item(player.eyeLocation, if (salmon) Material.SALMON else Material.COD)
            var at = player.eyeLocation.clone()
            var done = false
            loop(finish = { remove(fish) }) { age ->
                if (done) return@loop false
                if (age >= 45 || at.y < at.world.minHeight) { resolved(false); return@loop false }
                val impact = collision(at, velocity, .2)
                if (impact != null) {
                    val hit = impact.second?.let { hit(it, metadata.double("damage") ?: 1.5, .65, at, sourceName = source) } == true
                    val firstHit = hit && !volley.hit
                    resolved(hit); done = true
                    if (hit) {
                        // Several pellets may hit, but a trigger pull can only earn one pickup.
                        if (firstHit) cooked(impact.first, salmon)
                        dev.betrix.superSmashMobsBrawl.utils.HitFeedback.play(player)
                        at.world.playSound(impact.first, Sound.BLOCK_FIRE_EXTINGUISH, .25f, 1.8f)
                    } else at.world.spawnParticle(Particle.ITEM, impact.first, 5, .1, .1, .1, .03, ItemStack.of(if (salmon) Material.SALMON else Material.COD))
                    return@loop false
                }
                at.add(velocity)
                fish.teleport(at)
                velocity.y-=.04
                true
            }
        }
    }
    private fun cooked(origin: Location, salmon: Boolean) {
        val food = origin.world.dropItem(origin, ItemStack.of(if (salmon) Material.COOKED_SALMON else Material.COOKED_COD)).apply {
            itemStack = itemStack.apply { editMeta { it.persistentDataContainer.set(NamespacedKey("ssm", "polar_fish"), org.bukkit.persistence.PersistentDataType.STRING, java.util.UUID.randomUUID().toString()) } }
            pickupDelay = Int.MAX_VALUE
            isPersistent = false
            owner = player.uniqueId
            setCanMobPickup(false)
            setCanPlayerPickup(false)
            velocity = Vector(0.0, .22, 0.0)
            setWillAge(false)
            ownedEntities.add(this)
        }
        loop(finish = { remove(food) }) { age ->
            if (!food.isValid || age >= 400 || food.location.y < food.world.minHeight) return@loop false
            if (age >= 6 && player.boundingBox.clone().expand(.65).overlaps(food.boundingBox)) {
                if (progress.collect(salmon)) {
                    player.exp = progress.progress
                    player.world.playSound(player.location, Sound.ENTITY_GENERIC_EAT, .8f, 1f)
                    player.world.spawnParticle(Particle.ITEM, player.eyeLocation, 8, .15, .1, .15, .02, food.itemStack)
                    return@loop false
                }
            }
            true
        }
    }
    override fun teardown() { super.teardown(); volleys.clear(); player.exp = 0f }
}

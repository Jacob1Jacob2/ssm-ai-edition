package dev.betrix.superSmashMobsBrawl.items

import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.smash.SmashService
import dev.betrix.superSmashMobsBrawl.smash.SmashEffect
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.events.*
import dev.betrix.superSmashMobsBrawl.services.KitService
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import net.kyori.adventure.text.Component
import org.joml.Vector3f
import org.joml.Quaternionf
import kotlin.math.*

class ItemArena(val game: BrawlMinigame) {
    /**
     * The match's world, looked up once. (A hit that wins the match ends it there and then, inside
     * the hit: the game no longer knows its world, and whatever the item still had to do in that
     * tick, a sound, the next player in the blast, used to stop on an error.)
     */
    val world: World by lazy { game.worldManager.getWorld()!!.world }
    /** The match is over and its world is on the way out: nothing more is to be done to it. */
    val over get() = game.worldManager.getWorld() == null
    val terrain get() = SmashService.arena(game)
    val effects = ItemEffects(this)
    var tick = 0L; private set
    private var nextDrop = game.settings.itemDelaySeconds().coerceAtLeast(1) * 20L
    private val active = mutableListOf<SmashEffect>()
    private val pending = mutableListOf<SmashEffect>()
    private val drops = mutableListOf<ItemDrop>()
    private val pickupSlots by lazy {
        game.worldManager.getWorld()!!.data.itemSpawnPoints.map {
            Location(world, floor(it.x) + .5, it.y + .35, floor(it.z) + .5)
        }.distinctBy { Triple(it.blockX, it.blockY, it.blockZ) }
    }
    private val entities = mutableSetOf<Entity>()
    private var stepping = false
    private var closed = false
    fun players() = game.allPlayers().mapNotNull { it.player }.filter { it.isOnline && it.world == world &&
        it.gameMode == GameMode.SURVIVAL && !game.respawnManager.isRespawning(it) && !game.connectionManager.isDisconnected(it) }
    fun enemies(owner: Player) = players().filter { it != owner && !game.arePlayersOnSameTeam(owner, it) }
    fun add(effect: SmashEffect) { if (stepping) pending += effect else active += effect }
    fun track(entity: Entity): Entity { entity.isPersistent = false; entities += entity; return entity }
    fun visual(at: Location, material: Material, scale: Float = 1f): ItemDisplay = world.spawn(at, ItemDisplay::class.java).also {
        track(it); it.setItemStack(ItemStack.of(material)); it.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
        it.teleportDuration = 1; it.interpolationDuration = 1
        it.transformation = org.bukkit.util.Transformation(Vector3f(), Quaternionf(), Vector3f(scale), Quaternionf())
    }
    /** The nearest place the map itself says there is ground: where an item can lie, someone can stand. */
    /** Well under anywhere the map says there is to stand: lower than this, someone is on their way out of the world. */
    val floorY: Double by lazy {
        ((pickupSlots.map { it.y } + game.worldManager.getWorld()?.data?.spawnPoints.orEmpty().map { it.y }).minOrNull() ?: world.minHeight.toDouble()) - 6.0
    }
    fun nearestStand(at: Location): Location? =
        (pickupSlots + game.worldManager.getWorld()?.data?.spawnPoints.orEmpty().map { Location(world, it.x, it.y, it.z) }).minByOrNull { it.distanceSquared(at) }
    /** Pickups that can be collected right now, for bots deciding whether to fetch one. */
    fun pickupLocations(): List<Location> = drops.filter { !it.expired() && it.age > 10 }.map { it.at }
    /** The same pickups with what each one is, for a bot that is after something in particular. */
    fun pickups(): List<Pair<ArenaItem, Location>> = drops.filter { !it.expired() && it.age > 10 }.map { it.kind to it.at }
    fun spawn(kind: ArenaItem, at: Location): Boolean {
        if (at.world != world) return false
        return spawnDrop(kind, at)
    }
    private fun spawnDrop(kind: ArenaItem, manualAt: Location? = null): Boolean {
        if (closed || manualAt == null && pickupSlots.isEmpty()) return false
        drops.removeIf { if (it.expired()) { it.close(); true } else false }
        // Command-spawned pickups share the map's slot budget but appear at the cursor.
        // Markerless test maps retain a small budget for manually spawned pickups.
        val capacity = pickupSlots.size.takeIf { it > 0 } ?: 16
        val slot = (0 until capacity).filter { index -> drops.none { it.slot == index } }.randomOrNull()
            ?: drops.maxBy { it.age }.let { oldest ->
                drops.remove(oldest); oldest.close(); oldest.slot
            }
        drops += ItemDrop(kind, (manualAt ?: pickupSlots[slot]).clone(), slot)
        return true
    }
    fun dropCount() = drops.size
    fun effectCount() = active.size + pending.size
    fun step() {
        if (closed) return
        tick++
        if (tick >= nextDrop) {
            if (game.settings.items) {
                // (A balance run can ask for every item equally often, to see enough of the rare ones.)
                val kind = if (game.settings.equalItemDrops) game.settings.enabledItems.randomOrNull() else ArenaItem.roll(game.settings.enabledItems)
                if (kind != null && spawnDrop(kind) && ItemStats.on) ItemStats.row(game, "drop", kind)
            }
            nextDrop = tick + game.settings.itemDelaySeconds().coerceAtLeast(1) * 20L
        }
        drops.removeIf { if (!it.step()) { it.close(); true } else false }
        stepping = true
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val effect = iterator.next()
            if (!effect.step()) { effect.close(); iterator.remove() }
            // That step ended the match (a hit that took the last life), and the match closed
            // this arena from inside it: nothing more is stepped, and the closing waits until here.
            if (closed) break
        }
        stepping = false
        if (closed) { shut(); return }
        active += pending; pending.clear()
        entities.removeIf { !it.isValid }
    }
    fun close() {
        if (closed) return
        closed = true
        // (Asked for from inside one of this arena's own effects: taking the effects away under
        // the feet of the loop that is going through them is what used to end in an error.)
        if (!stepping) shut()
    }
    private fun shut() {
        (active + pending).forEach { it.close() }; active.clear(); pending.clear()
        drops.forEach { it.close() }; drops.clear()
        entities.forEach { it.remove() }; entities.clear()
    }
    fun later(ticks: Int, action: () -> Unit) = add(object: SmashEffect {
        var age = 0
        override fun step(): Boolean { if (++age < ticks) return true; action(); return false }
    })
    fun loop(ticks: Int, every: Int = 1, finish: () -> Unit = {}, body: (Int) -> Boolean) = add(object: SmashEffect {
        var age = 0
        override fun step(): Boolean { if (age >= ticks) return false; val ok = age % every != 0 || body(age); age++; return ok }
        override fun close() = finish()
    })

    /** Damage percentages in the request are portions of a full health bar. */
    fun hit(owner: Player, target: Player, percent: Double, knockback: Double, origin: Location, kind: ArenaItem, self: Boolean = false): Boolean {
        if (target !in players() || (target == owner && !self) || target != owner && game.arePlayersOnSameTeam(owner, target)) return false
        val event = BrawlDamageEvent(target, Damager.DamagerLivingEntity(owner),
            (target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0) * percent / 100.0,
            damageType = BrawlDamageType.Projectile, sourceName = kind.label, automaticKnockback = false, ignoreArmor = true)
        if (percent > 0) event.callEvent()
        if (percent > 0 && event.appliedDamage <= 0) return false
        if (target in players() && knockback > 0 && KitService.getKitForPlayer(target)?.isInvincible() != true) {
            val direction = target.location.toVector().subtract(origin.toVector()).setY(0.0)
            if (direction.lengthSquared() < .001) direction.copy(owner.location.direction.setY(0.0))
            if (direction.lengthSquared() < .001) direction.z = 1.0
            target.velocity = direction.normalize().multiply(knockback).setY(min(1.5, .35 + knockback * .22))
            if (percent == 0.0) dev.betrix.superSmashMobsBrawl.services.CombatLogService.recordKnockback(target, owner, kind.label)
        }
        return true
    }
    @JvmOverloads fun blast(owner: Player, at: Location, radius: Double, percent: Double, kb: Double, kind: ArenaItem, self: Boolean = false, destroy: Boolean = true, maxBlocks: Int = Int.MAX_VALUE, selfScale: Double = 1.0, damageCap: Double = Double.POSITIVE_INFINITY) {
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, .8f)
        world.spawnParticle(Particle.EXPLOSION, at, 8, radius / 3, radius / 3, radius / 3, 0.0)
        players().filter { it.location.distanceSquared(at) <= radius * radius }.forEach {
            val falloff = (1.0 - it.location.distance(at) / (radius * 1.4)).coerceIn(.25, 1.0)
            val capped=min(percent,damageCap / (it.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0) * 100.0)
            hit(owner, it, capped * falloff * (if (it == owner) selfScale else 1.0), kb * falloff, at, kind, self)
        }
        if (destroy) destroy(at, radius, maxBlocks, if(kind==ArenaItem.ROCKET_LAUNCHER) 4f else .65f)
    }
    /** Takes a bite out of the map, unless the blast that would have has just ended the match. */
    fun destroy(at: Location, radius: Double, maxBlocks: Int = Int.MAX_VALUE, volume: Float = .65f) {
        if (!over) terrain.destroy(at, radius, maxBlocks, itemSound=true, itemSoundVolume=volume)
    }
    /** A swept collision test prevents fast rockets/volleys from tunnelling through fighters. */
    fun shot(owner: Player, kind: ArenaItem, at: Location = owner.eyeLocation, direction: Vector = owner.eyeLocation.direction,
             speed: Double = 1.4, homing: Boolean = false, material: Material = kind.material, life: Int = 100,
             gravity: Double = 0.0, drag: Double = 1.0, trail: Boolean = true,
             impact: (Location, Player?) -> Unit) {
        if (entities.size > 300) return
        val nativeFlight = kind == ArenaItem.RAIN_STAFF
        var pos = at.clone()
        var velocity = direction.clone().normalize().multiply(speed)
        val display: Entity = if (material == Material.ARROW) world.spawn(pos,Arrow::class.java) {
            it.shooter=owner; it.damage=0.0; it.isCritical=false; it.setGravity(false)
            it.pickupStatus=AbstractArrow.PickupStatus.DISALLOWED
            if(nativeFlight) it.velocity=velocity.clone()
            (it as org.bukkit.craftbukkit.entity.CraftArrow).handle.setNoPhysics(true)
        }.also { track(it) } else if (kind == ArenaItem.RAIN_STAFF) {
            val projectile: Projectile = when (material) {
                // A snowball with an egg item has the same thrown-egg appearance but no hatch behavior.
                Material.EGG -> world.spawn(pos,Snowball::class.java).also { it.item=ItemStack.of(Material.EGG) }
                Material.FIRE_CHARGE -> world.spawn(pos,SmallFireball::class.java).also { it.setIsIncendiary(false); it.yield=0f; it.acceleration=Vector() }
                else -> world.spawn(pos,Snowball::class.java)
            }
            projectile.shooter=owner;projectile.setGravity(false);projectile.velocity=velocity.clone();track(projectile)
        } else visual(pos, material, .8f).also {
            if (kind == ArenaItem.KNOCKBACK_ROD) it.setItemStack(ItemStack.empty())
        }
        val shotbow=kind==ArenaItem.SHOTBOW
        val enlarged=if(shotbow) dev.betrix.superSmashMobsBrawl.projectiles.ProjectileUpgradeVisual.create(display,2.0)?.also { track(it) } else null
        enlarged?.let { dev.betrix.superSmashMobsBrawl.projectiles.ProjectileUpgradeVisual.update(it,display,2.0,velocity) }
        loop(life, finish = { display.remove(); enlarged?.remove() }) {
            if (!display.isValid) false else {
                if (homing) enemies(owner).filterNot(ItemService::hidden).minByOrNull { it.location.distanceSquared(pos) }?.let { target ->
                    val aim = target.location.add(0.0, .8, 0.0).toVector().subtract(pos.toVector())
                    if (aim.lengthSquared() > .01) velocity = velocity.multiply(.86).add(aim.normalize().multiply(speed * .14)).normalize().multiply(speed)
                }
                if(nativeFlight) { pos=display.location; velocity=display.velocity }
                val distance=velocity.length()
                if(distance<.0001) return@loop false
                val unit = velocity.clone().normalize()
                val block = world.rayTraceBlocks(pos, unit, distance, FluidCollisionMode.NEVER, true)
                val hit = enemies(owner).mapNotNull { target ->
                    (target.disguise?.boundingBox ?: target.boundingBox).clone().expand(if(shotbow).5 else .25)
                        .rayTrace(pos.toVector(), unit, distance)?.let { target to it.hitPosition }
                }.minByOrNull { it.second.distanceSquared(pos.toVector()) }
                if (hit != null && (block == null || hit.second.distanceSquared(pos.toVector()) < block.hitPosition.distanceSquared(pos.toVector()))) {
                    impact(hit.second.toLocation(world), hit.first); false
                } else if (block != null) { impact(block.hitPosition.toLocation(world), null); false }
                else {
                    // Rain uses native motion and velocity packets, allowing the client to predict
                    // its path. Teleporting zero-velocity projectiles each tick caused stutter.
                    if(!nativeFlight) { pos.add(velocity); display.teleport(pos) }
                    if (display is Arrow && !nativeFlight) {
                        val yaw=Math.toDegrees(atan2(velocity.x,velocity.z)).toFloat()
                        val pitch=Math.toDegrees(atan2(velocity.y,sqrt(velocity.x*velocity.x+velocity.z*velocity.z))).toFloat()
                        display.setRotation(yaw,pitch)
                        enlarged?.let { dev.betrix.superSmashMobsBrawl.projectiles.ProjectileUpgradeVisual.update(it,display,2.0,velocity) }
                        display.velocity=Vector()
                        val handle=(display as org.bukkit.craftbukkit.entity.CraftArrow).handle
                        handle.yRotO=yaw; handle.xRotO=pitch
                    }
                    // A shot with weight (the Shotbow's arrows): slowed by the air, then pulled down, as an arrow is.
                    if (!nativeFlight && (gravity != 0.0 || drag != 1.0)) { velocity.multiply(drag); velocity.y -= gravity }
                    if (kind == ArenaItem.KNOCKBACK_ROD) world.spawnParticle(Particle.LARGE_SMOKE,pos,12,.16,.16,.16,.005)
                    if (trail) world.spawnParticle(if (kind in setOf(ArenaItem.FIRE_FLOWER, ArenaItem.ROCKET_LAUNCHER, ArenaItem.MISSILE_BATTERY)) Particle.FLAME else if (kind == ArenaItem.KNOCKBACK_ROD) Particle.SMOKE else Particle.CRIT,
                        pos, 2, .05, .05, .05, 0.0)
                    pos.y > world.minHeight && pos.y < world.maxHeight
                }
            }
        }
    }

    private inner class ItemDrop(val kind: ArenaItem, spawnAt: Location, val slot: Int) {
        var age = 0
        // Native Item entities use the client's normal dropped-item size, bob and rotation.
        // A hidden physics item anchors only tool upgrades, whose material varies by viewer.
        val personal = kind.tool || kind == ArenaItem.MONSTER_EGG
        val body = droppedItem(spawnAt, ItemPresentation.material(kind), visible = !personal, gravity = true)
        val at get() = body.location
        val views = mutableMapOf<java.util.UUID, Item>()
        val viewLabels = mutableMapOf<java.util.UUID, TextDisplay>()
        val label = world.spawn(at.clone().add(0.0, .8, 0.0), TextDisplay::class.java).also {
            track(it); it.text(Component.text(kind.label, kind.rarity.color)); it.billboard = Display.Billboard.CENTER
            it.teleportDuration=1; it.interpolationDuration=1
            it.isShadowed = true; it.backgroundColor = Color.fromARGB(0); it.viewRange = .7f
            // Tool name and appearance depend on the viewer's kit.
            if (kind.tool) it.isVisibleByDefault=false
        }
        fun expired() = age >= 7 * 60 * 20 || !body.isValid || at.y <= world.minHeight + 1
        fun step(): Boolean {
            age++
            if (expired()) return false
            views.entries.removeIf { (id, item) ->
                if (Bukkit.getPlayer(id)?.world != world) { item.remove();viewLabels.remove(id)?.remove(); true } else false
            }
            if (personal) world.players.forEach { viewer ->
                val adapted = ItemService.adapted(viewer, kind) ?: kind
                val material = ItemPresentation.material(adapted,viewer)
                val display = views.getOrPut(viewer.uniqueId) {
                    droppedItem(at, material, visible = false, gravity = false)
                        .also { viewer.showEntity(ItemService.plugin, it) }
                }
                if (display.itemStack.type != material) display.itemStack = ItemStack.of(material)
                if (display.location.distanceSquared(at) > .000001) display.teleport(at)
                display.velocity = Vector()
                if (kind.tool) {
                    val name=viewLabels.getOrPut(viewer.uniqueId) {
                        world.spawn(at.clone().add(0.0,.9,0.0),TextDisplay::class.java) {
                            it.isVisibleByDefault=false;it.billboard=Display.Billboard.CENTER
                            it.teleportDuration=1;it.isShadowed=true;it.backgroundColor=Color.fromARGB(0);it.viewRange=.7f
                        }.also { track(it);viewer.showEntity(ItemService.plugin,it) }
                    }
                    name.text(Component.text(adapted.label,kind.rarity.color));name.teleport(at.clone().add(0.0,.9,0.0))
                }
            }
            label.teleport(at.clone().add(0.0, .9, 0.0))
            if (age > 10) for (player in players()) {
                if (player.location.distanceSquared(at) < 2.25 && ItemService.give(player, kind)) return false
            }
            return true
        }
        private fun droppedItem(at: Location, material: Material, visible: Boolean, gravity: Boolean): Item =
            world.dropItem(at, ItemService.stack(kind).also { it.type=material }) {
                it.isVisibleByDefault = visible
                it.isPersistent = false
                it.isInvulnerable = true
                // The arena owns the seven-minute lifetime, not vanilla's five-minute timer.
                it.setUnlimitedLifetime(true)
                it.setGravity(gravity)
                // The infinite pickup-delay sentinel also prevents vanilla item merging.
                // Collection remains handled by ItemService, including automatic helmet equip.
                it.pickupDelay = Int.MAX_VALUE
                it.setCanPlayerPickup(false)
                it.setCanMobPickup(false)
                it.velocity = Vector()
            }.also { track(it) }

        fun close() { body.remove(); label.remove(); views.values.forEach { it.remove() }; views.clear();viewLabels.values.forEach { it.remove() };viewLabels.clear() }
    }
}

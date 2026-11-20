package dev.betrix.superSmashMobsBrawl.items

import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.smash.SmashEffect
import dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.*

/** Bounded, match-owned effects. All terrain changes use the arena's restoration journal. */
class ItemEffects(private val arena: ItemArena) {
    private val world get() = arena.world
    private val channeling = mutableSetOf<UUID>()
    private val raining = mutableSetOf<UUID>()
    private val rainSoundAt = mutableMapOf<UUID, Int>()
    /** Who is in a Cage Trap's cage right now. */
    private val caged = mutableSetOf<UUID>()
    fun isCaged(p: Player) = p.uniqueId in caged
    /** The Personal Rocket flight each rider is on: a second rocket lit in mid-flight takes over from the first. */
    private val rockets = mutableMapOf<UUID, Int>()
    private val debug get() = dev.betrix.superSmashMobsBrawl.bots.BotManager.debug
    private fun log(text: String) = ItemService.plugin.logger.info(text)
    private fun maxHealth(p: Player) = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
    private fun heal(p: Player, amount: Double) { p.health = (p.health + amount).coerceAtMost(maxHealth(p)) }
    private fun potion(p: Player, type: PotionEffectType, ticks: Int, amplifier: Int = 0) {
        val life = KitService.getKitForPlayer(p)
        val old = p.getPotionEffect(type)
        val applied = PotionEffect(type, ticks, amplifier, false, true)
        p.addPotionEffect(applied)
        arena.loop(ticks, finish = {
            val current = p.getPotionEffect(type)
            if (current?.amplifier == amplifier && current.duration <= ticks) p.removePotionEffect(type)
            if (p in arena.players() && KitService.getKitForPlayer(p) === life && life != null && old != null && old.duration > ticks)
                p.addPotionEffect(PotionEffect(type, old.duration - ticks, old.amplifier, old.isAmbient, old.hasParticles()))
        }) { p in arena.players() && KitService.getKitForPlayer(p) === life }
    }
    private fun aim(p: Player, range: Double = 24.0): Location {
        val trace = world.rayTraceBlocks(p.eyeLocation, p.eyeLocation.direction, range, FluidCollisionMode.NEVER, true)
        return trace?.hitPosition?.toLocation(world)?.add(0.0, .15, 0.0) ?: p.eyeLocation.add(p.eyeLocation.direction.multiply(range))
    }
    fun use(p: Player, kind: ArenaItem, target: Player?): Boolean {
        when (kind) {
            ArenaItem.DIAMOND_AXE, ArenaItem.DIAMOND_SHOVEL, ArenaItem.DIAMOND_SWORD, ArenaItem.DIAMOND_HELMET -> return false
            ArenaItem.SMASHER -> {
                val at=p.location.add(p.location.direction.setY(0.0).multiply(2.0))
                arena.blast(p,at,11.0,104.0,2.2,kind)
                fieryBurst(at,11.0); ignite(at,11)
                world.playSound(p.location,Sound.ENTITY_IRON_GOLEM_ATTACK,1f,.65f)
                repeat(30) { world.playSound(at,Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR,1f,1f) }
            }
            ArenaItem.HOMING_SNOWBALL -> {
                world.playSound(p.location, Sound.ENTITY_SNOWBALL_THROW, .7f, 1f)
                arena.shot(p, kind, homing = true, speed = .95) { at, hit -> hit?.let { arena.hit(p, it, ArenaItem.SNOWBALL_DAMAGE, 1.2, at, kind) } }
            }
            ArenaItem.SHOTBOW -> shotbow(p,1.0)
            ArenaItem.PLAYER_GRAPPLE -> return false // Native fishing hook and reel event own this item.
            ArenaItem.FIRE_FLOWER, ArenaItem.ROCKET_LAUNCHER, ArenaItem.MISSILE_BATTERY -> {
                val big = kind == ArenaItem.ROCKET_LAUNCHER
                world.playSound(p.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, if (big) .5f else 1.2f)
                arena.shot(p, kind, material = Material.FIRE_CHARGE, speed = if (big) 1.7 else 1.3) { at, hit ->
                    arena.blast(p, at, if (big) 10.0 else 2.5, if (big) 90.0 else 25.0, if (big) 2.8 else 1.0, kind,
                        self=big || kind == ArenaItem.MISSILE_BATTERY, selfScale=.5, destroy=kind != ArenaItem.FIRE_FLOWER)
                    if (big) fieryBurst(at,10.0)
                    if (big && hit != null) burn(p, hit, kind)
                    if (kind == ArenaItem.FIRE_FLOWER) ignite(at)
                }
            }
            ArenaItem.AIR_STRIKE -> thrown(p,kind,60) { at ->
                // One damage pulse; the many visible lightning strikes must not stack damage.
                arena.blast(p,at,6.0,97.5,2.8,kind,true,destroy=false,selfScale=.5)
                fieryBurst(at,6.0)
                repeat(32) { i -> arena.later(1 + i / 4) {
                    val angle=i*Math.PI*(3.0-sqrt(5.0)); val radius=5.5*sqrt(i/31.0)
                    val strike=at.clone().add(cos(angle)*radius,0.0,sin(angle)*radius)
                    world.strikeLightningEffect(strike)
                    arena.terrain.destroy(strike,1.6,itemSound=true)
                    world.spawnParticle(Particle.EXPLOSION,strike,8,.7,.6,.7,0.0)
                    ignite(strike,2)
                } }
            }
            ArenaItem.ICE_CUBE -> thrown(p, kind, 60) { at ->
                world.playSound(at, Sound.BLOCK_GLASS_BREAK, 1f, .7f)
                world.spawnParticle(Particle.BLOCK,at,150,2.0,1.0,2.0,.1,Material.ICE.createBlockData())
                world.spawnParticle(Particle.SNOWFLAKE,at,90,2.0,1.0,2.0,.08)
                arena.players().filter { it.location.distanceSquared(at) <= 16 }.forEach { victim ->
                    ItemService.freeze(victim, 200); potion(victim, PotionEffectType.SLOWNESS, 200, 2)
                    fun centered() = victim.location.apply { yaw=0f; pitch=0f }
                    val cube = world.spawn(centered(), BlockDisplay::class.java)
                    arena.track(cube); cube.block = Material.ICE.createBlockData(); cube.teleportDuration=1; cube.interpolationDuration=1
                    cube.transformation = org.bukkit.util.Transformation(org.joml.Vector3f(-.6f,0f,-.6f), org.joml.Quaternionf(), org.joml.Vector3f(1.2f, 1.7f, 1.2f), org.joml.Quaternionf())
                    arena.loop(200, finish = { cube.remove() }) {
                        if (victim !in arena.players() || !ItemService.frozen(victim)) false else { cube.teleport(centered()); true }
                    }
                }
            }
            ArenaItem.PROXIMITY_MINE -> thrown(p, kind, 60, waitForGround=true) { at -> mine(p, at, kind) }
            ArenaItem.CAGE_TRAP -> thrown(p, kind, 60, waitForGround=true) { at -> cageTrap(at) }
            ArenaItem.PERSONAL_ROCKET -> rocket(p)
            ArenaItem.ANVIL -> anvils(p)
            // In if out, out if in: the fuel is used up by the flying, not by the click.
            ArenaItem.FLYING_CAR -> return car(p)
            ArenaItem.MONSTER_EGG -> thrown(p, kind, 60) { at -> clone(p, at) }
            ArenaItem.CRAFTY_BOMB -> return false // Requires an actual clicked block face.
            ArenaItem.FRAG_GRENADE -> thrown(p, kind, 40) { arena.blast(p, it, 3.0, 30.0, 1.2, kind, true, maxBlocks=7, damageCap=6.0) }
            ArenaItem.CLUSTER_GRENADE -> thrown(p, kind, 60) { at ->
                repeat(5) { i ->
                    val direction = Vector(cos(i * Math.PI * 2 / 5)*.165, .24, sin(i * Math.PI * 2 / 5)*.165)
                    thrown(p, kind, 200, at, direction, Material.TNT, onLanding=true, throwSound=false) {
                        // Remove terrain in the same tick as the landing explosion.
                        arena.blast(p, it, 2.8, 25.875, 1.2, kind, true)
                    }
                }
            }
            ArenaItem.SINGULARITY_GRENADE, ArenaItem.SINGULARITY_NUKE -> thrown(p, kind, 60) { at -> singularity(p, at, kind) }
            ArenaItem.SPEED -> { potion(p, PotionEffectType.SPEED, 600, 1); world.playSound(p.location,Sound.ENTITY_GENERIC_EAT,1f,1f) }
            ArenaItem.INSTANT_TELEPORTER -> {
                var destination = p.location
                val direction = p.eyeLocation.direction
                for (i in 1..88) {
                    val at = p.location.add(direction.clone().multiply(i * .25))
                    if (at.y <= world.minHeight + 1 || at.y >= world.maxHeight - 2 || !at.block.isPassable || !at.clone().add(0.0,1.0,0.0).block.isPassable) break
                    destination = at
                }
                world.playSound(p.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
                world.spawnParticle(Particle.PORTAL,p.location.add(0.0,.8,0.0),80,.4,.7,.4,.4)
                p.teleport(destination); p.fallDistance = 0f
                world.spawnParticle(Particle.PORTAL,destination.clone().add(0.0,.8,0.0),80,.4,.7,.4,.4)
                world.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
            }
            ArenaItem.CLOUD_IN_A_BOTTLE -> cloud(p)
            ArenaItem.POCKET_TRAMPOLINE -> trampoline(p)
            ArenaItem.POISON_MIST -> thrown(p, kind, 60) { at -> mist(p, at) }
            // Swaps itself for the borrowed tool, so there is nothing left to use up afterwards.
            ArenaItem.MYSTERY_ABILITY -> return dev.betrix.superSmashMobsBrawl.abilities.MysteryAbility.grant(p)
            ArenaItem.JETPACK -> { p.velocity = p.velocity.setY(.9); p.fallDistance = 0f
                world.spawnParticle(Particle.LARGE_SMOKE, p.location, 20, .3, .1, .3, .03); world.playSound(p.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, .8f, 1.8f) }
            ArenaItem.PORKCHOP, ArenaItem.STEAK, ArenaItem.AWESOME_SOUP -> {
                heal(p,if (kind == ArenaItem.AWESOME_SOUP) maxHealth(p) else maxHealth(p)*kind.healPercent/100.0)
                if (kind == ArenaItem.AWESOME_SOUP) {
                    hearts(p); world.playSound(p.location,Sound.BLOCK_GRASS_BREAK,2f,1f)
                    potion(p,PotionEffectType.REGENERATION,40)
                }
                else {
                    world.spawnParticle(Particle.ITEM,p.eyeLocation,20,.2,.15,.2,.06,p.inventory.itemInMainHand.clone().also { it.amount=1 })
                    world.playSound(p.location,Sound.ENTITY_GENERIC_EAT,1f,1f)
                }
            }
            ArenaItem.GILDED_APPLE -> {
                ItemService.addAbsorption(p); hearts(p)
                world.playSound(p.location,Sound.ENTITY_GENERIC_EAT,1f,1f)
                world.playSound(p.location,Sound.ENTITY_PLAYER_BURP,.5f,1f)
            }
            ArenaItem.REGENERATE -> {
                if (KitService.getKitForPlayer(p)?.id == "magma_cube") { p.sendMessage("XP Fill does not work for Magma Cube."); return false }
                KitService.getKitForPlayer(p)?.refillEnergy(); world.spawnParticle(Particle.HAPPY_VILLAGER,p.location.add(0.0,1.0,0.0),40,.5,.8,.5,0.0); world.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .8f, 1f)
            }
            ArenaItem.EXTRA_LIFE -> {
                val team = arena.game.teamManager.findTeamOf(p) ?: return false
                val stocks = team.metadata["stocks"] as? Int ?: return false
                if (stocks >= MAX_LIVES) { extraLifeFailed(p, "You already have eight lives. You cannot have more."); return false }
                if (!channeling.add(p.uniqueId)) return false
                val life = KitService.getKitForPlayer(p)
                var previous = p.location
                var health = p.health
                arena.loop(101, finish = { channeling.remove(p.uniqueId) }) { age ->
                    if (p !in arena.players() || KitService.getKitForPlayer(p) !== life) false
                    else if (p.location.distanceSquared(previous) > .04 || p.health < health) {
                        extraLifeFailed(p, "You moved too quickly or took damage.")
                        false
                    } else {
                        previous = p.location; health = p.health
                        if (age == 100) {
                            team.metadata["stocks"] = min(MAX_LIVES, (team.metadata["stocks"] as? Int ?: stocks) + 1)
                            world.playSound(p.location,Sound.ITEM_TOTEM_USE,1f,1f)
                            world.spawnParticle(Particle.TOTEM_OF_UNDYING,p.location.add(0.0,1.0,0.0),120,.5,.8,.5,.25)
                            p.playEffect(EntityEffect.TOTEM_RESURRECT)
                        } else if (age % 20 == 0) dev.betrix.superSmashMobsBrawl.services.EnergyHud.message(p, net.kyori.adventure.text.Component.text("Extra Life: ${5 - age / 20}s", kind.rarity.color))
                        true
                    }
                }
            }
            ArenaItem.SHADOW_WALK -> { ItemService.hide(p, ArenaItem.SHADOW_WALK_TICKS);world.playSound(p.location,Sound.ENTITY_ENDERMAN_TELEPORT,1f,.7f); world.spawnParticle(Particle.LARGE_SMOKE, p.location, 20, .4, .7, .4, .02) }
            ArenaItem.FIRST_AID_KIT -> { heal(p, (maxHealth(p) - p.health) * .15); hearts(p); potion(p, PotionEffectType.REGENERATION, 400) }
            ArenaItem.RAIN_STAFF -> {
                rain(p)
                if (raining.add(p.uniqueId)) arena.loop(510, every = 2, finish = { raining.remove(p.uniqueId) }) { age ->
                    if (age == 0) true else if (p !in arena.players() || !p.isBlocking || ItemService.kind(p.inventory.itemInMainHand) != kind) false
                    else if (ItemService.consume(p, 1)) { rain(p); true } else false
                }
            }
            ArenaItem.KNOCKBACK_ROD -> {
                if (target != null) {
                    if (target !in arena.enemies(p) || target.location.distanceSquared(p.location) > 16) return false
                    arena.hit(p, target, 0.0, 2.8, p.location, kind)
                } else arena.shot(p, kind, material = Material.SNOWBALL, speed = 1.6) { at, hit -> hit?.let { arena.hit(p, it, 0.0, 2.3, at, kind) } }
                world.playSound(p.location, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, .8f, 1f)
            }
        }
        return true
    }

    private fun burn(owner: Player, target: Player, kind: ArenaItem) {
        target.fireTicks = 110
        arena.loop(101, every = 20, finish = { target.fireTicks = 0 }) { age ->
            if (target !in arena.players()) false else { if (age > 0) arena.hit(owner, target, 5.0, 0.0, target.location, kind); true }
        }
    }
    private fun ignite(at: Location, radius: Int = 2) {
        for (x in -radius..radius) for (z in -radius..radius) {
            if (x*x+z*z>radius*radius) continue
            val column=at.clone().add(x.toDouble(),2.0,z.toDouble())
            val ground=world.rayTraceBlocks(column,Vector(0,-1,0),12.0,FluidCollisionMode.NEVER,true) ?: continue
            val b=ground.hitBlock?.getRelative(org.bukkit.block.BlockFace.UP) ?: continue
            if (b.isEmpty && b.getRelative(0,-1,0).type.isSolid) {
                arena.terrain.changeBlock(b, Material.FIRE)
                arena.later(100) { if (b.type == Material.FIRE) b.setType(Material.AIR, false) }
            }
        }
    }
    private fun thrown(p: Player, kind: ArenaItem, fuse: Int, start: Location = p.eyeLocation,
                       initial: Vector = p.eyeLocation.direction.multiply(.7).add(Vector(0.0,.18,0.0)),
                       material: Material = ItemPresentation.material(kind,p), onLanding: Boolean = false,
                       throwSound: Boolean = true, waitForGround: Boolean = false, explode: (Location) -> Unit) {
        val body=world.dropItem(start,ItemStack.of(material)) {
            it.pickupDelay=Int.MAX_VALUE; it.setCanPlayerPickup(false); it.setCanMobPickup(false)
            it.isInvulnerable=true; it.isPersistent=false; it.velocity=initial
        }
        arena.track(body)
        if (throwSound) world.playSound(start,Sound.ENTITY_SNOWBALL_THROW,.6f,.8f)
        var age=0
        arena.add(object: SmashEffect {
            override fun step(): Boolean {
                if (!body.isValid || body.location.y < world.minHeight) return false
                age++
                if (if (onLanding) age > 1 && body.isOnGround else age >= fuse && (!waitForGround || body.isOnGround)) {
                    explode(body.location); return false
                }
                if (waitForGround && age >= 1200) return false
                if (onLanding && age >= fuse) return false // Clean up a child that never lands; no mid-air blast.
                if (kind==ArenaItem.AIR_STRIKE) {
                    val at=body.location
                    world.spawnParticle(Particle.CLOUD,at,5,.12,.1,.12,.03)
                    world.spawnParticle(Particle.FLAME,at,4,.12,.1,.12,.01)
                    if (age%10==0) world.playSound(at,Sound.BLOCK_NOTE_BLOCK_HAT,.8f,.7f+age/80f)
                } else if (age%10==0) world.spawnParticle(Particle.SMOKE,body.location,2,.05,.05,.05,.01)
                return true
            }
            override fun close() { body.remove() }
        })
    }
    private fun mine(owner: Player, at: Location, kind: ArenaItem) {
        val ground=world.rayTraceBlocks(at.clone().add(0.0,.2,0.0),Vector(0,-1,0),2.0,FluidCollisionMode.NEVER,true) ?: return
        val surface=ground.hitPosition.toLocation(world)
        val pos=Location(world,floor(surface.x),surface.y,floor(surface.z))
        // Render the placed, flat plate rather than leaving another dropped item behind.
        val body=world.spawn(pos,BlockDisplay::class.java) { it.block=Material.OAK_PRESSURE_PLATE.createBlockData() }
        arena.track(body)
        val trigger=pos.clone().add(.5,0.0,.5)
        world.playSound(pos,Sound.BLOCK_WOODEN_BUTTON_CLICK_ON,.7f,1.3f)
        arena.loop(1200,finish={body.remove()}) { age ->
            if(!body.isValid) false
            else if(age>5 && arena.players().any { it.location.distanceSquared(trigger)<1.8 }) {
                arena.blast(owner,trigger,4.0,80.0,1.8,kind,true);false
            } else true
        }
    }
    private fun clone(owner: Player, at: Location) {
        if (owner !in arena.players()) return
        val ranged=KitService.getKitForPlayer(owner)?.id in setOf("skeleton","zombie")
        val appearance = me.libraryaddict.disguise.DisguiseAPI.getDisguise(owner)?.clone()
            ?: me.libraryaddict.disguise.disguisetypes.PlayerDisguise(owner.name)
        appearance.watcher.isBurning=false; appearance.watcher.isInvisible=false
        appearance.watcher.itemInMainHand=ItemStack.of(if(ranged) Material.BOW else Material.IRON_SWORD)
        me.libraryaddict.disguise.DisguiseAPI.disguiseNextEntity(appearance)
        val mob = world.spawn(at, Zombie::class.java) { it.setShouldBurnInDay(false);it.fireTicks=0 }
        arena.track(mob); mob.setAdult(); mob.isSilent = true; mob.setShouldBurnInDay(false); mob.fireTicks=0; mob.removeWhenFarAway=false
        Bukkit.getMobGoals().removeAllGoals(mob)
        mob.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHealth(owner) * .2; mob.health = maxHealth(owner) * .2
        mob.equipment.setItemInMainHand(ItemStack.of(if(ranged) Material.BOW else Material.IRON_SWORD))
        mob.equipment.itemInMainHandDropChance = 0f
        ClassicSummons.owners[mob.uniqueId] = owner
        cloneOwners[mob.uniqueId] = owner
        arena.loop(Int.MAX_VALUE, finish = { cloneOwners.remove(mob.uniqueId); ClassicSummons.owners.remove(mob.uniqueId); mob.remove() }) { age ->
            if (!mob.isValid || mob.isDead) false else {
                val enemy = arena.enemies(owner).filterNot(ItemService::hidden).minByOrNull { it.location.distanceSquared(mob.location) }
                if (enemy != null) {
                    mob.fireTicks=0
                    if (!ranged || enemy.location.distanceSquared(mob.location)>100) mob.pathfinder.moveTo(enemy,1.2)
                    else mob.pathfinder.stopPathfinding()
                    if (age % (if(ranged) 25 else 16) == 0 && enemy.location.distanceSquared(mob.location) < (if(ranged) 400 else 7) && mob.hasLineOfSight(enemy)) {
                        mob.swingMainHand()
                        if (ranged) {
                            val origin=mob.eyeLocation
                            world.playSound(origin,Sound.ENTITY_ARROW_SHOOT,1f,1f)
                            arena.shot(owner,ArenaItem.MONSTER_EGG,origin,enemy.location.add(0.0,.8,0.0).toVector().subtract(origin.toVector()),1.6,material=Material.ARROW) { pos,hit ->
                                hit?.let { arena.hit(owner,it,15.0,.6,pos,ArenaItem.MONSTER_EGG) }
                            }
                        } else arena.hit(owner,enemy,15.0,.6,mob.location,ArenaItem.MONSTER_EGG)
                    }
                }
                true
            }
        }
    }
    private fun singularity(owner: Player, at: Location, kind: ArenaItem) {
        val nuke=kind==ArenaItem.SINGULARITY_NUKE
        val radius=if(nuke)12.0 else 6.0
        val blocks=mutableListOf<BlockDisplay>()
        val targets=mutableListOf<org.bukkit.block.Block>()
        val r=radius.toInt()
        for(x in -r..r) for(y in -r/2..r/2) for(z in -r..r) {
            if(x*x+y*y+z*z>radius*radius)continue
            val b=at.clone().add(x.toDouble(),y.toDouble(),z.toDouble()).block
            if(!b.isEmpty && b.type !in setOf(Material.BEDROCK,Material.BARRIER,Material.COMMAND_BLOCK))targets+=b
        }
        val intake=if(nuke)24 else 4
        val sampled=targets.shuffled().take(intake*20)
        // The vanilla portal/trigger.ogg is exactly 4.000 seconds at pitch 1.
        world.playSound(at,Sound.BLOCK_PORTAL_TRIGGER,1.5f,1f)
        arena.loop(80,finish={blocks.forEach { it.remove() }}) { age ->
            if(age%4==0) sampled.drop(age/4*intake).take(intake).forEach { b ->
                if(!b.isEmpty) {
                    val visual=world.spawn(b.location,BlockDisplay::class.java) {
                        it.block=b.blockData;it.teleportDuration=1;it.interpolationDuration=1
                    };arena.track(visual);blocks+=visual;arena.terrain.changeBlock(b,Material.AIR,false)
                }
            }
            arena.players().filter { it.location.distanceSquared(at)<radius*radius }.forEach {
                val delta=at.clone().add(0.0,1.0,0.0).toVector().subtract(it.location.toVector())
                if(delta.lengthSquared()>.2)it.velocity=delta.normalize().multiply(.45+age*.004)
            }
            blocks.forEachIndexed { i,b ->
                val angle=age*(if(nuke).85 else .55)+i*2.4
                val jitter=if(nuke).8 else .35
                val goal=at.clone().add(cos(angle)*(1.0+sin(age*.7+i)*jitter),1.0+sin(angle*1.7)*jitter,sin(angle)*(1.0+cos(age*.9+i)*jitter))
                b.teleport(b.location.add(goal.toVector().subtract(b.location.toVector()).multiply(.3)))
                val transform=b.transformation
                transform.leftRotation.rotateXYZ(.2f,.3f,if(nuke).4f else .15f);b.transformation=transform
            }
            world.spawnParticle(Particle.PORTAL,at,if(nuke)100 else 35,1.5,1.5,1.5,.5)
            if(nuke) { world.spawnParticle(Particle.REVERSE_PORTAL,at,70,2.0,2.0,2.0,.2);world.spawnParticle(Particle.SMOKE,at,20,1.2,1.2,1.2,.1) }
            true
        }
        arena.later(80) {
            val debris=blocks.map { it.block }.ifEmpty { listOf(Material.COBBLESTONE.createBlockData()) }
            repeat(if(nuke)100 else 40) { i ->
                dev.betrix.superSmashMobsBrawl.utils.TerrainDebris.toss(arena.terrain,at.clone().add(0.0,1.0,0.0),debris[i%debris.size],Vector(
                    kotlin.random.Random.nextDouble(-1.2,1.2),kotlin.random.Random.nextDouble(.7,1.8),kotlin.random.Random.nextDouble(-1.2,1.2)))
            }
            arena.blast(owner,at,radius,if(nuke)75.0 else 65.0,3.2,kind,true,maxBlocks=if(nuke)120 else 60)
        }
    }
    private fun cloud(p: Player) {
        val center = p.location.clone().subtract(0.0, 1.0, 0.0).block
        val blocks = mutableListOf<org.bukkit.block.Block>()
        for (x in -2..2) for (z in -2..2) {
            val block = center.getRelative(x,0,z)
            if (block.isEmpty) { arena.terrain.changeBlock(block, Material.SNOW_BLOCK); blocks += block }
        }
        p.fallDistance = 0f; p.velocity = p.velocity.setY(max(0.0, p.velocity.y))
        blocks.shuffled().forEachIndexed { i, block -> arena.later(100 + i * 2) {
            if (block.type == Material.SNOW_BLOCK) { world.spawnParticle(Particle.CLOUD, block.location.add(.5,.5,.5), 5, .3,.1,.3,.01); block.setType(Material.AIR, false) }
        } }
        world.playSound(p.location, Sound.BLOCK_SNOW_BREAK, 1f, 1f)
    }
    /** A 3x3 slime pad in the free blocks right under the feet, and a launch straight up. */
    private fun trampoline(p: Player) {
        val center = p.location.clone().subtract(0.0, 1.0, 0.0).block
        val blocks = mutableListOf<org.bukkit.block.Block>()
        for (x in -1..1) for (z in -1..1) {
            val block = center.getRelative(x,0,z)
            if (block.isEmpty) { arena.terrain.changeBlock(block, Material.SLIME_BLOCK); blocks += block }
        }
        p.fallDistance = 0f; p.velocity = p.velocity.setY(TRAMPOLINE_LAUNCH)
        blocks.shuffled().forEachIndexed { i, block -> arena.later(100 + i * 2) {
            if (block.type == Material.SLIME_BLOCK) {
                world.spawnParticle(Particle.ITEM_SLIME, block.location.add(.5,.5,.5), 8, .3,.3,.3,.01); block.setType(Material.AIR, false)
            }
        } }
        world.spawnParticle(Particle.ITEM_SLIME, p.location, 30, .8, .1, .8, .05)
        world.playSound(p.location, Sound.BLOCK_SLIME_BLOCK_PLACE, 1f, 1f)
        world.playSound(p.location, Sound.ENTITY_SLIME_JUMP, 1f, .8f)
    }
    /** A cloud that lingers for five seconds. Everyone in it, the thrower too, gets Poison II once, for six seconds. */
    private fun mist(owner: Player, at: Location) {
        val poisoned = mutableSetOf<UUID>()
        val green = Particle.DustOptions(Color.fromRGB(96, 190, 60), 1.8f)
        world.playSound(at, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1.2f, .8f)
        world.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, .6f, .6f)
        arena.loop(MIST_TICKS) { age ->
            if (age % 3 == 0) {
                world.spawnParticle(Particle.DUST, at.clone().add(0.0, .9, 0.0), 45, MIST_RADIUS * .55, .9, MIST_RADIUS * .55, 0.0, green)
                world.spawnParticle(Particle.SNEEZE, at.clone().add(0.0, .6, 0.0), 6, MIST_RADIUS * .5, .6, MIST_RADIUS * .5, .01)
            }
            if (age % 20 == 0) world.playSound(at, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, .5f, .7f)
            arena.players().filter { it.location.distanceSquared(at) <= MIST_RADIUS * MIST_RADIUS && poisoned.add(it.uniqueId) }.forEach { victim ->
                potion(victim, PotionEffectType.POISON, POISON_TICKS, 1)
                if (victim != owner) dev.betrix.superSmashMobsBrawl.services.CombatLogService.recordKnockback(victim, owner, ArenaItem.POISON_MIST.label)
            }
            true
        }
    }
    private fun rain(p: Player) {
        val target=aim(p)
        repeat(6) {
            val at=target.clone().add(kotlin.random.Random.nextDouble(-2.5,2.5),12.0,kotlin.random.Random.nextDouble(-2.5,2.5))
            arena.shot(p,ArenaItem.RAIN_STAFF,at,Vector(0,-1,0),1.2,
                material=listOf(Material.SNOWBALL,Material.FIRE_CHARGE,Material.ARROW,Material.EGG).random(),life=60) { pos,hit ->
                if (hit != null && arena.hit(p,hit,5.0,.25,pos,ArenaItem.RAIN_STAFF))
                    // The unpitched random/successful_hit.ogg sample requested for Rain Staff.
                    p.playSound(p.location,Sound.ENTITY_ARROW_HIT_PLAYER,1f,1f)
            }
        }
        world.playSound(p.location,Sound.BLOCK_AMETHYST_BLOCK_CHIME,.6f,.8f+kotlin.random.Random.nextFloat()*.4f)
        // Rain where it falls, and for the caster. A volley lands every two ticks; the sample is longer than that.
        val now=org.bukkit.Bukkit.getCurrentTick()
        if(now-(rainSoundAt[p.uniqueId] ?: -100)>=10) {
            rainSoundAt[p.uniqueId]=now
            world.playSound(target,Sound.WEATHER_RAIN,SoundCategory.PLAYERS,2.2f,1f)
            p.playSound(p.location,Sound.WEATHER_RAIN_ABOVE,SoundCategory.PLAYERS,.7f,1f)
        }
    }
    fun shotbow(p: Player, force: Double) {
        // The ordinary wood-hit event includes six samples. Restrict this item to the requested first four.
        val samples=(1..4).shuffled().take(2)
        world.players.filter { it.location.distanceSquared(p.location)<=1024 }.forEach { viewer ->
            samples.forEach {
                if (dev.betrix.superSmashMobsBrawl.services.ResourcePackService.hasPack(viewer))
                    viewer.playSound(p.location,"ssm:item.shotbow.wood$it",1f,1f)
                else viewer.playSound(p.location,Sound.BLOCK_WOOD_HIT,1f,1f)
            }
        }
        // All arrows in this volley share a per-target damage limit.
        val hitTargets = mutableSetOf<UUID>()
        // Where the volley came down, for the log: a bow half drawn must not reach as far as one fully drawn.
        val from = p.eyeLocation
        val landed = if (dev.betrix.superSmashMobsBrawl.bots.BotManager.debug) mutableListOf<Double>() else null
        if (landed != null) org.bukkit.Bukkit.getScheduler().runTaskLater(ItemService.plugin, Runnable {
            val reach = if (landed.isEmpty()) "none came down" else
                "${landed.size} of 16 came down %.1f to %.1f blocks out (%.1f on average)".format(landed.min(), landed.max(), landed.average())
            ItemService.plugin.logger.info("[shotbow] ${p.name} drawn %.0f%%: arrows leave at %.2f blocks a tick, $reach, hit ${hitTargets.size}, %.2f%% damage, trail ${ShotbowRules.trail(force)}"
                .format(force * 100, ShotbowRules.speed(force), ShotbowRules.damage(force)))
        }, 105L)
        repeat(16) {
            val forward=p.eyeLocation.direction
            val direction=forward.clone().add(Vector(kotlin.random.Random.nextDouble(-.16,.16),
                kotlin.random.Random.nextDouble(-.16,.16),kotlin.random.Random.nextDouble(-.16,.16))).normalize()
            arena.shot(p,ArenaItem.SHOTBOW,direction=direction,material=Material.ARROW,speed=ShotbowRules.speed(force),
                gravity=ShotbowRules.GRAVITY,drag=ShotbowRules.DRAG,trail=ShotbowRules.trail(force)) { at,hit ->
                landed?.add(at.toVector().setY(0).distance(from.toVector().setY(0)))
                if (hit != null && hitTargets.add(hit.uniqueId) && arena.hit(p,hit,ShotbowRules.damage(force),.25,at,ArenaItem.SHOTBOW))
                    // Let the client play its exact vanilla PvP bow-hit cue and pitch.
                    (p as org.bukkit.craftbukkit.entity.CraftPlayer).handle.connection.send(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                            net.minecraft.network.protocol.game.ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND,0f))
            }
        }
    }
    fun grapple(p:Player,victim:Player) {
        val destination=p.location.subtract(p.location.direction.setY(0.0).normalize().multiply(8.0))
        victim.velocity=destination.toVector().subtract(victim.location.toVector()).normalize().multiply(3.25).setY(1.69).multiply(1.5)
        dev.betrix.superSmashMobsBrawl.services.CombatLogService.recordKnockback(victim,p,ArenaItem.PLAYER_GRAPPLE.label)
        world.playSound(p.location,Sound.ENTITY_FISHING_BOBBER_RETRIEVE,1f,1f)
    }
    fun placeCrafty(p:Player,location:Location):Boolean {
        if(p !in arena.players())return false
        val at=location.block.location
        if(!at.block.isEmpty || !at.clone().subtract(0.0,1.0,0.0).block.type.isSolid)return false
        if((0..1).any { x -> (0..1).any { y -> (0..1).any { z -> !at.clone().add(x.toDouble(),y.toDouble(),z.toDouble()).block.isPassable } } })return false
        val visuals=mutableListOf<BlockDisplay>()
        fun block(x:Int,y:Int,z:Int) {
            val visual=world.spawn(at.clone().add(x.toDouble(),y.toDouble(),z.toDouble()),BlockDisplay::class.java) { it.block=Material.TNT.createBlockData() }
            arena.track(visual);visuals+=visual
        }
        block(0,0,0);world.playSound(at,Sound.BLOCK_GRASS_PLACE,1f,1f)
        arena.loop(60,finish={visuals.forEach { it.remove() }}) { age ->
            if(age==20) {block(1,0,0);block(0,0,1);block(1,0,1)}
            if(age==40)for(x in 0..1)for(z in 0..1)block(x,1,z)
            // Emit from the exposed top faces that exist now, including the initial single TNT.
            val width=if(age<20)1 else 2
            val top=if(age<40)1.02 else 2.02
            repeat(8) {
                val smokeAt=at.clone().add(kotlin.random.Random.nextDouble(.1,width-.1),top,
                    kotlin.random.Random.nextDouble(.1,width-.1))
                world.spawnParticle(Particle.LARGE_SMOKE,smokeAt,1,0.0,0.0,0.0,.01)
                if(it<6) world.spawnParticle(Particle.FLAME,smokeAt,1,0.0,0.0,0.0,.01)
            }
            true
        }
        arena.later(60) {
            val center=at.clone().add(1.0,1.0,1.0)
            // Restore the four cardinal arms without dealing several overlapping hits.
            val damaged=arena.players().filter { it.location.distanceSquared(center)<=16.0 }.map { it.uniqueId }.toMutableSet()
            arena.blast(p,center,4.0,55.0,2.2,ArenaItem.CRAFTY_BOMB,true)
            fieryBurst(center,4.0)
            for(direction in listOf(Vector(1,0,0),Vector(-1,0,0),Vector(0,0,1),Vector(0,0,-1))) {
                for(distance in 1..18) {
                    val blast=center.clone().subtract(0.0,.5,0.0).add(direction.clone().multiply(distance))
                    arena.terrain.destroy(blast,1.5,itemSound=true)
                    world.spawnParticle(Particle.EXPLOSION,blast,1)
                    world.spawnParticle(Particle.FLAME,blast,12,.6,.5,.6,.08)
                    arena.players().filter { it.location.distanceSquared(blast)<4.0 && damaged.add(it.uniqueId) }
                        .forEach { arena.hit(p,it,55.0,2.0,center,ArenaItem.CRAFTY_BOMB,true) }
                }
            }
        }
        return true
    }
    // ------------------------------------------------------------------ cage trap

    /** A trapdoor lying on the ground where the throw came to rest. Whoever walks over it is caged: its thrower too. */
    fun cageTrap(at: Location) {
        val ground=world.rayTraceBlocks(at.clone().add(0.0,.2,0.0),Vector(0,-1,0),2.0,FluidCollisionMode.NEVER,true)
            ?: run { if(debug) log("[cage] nothing under the trap at %.1f %.1f %.1f to set it on".format(at.x,at.y,at.z));return }
        val surface=ground.hitPosition.toLocation(world)
        val pos=Location(world,floor(surface.x),surface.y,floor(surface.z))
        val body=world.spawn(pos,BlockDisplay::class.java) { it.block=Material.OAK_TRAPDOOR.createBlockData() }
        arena.track(body)
        val trigger=pos.clone().add(.5,0.0,.5)
        world.playSound(pos,Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE,.8f,1.2f)
        if(debug) log("[cage] trap set at ${trigger.blockX} ${trigger.blockY} ${trigger.blockZ}")
        arena.loop(1200,finish={body.remove()}) { age ->
            if(!body.isValid) return@loop false
            val caught=if(age<=5) null else arena.players().filter { it.uniqueId !in caged }.filter {
                val l=it.location
                CageRules.steppedOn(l.x-trigger.x,l.y-trigger.y,l.z-trigger.z)
            }.minByOrNull { it.location.distanceSquared(trigger) }
            if(caught==null) true else { cage(caught);false }
        }
    }

    /**
     * Bars on four sides and a lid, of trapdoors, for [CageRules.HOLD_TICKS]. Whoever is inside
     * stays inside: the bars are real blocks, and anything that would carry them out (a teleport,
     * a blast that takes the bars away) puts them back in the middle.
     */
    fun cage(victim: Player) {
        if(!caged.add(victim.uniqueId)) return
        leaveCar(victim)
        val cell=victim.location.block
        val bars=mutableListOf<org.bukkit.block.BlockState>()
        fun bar(block: org.bukkit.block.Block, shape: (org.bukkit.block.data.type.TrapDoor) -> Unit) {
            // (Something solid there already is a bar as it is.)
            if(!block.isPassable || block.type==Material.OAK_TRAPDOOR) return
            val before=block.state
            arena.terrain.changeBlock(block,Material.OAK_TRAPDOOR,false)
            if(block.type!=Material.OAK_TRAPDOOR) return
            block.setBlockData((Material.OAK_TRAPDOOR.createBlockData() as org.bukkit.block.data.type.TrapDoor).also(shape),false)
            bars+=before
        }
        for((dx,dy,dz) in CageRules.BARS) bar(cell.getRelative(dx,dy,dz)) { door ->
            // The lid lies flat on top; a side stands open against the cell, on the edge of its block nearest to it.
            if(dy==2) door.isOpen=false
            else {
                door.isOpen=true
                door.facing=when { dx>0 -> org.bukkit.block.BlockFace.EAST;dx<0 -> org.bukkit.block.BlockFace.WEST
                    dz>0 -> org.bukkit.block.BlockFace.SOUTH;else -> org.bukkit.block.BlockFace.NORTH }
            }
        }
        // Caught with nothing under the feet (stepping off an edge): a floor as well.
        cell.getRelative(0,-1,0).takeIf { it.isPassable }?.let { under -> bar(under) { it.isOpen=false;it.half=org.bukkit.block.data.Bisected.Half.TOP } }
        val middle=Location(world,cell.x+.5,victim.location.y,cell.z+.5)
        fun hold() {
            victim.teleport(middle.clone().also { it.yaw=victim.location.yaw;it.pitch=victim.location.pitch })
            victim.velocity=Vector();victim.fallDistance=0f
        }
        hold()
        world.playSound(middle,Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE,1.4f,.6f)
        world.playSound(middle,Sound.BLOCK_CHAIN_PLACE,1.2f,.7f)
        world.spawnParticle(Particle.BLOCK,middle.clone().add(0.0,1.0,0.0),40,.5,.9,.5,.02,Material.OAK_TRAPDOOR.createBlockData())
        if(debug) log("[cage] ${victim.name} caged at ${cell.x} ${cell.y} ${cell.z} behind ${bars.size} bars: " +
            bars.joinToString(" ") { b -> val box=b.block.boundingBox
                "(${b.x-cell.x},${b.y-cell.y},${b.z-cell.z} x%.2f-%.2f y%.2f-%.2f z%.2f-%.2f)".format(box.minX-cell.x,box.maxX-cell.x,box.minY-cell.y,box.maxY-cell.y,box.minZ-cell.z,box.maxZ-cell.z) })
        arena.loop(CageRules.HOLD_TICKS,finish={
            caged.remove(victim.uniqueId)
            bars.forEach { before -> if(before.block.type==Material.OAK_TRAPDOOR) before.update(true,false) }
            world.playSound(middle,Sound.BLOCK_WOODEN_TRAPDOOR_OPEN,1.2f,.8f)
            if(debug) log("[cage] ${victim.name} out of the cage, %.2f blocks from its middle".format(if(victim.world==world) victim.location.distance(middle) else -1.0))
        }) {
            if(victim !in arena.players()) false else {
                val l=victim.location
                if(abs(l.x-middle.x)>.46 || abs(l.z-middle.z)>.46 || l.y<cell.y-.3 || l.y>cell.y+.8) hold()
                true
            }
        }
    }

    // ------------------------------------------------------------------ personal rocket

    /** Where the rocket goes: where its rider looks. (A bot looks where it is going: at the nearest opponent.) */
    private fun heading(p: Player, left: Double): Vector? {
        if(dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(p)) {
            val body=p.location.add(0.0,.9,0.0)
            // Not after someone who is on their way out of the world (under the map, with nothing
            // under them), nor after someone the rocket will not last as far as: either is a ride
            // out of the world too.
            fun onTheMap(e: Player)=e.location.y>=arena.floorY ||
                world.rayTraceBlocks(e.location.add(0.0,.3,0.0),Vector(0,-1,0),3.0,FluidCollisionMode.NEVER,true)!=null
            val prey=arena.enemies(p).filterNot(ItemService::hidden).filter { onTheMap(it) && it.location.distance(body)<=left+RocketRules.BLAST_RADIUS-2 }
                .minByOrNull { it.location.distanceSquared(body) }
            if(prey==null) {
                // Nobody left to ride it at: back over somewhere to stand, and let go of it there.
                val stand=arena.nearestStand(body) ?: return p.eyeLocation.direction
                val back=stand.clone().add(0.0,2.5,0.0).toVector().subtract(body.toVector())
                return if(hypot(back.x,back.z)<2.5 && back.y> -4.0 && back.y<2.0) null else back.normalize()
            }
            val gap=prey.location.add(0.0,.9,0.0).toVector().subtract(body.toVector()).takeIf { it.lengthSquared()>.01 } ?: return p.eyeLocation.direction
            val straight=gap.clone().normalize()
            // Something solid right in the way, and too far from them for the blast to reach: up and over it,
            // not into it. (Straight at a wall or the floor, the rocket would go off in its rider's own face.)
            val blocked=gap.length()>RocketRules.BLAST_RADIUS-2 &&
                world.rayTraceBlocks(body,straight,min(gap.length(),RocketRules.SPEED+2.5),FluidCollisionMode.NEVER,true)!=null
            return if(blocked) Vector(straight.x*.55,.85,straight.z*.55).normalize() else straight
        }
        return p.eyeLocation.direction
    }

    /**
     * The rider is the rocket: carried the way they look for as long as it burns, steering as they
     * go, and it goes off against the first thing in the way. The blast is not the rider's problem.
     */
    private fun rocket(p: Player) {
        val flight=(rockets[p.uniqueId] ?: 0)+1
        rockets[p.uniqueId]=flight
        leaveCar(p)
        world.playSound(p.location,Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,1.6f,.8f)
        var hit=false;var stalled=0;var last=p.location;var flown=0.0
        arena.loop(RocketRules.BURN_TICKS,finish={
            if(rockets[p.uniqueId]==flight) {
                rockets.remove(p.uniqueId)
                // Burnt out with nothing in the way: a pop, and whatever speed is left.
                if(!hit && p in arena.players()) {
                    world.playSound(p.location,Sound.ENTITY_FIREWORK_ROCKET_BLAST,1f,1.2f)
                    world.spawnParticle(Particle.FIREWORK,p.location.add(0.0,.9,0.0),40,.3,.3,.3,.12)
                    if(debug) log("[rocket] ${p.name} burnt out or let go after %.1f blocks with nothing in the way, at %.1f %.1f %.1f".format(flown,p.location.x,p.location.y,p.location.z))
                }
            }
        }) { age ->
            if(rockets[p.uniqueId]!=flight || p !in arena.players()) return@loop false
            val dir=heading(p,(RocketRules.BURN_TICKS-age)*RocketRules.SPEED) ?: return@loop false
            val here=p.location
            val body=here.clone().add(0.0,.9,0.0).toVector()
            val reach=RocketRules.SPEED+.5
            flown+=here.distance(last)
            // In the way this tick: an opponent, something solid ahead of the body or the head, or the ground dived into.
            val struck=arena.enemies(p).mapNotNull { e ->
                val box=(e.disguise?.boundingBox ?: e.boundingBox).clone().expand(.6)
                if(box.contains(body)) e to body.clone() else box.rayTrace(body,dir,reach)?.let { e to it.hitPosition }
            }.minByOrNull { it.second.distanceSquared(body) }
            val wall=listOf(.9,1.6).mapNotNull { world.rayTraceBlocks(here.clone().add(0.0,it,0.0),dir,reach,FluidCollisionMode.NEVER,true)?.hitPosition }
                .minByOrNull { it.distanceSquared(body) }
            val dived=age>=2 && dir.y< -.25 && (p as Entity).isOnGround
            if(age>=3 && here.distanceSquared(last)<.09) stalled++ else stalled=0
            last=here
            val impact=struck?.second ?: wall ?: here.toVector().takeIf { dived } ?: body.takeIf { stalled>=3 }
            if(impact!=null) {
                hit=true
                if(debug) log("[rocket] ${p.name} hit ${struck?.first?.name ?: "the map"} after %.1f blocks, $age ticks".format(flown))
                rocketBlast(p,impact.toLocation(world),dir)
                false
            } else {
                p.velocity=dir.clone().multiply(RocketRules.SPEED);p.fallDistance=0f
                world.spawnParticle(Particle.FIREWORK,here.clone().add(0.0,.6,0.0),3,.1,.1,.1,.03)
                world.spawnParticle(Particle.FLAME,here.clone().add(0.0,.4,0.0),2,.08,.08,.08,.01)
                true
            }
        }
    }
    private fun rocketBlast(p: Player, at: Location, dir: Vector) {
        val before=arena.enemies(p).associateWith { it.health }
        arena.blast(p,at,RocketRules.BLAST_RADIUS,RocketRules.BLAST_DAMAGE,RocketRules.BLAST_KNOCKBACK,ArenaItem.PERSONAL_ROCKET,destroy=false)
        arena.destroy(at,RocketRules.CRATER)
        fieryBurst(at,RocketRules.BLAST_RADIUS*.6)
        world.spawnParticle(Particle.FIREWORK,at,150,.6,.6,.6,.3)
        world.playSound(at,Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST,3f,.8f)
        world.playSound(at,Sound.ENTITY_FIREWORK_ROCKET_TWINKLE,2f,1f)
        // Thrown back off whatever it was, unhurt.
        val health=p.health
        p.velocity=dir.clone().multiply(-.45).setY(.5);p.fallDistance=0f
        if(debug) log("[rocket] blast at ${at.blockX} ${at.blockY} ${at.blockZ}: rider health %.1f -> %.1f, hurt ".format(health,p.health) +
            before.filter { (e,was) -> e.health<was || e !in arena.players() }.keys.joinToString { it.name }.ifEmpty { "nobody" })
    }

    // ------------------------------------------------------------------ anvil

    /** One anvil out of the sky for every opponent, on to where each of them is right now. Never for the user's own team. */
    private fun anvils(p: Player) {
        val struck=mutableSetOf<UUID>()
        val targets=arena.enemies(p)
        // With nobody to drop one on (the sandbox), one comes down where the player is looking, to show what it does.
        val spots=if(targets.isEmpty()) listOf(aim(p)) else targets.map { it.location }
        world.playSound(p.location,Sound.BLOCK_ANVIL_PLACE,1f,.6f)
        if(debug) log("[anvil] ${p.name} calls ${spots.size} down, on ${targets.joinToString { it.name }.ifEmpty { "nobody: where they are looking" }}")
        spots.forEach { anvil(p,it,struck) }
    }
    private fun anvil(owner: Player, over: Location, struck: MutableSet<UUID>) {
        // It comes down on the ground under them; on someone high in the air, on them.
        val ground=world.rayTraceBlocks(over.clone().add(0.0,.5,0.0),Vector(0,-1,0),6.5,FluidCollisionMode.NEVER,true)
        val land=Location(world,over.x,ground?.hitPosition?.y ?: over.y,over.z)
        // What it lands on, for what the landing throws up.
        val dirt=ground?.hitBlock?.blockData?.takeIf { !it.material.isAir } ?: Material.ANVIL.createBlockData()
        val size=AnvilRules.SCALE.toFloat()
        val body=world.spawn(land.clone().add(0.0,AnvilRules.HEIGHT,0.0),BlockDisplay::class.java) {
            it.block=Material.ANVIL.createBlockData();it.teleportDuration=1;it.interpolationDuration=1
            it.transformation=org.bukkit.util.Transformation(org.joml.Vector3f(-size/2,0f,-size/2),org.joml.Quaternionf(),org.joml.Vector3f(size),org.joml.Quaternionf())
        }
        arena.track(body)
        world.playSound(land,Sound.ENTITY_WITHER_SHOOT,1.2f,.5f)
        arena.loop(AnvilRules.FALL_TICKS+14,finish={body.remove()}) { age ->
            if(age<=AnvilRules.FALL_TICKS) {
                val at=land.clone().add(0.0,AnvilRules.heightAt(age),0.0)
                body.teleport(at)
                // A streak down the sky behind it.
                if(age<AnvilRules.FALL_TICKS) {
                    world.spawnParticle(Particle.CLOUD,at.clone().add(0.0,size*.9,0.0),8,size*.22,.5,size*.22,.02)
                    world.spawnParticle(Particle.LARGE_SMOKE,at.clone().add(0.0,size*.9,0.0),3,size*.18,.4,size*.18,.01)
                }
            }
            if(age==AnvilRules.FALL_TICKS) {
                val under=arena.enemies(owner).filter { val l=it.location;AnvilRules.lands(l.x-land.x,l.y-land.y,l.z-land.z) }
                if(debug) log("[anvil] landed at %.1f %.1f %.1f: under it ".format(land.x,land.y,land.z) +
                    under.joinToString { "${it.name} (%.1f blocks off)".format(hypot(it.location.x-land.x,it.location.z-land.z)) }.ifEmpty { "nobody" } +
                    arena.enemies(owner).minOfOrNull { hypot(it.location.x-land.x,it.location.z-land.z) }?.let { ", nearest opponent %.1f blocks off".format(it) }.orEmpty())
                anvilLanding(land,dirt)
                under.filter { struck.add(it.uniqueId) }.forEach { arena.hit(owner,it,AnvilRules.DAMAGE,1.0,land,ArenaItem.ANVIL) }
            }
            true
        }
    }
    /**
     * What five blocks of anvil arriving from the sky looks and sounds like: the clang, a thud under
     * it and a blast over it; iron and ground thrown up, sparks and smoke; a ring of dust running
     * out across the floor; and lumps of whatever it landed on flung out of the way. And the ground
     * it came down on is gone: a dish as wide as the anvil ([AnvilRules.CRATER]).
     */
    private fun anvilLanding(land: Location, dirt: org.bukkit.block.data.BlockData) {
        world.playSound(land,Sound.BLOCK_ANVIL_LAND,3f,.5f)
        world.playSound(land,Sound.ITEM_MACE_SMASH_GROUND_HEAVY,3f,.7f)
        world.playSound(land,Sound.ENTITY_GENERIC_EXPLODE,2f,.6f)
        arena.destroy(land.clone().add(0.0,AnvilRules.CRATER_LIFT,0.0),AnvilRules.CRATER)
        if(debug) log("[anvil] crater at %.1f %.1f %.1f: the block it landed on is now ${land.clone().subtract(0.0,.5,0.0).block.type}".format(land.x,land.y,land.z))
        val low=land.clone().add(0.0,.3,0.0)
        world.spawnParticle(Particle.EXPLOSION_EMITTER,land.clone().add(0.0,1.0,0.0),1)
        world.spawnParticle(Particle.BLOCK,low,160,1.6,.4,1.6,.2,Material.ANVIL.createBlockData())
        world.spawnParticle(Particle.BLOCK,low,160,2.0,.3,2.0,.2,dirt)
        world.spawnParticle(Particle.LAVA,low,20,1.5,.2,1.5,0.0)
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,low,24,1.6,.3,1.6,.04)
        arena.loop(AnvilRules.SHOCK_TICKS) { age ->
            val radius=AnvilRules.REACH*.5+(AnvilRules.SHOCK_REACH-AnvilRules.REACH*.5)*(age+1)/AnvilRules.SHOCK_TICKS
            val points=(radius*5).toInt().coerceAtLeast(12)
            repeat(points) { i ->
                val angle=i*2*Math.PI/points
                val at=land.clone().add(cos(angle)*radius,.15,sin(angle)*radius)
                world.spawnParticle(Particle.DUST_PILLAR,at,2,.1,.05,.1,0.0,dirt)
                if(i%3==0) world.spawnParticle(Particle.CLOUD,at,1,.05,.05,.05,.01)
            }
            true
        }
        if(!arena.over) repeat(8) { i ->
            val angle=i*2*Math.PI/8+kotlin.random.Random.nextDouble(-.3,.3)
            val out=kotlin.random.Random.nextDouble(.25,.5)
            dev.betrix.superSmashMobsBrawl.utils.TerrainDebris.toss(arena.terrain,land.clone().add(cos(angle)*1.8-.5,.4,sin(angle)*1.8-.5),dirt,
                Vector(cos(angle)*out,kotlin.random.Random.nextDouble(.45,.8),sin(angle)*out))
        }
    }

    // ------------------------------------------------------------------ flying car

    /** Which way a driver has the car going this tick. */
    private data class Keys(val yaw: Float, val forward: Boolean, val up: Boolean, val down: Boolean)

    /**
     * The Flying Car's one button: in if out, out if in. The item stays in the hand either way.
     *
     * The car is a minecart with its driver in it, and the minecart is itself carried: it rides a
     * display with nothing to show, which is what is actually moved. A minecart moved by the
     * server only tells the players where it is every third tick, and their games spend the next
     * five catching up with that, which is what made the first car lurch along; a display says
     * where it is every tick and is glided there over [CarRules.GLIDE_TICKS]. Being carried, the
     * minecart also has no heading of its own to fall back on, so it can be turned to face the way
     * its driver looks while it hangs still.
     */
    private fun car(p: Player): Boolean {
        if(driving(p)) { leaveCar(p);return true }
        if(p.isInsideVehicle || p.uniqueId in caged) return false
        val slot=p.inventory.heldItemSlot
        if(ItemService.uses(p.inventory.getItem(slot))<=0) {
            // An empty tank is not the end of the car: it fills again while it stands.
            dev.betrix.superSmashMobsBrawl.services.EnergyHud.message(p,net.kyori.adventure.text.Component.text("Flying Car: out of fuel. It regens while parked",net.kyori.adventure.text.format.NamedTextColor.RED))
            p.playSound(p.location,Sound.BLOCK_DISPENSER_FAIL,.8f,1.2f)
            return false
        }
        val home=p.location.takeIf { !it.clone().subtract(0.0,.3,0.0).block.isPassable }
        // (A hair off the floor, so that the car does not begin by touching what it stands on.)
        val start=p.location.add(0.0,.03,0.0).apply { yaw=0f;pitch=0f }
        val root=world.spawn(start,ItemDisplay::class.java) { it.teleportDuration=CarRules.GLIDE_TICKS;it.isPersistent=false }
        val cart=world.spawn(start,org.bukkit.entity.minecart.RideableMinecart::class.java) {
            it.setGravity(false);it.isInvulnerable=true;it.isPersistent=false;it.isSilent=true
            it.setRotation(p.location.yaw-90f,0f)
        }
        arena.track(root);arena.track(cart);carts+=cart.uniqueId
        val car=Car(root,cart,slot,home,start.toVector())
        if(!root.addPassenger(cart) || !cart.addPassenger(p)) { park(p,car);return false }
        cars[p.uniqueId]=car
        world.playSound(p.location,Sound.BLOCK_PISTON_EXTEND,.8f,1.4f)
        if(debug || !dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(p)) log("[car] ${p.name} in, ${ItemService.uses(p.inventory.getItem(slot))} seconds of fuel")
        arena.loop(Int.MAX_VALUE,finish={ park(p,car) }) { drive(p,car) }
        return true
    }
    /** Where a car at [at] is, for the purposes of what it runs into. */
    private fun carBox(at: Vector)=org.bukkit.util.BoundingBox(at.x-CarRules.HALF_WIDTH,at.y,at.z-CarRules.HALF_WIDTH,
        at.x+CarRules.HALF_WIDTH,at.y+CarRules.HEIGHT,at.z+CarRules.HALF_WIDTH)
    /**
     * Carries the car on by its speed. Nothing else would stop it at a wall (it is not pushed
     * along like a minecart on the ground, it is put where it is), so this does: one direction at
     * a time, as far as there is room, sliding along whatever it cannot go through.
     */
    private fun carry(car: Car) {
        val at=car.at
        fun free(spot: Vector)=!world.hasCollisionsIn(carBox(spot))
        // Got in against a wall, with the car's corner inside it: let it out, whichever way it goes.
        val inside=!free(at)
        for(axis in 0..2) {
            fun along(distance: Double)=when(axis) { 0 -> Vector(0.0,distance,0.0);1 -> Vector(distance,0.0,0.0);else -> Vector(0.0,0.0,distance) }
            var step=when(axis) { 0 -> car.speed.y;1 -> car.speed.x;else -> car.speed.z }
            if(step==0.0) continue
            if(!inside && !free(at.clone().add(along(step)))) {
                // As near to it as a quarter of the step gets, and no further that way.
                step=listOf(.75,.5,.25).map { step*it }.firstOrNull { free(at.clone().add(along(it))) } ?: 0.0
                when(axis) { 0 -> car.speed.y=0.0;1 -> car.speed.x=0.0;else -> car.speed.z=0.0 }
            }
            at.add(along(step))
        }
        (car.root as org.bukkit.craftbukkit.entity.CraftEntity).handle.setPos(at.x,at.y,at.z)
    }
    /**
     * Turns the car to face [yaw] and tells every player who can see it so there and then.
     *
     * The server would get round to telling them by itself, but only every third tick, and their
     * games then take three more to swing the minecart round: a car that trailed a quarter of a
     * second behind where its driver was looking, in steps. Told every tick, it follows the look.
     */
    private fun face(car: Car, yaw: Float) {
        car.cart.setRotation(yaw,0f)
        if(!explicitTurns) return
        val packed=net.minecraft.util.Mth.packDegrees(yaw)
        if(packed==car.facing) return
        car.facing=packed
        val packet=net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Rot(car.cart.entityId,packed,0.toByte(),false)
        car.cart.trackedBy.forEach { (it as org.bukkit.craftbukkit.entity.CraftPlayer).handle.connection.send(packet) }
        car.turnsSent++
    }
    /**
     * The noise of the car, for as long as it is going anywhere. It is played on the driver, so it
     * goes where the car goes (a sound played at a place is left behind in a second at this
     * speed, and one played on the minecart is not heard at all: the minecart is silent, or it
     * would add its own clatter on rails to this).
     */
    private fun hum(p: Player, car: Car) {
        val moving=car.speed.length()>=CarRules.HUM_SPEED
        if(CarRules.hums(moving,car.humming,arena.tick-car.hummed)) {
            if(debug && !car.humming) log("[car] ${p.name}'s car is on the move: its noise starts")
            world.playSound(p,HUM,org.bukkit.SoundCategory.PLAYERS,.6f,1.15f)
            car.humming=true;car.hummed=arena.tick;car.hums++
        } else if(!moving && car.humming) {
            if(debug) log("[car] ${p.name}'s car has stopped: its noise is cut off (started ${car.hums} times, ${CarRules.HUM_TICKS} ticks apart)")
            car.humming=false;car.hums=0;hush(p)
        }
    }
    private fun drive(p: Player, car: Car): Boolean {
        val cart=car.cart
        if(cars[p.uniqueId]!==car || car.parked || p !in arena.players() || !cart.isValid || !car.root.isValid || p.vehicle!=cart || cart.vehicle!=car.root) return false
        if(ItemService.kind(p.inventory.getItem(car.slot))!=ArenaItem.FLYING_CAR) return false
        // (A bot can be driven as a player is, by where it looks: how the tests check the path a player's car takes.)
        val bot=dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(p) && p.uniqueId !in handDriven
        // The sneak key takes the car down; it must not also be what it is in any other minecart, the way out.
        if(p.isSneaking) p.isSneaking=false
        val keys=if(bot) botKeys(p,car) ?: return false else p.currentInput.let { input ->
            // A and D swing the driver's view round, and the car with it (see CarSteering).
            CarSteering.steer(p,heldTurn[p.uniqueId] ?: CarRules.turnWanted(input.isLeft,input.isRight))
            Keys(p.location.yaw,input.isForward,input.isJump,input.isSneak)
        }
        val (x,y,z)=CarRules.velocity(keys.yaw,keys.forward,keys.up,keys.down)
        car.speed.x=CarRules.eased(car.speed.x,x);car.speed.y=CarRules.eased(car.speed.y,y);car.speed.z=CarRules.eased(car.speed.z,z)
        carry(car)
        // The car faces the way its driver does, whether it is going anywhere or not.
        face(car,keys.yaw-90f)
        hum(p,car)
        // How far the driver has looked round while the car stood, for the log: what it should have turned by.
        car.looked?.let { before -> if(car.speed.length()<CarRules.HUM_SPEED) car.turnedStill+=abs(net.minecraft.util.Mth.wrapDegrees(keys.yaw-before)) }
        car.looked=keys.yaw
        p.fallDistance=0f
        val going=car.speed
        if(going.length()>=CarRules.RAM_SPEED) {
            val box=carBox(car.at).expand(.45,.3,.45)
            arena.enemies(p).filter { (it.disguise?.boundingBox ?: it.boundingBox).overlaps(box) }.forEach { e ->
                if(arena.tick-(car.rammed[e.uniqueId] ?: -1000L)<CarRules.RAM_EVERY_TICKS) return@forEach
                car.rammed[e.uniqueId]=arena.tick
                if(arena.hit(p,e,CarRules.RAM_DAMAGE,CarRules.RAM_KNOCKBACK,car.at.toLocation(world),ArenaItem.FLYING_CAR)) {
                    world.playSound(car.at.toLocation(world),Sound.ENTITY_IRON_GOLEM_ATTACK,1f,1.3f)
                    world.spawnParticle(Particle.CRIT,e.location.add(0.0,1.0,0.0),20,.3,.4,.3,.3)
                    if(debug) log("[car] ${p.name} rammed ${e.name}")
                    // A bot does not sit on top of whoever it hit: on through, and round again.
                    if(bot) { car.passYaw=keys.yaw;car.passUntil=arena.tick+12 }
                }
            }
            if(arena.tick%2==0L) world.spawnParticle(Particle.CLOUD,car.at.toLocation(world).add(0.0,.2,0.0),1,.1,.05,.1,.01)
        }
        // A second in the car is a second off the fuel bar, moving or not.
        if(++car.burnt>=20) {
            car.burnt=0
            val left=ItemService.charge(p,car.slot,-1)
            // (A player's car leaves a line every five seconds whether or not anything is being
            // debugged: what the server saw of a drive is the only account of it there is afterwards.)
            if(debug || !bot && ++car.seconds%5==0) {
                val at=car.at.toLocation(world)
                val seat=p.location
                val (byKeys,steps)=CarSteering.taken(p.uniqueId)
                log("[car] ${p.name} at %.1f %.1f %.1f: %.1f blocks since the last line (keys: %s%s%s), driver %.2f from the car, cart facing %.0f for a look of %.0f, looked round %.0f degrees while it stood, %d turns sent to %d who see it, turned %.0f degrees by A and D in %d steps, fuel $left".format(
                    at.x,at.y,at.z,car.lastAt?.takeIf { it.world==at.world }?.distance(at) ?: 0.0,
                    if(keys.forward) "forward " else "",if(keys.up) "up " else "",if(keys.down) "down" else "",
                    hypot(seat.x-at.x,seat.z-at.z),cart.location.yaw,keys.yaw,car.turnedStill,car.turnsSent,cart.trackedBy.size,byKeys,steps))
                car.lastAt=at;car.turnedStill=0f;car.turnsSent=0
            }
            if(left<=0) {
                // The tank is dry: the car is gone, the item stays, and fills again while it stands.
                world.playSound(car.at.toLocation(world),Sound.BLOCK_FIRE_EXTINGUISH,.8f,.7f)
                dev.betrix.superSmashMobsBrawl.services.EnergyHud.message(p,net.kyori.adventure.text.Component.text("Flying Car: out of fuel. It regens while parked",net.kyori.adventure.text.format.NamedTextColor.RED))
                if(debug) log("[car] ${p.name} out of fuel at %.1f %.1f %.1f".format(car.at.x,car.at.y,car.at.z))
                return false
            }
        }
        return true
    }
    /**
     * A bot at the wheel: at the nearest opponent for as long as the fuel will still get it back,
     * then to where it got in (or the nearest place there is to stand), and out. Null to get out now.
     */
    private fun botKeys(p: Player, car: Car): Keys? {
        val at=car.at.toLocation(world)
        val moved=car.lastSpot?.takeIf { it.world==at.world }?.distance(at) ?: 1.0
        car.lastSpot=at.clone()
        if(arena.tick<car.passUntil) return Keys(car.passYaw,true,false,false)
        // Round whatever it ran into: sideways and up for a moment, then at them again.
        if(arena.tick<car.dodgeUntil) return Keys(car.dodgeYaw,true,true,false)
        val fuel=ItemService.uses(p.inventory.getItem(car.slot))
        val stand=car.home ?: arena.nearestStand(at)
        val wayBack=stand?.let { hypot(it.x-at.x,it.z-at.z)/(CarRules.FORWARD*20)+abs(it.y-at.y)/(CarRules.CLIMB*20)+2.0 } ?: 0.0
        val prey=arena.enemies(p).filterNot(ItemService::hidden).minByOrNull { it.location.distanceSquared(at) }
        val low=fuel<=wayBack
        val hunting=prey!=null && !low && !car.gaveUp
        val goal=(if(hunting) prey!!.location.add(0.0,.3,0.0) else stand) ?: return null
        val dx=goal.x-at.x;val dz=goal.z-at.z;val dy=goal.y-at.y
        val flat=hypot(dx,dz)
        if(!hunting && flat<1.2 && dy> -1.6 && dy<.8) {
            if(debug) log("[car] ${p.name} (bot) back where it can stand, getting out with $fuel seconds left" +
                if(car.gaveUp) ": no way to ${prey?.name} by car" else if(prey==null) ": nobody to chase" else "")
            // Too little left to go anywhere and come back: the rest is not worth keeping the hand full for.
            if(low && prey!=null) p.inventory.setItem(car.slot,null)
            return null
        }
        val keys=Keys(Math.toDegrees(atan2(-dx,dz)).toFloat(),flat>.5,dy>.35,dy< -.35)
        // Pressing on and getting nowhere: something is in the way.
        if((keys.forward || keys.up || keys.down) && moved<CarRules.STUCK_STEP) car.stuck++ else car.stuck=0
        if(moved>.3 && ++car.rolling>=60) { car.rolling=0;car.dodges=0 }
        if(car.stuck>=CarRules.STUCK_TICKS) {
            car.stuck=0
            if(++car.dodges>CarRules.DODGES) {
                // Round it every way there is and still not there: they are somewhere a car does not go.
                if(!hunting) { if(debug) log("[car] ${p.name} (bot) cannot get back to where it got in: out here");return null }
                car.gaveUp=true;car.dodges=0
                if(debug) log("[car] ${p.name} (bot) gives up on ${prey?.name}: going back")
            } else {
                car.dodgeYaw=keys.yaw+(if(car.dodges%2==1) 90f else -90f)
                car.dodgeUntil=arena.tick+CarRules.DODGE_TICKS
                if(debug) log("[car] ${p.name} (bot) blocked at %.1f %.1f %.1f: going round (${car.dodges})".format(at.x,at.y,at.z))
                return Keys(car.dodgeYaw,true,true,false)
            }
        }
        return keys
    }
    /** Out of the car, if in one. The item stays, with whatever fuel is left in it. */
    fun leaveCar(p: Player) { cars[p.uniqueId]?.let { park(p,it) } }

    private fun extraLifeFailed(p:Player, reason:String) {
        val message=net.kyori.adventure.text.Component.text("Extra Life failed! $reason", net.kyori.adventure.text.format.NamedTextColor.RED)
        p.sendMessage(net.kyori.adventure.text.Component.text("Item> ", net.kyori.adventure.text.format.NamedTextColor.BLUE).append(message))
        dev.betrix.superSmashMobsBrawl.services.EnergyHud.message(p,message)
        p.playSound(p.location,Sound.BLOCK_NOTE_BLOCK_BASS,1f,.5f)
    }
    private fun hearts(p:Player) { world.spawnParticle(Particle.HEART,p.location.add(0.0,1.1,0.0),12,.5,.7,.5,0.0) }
    private fun fieryBurst(at:Location,radius:Double) {
        world.spawnParticle(Particle.EXPLOSION,at,60,radius*.4,radius*.2,radius*.4,0.0)
        world.spawnParticle(Particle.FLAME,at,180,radius*.4,.8,radius*.4,.12)
        world.spawnParticle(Particle.LARGE_SMOKE,at,100,radius*.4,.8,radius*.4,.1)
    }
    /**
     * A Flying Car in the air: the display that carries it, its minecart, the hotbar slot its item
     * (and so its fuel) is in, where its driver got in, and where it is now.
     */
    class Car(val root: Display, val cart: Minecart, val slot: Int, val home: Location?, val at: Vector) {
        /** Blocks a tick, each way: what the keys ask for, come up to gradually. */
        val speed = Vector()
        /** Ticks flown since the last whole second came off the fuel. */
        var burnt = 0
        var parked = false
        /** A bot's run on through whoever it has just rammed: the heading it holds, and until when. */
        var passYaw = 0f
        var passUntil = 0L
        val rammed = mutableMapOf<UUID, Long>()
        /** Where it was a second ago, for the log. */
        var lastAt: Location? = null
        /** A bot's way round something solid: where the car was a tick ago, how long it has pressed on without moving, and its swerve. */
        var lastSpot: Location? = null
        var stuck = 0
        var rolling = 0
        var dodges = 0
        var dodgeYaw = 0f
        var dodgeUntil = 0L
        /** No way to the opponent by car: back to where it got in. */
        var gaveUp = false
        /** The way the minecart was last said to face, as it goes over the wire (256ths of a turn). */
        var facing: Byte? = null
        /** Whether the car's noise is on, and the tick it was last started. */
        var humming = false
        var hummed = 0L
        var hums = 0
        /** For the log: where the driver looked a tick ago, how far they have looked round while the car stood, and how often the car was turned for those who see it. */
        var looked: Float? = null
        var turnedStill = 0f
        var turnsSent = 0
        var seconds = 0
    }
    companion object {
        /** Every Flying Car being driven, by its driver; and their minecarts, which nothing may break or empty. */
        val cars = mutableMapOf<UUID, Car>()
        val carts = mutableSetOf<UUID>()
        fun driving(p: Player) = cars.containsKey(p.uniqueId)
        /** What a Flying Car on the move sounds like: a minecart on rails, a little higher. Two seconds long. */
        val HUM = Sound.ENTITY_MINECART_RIDING
        /** The car has stopped, or is gone: so has its noise, for everyone who heard it. */
        fun hush(p: Player) { if (p.isOnline) p.world.players.forEach { it.stopSound(HUM, org.bukkit.SoundCategory.PLAYERS) } }
        /** Debug: with this off, players learn which way a car faces only as the server gets round to it (see [face]). */
        var explicitTurns = true
        /** Debug: bots whose car goes by where they look and the keys they hold, as a player's does. */
        val handDriven = mutableSetOf<UUID>()
        /** Debug: degrees a second a hand-driven bot's turn keys ask for, in place of keys it does not have. */
        val heldTurn = mutableMapOf<UUID, Double>()
        /** The car is gone and its driver is where it was. (Safe to call twice.) */
        fun park(p: Player, car: Car) {
            if (car.parked) return
            car.parked = true
            cars.remove(p.uniqueId, car)
            carts.remove(car.cart.uniqueId)
            handDriven.remove(p.uniqueId)
            heldTurn.remove(p.uniqueId)
            CarSteering.release(p.uniqueId)
            if (car.cart.isValid) { car.cart.eject(); car.cart.remove() }
            car.root.remove()
            p.fallDistance = 0f
            if (car.humming) { car.humming = false; hush(p) }
            if (p.isOnline) p.world.playSound(p.location, Sound.BLOCK_PISTON_CONTRACT, .7f, 1.4f)
            val bots = dev.betrix.superSmashMobsBrawl.bots.BotManager
            if (bots.debug || !bots.isBot(p)) ItemService.plugin.logger.info("[car] ${p.name} out")
        }
        val cloneOwners = mutableMapOf<UUID, Player>()
        /** The most lives a fighter can hold, from the menu or from Extra Life. */
        const val MAX_LIVES = 8
        /** About fifteen blocks up. */
        const val TRAMPOLINE_LAUNCH = 1.6
        const val MIST_RADIUS = 3.5
        const val MIST_TICKS = 100
        const val POISON_TICKS = 120
    }
}

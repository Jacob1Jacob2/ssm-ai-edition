package dev.betrix.superSmashMobsBrawl.items

import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.smash.SmashEffect
import dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons
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
                arena.shot(p, kind, homing = true, speed = .95) { at, hit -> hit?.let { arena.hit(p, it, 30.0, 1.2, at, kind) } }
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
            ArenaItem.FOOD, ArenaItem.AWESOME_SOUP -> {
                heal(p,if (kind == ArenaItem.AWESOME_SOUP) maxHealth(p) else maxHealth(p)*.1)
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
            ArenaItem.SHADOW_WALK -> { ItemService.hide(p, 300); world.playSound(p.location,Sound.ENTITY_ENDERMAN_TELEPORT,1f,.7f); world.spawnParticle(Particle.LARGE_SMOKE, p.location, 20, .4, .7, .4, .02) }
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
    /** A cloud that lingers for five seconds. Everyone in it, the thrower too, is poisoned once, for six seconds. */
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
                potion(victim, PotionEffectType.POISON, POISON_TICKS)
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
        repeat(16) {
            val forward=p.eyeLocation.direction
            val direction=forward.clone().add(Vector(kotlin.random.Random.nextDouble(-.16,.16),
                kotlin.random.Random.nextDouble(-.16,.16),kotlin.random.Random.nextDouble(-.16,.16))).normalize()
            arena.shot(p,ArenaItem.SHOTBOW,direction=direction,material=Material.ARROW,speed=1.0+2.0*force) { at,hit ->
                if (hit != null && hitTargets.add(hit.uniqueId) && arena.hit(p,hit,6.25*force,.25,at,ArenaItem.SHOTBOW))
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
    companion object {
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

package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import org.bukkit.*
import org.bukkit.block.BlockState
import org.bukkit.entity.*
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import net.kyori.adventure.text.Component

/** One scheduler per arena, with bounded effects and reversible landing platforms. */
class SmashArena(val game: BrawlMinigame) {
    val world get() = game.worldManager.getWorld()!!.world
    var tick = 0L; private set
    private var nextDrop = interval()
    private val effects = mutableListOf<SmashEffect>()
    private val pending = mutableListOf<SmashEffect>()
    private var stepping = false
    private var drop: CrystalDrop? = null
    val originalBlocks = linkedMapOf<org.bukkit.block.Block, BlockState>()
    private val restoreAt = mutableMapOf<org.bukkit.block.Block, Long>()
    private data class Decoration(val snapshot:EntitySnapshot, val at:Location, val due:Long)
    private val decorationsToRestore = mutableListOf<Decoration>()
    val entities = mutableSetOf<Entity>()
    private val storms=mutableSetOf<java.util.UUID>()
    val hasStorm get()=storms.isNotEmpty()
    private var oldTime=0L
    private var oldStorm=false
    private var oldThunder=false
    private fun skyPlayers():List<Player> {
        val currentWorld=game.worldManager.getWorld()?.world ?: return emptyList()
        return game.allPlayers().mapNotNull {it.player}.filter {it.world==currentWorld}
    }
    fun startStorm(player:Player) {
        if(storms.isEmpty()){oldTime=world.time;oldStorm=world.hasStorm();oldThunder=world.isThundering}
        storms+=player.uniqueId
        world.time=18000;world.setStorm(true);world.isThundering=true
        skyPlayers().forEach {it.setPlayerTime(18000,false);it.setPlayerWeather(WeatherType.DOWNFALL);it.playSound(it.location,Sound.ENTITY_LIGHTNING_BOLT_THUNDER,1f,.8f)}
    }
    fun endStorm(player:Player) {
        if(!storms.remove(player.uniqueId) || storms.isNotEmpty())return
        game.worldManager.getWorld()?.world?.let {it.time=oldTime;it.setStorm(oldStorm);it.isThundering=oldThunder}
        skyPlayers().forEach {if(nights.isEmpty())it.resetPlayerTime();it.resetPlayerWeather()}
    }
    private val nights=mutableSetOf<java.util.UUID>()
    fun startNight(player:Player) {nights+=player.uniqueId;updateNight()}
    fun endNight(player:Player) {nights-=player.uniqueId;updateNight()}
    private fun updateNight() {
        skyPlayers().forEach {
            if(nights.isNotEmpty() || hasStorm)it.setPlayerTime(18000,false) else it.resetPlayerTime()
        }
    }
    fun players() = game.allPlayers().mapNotNull { it.player }.filter {
        it.isOnline && it.world == world && it.gameMode == GameMode.SURVIVAL && !game.connectionManager.isDisconnected(it) && !game.respawnManager.isRespawning(it)
    }
    fun enemies(owner: Player) = players().filter { it != owner && !game.arePlayersOnSameTeam(owner, it) }
    fun effect(effect: SmashEffect) { if (stepping) pending += effect else effects += effect }
    fun track(entity: Entity): Entity { entity.isPersistent = false; entities += entity; return entity }
    fun interval(): Long = game.settings.crystalDelaySeconds().toLong() * 20
    fun step() {
        tick++
        if(nights.isNotEmpty() && tick%20==0L)updateNight()
        val restored = restoreAt.filter { (block, due) -> tick >= due && drop?.owns(block) != true }.keys
        val states = restored.mapNotNull { originalBlocks.remove(it) }
        restored.forEach { restoreAt.remove(it) }
        dev.betrix.superSmashMobsBrawl.utils.TerrainRestoration.restore(states)
        decorationsToRestore.removeIf {
            if(tick<it.due) false else { restoreDecoration(it); true }
        }
        if (game.settings.crystals && tick >= nextDrop && drop == null) {
            SmashTier.random(game.settings.enabledCrystalTiers)?.let {spawnDrop(it)}; nextDrop = tick + interval()
        }
        drop?.let { if (!it.step()) { it.close(); drop = null; nextDrop = tick + interval() } }
        stepping = true
        val iterator = effects.iterator()
        while (iterator.hasNext()) {
            val effect = iterator.next()
            if (!effect.step()) { effect.close(); iterator.remove() }
        }
        stepping = false
        effects += pending; pending.clear()
        entities.removeIf { !it.isValid }
    }
    @JvmOverloads fun spawnDrop(tier: SmashTier, requested: Location? = null): Boolean {
        if (drop != null) return false
        val map = game.worldManager.getWorld()!!.data
        val point = map.crystalSpawnPoints.randomOrNull()
        val at = requested?.clone() ?: point?.let { Location(world, kotlin.math.floor(it.x)+0.5, it.y, kotlin.math.floor(it.z)+0.5) } ?: return false
        if (at.world != world) return false
        // Marker coordinates can be air above the platform. Find the nearby solid floor.
        repeat(8) { if (at.clone().subtract(0.0,1.0,0.0).block.isPassable) at.subtract(0.0,1.0,0.0) }
        drop = CrystalDrop(world, at, tier, ::players)
        return true
    }
    fun landingLocation(): Location? = drop?.at?.clone()
    /** The unclaimed crystal's pad and how many ticks until it can be taken (0 = it has landed). */
    fun crystalLanding(): Pair<Location, Int>? = drop?.takeIf { it.claimable }?.let { it.at.clone() to it.ticksToLand }
    fun protectsCrystal(block:org.bukkit.block.Block)=drop?.protects(block)==true
    fun strikeCrystal(entity: Entity, attacker: Player) = drop?.strike(entity, attacker) == true
    fun botStrikeCrystal(player: Player) { drop?.botStrike(player) }
    @JvmOverloads fun changeBlock(block: org.bukkit.block.Block, material: Material, debris: Boolean = true, itemSound: Boolean = false, itemSoundVolume: Float = .65f) {
        if(protectsCrystal(block))return
        if (material.isAir && !block.isEmpty && block.type !in setOf(Material.BARRIER,Material.BEDROCK)) {
            if (itemSound) world.playSound(block.location,Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR,itemSoundVolume,1f)
            if (debris) dev.betrix.superSmashMobsBrawl.utils.TerrainDebris.toss(this,block.location,block.blockData)
        }
        originalBlocks.putIfAbsent(block, block.state)
        if (material.isAir && originalBlocks[block]?.type?.isAir == false)
            restoreAt.putIfAbsent(block, tick + 200)
        block.setType(material, false)
    }
    /** Refreshable temporary construction; overlapping webs/ice expire after their last placement. */
    fun temporaryBlock(block:org.bukkit.block.Block,material:Material,ticks:Int):Boolean {
        if(protectsCrystal(block) || block.type in setOf(Material.BARRIER,Material.BEDROCK))return false
        changeBlock(block,material,false)
        restoreAt[block]=maxOf(restoreAt[block] ?: 0,tick+ticks)
        return true
    }
    @JvmOverloads fun destroy(center: Location, radius: Double, maxBlocks: Int = Int.MAX_VALUE, itemSound: Boolean = false, itemSoundVolume: Float = .65f) {
        if(center.world != world) return
        dev.betrix.superSmashMobsBrawl.services.ScaledMapPlants.destroy(center,radius)
        dev.betrix.superSmashMobsBrawl.services.ScaledMapDetails.destroy(center,radius,itemSound)
        val r = kotlin.math.ceil(radius).toInt()
        val candidates = mutableListOf<org.bukkit.block.Block>()
        for (x in -r..r) for (y in -r..r) for (z in -r..r) {
            if (x*x+y*y+z*z > radius*radius) continue
            val b=center.clone().add(x.toDouble(),y.toDouble(),z.toDouble()).block
            if (!b.isEmpty && b.type !in setOf(Material.BEDROCK,Material.BARRIER,Material.COMMAND_BLOCK) && drop?.owns(b) != true)
                candidates += b
        }
        candidates.sortedBy { it.location.add(.5,.5,.5).distanceSquared(center) }
            .take(maxBlocks).forEach { changeBlock(it,Material.AIR,itemSound=itemSound,itemSoundVolume=itemSoundVolume) }
    }
    /** Multiply actual terrain removed, including on thin floating islands where volume
     * scaling alone would not break three times as many blocks. */
    fun destroyMultiple(center:Location,baseRadius:Int,multiplier:Int) {
        if(center.world!=world)return
        val candidates=mutableListOf<org.bukkit.block.Block>()
        var baseline=0
        val reach=baseRadius*multiplier
        for(x in -reach..reach)for(y in -reach..reach)for(z in -reach..reach) {
            val distance=x*x+y*y+z*z
            if(distance>reach*reach)continue
            val b=center.clone().add(x.toDouble(),y.toDouble(),z.toDouble()).block
            if(b.isEmpty || b.type in setOf(Material.BEDROCK,Material.BARRIER,Material.COMMAND_BLOCK) || protectsCrystal(b))continue
            candidates+=b
            if(distance<=baseRadius*baseRadius)baseline++
        }
        val chosen=candidates.sortedBy {it.location.add(.5,.5,.5).distanceSquared(center)}.take(baseline*multiplier)
        chosen.forEach {changeBlock(it,Material.AIR)}
        val reachUsed=chosen.maxOfOrNull {it.location.add(.5,.5,.5).distance(center)} ?: return
        dev.betrix.superSmashMobsBrawl.services.ScaledMapPlants.destroy(center,reachUsed)
        dev.betrix.superSmashMobsBrawl.services.ScaledMapDetails.destroy(center,reachUsed,false)
    }
    /** Enlarged-map decorations are terrain too; retain their model and collision carriers. */
    fun rememberDecoration(display: BlockDisplay, carriers: Collection<org.bukkit.block.Block> = emptyList(), itemSound: Boolean = false) {
        if(!display.isValid) return
        display.createSnapshot()?.let { decorationsToRestore += Decoration(it,display.location,tick+200) }
        carriers.forEach { changeBlock(it,Material.AIR,itemSound=itemSound) }
    }
    private fun restoreDecoration(saved: Decoration) {
        val entity=saved.snapshot.createEntity(saved.at)
        dev.betrix.superSmashMobsBrawl.services.ScaledMapPlants.trackRestored(entity)
        dev.betrix.superSmashMobsBrawl.services.ScaledMapDetails.trackRestored(entity)
    }
    fun close() {
        storms.clear();nights.clear()
        skyPlayers().forEach {if(nights.isEmpty())it.resetPlayerTime();it.resetPlayerWeather()}
        drop?.close(); drop=null
        (effects + pending).forEach { it.close() };effects.clear();pending.clear()
        entities.toList().forEach { it.remove() };entities.clear()
        dev.betrix.superSmashMobsBrawl.utils.TerrainRestoration.restore(originalBlocks.values.toList().asReversed());originalBlocks.clear();restoreAt.clear()
        decorationsToRestore.forEach(::restoreDecoration);decorationsToRestore.clear()
    }
}

interface SmashEffect { fun step(): Boolean; fun close() {} }

internal class CrystalDrop(val world: World, val at: Location, val tier: SmashTier,
    private val players: () -> List<Player>, private val physicalPlatform: Boolean = true) {
    private var age=0
    private var closed=false
    private var shattered=false
    private var shatterTicks=0
    private val blocks=linkedMapOf<org.bukkit.block.Block,BlockState>()
    private val platformDisplays=mutableListOf<BlockDisplay>()
    private val decorations=mutableListOf<Entity>()
    private val landingTicks=tier.landingTicks
    val claimable get()=!closed && !shattered
    val ticksToLand get()=(landingTicks-age).coerceAtLeast(0)
    // Triple the travel distance, retaining each tier's full descent time. Display/firework
    // entities can start above the build ceiling; clamping here would slow high-map drops.
    private val startHeight = .35 + (35.0 - .35) * 3.0
    private val visual=CrystalVisual(at.clone().add(0.0,startHeight,0.0),tier)
    private val beamDecorations=mutableListOf<Entity>()
    private val label=world.spawn(at.clone().add(0.0,startHeight+1.85,0.0),TextDisplay::class.java) {
        it.text(Component.text(tier.title,tier.color).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
        it.billboard=Display.Billboard.CENTER;it.brightness=Display.Brightness(15,15)
        it.isShadowed=true;it.isPersistent=false;it.viewRange=2.5f
        it.teleportDuration=1
    }
    init {
        // A flush 3x3 quartz landing pad, colored center lens and recessed beacon.
        // Save EVERY layer so expiry, pickup, /hub and admin previews leave no terrain behind.
        for(x in -1..1)for(z in -1..1) {
            val above=at.clone().add(x.toDouble(),0.0,z.toDouble()).block
            // Aura snow is temporary. Do not resurrect an expired aura layer when the pad closes.
            if(physicalPlatform) {
                var decoration=above
                while(decoration.y<world.maxHeight && isPadDecoration(decoration)) {
                    if(decoration.type==Material.SNOW)decoration.setType(Material.AIR,false)
                    else placePlatform(decoration,Material.AIR)
                    decoration=decoration.getRelative(0,1,0)
                }
            }
            placePlatform(at.clone().add(x.toDouble(),-3.0,z.toDouble()).block,Material.IRON_BLOCK)
            placePlatform(at.clone().add(x.toDouble(),-1.0,z.toDouble()).block,
                platformMaterial(x,z))
        }
        placePlatform(at.clone().add(0.0,-2.0,0.0).block,Material.BEACON)
        // White beam core inside a colored shaft: visible even when the terrain blocks a real beacon.
        val openSky = physicalPlatform && (at.blockY until world.maxHeight).all { y ->
            val block = world.getBlockAt(at.blockX, y, at.blockZ)
            block.isPassable || block.type.name.contains("GLASS")
        }
        // Use the animated native beacon beam whenever the marker is open to the sky.
        if (!openSky) for((width,material) in listOf(0.24f to tier.glass,0.055f to Material.WHITE_STAINED_GLASS)) {
            beamDecorations+=world.spawn(at.clone().add(-width/2.0,-0.85,-width/2.0),BlockDisplay::class.java) {
                it.block=material.createBlockData();it.brightness=Display.Brightness(15,15)
                it.transformation=Transformation(Vector3f(),Quaternionf(),Vector3f(width,96f,width),Quaternionf())
                it.isPersistent=false;it.viewRange=2f
            }
        }
        decorations+=beamDecorations
        if(tier.ordinal>=2) {
            // Gold edging stays entirely inside the original three-block square.
            for(edge in listOf(-1.48,1.40)) {
                trim(-1.48,-.015,edge,2.96f,.035f,.08f,Material.GOLD_BLOCK)
                trim(edge,-.015,-1.48,.08f,.035f,2.96f,Material.GOLD_BLOCK)
            }
        }
        if(tier==SmashTier.ULTRA)for(x in listOf(-1.2,.9))for(z in listOf(-1.2,.9))
            trim(x,-.01,z,.3f,.05f,.3f,Material.REDSTONE_BLOCK)
        players().forEach {it.playSound(it.location,Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,.8f,1f)}
    }
    private fun platformMaterial(x:Int,z:Int):Material = when {
        x==0 && z==0 -> tier.glass
        tier==SmashTier.LIGHT -> Material.QUARTZ_BLOCK
        x!=0 && z!=0 -> if(tier==SmashTier.ULTRA)Material.QUARTZ_PILLAR else Material.CHISELED_QUARTZ_BLOCK
        tier.ordinal>=2 -> Material.QUARTZ_BRICKS
        else -> Material.SMOOTH_QUARTZ
    }
    private fun isPadDecoration(block:org.bukkit.block.Block):Boolean {
        val type=block.type
        if(type.isAir || block.isLiquid)return false
        val name=type.name
        return block.isPassable || type==Material.SNOW || type==Material.FLOWER_POT ||
            name.startsWith("POTTED_") || name.endsWith("_CARPET") || name.endsWith("TORCH") ||
            name.endsWith("CANDLE") || name.endsWith("_CANDLE_CAKE")
    }
    private fun trim(x:Double,y:Double,z:Double,sx:Float,sy:Float,sz:Float,material:Material) {
        decorations+=world.spawn(at.clone().add(x,y,z),BlockDisplay::class.java) {
            it.block=material.createBlockData();it.isPersistent=false;it.brightness=Display.Brightness(15,15)
            it.transformation=Transformation(Vector3f(),Quaternionf(),Vector3f(sx,sy,sz),Quaternionf())
        }
    }
    private fun placePlatform(block: org.bukkit.block.Block, material: Material) {
        if(physicalPlatform) {blocks.putIfAbsent(block,block.state);block.setType(material,false)}
        else platformDisplays+=world.spawn(block.location,BlockDisplay::class.java) {
            it.block=material.createBlockData();it.isPersistent=false
        }
    }
    fun owns(block:org.bukkit.block.Block)=block in blocks
    fun protects(block:org.bukkit.block.Block)=owns(block) || (block.world==world && block.y==at.blockY &&
        kotlin.math.abs(block.x-at.blockX)<=1 && kotlin.math.abs(block.z-at.blockZ)<=1)
    fun step():Boolean {
        if(closed)return false
        if(shattered) {
            shatterTicks++
            return shatterTicks<80
        }
        age++
        val location=at.clone().add(0.0,tier.descentHeight(startHeight,age),0.0)
        visual.move(location,age)
        val core=location.clone().add(0.0,.85,0.0)
        label.teleport(core.clone().add(0.0,1.0,0.0))
        if(age<=landingTicks) {
            world.spawnParticle(Particle.FIREWORK,core,4,.2,.3,.2,.02)
            repeat(tier.fireworksAt(age)) {
                val angle=age*2.3999632297
                val burst=core.clone().add(kotlin.math.cos(angle)*.6,0.0,kotlin.math.sin(angle)*.6)
                dev.betrix.superSmashMobsBrawl.utils.ClassicEffects.fireworkBurst(burst,tier.rgb)
                players().forEach {it.playSound(it.location,Sound.ENTITY_FIREWORK_ROCKET_BLAST,.4f,1f)}
            }
        }
        if(age==landingTicks) {
            world.playSound(at,Sound.BLOCK_BEACON_POWER_SELECT,.8f,.8f+tier.ordinal*.15f)
            world.spawnParticle(Particle.FIREWORK,location,12+tier.ordinal*12,.2,.2,.2,.08)
        }
        if(age>=landingTicks)players().firstOrNull {it.location.distanceSquared(at)<2.25 && !SmashService.hasStar(it)}?.let {strike(visual.hitbox,it)}
        return true // Unclaimed crystals remain until collected or the match is cleaned up.
    }
    /** Always consume attacks against this entity, including while it is descending. */
    fun botStrike(player: Player) {
        if (!closed && !shattered && age >= landingTicks && player.world == world && player.location.distanceSquared(at) <= 9.0) {
            player.swingMainHand()
            strike(visual.hitbox, player)
        }
    }

    fun strike(entity: Entity, player: Player): Boolean {
        if(entity!=visual.hitbox)return false
        if(closed || shattered || age<landingTicks || player !in players() || player.world!=world ||
            player.eyeLocation.distanceSquared(visual.hitbox.location.clone().add(0.0,0.85,0.0))>36.0)return true
        if(SmashService.giveStar(player,tier)) {
            shattered=true
            val center=visual.hitbox.location.add(0.0,0.85,0.0)
            world.spawnParticle(Particle.PORTAL,center,90+tier.ordinal*30,0.6,0.6,0.6,0.6)
            world.spawnParticle(Particle.FIREWORK,center,24+tier.ordinal*12,0.2,0.2,0.2,0.12)
            world.playSound(center,Sound.BLOCK_GLASS_BREAK,1f,0.7f)
            visual.close(); label.remove()
            beamDecorations.forEach {it.remove()}
            shatterTicks=1
            world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, .7f)
        }
        return true
    }
    fun close() {
        if(closed)return
        closed=true
        visual.close();label.remove();decorations.forEach {it.remove()}
        platformDisplays.forEach {it.remove()}
        blocks.values.toList().asReversed().forEach {it.update(true,false)}
        blocks.clear()
    }
}

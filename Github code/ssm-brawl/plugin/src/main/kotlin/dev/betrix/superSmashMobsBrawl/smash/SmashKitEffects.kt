package dev.betrix.superSmashMobsBrawl.smash

import org.bukkit.*
import org.bukkit.inventory.ItemStack

/** Smash strength never changes the kit's particle family into its rarity color. */
object SmashKitEffects {
    private val melody=intArrayOf(0,4,7,9,12,16,19,21,24)
    fun note(at:Location,index:Int) {
        val pitch=Math.pow(2.0,(melody[Math.floorMod(index,melody.size)]-12)/12.0).toFloat()
        at.world.playSound(at,Sound.BLOCK_NOTE_BLOCK_HARP,.8f,pitch)
    }
    fun particles(kit: String, at: Location, count: Int, spread: Double) {
        val world=at.world
        when(kit) {
            "skeleton","horse" -> world.spawnParticle(Particle.ITEM,at,count,spread,spread,spread,.01,ItemStack.of(Material.BONE))
            "iron_golem" -> world.spawnParticle(Particle.BLOCK,at,count,spread,spread*.4,spread,.05,ground(at))
            "spider" -> world.spawnParticle(Particle.ITEM,at,count,spread,spread,spread,.01,ItemStack.of(Material.COBWEB))
            "slime" -> world.spawnParticle(Particle.ITEM_SLIME,at,count,spread,spread,spread,.03)
            "squid" -> world.spawnParticle(Particle.POOF,at,count,spread,spread,spread,0.0)
            "goat" -> world.spawnParticle(Particle.NOTE,at,count,spread,spread,spread,0.0)
            "blaze","magma_cube" -> world.spawnParticle(Particle.FLAME,at,count,spread,spread,spread,.02)
            "enderman","wither_skeleton" -> world.spawnParticle(Particle.PORTAL,at,count,spread,spread,spread,.1)
            "snowman" -> world.spawnParticle(Particle.SNOWFLAKE,at,count,spread,spread,spread,.02)
            "guardian" -> world.spawnParticle(Particle.SPLASH,at,count,spread,spread,spread,.03)
            "witch" -> world.spawnParticle(Particle.WITCH,at,count,spread,spread,spread,.02)
            "phantom" -> world.spawnParticle(Particle.SOUL_FIRE_FLAME,at,count,spread,spread,spread,.01)
            "mooshroom" -> world.spawnParticle(Particle.BLOCK,at,count,spread,spread,spread,.02,Material.RED_MUSHROOM_BLOCK.createBlockData())
            "pig" -> world.spawnParticle(Particle.ITEM,at,count,spread,spread,spread,.02,ItemStack.of(Material.COOKED_PORKCHOP))
            "zombie" -> world.spawnParticle(Particle.BLOCK,at,count,spread,spread,spread,.02,Material.REDSTONE_BLOCK.createBlockData())
            "sheep" -> world.spawnParticle(Particle.BLOCK,at,count,spread,spread,spread,.02,Material.WHITE_WOOL.createBlockData())
            else -> world.spawnParticle(Particle.CLOUD,at,count,spread,spread,spread,.02)
        }
    }
    fun ground(at: Location): org.bukkit.block.data.BlockData {
        for(y in 0..4)at.clone().subtract(0.0,y.toDouble(),0.0).block.let { if(it.type.isSolid)return it.blockData }
        return Material.STONE.createBlockData()
    }
    fun shot(kit:String,at:Location) {
        if(kit=="goat") {note(at,kotlin.random.Random.nextInt(melody.size));return}
        val (sound,pitch)=when(kit) {
            "skeleton","zombie" -> Sound.ENTITY_ARROW_SHOOT to 1f
            "spider" -> Sound.ENTITY_SPIDER_AMBIENT to 2f
            "creeper" -> Sound.ENTITY_CREEPER_DEATH to 1.5f
            "slime" -> Sound.ENTITY_SLIME_SQUISH to 1.4f
            "squid" -> Sound.ENTITY_GENERIC_EXPLODE to .75f
            "goat" -> Sound.BLOCK_NOTE_BLOCK_HARP to 1.2f
            "iron_golem" -> Sound.BLOCK_STONE_BREAK to .6f
            "horse" -> Sound.ENTITY_SKELETON_HURT to 1.2f
            "blaze","magma_cube" -> Sound.ENTITY_BLAZE_SHOOT to 1f
            "enderman" -> Sound.ENTITY_ENDERMAN_TELEPORT to 1f
            "guardian" -> Sound.ENTITY_GUARDIAN_ATTACK to 1f
            "witch" -> Sound.ENTITY_WITCH_THROW to 1f
            "wither_skeleton" -> Sound.ENTITY_WITHER_SHOOT to 1f
            "chicken","snowman" -> Sound.ENTITY_EGG_THROW to 1f
            "pig" -> Sound.ENTITY_PIG_AMBIENT to 1.3f
            "cow","mooshroom" -> Sound.ENTITY_COW_AMBIENT to 1.2f
            "wolf" -> Sound.ENTITY_WOLF_GROWL to 1.3f
            "sheep" -> Sound.ENTITY_SHEEP_AMBIENT to 1.2f
            "villager" -> Sound.ENTITY_VILLAGER_AMBIENT to 1.4f
            else -> Sound.ENTITY_PHANTOM_FLAP to 1f
        }
        at.world.playSound(at,sound,if(kit=="squid") .65f else .7f,pitch)
        if(kit=="squid")at.world.playSound(at,Sound.ENTITY_FISHING_BOBBER_SPLASH,.5f,1f)
    }
}

package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import dev.betrix.superSmashMobsBrawl.projectiles.ProjectileAction
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.util.Vector
import kotlin.random.Random

/** Four snowballs per held burst, nine bursts per tank; three seconds to refill. */
class BlizzardAbility(player: Player) : ClassicKitAbility("blizzard", player) {
    override val countsOwnUses get()=true
    private var firing=false
    private var energy=0.0
    fun refill() { energy=1.0 }
    private var clock=0
    private val lastHit=mutableMapOf<java.util.UUID,Int>()
    private val projectiles=mutableSetOf<BrawlProjectile>()
    private val snow=mutableMapOf<Block,BlockState>()
    override fun setup() {
        super.setup()
        if(borrowed!=null)energy=1.0
        runnables.add(repeatingTask(1) {
            if (game?.getState() != dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS) return@repeatingTask
            clock++
            if(!firing)energy=(energy+1.0/60.0).coerceAtMost(1.0)
            if(borrowed==null)player.exp=energy.toFloat()
        })
    }
    override fun canActivate(sendMessage:Boolean)=!firing && energy>=1.0/9.0 && super.canActivate(sendMessage)
    override fun activate() {
        super.activate();firing=true
        loop(finish={firing=false}) {age ->
            if(age>1 && (!player.isBlocking || !isCorrectItemForAbility(player.inventory.itemInMainHand)))return@loop false
            if(age%4==0) {
                if(energy+0.00001<1.0/9.0)return@loop false
                if(!spendUse())return@loop false
                energy=(energy-1.0/9.0).coerceAtLeast(0.0)
                repeat(4) {launchSnowball()}
                player.world.playSound(player.location,Sound.BLOCK_SNOW_STEP,0.1f,0.5f)
            }
            true
        }
    }
    private fun launchSnowball() {
        val direction=player.eyeLocation.direction
            .add(Vector(Random.nextDouble(-0.10,0.10),Random.nextDouble(-0.10,0.10),Random.nextDouble(-0.10,0.10)))
        val projectile=BrawlProjectile.custom(player,"abilities.blizzard.name") {
            player.world.spawn(player.eyeLocation.add(player.eyeLocation.direction),Snowball::class.java).apply {shooter=player}
        }.setInitialVelocity {it.velocity=direction.multiply(2.0)}.projectileSize(0.18).maxLifetime(60)
            .onHitEntity {target,shot ->
                if(clock-lastHit.getOrDefault(target.uniqueId,-100)>=4 && hit(target,1.0)) {
                    lastHit[target.uniqueId]=clock
                    target.velocity=(shot.projectileEntity?.velocity ?: direction).clone().multiply(0.15).add(Vector(0.0,0.15 * itemBoost,0.0))
                }
                ProjectileAction.DESTROY
            }.onHitBlock {block,_ ->
                val above=block.getRelative(org.bukkit.block.BlockFace.UP)
                if(above.isEmpty && block.type.isSolid && block.type !in setOf(Material.ICE,Material.PACKED_ICE,Material.BLUE_ICE,Material.FROSTED_ICE) && above !in snow) {
                    snow[above]=above.state;above.setType(Material.SNOW,false)
                    loop(finish={snow.remove(above)?.update(true,false)}) {it<40}
                }
                ProjectileAction.DESTROY
            }.onTeardown {projectiles.remove(it)}.upgraded().launch()
        projectiles+=projectile
    }
    override fun teardown() {
        projectiles.toList().forEach {it.teardown()};projectiles.clear()
        snow.values.forEach {it.update(true,false)};snow.clear()
        super.teardown()
    }
}

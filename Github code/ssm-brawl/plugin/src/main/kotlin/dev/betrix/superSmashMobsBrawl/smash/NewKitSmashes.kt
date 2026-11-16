package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.abilities.*
import dev.betrix.superSmashMobsBrawl.services.KitService
import org.bukkit.Particle
import kotlin.math.*
import org.bukkit.potion.PotionEffectType

/** Uses the actual kit abilities, so their collisions and cleanup also apply to upgrades. */
class NewKitSmashes(private val session: SmashSession) {
    private val player get()=session.player
    private val goat=GoatSmashes(session)
    private fun ability(id:String)=KitService.getKitForPlayer(player)?.getAbility(id)
    fun handles(power:SmashPower)=power.spec.kit in setOf("goat","mooshroom","phantom")
    fun end(power:SmashPower) {
        if(power.spec.kit=="goat"){goat.end(power);return}
        if(power.spec.key=="phantom:3") { (ability("phantom_flight") as? PhantomFlightAbility)?.stopFlight();session.arena.endNight(player) }
    }
    fun begin(power:SmashPower) {
        if(power.spec.key in setOf("phantom:1","phantom:3")) {
            (ability("phantom_flight") as? PhantomFlightAbility)?.refill()
        }
        if(power.spec.key=="phantom:3") {session.arena.startNight(player);(ability("phantom_flight") as? PhantomFlightAbility)?.startFlight()}
    }
    fun tick(power:SmashPower) {
        if(power.spec.kit=="goat"){goat.tick(power);return}
        when(power.spec.key) {
            "mooshroom:3" -> {
                val center=player.location
                val radius=8.0
                if(power.age%4==0) {
                    repeat(64) {i ->
                        val angle=2*PI*i/64
                        center.world.spawnParticle(Particle.SPORE_BLOSSOM_AIR,
                            center.clone().add(cos(angle)*radius,.3,sin(angle)*radius),1,.08,.15,.08,0.0)
                    }
                    center.world.spawnParticle(Particle.CRIMSON_SPORE,center.clone().add(0.0,1.0,0.0),60,4.0,.7,4.0,0.0)
                }
                if(power.age%20==0) {
                    session.enemies().filter {it.location.distanceSquared(center)<=radius*radius}.forEach {
                        val proximity=1.0-it.location.distance(center)/radius
                        session.hit(it,2.0+4.0*proximity,0.3,source=power.spec.name)
                    }
                }
            }
            "phantom:3" -> {
                val flight=ability("phantom_flight") as? PhantomFlightAbility ?: return
                flight.maintainNightfall()
                if(power.age%10==0 && flight.flying)flight.bomb(1.4)
                if(power.age%4==0)player.world.spawnParticle(Particle.SOUL_FIRE_FLAME,player.location,5,0.7,0.2,0.7,0.01)
            }
        }
    }
    fun click(power:SmashPower):Boolean {
        if(power.spec.kit=="goat")return goat.click(power)
        when(power.spec.key) {
            "mooshroom:0" -> (ability("strawbury_swirl") as? StrawburySwirlAbility)?.spray(1.20,2.5)
            "mooshroom:1" -> (ability("fungal_trampoline") as? FungalTrampolineAbility)?.trampoline(1.5,true)
            "mooshroom:2" -> (ability("mushroom_wall") as? MushroomWallAbility)?.wall(1.5,true)
            "phantom:0" -> (ability("ravenous_membrane") as? RavenousMembraneAbility)?.volley(1.2,7)
            else -> return false
        }
        return true
    }
}

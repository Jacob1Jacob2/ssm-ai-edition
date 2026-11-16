package dev.betrix.superSmashMobsBrawl.smash

import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.util.Vector

/** Cancelling a fuse restores gravity without detonating it during death or match cleanup. */
class CreeperSmashes(private val s:SmashSession) {
    private var charge:Charge?=null
    fun begin(power:SmashPower) {charge?.close();charge=Charge(power).also {s.arena.effect(it)}}
    fun end(power:SmashPower) {if(charge?.power===power)charge?.close()}
    private inner class Charge(val power:SmashPower):SmashEffect {
        private val gravity=s.player.hasGravity()
        private var age=0
        private var stopped=false
        private val ultra=power.spec.tier==SmashTier.ULTRA
        private val ticks=if(ultra)60 else 40
        init {s.player.setGravity(false);s.player.velocity=Vector()}
        override fun step():Boolean {
            if(stopped || !s.alive || !s.has(power.spec.key))return false
            s.player.velocity=Vector();s.player.fallDistance=0f;age++
            s.player.world.playSound(s.player.location,Sound.ENTITY_CREEPER_PRIMED,.5f+age/20f,.5f+age.toFloat()/ticks)
            if(age<ticks)return true
            val at=s.player.location
            s.combat.pulse(at,if(ultra)18.0 else 26.0,if(ultra)84.0 else 40.0,if(ultra)4.0 else 3.0,power,
                terrain=if(ultra)0.0 else 6.0)
            if(ultra)s.arena.destroyMultiple(at,9,3)
            repeat(if(ultra)12 else 6) {i ->
                at.world.spawnParticle(Particle.EXPLOSION_EMITTER,at.clone().add(0.0,i*.35,0.0),1)
                at.world.playSound(at,Sound.ENTITY_GENERIC_EXPLODE,2f,.65f+i*.05f)
            }
            close();return false
        }
        override fun close() {
            if(stopped)return
            stopped=true
            if(charge===this) {s.player.setGravity(gravity);charge=null}
        }
    }
}

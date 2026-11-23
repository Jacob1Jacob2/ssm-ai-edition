package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/**
 * What kit abilities have in the air: where each thing is and how it is moving. Only ever read,
 * by bots, which have no eyes to see a fireball coming with; nothing a kit does depends on it.
 *
 * Two kinds of thing fly. The restored classic abilities move theirs a step at a time and ask
 * [ClassicKitAbility.collision] about every step, which notes the step here; what was noted in
 * this tick and the one before is kept (an ability may run before or after the bot that looks).
 * The others are [BrawlProjectile]s, real entities, listed here from launch to teardown.
 */
object Flights {
    /** One thing in the air: whose, what (an ability or projectile name), where, its movement a tick, and how wide it hits. */
    class Flight(val owner: Player, val what: String, val at: Location, val velocity: Vector, val radius: Double)

    /**
     * Something that is going to go off where it is, in a known time: a Creeper that has begun to
     * hiss. Whose, what, where, how far it reaches and how many ticks are left.
     */
    class Blast(val owner: Player, val what: String, val at: Location, val radius: Double, val ticksLeft: Int)

    /**
     * Something standing in the arena that hurts whoever touches it, for a known time: a
     * Mooshroom's Spore Wall. Whose, what, the blocks it fills (from corner to corner), how far
     * outside them it still reaches a body, and how many ticks are left.
     */
    class Standing(val owner: Player, val what: String, val world: World,
                   val minX: Double, val minY: Double, val minZ: Double, val maxX: Double, val maxY: Double, val maxZ: Double,
                   val reach: Double, val ticksLeft: Int)

    private var now = ArrayList<Flight>()
    private var nowTick = -1
    private var before = ArrayList<Flight>()
    private var beforeTick = -1
    private var blastsNow = ArrayList<Blast>()
    private var blastsBefore = ArrayList<Blast>()
    private var standingNow = ArrayList<Standing>()
    private var standingBefore = ArrayList<Standing>()
    private val launched = HashSet<BrawlProjectile>()

    private fun roll() {
        val tick = Bukkit.getCurrentTick()
        if (tick == nowTick) return
        before = now; blastsBefore = blastsNow; standingBefore = standingNow; beforeTick = nowTick
        now = ArrayList(); blastsNow = ArrayList(); standingNow = ArrayList(); nowTick = tick
    }

    /** A tick of something made of [blocks] that hurts whoever comes within [reach] of them. */
    fun stands(owner: Player, what: String, blocks: Collection<org.bukkit.block.Block>, reach: Double, ticksLeft: Int) {
        val first = blocks.firstOrNull() ?: return
        roll()
        standingNow.add(Standing(owner, what, first.world,
            blocks.minOf { it.x }.toDouble(), blocks.minOf { it.y }.toDouble(), blocks.minOf { it.z }.toDouble(),
            blocks.maxOf { it.x } + 1.0, blocks.maxOf { it.y } + 1.0, blocks.maxOf { it.z } + 1.0, reach, ticksLeft))
    }

    /** Everything of that kind standing in [world] at this moment (each once, as with [blastsIn]). */
    fun standingIn(world: World): List<Standing> {
        roll()
        if (standingNow.isEmpty() && standingBefore.isEmpty()) return emptyList()
        val out = ArrayList<Standing>()
        for (s in standingNow) if (s.world == world) out.add(s)
        if (beforeTick == nowTick - 1) for (s in standingBefore) {
            if (s.world == world && out.none { it.owner == s.owner && it.what == s.what }) out.add(s)
        }
        return out
    }

    /** A tick of something winding up to go off. */
    fun warn(owner: Player, what: String, at: Location, radius: Double, ticksLeft: Int) {
        roll()
        blastsNow.add(Blast(owner, what, at.clone(), radius, ticksLeft))
    }

    /** Everything winding up in [world] at this moment (each once: this tick's word of it, or failing that the last tick's). */
    fun blastsIn(world: World): List<Blast> {
        roll()
        val out = ArrayList<Blast>()
        for (b in blastsNow) if (b.at.world == world) out.add(b)
        if (beforeTick == nowTick - 1) for (b in blastsBefore) {
            if (b.at.world == world && out.none { it.owner == b.owner && it.what == b.what }) out.add(b)
        }
        return out
    }

    /** A step of something a classic ability is flying. */
    fun note(owner: Player, what: String, from: Location, step: Vector, radius: Double) {
        roll()
        now.add(Flight(owner, what, from.clone(), step.clone(), radius))
    }

    fun launched(projectile: BrawlProjectile) { launched.add(projectile) }
    fun ended(projectile: BrawlProjectile) { launched.remove(projectile) }

    /** Everything in the air in [world] at this moment. */
    fun inWorld(world: World): List<Flight> {
        roll()
        val out = ArrayList<Flight>()
        for (f in now) if (f.at.world == world) out.add(f)
        if (beforeTick == nowTick - 1) for (f in before) if (f.at.world == world) out.add(f)
        // (One that ended without saying so is not kept for ever.)
        launched.removeIf { it.projectileEntity?.isValid == false }
        for (p in launched) {
            val entity = p.projectileEntity ?: continue
            if (entity.world != world) continue
            val velocity = entity.velocity
            // (An arrow stuck in the ground is still listed until it goes.)
            if (velocity.lengthSquared() < 0.0025) continue
            out.add(Flight(p.owner, p.name, entity.location, velocity, p.hitSize))
        }
        return out
    }
}

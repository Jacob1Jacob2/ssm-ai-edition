package dev.betrix.superSmashMobsBrawl.bots.combat

import dev.betrix.superSmashMobsBrawl.bots.RouteNode
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What a bot has learned from dying, for the length of one match.
 *
 * A player who is shot off the same path by the same opponent sitting in the same place does not
 * walk down it a third time: they try another way in, and when that costs a life as well they stop
 * going in and wait for the camper to come out. This is that memory. It holds plain data only (no
 * Bukkit types), so the rules can be unit tested; the bot's brain tells it what happened and asks
 * it what not to do again.
 *
 * @param learnAfter deaths in the same trap before the lesson takes hold: better players learn sooner
 */
class DeathMemory(private val learnAfter: Int) {
    data class Point(val x: Double, val y: Double, val z: Double) {
        fun distance(o: Point): Double {
            val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    /** Somewhere an opponent stayed put and killed this bot from. */
    class Trap internal constructor(val killer: UUID, val camp: Point) {
        internal val spotList = ArrayList<Point>()
        internal val wayList = LinkedHashSet<String>()
        var deaths = 0; internal set
        /** How far from [camp] to keep while not going in: a little further than the bot was ever hit from. */
        var keepOut = 0.0; internal set
        /** Until when the bot does not walk into their view within [keepOut]: longer after every death. */
        var keepOutUntil = 0L; internal set
        /** Until when the bot does not go in at all, by any way: from the second death here on. */
        var standoffUntil = 0L; internal set
        /** The ground the bot was standing on when it was hit. */
        val spots: List<Point> get() = spotList
        /** How the bot was trying to get in each time it died ("walk", "jump", "cross:DROP"...). */
        val ways: Set<String> get() = wayList
    }

    private val trapList = ArrayList<Trap>()
    private val deadlyHops = HashMap<Pair<RouteNode, RouteNode>, Pair<Int, Long>>()
    private val deadlyTries = ArrayList<Pair<String, Point>>()
    /** Fights lost fairly to somebody standing their ground: who, and where they stood. */
    private val fairLosses = ArrayList<Pair<UUID, Point>>()

    val traps: List<Trap> get() = trapList

    /** Has this trap cost enough lives for the bot to act on it? */
    fun learned(trap: Trap) = trap.deaths >= learnAfter

    /**
     * The bot was killed by [killer], who was at [killerAt].
     *
     * Only a killer [holding] a spot leaves anything to stay away from: a fight that moves about
     * the map does not. A death by damage after a real exchange of blows ([exchanged]) is a fight
     * lost, and only the second one against the same opponent in the same place is taken for a
     * trap; being knocked into the void ([fell]), or killed without getting a blow in, by someone
     * who has not moved is a trap the first time.
     *
     * @param hitAt everywhere the bot was when the killer hit it in its last seconds
     * @param standing the ground among those places, and the last ground it stood on
     * @param way how it was trying to get in to them
     * @return the trap this death was added to, or null if there is nothing to learn from it
     */
    fun killedBy(killer: UUID, killerAt: Point, holding: Boolean, fell: Boolean, exchanged: Boolean,
                 hitAt: List<Point>, standing: List<Point>, way: String, now: Long): Trap? {
        val known = trapList.firstOrNull { it.killer == killer && it.camp.distance(killerAt) < CAMP }
        if (known == null && !holding) return null
        if (known == null && !fell && exchanged) {
            // Once is a fight lost. Losing it again to the same opponent in the same place is a pattern.
            if (fairLosses.none { it.first == killer && it.second.distance(killerAt) < CAMP }) {
                fairLosses += killer to killerAt
                return null
            }
        }
        val trap = known ?: Trap(killer, killerAt).also { trapList += it }
        trap.deaths++
        for (s in standing) if (trap.spotList.size < MAX_SPOTS && trap.spotList.none { it.distance(s) < 2.0 }) trap.spotList += s
        val far = (hitAt + standing).maxOfOrNull { it.distance(trap.camp) } ?: 0.0
        trap.keepOut = max(trap.keepOut, far + 3.0).coerceIn(MIN_KEEP_OUT, MAX_KEEP_OUT)
        trap.wayList += way
        // Once learned: no walking into their view for a while, and for much longer every time it
        // happens again. Twice: for that long, no going in by any other way either.
        val times = trap.deaths - learnAfter + 1
        if (times >= 1) trap.keepOutUntil = now + QUIET_TICKS * times * times
        if (times >= 2) trap.standoffUntil = trap.keepOutUntil
        return trap
    }

    /**
     * The camper of [trap] has hit the bot at [at] and is winning the exchange: wherever that is,
     * it is inside their reach. Returns true if the bot now keeps further off than it did.
     */
    fun hitFrom(trap: Trap, at: Point): Boolean {
        val wider = (at.distance(trap.camp) + 3.0).coerceIn(MIN_KEEP_OUT, MAX_KEEP_OUT)
        if (wider <= trap.keepOut) return false
        trap.keepOut = wider
        return true
    }

    /** The trap [killer] is sitting in right now, once the bot has learned it. */
    fun trapOf(killer: UUID, at: Point): Trap? = trapList.firstOrNull { it.killer == killer && learned(it) && it.camp.distance(at) < CAMP }

    /** The trap [killer] is sitting in, while the bot has given up going in to them. */
    fun standingOff(killer: UUID, at: Point, now: Long): Trap? = trapOf(killer, at)?.takeIf { now < it.standoffUntil }

    /** Has this way of getting in to [target] already cost a life where they are now? */
    fun wayBarred(target: UUID, at: Point, way: String, now: Long): Boolean {
        val trap = trapOf(target, at)
        if (trap != null && (now < trap.standoffUntil || way in trap.wayList)) return true
        return deadlyTries.any { it.first == way && it.second.distance(at) < CAMP }
    }

    /** The bot fell to its death with nobody's help while trying [way] on someone standing at [targetAt]. */
    fun fellTrying(way: String, targetAt: Point) { deadlyTries += way to targetAt }

    /** The bot fell to its death with nobody's help on the jump [hop]. Returns how often that has happened now. */
    fun fellOn(hop: Pair<RouteNode, RouteNode>, now: Long): Int {
        val count = (deadlyHops[hop]?.first ?: 0) + 1
        deadlyHops[hop] = count to if (count >= 2) Long.MAX_VALUE else now + HOP_REST_TICKS
        return count
    }

    val anyHops: Boolean get() = deadlyHops.isNotEmpty()

    /** Is the jump from [from] to [to] one the bot fell to its death on and is leaving alone? */
    fun hopBarred(from: RouteNode, to: RouteNode, now: Long): Boolean = deadlyHops[from to to]?.let { now < it.second } == true

    fun clear() { trapList.clear(); deadlyHops.clear(); deadlyTries.clear(); fairLosses.clear() }

    companion object {
        /** A trap is still set while whoever set it is within this many blocks of where they sat. */
        const val CAMP = 6.0
        const val MIN_KEEP_OUT = 8.0
        const val MAX_KEEP_OUT = 30.0
        const val MAX_SPOTS = 24
        /** Two minutes at twenty ticks a second: how long the first death keeps the bot out; the second, four times that; the third, nine. */
        const val QUIET_TICKS = 2400L
        const val HOP_REST_TICKS = 1200L
    }
}

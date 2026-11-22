package dev.betrix.superSmashMobsBrawl.bots.combat

import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import dev.betrix.superSmashMobsBrawl.items.ArenaItem
import dev.betrix.superSmashMobsBrawl.items.ArenaItem.*

/** How a bot points an item before using it. */
enum class ItemAim {
    /** No aiming: heals, buffs and things that act on the bot itself. */
    NONE,
    /** Straight at the opponent, leading them: rockets, snowballs, the teleporter. */
    TARGET,
    /** Thrown in an arc that lands on the opponent: grenades and other fused items. */
    LOBBED,
    /** Toward safe ground: the teleporter used to get back on the map. */
    ESCAPE,
    /** Used on the opponent directly in melee range (knockback rod hit, grapple, placed bomb). */
    CONTACT,
    /** At the ground under the opponent, and held there: what a Rain Staff rains on is the block it is pointed at. */
    GROUND,
}

/** What the bot knows when it decides whether the item in its hotbar is worth using now. */
data class ItemSituation(
    /** Own health and energy bar, 0..1. */
    val health: Double,
    val energy: Double = 1.0,
    val lives: Int = 4,
    val fleeing: Boolean = false,
    /** Dropping with nothing underneath. */
    val falling: Boolean = false,
    /** Still has its double jump (or flap, leap...). */
    val airJump: Boolean = true,
    /** Distance to the current opponent, or null with nobody to fight. */
    val targetDistance: Double? = null,
    val targetVisible: Boolean = false,
    val targetHealth: Double = 1.0,
    /** Opponent's horizontal speed in blocks per tick; under about 0.08 they are standing still. */
    val targetSpeed: Double = 0.0,
    val targetNearEdge: Boolean = false,
    /** The route planner knows no way to the opponent. */
    val targetUnreachable: Boolean = false,
    /** There is a drop behind the bot, so anything thrown over its shoulder leaves the map. */
    val voidBehind: Boolean = false,
    /** Distance to the nearest opponent of any kind. */
    val nearestEnemy: Double = Double.MAX_VALUE,
    /** How long the item has been carried. */
    val heldSeconds: Int = 0,
    /** Sitting in something (a Flying Car, as it is the only thing there is to sit in). */
    val riding: Boolean = false,
    /** What is left in the carried item: its uses, or for a Flying Car its seconds of fuel. */
    val charge: Int = Int.MAX_VALUE,
)

/**
 * When an item is worth using. Every item has a job (heal, recover, finish, punish a camper), and
 * a bot that fires each one the second it picks it up wastes nearly all of them.
 *
 * Normal bots know the job of each item but not the timing: they throw at anything in range.
 * Expert bots wait for the moment the item pays off, and keep recovery items for a fall.
 */
object ItemTactics {
    private fun skill(d: BotDifficulty) = when (d) {
        BotDifficulty.NORMAL -> 0.25
        BotDifficulty.HARD -> 0.5
        BotDifficulty.HARDER -> 0.8
        BotDifficulty.EXPERT -> 1.0
    }

    /**
     * How to use [item] right now, or null to keep holding it. [old] is how the Rain Staff and the
     * Crafty Bomb were played before November 2026, kept to measure the difference against.
     */
    fun plan(item: ArenaItem, s: ItemSituation, difficulty: BotDifficulty, old: Boolean = false): ItemAim? {
        val skill = skill(difficulty)
        val careful = skill >= 0.75
        val dist = s.targetDistance
        val seen = s.targetVisible && dist != null
        val still = s.targetSpeed < 0.08
        fun within(range: ClosedFloatingPointRange<Double>) = seen && dist!! in range
        // Nobody keeps an item for ever: past this, any reasonable chance is taken.
        val impatient = s.heldSeconds > 20 + (40 * skill).toInt()
        // Sitting where no route leads: the moment to use it is whenever there is a shot at all.
        val camped = s.targetUnreachable
        fun at(aim: ItemAim, condition: Boolean) = if (condition) aim else null

        return when (item) {
            DIAMOND_AXE, DIAMOND_SHOVEL, DIAMOND_SWORD, DIAMOND_HELMET -> null // applied on pickup

            // --- healing: not at full health, and the big ones not until they are needed
            PORKCHOP, STEAK -> at(ItemAim.NONE, s.health <= 0.9)
            FIRST_AID_KIT -> at(ItemAim.NONE, s.health <= 0.75 - 0.25 * skill)
            AWESOME_SOUP -> at(ItemAim.NONE, s.health <= 0.6 - 0.25 * skill || (s.fleeing && s.health <= 0.5))
            GILDED_APPLE -> at(ItemAim.NONE, within(0.0..12.0) || s.health <= 0.6)
            REGENERATE -> at(ItemAim.NONE, s.energy <= 0.3)
            EXTRA_LIFE -> at(ItemAim.NONE, s.lives < 8 && !s.falling && s.nearestEnemy > 14)

            // --- movement: speed for a chase or an escape, invisibility for an approach or an escape
            SPEED -> at(ItemAim.NONE, s.fleeing || (dist != null && dist > 6))
            SHADOW_WALK -> at(ItemAim.NONE, s.fleeing || s.health <= 0.45 || within(5.0..16.0))

            // --- recovery: careful bots keep these for a fall they cannot jump out of
            CLOUD_IN_A_BOTTLE -> at(ItemAim.NONE, s.falling && (!careful || !s.airJump))
            JETPACK -> at(ItemAim.NONE, s.falling && !s.airJump)
            // A pad in mid-air and a launch off it: the same moment as a cloud.
            POCKET_TRAMPOLINE -> at(ItemAim.NONE, s.falling && (!careful || !s.airJump))
            // Whatever comes out is worth more in the hand than in the bundle.
            MYSTERY_ABILITY -> ItemAim.NONE
            INSTANT_TELEPORTER -> when {
                s.falling && !s.airJump -> ItemAim.ESCAPE
                // The answer to someone sitting where nothing walks: appear next to them.
                within(9.0..22.0) && (s.targetUnreachable || !careful || impatient) -> ItemAim.TARGET
                else -> null
            }

            // --- shot straight at the opponent
            HOMING_SNOWBALL -> at(ItemAim.TARGET, within(3.0..30.0))
            FIRE_FLOWER, MISSILE_BATTERY -> at(ItemAim.TARGET, within(3.0..24.0))
            // Not a shot at all: see [RainTactics].
            RAIN_STAFF -> at(ItemAim.GROUND, if (old) within(3.0..24.0) else within(RainTactics.NEAR..RainTactics.FAR))
            // One rocket: careful bots want a slow or close target, and never their own feet.
            ROCKET_LAUNCHER -> at(ItemAim.TARGET, within(8.0..28.0) && (!careful || still || dist!! < 14 || impatient || camped))
            SHOTBOW -> at(ItemAim.TARGET, within(0.0..9.0))
            // The rider goes with the rocket, so it is also the way to someone no route leads to
            // (seen or not: it goes off against whatever is between them, and that is near enough).
            PERSONAL_ROCKET -> at(ItemAim.TARGET, !s.riding && (within(5.0..26.0) || (camped && dist != null && dist <= 26.0)))
            // An anvil for every opponent, wherever they are. It lands where they stood half a
            // second before: only someone running and jumping is out from under it by then, so
            // careful bots wait for anything less.
            ANVIL -> at(ItemAim.NONE, dist != null && (!careful || s.targetSpeed < ANVIL_OUTRUN || impatient || camped))
            // The best thing there is to find: going anywhere and running people down, for as long
            // as the tank lasts (and it fills again while the car stands). Got into for a chase or
            // to reach a camper, with enough in the tank to be worth it; and with anything at all
            // in it, to be caught by when falling.
            FLYING_CAR -> at(ItemAim.NONE, !s.riding && s.charge >= 1 &&
                ((s.falling && !s.airJump) || (s.charge >= CAR_WORTH && ((dist != null && dist > 5.0) || camped))))

            // --- finishers: worth most when the opponent is at an edge or already hurt
            KNOCKBACK_ROD -> when {
                within(0.0..3.6) && (!careful || s.targetNearEdge || s.targetHealth < 0.4 || impatient) -> ItemAim.CONTACT
                // Someone sitting on a perch nothing walks to is on an edge by definition: knock them off it.
                within(3.6..12.0) && (s.targetNearEdge || camped) -> ItemAim.TARGET
                else -> null
            }
            // Throws the opponent far behind the bot, so it wants the drop at its back. It also
            // fetches a camper out of their hiding place, wherever they land.
            PLAYER_GRAPPLE -> at(ItemAim.CONTACT, within(0.0..14.0) && (!careful || s.voidBehind || impatient || camped))
            // The blast goes through floors and walls: no need to see someone hiding under the bot.
            SMASHER -> if (camped && !seen && dist != null && dist <= SMASHER_THROUGH) ItemAim.NONE else at(ItemAim.CONTACT, within(0.0..7.0))

            // --- three-second fuses: careful bots save them for someone who is not moving
            //     (a camper is not going anywhere)
            AIR_STRIKE -> at(ItemAim.LOBBED, within(8.0..22.0) && (!careful || still || impatient || camped))
            // The mist stays for five seconds, so it also works on someone who is merely near.
            POISON_MIST -> at(ItemAim.LOBBED, within(5.0..16.0) && (!careful || still || s.targetSpeed < 0.2 || impatient || camped))
            ICE_CUBE -> at(ItemAim.LOBBED, within(6.0..16.0) && (!careful || still || s.targetNearEdge || impatient || camped))
            SINGULARITY_GRENADE -> at(ItemAim.LOBBED, within(6.0..16.0) && (!careful || still || impatient || camped))
            SINGULARITY_NUKE -> at(ItemAim.LOBBED, within(9.0..18.0) && (!careful || still || impatient || camped))
            CLUSTER_GRENADE -> at(ItemAim.LOBBED, within(6.0..16.0) && (!careful || still || impatient || camped))
            FRAG_GRENADE -> at(ItemAim.LOBBED, within(4.0..14.0))
            // Set down beside whoever the bot is fighting: three seconds on, they are still about.
            // (Careful bots used to wait for somebody standing still within six blocks, which in a
            // fight is nobody: two bombs in three were still in the hand when their owner died.)
            CRAFTY_BOMB -> at(ItemAim.CONTACT, within(0.0..6.0) &&
                (!careful || still || impatient || (!old && (dist!! <= CraftyTactics.CLOSE || s.targetSpeed < 0.2 || camped))))
            // A mine goes where the opponent is about to walk: at a chaser, or between the two.
            PROXIMITY_MINE, CAGE_TRAP -> at(ItemAim.LOBBED, (s.fleeing && within(0.0..12.0)) || within(4.0..12.0))
            MONSTER_EGG -> at(ItemAim.LOBBED, within(0.0..20.0))
        }
    }

    /** Items a bot cannot spend should not block it from picking up others. */
    fun shouldDiscard(failedUses: Int) = failedUses >= 4

    /** Blocks a tick: a walk is 0.22 and a run 0.28, and neither gets out from under a Falling Anvil; a running jump, 0.36, does. */
    const val ANVIL_OUTRUN = 0.33
    /** Seconds of fuel a Flying Car wants in it before a bot gets in to chase somebody. */
    const val CAR_WORTH = 12

    /** The Smasher's blast reaches 11 blocks; this close it still takes off a good part of a health bar. */
    const val SMASHER_THROUGH = 8.5

    /**
     * What an item is worth against an opponent sitting where no route leads:
     * 3 gets the bot to them, 2 hurts or moves them from where the bot can stand, 0 is no help.
     */
    fun siegeValue(item: ArenaItem): Int = when (item) {
        INSTANT_TELEPORTER, JETPACK, CLOUD_IN_A_BOTTLE, POCKET_TRAMPOLINE, FLYING_CAR, PERSONAL_ROCKET -> 3
        ROCKET_LAUNCHER, MISSILE_BATTERY, SMASHER, AIR_STRIKE, HOMING_SNOWBALL, FIRE_FLOWER, RAIN_STAFF, ANVIL,
        PLAYER_GRAPPLE, KNOCKBACK_ROD, SINGULARITY_NUKE, SINGULARITY_GRENADE, CLUSTER_GRENADE, FRAG_GRENADE,
        POISON_MIST, ICE_CUBE, MONSTER_EGG -> 2
        else -> 0
    }

    /** How far away an item still does something to an opponent the bot can see, for choosing where to stand. */
    fun reach(item: ArenaItem): Double = when (item) {
        ANVIL, FLYING_CAR -> 40.0
        HOMING_SNOWBALL -> 30.0
        PERSONAL_ROCKET -> 26.0
        ROCKET_LAUNCHER -> 28.0
        FIRE_FLOWER, MISSILE_BATTERY -> 24.0
        RAIN_STAFF -> RainTactics.FAR
        AIR_STRIKE -> 22.0
        INSTANT_TELEPORTER, MONSTER_EGG -> 20.0
        SINGULARITY_NUKE -> 18.0
        POISON_MIST, ICE_CUBE, SINGULARITY_GRENADE, CLUSTER_GRENADE -> 16.0
        FRAG_GRENADE, PLAYER_GRAPPLE -> 14.0
        KNOCKBACK_ROD -> 12.0
        else -> 0.0
    }

    /** Things that cost nothing to use up on the spot, when the hand is wanted for something else. */
    fun usedUpFreely(item: ArenaItem): Boolean =
        item in setOf(PORKCHOP, STEAK, FIRST_AID_KIT, AWESOME_SOUP, GILDED_APPLE, REGENERATE, SPEED, SHADOW_WALK)
}

/**
 * A Rain Staff rains on the block its holder is looking at: six projectiles a volley, from twelve
 * blocks up, over five blocks by five, and a volley every two ticks for as long as the button is
 * held. Bots used to point it at the opponent, as they would a rocket, and click: the look went
 * past the opponent to whatever was behind them, the rain fell there, and one click was one
 * volley. Of 120 projectiles about two landed (500 matches, November 2026).
 */
object RainTactics {
    /** Nearer than this the opponent is all but in reach, and a hit is worth more than the staff. */
    const val NEAR = 3.5
    /** Past this a look at the ground runs so nearly along it that any bump in the way takes the rain. */
    const val FAR = 16.0
    /** A volley takes this long to come down: twelve blocks at 1.2 a tick. */
    const val FALL_TICKS = 10
    /** The rain's middle this far from where the opponent will be still has them under it (it falls 2.5 either way). */
    const val CATCH = 2.0
    /** The longest the button is held in one go: then a look at whether this is still the one to rain on. */
    const val HOLD_TICKS = 100
    /** Ticks the rain may be off the opponent (over a gap, behind a pillar) before the button is let go. */
    const val PATIENCE = 12
    /** Ticks a bot gives itself to get its eye onto the spot before thinking again. */
    const val SETTLE_TICKS = 20
    /** Blocks the rain lands off the mark for each degree of a bot's unsteadiness. */
    const val SLIP = 0.1
    /** Ticks of rain (a volley every two) with nothing landing on anybody before the button is let go. */
    const val DRY_TICKS = 40
    /** How long the staff is then left alone: the same rain from the same place would land no better. */
    const val DRY_REST = 100
    fun dry(ticksSinceItLanded: Long) = ticksSinceItLanded > DRY_TICKS

    /** Where the opponent will be when a volley let go now comes down. Only those who lead a shot lead this. */
    fun lead(x: Double, z: Double, vx: Double, vz: Double, leads: Boolean): Pair<Double, Double> =
        if (leads) x + vx * FALL_TICKS to z + vz * FALL_TICKS else x to z

    /** Whether rain with its middle [off] blocks (on the flat) from where the opponent will be is worth a volley. */
    fun catches(off: Double) = off <= CATCH

    /** Whether the opponent is where a staff can be used on them at all: a little past [FAR] is kept, once it is raining. */
    fun inReach(distance: Double, raining: Boolean) = distance >= NEAR && distance <= FAR + if (raining) 2.0 else 0.0
}

/**
 * A Monster Egg's clone: a fifth of its owner's health, 15% of a health bar a swing, and it goes
 * for whoever is nearest. One or two hits finish it. Bots used to leave it alone unless it was
 * standing between them and the player they were after, and took every swing it had.
 */
object CloneTactics {
    /** How near a clone has to be to be looked for at all. */
    const val NOTICE = 6.0
    /**
     * Half the angle a fighter sees in front of them, in degrees. A clone coming from outside it
     * is not seen coming, and gets its first swing in. (Without this a bot turned on every clone
     * before it arrived, from any side: 2% of a health bar a pickup, where a clone is worth 15% a
     * swing against somebody looking the other way.)
     */
    const val VIEW = 60.0
    /** Whether a clone [degrees] to the side of where the bot is facing is one it can see coming. */
    fun inView(degrees: Double) = kotlin.math.abs(degrees) <= VIEW
    /** Ticks after a hit from a clone standing next to it that a bot knows it is there, seen or not. */
    const val FELT_TICKS = 40
    /** How near a clone has to be for a hit from its owner's side to be put down to it. */
    const val FELT_RANGE = 4.0
    /** How long after first noticing a clone a bot is ready to do something about it: twice its reaction time. */
    fun readyAfter(d: BotDifficulty) = d.reactionTicks * 2
    /** Near enough to turn on: it swings from 2.65 blocks, so this is it arriving. */
    fun turnsOn(distance: Double, reach: Double) = distance <= reach + 1.5
    /** Near enough to hit. */
    fun swings(distance: Double, reach: Double) = distance <= reach + 0.6
    /** Ticks of swinging at a clone with no hit landing before it is given up on (it is out of reach, whatever it looks like). */
    const val PATIENCE = 50
    /** How long a clone that could not be hit is then left alone. */
    const val LEFT_ALONE_TICKS = 200
    fun givesUp(ticksWithoutAHit: Long) = ticksWithoutAHit > PATIENCE
}

/**
 * Somebody in a Shadow Walk cannot be seen at all, but is still there to be hit. Whoever was
 * fighting them swings on at where they were for a moment, and a hit either way gives them away
 * again. Bots used to forget them the instant they vanished, for the whole thirty seconds: their
 * holder won 37% of matches against 19% for the holder of an average item.
 */
object ShadowTactics {
    /** Ticks after the last sign of a hidden opponent (in view, hit by them, hit them) that a bot still knows where to swing. */
    fun memoryTicks(d: BotDifficulty) = when (d) {
        BotDifficulty.NORMAL -> 15
        BotDifficulty.HARD -> 25
        BotDifficulty.HARDER -> 35
        BotDifficulty.EXPERT -> 45
    }
    /** Whether a bot can still go for an opponent: always if they can be seen, and for a moment after a sign of them if not. */
    fun knows(hidden: Boolean, sinceSign: Long, d: BotDifficulty) = !hidden || sinceSign <= memoryTicks(d)
    /**
     * Whether somebody unseen who is now [moved] blocks from the last sign of them, [sinceSign]
     * ticks ago, can have got there on foot or by being knocked there. Further than that they
     * went some other way (a teleport, a death and a new life across the map) and are lost.
     */
    fun followed(moved: Double, sinceSign: Long) = moved <= 4.0 + sinceSign * 0.6
    /** Degrees of unsteadiness added to a swing or a shot at somebody who cannot be seen. */
    const val BLUR = 8.0
}

/**
 * A Crafty Bomb goes off three seconds after it is set down: everybody within four blocks of its
 * middle, and along four lines eighteen blocks out from it, north, south, east and west. Whoever
 * set it down is hurt like anybody else, so they step out between two of the lines and wait.
 */
object CraftyTactics {
    /** An opponent this near is in the fight, and will not be far off in three seconds. */
    const val CLOSE = 4.0
    const val FUSE_TICKS = 60
    const val RADIUS = 4.0
    const val ARM = 18
    /** A line takes anybody within this far of one of its points. */
    const val ARM_REACH = 2.0
    /** How far outside the blast a bot wants to be before it calls itself clear. */
    const val MARGIN = 1.0

    /**
     * Is a spot inside what the bomb will blow up? [dx], [dy], [dz] are from the bomb's middle
     * (the centre of its two-by-two-by-two cube) to somebody's feet; [margin] widens the blast.
     */
    fun inBlast(dx: Double, dy: Double, dz: Double, margin: Double = 0.0): Boolean {
        val round = RADIUS + margin
        if (dx * dx + dy * dy + dz * dz <= round * round) return true
        // The lines run half a block under the middle, with a blast at every block out along them.
        val reach = (ARM_REACH + margin) * (ARM_REACH + margin)
        val up = (dy + 0.5) * (dy + 0.5)
        val east = kotlin.math.abs(dx)
        val south = kotlin.math.abs(dz)
        for (k in 1..ARM) {
            if ((east - k) * (east - k) + up + dz * dz < reach) return true
            if ((south - k) * (south - k) + up + dx * dx < reach) return true
        }
        return false
    }

    /**
     * Where to stand clear, as steps east and south of the bomb's middle: out between two of the
     * lines, the nearest ones first for somebody now [dx], [dz] from it.
     */
    fun refuges(dx: Double, dz: Double): List<Pair<Double, Double>> {
        val out = listOf(4.0 to 4.0, 5.0 to 5.0, 4.0 to 6.0, 6.0 to 4.0, 7.0 to 7.0, 5.0 to 8.0, 8.0 to 5.0)
        return listOf(1, -1).flatMap { sx -> listOf(1, -1).flatMap { sz -> out.map { (x, z) -> x * sx to z * sz } } }
            .sortedBy { (x, z) -> (x - dx) * (x - dx) + (z - dz) * (z - dz) }
    }
}

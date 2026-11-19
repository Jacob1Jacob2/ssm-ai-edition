package dev.betrix.superSmashMobsBrawl.bots

/**
 * The air jump a kit has, as far as planning goes: what it gives ([launch], see [JumpSim]), the
 * name its long jumps are filed under ([id]), and whether a jump with it is something that can be
 * flown in the head from start to finish ([flown]): one push off the ground and one air jump.
 * Where it is, the planner strikes out the jumps of the rules that do not come off and the bot
 * looks before it leaps. A chicken's flight is several flaps and a spider grabs the wall it
 * runs into, so for those two the head is no guide to what they cannot do.
 */
enum class JumpKind(val id: String, val launch: JumpSim.Launch, val flown: Boolean,
                    /** The same jump with something charged first: how much (0 to 1), and what it then gives. Least first. */
                    val charged: List<Pair<Float, JumpSim.Launch>> = emptyList()) {
    STANDARD("standard", JumpSim.STANDARD, true),
    /** The chicken: every flap is at least a standard jump, so it plans with those, and flies the rest its own way. */
    FLAPS("standard", JumpSim.STANDARD, false),
    BOUNCY("bouncy", JumpSim.BOUNCY, true),
    WOLF("wolf", JumpSim.WOLF, true),
    /** The goat crouches on the ground to charge its leap: half takes a second and a half, all of it three. */
    GOAT("goat", JumpSim.GOAT, true, listOf(0.5f to JumpSim.GOAT_HALF, 1f to JumpSim.GOAT_FULL)),
    SPIDER("spider", JumpSim.SPIDER, false);

    /** The highest landing this jump gets onto at all, charged as far as it goes. */
    val rise: Double get() = JumpSim.reachOf(charged.lastOrNull()?.second ?: launch).rise
}

/**
 * How a kit gets around, as far as planning a route goes.
 *
 * Every kit used to be planned for as if it had one standard double jump, so a chicken with eight
 * flaps and a spider that walks up walls were told "there is no way up there" about roofs they
 * could simply fly or climb to. The numbers are deliberately short of what the kit can really do:
 * a route is a promise that the jump will be made.
 *
 * @param up the highest ledge it gets onto from standing right beside it, by the rules
 * @param across the widest gap it jumps to a landing at about its own height, by the rules
 * @param climb how far it can go straight up a wall it is touching (0 for everyone but the spider)
 * @param jumps its air jump: what the long jumps flown in the head for it are flown with
 */
data class Mobility(val up: Double, val across: Double, val climb: Double = 0.0, val jumps: JumpKind = JumpKind.STANDARD) {
    /** Has a jump to use in the air at all. */
    val airborne get() = up > 1.3

    /**
     * The kind of fighter whose long jumps this one plans with ([ArenaJumps] keeps one set for
     * each): its own, but for the chicken, which uses the ones worked out for a standard jump.
     */
    val home: Mobility get() = if (jumps == JumpKind.FLAPS) JUMPER else this

    companion object {
        /** No air jump in hand: steps, hops and short running jumps only. */
        val WALKER = Mobility(1.25, 3.0)
        /** The standard double jump. */
        val JUMPER = Mobility(4.5, 6.0)
        /** The chicken: eight flaps on a full bar, each worth about four blocks of height. */
        val FLIER = Mobility(24.0, 7.0, jumps = JumpKind.FLAPS)
        /** The spider: a leap that goes exactly where it looks, and walls are just more floor. */
        val CLIMBER = Mobility(5.0, 6.0, 48.0, JumpKind.SPIDER)
        /** The slime and the magma cube: the same jump, stronger, and never a low one. */
        val BOUNCER = Mobility(4.5, 6.0, jumps = JumpKind.BOUNCY)
        /** The wolf: a pounce that goes exactly where it looks. */
        val POUNCER = Mobility(4.5, 6.0, jumps = JumpKind.WOLF)
        /** The goat: full height whichever way it looks. */
        val LEAPER = Mobility(4.5, 6.0, jumps = JumpKind.GOAT)

        /** Every kind of fighter that has long jumps of its own worked out. */
        val HOMES = listOf(JUMPER, BOUNCER, POUNCER, LEAPER, CLIMBER)

        /**
         * A ledge well overhead is taken one of two ways. Straight up from where the fighter
         * stands and then across with the air jump: that needs only the column overhead to be
         * open, but the air jump alone has to cover the whole distance. Or at a run, jumping
         * forward and finishing with the air jump: that goes further, and needs the direct line
         * to be open instead. The planner checks the path of whichever applies and the bot then
         * jumps that way, so both must ask this one question.
         */
        fun straightUp(rise: Double, across: Double): Boolean = rise > 1.25 && across <= (if (rise > 3.0) 4.0 else 5.0)

        /**
         * A chicken or a spider is planned for at full strength even when it is out of breath:
         * both get it back standing still for a few seconds, and the bot waits for that at the
         * foot of the climb rather than deciding there is no way up.
         */
        fun of(kitId: String?, airJump: Boolean): Mobility = when (kitId) {
            "chicken" -> FLIER
            "spider" -> CLIMBER
            else -> if (!airJump) WALKER else when (kitId) {
                "slime", "magma_cube" -> BOUNCER
                "wolf" -> POUNCER
                "goat" -> LEAPER
                else -> JUMPER
            }
        }
    }
}

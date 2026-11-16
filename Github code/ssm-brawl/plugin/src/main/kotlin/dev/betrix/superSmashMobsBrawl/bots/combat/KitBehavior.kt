package dev.betrix.superSmashMobsBrawl.bots.combat

import dev.betrix.superSmashMobsBrawl.bots.AbilityHint
import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/** One button press in a combo, [delayTicks] after the step before it. */
sealed interface ComboStep {
    val delayTicks: Int
    data class Ability(val id: String, override val delayTicks: Int = 0) : ComboStep
    data class Melee(override val delayTicks: Int = 0) : ComboStep
}

/**
 * A chain that starts when the bot uses [opener].
 *
 * @param mechanical the follow-up is part of how the ability works (press again to detonate), so
 *   every difficulty performs it. Other combos are skill: Expert bots never drop them, lower
 *   difficulties only sometimes continue.
 */
data class Combo(val opener: String, val steps: List<ComboStep>, val mechanical: Boolean = false)

/** What an ability choice is made from. */
data class AbilityOption(val id: String, val hint: AbilityHint, val cooldownSeconds: Double)

/** What a bot weighs when it picks an ability. Health is 0..1, distances are blocks, speed is blocks per tick. */
data class FightSituation(
    val distance: Double,
    val targetHealth: Double = 1.0,
    val targetSpeed: Double = 0.0,
    val targetAirborne: Boolean = false,
    /** A good hit would knock the opponent off the map. */
    val targetNearEdge: Boolean = false,
    /** How far ahead of the bot, toward the opponent, there is still ground to land on. */
    val groundAhead: Double = 32.0,
    /** The same behind the bot, for abilities that recoil. */
    val groundBehind: Double = 32.0,
    /** The bot is at an edge or in the air, where it may need its recovery in a moment. */
    val exposed: Boolean = false,
    /** It still has its double jump, so spending a recovery ability on an attack is affordable. */
    val airJump: Boolean = true,
)

/**
 * Kit-specific knowledge. A kit registers one of these to teach bots its combos and ability
 * priorities; kits without one use [DefaultKitBehavior], which reasons from the ability hints.
 */
interface KitBehavior {
    val combos: List<Combo> get() = emptyList()

    /** How good a moment this is for [option]: around 0 is a waste, around 1 is ideal. */
    fun value(option: AbilityOption, s: FightSituation): Double = AbilityPlanner.value(option, s)
}

object DefaultKitBehavior : KitBehavior

object KitBehaviors {
    private val registered = HashMap<String, KitBehavior>()

    init {
        fun ability(id: String, after: Int) = ComboStep.Ability(id, after)
        // Abilities that are pressed a second time to finish.
        fun repress(id: String) = Combo(id, listOf(ability(id, 14)), mechanical = true)
        fun kit(id: String, vararg combos: Combo) = register(id, object : KitBehavior { override val combos = combos.toList() })

        kit("goat", repress("goated_ram"))
        // Drag them in with the axe, then splash; a marked target takes more from everything.
        kit("guardian", repress("water_splash"), Combo("whirlpool_axe", listOf(ability("water_splash", 14))),
            Combo("target_laser", listOf(ability("whirlpool_axe", 8))))
        // A dazed target cannot get out of the way of the bats.
        kit("witch", repress("bat_wave"), Combo("daze_potion", listOf(ability("bat_wave", 10))))
        // A tackled target takes extra knockback from the strike.
        kit("wolf", Combo("cub_tackle", listOf(ability("wolf_strike", 12))))
        // Eggs slow them down enough for the slow missile to connect.
        kit("chicken", Combo("egg_blaster", listOf(ability("chicken_missile", 18))))
        // Hook them in, then break the ground under them.
        kit("iron_golem", Combo("iron_hook", listOf(ability("fissure", 16))))
        kit("creeper", Combo("sulphur_bomb", listOf(ability("explode", 12))))
        // Rush in, then kick them away.
        kit("horse", Combo("bone_rush", listOf(ability("bone_kick", 20))))
        // Bile primes them for the hits that follow.
        kit("zombie", Combo("bile_blaster", listOf(ComboStep.Melee(6), ability("deaths_grasp", 6))))
        kit("spider", Combo("spin_web", listOf(ability("needler", 10))))
        kit("squid", Combo("ink_shotgun", listOf(ability("fish_flurry", 8))))
        kit("enderman", Combo("block_toss", listOf(ability("blink", 14))))
        kit("cow", Combo("angry_herd", listOf(ability("milk_spiral", 12))))
        kit("mooshroom", Combo("strawbury_swirl", listOf(ability("moostrike", 12))))
        kit("polar_bear", Combo("cub_carrier", listOf(ability("fish_gun", 10))))
    }

    fun register(kitId: String, behavior: KitBehavior) { registered[kitId] = behavior }
    fun of(kitId: String?): KitBehavior = registered[kitId] ?: DefaultKitBehavior
}

/** Picks the ability to press, if any is worth pressing, and decides whether a combo is carried through. */
object AbilityPlanner {
    /** How well a difficulty judges the moment: 0.25 for Normal up to 1 for Expert. */
    fun skill(difficulty: BotDifficulty) = when (difficulty) {
        BotDifficulty.NORMAL -> 0.25
        BotDifficulty.HARD -> 0.5
        BotDifficulty.HARDER -> 0.8
        BotDifficulty.EXPERT -> 1.0
    }

    /**
     * How good a moment this is for an ability. An ordinary use in range scores about 0.7; the
     * things that make an ability a waste (or a suicide) push it well under 0.5.
     */
    fun value(o: AbilityOption, s: FightSituation): Double {
        val h = o.hint
        var v = 0.6
        // Best in the middle of its range.
        val half = (h.maxRange - h.minRange) / 2
        if (half > 0) v += 0.15 * (1 - abs(s.distance - (h.minRange + h.maxRange) / 2) / half)
        if (h.speed > 0) {
            // A shot is led, so only what the opponent does after it leaves can make it miss.
            // The slower the shot and the faster they run, the further they can get from it.
            val flight = s.distance / h.speed
            val drift = s.targetSpeed * flight * if (s.targetAirborne) 0.4 else 1.0
            v -= min(0.4, (drift - 1.5).coerceAtLeast(0.0) * 0.1)
            if (s.targetSpeed < 0.05) v += 0.1
        }
        // Knockback grows as health drops, so the big launches finish hurt or cornered opponents.
        if (h.knockback >= 2.0 && (s.targetNearEdge || s.targetHealth < 0.4)) v += 0.3
        v += if (h.damage / 20.0 >= s.targetHealth) 0.25 else min(0.15, h.damage / 60.0)
        // Never dash, leap or recoil off the map.
        if (h.moves > 0 && min(h.moves, s.distance + 1.5) > s.groundAhead) v -= 1.0
        if (h.moves < 0 && -h.moves > s.groundBehind) v -= 1.0
        // The way back onto the map is not for attacking with while standing at the edge.
        if (h.recovery && s.exposed && !s.airJump) v -= 0.7
        if (h.grounded && s.targetAirborne) v -= 0.5
        // The longer the recharge, the better the moment has to be.
        v -= min(0.2, o.cooldownSeconds / 75.0)
        return v
    }

    /**
     * The ability to use now, or null when none is worth it. Normal bots judge the moment badly
     * (their reading of it is noisy) and settle for less; Expert bots read it exactly and wait.
     */
    fun choose(options: List<AbilityOption>, s: FightSituation, difficulty: BotDifficulty,
               kit: KitBehavior, random: Random = Random.Default): AbilityOption? {
        if (options.isEmpty()) return null
        val skill = skill(difficulty)
        val noise = 0.05 + (1 - skill) * 0.55
        val (best, score) = options.map { it to kit.value(it, s) + (random.nextDouble() * 2 - 1) * noise }.maxBy { it.second }
        return best.takeIf { score >= 0.3 + 0.2 * skill }
    }

    fun comboFor(abilityId: String, kit: KitBehavior, difficulty: BotDifficulty, random: Random = Random.Default): Combo? {
        val combo = kit.combos.firstOrNull { it.opener == abilityId } ?: return null
        return combo.takeIf { it.mechanical || random.nextDouble() < difficulty.abilityChance }
    }
}

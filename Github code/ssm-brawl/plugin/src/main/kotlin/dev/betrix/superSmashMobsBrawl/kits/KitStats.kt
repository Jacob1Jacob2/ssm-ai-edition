package dev.betrix.superSmashMobsBrawl.kits

import kotlin.math.abs
import kotlin.math.roundToLong

/** How a player's version of a kit compares with the normal one. */
enum class KitVerdict(val label: String) { NORMAL("Normal"), BUFFED("Buffed"), NERFED("Nerfed") }

/** What a kit is normally. [regen] is null for a kit that does not heal; [cooldowns] is seconds per ability id. */
data class KitBase(val regen: Double?, val armor: Double, val damage: Double, val cooldowns: Map<String, Double>)

/**
 * One player's changes to one kit. A null (or missing) value means "as normal".
 * Plain data with no Bukkit in it, so the rules can be tested on their own.
 */
data class KitStats(val regen: Double? = null, val armor: Double? = null, val damage: Double? = null,
                    val cooldowns: Map<String, Double> = emptyMap()) {

    /** The same changes without the ones that only restate the normal value. */
    fun trimmed(base: KitBase) = KitStats(
        regen?.takeIf { base.regen != null && !same(it, base.regen) },
        armor?.takeIf { !same(it, base.armor) },
        damage?.takeIf { !same(it, base.damage) },
        cooldowns.filter { (id, seconds) -> base.cooldowns[id]?.let { !same(seconds, it) } == true })

    val isEmpty get() = regen == null && armor == null && damage == null && cooldowns.isEmpty()

    /**
     * Buffed as soon as anything is better than normal, because one stronger stat is an advantage
     * whatever else was given up for it. Nerfed only when every change makes the kit worse.
     */
    fun verdict(base: KitBase): KitVerdict {
        val t = trimmed(base)
        if (t.isEmpty) return KitVerdict.NORMAL
        val better = (t.regen != null && t.regen > base.regen!!) || (t.armor != null && t.armor > base.armor) ||
            (t.damage != null && t.damage > base.damage) || t.cooldowns.any { (id, seconds) -> seconds < base.cooldowns.getValue(id) }
        return if (better) KitVerdict.BUFFED else KitVerdict.NERFED
    }

    /**
     * How one ability's tool looks. A cooldown belongs to its own tool only; health regen, armor
     * and attack damage belong to the whole kit and show on the kit's [first] tool.
     */
    fun toolVerdict(base: KitBase, abilityId: String, first: Boolean): KitVerdict {
        val t = trimmed(base)
        return KitStats(t.regen.takeIf { first }, t.armor.takeIf { first }, t.damage.takeIf { first },
            t.cooldowns.filterKeys { it == abilityId }).verdict(base)
    }

    /** "regen=0.3;armor=5.0;cd.ink_shotgun=4.0" */
    fun encode(): String = (listOfNotNull(regen?.let { "regen=$it" }, armor?.let { "armor=$it" }, damage?.let { "damage=$it" }) +
        cooldowns.toSortedMap().map { (id, seconds) -> "cd.$id=$seconds" }).joinToString(";")

    companion object {
        private fun same(a: Double, b: Double) = abs(a - b) < 1e-6

        fun decode(text: String): KitStats {
            val values = text.split(';').mapNotNull { part ->
                val key = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                if (key.isEmpty() || value == null) null else key to value
            }.toMap()
            return KitStats(values["regen"], values["armor"], values["damage"],
                values.filterKeys { it.startsWith("cd.") }.mapKeys { it.key.removePrefix("cd.") })
        }
    }
}

/**
 * The row of squares a stat is shown on in the Change Kit Stats menu. A stat is measured in
 * [units] of one square each (0.10 regen, 1 armor, 1 damage, 2 seconds of cooldown), to the
 * nearest half square.
 */
object StatBar {
    const val FULL = 2
    const val HALF = 1
    const val EMPTY = 0

    fun units(value: Double, step: Double): Double = (value / step * 2).roundToLong() / 2.0

    /** How much of the [square]-th square (counting from 1) is covered. */
    fun fill(units: Double, square: Int): Int = if (units >= square) FULL else if (units >= square - 0.5) HALF else EMPTY

    /** Clicking a square fills the bar up to it; clicking the square the bar already ends on takes half of it back. */
    fun click(units: Double, square: Int): Double = if (units == square.toDouble()) square - 0.5 else square.toDouble()
}

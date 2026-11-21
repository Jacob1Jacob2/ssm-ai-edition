package dev.betrix.superSmashMobsBrawl.items

import java.util.Locale
import kotlin.math.sqrt

/**
 * Adds up what [ItemStats] recorded: for every item, how often it dropped and was taken, what a
 * pickup of it did (damage, kills, healing), how often it was thrown away unused, and how often
 * whoever held it went on to win.
 *
 * Everything here is arithmetic on lines of text, with nothing of the game in it, so that it can
 * be checked by tests.
 */
object ItemReport {
    data class Row(val match: String, val tick: Long, val event: String, val item: String, val actor: String,
                   val target: String, val value: Double, val extra: String)

    /** The lines of one recorded file as rows. [prefix] keeps the matches of two files apart. */
    fun parse(lines: Sequence<String>, prefix: String = ""): List<Row> = lines.mapNotNull { line ->
        val parts = line.split(',')
        if (parts.size < 8 || parts[0] == "match") return@mapNotNull null
        val tick = parts[1].toLongOrNull() ?: return@mapNotNull null
        Row(prefix + parts[0], tick, parts[2], parts[3], parts[4], parts[5], parts[6].toDoubleOrNull() ?: 0.0, parts.drop(7).joinToString(","))
    }.toList()

    /** One item's sums. Damage, healing and self-damage are in percent of a full health bar. */
    class Line(val item: ArenaItem) {
        var drops = 0
        var pickups = 0
        var uses = 0.0
        var discards = 0
        /** Pickups that went on to hurt, launch or kill somebody. */
        var hitPickups = 0
        var hits = 0
        var damage = 0.0
        var selfDamage = 0.0
        var kills = 0
        var heal = 0.0
        /** Seconds flown (the Flying Car). */
        var fuel = 0.0
        /** Fighters who had it at least once in a match that somebody won, and how many of them were that somebody. */
        var holders = 0
        var holderWins = 0

        val pickedShare: Double? get() = share(pickups.toDouble(), drops)
        val usesEach: Double? get() = each(uses)
        val hitShare: Double? get() = share(hitPickups.toDouble(), pickups)
        val damageEach: Double? get() = each(damage)
        val selfDamageEach: Double? get() = each(selfDamage)
        val killsEach: Double? get() = each(kills.toDouble())
        val healEach: Double? get() = each(heal)
        val fuelEach: Double? get() = each(fuel)
        val discardShare: Double? get() = share(discards.toDouble(), pickups)
        val holderWinShare: Double? get() = share(holderWins.toDouble(), holders)
        private fun each(sum: Double) = if (pickups == 0) null else sum / pickups
    }

    class Totals {
        var matches = 0
        /** Matches that ended with a winner, and those stopped for going on too long. */
        var decided = 0
        var timedOut = 0
        var seconds = 0.0
        var fighters = 0
        var drops = 0
        var pickups = 0
        var deaths = 0
        var itemKills = 0
        var otherKills = 0
        /** Deaths that were nobody's kill: a fall with nobody's help, or the fighter's own doing. */
        var unclaimed = 0
        var damage = 0.0
        var itemDamage = 0.0
        var holders = 0
        var holderWins = 0
        /** How often the holder of an average item won: what an item's own figure is held up against. */
        val baseline: Double? get() = share(holderWins.toDouble(), holders)
    }

    class Report(val totals: Totals, val lines: List<Line>, val otherDamage: Map<String, Double>) {
        fun line(item: ArenaItem) = lines.first { it.item == item }
    }

    private fun share(part: Double, whole: Int): Double? = if (whole == 0) null else part / whole

    private class Held(val item: ArenaItem, val tick: Long) { var hit = false }

    fun build(rows: List<Row>): Report {
        val lines = ArenaItem.entries.associateWith { Line(it) }
        val byId = ArenaItem.entries.associateBy { it.id }
        val totals = Totals()
        val other = HashMap<String, Double>()
        for (events in rows.groupBy { it.match }.values) {
            // (In the order things happened; within a tick, the order they were written in.)
            val sorted = events.sortedBy { it.tick }
            // A match the run was stopped in the middle of has no ending, and says nothing about who would have won it: left out whole.
            val end = sorted.lastOrNull { it.event == "end" } ?: continue
            totals.matches++
            val ending = end.extra.split('|')
            val winners = ending.drop(1).filter { it.isNotEmpty() }.toSet()
            val timedOut = ending.first() == "balance_timeout"
            val decided = !timedOut && winners.isNotEmpty()
            if (timedOut) totals.timedOut++
            if (decided) totals.decided++
            totals.seconds += end.value
            totals.fighters += sorted.count { it.event == "player" }

            val held = HashMap<String, MutableList<Held>>()
            val had = LinkedHashSet<Pair<String, ArenaItem>>()
            // What somebody did with an item is put down to their latest pickup of it.
            fun holding(actor: String, item: ArenaItem, tick: Long) = held[actor]?.lastOrNull { it.item == item && it.tick <= tick }
            for (e in sorted) {
                val item = byId[e.item]
                when (e.event) {
                    "drop" -> if (item != null) { lines.getValue(item).drops++; totals.drops++ }
                    "pickup" -> if (item != null) {
                        lines.getValue(item).pickups++; totals.pickups++
                        held.getOrPut(e.actor) { mutableListOf() } += Held(item, e.tick)
                        had += e.actor to item
                    }
                    "use" -> if (item != null) lines.getValue(item).uses += e.value
                    "fuel" -> if (item != null) lines.getValue(item).fuel += e.value
                    "heal" -> if (item != null) lines.getValue(item).heal += e.value
                    "discard" -> if (item != null) lines.getValue(item).discards++
                    "damage" ->
                        if (e.actor == e.target) { if (item != null) lines.getValue(item).selfDamage += e.value }
                        else {
                            totals.damage += e.value
                            if (item != null) {
                                val line = lines.getValue(item)
                                line.damage += e.value; line.hits++; totals.itemDamage += e.value
                                holding(e.actor, item, e.tick)?.hit = true
                            } else other.merge(e.extra, e.value, Double::plus)
                        }
                    "knock" -> if (item != null && e.actor != e.target) holding(e.actor, item, e.tick)?.hit = true
                    "death" -> {
                        totals.deaths++
                        when {
                            e.actor.isEmpty() || e.actor == e.target -> totals.unclaimed++
                            item != null -> { lines.getValue(item).kills++; totals.itemKills++; holding(e.actor, item, e.tick)?.hit = true }
                            else -> totals.otherKills++
                        }
                    }
                }
            }
            for (pickups in held.values) for (pickup in pickups) if (pickup.hit) lines.getValue(pickup.item).hitPickups++
            if (decided) for ((actor, item) in had) {
                val line = lines.getValue(item)
                line.holders++; totals.holders++
                if (actor in winners) { line.holderWins++; totals.holderWins++ }
            }
        }
        return Report(totals, lines.values.toList(), other)
    }

    /** Fewer pickups than this and an item's figures are too few to say anything by. */
    const val ENOUGH = 20

    /** How much more often than the holder of an average item this one's holder won, in points of a percentage, with its margin. */
    fun lift(line: Line, totals: Totals): Pair<Double, Double>? {
        val own = line.holderWinShare ?: return null
        val baseline = totals.baseline ?: return null
        return (own - baseline) * 100 to sqrt(own * (1 - own) / line.holders) * 100
    }

    private fun median(values: List<Double>): Double? = values.sorted().let {
        if (it.isEmpty()) null else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2
    }

    /**
     * The items that do not sit where their rarity puts them, by the damage a pickup deals: more
     * than twice what is usual for their tier, or less than what is usual for the tier below.
     * Only items picked up [ENOUGH] times, and only among those that deal damage at all.
     */
    fun standouts(report: Report): List<String> {
        val measured = report.lines.filter { it.pickups >= ENOUGH && (it.damageEach ?: 0.0) > 0.0 }
        val usual = ItemRarity.entries.associateWith { tier -> median(measured.filter { it.item.rarity == tier }.mapNotNull { it.damageEach }) }
        val found = ArrayList<String>()
        for (line in measured.sortedByDescending { it.damageEach }) {
            val tier = line.item.rarity
            val own = line.damageEach!!
            val here = usual[tier]
            val below = ItemRarity.entries.getOrNull(tier.ordinal - 1)?.let { usual[it] }
            if (here != null && here > 0 && own >= here * 2 && measured.count { it.item.rarity == tier } >= 3)
                found += "${line.item.label} (${tier.label}): ${one(own)}% a pickup, where ${one(here)}% is usual for its tier"
            else if (below != null && own < below)
                found += "${line.item.label} (${tier.label}): ${one(own)}% a pickup, less than the ${one(below)}% usual for the tier below it"
        }
        return found
    }

    private fun one(value: Double) = "%.1f".format(Locale.ROOT, value)
    private fun whole(value: Double) = "%.0f".format(Locale.ROOT, value)
    private fun percent(value: Double?) = value?.let { whole(it * 100) + "%" } ?: "-"
    private fun figure(value: Double?, zero: String = "-") = value?.let { if (it == 0.0) zero else one(it) } ?: "-"

    /** The report as a page of text: a summary, a table for every rarity, and what stands out. */
    fun markdown(title: String, report: Report): String = buildString {
        val t = report.totals
        appendLine("# $title")
        appendLine()
        if (t.matches == 0) { appendLine("No finished matches were recorded."); return@buildString }
        val average = t.seconds / t.matches
        appendLine("${t.matches} matches (${t.decided} with a winner, ${t.timedOut} stopped for going on too long), " +
            "${one(t.fighters.toDouble() / t.matches)} fighters each, ${(average / 60).toInt()}:${"%02d".format((average % 60).toInt())} on average.")
        appendLine("${t.drops} items dropped, ${t.pickups} were picked up (${percent(share(t.pickups.toDouble(), t.drops))}).")
        if (t.damage > 0) appendLine("Items dealt ${percent(t.itemDamage / t.damage)} of all damage; kits (abilities and melee) dealt the rest.")
        if (t.deaths > 0) appendLine("${t.deaths} deaths: ${t.itemKills} by an item (${percent(share(t.itemKills.toDouble(), t.deaths))}), " +
            "${t.otherKills} by a kit, ${t.unclaimed} that were nobody's kill.")
        t.baseline?.let { appendLine("The holder of an average item won ${percent(it)} of the matches they had it in: that is what \"Lift\" is measured from.") }
        appendLine()
        appendLine("Damage, healing and self-damage are in percent of a full health bar, per pickup. Kills are per 100 pickups.")
        appendLine("Lift is how much more (or less) often than that average the item's holder won, in points, give or take the figure after it;")
        appendLine("a lift smaller than twice that figure is as likely chance as the item.")
        for (tier in ItemRarity.entries.reversed()) {
            val lines = report.lines.filter { it.item.rarity == tier }.sortedWith(compareByDescending<Line> { it.killsEach ?: -1.0 }.thenByDescending { it.damageEach ?: -1.0 })
            appendLine()
            appendLine("## ${tier.label}")
            appendLine()
            // Of the kills items made, not of every kill: kits make most kills, and against all of
            // them every item rounded to nothing.
            appendLine("| Item | Dropped | Picked up | Uses | Hit someone | Damage | Kills /100 | Of item kills | Healed | Hurt its user | Thrown away | Lift |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            for (line in lines) {
                val lifted = lift(line, t)?.let { (points, margin) -> "${if (points >= 0) "+" else ""}${one(points)} ±${one(margin)}" } ?: "-"
                appendLine("| ${line.item.label} | ${line.drops} | ${line.pickups} | ${figure(line.usesEach)} | ${percent(line.hitShare)} | " +
                    "${figure(line.damageEach)} | ${figure(line.killsEach?.times(100))} | ${percent(share(line.kills.toDouble(), t.itemKills))} | " +
                    "${figure(line.healEach)} | ${figure(line.selfDamageEach)} | ${percent(line.discardShare)} | $lifted |")
            }
        }
        val out = standouts(report)
        appendLine()
        appendLine("## Out of step with their rarity")
        appendLine()
        if (out.isEmpty()) appendLine("Nothing, by damage per pickup.") else out.forEach { appendLine("- $it") }
        val quiet = report.lines.filter { it.pickups >= ENOUGH && it.damage == 0.0 && it.kills == 0 }
        if (quiet.isNotEmpty()) {
            appendLine()
            appendLine("## Deal no damage of their own")
            appendLine()
            appendLine("Judge these by Lift, healing and how often they are thrown away: " + quiet.joinToString { it.item.label } + ".")
        }
        val few = report.lines.filter { it.pickups < ENOUGH }
        if (few.isNotEmpty()) {
            appendLine()
            appendLine("## Too few pickups to judge")
            appendLine()
            appendLine(few.joinToString { "${it.item.label} (${it.pickups})" } + ".")
        }
        if (report.otherDamage.isNotEmpty() && t.damage > 0) {
            appendLine()
            appendLine("## Where the rest of the damage came from")
            appendLine()
            report.otherDamage.entries.sortedByDescending { it.value }.take(12).forEach { appendLine("- ${it.key}: ${percent(it.value / t.damage)} of all damage") }
        }
        appendLine()
        appendLine("## What these figures leave out")
        appendLine()
        NOTES.forEach { appendLine("- $it") }
    }

    /** What a reader has to know before taking a figure at its word: what was not counted, and who was playing. */
    val NOTES = listOf(
        "Bots played every fighter, so each figure is the item in a bot's hands: an item the bots use badly looks weaker than it is, and one they cannot answer looks stronger.",
        "A tool upgrade becomes the upgrade for a tool the fighter's kit has. A dropped Shovel upgrade can be picked up as an Axe upgrade, so read the three together.",
        "The tool upgrades, the Armor Upgrade and the Mystery Ability do their damage through the kit's own abilities, where it is counted as the kit's: only Lift says anything about them.",
        "Poison is not counted as damage, so the Poison Mist shows who it reached but not what it did to them.",
        "Healing is what the item gave back at once: the First Aid Kit's twenty seconds of regeneration are not in its figure, and the Gilded Apple's is its absorption hearts.",
    )

    /** Every figure, one item a line, for sorting and charting elsewhere. */
    fun csv(report: Report): String = buildString {
        val t = report.totals
        appendLine("item,rarity,drops,pickups,picked_share,uses_each,hit_share,hits,damage_each,self_damage_each,kills,kills_per_100,share_of_deaths,heal_each,fuel_each,discard_share,holders,holder_wins,holder_win_share,lift_points,lift_margin")
        fun n(value: Double?) = value?.let { "%.4f".format(Locale.ROOT, it) } ?: ""
        for (line in report.lines) {
            val lifted = lift(line, t)
            appendLine(listOf(line.item.id, line.item.rarity.label, line.drops, line.pickups, n(line.pickedShare), n(line.usesEach), n(line.hitShare),
                line.hits, n(line.damageEach), n(line.selfDamageEach), line.kills, n(line.killsEach?.times(100)), n(share(line.kills.toDouble(), t.deaths)),
                n(line.healEach), n(line.fuelEach), n(line.discardShare), line.holders, line.holderWins, n(line.holderWinShare),
                n(lifted?.first), n(lifted?.second)).joinToString(","))
        }
    }
}

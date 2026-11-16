package dev.betrix.superSmashMobsBrawl.utils

import java.util.UUID

/** Per-life, recent combat attribution. Self-damage is visible but cannot steal kill/assist credit. */
object CombatHistory {
    const val LOG_WINDOW_MS = 15_000L
    const val KILL_CREDIT_MS = 10_000L
    data class Hit(val attackerId: UUID, val attacker: String, val amount: Double, val label: String,
        val time: Long, val directKnockback: Boolean = false)
    data class Row(val last: Hit, val total: Double, val order: Int)
    data class Summary(val killer: Hit?, val assists: Int, val rows: List<Row>)

    fun summarize(hits: List<Hit>, victim: UUID, now: Long): Summary {
        val recent = hits.filter {now-it.time in 0..LOG_WINDOW_MS && it.amount.isFinite() &&
            (it.amount>0 || it.amount==0.0 && it.directKnockback)}
        // List order also disambiguates hits received within the same millisecond.
        val killer = recent.asReversed().firstOrNull {it.attackerId!=victim && now-it.time<=KILL_CREDIT_MS}
        val assists = if(killer==null) 0 else recent.asSequence()
            .filter {it.amount>0 && it.attackerId!=victim && it.attackerId!=killer.attackerId}
            .map {it.attackerId}.distinct().count()
        // Never combine different abilities and then label the total as just the last ability.
        val rows = recent.withIndex().groupBy {it.value.attackerId to it.value.label}.values.map {group ->
            Row(group.last().value, group.sumOf {it.value.amount}, group.last().index)
        }.sortedWith(compareByDescending<Row> {
            killer!=null && it.last.attackerId==killer.attackerId && it.last.label==killer.label
        }.thenByDescending {it.last.time}.thenByDescending {it.order})
        return Summary(killer, assists, rows)
    }
}

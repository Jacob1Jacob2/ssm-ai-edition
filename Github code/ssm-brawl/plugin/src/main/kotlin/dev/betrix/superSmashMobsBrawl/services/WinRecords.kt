package dev.betrix.superSmashMobsBrawl.services

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class WinPeriod(val label: String) {
    ALL_TIME("All-Time"), WEEKLY("Weekly"), DAILY("Daily");
    fun next() = entries[(ordinal + 1) % entries.size]
}

@Serializable
data class WinIdentity(val id: String, val name: String, val bot: Boolean, val rank: String = "none")

@Serializable
data class WinnerRecord(val identity: WinIdentity, val days: Map<String, Int> = emptyMap(), val kills: Int = 0)

@Serializable
private data class SavedWins(
    val version: Int = 1,
    val players: Map<String, WinnerRecord> = emptyMap(),
    val matches: Set<String> = emptySet(),
)

data class RankedWins(val identity: WinIdentity, val wins: Int)

/** Main-thread store. Commit the complete result atomically, including the match deduplication key. */
class WinRecords(private val file: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var saved = if (Files.exists(file)) json.decodeFromString<SavedWins>(Files.readString(file)) else SavedWins()

    fun record(matchId: String, winners: List<WinIdentity>, date: LocalDate): Boolean {
        if (matchId in saved.matches || winners.isEmpty()) return false
        val players = saved.players.toMutableMap()
        winners.distinctBy { it.id }.forEach { winner ->
            val days = players[winner.id]?.days.orEmpty().toMutableMap()
            days[date.toString()] = (days[date.toString()] ?: 0) + 1
            players[winner.id] = WinnerRecord(winner, days, players[winner.id]?.kills ?: 0)
        }
        val next = saved.copy(players = players, matches = saved.matches + matchId)
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(next))
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        saved = next
        return true
    }

    fun recordKill(killer: WinIdentity) {
        val old = saved.players[killer.id] ?: WinnerRecord(killer)
        val next = saved.copy(players = saved.players + (killer.id to old.copy(identity = killer, kills = old.kills + 1)))
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(next))
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        saved = next
    }

    fun kills(): List<RankedWins> = saved.players.values.filter { it.kills > 0 }
        .map { RankedWins(it.identity, it.kills) }
        .sortedWith(compareByDescending<RankedWins> { it.wins }.thenBy { it.identity.name.lowercase() }.thenBy { it.identity.id })

    fun standings(period: WinPeriod, today: LocalDate): List<RankedWins> {
        val start = when (period) {
            WinPeriod.ALL_TIME -> LocalDate.MIN
            WinPeriod.WEEKLY -> today.minusDays((today.dayOfWeek.value - 1).toLong())
            WinPeriod.DAILY -> today
        }
        return saved.players.values.map { entry ->
            RankedWins(entry.identity, entry.days.entries.sumOf { (day, wins) ->
                val date = LocalDate.parse(day)
                if (date >= start && date <= today) wins else 0
            })
        }.filter { it.wins > 0 }.sortedWith(compareByDescending<RankedWins> { it.wins }
            .thenBy { it.identity.name.lowercase() }.thenBy { it.identity.id })
    }
}

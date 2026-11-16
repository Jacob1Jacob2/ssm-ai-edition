package dev.betrix.superSmashMobsBrawl.events

import gg.flyte.twilight.event.TwilightEvent
import org.bukkit.entity.Player

class QueuePopEvent(val minigameId: String, val players: List<Player>,
    val setup: dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup? = null,
    val host: java.util.UUID? = null,
    val lobbyId: Int = players.firstOrNull()?.let { dev.betrix.superSmashMobsBrawl.services.LobbyService.lobbyOf(it) } ?: 1) : TwilightEvent()

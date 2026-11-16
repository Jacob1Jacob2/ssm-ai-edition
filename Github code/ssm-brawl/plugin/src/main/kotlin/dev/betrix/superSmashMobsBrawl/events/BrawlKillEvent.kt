package dev.betrix.superSmashMobsBrawl.events

import gg.flyte.twilight.event.TwilightEvent
import org.bukkit.entity.Player

/** Awarded from the same damage log as the death message, including void knockouts. */
class BrawlKillEvent(val killer: Player, val victim: Player) : TwilightEvent()

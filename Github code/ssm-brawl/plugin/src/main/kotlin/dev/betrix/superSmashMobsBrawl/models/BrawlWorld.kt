package dev.betrix.superSmashMobsBrawl.models

import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.HubMapDef
import org.bukkit.World

sealed interface BrawlWorld {
    val world: World
}

data class BrawlGameWorld(override val world: World, val data: GameMapDef,
    val transform: dev.betrix.superSmashMobsBrawl.services.MapTransform = dev.betrix.superSmashMobsBrawl.services.MapTransform()) : BrawlWorld

data class BrawlHubWorld(override val world: World, val data: HubMapDef) : BrawlWorld

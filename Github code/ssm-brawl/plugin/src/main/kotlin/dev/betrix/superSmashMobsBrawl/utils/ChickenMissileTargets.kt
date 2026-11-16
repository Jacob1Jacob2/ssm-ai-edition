package dev.betrix.superSmashMobsBrawl.utils

import dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons
import dev.betrix.superSmashMobsBrawl.items.ItemEffects
import dev.betrix.superSmashMobsBrawl.services.MinigameService
import org.bukkit.GameMode
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object ChickenMissileTargets : KoinComponent {
    private val games: MinigameService by inject()
    const val HITBOX_SCALE = 1.2

    fun canHit(owner: Player, target: LivingEntity): Boolean {
        if (!target.isValid || target.isDead || target.world != owner.world) return false
        val game = games.getMinigameForPlayer(owner) ?: return false
        val fighter = target as? Player ?: ClassicSummons.owners[target.uniqueId]
            ?: ItemEffects.cloneOwners[target.uniqueId] ?: return false
        return fighter != owner && fighter.gameMode == GameMode.SURVIVAL &&
            games.getMinigameForPlayer(fighter) === game &&
            !game.respawnManager.isRespawning(fighter) && !game.arePlayersOnSameTeam(owner, fighter)
    }

    fun enlarged(box: BoundingBox): BoundingBox = box.clone().expand(
        box.widthX * (HITBOX_SCALE-1)/2, box.height * (HITBOX_SCALE-1)/2, box.widthZ * (HITBOX_SCALE-1)/2)
}

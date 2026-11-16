package dev.betrix.superSmashMobsBrawl.disguises

import dev.betrix.superSmashMobsBrawl.models.Hitbox
import me.libraryaddict.disguise.disguisetypes.DisguiseType
import me.libraryaddict.disguise.disguisetypes.MobDisguise
import org.bukkit.entity.Player

class VillagerDisguise(player: Player) : BrawlDisguise(player) {
    override val disguise = MobDisguise(DisguiseType.VILLAGER)
    override val hitbox = Hitbox(0.6, 1.95)

    fun setArt(art: dev.betrix.superSmashMobsBrawl.abilities.VillagerArt) {
        val watcher = disguise.watcher as? me.libraryaddict.disguise.disguisetypes.watchers.VillagerWatcher ?: return
        watcher.profession = when (art) {
            dev.betrix.superSmashMobsBrawl.abilities.VillagerArt.BUTCHER -> org.bukkit.entity.Villager.Profession.BUTCHER
            dev.betrix.superSmashMobsBrawl.abilities.VillagerArt.BLACKSMITH -> org.bukkit.entity.Villager.Profession.TOOLSMITH
            dev.betrix.superSmashMobsBrawl.abilities.VillagerArt.SPEEDSTER -> org.bukkit.entity.Villager.Profession.NITWIT
            else -> org.bukkit.entity.Villager.Profession.NONE
        }
    }
}

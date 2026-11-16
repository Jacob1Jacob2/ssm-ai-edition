package dev.betrix.superSmashMobsBrawl.disguises

import dev.betrix.superSmashMobsBrawl.models.Hitbox
import me.libraryaddict.disguise.disguisetypes.DisguiseType
import me.libraryaddict.disguise.disguisetypes.MobDisguise
import org.bukkit.entity.Player

class MooshroomDisguise(player: Player) : BrawlDisguise(player) {
    override val disguise =
        MobDisguise(DisguiseType.MUSHROOM_COW).apply {
            (watcher as me.libraryaddict.disguise.disguisetypes.watchers.MushroomCowWatcher).variant = org.bukkit.entity.MushroomCow.Variant.RED
        }
    override val hitbox = Hitbox(0.9, 1.4)
}

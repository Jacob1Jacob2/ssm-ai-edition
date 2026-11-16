package dev.betrix.superSmashMobsBrawl.disguises

import dev.betrix.superSmashMobsBrawl.models.Hitbox
import me.libraryaddict.disguise.disguisetypes.DisguiseType
import me.libraryaddict.disguise.disguisetypes.MobDisguise
import org.bukkit.entity.Player

class PhantomDisguise(player: Player) : BrawlDisguise(player) {
    override val disguise =
        MobDisguise(DisguiseType.PHANTOM)
    override val hitbox = Hitbox(0.9, 0.5)
}

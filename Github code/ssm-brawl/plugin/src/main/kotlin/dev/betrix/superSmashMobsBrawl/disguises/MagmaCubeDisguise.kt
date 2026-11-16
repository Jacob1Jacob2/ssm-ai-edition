package dev.betrix.superSmashMobsBrawl.disguises

import dev.betrix.superSmashMobsBrawl.models.Hitbox
import me.libraryaddict.disguise.disguisetypes.DisguiseType
import me.libraryaddict.disguise.disguisetypes.MobDisguise
import me.libraryaddict.disguise.disguisetypes.watchers.SlimeWatcher
import org.bukkit.entity.Player

class MagmaCubeDisguise(player: Player) : BrawlDisguise(player) {
    override val disguise = MobDisguise(DisguiseType.MAGMA_CUBE)
    private var size = 1
    override val hitbox get() = Hitbox(0.51 * size, 0.51 * size)

    override fun setup() { setSize(1); super.setup() }

    fun setSize(size: Int) {
        this.size = size.coerceIn(1, 4)
        (disguise.watcher as? SlimeWatcher)?.size = this.size
    }
}

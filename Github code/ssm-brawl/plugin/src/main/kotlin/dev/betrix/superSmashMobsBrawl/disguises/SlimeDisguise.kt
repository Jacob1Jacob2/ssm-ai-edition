package dev.betrix.superSmashMobsBrawl.disguises

import dev.betrix.superSmashMobsBrawl.models.Hitbox
import dev.betrix.superSmashMobsBrawl.utils.Atom
import gg.flyte.twilight.scheduler.repeatingTask
import me.libraryaddict.disguise.disguisetypes.DisguiseType
import me.libraryaddict.disguise.disguisetypes.MobDisguise
import me.libraryaddict.disguise.disguisetypes.watchers.SlimeWatcher
import org.bukkit.entity.Player

class SlimeDisguise(player: Player) : BrawlDisguise(player) {
    override val disguise = MobDisguise(DisguiseType.SLIME)
    override val hitbox get() = visibleSize(slimeSizeAtom.value).let { Hitbox(0.51 * it, 0.51 * it) }

    private val slimeSizeAtom = Atom(1)

    override fun setup() {
        setSize(1)
        super.setup()

        runnables.add(repeatingTask(1) {
            slimeSizeAtom.value = when(player.exp) {
                in 0.0..0.55 -> 1
                in 0.55..0.8 -> 2
                else -> 3
            }
        })

        slimeSizeAtom.subscribe { size ->
            setSize(size)
        }
    }

    override fun teardown() {
        super.teardown()
        slimeSizeAtom.unsubscribeAll()
    }

    fun setSize(size: Int) {
        (disguise.watcher as? SlimeWatcher)?.let { val wanted=visibleSize(size);if(it.size!=wanted)it.size=wanted }
    }
    private fun visibleSize(size:Int):Int = when {
        dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"slime:3") -> 12
        dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"slime:2") -> (if(size>=3)4 else size)*2
        else -> if(size>=3)4 else size
    }

    fun getSize(): Int = slimeSizeAtom.value
}

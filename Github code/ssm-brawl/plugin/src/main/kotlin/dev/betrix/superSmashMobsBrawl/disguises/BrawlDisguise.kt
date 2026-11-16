package dev.betrix.superSmashMobsBrawl.disguises

import dev.betrix.superSmashMobsBrawl.Manageable
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.models.Hitbox
import gg.flyte.twilight.event.event
import java.util.concurrent.ConcurrentHashMap
import me.libraryaddict.disguise.DisguiseAPI
import me.libraryaddict.disguise.disguisetypes.MobDisguise
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.util.BoundingBox

abstract class BrawlDisguise(protected val player: Player) : Manageable() {
    private var active = false
    protected abstract val disguise: MobDisguise
    protected abstract val hitbox: Hitbox

    val boundingBox: BoundingBox
        get() {
            val location = player.location
            val centerX = location.x
            val centerY = location.y + (hitbox.height / 2.0)
            val centerZ = location.z

            val halfWidth = hitbox.width / 2.0
            val halfHeight = hitbox.height / 2.0

            return BoundingBox.of(
                location.toVector().setX(centerX).setY(centerY).setZ(centerZ),
                halfWidth,
                halfHeight,
                halfWidth,
            )
        }

    companion object {
        private val playerDisguises = ConcurrentHashMap<Player, BrawlDisguise>()

        fun getDisguise(player: Player): BrawlDisguise? {
            return playerDisguises[player]
        }

        fun hasDisguise(player: Player): Boolean {
            return playerDisguises.containsKey(player)
        }

        /** Removes a disguise from the registry (internal use only) */
        internal fun removeDisguise(player: Player, expected: BrawlDisguise) {
            playerDisguises.remove(player, expected)
        }

        /** Registers a disguise in the registry (internal use only) */
        internal fun registerDisguise(player: Player, disguise: BrawlDisguise) {
            playerDisguises[player]?.takeIf { it !== disguise }?.teardown()
            playerDisguises[player] = disguise
        }
    }

    override fun setup() {
        registerDisguise(player, this)
        active = true

        disguise.entity = player
        // An explicit empty stack hides equipment; null would inherit player armor.
        disguise.watcher.armor = Array(4) { org.bukkit.inventory.ItemStack.empty() }
        // No "You are disguised as a Chicken" action bar: it would cover the ability recharge bar.
        // (null meant "use the LibsDisguises default", which is the action bar.)
        disguise.notifyBar = me.libraryaddict.disguise.DisguiseConfig.NotifyBar.NONE
        disguise.isSelfDisguiseVisible = false
        // Keep the owner's real inventory visible; watcher armor still hides it on the mob.
        disguise.setHideArmorFromSelf(false)
        disguise.setHideHeldItemFromSelf(false)
        disguise.setReplaceSounds(false) // Combat explicitly layers the mob and player hurt cues.

        // Ranks and levels belong to chat; the tab name is the plain colored player name.
        val shownName =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .serialize(player.playerListName())
        disguise.watcher.customName = shownName
        disguise.watcher.isCustomNameVisible = true

        DisguiseAPI.disguiseToAll(player, disguise)
        disguise.startDisguise()
        player.updateInventory()

        // Repair a removed visual without overwriting an active Smash transformation.
        runnables.add(gg.flyte.twilight.scheduler.repeatingTask(20) {
            if (active && getDisguise(player) === this@BrawlDisguise && player.isOnline &&
                DisguiseAPI.getDisguise(player) == null) refreshVisual()
        })
        listeners.add(
            event<PlayerQuitEvent> {
                if (player != this@BrawlDisguise.player) {
                    return@event
                }

                this@BrawlDisguise.teardown()
            }
        )
    }

    fun refreshVisual() {
        if (!active || getDisguise(player) !== this || !player.isOnline) return
        DisguiseAPI.disguiseToAll(player, disguise)
        disguise.startDisguise()
        player.updateInventory()
    }

    fun isInvisible(): Boolean = disguise.watcher.isInvisible
    fun setInvisible(value: Boolean) { disguise.watcher.isInvisible = value }

    override fun teardown() {
        if (!active) return
        active = false
        super.teardown()
        disguise.stopDisguise()
        disguise.removeDisguise()

        removeDisguise(player, this)
    }
}

package dev.betrix.superSmashMobsBrawl.items

import com.google.common.collect.HashMultimap
import dev.betrix.superSmashMobsBrawl.kits.BrawlKit
import dev.betrix.superSmashMobsBrawl.services.KitService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** A pickup bonus belongs to one kit/life and protects through the fourth accepted hit. */
object DiamondHelmet {
    const val ARMOR_BARS = 2.5
    private val bonusKey = NamespacedKey("ssm", "diamond_helmet_bonus")
    private data class Protection(val kit: BrawlKit, var original: ItemStack?, var hits: Int = 4)
    private val active = mutableMapOf<UUID, Protection>()

    fun hitsRemaining(player: Player): Int = active[player.uniqueId]
        ?.takeIf { it.kit === KitService.getKitForPlayer(player) }?.hits ?: 0

    fun armorBonus(player: Player) = if (hitsRemaining(player) > 0) ARMOR_BARS else 0.0

    fun equip(player: Player): Boolean {
        val kit = KitService.getKitForPlayer(player) ?: return false
        if (active[player.uniqueId]?.kit !== kit) clear(player)
        val protection = active.getOrPut(player.uniqueId) { Protection(kit, player.inventory.helmet?.clone()) }
        // A kit ability may have replaced the helmet since the last server tick.
        if (ItemService.kind(player.inventory.helmet) != ArenaItem.DIAMOND_HELMET) {
            protection.original = player.inventory.helmet?.clone()
        }
        protection.hits = ArenaItem.DIAMOND_HELMET.uses
        apply(player, protection)
        player.playSound(player.location, Sound.ITEM_ARMOR_EQUIP_DIAMOND, .8f, 1f)
        return true
    }

    private fun apply(player: Player, protection: Protection) {
        val helmet = ItemService.stack(ArenaItem.DIAMOND_HELMET, protection.hits)
        helmet.editMeta { meta ->
            // Replace vanilla diamond armor with the kit's original head attributes plus five
            // armor points, so the visible bar gains exactly 2.5 icons, not the default 1.5.
            val modifiers = HashMultimap.create<Attribute, AttributeModifier>()
            protection.original?.takeUnless { it.type.isAir }?.let { original ->
                modifiers.putAll(original.itemMeta?.attributeModifiers
                    ?: original.type.getDefaultAttributeModifiers(EquipmentSlot.HEAD))
            }
            modifiers.put(Attribute.ARMOR, AttributeModifier(bonusKey, ARMOR_BARS * 2,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD))
            meta.setAttributeModifiers(modifiers)
        }
        player.inventory.setItem(EquipmentSlot.HEAD, helmet)
    }

    /** Keep art changes intact, while reapplying the remaining pickup protection. */
    fun refresh(player: Player) {
        val protection = active[player.uniqueId] ?: return
        if (protection.kit !== KitService.getKitForPlayer(player)) { clear(player); return }
        if (ItemService.kind(player.inventory.helmet) != ArenaItem.DIAMOND_HELMET) {
            protection.original = player.inventory.helmet?.clone()
            apply(player, protection)
        }
    }

    fun hit(player: Player) {
        val protection = active[player.uniqueId] ?: return
        if (protection.kit !== KitService.getKitForPlayer(player)) { clear(player); return }
        if (--protection.hits > 0) {
            refresh(player)
            apply(player, protection)
        } else {
            clear(player)
            player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, .8f, 1f)
            player.sendMessage(Component.text("Item> ", NamedTextColor.BLUE)
                .append(Component.text("Your Armor Upgrade broke!", NamedTextColor.GRAY)))
        }
    }

    fun clear(player: Player) {
        val protection = active.remove(player.uniqueId) ?: return
        if (ItemService.kind(player.inventory.helmet) == ArenaItem.DIAMOND_HELMET) {
            player.inventory.setItem(EquipmentSlot.HEAD, protection.original?.clone() ?: ItemStack.empty())
        }
    }
}

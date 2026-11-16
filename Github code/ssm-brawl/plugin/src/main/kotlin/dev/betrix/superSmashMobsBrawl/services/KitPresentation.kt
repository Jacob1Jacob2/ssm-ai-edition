package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.extensions.isSword
import dev.betrix.superSmashMobsBrawl.models.brawlData.AbilityUsage
import dev.betrix.superSmashMobsBrawl.models.brawlData.KitDef
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.BlocksAttacks
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Preview equipment shares combat item formatting, but never installs abilities or passives. */
object KitPresentation : KoinComponent {
    private val lang: LangService by inject()
    private val data: DataService by inject()
    private val plugin: JavaPlugin by inject()
    private val previewKey = NamespacedKey("ssm", "kit_preview")

    fun isPreview(item: ItemStack?) = item?.itemMeta?.persistentDataContainer
        ?.has(previewKey, PersistentDataType.BYTE) == true

    private fun mark(item: ItemStack) = item.apply {
        editMeta { it.persistentDataContainer.set(previewKey, PersistentDataType.BYTE, 1) }
    }

    fun abilityItem(kitId: String, id: String, preview: Boolean = false): ItemStack {
        val ability = requireNotNull(data.getAbility(id))
        return ItemStack.of(Material.matchMaterial(ability.hotbarItem) ?: Material.IRON_AXE).apply {
            editMeta { meta ->
                meta.isUnbreakable = true
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
                val usage = if (ability.usage == AbilityUsage.LEFT_CLICK) "Left-Click" else "Right-Click"
                meta.customName(Component.text(usage, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
                    .append(Component.text(" - ", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
                    .append(lang.t("abilities.$id.name").color(lang.kitColor(kitId)).decorate(TextDecoration.BOLD))
                    .decoration(TextDecoration.ITALIC, false))
                meta.lore(listOf(lang.t("messages.abilities.hotbarItemDescription") { "abilityId" to id }))
                if (!preview) meta.persistentDataContainer.set(NamespacedKey(plugin, "abilityId"), PersistentDataType.STRING, id)
            }
            if (preview) mark(this)
            else if (type.isSword) setData(DataComponentTypes.BLOCKS_ATTACKS, BlocksAttacks.blocksAttacks().build())
        }.let(dev.betrix.superSmashMobsBrawl.utils.AbilityTools::prepare)
    }

    fun equipPreview(player: Player, kit: KitDef) {
        kit.abilities.forEach { entry ->
            data.getAbility(entry.id)?.let { player.inventory.setItem(it.itemSlot, KitStatsService.dress(player, kit.id, it.id, abilityItem(kit.id, it.id, true))) }
        }
        KitStatsService.showArmor(player, kit.id)
        kit.passives.firstOrNull { it.id == "arrow_recharge" }?.let { entry ->
            val defaults = data.getPassive(entry.id)?.metadata
            val slot = entry.overrides?.metadata?.get("arrowHotbarSlot")?.content?.toIntOrNull()
                ?: defaults?.get("arrowHotbarSlot")?.content?.toIntOrNull() ?: 3
            val count = entry.overrides?.metadata?.get("maximumArrowCount")?.content?.toIntOrNull()
                ?: defaults?.get("maximumArrowCount")?.content?.toIntOrNull() ?: 3
            player.inventory.setItem(slot, mark(ItemStack.of(Material.ARROW, count)))
        }
        fun armor(material: String?) = material?.let(Material::matchMaterial)?.let { mark(ItemStack.of(it)) }
        player.inventory.setItem(org.bukkit.inventory.EquipmentSlot.HEAD, armor(kit.armorItems?.helmet) ?: ItemStack.empty())
        player.inventory.setItem(org.bukkit.inventory.EquipmentSlot.CHEST, armor(kit.armorItems?.chestplate) ?: ItemStack.empty())
        player.inventory.setItem(org.bukkit.inventory.EquipmentSlot.LEGS, armor(kit.armorItems?.leggings) ?: ItemStack.empty())
        player.inventory.setItem(org.bukkit.inventory.EquipmentSlot.FEET, armor(kit.armorItems?.boots) ?: ItemStack.empty())
    }

    fun clearPreview(player: Player) {
        KitStatsService.showArmor(player, null)
        for (slot in 0 until player.inventory.size) if (isPreview(player.inventory.getItem(slot))) player.inventory.setItem(slot, null)
        if (isPreview(player.itemOnCursor)) player.setItemOnCursor(null)
    }
}

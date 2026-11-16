package dev.betrix.superSmashMobsBrawl.smash

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object SmashItems {
    val FOOD=NamespacedKey("ssm","smash_food")
    fun carrot()=ItemStack.of(Material.GOLDEN_CARROT).apply {
        editMeta {it.displayName(Component.text("Golden Bacon Carrot",NamedTextColor.GOLD).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC,false));it.persistentDataContainer.set(FOOD,PersistentDataType.BYTE,1.toByte())}
        setData(DataComponentTypes.FOOD,io.papermc.paper.datacomponent.item.FoodProperties.food().nutrition(6).saturation(14.4f).canAlwaysEat(true).build())
    }
    private fun description(spec:SmashSpec)=wrap(SmashDescriptions.get(spec))
    private fun wrap(text:String):List<Component> {
        val lines=mutableListOf<String>();var line=""
        text.split(' ').forEach { word ->
            if(line.isNotEmpty() && line.length+word.length+1>44){lines+=line;line=""}
            line=if(line.isEmpty())word else "$line $word"
        }
        if(line.isNotEmpty())lines+=line
        return lines.map {Component.text(it,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)}
    }
    val STAR=NamespacedKey("ssm","smash_star")
    val POWER=NamespacedKey("ssm","smash_power")
    /** Glint is rarity, never the menu's selected-state marker. Index four is magenta Random. */
    fun styleStar(item:ItemStack,index:Int) {
        require(index in 0..4)
        item.editMeta {it.itemModel=NamespacedKey("ssm",if(index==4)"smash_star_random" else "smash_star_$index")}
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,index in 1..3)
    }
    @JvmOverloads fun star(tier:SmashTier,kit:String?=null)=ItemStack.of(Material.NETHER_STAR).apply {
        editMeta {
            it.displayName(Component.text(tier.title,tier.color).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC,false))
            val spec=SmashCatalog.specs.firstOrNull { s -> s.kit==kit && s.tier==tier }
            it.lore(if(spec!=null) listOf(Component.text(spec.name,tier.color).decorate(TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC,false)) + description(spec)
                else wrap("Grants your selected kit's ${tier.title.removeSuffix(" Crystal")} power."))
            it.persistentDataContainer.set(STAR,PersistentDataType.INTEGER,tier.ordinal)
            it.itemModel=NamespacedKey("ssm","smash_star_${tier.ordinal}")
        }
        styleStar(this,tier.ordinal)
    }
    fun crystal(tier:SmashTier)=ItemStack.of(Material.END_CRYSTAL).apply {
        editMeta { it.itemModel=NamespacedKey("ssm","smash_crystal_${tier.ordinal}") }
    }
    fun toolLore(power:SmashPower)=description(power.spec) + Component.empty() + listOf(
        Component.text(if(power.spec.fuelSeconds>0) "Hold to use • ${power.spec.fuelSeconds}s total fuel" else
            if(power.uses>=0) "${power.uses} uses remaining" else if(power.spec.seconds>0) "Lasts ${power.spec.seconds} seconds" else "Lasts for this life",NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false))
    fun heldControl(key:String)=key in setOf("blaze:0","squid:1","squid:3","villager:2","goat:0","snowman:1","slime:1","enderman:1","polar_bear:1","magma_cube:1","zombie:0","zombie:1") || SmashCatalog.specs.any {it.key==key && it.fuelSeconds>0}
    fun tool(power:SmashPower)=ItemStack.of(power.spec.material).apply {
        editMeta {
            it.displayName(Component.text(power.spec.name,power.spec.tier.color).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC,false))
            it.persistentDataContainer.set(POWER,PersistentDataType.STRING,power.spec.key)
            it.lore(toolLore(power))
        }
        setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,!power.spec.material.name.startsWith("NETHERITE_") && (power.spec.tier!=SmashTier.LIGHT || power.spec.key=="zombie:0"))
        if(power.spec.uses>0) {
            setData(DataComponentTypes.MAX_STACK_SIZE,1)
            setData(DataComponentTypes.MAX_DAMAGE,power.spec.uses)
            setData(DataComponentTypes.DAMAGE,(power.spec.uses-power.uses).coerceIn(0,power.spec.uses-1))
        }
        if(power.spec.kind==SmashKind.SUMMON)styleStar(this,power.spec.tier.ordinal)
        if(power.spec.fuelSeconds>0 || power.spec.key in setOf("blaze:0","squid:1","squid:3","villager:2","goat:0","snowman:1","slime:1","enderman:1","polar_bear:1","magma_cube:1","zombie:1"))
            setData(DataComponentTypes.BLOCKS_ATTACKS,io.papermc.paper.datacomponent.item.BlocksAttacks.blocksAttacks().build())
        if(!heldControl(power.spec.key)) {
            if(type !in setOf(Material.BOW,Material.CROSSBOW)) setData(DataComponentTypes.BLOCKS_ATTACKS,io.papermc.paper.datacomponent.item.BlocksAttacks.blocksAttacks().build())
            unsetData(DataComponentTypes.CONSUMABLE)
            unsetData(DataComponentTypes.FOOD)
        }
    }.let(dev.betrix.superSmashMobsBrawl.utils.AbilityTools::prepare)
}

package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.smash.SmashItems
import dev.betrix.superSmashMobsBrawl.smash.SmashTier
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

object SetupSelectionMenus {
    private fun item(type:Material,name:String,lines:List<String> = emptyList(),selected:Boolean=false)=ItemStack.of(type).apply {
        editMeta {
            it.displayName(Component.text(name,if(selected)GREEN else YELLOW).decoration(TextDecoration.ITALIC,false))
            it.lore(lines.map {s -> Component.text(s,GRAY).decoration(TextDecoration.ITALIC,false)})
        }
        setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,selected)
    }

    fun crystals(player:Player) {
        val s=GameSetupService.setupOf(player)
        player.openInventory(brawlGui(Component.text("Choose Smash Crystals"),27, selectionEffects=true) {
            onClick {isCancelled=true}
            SmashTier.entries.forEach {tier ->
                val enabled=tier in s.enabledCrystalTiers
                val total=s.enabledCrystalTiers.sumOf {it.weight}
                val percent=if(enabled && total>0) "%.1f".format(tier.weight*100.0/total) else "0"
                set(10+tier.ordinal*2,item(Material.NETHER_STAR,tier.title,listOf(if(enabled)"Enabled — $percent% of drops" else "Disabled","Click to toggle"),enabled).apply {
                    SmashItems.styleStar(this,tier.ordinal)
                    SelectionIcons.mark(this,enabled)
                    editMeta {it.displayName(Component.text(tier.title,tier.color).decoration(TextDecoration.ITALIC,false))}
                }) {
                    s.enabledCrystalTiers=if(enabled)s.enabledCrystalTiers-tier else s.enabledCrystalTiers+tier
                    GameSetupService.save();GuideMenus.ding(player);crystals(player)
                }
            }
            set(18,item(Material.ARROW,"Back")) {GameSetupService.openMenu(player)}
            set(21,item(if(s.enabledCrystalTiers.size==SmashTier.entries.size)Material.LIME_DYE else Material.GRAY_DYE,"Enable All")) {
                s.enabledCrystalTiers=SmashTier.entries.toSet();GameSetupService.save();GuideMenus.ding(player);crystals(player)
            }
            set(23,item(if(s.enabledCrystalTiers.isEmpty())Material.RED_DYE else Material.GRAY_DYE,"Disable All")) {
                s.enabledCrystalTiers=emptySet();GameSetupService.save();GuideMenus.ding(player);crystals(player)
            }
        })
    }

    /** One dye per number of lives, in the colour that number has on the scoreboard. */
    private val LIFE_DYES=listOf(Material.RED_DYE,Material.ORANGE_DYE,Material.YELLOW_DYE,Material.LIME_DYE,
        Material.LIGHT_BLUE_DYE,Material.MAGENTA_DYE,Material.WHITE_DYE,Material.CYAN_DYE)

    fun lives(player:Player) {
        val s=GameSetupService.setupOf(player)
        player.openInventory(brawlGui(Component.text("Select Lives"),27, selectionEffects=true) {
            onClick {isCancelled=true}
            set(9,item(Material.REDSTONE_BLOCK,"Back to Settings")) {GameSetupService.openMenu(player)}
            LIFE_DYES.forEachIndexed {i,dye ->
                val count=i+1
                val selected=s.lives==count
                set(10+i,item(dye,"",listOf("Every fighter starts with $count ${if(count==1)"life" else "lives"}",
                    if(selected)"Selected" else "Click to select"),selected).apply {
                    amount=count
                    editMeta {
                        it.displayName(dev.betrix.superSmashMobsBrawl.utils.CombatFeedback.livesText("$count ${if(count==1)"Life" else "Lives"}",count)
                            .decoration(TextDecoration.ITALIC,false))
                        // There is no rainbow dye in the game: the pack draws one over the cyan dye.
                        if(count==8)it.itemModel=org.bukkit.NamespacedKey("ssm","rainbow_dye")
                    }
                    SelectionIcons.mark(this,selected)
                }) {
                    s.lives=count
                    GameSetupService.save();GuideMenus.ding(player);lives(player)
                }
            }
        })
    }

    fun opponents(player:Player,page:Int=0) {
        val s=GameSetupService.setupOf(player)
        // Null marks Add Player, separating the built-in roster from custom opponents.
        val roster:List<BotManager.BotProfile?> = BotManager.rosterProfiles().sortedBy {it.name.lowercase()} + CustomBotService.profiles()
        val pages=((roster.size+44)/45).coerceAtLeast(1)
        val current=page.coerceIn(0,pages-1)
        // The count first: "1/3" alone read as one of three chosen, not as the page.
        player.openInventory(brawlGui(Component.text("Opponents ${s.selectedBotNames.size}/${s.bots} — Page ${current+1}/$pages"),54) {
            onClick {isCancelled=true}
            roster.drop(current*45).take(45).forEachIndexed {slot,profile ->
                if(profile==null) {
                    set(slot,item(Material.LIME_DYE,"Add Player",listOf("Add a Minecraft username","Choose their bot difficulty"))) {CustomBotService.prompt(player,current)}
                    return@forEachIndexed
                }
                val selected=profile.name in s.selectedBotNames
                val head=item(Material.PLAYER_HEAD,profile.name,selected=selected).apply {
                    editMeta {
                        it.displayName(dev.betrix.superSmashMobsBrawl.bots.BotIdentity.rosterName(profile).let { name -> if(CustomBotService.isCustom(profile.name)) name.append(Component.text(" (Custom)",GRAY)) else name })
                        it.lore(listOf(
                            Component.text("Roster difficulty: ",GRAY).append(GameSetupService.difficultyLabel(profile.difficulty))
                                .decoration(TextDecoration.ITALIC,false),
                            Component.text("Kit: ${CustomBotService.kitName(s.botKits[profile.name.lowercase()])} — right-click to change",GRAY).decoration(TextDecoration.ITALIC,false),
                            Component.text(if(CustomBotService.isCustom(profile.name))"Custom opponent — manual selection only" else "Built-in opponent",GRAY).decoration(TextDecoration.ITALIC,false),
                            Component.text(if(selected)"Selected — click to remove" else "Click to choose this opponent",GRAY)
                                .decoration(TextDecoration.ITALIC,false)))
                        // Every head here is drawn by the pack, lit the same way whether chosen or not.
                        it.itemModel=org.bukkit.NamespacedKey("ssm",if(selected)"opponent_selected" else "opponent")
                    }
                    // The kit chosen for this opponent is drawn small in the head's bottom-left corner.
                    s.botKits[profile.name.lowercase()]?.takeIf {it in MenuIcons.cornerIcons}?.let {kit ->
                        setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                            io.papermc.paper.datacomponent.item.CustomModelData.customModelData().addString(kit).build())
                    }
                }
                set(slot,head) {
                    if(isRightClick) {CustomBotService.kits(player,profile.name,current);return@set}
                    if(selected)s.selectedBotNames=s.selectedBotNames-profile.name
                    else if(s.selectedBotNames.size<s.bots)s.selectedBotNames=s.selectedBotNames+profile.name
                    else {player.sendMessage(Component.text("Increase the player count to choose more opponents.",YELLOW));return@set}
                    player.playSound(player.location,org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING,0.3f,2f)
                    GameSetupService.save();LobbyBotService.configure(player);opponents(player,current)
                }
                val targetInventory=inventory
                BotSkinService.resolve(profile.name) {skin ->
                    if(player.openInventory.topInventory==targetInventory) {
                        head.editMeta {meta ->
                            if(skin!=null)(meta as SkullMeta).ownerProfile=skin
                        }
                        targetInventory.setItem(slot,head)
                    }
                }
            }
            set(45,item(Material.ARROW,if(current>0)"Previous Page" else "Back to Settings")) {
                if(current>0)opponents(player,current-1) else GameSetupService.openMenu(player)
            }
            set(47,item(Material.LIME_DYE,"Add Player",listOf("Add a Minecraft username"))) {CustomBotService.prompt(player,current)}
            set(49,item(Material.PAPER,"${s.selectedBotNames.size}/${s.bots} chosen",listOf("Choose up to ${s.bots} opponents","Random built-in bots fill the rest")))
            set(51,item(Material.ENDER_EYE,"Random Opponents",listOf("Use random built-in opponents","Custom opponents are never picked randomly","Also resets every kit you chose for them"))) {s.selectedBotNames=emptyList();s.botKits=emptyMap();GameSetupService.save();LobbyBotService.configure(player);opponents(player,current)}
            if(current<pages-1)set(53,item(Material.ARROW,"Next Page")) {opponents(player,current+1)}
        })
    }
}

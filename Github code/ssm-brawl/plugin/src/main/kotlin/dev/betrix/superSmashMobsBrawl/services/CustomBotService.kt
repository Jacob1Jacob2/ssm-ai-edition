package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.*
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.*
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/** Global custom roster; selections and kit overrides belong to the host's saved setup. */
object CustomBotService:KoinComponent {
    private val plugin:SuperSmashMobsBrawl by inject()
    private val file get()=File(plugin.dataFolder,"custom-bots.yml")
    private val saved by lazy {YamlConfiguration.loadConfiguration(file)}
    fun profiles():List<BotManager.BotProfile> = saved.getKeys(false).mapNotNull {key ->
        val name=saved.getString("$key.name") ?: return@mapNotNull null
        BotManager.BotProfile(name,"none",BotDifficulty.parse(saved.getString("$key.difficulty","normal")!!))
    }
    fun isCustom(name:String)=profiles().any {it.name.equals(name,true)}
    fun add(name:String,difficulty:BotDifficulty):Boolean {
        if(!name.matches(Regex("[A-Za-z0-9_]{1,16}")) || BotManager.rosterProfiles().any {it.name.equals(name,true)} || isCustom(name))return false
        val key=name.lowercase()
        saved.set("$key.name",name);saved.set("$key.difficulty",difficulty.name)
        saved.save(file)
        return true
    }
    fun remove(name:String):Boolean {
        if(!isCustom(name))return false
        saved.set(name.lowercase(),null);saved.save(file)
        GameSetupService.forgetBot(name)
        return true
    }
    private fun later(action:()->Unit) {Bukkit.getScheduler().runTask(plugin,Runnable(action))}
    private fun icon(type:Material,name:Component,lines:List<Component> = emptyList())=ItemStack.of(type).apply {
        editMeta {it.displayName(name.decoration(TextDecoration.ITALIC,false));it.lore(lines.map {line->line.decoration(TextDecoration.ITALIC,false)})}
    }
    fun prompt(player:Player,page:Int) {
        player.closeInventory()
        later {
            if(!player.isOnline)return@later
            player.showDialog(Dialog.create {builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Add Player"))
                    .inputs(listOf(DialogInput.text("username",Component.text("Minecraft username")).maxLength(16).build())).build())
                .type(DialogType.confirmation(
                    ActionButton.create(Component.text("OK",GREEN),null,100,
                        DialogAction.customClick({view,audience ->
                            if(audience==player)later {lookup(player,view.getText("username").orEmpty().trim(),page)}
                        },ClickCallback.Options.builder().uses(1).build())),
                    ActionButton.create(Component.text("Cancel",RED),null,100,
                        DialogAction.customClick({_,audience -> if(audience==player)later {SetupSelectionMenus.opponents(player,page)}},ClickCallback.Options.builder().uses(1).build()))))
            })
        }
    }
    private fun lookup(player:Player,name:String,page:Int) {
        if(!player.isOnline)return
        if(!name.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            player.sendMessage(Component.text("Enter a Minecraft username using letters, numbers or underscores.",RED));prompt(player,page);return
        }
        if((BotManager.rosterProfiles()+profiles()).any {it.name.equals(name,true)}) {
            player.sendMessage(Component.text("That player is already in Choose Opponents.",YELLOW));SetupSelectionMenus.opponents(player,page);return
        }
        player.sendMessage(Component.text("Looking up $name…",GRAY))
        BotSkinService.resolve(name) {skin ->
            if(!player.isOnline)return@resolve
            if(skin==null) {
                player.sendMessage(Component.text("Could not verify that Minecraft account. Check the name or try again shortly.",RED))
                SetupSelectionMenus.opponents(player,page)
            } else difficulty(player,skin.name ?: name,page)
        }
    }
    private fun difficulty(player:Player,name:String,page:Int) {
        player.openInventory(brawlGui(Component.text("Difficulty — $name"),27) {
            onClick {isCancelled=true}
            BotDifficulty.entries.forEachIndexed {i,d ->
                set(10+i*2,icon(listOf(Material.LIME_DYE,Material.YELLOW_DYE,Material.ORANGE_DYE,Material.RED_DYE)[i],GameSetupService.difficultyLabel(d))) {
                    if(add(name,d))player.sendMessage(Component.text("Added $name. Select their head to fight them.",GREEN))
                    else player.sendMessage(Component.text("That player has already been added.",YELLOW))
                    SetupSelectionMenus.opponents(player,(BotManager.rosterProfiles().size+profiles().size)/45)
                }
            }
            set(22,icon(Material.ARROW,Component.text("Cancel",YELLOW))) {SetupSelectionMenus.opponents(player,page)}
        })
    }
    fun kitName(id:String?)=id?.split('_')?.joinToString(" "){it.replaceFirstChar(Char::uppercase)} ?: "Random"
    fun kits(player:Player,name:String,page:Int) = KitService.openBotKitSelectionGui(player,name,page)
}

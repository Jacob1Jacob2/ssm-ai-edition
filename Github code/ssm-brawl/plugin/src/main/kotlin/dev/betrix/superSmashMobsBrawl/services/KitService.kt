package dev.betrix.superSmashMobsBrawl.services

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.shynixn.mccoroutine.bukkit.launch
import com.github.shynixn.mccoroutine.bukkit.minecraftDispatcher
import dev.betrix.superSmashMobsBrawl.Manageable
import dev.betrix.superSmashMobsBrawl.events.PlayerDocumentLoaded
import dev.betrix.superSmashMobsBrawl.events.PlayerSelectKitEvent
import dev.betrix.superSmashMobsBrawl.extensions.playErrorSound
import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.kits.BrawlKit
import dev.betrix.superSmashMobsBrawl.models.brawlData.KitDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.KitSwitchingMode
import gg.flyte.twilight.event.event
import io.papermc.paper.datacomponent.DataComponentTypes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

enum class AssignKitError {
    PLAYER_HAS_KIT,
    PLAYER_NOT_ONLINE,
    SETUP_FAILED,
}

object KitService : KoinComponent, Manageable() {
    private val lang: LangService by inject()
    private val plugin: JavaPlugin by inject()
    private val dataService: DataService by inject()
    private val minigameService: MinigameService by inject()
    private val api: ApiService by inject()

    private val playerSelectedKits = ConcurrentHashMap<UUID, String>() // kit id
    private val assignedBrawlKits = ConcurrentHashMap<UUID, BrawlKit>()

    override fun setup() {
        listeners.add(
            event<PlayerQuitEvent> {
                unassignKit(player)
                playerSelectedKits.remove(player.uniqueId)
            }
        )

        listeners.add(
            event<PlayerDocumentLoaded> {
                plugin.launch {
                    withContext(Dispatchers.IO) {
                        val kitId = api.playersGetSelectedKitAsync(player)

                        withContext(plugin.minecraftDispatcher) {
                            // A delayed profile load must not undo a selection the player just made.
                            if (player.isOnline) {
                                playerSelectedKits.putIfAbsent(player.uniqueId, kitId)
                                HotbarService.refreshHubKit(player)
                            }
                        }
                    }
                }
            }
        )
    }

    fun isRandomSelected(player: OfflinePlayer) = playerSelectedKits[player.uniqueId] == "@random"

    fun selectRandomKit(player: Player) {
        playerSelectedKits[player.uniqueId] = "@random"
        HotbarService.refreshHubKit(player)
        plugin.launch { withContext(Dispatchers.IO) { api.playersSetSelectedKitAsync(player, "@random") } }
    }

    fun resolveKitForMatch(player: OfflinePlayer): KitDef =
        if (isRandomSelected(player)) getAllKitData().filter { it.userFacing && it.id in KitMenuLayout.slots }.random()
        else currentSelectedKitForPlayer(player)

    fun playerSelectKit(player: Player, kit: KitDef) {
        playerSelectedKits[player.uniqueId] = kit.id
        HotbarService.refreshHubKit(player)

        // Save to backend asynchronously
        plugin.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    api.playersSetSelectedKitAsync(player, kit.id)
                }.onFailure {
                    plugin.logger.warning(
                        "Failed to save selected kit for ${player.name}: ${it.message}"
                    )
                }
            }
        }

        // Determine if the player should switch kits immediately based on their current minigame
        val currentMinigame = minigameService.getMinigameForPlayer(player)
        val shouldSwitchImmediately =
            currentMinigame?.getKitSwitchingMode() == KitSwitchingMode.IMMEDIATE

        // Dispatch the event
        PlayerSelectKitEvent.call(player, kit, shouldSwitchImmediately)
    }

    fun currentSelectedKitForPlayer(player: OfflinePlayer): KitDef {
        val kitId = playerSelectedKits[player.uniqueId]

        return kitId?.let { dataService.getKit(kitId) } ?: dataService.getKit(defaultKitId())!!
    }

    fun assignKit(player: OfflinePlayer): Result<BrawlKit, AssignKitError> {
        if (
            !playerSelectedKits.containsKey(player.uniqueId) ||
                playerSelectedKits[player.uniqueId] == null
        ) {
            playerSelectedKits[player.uniqueId] = defaultKitId()
        }
        return assignKit(player, resolveKitForMatch(player).id)
    }

    fun assignKit(player: OfflinePlayer, kitId: String): Result<BrawlKit, AssignKitError> {
        if (assignedBrawlKits.containsKey(player.uniqueId)) {
            unassignKit(player)
        }

        if (!player.isOnline) {
            return Err(AssignKitError.PLAYER_NOT_ONLINE)
        }

        val kitData = dataService.getKit(kitId) ?: dataService.getKit(defaultKitId())!!

        val brawlKit = BrawlKit(kitData.id, player.player!!)

        assignedBrawlKits[player.uniqueId] = brawlKit

        // Run setup synchronously on the main thread
        val setupResult =
            try {
                if (Bukkit.isPrimaryThread()) {
                    brawlKit.setup()
                    true
                } else {
                    val future = java.util.concurrent.CompletableFuture<Boolean>()
                    Bukkit.getScheduler()
                        .runTask(
                            plugin,
                            Runnable {
                                try {
                                    brawlKit.setup()
                                    future.complete(true)
                                } catch (e: Exception) {
                                    future.complete(false)
                                    plugin.logger.warning(
                                        "Error during kit setup for player ${player.name}: ${e.message}"
                                    )
                                }
                            },
                        )
                    future.get()
                }
            } catch (e: Exception) {
                plugin.logger.warning(
                    "Error during kit setup for player ${player.name}: ${e.message}"
                )
                false
            }

        if (!setupResult) {
            assignedBrawlKits.remove(player.uniqueId)
            return Err(AssignKitError.SETUP_FAILED)
        }

        return Ok(brawlKit)
    }

    fun assignKit(player: OfflinePlayer, kit: KitDef) {
        if (assignedBrawlKits.containsKey(player.uniqueId)) {
            unassignKit(player)
        }

        if (player.isOnline) {
            val brawlKit = BrawlKit(kit.id, player.player!!)
            assignedBrawlKits[player.uniqueId] = brawlKit

            // Run setup synchronously on the main thread
            val setupResult =
                try {
                    if (Bukkit.isPrimaryThread()) {
                        brawlKit.setup()
                        true
                    } else {
                        val future = java.util.concurrent.CompletableFuture<Boolean>()
                        Bukkit.getScheduler()
                            .runTask(
                                plugin,
                                Runnable {
                                    try {
                                        brawlKit.setup()
                                        future.complete(true)
                                    } catch (e: Exception) {
                                        future.complete(false)
                                        plugin.logger.warning(
                                            "Error during kit setup for player ${player.name}: ${e.message}"
                                        )
                                    }
                                },
                            )
                        future.get()
                    }
                } catch (e: Exception) {
                    plugin.logger.warning(
                        "Error during kit setup for player ${player.name}: ${e.message}"
                    )
                    false
                }

            if (!setupResult) {
                assignedBrawlKits.remove(player.uniqueId)
            }
        }
    }

    fun unassignKit(player: OfflinePlayer): BrawlKit? {
        val kit = assignedBrawlKits.remove(player.uniqueId)
        kit?.let { instance ->
            val runTeardown = {
                try {
                    instance.teardown()
                } catch (e: Exception) {
                    plugin.logger.warning(
                        "Error during kit teardown for player ${player.name}: ${e.stackTraceToString()}"
                    )
                }
            }
            if (Bukkit.isPrimaryThread()) runTeardown()
            else Bukkit.getScheduler().runTask(plugin, Runnable { runTeardown() })
        }
        return kit
    }

    fun getKitForPlayer(player: Player): BrawlKit? = assignedBrawlKits[player.uniqueId]

    fun hasKit(player: Player): Boolean = assignedBrawlKits.containsKey(player.uniqueId)

    fun getKitData(id: String): KitDef? {
        return dataService.getKit(id)
    }

    fun getAllKitData(): List<KitDef> {
        return dataService.getAllKits()
    }

    fun findClosestKitById(id: String): KitDef? {
        if (id.isBlank()) return null

        getKitData(id)?.let {
            return it
        }

        getAllKitData()
            .filter { it.userFacing }
            .find { it.id.equals(id, ignoreCase = true) }
            ?.let {
                return it
            }

        return getAllKitData()
            .filter { it.userFacing }
            .find { it.id.contains(id, ignoreCase = true) }
    }

    fun openKitSelectionGui(player: Player) = openKitSelection(player, null, 0)

    fun openBotKitSelectionGui(player: Player, name: String, page: Int) = openKitSelection(player, name, page)

    private fun selectFromMenu(player: Player, kit: KitDef, botName: String?, page: Int) {
        if (botName != null) {
            val setup = GameSetupService.setupOf(player)
            setup.botKits = setup.botKits + (botName.lowercase() to kit.id)
            // Giving an opponent a kit means wanting them in the match.
            if (setup.selectedBotNames.none { it.equals(botName, ignoreCase = true) }) {
                if (setup.selectedBotNames.size < setup.bots) setup.selectedBotNames = setup.selectedBotNames + botName
                else player.sendMessage(Component.text("Kit saved. Increase the player count to add $botName to the match.", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
            }
            GameSetupService.save()
            LobbyBotService.configure(player)
            SetupSelectionMenus.opponents(player, page)
        } else {
            playerSelectKit(player, kit)
            openKitSelectionGui(player)
            player.sendMessage(lang.t("messages.kits.select.success") { "kitId" to kit.id })
        }
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, .3f, 2f)
    }

    private fun openKitSelection(player: Player, botName: String?, page: Int) {
        val selectedId = if (botName == null) (if(isRandomSelected(player)) "@random" else currentSelectedKitForPlayer(player).id)
            else GameSetupService.setupOf(player).botKits[botName.lowercase()]

        val guiColumns = 9
        val guiRows = 6
        val filledSlots = hashSetOf<Int>()

        val kitSelectionGui =
            brawlGui(if (botName == null) lang.t("gui.kitSelection.title") else Component.text("$botName — Choose Kit"), guiColumns * guiRows, selectionEffects=true) {
                onClick { isCancelled = true }

                getAllKitData()
                    .filter { it.userFacing && it.id in KitMenuLayout.slots }
                    .forEach { kit ->
                        set(
                            KitMenuLayout.slots.getValue(kit.id).apply {
                                filledSlots.add(this)
                            },
                            MenuIcons.kit(kit.id,kit.displayItem!!).apply {
                                val isKitSelected = selectedId == kit.id

                                val meta = itemMeta

                                if (isKitSelected) {
                                    meta.displayName(
                                        lang.t("gui.kitSelection.selectedKitName") {
                                            "kitId" to kit.id
                                        }
                                    )
                                } else {
                                    meta.displayName(
                                        lang.t("gui.kitSelection.kitName") { "kitId" to kit.id }
                                    )
                                }

                                val loreList = statsLore(kit) + listOf(
                                    Component.empty(),
                                    if (isKitSelected && botName == null) Component.text("You already have this kit selected.", net.kyori.adventure.text.format.NamedTextColor.RED)
                                    else Component.text("Left-click to select", net.kyori.adventure.text.format.NamedTextColor.YELLOW),
                                    Component.text("Right-click for more stats", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                                ).map { it.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false) }

                                meta.lore(loreList)

                                itemMeta = meta

                                setData(
                                    DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,
                                    isKitSelected,
                                )
                            },
                        ) {
                            if (isRightClick) { openKitStats(player, kit, botName, page); return@set }
                            if (!isLeftClick) return@set
                            if (botName == null && selectedId == kit.id) {
                                player.sendMessage(lang.t("messages.kits.select.already_selected") { "kitId" to kit.id })
                                player.playErrorSound()
                                return@set
                            }

                            selectFromMenu(player, kit, botName, page)
                        }
                    }

                for (cellIdx in 0 until guiColumns * guiRows) {
                    if (filledSlots.contains(cellIdx)) {
                        continue
                    }

                    set(
                        cellIdx,
                        ItemStack.of(Material.GRAY_STAINED_GLASS_PANE).apply {
                            val meta = itemMeta

                            meta.displayName(Component.empty())

                            itemMeta = meta
                        },
                    )
                }
                fun button(material:Material,name:String,color:net.kyori.adventure.text.format.NamedTextColor)=ItemStack.of(material).apply {
                    editMeta {it.displayName(Component.text(name,color).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false))}
                }
                KitMenuLayout.comingSoon.forEach {slot -> set(slot,button(Material.BLACK_CONCRETE,"Coming Soon",net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY)) }
                set(42,button(Material.ENDER_EYE,"Random Kit",net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE).apply {
                    editMeta { it.itemModel = org.bukkit.NamespacedKey("ssm", "kit_random"); it.setEnchantmentGlintOverride(if(botName == null) isRandomSelected(player) else selectedId == null) }
                }) {
                    if (botName == null) {
                        selectRandomKit(player)
                        openKitSelectionGui(player)
                        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, .3f, 2f)
                    } else {
                        val setup = GameSetupService.setupOf(player)
                        setup.botKits = setup.botKits - botName.lowercase(); GameSetupService.save()
                        LobbyBotService.configure(player)
                        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, .3f, 2f)
                        SetupSelectionMenus.opponents(player, page)
                    }
                }
                val inGame=minigameService.isPlayerInMinigame(player)
                set(45,button(Material.EMERALD_BLOCK,if(botName != null || inGame) "Done" else "Play",net.kyori.adventure.text.format.NamedTextColor.GREEN)) {
                    if (botName != null) SetupSelectionMenus.opponents(player, page)
                    else { player.closeInventory();if(!inGame)GameSetupService.startGame(player) }
                }
                set(53,button(Material.REDSTONE_BLOCK,"Back",net.kyori.adventure.text.format.NamedTextColor.RED)) {
                    if (botName != null) SetupSelectionMenus.opponents(player, page)
                    else if(inGame)player.closeInventory() else GameSetupService.openMenu(player)
                }
            }

        player.openInventory(kitSelectionGui)
    }

    fun openKitStats(player: Player, kit: KitDef) = openKitStats(player, kit, null, 0)

    private fun openKitStats(player: Player, kit: KitDef, botName: String?, page: Int) {
        val gray = net.kyori.adventure.text.format.NamedTextColor.GRAY
        val color = lang.kitColor(kit.id)
        fun display(material: Material, name: Component, lore: List<Component> = emptyList()) = ItemStack.of(material).apply {
            editMeta { meta ->
                meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                meta.lore(lore.map { it.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false) })
            }
        }
        // One chest: weapons top left, smashes top right, passives under the weapons,
        // Back / kit icon / Select along the bottom.
        player.openInventory(brawlGui(lang.t("kits.${kit.id}.name").append(Component.text(" Stats")), 27) {
            onClick { isCancelled = true }
            for(slot in 0..26) set(slot, display(Material.GRAY_STAINED_GLASS_PANE, Component.empty()))
            set(22, MenuIcons.kit(kit.id, kit.displayItem ?: Material.BOOK).apply {
                editMeta { it.displayName(lang.t("kits.${kit.id}.name").color(color).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)); it.lore(statsLore(kit)) }
            })
            dev.betrix.superSmashMobsBrawl.smash.SmashTier.entries.forEachIndexed { index, tier ->
                set(5 + index, GuideMenus.smashItem(kit.id, tier))
            }
            kit.abilities.take(5).forEachIndexed { index, entry ->
                val ability = dataService.getAbility(entry.id) ?: return@forEachIndexed
                val lore = arrayListOf(lang.t("abilities.${entry.id}.description").color(gray),
                    Component.text("Recharge: ${ability.cooldown}s", gray))
                val values = (ability.metadata ?: emptyMap()) + (entry.overrides?.metadata ?: emptyMap())
                for ((label, keys) in listOf("Damage" to listOf("damage", "baseDamage", "projectileDamage", "arrowDamage"),
                    "Radius" to listOf("explosionRadius", "radius"), "Range" to listOf("range", "maxDistance"),
                    "Knockback" to listOf("knockback", "explosionKnockbackMultiplier", "projectileKnockbackModifier"))) {
                    keys.firstNotNullOfOrNull { values[it]?.content }?.let { lore.add(Component.text("$label: $it", gray)) }
                }
                set(index, display(Material.matchMaterial(ability.hotbarItem) ?: Material.IRON_AXE,
                    lang.t("abilities.${entry.id}.name").color(color), lore))
            }
            kit.passives.filter { dataService.getPassive(it.id)?.userFacing == true }.take(5).forEachIndexed { index, entry ->
                val passive = dataService.getPassive(entry.id)!!
                set(9+index, display(passive.displayItem?.let(Material::matchMaterial) ?: Material.PAPER,
                    lang.t("passives.${entry.id}.name").color(color), listOf(lang.t("passives.${entry.id}.description").color(gray))))
            }
            set(18, display(Material.REDSTONE_BLOCK, Component.text("Back", net.kyori.adventure.text.format.NamedTextColor.RED))) {
                openKitSelection(player, botName, page)
            }
            set(26, display(Material.LIME_DYE, Component.text("Select Kit", color))) {
                selectFromMenu(player, kit, botName, page)
            }
        })
    }

    /** The classic kit card: two coloured columns, white values. */
    private fun statLines(kit: KitDef): List<Component> {
        val white = net.kyori.adventure.text.format.NamedTextColor.WHITE
        fun stat(label: String, value: String, color: net.kyori.adventure.text.format.NamedTextColor): Component =
            Component.text("$label: ", color).append(Component.text(value, white))
        fun row(left: Component, gap: String, right: Component) =
            Component.empty().decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
                .append(left).append(Component.text(gap, white)).append(right)
        val regen = kit.passives.firstOrNull { it.id == "regeneration" }
            ?.overrides?.metadata?.get("healAmount")?.content?.toDoubleOrNull() ?: 0.25
        fun line(label: String, value: String, color: net.kyori.adventure.text.format.NamedTextColor) =
            row(stat(label, value, color), "", Component.empty())
        val lines = arrayListOf(
            line("Damage", "${kit.meleeDamage}", net.kyori.adventure.text.format.NamedTextColor.RED),
            line("Armor", "${kit.armor}", net.kyori.adventure.text.format.NamedTextColor.AQUA),
            line("Knockback Taken", "${(kit.knockbackMultiplier * 100).toInt()}%", net.kyori.adventure.text.format.NamedTextColor.YELLOW),
            line("Health Regeneration", "$regen per Second", net.kyori.adventure.text.format.NamedTextColor.GREEN),
        )
        fun extra(label: String, value: String) = lines.add(row(
            stat(label, value, net.kyori.adventure.text.format.NamedTextColor.GOLD), "", Component.empty()))
        if (kit.id == "snowman") extra("On Arctic Aura snow", "7.0 damage")
        if (kit.id == "skeleton") extra("Arrow Damage", "6.0")
        if (kit.id == "villager") {
            extra("Butcher", "7 damage / 4.5 armor")
            extra("Blacksmith", "5 damage / 7.5 armor")
            extra("Speedster", "4 damage / 4 armor")
        }
        return lines
    }

    private fun statsLore(kit: KitDef): List<Component> {
    val loreList = arrayListOf<Component>()
    loreList.addAll(statLines(kit))
    loreList.add(Component.empty())
    loreList.add(lang.t("gui.kitSelection.abilityList.title"))

    // Skills and passives wear the kit's colour; each skill shows its recharge.
    val color = lang.kitColor(kit.id)
    fun entry(name: Component, suffix: String = ""): Component =
        Component.text("  ", color).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
            .append(name.color(color)).append(Component.text(suffix, color))
    kit.abilities.forEach {
        val seconds = dataService.getAbility(it.id)?.cooldown
        val shown = seconds?.let { s -> if (s % 1.0 == 0.0) "${s.toInt()}" else "$s" }
        loreList.add(entry(lang.t("abilities.${it.id}.name"), if (shown == null) "" else " (${shown}s)"))
    }

    loreList.add(lang.t("gui.kitSelection.passiveList.title"))

    val passives = kit.passives.filter { dataService.getPassive(it.id)?.userFacing == true }
    passives.forEach { loreList.add(entry(lang.t("passives.${it.id}.name"))) }
    if (passives.isEmpty()) loreList.add(entry(Component.text("None")))

        return loreList
    }

    private fun defaultKitId(): String {
        return dataService.getKit("skeleton")?.id ?: "skeleton"
    }
}

fun getCenteredSlot(columns: Int, rows: Int, index: Int): Int {
    if (index <= 0) {
        val centerRow = rows / 2
        val centerCol = columns / 2
        return centerRow * columns + centerCol
    }

    val centerRow = rows / 2
    val centerCol = columns / 2

    // Prevent infinite loops when there are more items than slots
    if (index >= columns * rows) {
        return centerRow * columns + centerCol
    }

    // Calculate which "layer" this index belongs to
    var currentIndex = 1
    var layer = 1

    while (currentIndex <= index) {
        val itemsInLayer = getItemsInLayer(layer, centerRow, centerCol, rows, columns)
        if (itemsInLayer == 0) {
            break
        }

        if (currentIndex + itemsInLayer > index) {
            // This index is in the current layer
            val positionInLayer = index - currentIndex
            return getSlotInLayer(layer, positionInLayer, centerRow, centerCol, rows, columns)
        }
        currentIndex += itemsInLayer
        layer++
    }

    // Fallback to center slot
    return centerRow * columns + centerCol
}

private fun getItemsInLayer(
    layer: Int,
    centerRow: Int,
    centerCol: Int,
    rows: Int,
    columns: Int,
): Int {
    var count = 0

    for (r in 0 until rows) {
        for (c in 0 until columns) {
            val manhattanDistance = kotlin.math.abs(r - centerRow) + kotlin.math.abs(c - centerCol)
            if (manhattanDistance == layer) {
                count++
            }
        }
    }

    return count
}

private fun getSlotInLayer(
    layer: Int,
    positionInLayer: Int,
    centerRow: Int,
    centerCol: Int,
    rows: Int,
    columns: Int,
): Int {
    var currentPos = 0

    for (r in 0 until rows) {
        for (c in 0 until columns) {
            val manhattanDistance = kotlin.math.abs(r - centerRow) + kotlin.math.abs(c - centerCol)
            if (manhattanDistance == layer) {
                if (currentPos == positionInLayer) {
                    return r * columns + c
                }
                currentPos++
            }
        }
    }

    // Fallback to center
    return centerRow * columns + centerCol
}

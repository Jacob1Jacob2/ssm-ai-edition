package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.kits.KitBase
import dev.betrix.superSmashMobsBrawl.kits.KitStats
import dev.betrix.superSmashMobsBrawl.kits.KitVerdict
import dev.betrix.superSmashMobsBrawl.kits.StatBar
import dev.betrix.superSmashMobsBrawl.models.brawlData.KitDef
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Change Kit Stats: every player can tune their own copy of each kit (health regen, armor, attack
 * damage and ability cooldowns). The changes are kept per player and per kit, between matches.
 *
 * A kit with anything better than normal is Buffed: it plays, but its wins and kills stay off the
 * leaderboard. A kit that is only worse is Nerfed: it still counts. The tool of a changed ability
 * turns to wood (better) or gold (worse) and says so in its name; that is looks only. Bots always
 * play the normal kit.
 */
object KitStatsService : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val data: DataService by inject()
    private val lang: LangService by inject()
    private val minigames: MinigameService by inject()

    private val NONE = KitStats()
    /** "player uuid.kit id" -> that player's changes. Only changed kits are in here. */
    private val stats = ConcurrentHashMap<String, KitStats>()
    /** The kit each player was last given, so a win can be judged after the kit is taken off again. */
    private val wearing = ConcurrentHashMap<UUID, String>()
    private val told = ConcurrentHashMap<UUID, Long>()

    private val file: File get() = File(plugin.dataFolder, "kit-stats.properties")

    fun setup() {
        // The Back button lives in the player's own inventory while the menu is open; never leave it there.
        gg.flyte.twilight.event.event<org.bukkit.event.inventory.InventoryCloseEvent> { (player as? Player)?.let(::takeBack) }
        gg.flyte.twilight.event.event<org.bukkit.event.player.PlayerJoinEvent> { takeBack(player) }
        gg.flyte.twilight.event.event<org.bukkit.event.player.PlayerQuitEvent> { takeBack(player) }
        if (!file.exists()) return
        val props = Properties()
        file.inputStream().use { props.load(it) }
        props.stringPropertyNames().forEach { key ->
            val decoded = KitStats.decode(props.getProperty(key))
            if (!decoded.isEmpty) stats[key] = decoded
        }
    }

    private fun save() {
        val props = Properties()
        stats.forEach { (key, value) -> props.setProperty(key, value.encode()) }
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, "SSM AI Edition - changed kit stats per player and kit") }
    }

    // ------------------------------------------------------------------ what a kit is

    fun base(kit: KitDef): KitBase {
        val regen = kit.passives.firstOrNull { it.id == "regeneration" }?.let { entry ->
            entry.overrides?.metadata?.get("healAmount")?.content?.toDoubleOrNull()
                ?: data.getPassive("regeneration")?.metadata?.get("healAmount")?.content?.toDoubleOrNull() ?: 0.25
        }
        return KitBase(regen, kit.armor, kit.meleeDamage,
            kit.abilities.mapNotNull { entry -> data.getAbility(entry.id)?.let { entry.id to it.cooldown } }.toMap())
    }

    private fun key(id: UUID, kitId: String) = "$id.$kitId"
    fun statsOf(id: UUID, kit: KitDef): KitStats = (stats[key(id, kit.id)] ?: NONE).trimmed(base(kit))
    fun verdict(id: UUID, kit: KitDef): KitVerdict = statsOf(id, kit).verdict(base(kit))
    fun changed(id: UUID, kit: KitDef): Boolean = !statsOf(id, kit).isEmpty

    fun reset(id: UUID, kit: KitDef) {
        if (stats.remove(key(id, kit.id)) != null) save()
        plugin.server.getPlayer(id)?.let(::refreshPreview)
    }

    // ------------------------------------------------------------------ in a match

    private fun live(player: Player, kitId: String?): KitStats? {
        if (kitId == null || stats.isEmpty() || BotManager.isBot(player)) return null
        val kit = data.getKit(kitId) ?: return null
        return stats[key(player.uniqueId, kit.id)]?.trimmed(base(kit))
    }

    /** Health per regeneration tick, when this player changed it. */
    fun regen(player: Player, kitId: String?): Double? = live(player, kitId)?.regen
    /** Added to whatever armor the kit has right now, so forms and smashes still work on top. */
    fun armorBonus(player: Player, kit: KitDef): Double = live(player, kit.id)?.armor?.let { it - kit.armor } ?: 0.0
    fun damageBonus(player: Player, kit: KitDef): Double = live(player, kit.id)?.damage?.let { it - kit.meleeDamage } ?: 0.0
    /** Seconds, when this player changed that ability's cooldown. */
    fun cooldown(player: Player, kitId: String?, abilityId: String): Double? = live(player, kitId)?.cooldowns?.get(abilityId)

    /** Called when [player] is given a kit. */
    fun wear(player: Player, kitId: String) {
        if (BotManager.isBot(player)) return
        wearing[player.uniqueId] = kitId
        val kit = data.getKit(kitId) ?: return
        val now = System.currentTimeMillis()
        if (verdict(player.uniqueId, kit) == KitVerdict.BUFFED && minigames.isPlayerInMinigame(player) &&
            now - (told[player.uniqueId] ?: 0L) > 60_000) {
            told[player.uniqueId] = now
            player.sendMessage(plain("Your kit is Buffed: wins and kills with it do not count for the leaderboard.", NamedTextColor.GOLD))
        }
    }

    /** False for a player whose current kit is Buffed. Bots have no changed kits, so they always count. */
    fun countsForLeaderboard(id: UUID): Boolean {
        val kit = wearing[id]?.let(data::getKit) ?: return true
        return verdict(id, kit) != KitVerdict.BUFFED
    }

    /**
     * Shows on the ability tool that the kit is not the normal one: wood for Buffed, gold for
     * Nerfed, and the word at the end of the tooltip. Only the look changes; damage and cooldowns
     * come from the kit, never from the tool.
     */
    fun dress(player: Player, kitId: String, abilityId: String, item: ItemStack): ItemStack {
        if (BotManager.isBot(player)) return item
        val kit = data.getKit(kitId) ?: return item
        // Each tool answers for its own cooldown; the first one also for the stats of the whole kit.
        val verdict = statsOf(player.uniqueId, kit).toolVerdict(base(kit), abilityId, kit.abilities.firstOrNull()?.id == abilityId)
        if (verdict == KitVerdict.NORMAL) return item
        val buffed = verdict == KitVerdict.BUFFED
        val tag = plain("[${verdict.label}]", if (buffed) NamedTextColor.RED else NamedTextColor.GOLD)
        val tool = TOOL.matchEntire(item.type.name)?.let { Material.matchMaterial((if (buffed) "WOODEN_" else "GOLDEN_") + it.groupValues[1]) }
        return (if (tool != null) item.withType(tool) else item.clone()).apply {
            editMeta { meta ->
                // The name is what shows above the hotbar when the tool is selected.
                meta.customName()?.let { name -> meta.customName(name.append(Component.text(" ")).append(tag)) }
                meta.lore((meta.lore() ?: emptyList()) + tag)
            }
        }
    }
    private val TOOL = Regex("(?:WOODEN|STONE|COPPER|IRON|GOLDEN|DIAMOND|NETHERITE)_(SWORD|AXE|SHOVEL|PICKAXE|HOE)")

    private val ARMOR_KEY = org.bukkit.NamespacedKey("ssm", "kit_stats_armor")

    /**
     * Makes the armor bar above the hotbar show a changed armor value (one icon is one armor).
     * The bar is drawn from what the player wears, so the difference is added on top; a null
     * [kitId] takes it off again.
     */
    fun showArmor(player: Player, kitId: String?) {
        val attribute = player.getAttribute(org.bukkit.attribute.Attribute.ARMOR) ?: return
        attribute.modifiers.filter { it.key == ARMOR_KEY }.forEach(attribute::removeModifier)
        val kit = kitId?.let(data::getKit) ?: return
        val bonus = armorBonus(player, kit)
        if (bonus != 0.0) attribute.addTransientModifier(
            org.bukkit.attribute.AttributeModifier(ARMOR_KEY, bonus * 2, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER))
    }

    /** The tools a player carries around the lobby show the same wood or gold as in a match. */
    private fun refreshPreview(player: Player) {
        if (minigames.isPlayerInMinigame(player) || KitService.isRandomSelected(player)) return
        if (player.inventory.contents.none { KitPresentation.isPreview(it) }) return
        KitPresentation.equipPreview(player, KitService.currentSelectedKitForPlayer(player))
    }

    // ------------------------------------------------------------------ menu

    private fun plain(text: String, color: NamedTextColor) = Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun item(material: Material, name: String, color: NamedTextColor, lore: List<String> = emptyList()) =
        ItemStack.of(material).apply { editMeta { it.displayName(plain(name, color)); it.lore(lore.map { line -> plain(line, NamedTextColor.GRAY) }) } }

    private fun number(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString() else ((value * 100).roundToLong() / 100.0).toString()

    /** Top right of the player's own inventory: the menu itself has no free slot. */
    private const val BACK_SLOT = 17
    private val BACK_KEY = org.bukkit.NamespacedKey("ssm", "kit_stats_back")
    private fun isBack(item: ItemStack?) = item?.itemMeta?.persistentDataContainer?.has(BACK_KEY) == true
    private fun takeBack(player: Player) {
        if (isBack(player.inventory.getItem(BACK_SLOT))) player.inventory.setItem(BACK_SLOT, null)
    }
    private fun giveBack(player: Player) {
        val there = player.inventory.getItem(BACK_SLOT)
        if (there != null && !there.type.isAir && !isBack(there)) return
        player.inventory.setItem(BACK_SLOT, MenuIcons.navigation(false).apply { editMeta {
            it.displayName(plain("Back", NamedTextColor.RED))
            it.lore(listOf(plain("Return to Game Settings", NamedTextColor.GRAY)))
            it.persistentDataContainer.set(BACK_KEY, org.bukkit.persistence.PersistentDataType.BYTE, 1)
        } })
    }

    private const val REGEN_STEP = 0.1
    private const val COOLDOWN_STEP = 2.0

    fun openMenu(player: Player) {
        if (KitService.isRandomSelected(player)) {
            player.sendMessage(plain("Random Kit is selected. Choose a kit first, then change its stats.", NamedTextColor.YELLOW))
            return
        }
        val kit = KitService.currentSelectedKitForPlayer(player)
        val base = base(kit)
        val mine = statsOf(player.uniqueId, kit)
        val verdict = mine.verdict(base)

        fun apply(changed: KitStats) {
            val trimmed = changed.trimmed(base)
            if (trimmed.isEmpty) stats.remove(key(player.uniqueId, kit.id)) else stats[key(player.uniqueId, kit.id)] = trimmed
            save()
            refreshPreview(player)
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f)
            openMenu(player)
        }

        val title = lang.t("kits.${kit.id}.name").append(Component.text(" Stats" + if (verdict == KitVerdict.NORMAL) "" else " [${verdict.label}]"))
        val gui = brawlGui(title, 54, selectionEffects = true) {
            onClick {
                isCancelled = true
                if (clickedInventory == player.inventory && isBack(currentItem)) {
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1f)
                    GameSetupService.openMenu(player)
                }
            }
            for (slot in 0 until 54) set(slot, item(Material.GRAY_STAINED_GLASS_PANE, "", NamedTextColor.GRAY))

            /**
             * A stat where more is better. The icon is the first square, so the lowest it goes is
             * one [step]. Lime is a full square, yellow half of one, red none.
             */
            fun stat(row: Int, icon: ItemStack, label: String, unit: String, normal: Double, current: Double, step: Double,
                     change: (Double) -> KitStats) {
                val units = StatBar.units(current, step)
                fun text(value: Double) = "${number(value)}$unit"
                fun note(value: Double) = when { value > normal + 1e-6 -> "Better than normal: Buffed"; value < normal - 1e-6 -> "Worse than normal"; else -> "Normal" }
                set(row * 9, icon.apply { editMeta {
                    it.displayName(plain("$label: ${text(current)}", NamedTextColor.AQUA))
                    it.lore(listOf("Normal: ${text(normal)}", "Each square is ${text(step)}, this one included", "Click for the lowest: ${text(step)}")
                        .map { line -> plain(line, NamedTextColor.GRAY) })
                } }) { apply(change(step)) }
                for (column in 1..8) {
                    val square = column + 1
                    val fill = StatBar.fill(units, square)
                    val target = StatBar.click(units, square) * step
                    val pane = when (fill) { StatBar.FULL -> Material.LIME_STAINED_GLASS_PANE; StatBar.HALF -> Material.YELLOW_STAINED_GLASS_PANE; else -> Material.RED_STAINED_GLASS_PANE }
                    val color = when (fill) { StatBar.FULL -> NamedTextColor.GREEN; StatBar.HALF -> NamedTextColor.YELLOW; else -> NamedTextColor.RED }
                    set(row * 9 + column, item(pane, "Set $label to ${text(target)}", color, listOf("Now: ${text(current)}", "Normal: ${text(normal)}", note(target)))) {
                        apply(change(target))
                    }
                }
            }

            /** A cooldown, where less is better: red squares from the left at 2 seconds each. The icon is not a square. */
            fun cooldown(row: Int, abilityId: String, icon: Material, normal: Double, current: Double) {
                val units = StatBar.units(current, COOLDOWN_STEP)
                fun text(value: Double) = if (value <= 0.0) "no cooldown" else "${number(value)}s"
                fun change(seconds: Double) = mine.copy(cooldowns = mine.cooldowns + (abilityId to seconds))
                fun note(value: Double) = when { value < normal - 1e-6 -> "Shorter than normal: Buffed"; value > normal + 1e-6 -> "Longer than normal"; else -> "Normal" }
                set(row * 9, ItemStack.of(icon).apply { editMeta {
                    it.displayName(lang.t("abilities.$abilityId.name").color(lang.kitColor(kit.id)).decoration(TextDecoration.ITALIC, false)
                        .append(plain(": ${text(current)}", NamedTextColor.AQUA)))
                    it.lore(listOf("Normal: ${text(normal)}", "Each red square is 2 seconds", "Click for no cooldown").map { line -> plain(line, NamedTextColor.GRAY) })
                    it.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES)
                } }) { apply(change(0.0)) }
                for (square in 1..8) {
                    val fill = StatBar.fill(units, square)
                    val target = StatBar.click(units, square) * COOLDOWN_STEP
                    val block = when (fill) { StatBar.FULL -> Material.RED_STAINED_GLASS; StatBar.HALF -> Material.YELLOW_STAINED_GLASS; else -> Material.LIME_STAINED_GLASS }
                    val color = when (fill) { StatBar.FULL -> NamedTextColor.RED; StatBar.HALF -> NamedTextColor.YELLOW; else -> NamedTextColor.GREEN }
                    set(row * 9 + square, item(block, "Set cooldown to ${text(target)}", color, listOf("Now: ${text(current)}", "Normal: ${text(normal)}", note(target)))) {
                        apply(change(target))
                    }
                }
            }

            base.regen?.let { normal ->
                stat(0, MenuIcons.kit(kit.id, kit.displayItem ?: Material.BOOK), "Health Regen", " / sec", normal, mine.regen ?: normal, REGEN_STEP) { mine.copy(regen = it) }
            }
            stat(1, ItemStack.of(Material.IRON_CHESTPLATE).apply { editMeta { it.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES) } },
                "Armor", "", base.armor, mine.armor ?: base.armor, 1.0) { mine.copy(armor = it) }
            stat(2, ItemStack.of(Material.DIAMOND_SWORD).apply { editMeta { it.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES) } },
                "Attack Damage", "", base.damage, mine.damage ?: base.damage, 1.0) { mine.copy(damage = it) }
            kit.abilities.take(3).forEachIndexed { index, entry ->
                val ability = data.getAbility(entry.id) ?: return@forEachIndexed
                cooldown(3 + index, entry.id, Material.matchMaterial(ability.hotbarItem) ?: Material.IRON_AXE,
                    ability.cooldown, mine.cooldowns[entry.id] ?: ability.cooldown)
            }
        }
        player.openInventory(gui)
        // After opening: closing the previous menu just took the button away.
        giveBack(player)
    }
}

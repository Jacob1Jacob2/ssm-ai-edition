package dev.betrix.superSmashMobsBrawl.commands

import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.services.DataService
import dev.rollczi.litecommands.annotations.argument.Arg
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.description.Description
import dev.rollczi.litecommands.annotations.execute.Execute
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** /bots list | spawn <n> | clear | game <minigame> <n> [difficulty]  (ops/console only) */
@Command(name = "bots")
class BotsCommand : KoinComponent {
    private val dataService: DataService by inject()

    private fun allowed(sender: CommandSender): Boolean {
        if (sender is Player && !sender.isOp) {
            sender.sendMessage("Only operators can manage bots.")
            return false
        }
        return true
    }

    @Execute(name = "list")
    @Description("List online AI bots")
    fun list(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        val bots = BotManager.onlineBots()
        sender.sendMessage("${bots.size} bots online:")
        bots.forEach { p ->
            val profile = BotManager.profileOf(p)
            sender.sendMessage(
                " - ${p.name} [${profile?.difficulty?.name?.lowercase()}] " +
                    "${p.world.name} ${"%.1f %.1f %.1f".format(p.location.x, p.location.y, p.location.z)} " +
                    "hp=${"%.1f".format(p.health)} mode=${p.gameMode}"
            )
        }
    }

    @Execute(name = "spawn")
    @Description("Spawn some AI bots into the hub")
    fun spawn(@Context sender: CommandSender, @Arg count: Int) {
        if (!allowed(sender)) return
        val spawned = BotManager.spawnRandom(count.coerceIn(1, 20))
        sender.sendMessage("Spawned ${spawned.size} bots: ${spawned.joinToString { it.name }}")
    }

    @Execute(name = "debug")
    @Description("Toggle logging of bot movement decisions")
    fun debug(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        BotManager.debug = !BotManager.debug
        sender.sendMessage("Bot debug logging ${if (BotManager.debug) "ON" else "OFF"}.")
    }

    @Execute(name = "testhit")
    @Description("Debug: make one bot do a vanilla (client-style) attack on another")
    fun testHit(@Context sender: CommandSender, @Arg attacker: String, @Arg victim: String) {
        if (!allowed(sender)) return
        val auto = BotManager.onlineBots().filter { it.gameMode == org.bukkit.GameMode.SURVIVAL && it.world.name.startsWith("ssmbworld_") }
        val a = if (attacker == "auto") auto.getOrNull(0) else org.bukkit.Bukkit.getPlayerExact(attacker)
        val v = if (victim == "auto") auto.getOrNull(1) else org.bukkit.Bukkit.getPlayerExact(victim)
        if (a == null || v == null) {
            sender.sendMessage("Both players must be online.")
            return
        }
        val before = v.health
        (a as org.bukkit.craftbukkit.entity.CraftPlayer).handle.attack((v as org.bukkit.craftbukkit.entity.CraftPlayer).handle)
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            org.bukkit.Bukkit.getPluginManager().getPlugin("super-smash-mobs-brawl")!!,
            Runnable {
                sender.sendMessage(
                    "TESTHIT ${a.name} -> ${v.name}: health ${"%.1f".format(before)} -> ${"%.1f".format(v.health)}, " +
                        "velocity ${"%.2f".format(v.velocity.length())}"
                )
            },
            2L,
        )
    }

    @Execute(name = "clear")
    @Description("Remove all AI bots")
    fun clear(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        val n = BotManager.onlineBots().size
        BotManager.despawnAll()
        sender.sendMessage("Removed $n bots.")
    }

    @Execute(name = "game")
    @Description("Start a bots-only game")
    fun game(@Context sender: CommandSender, @Arg minigame: String, @Arg count: Int) =
        startGame(sender, minigame, count, null)

    @Execute(name = "game")
    @Description("Start a bots-only game with one difficulty")
    fun gameWithDifficulty(
        @Context sender: CommandSender,
        @Arg minigame: String,
        @Arg count: Int,
        @Arg difficulty: String,
    ) = startGame(sender, minigame, count, if (difficulty == "any") null else BotDifficulty.parse(difficulty))

    @Execute(name = "game")
    @Description("Start a bots-only game on a specific map (difficulty 'any' for mixed)")
    fun gameOnMap(
        @Context sender: CommandSender,
        @Arg minigame: String,
        @Arg count: Int,
        @Arg difficulty: String,
        @Arg map: String,
    ) = startGame(sender, minigame, count, if (difficulty == "any") null else BotDifficulty.parse(difficulty), map)

    @Execute(name = "itemgame")
    @Description("Debug: a bots-only game with item drops every few seconds and movement logging on")
    fun itemGame(
        @Context sender: CommandSender,
        @Arg count: Int,
        @Arg difficulty: String,
        @Arg map: String,
        @Arg seconds: Int,
    ) {
        if (!allowed(sender)) return
        val def = dataService.getMinigame("super_smash_mobs") ?: return
        BotManager.debug = true
        val setup = dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup(
            difficulty = if (difficulty == "any") null else BotDifficulty.parse(difficulty),
            players = count, items = true, crystals = false, itemIntervalSeconds = seconds.coerceAtLeast(1))
        val bots = BotManager.startBotGame(def, count.coerceIn(2, 16), setup.difficulty, map, setup = setup)
        sender.sendMessage("Item game: ${bots.joinToString { it.name }}")
    }

    @Execute(name = "crystalgame")
    @Description("Debug: a bots-only game where a Smash Crystal of one rarity falls every few seconds (kit 'any' for mixed)")
    fun crystalGame(
        @Context sender: CommandSender,
        @Arg count: Int,
        @Arg difficulty: String,
        @Arg map: String,
        @Arg seconds: Int,
        @Arg kit: String,
        @Arg rarity: String,
    ) {
        if (!allowed(sender)) return
        val def = dataService.getMinigame("super_smash_mobs") ?: return
        val tier = dev.betrix.superSmashMobsBrawl.smash.SmashTier.entries.find { it.name.equals(rarity, true) }
            ?: return sender.sendMessage("Rarity is one of light, regular, super, ultra")
        BotManager.debug = true
        val setup = dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup(
            difficulty = if (difficulty == "any") null else BotDifficulty.parse(difficulty),
            players = count, items = false, crystals = true, crystalIntervalSeconds = seconds.coerceAtLeast(1),
            enabledCrystalTiers = setOf(tier))
        val size = count.coerceIn(2, 16)
        val bots = BotManager.startBotGame(def, size, setup.difficulty, map,
            kits = if (kit == "any") emptyList() else List(size) { kit }, setup = setup)
        sender.sendMessage("Crystal game: ${bots.joinToString { it.name }}")
    }

    @Execute(name = "chatgame")
    @Description("Debug: a bots-only game with a chat personality (toxic, salty, chill, silent, random); the chat is written to the log")
    fun chatGame(
        @Context sender: CommandSender,
        @Arg count: Int,
        @Arg difficulty: String,
        @Arg map: String,
        @Arg personality: String,
    ) {
        if (!allowed(sender)) return
        val def = dataService.getMinigame("super_smash_mobs") ?: return
        val mode = dev.betrix.superSmashMobsBrawl.bots.BotPersonality.entries.find { it.name.equals(personality, true) }
            ?: return sender.sendMessage("Personality is one of toxic, salty, chill, silent, random")
        BotManager.debug = true
        val setup = dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup(
            difficulty = if (difficulty == "any") null else BotDifficulty.parse(difficulty),
            players = count, items = false, crystals = false, botPersonality = mode)
        val bots = BotManager.startBotGame(def, count.coerceIn(2, 16), setup.difficulty, map, setup = setup)
        sender.sendMessage("Chat game (${mode.label}): ${bots.joinToString { it.name }}")
    }

    @Execute(name = "chasetest")
    @Description("Debug: measure whether bots can reach a still opponent all over a map")
    fun chaseTest(
        @Context sender: CommandSender,
        @Arg map: String,
        @Arg kit: String,
        @Arg difficulty: String,
        @Arg pairs: Int,
        @Arg samples: Int,
        @Arg seconds: Int,
        @Arg seed: Int,
    ) {
        if (!allowed(sender)) return
        sender.sendMessage(dev.betrix.superSmashMobsBrawl.bots.BotChaseTest.start(map, kit, BotDifficulty.parse(difficulty), pairs, samples, seconds, seed))
    }

    @Execute(name = "chasespot")
    @Description("Debug: one bot chases a still opponent at exact coordinates, with movement logging on")
    fun chaseSpot(
        @Context sender: CommandSender,
        @Arg map: String,
        @Arg kit: String,
        @Arg difficulty: String,
        @Arg x: Double,
        @Arg y: Double,
        @Arg z: Double,
        @Arg seconds: Int,
    ) {
        if (!allowed(sender)) return
        BotManager.debug = true
        sender.sendMessage(dev.betrix.superSmashMobsBrawl.bots.BotChaseTest.start(map, kit, BotDifficulty.parse(difficulty), 1, 1, seconds, 1, Triple(x, y, z)))
    }

    private fun startGame(
        sender: CommandSender,
        minigame: String,
        count: Int,
        difficulty: BotDifficulty?,
        map: String? = null,
    ) {
        if (!allowed(sender)) return
        val def = dataService.getMinigame(minigame)
        if (def == null) {
            sender.sendMessage("Unknown minigame '$minigame'. Options: ${dataService.getAllMinigames().joinToString { it.id }}")
            return
        }
        val bots = BotManager.startBotGame(def, count.coerceIn(1, 16), difficulty, map)
        sender.sendMessage("Queued ${bots.size} bots for ${def.id}: ${bots.joinToString { it.name }}")
    }
}

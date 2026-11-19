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
    private val plugin: dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl by inject()
    private val minigameService: dev.betrix.superSmashMobsBrawl.services.MinigameService by inject()

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

    @Execute(name = "perchtest")
    @Description("Debug: Sheep bots try the Wool Mine perch on any opponent well below them")
    fun perchTest(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        BotManager.forcePerch = !BotManager.forcePerch
        sender.sendMessage("Forced Wool Mine perch ${if (BotManager.forcePerch) "ON" else "OFF"}.")
    }

    @Execute(name = "cubtest")
    @Description("Debug: an enemy bot punches every summoned mob (cubs etc.) six times; logs its health")
    fun cubTest(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        val summons = dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons.owners.toMap()
        plugin.logger.info("[cub] START ${summons.size} summons")
        summons.forEach { (id, owner) ->
            val mob = org.bukkit.Bukkit.getEntity(id) as? org.bukkit.entity.LivingEntity ?: return@forEach
            val hitter = BotManager.onlineBots().firstOrNull {
                it != owner && it.world == mob.world && it.gameMode == org.bukkit.GameMode.SURVIVAL
            } ?: run { plugin.logger.info("[cub] no enemy bot for ${mob.type}"); return@forEach }
            var n = 0
            object : org.bukkit.scheduler.BukkitRunnable() {
                override fun run() {
                    if (!mob.isValid) { plugin.logger.info("[cub] ${mob.type} of ${owner.name} DEAD after $n hits"); cancel(); return }
                    if (n >= 6) { plugin.logger.info("[cub] ${mob.type} of ${owner.name} SURVIVED health ${mob.health}"); cancel(); return }
                    val before = mob.health
                    hitter.attack(mob); n++
                    plugin.logger.info("[cub] ${hitter.name} hit ${mob.type} of ${owner.name}: $before -> ${if (mob.isValid) mob.health.toString() else "removed"}")
                }
            }.runTaskTimer(plugin, 1L, 12L)
        }
    }

    @Execute(name = "platform")
    @Description("Debug: puts a 3x3 stone platform in the running bots match, so that x y z is somewhere to stand")
    fun platform(@Context sender: CommandSender, @Arg x: Double, @Arg y: Double, @Arg z: Double) {
        if (!allowed(sender)) return
        val world = BotManager.onlineBots().firstOrNull { it.world.key.value().startsWith("ssmbworld") }?.world
            ?: run { plugin.logger.info("[platform] no match world"); return }
        val bx = kotlin.math.floor(x).toInt(); val by = kotlin.math.floor(y).toInt() - 1; val bz = kotlin.math.floor(z).toInt()
        for (dx in -1..1) for (dz in -1..1) {
            world.getBlockAt(bx + dx, by, bz + dz).setType(org.bukkit.Material.STONE, false)
            for (up in 1..3) world.getBlockAt(bx + dx, by + up, bz + dz).setType(org.bukkit.Material.AIR, false)
        }
        dev.betrix.superSmashMobsBrawl.bots.ArenaNav.invalidateAll(world, bx, bz)
        plugin.logger.info("[platform] 3x3 stone at $bx,$by,$bz in ${world.key.value()}")
    }

    @Execute(name = "giveitem")
    @Description("Debug: hands an arena item to every bot in a running match that is not a passive stand-in")
    fun giveItem(@Context sender: CommandSender, @Arg item: String) {
        if (!allowed(sender)) return
        val kind = dev.betrix.superSmashMobsBrawl.items.ArenaItem.parse(item)
            ?: run { plugin.logger.info("[giveitem] no such item: $item"); return }
        val bots = BotManager.onlineBots().filter {
            it.world.key.value().startsWith("ssmbworld") && it.gameMode == org.bukkit.GameMode.SURVIVAL && BotManager.brainOf(it)?.passive != true
        }
        if (bots.isEmpty()) plugin.logger.info("[giveitem] no bots in a match")
        bots.forEach { plugin.logger.info("[giveitem] ${kind.label} -> ${it.name}: ${dev.betrix.superSmashMobsBrawl.items.ItemService.give(it, kind)}") }
    }

    @Execute(name = "dropitem")
    @Description("Debug: puts an arena item pickup at x y z in the running bots match")
    fun dropItem(@Context sender: CommandSender, @Arg item: String, @Arg x: Double, @Arg y: Double, @Arg z: Double) {
        if (!allowed(sender)) return
        val kind = dev.betrix.superSmashMobsBrawl.items.ArenaItem.parse(item)
            ?: run { plugin.logger.info("[dropitem] no such item: $item"); return }
        val bot = BotManager.onlineBots().firstOrNull { it.world.key.value().startsWith("ssmbworld") && it.gameMode == org.bukkit.GameMode.SURVIVAL }
            ?: run { plugin.logger.info("[dropitem] no match world"); return }
        val placed = dev.betrix.superSmashMobsBrawl.items.ItemService.spawnAt(bot, kind, org.bukkit.Location(bot.world, x, y, z))
        plugin.logger.info("[dropitem] ${kind.label} at $x $y $z: $placed")
    }

    @Execute(name = "snowcount")
    @Description("Debug: counts snow layers in the running bots match that are nowhere near a Snowman (left-over snow)")
    fun snowCount(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        val bots = BotManager.onlineBots().filter { it.world.key.value().startsWith("ssmbworld") }
        val world = bots.firstOrNull()?.world ?: run { plugin.logger.info("[snow] no match world"); return }
        val snowmen = bots.filter { it.world == world && dev.betrix.superSmashMobsBrawl.services.KitService.getKitForPlayer(it)?.id == "snowman" }
        val centre = bots.first { it.world == world }.location
        var total = 0; var stray = 0
        for (x in -70..70) for (z in -70..70) {
            if (!world.isChunkLoaded((centre.blockX + x) shr 4, (centre.blockZ + z) shr 4)) continue
            for (y in -40..40) {
                val block = world.getBlockAt(centre.blockX + x, centre.blockY + y, centre.blockZ + z)
                if (block.type != org.bukkit.Material.SNOW) continue
                total++
                if (snowmen.none { it.location.distanceSquared(block.location) < 15 * 15 }) stray++
            }
        }
        plugin.logger.info("[snow] total=$total stray=$stray snowmen=${snowmen.size}")
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

    @Execute(name = "borrowtest")
    @Description("Debug: in a running bots game, hands a bot every Mystery Ability in turn and clicks it once")
    fun borrowTest(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        val all = dev.betrix.superSmashMobsBrawl.abilities.MysteryAbility.everything()
        plugin.logger.info("[borrow] START ${all.size} abilities")
        var index = 0
        object : org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                if (index >= all.size) { plugin.logger.info("[borrow] DONE"); cancel(); return }
                val (sourceKit, id) = all[index++]
                val bot = BotManager.onlineBots().firstOrNull {
                    it.gameMode == org.bukkit.GameMode.SURVIVAL && it.world.name.startsWith("ssmbworld_") &&
                        dev.betrix.superSmashMobsBrawl.services.KitService.getKitForPlayer(it)?.let { kit -> id !in kit.abilityIds() } == true &&
                        (0..8).any { slot -> it.inventory.getItem(slot) == null }
                }
                if (bot == null) { plugin.logger.info("[borrow] SKIP $id: no bot free for it"); return }
                runCatching {
                    val kit = dev.betrix.superSmashMobsBrawl.services.KitService.getKitForPlayer(bot)!!
                    bot.inventory.heldItemSlot = (0..8).first { slot -> bot.inventory.getItem(slot) == null }
                    check(dev.betrix.superSmashMobsBrawl.abilities.MysteryAbility.grant(bot, sourceKit to id)) { "not granted" }
                    val ability = kit.getAbility(id)!!
                    val tool = bot.inventory.itemInMainHand
                    val left = dataService.getAbility(id)?.usage == dev.betrix.superSmashMobsBrawl.models.brawlData.AbilityUsage.LEFT_CLICK
                    org.bukkit.event.player.PlayerInteractEvent(bot,
                        if (left) org.bukkit.event.block.Action.LEFT_CLICK_AIR else org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
                        tool, null, org.bukkit.block.BlockFace.SELF, org.bukkit.inventory.EquipmentSlot.HAND).callEvent()
                    val lent = ability.borrowed!!
                    plugin.logger.info("[borrow] OK $id from $sourceKit on ${bot.name} (${kit.id}): ${tool.type} uses ${lent.uses}/${lent.total} exp=${"%.2f".format(bot.exp)}")
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, Runnable { kit.release(ability) }, 30L)
                }.onFailure { plugin.logger.warning("[borrow] FAIL $id from $sourceKit on ${bot.name}: $it") }
            }
        }.runTaskTimer(plugin, 20L, 40L)
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

    @Execute(name = "column")
    @Description("Debug: what the route planner sees in one column of the running match's arena, and the blocks themselves")
    fun column(@Context sender: CommandSender, @Arg x: Int, @Arg z: Int, @Arg fromY: Int, @Arg toY: Int) {
        if (!allowed(sender)) return
        val world = org.bukkit.Bukkit.getWorlds().lastOrNull { it.key.value().startsWith("ssmbworld") } ?: return sender.sendMessage("No arena is loaded.")
        val terrain = dev.betrix.superSmashMobsBrawl.bots.ArenaTerrain(world, world.minHeight, dev.betrix.superSmashMobsBrawl.bots.Mobility.JUMPER, loadChunks = true)
        plugin.logger.info("[column] $x,$z in ${world.name}: stand ${terrain.describe(x, z)}")
        for (y in maxOf(fromY, toY) downTo minOf(fromY, toY)) {
            val block = world.getBlockAt(x, y, z)
            plugin.logger.info("[column] y=$y ${block.blockData.asString} boxes=" + block.collisionShape.boundingBoxes.joinToString(";") {
                "%.2f-%.2f,%.2f-%.2f,%.2f-%.2f".format(it.minX, it.maxX, it.minY, it.maxY, it.minZ, it.maxZ) })
        }
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

    @Execute(name = "area")
    @Description("Debug: where one can stand in every column of a rectangle of the running match's arena (height^room above)")
    fun area(@Context sender: CommandSender, @Arg x1: Int, @Arg z1: Int, @Arg x2: Int, @Arg z2: Int) {
        if (!allowed(sender)) return
        val world = BotManager.onlineBots().firstOrNull { it.world.key.value().startsWith("ssmbworld") }?.world
            ?: run { plugin.logger.info("[area] no match world"); return }
        val terrain = dev.betrix.superSmashMobsBrawl.bots.ArenaTerrain(world, world.minHeight, dev.betrix.superSmashMobsBrawl.bots.Mobility.JUMPER, loadChunks = true)
        for (x in minOf(x1, x2)..maxOf(x1, x2)) for (z in minOf(z1, z2)..maxOf(z1, z2)) {
            val stand = terrain.describe(x, z)
            if (stand.isNotEmpty()) plugin.logger.info("[area] $x,$z: $stand")
        }
        plugin.logger.info("[area] done")
    }

    @Execute(name = "blocks")
    @Description("Debug: draws a box of the running match's arena layer by layer from the top (# solid, . open, ~ liquid), x across and z down")
    fun blocks(@Context sender: CommandSender, @Arg x1: Int, @Arg y1: Int, @Arg z1: Int, @Arg x2: Int, @Arg y2: Int, @Arg z2: Int) {
        if (!allowed(sender)) return
        val world = BotManager.onlineBots().firstOrNull { it.world.key.value().startsWith("ssmbworld") }?.world
            ?: run { plugin.logger.info("[blocks] no match world"); return }
        val xs = minOf(x1, x2)..maxOf(x1, x2); val zs = minOf(z1, z2)..maxOf(z1, z2)
        if (xs.count() * zs.count() * (kotlin.math.abs(y2 - y1) + 1) > 60000) { plugin.logger.info("[blocks] too big a box"); return }
        for (y in maxOf(y1, y2) downTo minOf(y1, y2)) {
            plugin.logger.info("[blocks] y=$y x=${xs.first}..${xs.last}")
            for (z in zs) plugin.logger.info("[blocks] ${"%4d".format(z)} " + xs.joinToString("") { x ->
                val b = world.getBlockAt(x, y, z)
                if (b.isLiquid) "~" else if (b.collisionShape.boundingBoxes.isEmpty()) "." else "#" })
        }
        plugin.logger.info("[blocks] done")
    }

    @Execute(name = "vitals")
    @Description("Debug: sets every match bot's health and food, then logs health, food and saturation once a second for the given time")
    fun vitals(@Context sender: CommandSender, @Arg health: Double, @Arg food: Int, @Arg seconds: Int) {
        if (!allowed(sender)) return
        fun bots() = BotManager.onlineBots().filter { it.world.key.value().startsWith("ssmbworld") && it.gameMode == org.bukkit.GameMode.SURVIVAL }
        bots().forEach { it.health = health.coerceIn(1.0, it.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0); it.foodLevel = food.coerceIn(0, 20) }
        var left = seconds.coerceIn(1, 300)
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, { task: org.bukkit.scheduler.BukkitTask ->
            plugin.logger.info("[vitals] " + bots().joinToString("  ") { "${it.name} hp=${"%.2f".format(it.health)} food=${it.foodLevel} sat=${"%.1f".format(it.saturation)}" })
            if (--left <= 0) task.cancel()
        }, 0L, 20L)
    }

    @Execute(name = "ambush")
    @Description("Debug: one bot goes for an opponent that holds a spot and fights back from it, for the whole time; counts how often it dies there")
    fun ambush(
        @Context sender: CommandSender,
        @Arg map: String,
        @Arg kit: String,
        @Arg difficulty: String,
        @Arg x: Double,
        @Arg y: Double,
        @Arg z: Double,
        @Arg seconds: Int,
        @Arg hiderKit: String,
    ) {
        if (!allowed(sender)) return
        BotManager.debug = true
        sender.sendMessage(dev.betrix.superSmashMobsBrawl.bots.BotChaseTest.start(map, kit, BotDifficulty.parse(difficulty), 1, 1, seconds, 1,
            Triple(x, y, z), hiderKit, ambush = true))
    }

    @Execute(name = "navgen")
    @Description("Debug: works out the long jumps of maps ahead of time and saves them: maps (all, all@small, aviation,ruins@big ...) and kinds of jump (all, or standard,bouncy,wolf,goat,spider)")
    fun navGen(@Context sender: CommandSender, @Arg maps: String, @Arg jumps: String) {
        if (!allowed(sender)) return
        val answer = dev.betrix.superSmashMobsBrawl.bots.BotNavGen.start(maps.split(',').filter { it.isNotBlank() }, jumps.split(',').filter { it.isNotBlank() })
        plugin.logger.info("[navgen] $answer")
        sender.sendMessage(answer)
    }

    @Execute(name = "edges")
    @Description("Debug: where the route planner says a kit can go from the block at x y z of the running match, long jumps apart")
    fun edges(@Context sender: CommandSender, @Arg x: Double, @Arg y: Double, @Arg z: Double, @Arg kit: String) {
        if (!allowed(sender)) return
        val bot = BotManager.onlineBots().firstOrNull { it.world.key.value().startsWith("ssmbworld") }
            ?: run { plugin.logger.info("[edges] no match world"); return }
        val data = minigameService.getMinigameForPlayer(bot)?.worldManager?.getWorld()?.data
            ?: run { plugin.logger.info("[edges] no match"); return }
        val voidY = data.voidLevel.toInt()
        val nav = dev.betrix.superSmashMobsBrawl.bots.ArenaNav.of(bot.world, voidY, dev.betrix.superSmashMobsBrawl.bots.Mobility.of(kit, true))
        val node = nav.nearest(org.bukkit.Location(bot.world, x, y, z), 5.0)
            ?: run { plugin.logger.info("[edges] nowhere to stand at $x $y $z"); return }
        val jumps = dev.betrix.superSmashMobsBrawl.bots.ArenaJumps.of(bot.world, voidY, data, dev.betrix.superSmashMobsBrawl.bots.Mobility.of(kit, true))
        fun show(edges: List<dev.betrix.superSmashMobsBrawl.bots.RouteEdge>) = edges.joinToString(" ") {
            "${it.node.x},${"%.2f".format(it.node.y)},${it.node.z}${if (it.climb) "c" else if (it.jump) "j" else ""}(${"%.1f".format(it.cost)})" }
        plugin.logger.info("[edges] from ${node.x},${"%.2f".format(node.y)},${node.z} as $kit: " + show(nav.base(node)))
        val long = jumps.of(node) ?: jumps.now(node)
        plugin.logger.info("[edges] long jumps (${jumps.size} blocks known, ${jumps.jumps} jumps, complete=${jumps.complete}, waiting=${jumps.waiting}, misses=${jumps.misses}, from disk=${jumps.loaded}): " +
            show(long.filter { it.cost >= 0 }))
        plugin.logger.info("[edges] jumps of the rules struck out: " + show(long.filter { it.cost < 0 }))
    }

    @Execute(name = "jumptest")
    @Description("Debug: flies the jump from the block at x1 y1 z1 to the block at x2 y2 z2 of the running match in the head with a kit's air jump, and says how each try ended")
    fun jumpTest(@Context sender: CommandSender, @Arg x1: Double, @Arg y1: Double, @Arg z1: Double, @Arg x2: Double, @Arg y2: Double, @Arg z2: Double, @Arg kit: String) {
        if (!allowed(sender)) return
        val bot = BotManager.onlineBots().firstOrNull { it.world.key.value().startsWith("ssmbworld") }
            ?: run { plugin.logger.info("[jumptest] no match world"); return }
        val data = minigameService.getMinigameForPlayer(bot)?.worldManager?.getWorld()?.data
            ?: run { plugin.logger.info("[jumptest] no match"); return }
        val mobility = dev.betrix.superSmashMobsBrawl.bots.Mobility.of(kit, true)
        val launch = mobility.jumps.launch
        val terrain = dev.betrix.superSmashMobsBrawl.bots.ArenaTerrain(bot.world, data.voidLevel.toInt(), mobility, loadChunks = true)
        val a = terrain.surface(kotlin.math.floor(x1).toInt(), kotlin.math.floor(z1).toInt(), y1, 1.25, 3.0)
        val b = terrain.surface(kotlin.math.floor(x2).toInt(), kotlin.math.floor(z2).toInt(), y2, 1.25, 3.0)
        if (a == null || b == null) { plugin.logger.info("[jumptest] nowhere to stand at ${if (a == null) "the first" else "the second"} spot"); return }
        val sim = dev.betrix.superSmashMobsBrawl.bots.JumpSim
        val space = dev.betrix.superSmashMobsBrawl.bots.JumpSim.Space { x, y, z, foot -> terrain.clear(x, y, z, foot) }
        // (Worked out before the trace is on: building the table of how far the jump goes flies hundreds of jumps.)
        val reach = sim.reachOf(launch).at(b.y - a.y)
        sim.trace = { plugin.logger.info("[jumptest] $it") }
        try {
            val ok = sim.promised(space, launch, a.x + .5, a.y, a.z + .5, b.x + .5, b.y, b.z + .5)
            val across = kotlin.math.hypot((b.x - a.x).toDouble(), (b.z - a.z).toDouble())
            plugin.logger.info("[jumptest] ${a.x},${"%.2f".format(a.y)},${a.z} -> ${b.x},${"%.2f".format(b.y)},${b.z} as $kit (${mobility.jumps.id}): ${"%.2f".format(across)} across, " +
                "${"%.2f".format(b.y - a.y)} up, open-air reach at that height $reach, promised=$ok")
            // A kit that can charge its jump (the goat): the same with each amount charged.
            for ((charge, charged) in mobility.jumps.charged) {
                plugin.logger.info("[jumptest] with ${(charge * 100).toInt()}% charged:")
                val works = sim.promised(space, charged, a.x + .5, a.y, a.z + .5, b.x + .5, b.y, b.z + .5)
                plugin.logger.info("[jumptest] with ${(charge * 100).toInt()}% charged: promised=$works")
            }
        } finally { sim.trace = null }
    }

    @Execute(name = "flights")
    @Description("Debug: with bot debug logging on, logs every tick of an air jump next to the flight picked for it in the head")
    fun flights(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        BotManager.debugFlights = !BotManager.debugFlights
        plugin.logger.info("[bots] logging air jumps tick by tick: ${if (BotManager.debugFlights) "on" else "off"}")
    }

    @Execute(name = "nolearn")
    @Description("Debug: toggles whether bots remember how they died (off = the way they played before they learned from it)")
    fun noLearn(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        BotManager.noLearning = !BotManager.noLearning
        plugin.logger.info("[bots] learning from deaths: ${if (BotManager.noLearning) "off" else "on"}")
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

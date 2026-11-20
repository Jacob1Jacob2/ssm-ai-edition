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

    @Execute(name = "borrowdrop")
    @Description("Debug: in a running bots game, hands a bot a Mystery Ability and has it press the drop key on the tool; the tool and its ability should both be gone")
    fun borrowDrop(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        val bot = BotManager.onlineBots().firstOrNull {
            it.gameMode == org.bukkit.GameMode.SURVIVAL && it.world.name.startsWith("ssmbworld_") && (0..8).any { slot -> it.inventory.getItem(slot) == null }
        } ?: run { plugin.logger.info("[borrow] DROP: no bot free for it"); return }
        val kit = dev.betrix.superSmashMobsBrawl.services.KitService.getKitForPlayer(bot) ?: return
        val (sourceKit, id) = dev.betrix.superSmashMobsBrawl.abilities.MysteryAbility.everything().first { it.second !in kit.abilityIds() }
        val slot = (0..8).first { bot.inventory.getItem(it) == null }
        bot.inventory.heldItemSlot = slot
        if (!dev.betrix.superSmashMobsBrawl.abilities.MysteryAbility.grant(bot, sourceKit to id)) { plugin.logger.info("[borrow] DROP: not granted"); return }
        val before = bot.inventory.getItem(slot)?.type
        val dropped = bot.dropItem(false)
        val items = bot.world.getEntitiesByClass(org.bukkit.entity.Item::class.java).count { dev.betrix.superSmashMobsBrawl.abilities.MysteryAbility.isBorrowed(it.itemStack) }
        plugin.logger.info("[borrow] DROP $id on ${bot.name} (${kit.id}): held $before, drop went through=$dropped, slot now ${bot.inventory.getItem(slot)?.type ?: "empty"}, " +
            "ability ${if (id in kit.abilityIds()) "STILL ON THE KIT" else "gone"}, lying in the world: $items")
    }

    @Execute(name = "droptest")
    @Description("Debug: in a running bots game, hands a bot every item that can be thrown away, one a tick, and has it press the drop key; logs the sound each one makes and whether it left the hand")
    fun dropTest(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        BotManager.debug = true
        val kinds = dev.betrix.superSmashMobsBrawl.items.ArenaItem.entries.filter { !it.tool && it != dev.betrix.superSmashMobsBrawl.items.ArenaItem.DIAMOND_HELMET }
        var index = 0
        object : org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                if (index >= kinds.size) { plugin.logger.info("[drop] DONE"); cancel(); return }
                val bot = BotManager.onlineBots().firstOrNull {
                    it.gameMode == org.bukkit.GameMode.SURVIVAL && it.world.name.startsWith("ssmbworld_") &&
                        (0..8).any { slot -> it.inventory.getItem(slot) == null }
                } ?: return
                val kind = kinds[index++]
                val slot = (0..8).first { bot.inventory.getItem(it) == null }
                bot.inventory.heldItemSlot = slot
                bot.inventory.setItem(slot, dev.betrix.superSmashMobsBrawl.items.ItemService.stack(kind))
                val dropped = bot.dropItem(false)
                plugin.logger.info("[drop] ${kind.id} (${kind.material.name.lowercase()}): expected ${kind.dropFeel.name.lowercase()}, drop went through=$dropped, " +
                    "slot now ${bot.inventory.getItem(slot)?.type ?: "empty"}")
                bot.inventory.setItem(slot, null)
            }
        }.runTaskTimer(plugin, 1L, 2L)
    }

    @Execute(name = "shotbowtest")
    @Description("Debug: in a running bots game, a bot looses the Shotbow level down its longest clear line after holding it for 2, 3, 6, 10, 14 and 20 ticks; logs how fast each volley left and how far out it came down")
    fun shotbowTest(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        BotManager.debug = true
        val bot = BotManager.onlineBots().firstOrNull {
            it.gameMode == org.bukkit.GameMode.SURVIVAL && it.world.name.startsWith("ssmbworld_") && it.isOnGround
        } ?: run { plugin.logger.info("[shotbow] no bot standing in a match"); return }
        val eye = bot.eyeLocation
        val yaw = (0 until 16).map { it * 22.5f }.maxByOrNull { turn ->
            val along = org.bukkit.Location(bot.world, 0.0, 0.0, 0.0, turn, 0f).direction
            bot.world.rayTraceBlocks(eye, along, 60.0, org.bukkit.FluidCollisionMode.NEVER, true)?.hitPosition?.distance(eye.toVector()) ?: 60.0
        }!!
        val handle = (bot as org.bukkit.craftbukkit.entity.CraftPlayer).handle
        handle.setYRot(yaw); handle.setYHeadRot(yaw); handle.setXRot(0f)
        for (ticks in listOf(2, 3, 6, 10, 14, 20)) {
            val loosed = dev.betrix.superSmashMobsBrawl.items.ItemService.debugShotbow(bot, ticks)
            plugin.logger.info("[shotbow] ${bot.name} held $ticks ticks: ${if (loosed) "loosed" else "nothing loosed"}, " +
                "worked out reach on flat ground %.1f blocks".format(dev.betrix.superSmashMobsBrawl.items.ShotbowRules.reach(dev.betrix.superSmashMobsBrawl.items.ShotbowRules.force(ticks))))
        }
    }

    @Execute(name = "itemmenus")
    @Description("Debug: opens the three item menus for a bot (the guide, Choose Items, the sandbox's Spawn Items), logs what every slot holds, and in Choose Items clicks a rarity block twice and an item twice")
    fun itemMenus(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        BotManager.debug = true
        val bot = BotManager.onlineBots().firstOrNull() ?: run { plugin.logger.info("[menu] no bot to open it for"); return }
        val plainText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        val setup = dev.betrix.superSmashMobsBrawl.services.GameSetupService.setupOf(bot)
        val before = setup.enabledItems
        fun top() = bot.openInventory.topInventory
        fun describe(slot: Int): String {
            val item = top().getItem(slot) ?: return "-"
            val name = item.itemMeta?.displayName()?.let(plainText::serialize) ?: ""
            val lore = item.itemMeta?.lore()?.joinToString(" / ") { plainText.serialize(it) } ?: ""
            return "${item.type.name.lowercase()} '$name' [$lore]"
        }
        fun click(slot: Int) = org.bukkit.Bukkit.getPluginManager().callEvent(org.bukkit.event.inventory.InventoryClickEvent(
            bot.openInventory, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, slot,
            org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL))
        runCatching {
            dev.betrix.superSmashMobsBrawl.items.ItemMenus.open(bot)
            plugin.logger.info("[menu] --- ${plainText.serialize(bot.openInventory.title())}")
            for (slot in 0 until top().size) plugin.logger.info("[menu] guide slot $slot: ${describe(slot)}")
            dev.betrix.superSmashMobsBrawl.items.ItemMenus.spawner(bot)
            plugin.logger.info("[menu] --- ${plainText.serialize(bot.openInventory.title())}: slot 7 ${describe(7)}; slot 52 ${describe(52)}; slot 53 ${describe(53)}")
            dev.betrix.superSmashMobsBrawl.items.ItemMenus.open(bot, true)
            plugin.logger.info("[menu] --- ${plainText.serialize(bot.openInventory.title())}: ${setup.enabledItems.size} enabled; slot 0 ${describe(0)}; slot 2 ${describe(2)}")
            click(0); plugin.logger.info("[menu] clicked the common block: ${setup.enabledItems.size} enabled, commons on: ${setup.enabledItems.count { it.rarity.ordinal == 0 }}; slot 0 ${describe(0)}")
            click(0); plugin.logger.info("[menu] clicked it again: ${setup.enabledItems.size} enabled, commons on: ${setup.enabledItems.count { it.rarity.ordinal == 0 }}")
            click(52); plugin.logger.info("[menu] clicked slot 52: ${setup.enabledItems.size} enabled; ${describe(52)}")
            click(52); plugin.logger.info("[menu] clicked it again: ${setup.enabledItems.size} enabled; ${describe(52)}")
            click(1); plugin.logger.info("[menu] clicked the glass: ${setup.enabledItems.size} enabled")
            click(53); plugin.logger.info("[menu] clicked the redstone block: now in '${plainText.serialize(bot.openInventory.title())}'")
        }.onFailure { plugin.logger.warning("[menu] FAILED: $it") }
        setup.enabledItems = before
        dev.betrix.superSmashMobsBrawl.services.GameSetupService.save()
        bot.closeInventory()
    }

    @Execute(name = "useitem")
    @Description("Debug: in a running bots game, puts an arena item in a bot's hand and has it use it there and then (bots decide for themselves with 'bots giveitem'); 'cage' cages the bot outright")
    fun useItem(@Context sender: CommandSender, @Arg item: String) {
        if (!allowed(sender)) return
        BotManager.debug = true
        val items = dev.betrix.superSmashMobsBrawl.items.ItemService
        val bot = BotManager.onlineBots().firstOrNull {
            it.gameMode == org.bukkit.GameMode.SURVIVAL && it.world.name.startsWith("ssmbworld_") && !it.isInsideVehicle
        } ?: run { plugin.logger.info("[useitem] no bot in a match"); return }
        if (item == "cage") {
            items.arena(bot)?.effects?.cage(bot)
            plugin.logger.info("[useitem] caged ${bot.name}")
            return
        }
        if (item == "unseen") {
            // What Shadow Walk does to the gear of a player with the pack (a bot has none, so it is done by hand here).
            val gear = dev.betrix.superSmashMobsBrawl.items.UnseenGear
            plugin.logger.info("[unseen] ${bot.name} as it is: ${gear.describe(bot)}")
            gear.wear(bot)
            plugin.logger.info("[unseen] ${bot.name} unseen:   ${gear.describe(bot)}")
            gear.wear(bot)
            gear.shed(bot)
            plugin.logger.info("[unseen] ${bot.name} seen again: ${gear.describe(bot)}")
            return
        }
        if (item == "drain") {
            // A Flying Car with a quarter of a tank, left standing: it should begin to fill after ten seconds.
            for (slot in 0..35) if (items.kind(bot.inventory.getItem(slot)) != null) bot.inventory.setItem(slot, null)
            val car = dev.betrix.superSmashMobsBrawl.items.ArenaItem.FLYING_CAR
            val given = items.give(bot, car)
            val slot = (0..8).firstOrNull { items.kind(bot.inventory.getItem(it)) == car } ?: return
            val left = items.charge(bot, slot, -(car.uses - 10))
            plugin.logger.info("[useitem] a Flying Car -> ${bot.name} (given=$given), drained to $left seconds of fuel")
            return
        }
        if (item == "parked") {
            // A Flying Car hanging still while its driver looks round, driven the way a player's is
            // (by the look the server holds for them, set here exactly as a look packet from a
            // player in a vehicle sets it). What is counted is what the server then tells the
            // driver's game about the way the minecart faces: first left to itself, then with the
            // plugin telling it every tick.
            for (slot in 0..35) if (items.kind(bot.inventory.getItem(slot)) != null) bot.inventory.setItem(slot, null)
            val car = dev.betrix.superSmashMobsBrawl.items.ArenaItem.FLYING_CAR
            items.give(bot, car)
            val slot = (0..8).firstOrNull { items.kind(bot.inventory.getItem(it)) == car } ?: return
            bot.inventory.heldItemSlot = slot
            val effects = dev.betrix.superSmashMobsBrawl.items.ItemEffects
            effects.handDriven += bot.uniqueId
            val driver = (bot as org.bukkit.craftbukkit.entity.CraftPlayer).handle
            var driven: dev.betrix.superSmashMobsBrawl.items.ItemEffects.Car? = null
            var cart: net.minecraft.world.entity.Entity? = null
            var stood = org.bukkit.util.Vector()
            var told = 0
            var waited = 0
            var tick = 0
            var look = bot.location.yaw
            var meddled = 0
            var wrong = 0
            val phase = 60
            val pending = java.util.concurrent.atomic.DoubleAdder()
            val keySteps = java.util.concurrent.atomic.AtomicInteger()
            var keyed = 0.0
            var lookAtKeys = 0f
            org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, java.util.function.Consumer { task ->
                val car = driven ?: run {
                    // Not in yet: an item just used leaves the hand busy for a moment, so it is tried until it takes.
                    val got = effects.cars[bot.uniqueId] ?: run { bot.inventory.heldItemSlot = slot; items.use(bot); effects.cars[bot.uniqueId] }
                    if (got == null) {
                        if (++waited > 80 || !bot.isOnline) {
                            effects.handDriven -= bot.uniqueId
                            plugin.logger.info("[cartest] ${bot.name} could not get into a Flying Car here")
                            task.cancel()
                        }
                        return@Consumer
                    }
                    driven = got
                    cart = (got.cart as org.bukkit.craftbukkit.entity.CraftEntity).handle
                    stood = got.at.clone()
                    look = bot.location.yaw
                    effects.explicitTurns = false
                    dev.betrix.superSmashMobsBrawl.bots.nms.FakeConnection.sniff = { connection, packet ->
                        if (connection === driver.connection.connection) {
                            if (packet is net.minecraft.network.protocol.game.ClientboundMoveEntityPacket &&
                                packet.hasRotation() && packet.getEntity(driver.level()) === cart) told++
                            // What A and D send: "look this much further round". (From the steering timer, not the game's thread.)
                            if (packet is net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket && packet.relativeY()) {
                                pending.add(packet.yRot().toDouble()); keySteps.incrementAndGet()
                            }
                        }
                    }
                    plugin.logger.info("[cartest] ${bot.name} in a Flying Car at %.1f %.1f %.1f after $waited ticks, standing, looking round at 6 degrees a tick".format(stood.x, stood.y, stood.z))
                    got
                }
                val driven = car
                val gone = effects.cars[bot.uniqueId] !== driven
                if (tick > 0 && !gone) {
                    // Is the look still what it was set to a tick ago, and did the car turn to it in the meantime?
                    if (kotlin.math.abs(net.minecraft.util.Mth.wrapDegrees(bot.location.yaw - look)) > .01f) meddled++
                    if (kotlin.math.abs(net.minecraft.util.Mth.wrapDegrees(driven.cart.location.yaw - (look - 90f))) > .01f) wrong++
                }
                if (tick == phase || tick == phase * 2 || gone) {
                    plugin.logger.info("[cartest] ${if (tick <= phase) "left to the server" else "told every tick"}: the driver's game was told the minecart had turned $told times in $phase ticks; " +
                        "cart facing %.0f for a look of %.0f (wants %.0f); wrong on $wrong ticks, look changed by something else on $meddled; the car moved %.2f blocks".format(
                            driven.cart.location.yaw, look, net.minecraft.util.Mth.wrapDegrees(look - 90f), driven.at.distance(stood)))
                    told = 0; wrong = 0; meddled = 0
                    effects.explicitTurns = true
                }
                // Then the D key, held for two seconds and let go: what the driver's game is told to
                // turn by is what a game does with it (looks that much further round, and says so).
                val hold = 40
                val settle = 10
                if (tick == phase * 2 && !gone) {
                    effects.heldTurn[bot.uniqueId] = dev.betrix.superSmashMobsBrawl.items.CarRules.turnWanted(left = false, right = true)
                    pending.reset(); keySteps.set(0); keyed = 0.0; lookAtKeys = look
                }
                if (tick == phase * 2 + hold) effects.heldTurn[bot.uniqueId] = 0.0
                if (tick >= phase * 2 + hold + settle || gone) {
                    if (!gone) plugin.logger.info(("[cartest] D held for $hold ticks: the driver's game was told to look further round ${keySteps.get()} times, %.0f degrees in all " +
                        "(wants about %.0f); the look went from %.0f to %.0f, cart facing %.0f (wants %.0f); wrong on $wrong ticks; the car moved %.2f blocks").format(
                        keyed, dev.betrix.superSmashMobsBrawl.items.CarRules.TURN * hold / 20, lookAtKeys, look, driven.cart.location.yaw,
                        net.minecraft.util.Mth.wrapDegrees(look - 90f), driven.at.distance(stood)))
                    dev.betrix.superSmashMobsBrawl.bots.nms.FakeConnection.sniff = null
                    effects.explicitTurns = true
                    effects.handDriven -= bot.uniqueId
                    effects.heldTurn -= bot.uniqueId
                    if (!gone) items.arena(bot)?.effects?.leaveCar(bot)
                    task.cancel()
                    return@Consumer
                }
                if (tick < phase * 2) look = net.minecraft.util.Mth.wrapDegrees(look + 6f)
                else pending.sumThenReset().let { by -> keyed += by; look = net.minecraft.util.Mth.wrapDegrees(look + by.toFloat()) }
                driver.absSnapTo(driver.x, driver.y, driver.z, look, 0f)
                tick++
            }, 1L, 1L)
            return
        }
        if (item == "trap") {
            // A trap set where the bot stands: it is on it, so it is who gets caged.
            items.arena(bot)?.effects?.cageTrap(bot.location)
            plugin.logger.info("[useitem] a cage trap set under ${bot.name}")
            return
        }
        val kind = dev.betrix.superSmashMobsBrawl.items.ArenaItem.parse(item) ?: run { plugin.logger.info("[useitem] no such item: $item"); return }
        for (slot in 0..35) if (items.kind(bot.inventory.getItem(slot)) != null) bot.inventory.setItem(slot, null)
        val given = items.give(bot, kind)
        val slot = (0..8).firstOrNull { items.kind(bot.inventory.getItem(it)) == kind }
        if (slot != null) bot.inventory.heldItemSlot = slot
        val health = bot.health
        val used = slot != null && items.use(bot, kind == dev.betrix.superSmashMobsBrawl.items.ArenaItem.SMASHER)
        plugin.logger.info("[useitem] ${kind.label} -> ${bot.name}: given=$given used=$used, health %.1f -> %.1f, hand now ${slot?.let { bot.inventory.getItem(it)?.type } ?: "empty"}, uses left ${slot?.let { items.uses(bot.inventory.getItem(it)) } ?: 0}"
            .format(health, bot.health))
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

    @Execute(name = "teamgame")
    @Description("Debug: a bots-only Team-Ups game with a chat personality (toxic, salty, chill, silent, random); the first bot stands in for a player, and what the lobby has against it, the team-ups and the chat are written to the log")
    fun teamGame(
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
        // (The map may be given in one of its layouts, as for a chase test: desert@rev, aviation@small.)
        val setup = dev.betrix.superSmashMobsBrawl.bots.BotNavGen.shapeOf(map).apply(dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup(
            difficulty = if (difficulty == "any") null else BotDifficulty.parse(difficulty),
            players = count, items = false, crystals = false, botPersonality = mode, teamUps = true))
        val bots = BotManager.startBotGame(def, count.coerceIn(3, 16), setup.difficulty, map.substringBefore('@'), setup = setup)
        BotManager.standIns.clear()
        bots.firstOrNull()?.let { BotManager.standIns += it.uniqueId }
        plugin.logger.info("[teamups] game (${mode.label}): ${bots.firstOrNull()?.name} stands in for the player against ${bots.drop(1).joinToString { it.name }}")
        sender.sendMessage("Team-Ups game (${mode.label}): ${bots.joinToString { it.name }}")
    }

    @Execute(name = "sidegame")
    @Description("Debug: a bots-only game of two teams (4 bots 2v2, 8 4v4, 16 8v8); the sides, every life lost and the side that wins are written to the log as [sides]")
    fun sideGame(@Context sender: CommandSender, @Arg count: Int, @Arg difficulty: String, @Arg map: String, @Arg personality: String, @Arg lives: Int) {
        if (!allowed(sender)) return
        val def = dataService.getMinigame("super_smash_mobs") ?: return
        val mode = dev.betrix.superSmashMobsBrawl.bots.BotPersonality.entries.find { it.name.equals(personality, true) }
            ?: return sender.sendMessage("Personality is one of toxic, salty, chill, silent, random")
        BotManager.debug = true
        val size = count.coerceIn(4, 16)
        val plain = dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup(
            difficulty = if (difficulty == "any") null else BotDifficulty.parse(difficulty),
            players = size, items = false, crystals = false, teams = true, lives = lives.coerceIn(1, 8), botPersonality = mode)
        // (The map may be "@random", for a match too big for the map one would have named.)
        val setup = if (map.startsWith("@")) plain else dev.betrix.superSmashMobsBrawl.bots.BotNavGen.shapeOf(map).apply(plain)
        val bots = BotManager.startBotGame(def, size, setup.difficulty, if (map.startsWith("@")) map else map.substringBefore('@'), setup = setup)
        sender.sendMessage("Two teams game: ${bots.joinToString { it.name }}")
    }

    @Execute(name = "sidebar")
    @Description("Debug: writes the sidebar of every match that is on to the log as [sidebar], line by line (a gap between two columns shows as the pixel the second one starts at)")
    fun sidebar(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        for (game in minigameService.activeMinigames().filter { it.getState() == dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS }) {
            val lines = dev.betrix.superSmashMobsBrawl.services.ScoreboardService.describe(game)
            plugin.logger.info("[sidebar] ${game.allPlayers().size} fighters, ${lines.size} lines")
            lines.forEachIndexed { i, line -> plugin.logger.info("[sidebar] ${"%2d".format(i + 1)} | $line") }
        }
    }

    @Execute(name = "sidehost")
    @Description("Debug: in every bots-only game of two teams, makes the first Blue bot stand in for a player (it is talked to as one and the Blue bots take what it says as coming from their teammate)")
    fun sideHost(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        BotManager.standIns.clear()
        for (game in minigameService.activeMinigames().filter { it.sides.isNotEmpty() }) {
            val host = game.allPlayers().mapNotNull { it.player }.firstOrNull { game.sideOf(it) == dev.betrix.superSmashMobsBrawl.minigames.TeamSide.BLUE } ?: continue
            BotManager.standIns += host.uniqueId
            plugin.logger.info("[team] ${BotManager.displayNameOf(host)} stands in for the player on the Blue Team")
        }
    }

    @Execute(name = "sidesay")
    @Description("Debug: the stand-in player of a two teams game says this in chat (bots sidehost first); what its teammates make of it is logged as [team], with how far each is from it every two seconds")
    fun sideSay(@Context sender: CommandSender, @dev.rollczi.litecommands.annotations.join.Join("text") text: String) {
        if (!allowed(sender)) return
        for (id in BotManager.standIns) {
            val host = org.bukkit.Bukkit.getPlayer(id) ?: continue
            val game = minigameService.getMinigameForPlayer(host) ?: continue
            // ({foe} and {mate} stand for the name of an opponent and of a teammate: a test cannot know them beforehand.)
            fun someone(same: Boolean) = game.allPlayers().mapNotNull { it.player }
                .firstOrNull { it != host && (game.sideOf(it) == game.sideOf(host)) == same }?.let { BotManager.displayNameOf(it) } ?: "nobody"
            val said = text.replace("{foe}", someone(false)).replace("{mate}", someone(true))
            plugin.logger.info("[team] ${BotManager.displayNameOf(host)} types \"$said\"")
            dev.betrix.superSmashMobsBrawl.services.BotConversationService.say(host, said)
            // How far its teammates are from it for the next half minute: the proof that they came (or stayed away).
            var left = 15
            object : org.bukkit.scheduler.BukkitRunnable() {
                override fun run() {
                    if (left-- <= 0 || !host.isOnline || game.getState() != dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS) return cancel()
                    val mates = game.allPlayers().mapNotNull { it.player }.filter { it != host && game.sideOf(it) == game.sideOf(host) }
                    plugin.logger.info("[team] from ${BotManager.displayNameOf(host)}: " + mates.joinToString { mate ->
                        val order = dev.betrix.superSmashMobsBrawl.services.BotConversationService.orderFor(game, mate.uniqueId)
                        val target = BotManager.brainOf(mate)?.targetId?.let { id -> org.bukkit.Bukkit.getPlayer(id) }?.let { BotManager.displayNameOf(it) } ?: "nobody"
                        "${BotManager.displayNameOf(mate)} ${if (mate.gameMode != org.bukkit.GameMode.SURVIVAL) "dead" else "%.0f blocks".format(mate.location.distance(host.location))}" +
                            ", on $target" + (order?.let { ", asked to ${it.ask.name.lowercase()}" } ?: "")
                    })
                }
            }.runTaskTimer(plugin, 40L, 40L)
        }
    }

    @Execute(name = "menutest")
    @Description("Debug: opens Game Settings for a bot, clicks through the player counts, the modes, Your Team and Choose Opponents, and logs what the slots hold and what every click changed as [menutest]")
    fun menuTest(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        val profile = BotManager.rosterProfiles().firstOrNull() ?: return sender.sendMessage("The roster is empty")
        val bot = BotManager.spawn(profile) ?: return sender.sendMessage("Could not spawn ${profile.name}")
        dev.betrix.superSmashMobsBrawl.services.LobbyService.assign(bot, 1)
        dev.betrix.superSmashMobsBrawl.services.HubService.tryTeleportToDefaultHub(bot)
        val setups = dev.betrix.superSmashMobsBrawl.services.GameSetupService
        val s = setups.setupOf(bot)
        val plainText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        fun log(text: String) = plugin.logger.info("[menutest] $text")
        fun top() = bot.openInventory.topInventory
        fun title() = plainText.serialize(bot.openInventory.title())
        fun describe(slot: Int): String {
            val item = top().getItem(slot) ?: return "-"
            if (item.type.name.endsWith("_PANE")) return item.type.name.substringBefore("_STAINED").lowercase() + " pane"
            val model = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL)?.let { " <${it.asString()}>" } ?: ""
            val name = item.itemMeta?.displayName()?.let(plainText::serialize) ?: ""
            val lore = item.itemMeta?.lore()?.joinToString(" / ") { plainText.serialize(it) } ?: ""
            return "${item.type.name.lowercase()}${if (item.amount > 1) " x${item.amount}" else ""}$model '$name' [$lore]"
        }
        fun dump(what: String, slots: Iterable<Int>) = slots.forEach { log("$what slot $it: ${describe(it)}") }
        fun click(slot: Int, right: Boolean = false) = org.bukkit.Bukkit.getPluginManager().callEvent(org.bukkit.event.inventory.InventoryClickEvent(
            bot.openInventory, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, slot,
            if (right) org.bukkit.event.inventory.ClickType.RIGHT else org.bukkit.event.inventory.ClickType.LEFT,
            org.bukkit.event.inventory.InventoryAction.PICKUP_ALL))
        fun state() = "players=${s.players} random=${s.playersRandom} mode=${s.matchMode} label=${s.modeLabel} " +
            "teammates=${s.teammates} effective=${s.effectiveTeammates} opponents=${s.selectedBotNames} effective=${s.effectiveOpponents} kits=${s.botKits}"
        runCatching {
            setups.openMenu(bot)
            log("opened '${title()}', ${top().size} slots; ${state()}")
            dump("menu", 0..53)
            click(1); log("click No Players -> ${state()}"); dump("sandbox", listOf(0, 9, 18, 27, 36, 1, 10, 11, 12, 13, 14, 16, 17, 37, 38, 39, 40, 41, 45))
            click(12); log("click a dark difficulty -> difficulty=${s.difficulty} idle=${s.botsIdle}")
            click(27); log("click a dark personality -> personality=${s.botPersonality}")
            click(17); log("click the dark Choose Opponents -> '${title()}'")
            click(2); log("click 2 players -> ${state()}"); dump("duel", listOf(0, 27, 2, 10, 11, 16, 17, 37, 39, 40, 41, 45))
            click(4); log("click 8 players -> ${state()}")
            click(40); log("click two teams -> ${state()}"); dump("teams", listOf(4, 39, 40, 41, 43, 45))
            click(41); log("click Your Team -> '${title()}'"); dump("team menu", listOf(0, 1, 2, 45, 47, 49, 51, 53))
            // The team colour button: blue, red, random, and blue again.
            repeat(3) { click(47); log("clicked the team colour -> ${s.teamColor}; slot 47: ${describe(47)}") }
            click(0); click(1); log("put two on the team -> '${title()}'; ${state()}"); dump("team menu", listOf(0, 1, 2))
            click(2, right = true); log("right-click a third -> '${title()}'")
            click(1); log("picked the kit in slot 1 -> '${title()}'; ${state()}"); dump("team menu", listOf(0, 1, 2, 3, 49))
            click(3); log("a fourth for three seats -> ${state()}")
            click(0); log("took the first off again -> ${state()}")
            click(45); log("back -> '${title()}'"); dump("teams", listOf(17, 41))
            click(17); log("click Choose Opponents -> '${title()}'"); dump("opponents", listOf(0, 1, 2, 3, 49))
            click(1); log("a teammate made an opponent -> ${state()}"); dump("opponents", listOf(1))
            click(45); log("back -> '${title()}'")
            click(7); log("click Random players -> ${state()}"); dump("random", listOf(7, 39, 40, 41, 45))
            click(5); log("click 16 players -> ${state()}"); dump("sixteen", listOf(5, 39, 40))
            click(37); log("click FFA -> ${state()}"); dump("ffa", listOf(37, 40, 41))
            click(39); log("click bots vs you -> ${state()}"); dump("vs", listOf(39))
            click(38); log("click Team-Ups -> ${state()}"); dump("team-ups", listOf(38))
            click(49); log("click Close -> top inventory is now '${title()}' (${top().type})")
        }.onFailure { log("FAILED: $it"); it.printStackTrace() }
        BotManager.despawn(bot)
        log("done")
    }

    @Execute(name = "sandboxgame")
    @Description("Debug: one bot alone in a sandbox match (nobody to fight, nothing to win or lose); 'bots sandboxkill' takes a life off it to show the match goes on")
    fun sandboxGame(@Context sender: CommandSender, @Arg map: String) {
        if (!allowed(sender)) return
        val def = dataService.getMinigame("super_smash_mobs") ?: return
        BotManager.debug = true
        val setup = dev.betrix.superSmashMobsBrawl.bots.BotNavGen.shapeOf(map).apply(dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup(
            players = 1, items = false, crystals = false))
        val bots = BotManager.startBotGame(def, 1, null, map.substringBefore('@'), setup = setup)
        sender.sendMessage("Sandbox game: ${bots.joinToString { it.name }}")
    }

    @Execute(name = "sandboxkill")
    @Description("Debug: kills the fighter of every sandbox match once, and logs what the match and its hotbar look like")
    fun sandboxKill(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        for (game in minigameService.activeMinigames().filter { it.sandbox }) {
            for (fighter in game.allPlayers().mapNotNull { it.player }) {
                val hotbar = (0..8).joinToString(" ") { slot -> fighter.inventory.getItem(slot)?.type?.name?.lowercase() ?: "-" }
                plugin.logger.info("[sandbox] ${fighter.name}: state ${game.getState()}, lives ${game.teamManager.findTeamOf(fighter)?.metadata?.get("stocks")}, food ${fighter.foodLevel}, hotbar $hotbar")
                dev.betrix.superSmashMobsBrawl.events.BrawlDeathEvent.call(fighter, dev.betrix.superSmashMobsBrawl.events.DeathReason.Damage)
            }
        }
    }

    @Execute(name = "standin")
    @Description("Debug: puts the stand-in player of a Team-Ups test game at x y z and keeps it there (it still fights back), to see what the lobby does about a camper")
    fun standIn(@Context sender: CommandSender, @Arg x: Double, @Arg y: Double, @Arg z: Double) {
        if (!allowed(sender)) return
        for (id in BotManager.standIns) {
            val bot = org.bukkit.Bukkit.getPlayer(id) ?: continue
            val spot = org.bukkit.Location(bot.world, x, y, z)
            BotManager.brainOf(bot)?.rooted = true
            // A camper nobody can shift: put back and patched up every tick, for as long as it stands in.
            object : org.bukkit.scheduler.BukkitRunnable() {
                override fun run() {
                    val camper = org.bukkit.Bukkit.getPlayer(id)
                    if (camper == null || !camper.isOnline || id !in BotManager.standIns || camper.world != spot.world) return cancel()
                    if (camper.gameMode != org.bukkit.GameMode.SURVIVAL) return
                    if (camper.location.distanceSquared(spot) > 0.25) camper.teleport(spot)
                    camper.health = camper.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
                    camper.foodLevel = 20
                }
            }.runTaskTimer(plugin, 0L, 1L)
            plugin.logger.info("[teamups] ${bot.name} is camping at $x $y $z")
        }
    }

    @Execute(name = "heat")
    @Description("Debug: in every Team-Ups game, counts this much against each player (or stand-in) as camping, spam, farming or mouth")
    fun heat(@Context sender: CommandSender, @Arg amount: Double, @Arg reason: String) {
        if (!allowed(sender)) return
        val why = dev.betrix.superSmashMobsBrawl.bots.TeamUps.Reason.entries.find { it.name.equals(reason, true) }
            ?: return sender.sendMessage("Reason is one of camping, spam, farming, mouth")
        for (game in minigameService.activeMinigames().filterIsInstance<dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame>()) {
            if (!game.settings.teamUps) continue
            val players = game.allPlayers().mapNotNull { it.player }.filter { !BotManager.isBot(it) || it.uniqueId in BotManager.standIns }
            players.forEach { dev.betrix.superSmashMobsBrawl.services.BotConversationService.heat(game, it.uniqueId, amount, why) }
            plugin.logger.info("[teamups] +${amount.toInt()} ${why.name.lowercase()} for ${players.joinToString { it.name }}")
        }
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

    @Execute(name = "navkeys")
    @Description("Debug: the name each map's long jumps are filed under in each of its layouts, and the name they had before layouts were part of it")
    fun navKeys(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        for (map in dataService.getAllGameMaps().sortedBy { it.id })
            for (scale in dev.betrix.superSmashMobsBrawl.services.MapScale.entries) for (reverse in listOf(false, true))
                for (reflect in dev.betrix.superSmashMobsBrawl.services.ReflectMode.entries) {
                    val transform = dev.betrix.superSmashMobsBrawl.services.MapTransform(reverse, reflect, scale)
                    val def = if (transform.active) transform.definition(map) else map
                    // (As it was: the same without the layout.)
                    val shape = buildString {
                        append(def.voidLevel).append('|').append(def.worldBorderSize).append('|')
                        append(def.worldBorderCenterX).append('|').append(def.worldBorderCenterZ)
                        for (p in def.spawnPoints) append('|').append(p.x).append(',').append(p.y).append(',').append(p.z)
                    }
                    plugin.logger.info("[navkeys] ${map.id} ${transform.key.ifEmpty { "-" }} ${map.id}-${"%08x".format(shape.hashCode())} " +
                        dev.betrix.superSmashMobsBrawl.bots.ArenaJumps.keyOf(def))
                }
        plugin.logger.info("[navkeys] done")
    }

    @Execute(name = "mobs")
    @Description("Debug: the mobs standing in every match world right now, by kind and age in seconds (what a kit summoned and should have taken away again shows up here as an old one)")
    fun mobs(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        for (game in minigameService.activeMinigames()) {
            val world = game.worldManager.getWorld()?.world ?: continue
            val mobs = world.livingEntities.filter { it !is org.bukkit.entity.Player }
            plugin.logger.info("[mobs] ${game.worldManager.getWorld()?.data?.id}: " + if (mobs.isEmpty()) "none" else
                mobs.groupBy { it.type.name.lowercase() }.entries.joinToString(", ") { (kind, all) ->
                    "${all.size} $kind (oldest ${all.maxOf { it.ticksLived } / 20}s)" })
        }
    }

    @Execute(name = "lowblocks")
    @Description("Debug: what the running match's world has at and under its void level, inside the world border: blocks per height, and the lowest ones with their place and material")
    fun lowBlocks(@Context sender: CommandSender) {
        if (!allowed(sender)) return
        val bot = BotManager.onlineBots().firstOrNull { it.world.key.value().startsWith("ssmbworld") }
            ?: run { plugin.logger.info("[lowblocks] no match world"); return }
        val data = minigameService.getMinigameForPlayer(bot)?.worldManager?.getWorld()?.data
            ?: run { plugin.logger.info("[lowblocks] no match"); return }
        val world = bot.world
        val half = (data.worldBorderSize / 2).toInt()
        val cx = kotlin.math.floor(data.worldBorderCenterX).toInt()
        val cz = kotlin.math.floor(data.worldBorderCenterZ).toInt()
        val top = data.voidLevel.toInt() + 14
        val perHeight = java.util.TreeMap<Int, Int>()
        val lowest = ArrayList<Triple<Int, String, String>>()
        for (x in cx - half..cx + half) for (z in cz - half..cz + half) {
            if (!world.isChunkLoaded(x shr 4, z shr 4)) world.getChunkAt(x shr 4, z shr 4)
            for (y in world.minHeight..top) {
                val type = world.getBlockAt(x, y, z).type
                if (type.isAir) continue
                perHeight.merge(y, 1, Int::plus)
                if (y <= data.voidLevel.toInt() + 2) lowest += Triple(y, "$x $y $z", type.name.lowercase())
            }
        }
        plugin.logger.info("[lowblocks] ${data.id}: void at ${data.voidLevel}, border ${data.worldBorderSize} around $cx $cz, blocks up to y=$top by height: " +
            perHeight.entries.joinToString(" ") { "${it.key}:${it.value}" }.ifEmpty { "none" })
        plugin.logger.info("[lowblocks] at or under the void (+2): ${lowest.size}" + lowest.sortedBy { it.first }.take(30).joinToString("") { " | ${it.second} ${it.third}" })
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

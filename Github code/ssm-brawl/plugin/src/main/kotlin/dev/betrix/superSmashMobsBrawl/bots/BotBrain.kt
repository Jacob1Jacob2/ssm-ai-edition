package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.combat.*
import dev.betrix.superSmashMobsBrawl.bots.nms.BotPlayer
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.items.ItemService
import dev.betrix.superSmashMobsBrawl.services.BotConversationService
import dev.betrix.superSmashMobsBrawl.services.CombatLogService
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.models.brawlData.AbilityUsage
import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import dev.betrix.superSmashMobsBrawl.services.DataService
import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.services.MinigameService
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import net.minecraft.world.InteractionHand
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.bukkit.util.Vector
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Plays Super Smash Mobs for one bot. Runs every tick on the main thread.
 *
 * The bot "presses buttons" exactly like a client would: it fires the same click, right-click and
 * double-jump (toggle flight) events a real player's actions cause, so every kit, ability, cooldown
 * and knockback rule applies to bots unchanged.
 */
class BotBrain(private val nms: BotPlayer, private val profile: BotManager.BotProfile) :
    KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val minigameService: MinigameService by inject()
    private val dataService: DataService by inject()

    private val player: Player
        get() = nms.bukkitEntity

    private val d = profile.difficulty
    private val rng = Random()
    private val abilityKey by lazy { NamespacedKey(plugin, "abilityId") }

    private var task: BukkitTask? = null
    private var tick = 0L
    private var errorLogged = false
    private val lobbyWander = LobbyWander(nms)

    private var target: Player? = null
    private var yawError = 0f
    private var pitchError = 0f
    private var lastSafe: Location? = null
    private var nextClickTick = 0L
    private val abilityReadyAtMs = HashMap<String, Long>()
    private var holdUntilTick = -1L
    private var strafeDir = 1f
    private var nextStrafeFlip = 0L
    private var lastDoubleJumpTick = -100L
    private val unreachableUntil = HashMap<java.util.UUID, Long>()
    private var stuckRef: Location? = null
    private var routeSearch: ArenaRoute? = null
    private val route = ArrayDeque<RouteStep>()
    private var nextRouteTick = 0L
    private var routeGoal: Location? = null
    private val blockedSteps = HashMap<RouteNode, Long>()
    /** Where a wall climb came to nothing, and until when: no climbing from within a few blocks of there. */
    private val failedClimbs = ArrayList<Pair<RouteNode, Long>>()
    /** One jump that did not work (from a block, to a block). Other ways onto the block it was aimed at stay open. */
    private val blockedEdges = HashMap<Pair<RouteNode, RouteNode>, Long>()
    /** Remaining presses of the combo in progress, each with the tick it is due. */
    private class QueuedStep(val step: ComboStep, val due: Long, val mechanical: Boolean)
    private val comboQueue = ArrayDeque<QueuedStep>()

    /**
     * Something the bot has decided to use and is lining up: an ability or an item is only fired
     * once the bot is actually facing where it should go.
     *
     * @param speed projectile speed in blocks per tick, to lead a moving opponent (0 = no lead)
     * @param lob thrown in an arc, so aim above the opponent by an amount that grows with range
     */
    private class Shot(val name: String, val speed: Double, val lob: Boolean, val born: Long, val fire: () -> Unit)
    private var shot: Shot? = null
    /** Projectile speed of the ability being charged, so the bot keeps leading while it holds. */
    private var holdSpeed = 0.0

    // The item being carried: what it is, since when, and how often using it has failed.
    private var carried: dev.betrix.superSmashMobsBrawl.items.ArenaItem? = null
    private var carriedSince = 0L
    private var carriedFails = 0
    private var nextItemTick = 0L
    private var channelUntil = 0L
    /** The stretch of ticks this bot spends standing still, typing a chat message. */
    private var typingFrom = 0L
    private var typingUntil = 0L
    private var nextBowTick = 0L
    private var bowReleaseTick = 0L

    // Decision framework: what to do (state), who to fight (selector), how the kit plays (behavior).
    private val states = CombatStateMachine(CombatPolicy.of(d))
    private val selector = TargetSelector.of(d)
    private var opponents: List<Player> = emptyList()
    private var lastHealth = 0.0
    private val healthLosses = ArrayDeque<Pair<Long, Double>>()
    private var itemGoal: Location? = null
    /** Pad of the Smash Crystal this bot is going for, and when to stop ignoring an unreachable one. */
    private var crystalGoal: Location? = null
    private var crystalIgnoredUntil = 0L
    private val ignoredItems = HashMap<Triple<Int, Int, Int>, Long>()
    private var itemNearSince = -1L
    private var fleeGoal: Location? = null
    private var fleeGoalTick = -100L
    private var fleeFailedUntil = 0L
    /** The target's movement per tick, measured here because the server does not simulate humans. */
    private var trackedId: java.util.UUID? = null
    private var trackedAt: Location? = null
    private var trackedVelocity = Vector()

    /** The current state, for debugging and tests. */
    val state: CombatState get() = states.state

    /** A passive bot stands where it is put and never acts: the stand-in for a hiding player in tests. */
    @Volatile var passive = false

    /** Counts of navigation events since the last reset, read by the chase test. */
    class NavStats {
        var searches = 0; var unreachable = 0; var gapJumps = 0; var gapFails = 0
        var doubleJumps = 0; var blocked = 0; var recoveries = 0
        fun reset() { searches = 0; unreachable = 0; gapJumps = 0; gapFails = 0; doubleJumps = 0; blocked = 0; recoveries = 0 }
        override fun toString() = "searches=$searches unreachable=$unreachable gap=$gapJumps/$gapFails dj=$doubleJumps blocked=$blocked recov=$recoveries"
    }
    val navStats = NavStats()

    /** Forgets every route, grudge and jump in progress, as if the bot had just spawned here. */
    fun resetNavigation() {
        route.clear();routeSearch=null;routeGoal=null;nextRouteTick=0
        blockedSteps.clear();blockedEdges.clear();failedClimbs.clear();unreachableUntil.clear();unreachableAt.clear();withdrawUntil=0;stuckRef=null;gapJump=null;climb=null
        lastSafe=null;recovering=false;recoveryGoal=null;comboQueue.clear();shot=null
        fleeGoal=null;fleeFailedUntil=0;itemGoal=null
        navStats.reset()
    }

    fun debugSummary(): String =
        "state=${states.state} target=${target?.name} route=${route.size} searching=${routeSearch != null} " +
            "gapJump=${gapJump != null} recovering=$recovering onGround=${nms.onGround()} $navStats"

    private fun kitBehavior() = KitBehaviors.of(KitService.getKitForPlayer(player)?.id)
    private fun maxHealth(p: Player) = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
    private fun publish(event: (Long) -> CombatEvent) = BotConversationService.publish(player, event)

    private fun canDoubleJump() = player.allowFlight && (KitService.getKitForPlayer(player)?.getAbility("phantom_flight") as? dev.betrix.superSmashMobsBrawl.abilities.PhantomFlightAbility)?.flying != true

    /**
     * How this bot's kit gets around: what a route may ask of it. By the kit, not by whether the
     * jump happens to be in hand this instant: a route is walked from the ground, where the jump
     * is back. (Planning from mid-air with the jump just spent used to find "no way" to places
     * the bot had every way to, and it then sat that out for fifteen seconds.)
     */
    private fun mobility() = KitService.getKitForPlayer(player).let { Mobility.of(it?.id, it?.jumpPassive() != null) }

    /** Air jumps in hand right now: a chicken's remaining flaps, one double jump for everybody else. */
    private fun airJumpsLeft(): Int {
        val flap = KitService.getKitForPlayer(player)?.jumpPassive() as? dev.betrix.superSmashMobsBrawl.passives.FlapPassive
        return flap?.flapsLeft() ?: if (canDoubleJump()) 1 else 0
    }

    /** How many air jumps a ledge [rise] blocks overhead takes, going straight up. */
    private fun airJumpsFor(rise: Double): Int {
        val each = peakHeight(jumpLaunch(-88.0).second, 0.0) * 0.82
        return ceil((rise - 1.2).coerceAtLeast(0.1) / each.coerceAtLeast(0.5)).toInt()
    }

    /** The bot is standing still on purpose (getting its breath back for a climb): that is not being stuck. */
    private var patientTick = -100L

    /** Whether the last finished search found a way all the way to its goal. */
    private var routeReached = true

    private fun routeToward(to: Location, map: GameMapDef,
                            unreachable: () -> Unit = { target?.let(::markUnreachable) }): RouteStep? {
        val here = player.location
        // Keep walking a usable path, but not to where the target used to be: replan once the
        // target has moved a good part of the distance still to cover.
        if (routeSearch == null && route.isNotEmpty() && tick >= nextRouteTick &&
            (routeGoal?.distance(to) ?: 0.0) > maxOf(3.0, here.distance(to) * 0.3)) route.clear()
        if (route.isEmpty() && tick >= nextRouteTick && routeSearch == null) {
            blockedSteps.entries.removeIf { it.value <= tick }
            blockedEdges.entries.removeIf { it.value <= tick }
            failedClimbs.removeIf { it.second <= tick }
            // The arena's walking graph is shared by every bot of the same kind and kept between searches.
            val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
            // A fighter on the lip of a block has its centre over thin air; route from and to
            // the block it is really standing on.
            val start = nav.nearest(here, 5.0, if (nms.onGround()) 0.6 else 0.05)
            val goal = nav.nearest(to, 12.0)
            if(start != null && goal != null) {
                navStats.searches++
                routeSearch = ArenaRoute(start, goal, { n ->
                    nav.neighbours(n).filter {
                        it.node !in blockedSteps && (blockedEdges.isEmpty() || (n to it.node) !in blockedEdges) &&
                            (!it.climb || failedClimbs.none { (foot, _) -> abs(foot.x-n.x)<=3 && abs(foot.z-n.z)<=3 && abs(foot.y-n.y)<3.0 }) &&
                            abs(it.node.x-start.x)<=72 && abs(it.node.z-start.z)<=72
                    }
                }, ROUTE_LIMIT)
                routeGoal = to.clone()
                nextRouteTick = tick + if(d.smartTargeting) 30 else 50
            } else nextRouteTick = tick + 10
        }
        // A search takes at most 2.5ms of a tick, and all bots together at most six.
        val search = routeSearch
        val slice = if (search != null) RouteWorkBudget.slice() else 0L
        if(search != null && slice > 0) {
            val began = System.nanoTime()
            val result = search.advance(4000, began+slice)
            RouteWorkBudget.used(System.nanoTime()-began)
            if(result != null) {
                route.clear()
                route.addAll(search.remainingFrom(here.x,here.y,here.z,result))
                routeSearch=null
                routeReached=search.reached
                routeAt=search.start
                nextRouteTick=tick+5
                // No way through, and the bot already stands where the best attempt ends.
                val atEnd=result.isEmpty() || result.last().node.let { hypot(it.x+.5-here.x,it.z+.5-here.z)<1.5 && abs(it.y-here.y)<1.0 }
                if(!search.reached && atEnd) unreachable()
                dbg("route ${route.size} steps, ${search.expanded} expansions, reached=${search.reached}")
            }
        }
        // Drop every step the bot has reached, including ones it passed over in the air or was
        // knocked beyond. Going back to touch a block it has already got past looks lost.
        val height = if (nms.onGround()) .7 else 1.6
        var passed = -1
        for (i in 0 until minOf(route.size, 8)) {
            val n = route[i].node
            if (hypot(n.x+.5-here.x,n.z+.5-here.z)<.55 && abs(n.y-here.y)<height) passed = i
        }
        repeat(passed + 1) { routeAt = route.removeFirst().node }
        // A dash flies over a block a tick: whatever it has gone past is done with, whether or
        // not it came within arm's length of the middle of each block.
        if (tick <= dashUntil) dashDir?.let { (ux, uz) ->
            while (route.isNotEmpty()) {
                val n = route.first().node
                if ((n.x + .5 - here.x) * ux + (n.z + .5 - here.z) * uz > 0.3 || abs(n.y - here.y) > 2.0) break
                routeAt = route.removeFirst().node
            }
        }
        if(tick%5==0L && route.isNotEmpty()) {
            val n=route.first().node
            val fresh=ArenaTerrain(player.world,map.voidLevel.toInt(),canDoubleJump()).surface(n.x,n.z,n.y,.1,.1)
            if(fresh==null) {
                // The block the route was heading for is gone: forget what the graph knew here.
                forgetTerrain(n.x,n.z,map)
                route.clear();routeSearch=null;nextRouteTick=tick
            }
        }
        return route.firstOrNull()
    }

    /** Spends a little of the tick's route budget working out the arena's walking graph ahead of need. */
    private fun warmNav(world: org.bukkit.World, map: GameMapDef) {
        val nav = ArenaNav.of(world, map.voidLevel.toInt(), mobility())
        if (nav.warmed) return
        val slice = RouteWorkBudget.slice().coerceAtMost(1_500_000L)
        if (slice <= 0) return
        val began = System.nanoTime()
        nav.warm(map.spawnPoints.map { Location(world, it.x, it.y, it.z) }, slice)
        RouteWorkBudget.used(System.nanoTime() - began)
    }

    /** The arena changed here (or was never what the graph thought): have both graphs look again. */
    private fun forgetTerrain(x: Int, z: Int, map: GameMapDef) {
        ArenaNav.invalidateAll(player.world, x, z)
    }

    private fun inputToward(goal: Location): Pair<Float,Float> {
        val delta=goal.toVector().subtract(player.location.toVector()).setY(0)
        if(delta.lengthSquared()<.01)return 0f to 0f
        delta.normalize()
        val yaw=Math.toRadians(nms.yRot.toDouble())
        return (-sin(yaw)*delta.x+cos(yaw)*delta.z).toFloat() to (cos(yaw)*delta.x+sin(yaw)*delta.z).toFloat()
    }


    /** A deliberate jump across a gap: where we're aiming to land and whether we double jumped. */
    private class GapJump(val landing: Location, val startTick: Long, val takeoff: Location, from: RouteNode? = null) {
        var doubleJumped = false
        var leftGround = false
        /**
         * A ledge too high for one jump: go straight up first, and only then across onto it.
         * That is the path the route planner checked for headroom (the column over the take-off
         * block, then the air above the ledge). Running at the ledge instead puts the bot under
         * its overhang, where neither the jump nor the double jump gets off the ground.
         */
        val high = landing.y > takeoff.y + 1.3 &&
            // Unless it is too far away for that: then it is taken at a run instead (going straight
            // up first leaves the whole distance to the double jump, which does not have it, and
            // over a gap that was a fall into the void every time). The route was checked for
            // one way or the other by the same rule, measured between the middles of the blocks.
            Mobility.straightUp(landing.y - (from?.y ?: takeoff.y),
                kotlin.math.hypot(landing.x - (from?.let { it.x + .5 } ?: takeoff.x), landing.z - (from?.let { it.z + .5 } ?: takeoff.z)))
        /** Ticks the jump may take: a chicken flapping its way up a tower needs longer than a hop. */
        val limit = 60 + (maxOf(0.0, landing.y - takeoff.y) * 6).toInt()
        /**
         * For a landing close by and well below: the walking speed that carries the bot off the
         * edge and down onto it. A running jump at a ledge two blocks out and five down sails
         * clean over it. Zero when it takes a real jump to get there.
         */
        var stepOff = 0.0
        /** The block the jump was taken from, when it is a step of the route. */
        val from: RouteNode? = from
    }

    /** A wall climb in progress (spiders): up the wall beside [foot] until level with [ledge], then onto it. */
    private class Climb(val foot: Location, val ledge: Location, val startTick: Long)
    private var climb: Climb? = null
    private var nextDashTick = 0L
    private var dashTick = -1L
    private var dashUntil = -1L
    private var dashAim: Location? = null
    private var dashDir: Pair<Double, Double>? = null
    private var gapJump: GapJump? = null
    /** The route node the bot last stood on: where a jump along the route takes off from. */
    private var routeAt: RouteNode? = null
    private var wasOnGround = true
    private var jumpReasonTick = -100L
    private var jumpReason = ""
    /** Edge "carelessness" is rolled once per edge, not every tick spent standing at it. */
    private var edgeRolled = false

    private fun dbg(msg: String) {
        if (BotManager.debug) {
            val l = player.location
            plugin.logger.info(
                "[botdbg] ${profile.name} t=$tick ${"%.1f %.1f %.1f".format(l.x, l.y, l.z)} " +
                    "vy=${"%.2f".format(nms.deltaMovement.y)} $msg"
            )
        }
    }

    private var jumpFromY = 0.0

    private fun noteJump(reason: String) {
        jumpReason = reason
        jumpReasonTick = tick
        jumpFromY = player.location.y
    }

    /**
     * In the air because the bot jumped on purpose a moment ago, and still about as high as where
     * it took off and not yet dropping fast: it is on its way to a landing, whatever is (or is
     * not) underneath it for these few ticks. If the jump does miss, the bot is at most a block
     * below its take-off when this stops being true, with its double jump still in hand.
     */
    private fun midHop(): Boolean =
        tick - jumpReasonTick <= 18 && nms.deltaMovement.y > -0.55 && player.location.y > jumpFromY - 0.7

    /** Where each unreachable opponent was standing when the route search gave up on them. */
    private val unreachableAt = HashMap<java.util.UUID, Location>()
    private var withdrawUntil = 0L

    /**
     * True while [t] is still sitting where no route led. As long as they stay put there is no
     * point searching again every few seconds; the moment they move, the bot looks for a way.
     */
    private fun besieging(t: Player): Boolean =
        (unreachableUntil[t.uniqueId] ?: 0L) >= tick &&
            unreachableAt[t.uniqueId]?.let { it.world == t.world && it.distanceSquared(t.location) < 9.0 } == true

    /** Does this kit have anything that reaches an opponent [dist] blocks away? */
    private fun canHarmAt(dist: Double): Boolean {
        val bow = KitService.getKitForPlayer(player)?.id.let { it == "skeleton" || it == "zombie" }
        return (bow && dist <= 25) || kitAbilityIds().any { AbilityHints.of(it).let { h -> h.attack && h.damage > 0 && h.maxRange >= dist } }
    }

    /**
     * Nothing this kit has reaches them from any ground it can stand on, and they are hurting the
     * bot. Standing in the open under their fire is the one thing not to do: back off out of range
     * and let them come down.
     */
    private fun withdraw(t: Player, map: GameMapDef) {
        val here = player.location
        if (fleeGoal == null || tick - fleeGoalTick > 100) {
            val threat = t.location
            fleeGoal = map.spawnPoints.map { Location(here.world, it.x, it.y, it.z) }.maxByOrNull { it.distanceSquared(threat) }
            fleeGoalTick = tick
        }
        val goal = fleeGoal
        if (goal == null || here.distanceSquared(goal) < 9.0) {
            val (yaw, pitch) = lookAngles(player.eyeLocation, aimPointOf(t))
            turnTowards(yaw, pitch, d.turnSpeed)
            return setInputs(0f, 0f, false)
        }
        travel(goal, map) { withdrawUntil = 0 }
    }

    private fun markUnreachable(t: Player) {
        unreachableUntil[t.uniqueId] = tick + 300
        unreachableAt[t.uniqueId] = t.location.clone()
        navStats.unreachable++
        val bot = player.uniqueId
        val id = t.uniqueId
        publish { TargetUnreachable(bot, id, it) }
        // The target is kept for now. The next decision moves on to someone reachable if there
        // is anyone; if this is the only opponent left, the bot stays close and besieges them
        // instead of wandering off to the middle of the map and coming back.
    }

    private fun setTarget(next: Player?) {
        if (next == target) return
        val bot = player.uniqueId
        val from = target?.uniqueId
        val to = next?.uniqueId
        target = next
        publish { BotTargetChanged(bot, from, to, it) }
    }

    private fun enterState(next: CombatState) { states.force(next, tick)?.let(::stateChanged) }

    private fun stateChanged(previous: CombatState) {
        val now = states.state
        // Fleeing and fetching walk somewhere other than the target, so the old route is useless.
        if (setOf(previous, now).any { it == CombatState.FLEE || it == CombatState.GET_ITEM || it == CombatState.GET_CRYSTAL }) {
            route.clear();routeSearch=null;routeGoal=null;nextRouteTick=tick;stuckRef=null
        }
        fleeGoal = null
        itemNearSince = -1
        dbg("state $previous -> $now")
        val bot = player.uniqueId
        publish { BotStateChanged(bot, previous, now, it) }
    }

    fun start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { safeTick() }, 1L, 1L)
    }

    /** The bot is about to send a chat message: in [startInTicks] it stops and types for [ticks]. */
    fun typeFor(startInTicks: Int, ticks: Int) {
        typingFrom = tick + startInTicks
        typingUntil = typingFrom + ticks
        dbg("typing for $ticks ticks" + if (startInTicks > 0) " in $startInTicks" else "")
    }

    fun stop() {
        task?.cancel()
        task = null
        releaseInputs()
    }

    private fun safeTick() {
        try {
            tickBrain()
        } catch (e: Exception) {
            if (!errorLogged) {
                errorLogged = true
                plugin.logger.warning("Bot ${profile.name} AI error: ${e.stackTraceToString()}")
            }
        }
    }

    // ------------------------------------------------------------------ main loop

    private fun tickBrain() {
        tick++
        if (!player.isOnline || nms.isRemoved) return stop()

        if (holdUntilTick >= 0 && tick >= holdUntilTick) {
            nms.stopUsingItem()
            holdUntilTick = -1
        }

        val game = minigameService.getMinigameForPlayer(player)
        if (game == null && BotManager.isLobbyBot(player)) {
            lobbyWander.tick()
            return
        }
        if (passive) return releaseInputs()
        if (
            game == null ||
                game.getState() != MinigameState.IN_PROGRESS || game.settings.botsIdle ||
                player.gameMode != GameMode.SURVIVAL
        ) {
            // While the match counts down, work out how the arena can be walked, so the first
            // route of the fight is ready the moment it is needed.
            game?.worldManager?.getWorld()?.takeIf { it.world == player.world }?.let { warmNav(it.world, it.data) }
            target = null
            lastSafe = null
            gapJump = null
            climb = null
            route.clear();routeSearch=null;nextRouteTick=0
            blockedSteps.clear();blockedEdges.clear();stuckRef=null
            comboQueue.clear();bowReleaseTick=0;player.isSneaking=false
            shot=null;carried=null;channelUntil=0
            enterState(CombatState.IDLE)
            opponents=emptyList();healthLosses.clear();lastHealth=0.0
            itemGoal=null;ignoredItems.clear();fleeFailedUntil=0;trackedId=null
            crystalGoal=null;crystalIgnoredUntil=0
            releaseInputs()
            return
        }
        val map = game.worldManager.getWorld()?.data ?: return releaseInputs()
        dev.betrix.superSmashMobsBrawl.smash.SmashService.botTick(player)
        lastMap = map
        warmNav(player.world, map)

        val onGround = nms.onGround()
        // "Safe" is somewhere a fall can be steered back onto: properly on a block. Standing on
        // the lip of one, with the middle of the body over the drop, is not such a place; a
        // recovery aimed at that exact point comes down past the block.
        if (onGround && hasSolidBelow(player.location.clone().add(0.0, -0.05, 0.0), 1)) lastSafe = player.location.clone()
        if (wasOnGround && !onGround) {
            val v = nms.deltaMovement
            dbg(
                if (tick - jumpReasonTick <= 2) "left ground: jumped ($jumpReason)"
                else "left ground: pushed/walked off (vx=${"%.2f".format(v.x)} vz=${"%.2f".format(v.z)})"
            )
        }
        wasOnGround = onGround

        val decide = tick % d.reactionTicks == 0L
        trackHealth()
        if (decide) {
            chooseTarget(game)
            yawError = (rng.nextGaussian() * d.aimErrorDegrees).toFloat()
            pitchError = (rng.nextGaussian() * d.aimErrorDegrees * 0.5).toFloat()
        }

        if (bowReleaseTick>0 && tick>=bowReleaseTick) {
            nms.releaseUsingItem();bowReleaseTick=0;nextBowTick=tick+15
        }
        trackTarget()
        // Typing a chat message: hands are on the keyboard, so no moving, jumping or fighting until
        // it is sent. Only on solid ground; a bot knocked into the air deals with that first.
        if (tick in typingFrom until typingUntil && onGround && gapJump == null && climb == null && !recovering && !isOverVoid(map)) {
            comboQueue.clear(); shot = null
            if (tick == typingUntil - 1) dbg("typing done")
            return releaseInputs()
        }
        comboQueue.firstOrNull()?.takeIf { tick >= it.due }?.let { next ->
            comboQueue.removeFirst()
            val t = target
            // A combo is abandoned, not resumed, once the opponent is gone or we are falling.
            if(isOverVoid(map) || t == null || !player.hasLineOfSight(t)) comboQueue.clear()
            else when(val step = next.step) {
                is ComboStep.Ability -> {
                    val hint = AbilityHints.of(step.id)
                    val dist = player.eyeLocation.distance(aimPointOf(t))
                    // A second press finishes the ability wherever the opponent went; a
                    // follow-up with another ability is only worth it if they are in its range.
                    if(next.mechanical || (isReady(step.id) && dist >= hint.minRange - 1 && dist <= hint.maxRange + 2))
                        useAbility(step.id,hint,false)
                    else comboQueue.clear()
                }
                is ComboStep.Melee -> meleeClick()
            }
        }
        // A shot nobody got round to firing (the fight moved on) is forgotten.
        if (shot?.let { tick - it.born > 20 } == true) shot = null
        considerItem(target?.takeIf { it.isOnline && it.world == player.world }, map)
        if (tick < channelUntil) {
            // Channelling an Extra Life: any movement ends it, so stand still unless attacked.
            if (opponents.any { it.world == player.world && it.location.distanceSquared(player.location) < 36 }) channelUntil = 0
            else return releaseInputs()
        }
        val kitId=KitService.getKitForPlayer(player)?.id
        player.isSneaking = when(kitId) {
            // Climb only a wall that is in the way of where the bot is actually going. Climbing
            // every wall it touched whenever the opponent stood higher left spiders hanging on
            // walls that led nowhere.
            // (A climb that is part of the route is taken from the middle of the block at the
            // foot of the wall, not from wherever the spider first bumps into it.)
            "spider" -> climb != null || (nms.horizontalCollision && player.exp>.05 && route.firstOrNull()?.climb != true &&
                (gapJump?.landing?.y ?: route.firstOrNull()?.node?.y ?: (if (recovering) recoveryGoal?.y else null) ?: target?.y ?: 0.0) > player.y+.6)
            "goat" -> nms.onGround() && player.exp<.8 && (target?.location?.distanceSquared(player.location) ?: 0.0)>100
            "enderman" -> nms.onGround() && target?.let { it.location.distanceSquared(player.location)>400 && player.hasLineOfSight(it) } == true
            "phantom" -> player.isFlying && target?.let { hypot(it.x-player.x,it.z-player.z)<3 && it.y<player.y-1 } == true
            else -> false
        }
        if(player.isFlying && kitId=="phantom") {
            val dest=target?.location?.clone()?.add(0.0,3.0,0.0) ?: lastSafe
            if(dest!=null) {
                val dir=dest.toVector().subtract(player.location.toVector())
                if(dir.lengthSquared()>.1)player.velocity=dir.normalize().multiply(.35)
                val (yaw,pitch)=lookAngles(player.eyeLocation,target?.let { aimPointOf(it) } ?: dest)
                turnTowards(yaw,pitch,d.turnSpeed)
                if(decide && target!=null && shot==null) {
                    planAbility(target!!,player.location.distance(target!!.location))
                    // Already facing them in flight, so there is nothing to line up.
                    shot?.let { shot=null;it.fire() }
                }
            }
            setInputs(0f,0f,false);return
        }
        // The leap half of a dash: fired a few ticks into the jump, level down the lane.
        if (tick == dashTick) dashAim?.let { aim ->
            if (!nms.onGround() && canDoubleJump() && gapJump == null && climb == null && !recovering) {
                val (yaw, _) = lookAngles(player.eyeLocation, aim)
                nms.setYRot(yaw)
                nms.setYHeadRot(yaw)
                nms.setXRot(-6f)
                val here = player.location
                val length = hypot(aim.x - here.x, aim.z - here.z).coerceAtLeast(0.01)
                dashDir = (aim.x - here.x) / length to (aim.z - here.z) / length
                dashUntil = tick + 14
                doubleJump("dash")
            }
        }
        // A wall climb or a gap jump in progress steers itself; recovery must not turn it around mid-air.
        if (climb != null && continueClimb(map)) return
        if (gapJump != null && continueGapJump(map)) return

        // A hop across a small gap passes over the drop for a few ticks. That is the route
        // working, not a fall: recovering from it (a double jump back the way it came) used to
        // leave the bot with nothing in hand when it then really did fall.
        if (isOverVoid(map) && !midHop()) {
            enterState(CombatState.RECOVER)
            recover(map, decide)
            return
        }
        if (recovering) {
            recovering = false
            dbg("back over solid ground")
        }

        val t = target?.takeIf { it.isOnline && it.world == player.world }
        if (decide || states.state == CombatState.IDLE || states.state == CombatState.RECOVER) updateState(t)
        when (states.state) {
            CombatState.FLEE -> if (t != null) flee(t, decide, map) else wander(map)
            CombatState.GET_ITEM -> itemGoal?.let { collect(it, map) } ?: wander(map)
            CombatState.GET_CRYSTAL -> crystalGoal?.let { claimCrystal(it, t, map) } ?: wander(map)
            else -> if (t == null) wander(map) else fight(t, decide, map)
        }
    }

    // ------------------------------------------------------------------ state machine

    private fun trackHealth() {
        val health = player.health
        if (health < lastHealth) healthLosses.addLast(tick to lastHealth - health)
        lastHealth = health
        while (healthLosses.firstOrNull()?.let { tick - it.first > 40 } == true) healthLosses.removeFirst()
    }

    private fun trackTarget() {
        val t = target
        val at = t?.location
        val last = trackedAt
        trackedVelocity =
            if (t != null && at != null && last != null && trackedId == t.uniqueId && last.world == at.world)
                at.toVector().subtract(last.toVector()).takeIf { it.lengthSquared() < 2.25 } ?: Vector()
            else Vector()
        trackedId = t?.uniqueId
        trackedAt = at
    }

    private fun updateState(t: Player?) {
        val here = player.location
        val max = maxHealth(player)
        val item = nearestItem()
        itemGoal = item
        val crystal = if (tick < crystalIgnoredUntil) null
            else dev.betrix.superSmashMobsBrawl.smash.SmashService.crystalFor(player)?.takeIf { it.first.world == here.world }
        if (crystal != null && crystalGoal == null) dbg("crystal: pad ${crystal.first.blockX},${crystal.first.blockY},${crystal.first.blockZ} lands in ${crystal.second} ticks")
        crystalGoal = crystal?.first
        val situation = CombatSituation(
            crystalDistance = crystal?.first?.distance(here),
            crystalLandsIn = crystal?.second ?: 0,
            health = player.health / max,
            targetHealth = t?.let { it.health / maxHealth(it) },
            targetDistance = t?.location?.distance(here),
            itemDistance = item?.distance(here),
            itemEnemyDistance = item?.let { i -> opponents.filter { it.world == i.world }.minOfOrNull { it.location.distance(i) } },
            recentDamage = healthLosses.sumOf { it.second } / max,
            attacksReady = kitAbilityIds().count { AbilityHints.of(it).attack && isReady(it) },
        )
        states.update(situation, tick)?.let(::stateChanged)
    }

    private fun itemKey(at: Location) = Triple(at.blockX, at.blockY, at.blockZ)

    private fun nearestItem(): Location? {
        ignoredItems.entries.removeIf { it.value <= tick }
        val here = player.location
        return ItemService.pickupLocations(player)
            .filter { it.world == here.world && itemKey(it) !in ignoredItems && abs(it.y - here.y) < 12 }
            .minByOrNull { it.distanceSquared(here) }
            ?.takeIf { it.distanceSquared(here) < 900 }
    }

    private fun ignoreItem(at: Location, ticks: Long) {
        ignoredItems[itemKey(at)] = tick + ticks
        itemGoal = null
    }

    /** Walks to a pickup; gives up on one it cannot reach or cannot use. */
    private fun collect(goal: Location, map: GameMapDef) {
        val near = player.location.distanceSquared(goal) < 4.0
        if (!near) itemNearSince = -1 else if (itemNearSince < 0) itemNearSince = tick
        if ((near && tick - itemNearSince > 30) || states.ticksInState(tick) > 300) return ignoreItem(goal, 600)
        travel(goal, map) { ignoreItem(goal, 300) }
    }

    /**
     * Runs to the Smash Crystal's pad and stands on it until the crystal can be taken (a bot within
     * reach of a landed crystal takes it by itself, see SmashService.botTick).
     */
    private fun claimCrystal(pad: Location, t: Player?, map: GameMapDef) {
        val here = player.location
        if (hypot(pad.x - here.x, pad.z - here.z) < 1.0 && abs(pad.y - here.y) < 1.5) {
            // On the pad: watch whoever is coming instead of staring at the floor.
            t?.let { val (yaw, pitch) = lookAngles(player.eyeLocation, aimPointOf(it)); turnTowards(yaw, pitch, d.turnSpeed) }
            setInputs(0f, 0f, false)
            return
        }
        travel(pad, map) {
            dbg("crystal: no way to the pad, leaving it")
            crystalIgnoredUntil = tick + 200
            crystalGoal = null
        }
    }

    /** Runs to the spawn point that puts the most distance between the bot and its opponent. */
    private fun flee(t: Player, decide: Boolean, map: GameMapDef) {
        if (tick < fleeFailedUntil) return fight(t, decide, map)
        val here = player.location
        if (fleeGoal == null || tick - fleeGoalTick > 60) {
            val threat = t.location
            fleeGoal = map.spawnPoints.map { Location(here.world, it.x, it.y, it.z) }
                .maxByOrNull { it.distance(threat) - it.distance(here) * 0.5 }
            fleeGoalTick = tick
        }
        val goal = fleeGoal
        // Nowhere further to run: turn around and fight.
        if (goal == null || here.distanceSquared(goal) < 9.0) return fight(t, decide, map)
        // Something thrown over the shoulder at a chaser (a mine, a grenade) is still aimed.
        val aimPoint = aimPointOf(t)
        val turning = lineUpShot(aimPoint, player.eyeLocation.distance(aimPoint), player.hasLineOfSight(t))
        travel(goal, map, !turning) { fleeFailedUntil = tick + 100 }
    }

    /**
     * Follows the route to [goal] without fighting. Shared by fleeing and item collection.
     * [face] is false when something else (a shot being lined up) already decides where the bot looks.
     */
    private fun travel(goal: Location, map: GameMapDef, face: Boolean = true, unreachable: () -> Unit) {
        val here = player.location
        val step = routeToward(goal, map, unreachable)
        if (face) {
            val ahead = step?.let { Location(here.world, it.node.x + .5, it.node.y + 1.6, it.node.z + .5) }
                ?: goal.clone().add(0.0, 1.2, 0.0)
            val (yaw, _) = lookAngles(player.eyeLocation, ahead)
            turnTowards(yaw, 0f, d.turnSpeed)
        }
        var forward = 0f
        var strafe = 0f
        var jump = false
        if (step != null) {
            val (f, s, j) = stepInputs(step) ?: return
            forward = f; strafe = s; jump = j
        } else if (hypot(goal.x - here.x, goal.z - here.z) > .4) {
            val input = inputToward(goal); forward = input.first; strafe = input.second
        }
        val (f, s) = applyMovement(step, forward, strafe, jump, map)
        checkStuck(true, f, s)
    }

    /** Standing or hanging in a cobweb, where jumps and leaps do nothing and only walking gets out. */
    private fun inWeb(): Boolean {
        val feet = player.location.block
        return feet.type == org.bukkit.Material.COBWEB || feet.getRelative(BlockFace.UP).type == org.bukkit.Material.COBWEB
    }

    /** Inputs that follow a route step. Null when the step needs a gap jump, which then steers itself. */
    private fun stepInputs(step: RouteStep): Triple<Float, Float, Boolean>? {
        val dest=Location(player.world,step.node.x+.5,step.node.y,step.node.z+.5)
        if (inWeb()) {
            // Just push through toward the next step; the web is gone in a couple of seconds.
            val input=inputToward(dest)
            return Triple(input.first,input.second,false)
        }
        if(step.climb) return climbInputs(dest)
        if(nms.onGround() && !step.jump && dest.y-player.y>1.3) {
            // A walking step that far overhead means the route was planned from somewhere the
            // bot is not (it was knocked down, or planned mid-fall). Hopping at it gets nowhere.
            route.clear();routeSearch=null;nextRouteTick=tick
            return Triple(0f,0f,false)
        }
        if(nms.onGround() && step.jump && dest.y-player.y>1.25 && player.exp<.999f &&
            KitService.getKitForPlayer(player)?.jumpPassive() is dev.betrix.superSmashMobsBrawl.passives.FlapPassive &&
            airJumpsLeft()<airJumpsFor(dest.y-player.y)) {
            // A chicken that has just flown here has to get its flaps back before a tower.
            // Standing on the ground does that in a couple of seconds. (Only a chicken: nobody
            // else gets more jumps by waiting, and a bar that is already full has no more to give.)
            if(tick-patientTick>40) dbg("out of flaps for a ${"%.0f".format(dest.y-player.y)} block rise; waiting")
            patientTick=tick
            return Triple(0f,0f,false)
        }
        if(nms.onGround() && step.jump && dest.y-player.y>1.25) {
            // A high ledge is climbed straight up from the middle of the block below it; from
            // anywhere else the way up may be under the ledge's own overhang.
            val from=routeAt
            if(from!=null && abs(from.y-player.y)<.6) {
                val off=hypot(from.x+.5-player.x,from.z+.5-player.z)
                if(off>.25 && off<1.6) {
                    val input=inputToward(Location(player.world,from.x+.5,from.y,from.z+.5))
                    val gentle=if(off<.6) .45f else 1f
                    return Triple(input.first*gentle,input.second*gentle,false)
                }
            }
        }
        if(nms.onGround() && step.jump && dest.y-player.y>mobility().up+1.6) {
            // Far more than this kit can jump: the bot is not where the route was planned from
            // (it fell off the way there). Jumping at it anyway got nowhere; plan again from here.
            route.clear();routeSearch=null;nextRouteTick=tick
            return Triple(0f,0f,false)
        }
        if(nms.onGround() && step.jump) {
            val across=hypot(dest.x-player.x,dest.z-player.z)
            // (A long drop is steered the same way, so the bot comes down on the ledge and not past it.
            // So is a hop onto a block with nothing around it: run at that like any other step
            // and the bot is off the far side of it.)
            if(across>1.6 || dest.y-player.y>1.25 || player.y-dest.y>3.0 || (across>.9 && narrow(dest))) {
                startGapJump(Gap(player.location.distance(dest),dest));return null
            }
        }
        val input=inputToward(dest)
        val jump=nms.onGround() && (step.jump || step.node.y>player.y+.6 || nms.horizontalCollision)
        if(jump)noteJump("route step")
        else if(nms.onGround() && input.first>.8f) dashLane()?.let { lane ->
            // A spider or a wolf covers open ground with its leap, not on foot.
            val along=inputToward(lane)
            if(along.first>.8f) {
                noteJump("dash")
                dashTick=tick+DASH_DELAY;dashAim=lane;nextDashTick=tick+12
                return Triple(along.first,along.second,true)
            }
        }
        if(!jump && nms.onGround() && d.strafes && input.first>.8f) {
            // Good players cross open ground in sprint jumps; it is a quarter faster than running.
            // The jump is aimed down the whole straight, not at the next block: aimed at a block
            // half a step ahead and slightly to one side, it would leave a narrow bridge sideways.
            val far=straightRunAhead()
            if(far!=null) {
                val along=inputToward(far)
                if(along.first>.8f) {
                    noteJump("sprint jump")
                    return Triple(along.first,along.second,true)
                }
            }
        }
        return Triple(input.first,input.second,jump)
    }

    /**
     * A wall to climb (spiders). The climb is planned straight up from the middle of the block at
     * the foot of the wall, so that is where it starts: walk there, wait if the climb needs more
     * energy than is in the bar, then go up. Null once the climb has begun (it steers itself).
     */
    private fun climbInputs(ledge: Location): Triple<Float, Float, Boolean>? {
        val from = routeAt
        val here = player.location
        if (from == null || abs(from.y - here.y) > 0.6 || hypot(from.x + .5 - here.x, from.z + .5 - here.z) > 1.7) {
            if (!nms.onGround()) {
                // Still coming down onto the foot of the wall.
                val input = inputToward(from?.let { Location(here.world, it.x + .5, it.y, it.z + .5) } ?: ledge)
                return Triple(input.first, input.second, false)
            }
            // Not where the climb was planned from (knocked away from it): plan again from here.
            route.clear(); routeSearch = null; nextRouteTick = tick
            return Triple(0f, 0f, false)
        }
        val foot = Location(here.world, from.x + .5, from.y, from.z + .5)
        val off = hypot(foot.x - here.x, foot.z - here.z)
        if (off > 0.22) {
            val input = inputToward(foot)
            val gentle = if (off < .6) .45f else 1f
            return Triple(input.first * gentle, input.second * gentle, false)
        }
        if (!nms.onGround()) return Triple(0f, 0f, false)
        // A full bar is ten seconds on the wall, at eight blocks a second.
        val needed = ((ledge.y - here.y) * CLIMB_ENERGY_PER_BLOCK + 0.08).toFloat().coerceAtMost(0.98f)
        if (player.exp < needed) {
            if (tick - patientTick > 40) dbg("needs ${"%.2f".format(needed)} energy to climb ${"%.0f".format(ledge.y - here.y)} blocks, has ${"%.2f".format(player.exp)}; waiting")
            patientTick = tick
            return Triple(0f, 0f, false)
        }
        climb = Climb(foot, ledge, tick)
        dbg("climbing the wall to ${"%.1f %.1f %.1f".format(ledge.x, ledge.y, ledge.z)}")
        return null
    }

    /** Takes a wall climb up. Returns false once it is over (handed on to the hop onto the ledge, or given up). */
    private fun continueClimb(map: GameMapDef): Boolean {
        val c = climb ?: return false
        val here = player.location
        val age = tick - c.startTick
        val rise = c.ledge.y - c.foot.y
        val off = hypot(c.foot.x - here.x, c.foot.z - here.z)
        // No wall after all, out of energy, knocked off it, or caught on something on the way up.
        val failed = when {
            inWeb() -> "caught in a web"
            player.exp <= 0.02f -> "out of energy"
            off > 1.4 -> "knocked off the wall"
            age > 18 && here.y < c.foot.y + 0.6 -> "nothing to hold on to"
            age > 30 + rise * 4 -> "taking too long"
            else -> null
        }
        if (failed != null) {
            dbg("climb abandoned: $failed")
            climb = null
            player.isSneaking = false
            // Lack of breath is cured by waiting at the bottom; anything else by another way up.
            if (failed != "out of energy") {
                blockedSteps[RouteNode(c.ledge.blockX, (c.ledge.y * 16).roundToInt(), c.ledge.blockZ)] = tick + 200
                // The next ledge along is up the same wall: stay off this stretch of it for a while.
                failedClimbs += RouteNode(c.foot.blockX, (c.foot.y * 16).roundToInt(), c.foot.blockZ) to tick + 300
                navStats.blocked++
            }
            route.clear(); routeSearch = null; nextRouteTick = tick
            return false
        }
        if (here.y >= c.ledge.y + 0.45) {
            // Level with the ledge: let go of the wall and get onto it, with the leap if need be.
            climb = null
            player.isSneaking = false
            dbg("top of the climb, onto the ledge")
            gapJump = GapJump(c.ledge, tick, here.clone()).also { it.leftGround = true }
            return continueGapJump(map)
        }
        player.isSneaking = true
        val (yaw, _) = lookAngles(player.eyeLocation, c.ledge)
        turnTowards(yaw, 0f, 60f)
        // Stay in the column the climb was planned in; the wall is right beside it.
        val input = if (off > 0.2) inputToward(c.foot) else 0f to 0f
        // A wall that only begins a block up (over a doorway, under a balcony) is caught with a hop.
        setInputs(input.first, input.second, nms.onGround() && age in 4..9)
        return true
    }

    /**
     * Spider: Spin Web aimed at the floor throws its user straight up. With the leap already
     * spent, that is the jump that still gets it onto the ledge (or back out of the void).
     */
    private fun webLaunch(toward: Location): Boolean {
        if (KitService.getKitForPlayer(player)?.id != "spider" || holdUntilTick >= 0 || !isReady("spin_web")) return false
        val (yaw, _) = lookAngles(player.eyeLocation, toward)
        nms.setYRot(yaw)
        nms.setYHeadRot(yaw)
        nms.setXRot(60f)
        return useAbility("spin_web", AbilityHints.of("spin_web"), false)
    }

    /**
     * Kits whose air jump goes forward like a dash (a spider's leap, a wolf's pounce) cover open
     * ground the way their players do: jump, leap level down the lane, land running, again. That
     * is twice as fast as running it. Returns the far end of a lane that is safe to dash down
     * (flat floor under the whole flight, nothing in the way, no ceiling to hit), or null.
     */
    private fun dashLane(): Location? {
        if (tick < nextDashTick || route.size < DASH_NODES || gapJump != null) return null
        // Whatever the answer, it is not asked again every tick.
        nextDashTick = tick + 6
        if (!canDoubleJump() || nms.horizontalCollision || inWeb() || jumpLaunch(-6.0).first < 0.9) return null
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED) || tick - lastDoubleJumpTick < 10) return null
        val map = lastMap ?: return null
        val here = player.location
        // Level ground all the way: no jump, climb or drop among the steps the dash passes over.
        for (i in 0 until DASH_NODES) {
            val s = route[i]
            if (s.jump || s.climb || abs(s.node.y - here.y) > 0.05) return null
        }
        val end = route[DASH_NODES - 1].node
        val dx = end.x + .5 - here.x
        val dz = end.z + .5 - here.z
        val length = hypot(dx, dz)
        // The route doubles back on itself within a few steps: that is not a lane.
        if (length < DASH_LENGTH) return null
        val ux = dx / length
        val uz = dz / length
        val terrain = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
        var s = 0.5
        while (s <= minOf(length, DASH_LENGTH + 3.0)) {
            val x = here.x + ux * s
            val z = here.z + uz * s
            // Room for the body all the way, at ground level and at the top of the leap.
            if (!terrain.clear(x, here.y + 0.05, z) || !terrain.clear(x, here.y + 1.5, z)) return null
            // Floor under both shoulders: a dash along a one-block beam is a dash off it.
            for (side in DASH_SHOULDERS) {
                if (!hasSolidBelow(Location(here.world, x - uz * side, here.y - 0.1, z + ux * side), 1)) return null
            }
            s += 0.5
        }
        return Location(here.world, end.x + .5, end.y, end.z + .5)
    }

    /**
     * The far end of a level, straight, jump-free stretch of route starting where the bot stands,
     * or null if the next few blocks are not one. Such a stretch is safe to cover in a sprint jump.
     */
    private fun straightRunAhead(): Location? {
        if (route.size < 6 || nms.horizontalCollision || tick - lastDoubleJumpTick < 10) return null
        // A speed boost carries a jump further than the stretch that was checked.
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED) || inWeb()) return null
        val here = player.location
        val far = route[5].node
        val dx = far.x + .5 - here.x
        val dz = far.z + .5 - here.z
        val length = hypot(dx, dz)
        if (length < 4.5) return null
        for (i in 0..5) {
            val n = route[i].node
            if (route[i].jump || abs(n.y - here.y) > 0.05) return null
            // Distance of this block from the straight line to the far one.
            if (abs((n.x + .5 - here.x) * dz - (n.z + .5 - here.z) * dx) / length > 0.3) return null
        }
        // The graph is remembered, so look at the world itself along the flight: floor under
        // every block of it, and headroom so the jump is not a hop into a low ceiling.
        for (i in 1..5) {
            val at = here.clone().add(dx / length * i, 0.0, dz / length * i)
            if (!hasSolidBelow(at.clone().add(0.0, -0.1, 0.0), 1)) return null
            if (i <= 3 && hasCeiling(at, here.y + 2.6)) return null
        }
        if (hasCeiling(here, here.y + 2.6)) return null
        return Location(here.world, far.x + .5, far.y, far.z + .5)
    }

    /** Applies inputs after stopping any that would carry the bot off an edge. Returns what was applied. */
    private fun applyMovement(step: RouteStep?, f: Float, s: Float, jump: Boolean, map: GameMapDef): Pair<Float, Float> {
        var forward=f
        var strafe=s
        if(nms.onGround()) {
            val lookAhead=step?.let { minOf(.9,hypot(it.node.x+.5-player.x,it.node.z+.5-player.z)) } ?: .9
            if(!jump && !groundAhead(forward,strafe,lookAhead)) {forward=0f;strafe=0f}
            // Brake input against existing momentum before it carries us over an edge.
            val v=nms.deltaMovement
            if(!jump && hypot(v.x,v.z)>.12 && !hasSolidBelow(player.location.clone().add(v.x*5,0.0,v.z*5),4)) {
                val input=inputToward(player.location.clone().subtract(v.x*2,0.0,v.z*2))
                forward=input.first;strafe=input.second
            }
        } else if(step==null && !groundAheadToVoid(forward,strafe,map)) {
            // A planned landing has already been checked. Looking 1.5 blocks beyond
            // a one-block stepping stone used to reverse the bot halfway through its jump.
            val input=steerTo(lastSafe ?: player.location);forward=input.first;strafe=input.second
        }
        setInputs(forward,strafe,jump)
        return forward to strafe
    }

    /** Every two seconds: if the bot has not moved while it still has somewhere to be, try another way. */
    private fun checkStuck(far: Boolean, forward: Float, strafe: Float) {
        if(tick%40!=0L) return
        val here=player.location
        // Being held by a cobweb says nothing about the route, and neither does waiting on purpose.
        if(inWeb() || tick-patientTick<=40) { stuckRef=null;return }
        if(routeSearch==null && route.isNotEmpty() &&
            stuckRef?.let {it.world==here.world && hypot(it.x-here.x,it.z-here.z)<.55}==true && far) {
            // Waiting for a bounded path search is not a movement failure. Canceling
            // that search here prevented complex routes from ever finishing.
            // Vertical bouncing against a wall is not progress, either. Avoid this
            // step long enough to find a different approach instead of repeating it.
            blockedSteps[route.first().node]=tick+200
            navStats.blocked++
            lastMap?.let { forgetTerrain(route.first().node.x,route.first().node.z,it) }
            dbg("blocked route step ${route.first().node}; finding another approach")
            route.clear();routeSearch=null;nextRouteTick=tick
            if(nms.horizontalCollision && groundAhead(forward,strafe))setInputs(forward,strafe,true)
        }
        stuckRef=here.clone()
    }

    /** Expert bots run to where the opponent is going to be instead of where they were. */
    private fun chaseGoal(t: Player, dist: Double): Location {
        val at = t.location
        if (!d.smartTargeting) return at
        val v = trackedVelocity
        val from = Prediction.Point(at.x, at.y, at.z)
        val to = Prediction.position(from, v.x, v.y, v.z, abs(v.y) < 0.05, Prediction.leadTicks(dist), at.y - 12)
        if (Prediction.negligible(from, to)) return at
        val predicted = Location(at.world, to.x, to.y, to.z)
        // Never chase a prediction out over the void.
        return if (hasSolidBelow(predicted, 12)) predicted else at
    }

    /** Steers an in-progress gap jump. Returns false once it's over. */
    private fun continueGapJump(map: GameMapDef): Boolean {
        val g = gapJump ?: return false
        val age = tick - g.startTick
        if (inWeb()) {
            // Nothing jumps out of a cobweb. Give the jump up without blaming the landing for it;
            // walking carries the bot out, and the route is still good.
            dbg("gap jump abandoned: caught in a web")
            gapJump = null
            return false
        }
        if(!nms.onGround())g.leftGround=true
        if ((g.leftGround && age > 3 && nms.onGround()) || age > g.limit || (!g.leftGround && age>(if (g.stepOff>0.0) 30 else 12))) {
            val across = hypot(player.x-g.landing.x,player.z-g.landing.z)
            val landed = nms.onGround() && abs(player.y-g.landing.y)<.8 && across<1.0
            // Down on the block next to it is as good as on it: the route carries on from there.
            // (Counting that as a miss, and the landing as a place not to go, used to cut the
            // bot off from the very spot it had just reached.)
            val beside = !landed && nms.onGround() && abs(player.y-g.landing.y)<=1.3 && across<1.9
            if(!landed) {
                if(!beside) {
                    navStats.gapFails++
                    val to = RouteNode(g.landing.blockX,(g.landing.y*16).roundToInt(),g.landing.blockZ)
                    val from = g.from
                    if(from!=null) blockedEdges[from to to]=tick+200 else blockedSteps[to]=tick+200
                    forgetTerrain(g.landing.blockX,g.landing.blockZ,map)
                }
                route.clear();routeSearch=null;nextRouteTick=tick
            }
            dbg("gap jump finished (reached landing=$landed${if(beside) ", came down beside it" else ""})")
            gapJump = null
            return false
        }
        val (yaw, _) = lookAngles(player.eyeLocation, g.landing)
        turnTowards(yaw, 0f, 90f)
        if (!g.leftGround && g.stepOff > 0.0) {
            // Walk off the edge; no faster than lands on the ledge below.
            setInputs((g.stepOff / 0.216).toFloat().coerceIn(0.25f, 1f), 0f, false)
            return true
        }
        if (!g.leftGround) {
            // Hold jump through the vanilla jump delay instead of treating a missed
            // one-tick input as a successful landing at our original position.
            // (For a high ledge the jump is straight up: running at it puts the bot under
            // the ledge's lip, where it only hits its head.)
            noteJump("gap jump")
            setInputs(if (g.high) 0f else 1f, 0f, true)
            return true
        }
        // In the air: steer so we coast onto the landing spot instead of past it. Below the
        // lip of a high ledge, hold the take-off column instead and go across once above it.
        val rising = g.high && player.location.y < g.landing.y - 0.15
        val (f, s) = steerTo(if (rising) g.takeoff.clone().also { it.y = player.location.y - 1.0 } else g.landing)
        setInputs(f, s, false)
        // Double jump only if a plain jump won't make it: from the top of the arc on, check
        // whether our current speed still carries us to the landing before we fall past it.
        // Kits with more than one air jump (a chicken's flaps, a spider that touched a wall)
        // get to use the next one the same way.
        if (age >= 3 && !nms.onGround() && nms.deltaMovement.y < 0.05 && canDoubleJump() && tick - lastDoubleJumpTick > 8) {
            val here = player.location
            val remaining = hypot(g.landing.x - here.x, g.landing.z - here.z)
            val v = nms.deltaMovement
            val fall = ticksUntilBelow(g.landing.y, v.y, here.y)
            val reach = coastDistance(hypot(v.x, v.z), fall)
            // On a long way down, steering alone still makes up a block or so: no need to
            // spend the double jump on the last half block of a seven-block drop.
            val steer = if (fall >= 12) minOf(1.5, 0.006 * fall * fall) else 0.0
            if (reach + steer < remaining - 0.3 || here.y < g.landing.y - 0.3) {
                g.doubleJumped = true
                aimedDoubleJump(g.landing, "gap jump")
            }
        } else if (age >= 3 && !nms.onGround() && nms.deltaMovement.y < -0.05 && !canDoubleJump() && tick - lastDoubleJumpTick > 6 &&
            player.location.y < g.landing.y - 0.3 && webLaunch(g.landing)) {
            dbg("leap spent short of the ledge: spin web up")
        }
        // Clearly below the landing and dropping, with the double jump spent: hand over to
        // recovery. (Checking this in the same tick as the jump used to cancel every jump to a
        // high ledge the instant it began, while the bot was still on its way up.)
        if (g.doubleJumped && nms.deltaMovement.y < -0.1 && tick - lastDoubleJumpTick > 5 &&
            player.location.y < g.landing.y - 2.5 && isOverVoid(map)) {
            dbg("gap jump falling short, recovering")
            gapJump = null
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ targeting

    private fun chooseTarget(game: BrawlMinigame) {
        val me = player.location
        val alive =
            game.allPlayers().mapNotNull { it.player }.filter {
                it != player &&
                    it.world == player.world &&
                    it.gameMode == GameMode.SURVIVAL && !dev.betrix.superSmashMobsBrawl.items.ItemService.hidden(it) &&
                    !game.arePlayersOnSameTeam(player, it) &&
                    !game.respawnManager.isRespawning(it)
            }
        opponents = alive
        // Prefer someone the bot can get to; with nobody else left, an unreachable opponent is
        // still the one to deal with.
        val candidates = alive.filter { !besieging(it) }.ifEmpty { alive }
        // Only the smarter selector reads edges and attackers, so only it pays for them.
        val attackers = if (d.smartTargeting) CombatLogService.recentAttackers(player, 4000) else emptySet()
        // Whoever this bot told the lobby it is going after ("lets team on him").
        val wanted = dev.betrix.superSmashMobsBrawl.services.BotConversationService.grudge(game, player.uniqueId)
        val chosen = selector.choose(
            candidates.map {
                TargetCandidate(it.uniqueId, it.location.distance(me), it.health,
                    d.smartTargeting && !hasGroundNear(it.location, 2), it.uniqueId in attackers, it.uniqueId == wanted)
            },
            target?.uniqueId,
        )
        val next = candidates.find { it.uniqueId == chosen }
        if(next != target) {
            route.clear();routeSearch=null;routeGoal=null;nextRouteTick=tick
        }
        setTarget(next)
    }

    // ------------------------------------------------------------------ fighting

    private fun fight(t: Player, decide: Boolean, map: GameMapDef) {
        val aimPoint = aimPointOf(t)
        val dist = player.eyeLocation.distance(aimPoint)
        val reach = KitService.getKitForPlayer(player)?.getMeleeReach() ?: 3.0
        val sees = player.hasLineOfSight(t)
        // Nothing routes to an opponent we just found unreachable: hold the closest ground instead.
        val sieging = besieging(t)
        if (sieging && d.smartTargeting) {
            if (tick >= withdrawUntil && healthLosses.isNotEmpty() && !canHarmAt(dist)) {
                withdrawUntil = tick + 160
                dbg("cannot reach or hit ${t.name}; backing out of their range")
            }
            if (tick < withdrawUntil) return withdraw(t, map)
        }
        val step = if(!sieging && (dist>reach || !sees)) routeToward(chaseGoal(t,dist),map) else null

        if (decide) {
            val bow = KitService.getKitForPlayer(player)?.id.let { it == "skeleton" || it == "zombie" }
            engageRange = maxOf(reach + 4.0, if (bow) 25.0 else 0.0,
                kitAbilityIds().filter { AbilityHints.of(it).attack && isReady(it) }.maxOfOrNull { AbilityHints.of(it).maxRange } ?: 0.0)
        }
        if (lineUpShot(aimPoint, dist, sees)) {
            // Facing is taken care of: the bot is turning onto its shot.
        } else if (step == null || (sees && dist <= engageRange) || holdUntilTick >= 0 || bowReleaseTick > 0L) {
            // Watch the opponent only when they are close enough to hit or shoot. A fighter that
            // stares at a distant target walks sideways along its route and never sprints.
            // Aim with human-like error and limited turn speed, leading whatever is being charged.
            val (yaw, pitch) = lookAngles(player.eyeLocation, if (holdUntilTick >= 0) lead(aimPoint, dist, holdSpeed) else aimPoint)
            turnTowards(yaw + yawError, pitch + pitchError, d.turnSpeed)
        } else lookAlongRoute(step)

        // Abilities.
        if (decide && sees && shot == null && holdUntilTick < 0 && bowReleaseTick==0L && comboQueue.isEmpty() && rng.nextDouble() < d.abilityChance) {
            planAbility(t, dist)
        }

        if (sieging && trySiegeJump(t, dist, map)) return
        if (!sieging) prowlGoal = null
        // With no way to them, keep moving around them looking for one instead of standing and staring.
        if (!(sieging && dist > reach + 0.6 && prowl(t, dist, sees, map))) {
            var forward=0f
            var strafe=0f
            var jump=false
            if(step!=null) {
                val (f,s,j)=stepInputs(step) ?: return
                forward=f;strafe=s;jump=j
            } else if(dist>reach-.4 && sees) {
                val input=inputToward(t.location);forward=input.first;strafe=input.second
            } else if(d.strafes && dist<reach+1) {
                if(tick>=nextStrafeFlip){strafeDir=if(rng.nextBoolean())1f else -1f;nextStrafeFlip=tick+20}
                strafe=strafeDir*.65f
            }
            val (appliedForward,appliedStrafe)=applyMovement(step,forward,strafe,jump,map)
            checkStuck(dist>reach+1,appliedForward,appliedStrafe)
        }
        if(decide && holdUntilTick<0 && bowReleaseTick==0L && tick>=nextBowTick && dist in 5.0..25.0 && player.hasLineOfSight(t)) {
            val kit=KitService.getKitForPlayer(player)?.id
            if(kit=="skeleton" || kit=="zombie") {
                val bow=(0..8).firstOrNull { player.inventory.getItem(it)?.type==org.bukkit.Material.BOW }
                if(bow!=null) {
                    player.inventory.heldItemSlot=bow;nms.startUsingItem(InteractionHand.MAIN_HAND)
                    bowReleaseTick=tick+if(d.smartTargeting)24 else 16
                }
            }
        }

        // Melee.
        if (holdUntilTick < 0 && bowReleaseTick==0L && player.hasLineOfSight(t) && dist <= reach + 0.6 && tick >= nextClickTick) {
            meleeClick()
            val interval = 20.0 / d.clicksPerSecond
            nextClickTick = tick + (interval * (0.7 + rng.nextDouble() * 0.6)).toLong().coerceAtLeast(1)
        }
    }

    private var engageRange = 7.0
    private var nextSiegeJumpTick = 0L
    private var prowlGoal: Location? = null
    private var prowlUntil = 0L
    private var prowlRestUntil = 0L

    /**
     * The opponent is somewhere no route leads (a perch, a ledge, a roof). A player in that spot
     * does not stand and stare: they circle, try the other side, go up a level, and take the jump
     * when one opens up. So the bot walks to one piece of ground near the opponent after another,
     * pausing a moment at each to look (and for [trySiegeJump] and its ranged moves to get their
     * chance from the new angle). Returns true while it is on the move.
     */
    private fun prowl(t: Player, dist: Double, sees: Boolean, map: GameMapDef): Boolean {
        // Mid-shot the bot keeps its aim rather than walking it off.
        if (holdUntilTick >= 0 || bowReleaseTick > 0L || shot != null || gapJump != null) return false
        val here = player.location
        var goal = prowlGoal
        if (goal != null && (tick >= prowlUntil || goal.world != here.world ||
                (hypot(goal.x - here.x, goal.z - here.z) < 1.2 && abs(goal.y - here.y) < 1.5))) {
            // Arrived (or it is taking too long): have a look from here before moving on.
            prowlGoal = null; goal = null
            prowlRestUntil = tick + rng.nextInt(8, 26)
            route.clear(); routeSearch = null; nextRouteTick = tick
        }
        if (goal == null) {
            if (tick < prowlRestUntil) return false
            val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
            val at = t.location
            for (attempt in 1..8) {
                val angle = rng.nextDouble() * Math.PI * 2
                val radius = 2.5 + rng.nextDouble() * 8.0
                // Ground around their height first, else whatever is underneath.
                val probe = Location(at.world, at.x + cos(angle) * radius, at.y + rng.nextInt(-1, 4), at.z + sin(angle) * radius)
                val node = nav.nearest(probe, 14.0, 3.0) ?: continue
                val spot = Location(at.world, node.x + .5, node.y, node.z + .5)
                // Somewhere new, not the block the bot is already on.
                if (hypot(spot.x - here.x, spot.z - here.z) < 3.0 && abs(spot.y - here.y) < 2.0) continue
                goal = spot
                break
            }
            val chosen = goal ?: run { prowlRestUntil = tick + 20; return false }
            prowlGoal = chosen
            prowlUntil = tick + 140
            route.clear(); routeSearch = null; nextRouteTick = tick
            dbg("no way to ${t.name}; trying from ${chosen.blockX},${chosen.blockY},${chosen.blockZ}")
        }
        // Keep facing them while they are in range to be shot at; otherwise run where it is going.
        travel(prowlGoal!!, map, face = !(sees && dist <= engageRange)) {
            prowlGoal = null
            prowlRestUntil = tick + 5
        }
        return true
    }

    /** Faces the way the route goes, a node ahead when the next one is underfoot, so the bot sprints. */
    private fun lookAlongRoute(step: RouteStep) {
        val here = player.location
        val near = hypot(step.node.x + .5 - here.x, step.node.z + .5 - here.z) < 1.2
        val node = (if (near) route.getOrNull(1)?.node else null) ?: step.node
        val (yaw, pitch) = lookAngles(player.eyeLocation, Location(here.world, node.x + .5, node.y + 1.6, node.z + .5))
        turnTowards(yaw, pitch.coerceIn(-25f, 25f), maxOf(d.turnSpeed, 25f))
    }

    /**
     * No route leads to this opponent, but a jump might: players get onto ledges and odd blocks the
     * route planner does not model. Tried now and then while they are in sight and the kit's real
     * jump can carry the bot there. Returns true if a jump was started.
     */
    private fun trySiegeJump(t: Player, dist: Double, map: GameMapDef): Boolean {
        if (tick < nextSiegeJumpTick || !nms.onGround() || !canDoubleJump() || gapJump != null) return false
        nextSiegeJumpTick = tick + 30
        val node = ArenaNav.of(player.world, map.voidLevel.toInt(), true).nearest(t.location, 3.0) ?: return false
        val landing = Location(player.world, node.x + .5, node.y, node.z + .5)
        val here = player.location
        val across = hypot(landing.x - here.x, landing.z - here.z)
        if (across > 8.5 + (airJumpsLeft() - 1).coerceAtLeast(0) * 3.0 || across < 1.0 || !jumpReaches(landing) || !clearHop(landing, map)) return false
        // Never over open void: if it falls short there must be something to land on.
        val middle = here.clone().add((landing.x - here.x) / 2, 0.0, (landing.z - here.z) / 2)
        if (across > 4.5 && !hasSolidBelow(middle, 12)) return false
        nextSiegeJumpTick = tick + 70
        dbg("no route to ${t.name}; jumping straight at them")
        startGapJump(Gap(dist, landing))
        return true
    }

    /** Is there room to go straight up from here and then across onto [landing]? Sight is no guide under a ledge. */
    private fun clearHop(landing: Location, map: GameMapDef): Boolean {
        val terrain = ArenaTerrain(player.world, map.voidLevel.toInt(), true)
        val here = player.location
        val top = maxOf(here.y, landing.y) + 0.4
        var y = here.y + 0.2
        while (y <= top) {
            if (!terrain.clear(here.x, y, here.z)) return false
            y += 0.25
        }
        val steps = (hypot(landing.x - here.x, landing.z - here.z) * 3).toInt().coerceAtLeast(2)
        for (i in 1..steps) {
            val part = i.toDouble() / steps
            if (!terrain.clear(here.x + (landing.x - here.x) * part, top, here.z + (landing.z - here.z) * part)) return false
        }
        return true
    }

    /** Can a jump from here, followed by this kit's air jump at the top, land on [p]? */
    private fun jumpReaches(p: Location): Boolean {
        val here = player.location
        val from = here.y + 1.2
        val r = hypot(p.x - here.x, p.z - here.z)
        val jumps = airJumpsLeft()
        if (jumps > 1) {
            // Several air jumps (a chicken's flaps): some go on height, the rest on distance.
            val forHeight = if (p.y + 0.3 <= from) 0 else airJumpsFor(p.y + 0.3 - here.y)
            return jumps - forHeight >= 1 && r <= 1.5 + (jumps - forHeight) * 4.5
        }
        var pitch = 20.0
        while (pitch >= -88.0) {
            val (horizontal, vertical) = jumpLaunch(pitch)
            // Flatter jumps go further and steeper ones higher, so one that clears the height and
            // covers the distance means some jump in between lands on the spot.
            if (peakHeight(vertical, from) >= p.y + 0.3 &&
                coastDistance(horizontal, ticksUntilBelow(p.y, vertical, from)) + 0.8 >= r - 0.5) return true
            pitch -= 4.0
        }
        return false
    }

    private fun aimPointOf(t: Player): Location {
        val box = t.disguise?.boundingBox ?: t.boundingBox
        return Location(t.world, box.centerX, box.minY + box.height * 0.6, box.centerZ)
    }

    private fun meleeClick() {
        selectMeleeSlot()
        player.swingMainHand()
        PlayerInteractEvent(
                player,
                Action.LEFT_CLICK_AIR,
                player.inventory.itemInMainHand,
                null,
                BlockFace.SELF,
                EquipmentSlot.HAND,
            )
            .callEvent()
    }

    // ------------------------------------------------------------------ abilities

    private fun kitAbilityIds(): List<String> {
        val kit = KitService.getKitForPlayer(player) ?: return emptyList()
        return dataService.getKit(kit.id)?.abilities?.map { it.id } ?: emptyList()
    }

    private fun isReady(id: String): Boolean =
        KitService.getKitForPlayer(player)?.getAbility(id)?.readyForBot()
            ?: (System.currentTimeMillis() >= (abilityReadyAtMs[id] ?: 0L))

    /**
     * Picks an ability worth using on [t] right now, if there is one, and starts lining it up.
     * Which abilities are in range and ready is a fact; whether this is a good moment for any of
     * them is the planner's judgement, and it gets better with difficulty.
     */
    private fun planAbility(t: Player, dist: Double) {
        val options =
            kitAbilityIds().filter { id ->
                val hint = AbilityHints.of(id)
                hint.attack && isReady(id) && dist in hint.minRange..hint.maxRange &&
                    (!hint.recovery || (hasGroundNear(t.location,4) && !isOverVoid(lastMap ?: return@filter false))) &&
                    !(id=="phantom_flight" && player.isFlying) &&
                    !(id=="slime_rocket" && player.exp<.2f)
            }
        if (options.isEmpty() || !player.hasLineOfSight(t)) return
        val choice = AbilityPlanner.choose(
            options.map { AbilityOption(it, AbilityHints.of(it), dataService.getAbility(it)?.cooldown ?: 3.0) },
            fightSituation(t, dist), d, kitBehavior(),
        ) ?: return
        shot = Shot(choice.id, choice.hint.speed, false, tick) { useAbility(choice.id, choice.hint) }
    }

    private fun fightSituation(t: Player, dist: Double): FightSituation {
        val here = player.location
        val toward = t.location.toVector().subtract(here.toVector()).setY(0)
        val unit = if (toward.lengthSquared() > 1e-6) toward.normalize() else Vector()
        /** Blocks of ground along the line to the opponent (or away from them) before a drop. */
        fun ground(direction: Int, limit: Int): Double {
            for (i in 1..limit) {
                if (!hasSolidBelow(here.clone().add(unit.x * i * direction, 0.0, unit.z * i * direction), 6)) return i - 1.0
            }
            return 32.0
        }
        return FightSituation(
            distance = dist,
            targetHealth = t.health / maxHealth(t),
            targetSpeed = hypot(trackedVelocity.x, trackedVelocity.z),
            targetAirborne = abs(trackedVelocity.y) > 0.08,
            targetNearEdge = !hasGroundNear(t.location, 2),
            groundAhead = ground(1, 14),
            groundBehind = ground(-1, 4),
            exposed = !nms.onGround() || doubleArrayOf(2.5, -2.5).any { o ->
                !hasSolidBelow(here.clone().add(o, 0.0, 0.0), 6) || !hasSolidBelow(here.clone().add(0.0, 0.0, o), 6)
            },
            airJump = canDoubleJump(),
        )
    }

    /**
     * Turns the bot onto the shot it has decided on and fires once it is facing it, so an ability
     * or item is never let off in whatever direction the bot happened to be looking. Returns true
     * while a shot is being lined up (it owns where the bot looks).
     */
    private fun lineUpShot(aimPoint: Location, dist: Double, sees: Boolean): Boolean {
        val lining = shot ?: return false
        val age = tick - lining.born
        if (!sees) {
            // Lost sight of them: nothing to aim at. Give it a moment, then drop it.
            if (age > 4) shot = null
            return true
        }
        val raise = if (lining.lob) ((dist - 4.5) * 4).coerceIn(0.0, 40.0).toFloat() else 0f
        val facing = aimAt(lead(aimPoint, dist, lining.speed), d.turnSpeed * 2, raise)
        if (facing) {
            shot = null
            lining.fire()
        } else if (age >= 12) {
            // Could not get onto it. The careless let it go anyway; the careful wait for a better one.
            shot = null
            if (!d.smartTargeting) lining.fire()
        }
        return true
    }

    /** Where to aim so a projectile of this [speed] meets a moving opponent. Only the smarter bots lead. */
    private fun lead(point: Location, dist: Double, speed: Double): Location {
        if (speed <= 0 || !d.smartTargeting) return point
        val ticks = (dist / speed).coerceAtMost(20.0)
        // Jumps even out; mostly it is the sideways run that carries them out of the way.
        return point.clone().add(trackedVelocity.x * ticks, trackedVelocity.y * ticks * 0.3, trackedVelocity.z * ticks)
    }

    /** Turns toward [point] (raised by [raise] degrees for lobbed throws). True once the bot is facing it. */
    private fun aimAt(point: Location, maxStep: Float, raise: Float = 0f): Boolean {
        val (yaw, pitch) = lookAngles(player.eyeLocation, point)
        val wantYaw = yaw + yawError
        val wantPitch = (pitch + pitchError - raise).coerceIn(-90f, 90f)
        turnTowards(wantYaw, wantPitch, maxStep)
        val tolerance = maxOf(3.0, d.aimErrorDegrees).toFloat()
        return abs(wrapDegrees(wantYaw - nms.yRot)) <= tolerance && abs(wantPitch - nms.xRot) <= tolerance + 4f
    }

    // ------------------------------------------------------------------ items

    private fun itemSituation(t: Player?, map: GameMapDef): ItemSituation {
        val here = player.location
        val away = t?.let { here.toVector().subtract(it.location.toVector()).setY(0) }?.takeIf { it.lengthSquared() > 1e-6 }?.normalize()
        return ItemSituation(
            health = player.health / maxHealth(player),
            energy = player.exp.toDouble(),
            lives = minigameService.getMinigameForPlayer(player)?.teamManager?.findTeamOf(player)?.metadata?.get("stocks") as? Int ?: 4,
            fleeing = states.state == CombatState.FLEE,
            // Coming down over a gap on the way to a planned landing is not a fall: a jetpack
            // or cloud spent there is wasted. It is a fall once no jump is in progress.
            falling = gapJump == null && isOverVoid(map) && nms.deltaMovement.y < 0,
            airJump = canDoubleJump(),
            targetDistance = t?.location?.distance(here),
            targetVisible = t != null && player.hasLineOfSight(t),
            targetHealth = t?.let { it.health / maxHealth(it) } ?: 1.0,
            targetSpeed = hypot(trackedVelocity.x, trackedVelocity.z),
            targetNearEdge = t != null && !hasGroundNear(t.location, 2),
            targetUnreachable = t != null && besieging(t),
            voidBehind = away != null && (2..8 step 2).any { !hasSolidBelow(here.clone().add(away.x * it, 0.0, away.z * it), 12) },
            nearestEnemy = opponents.filter { it.world == here.world }.minOfOrNull { it.location.distance(here) } ?: Double.MAX_VALUE,
            heldSeconds = ((tick - carriedSince) / 20).toInt(),
        )
    }

    /**
     * Looks at the item the bot is carrying and uses it if this is the moment for it. Runs in every
     * state, because the moment for a heal is while fleeing and for a cloud is while falling.
     */
    private fun considerItem(t: Player?, map: GameMapDef) {
        if (tick < nextItemTick || shot != null || holdUntilTick >= 0 || bowReleaseTick > 0L) return
        nextItemTick = tick + 5
        val slot = (0..8).firstOrNull { ItemService.kind(player.inventory.getItem(it)) != null }
        val kind = slot?.let { ItemService.kind(player.inventory.getItem(it)) }
        if (slot == null || kind == null) { carried = null; return }
        if (kind != carried) { carried = kind; carriedSince = tick; carriedFails = 0 }
        if (kind.tool || kind == dev.betrix.superSmashMobsBrawl.items.ArenaItem.DIAMOND_HELMET) return
        when (val aim = ItemTactics.plan(kind, itemSituation(t, map), d) ?: return) {
            ItemAim.NONE -> spendItem(slot, kind, null)
            ItemAim.ESCAPE -> {
                // Nobody fumbles the aim when it is their life: point straight at solid ground.
                val safe = recoveryGoal ?: lastSafe ?: return
                val (yaw, pitch) = lookAngles(player.eyeLocation, safe.clone().add(0.0, 1.0, 0.0))
                nms.setYRot(yaw); nms.setYHeadRot(yaw); nms.setXRot(pitch)
                spendItem(slot, kind, null)
            }
            ItemAim.TARGET, ItemAim.LOBBED, ItemAim.CONTACT -> if (t != null) {
                val speed = when (kind) {
                    dev.betrix.superSmashMobsBrawl.items.ArenaItem.FIRE_FLOWER, dev.betrix.superSmashMobsBrawl.items.ArenaItem.MISSILE_BATTERY -> 1.3
                    dev.betrix.superSmashMobsBrawl.items.ArenaItem.ROCKET_LAUNCHER -> 1.7
                    dev.betrix.superSmashMobsBrawl.items.ArenaItem.KNOCKBACK_ROD -> if (aim == ItemAim.TARGET) 1.6 else 0.0
                    dev.betrix.superSmashMobsBrawl.items.ArenaItem.RAIN_STAFF -> 1.2
                    else -> 0.0
                }
                shot = Shot(kind.label, speed, aim == ItemAim.LOBBED, tick) { spendItem(slot, kind, t.takeIf { aim == ItemAim.CONTACT }) }
            }
        }
    }

    /** Uses the item in [slot]. [contact] is the opponent for items applied in melee range. */
    private fun spendItem(slot: Int, kind: dev.betrix.superSmashMobsBrawl.items.ArenaItem, contact: Player?) {
        if (ItemService.kind(player.inventory.getItem(slot)) != kind) return
        val previous = player.inventory.heldItemSlot
        player.inventory.heldItemSlot = slot
        val used = when (kind) {
            dev.betrix.superSmashMobsBrawl.items.ArenaItem.PLAYER_GRAPPLE -> contact != null && ItemService.botGrapple(player, contact)
            dev.betrix.superSmashMobsBrawl.items.ArenaItem.CRAFTY_BOMB -> contact != null && ItemService.botPlaceCrafty(player, contact.location)
            dev.betrix.superSmashMobsBrawl.items.ArenaItem.SMASHER -> ItemService.use(player, true, null)
            dev.betrix.superSmashMobsBrawl.items.ArenaItem.KNOCKBACK_ROD -> ItemService.use(player, contact != null, contact)
            else -> ItemService.use(player, false, null)
        }
        player.inventory.heldItemSlot = previous
        if (used) {
            carriedFails = 0
            dbg("used item ${kind.label}")
            if (kind == dev.betrix.superSmashMobsBrawl.items.ArenaItem.EXTRA_LIFE) channelUntil = tick + 104
        } else if (ItemTactics.shouldDiscard(++carriedFails)) {
            // An item this bot cannot spend would block every later pickup: let it go.
            dbg("discarding unusable item ${kind.label}")
            player.inventory.setItem(slot, null)
            carried = null
        }
    }

    private fun tryRecoveryAbility(): Boolean {
        val id = kitAbilityIds().firstOrNull { AbilityHints.of(it).recovery && isReady(it) } ?: return false
        return useAbility(id, AbilityHints.of(id))
    }

    private fun useAbility(id: String, hint: AbilityHint, followup: Boolean = true): Boolean {
        if(followup && !isReady(id))return false
        val slot = findAbilitySlot(id) ?: return false
        player.inventory.heldItemSlot = slot
        if (hint.aimUp != 0f) nms.setXRot((nms.xRot - hint.aimUp).coerceIn(-90f, 90f))

        val usage = dataService.getAbility(id)?.usage ?: AbilityUsage.RIGHT_CLICK
        val holdFor = if (hint.holdTicks.last > 0) hint.holdTicks.random() else 0
        if (holdFor > 0) {
            // Charge abilities fire when the player stops "blocking" with the item.
            nms.startUsingItem(InteractionHand.MAIN_HAND)
            holdUntilTick = tick + holdFor
            holdSpeed = hint.speed
        }

        val action = if (usage == AbilityUsage.LEFT_CLICK) Action.LEFT_CLICK_AIR else Action.RIGHT_CLICK_AIR
        if (usage == AbilityUsage.LEFT_CLICK) player.swingMainHand()
        PlayerInteractEvent(player, action, player.inventory.itemInMainHand, null, BlockFace.SELF, EquipmentSlot.HAND)
            .callEvent()

        if (id == "villager_arts") {
            (KitService.getKitForPlayer(player)?.getAbility(id) as?
                dev.betrix.superSmashMobsBrawl.abilities.VillagerArtsAbility)?.applyPreparedArt()
        }

        if(followup) AbilityPlanner.comboFor(id,kitBehavior(),d)?.let { combo ->
            comboQueue.clear()
            // A charged opener only leaves when its hold ends; the follow-up counts from there.
            var due=tick+holdFor
            combo.steps.forEach { due+=it.delayTicks;comboQueue.addLast(QueuedStep(it,due,combo.mechanical)) }
        }
        dbg("ability $id" + if (followup) "" else " (follow-up)")
        val cooldownMs = ((dataService.getAbility(id)?.cooldown ?: 3.0) * 1000).toLong()
        abilityReadyAtMs[id] = System.currentTimeMillis() + cooldownMs + 250
        return true
    }

    private fun abilityIdIn(slot: Int): String? {
        val item = player.inventory.getItem(slot) ?: return null
        if (!item.hasItemMeta()) return null
        return item.itemMeta.persistentDataContainer.get(abilityKey, PersistentDataType.STRING)
    }

    private fun findAbilitySlot(id: String): Int? = (0..8).firstOrNull { abilityIdIn(it) == id }

    /** Hold something that won't trigger a left-click ability when meleeing. */
    private fun selectMeleeSlot() {
        fun safe(slot: Int): Boolean {
            val id = abilityIdIn(slot) ?: return true
            return dataService.getAbility(id)?.usage != AbilityUsage.LEFT_CLICK
        }
        if (safe(player.inventory.heldItemSlot)) return
        (0..8).firstOrNull(::safe)?.let { player.inventory.heldItemSlot = it }
    }

    // ------------------------------------------------------------------ recovery

    private fun depthToVoid(map: GameMapDef): Int =
        (player.location.y - map.voidLevel).toInt().coerceIn(1, 80)

    /**
     * In the air with nothing but void underneath. Checked under the whole body, not just its
     * centre: a fighter on the lip of a block has its centre over thin air, and a bot that took a
     * jump from there for a fall used to fire its recovery jump, land on the same lip and do it
     * again, for ever.
     */
    private fun isOverVoid(map: GameMapDef): Boolean {
        if (nms.onGround()) return false
        val at = player.location
        val depth = depthToVoid(map)
        if (hasSolidBelow(at, depth)) return false
        for (dx in doubleArrayOf(-0.29, 0.29)) for (dz in doubleArrayOf(-0.29, 0.29)) {
            if (hasSolidBelow(at.clone().add(dx, 0.0, dz), depth)) return false
        }
        return true
    }

    private var recovering = false
    private var recoveryGoal: Location? = null
    private var recoveryScanTick = -100L

    private fun recover(map: GameMapDef, decide: Boolean) {
        val here = player.location
        fun hDist(x: Double, z: Double) = hypot(x - here.x, z - here.z)
        // Head for the closest safe ground: where we last stood, or the nearest spawn point.
        val options =
            listOfNotNull(lastSafe?.takeIf { it.world == here.world }) +
                map.spawnPoints.map { Location(here.world, it.x, it.y, it.z) }
        if(recoveryGoal==null || tick-recoveryScanTick>=10) {
        recoveryScanTick=tick
        val scan=ArenaTerrain(player.world,map.voidLevel.toInt(),canDoubleJump())
        val ledges=ArrayList<Location>()
        for(r in listOf(2,4,6,9,12)) for(i in 0..7) {
            val a=Math.toRadians(i*45.0)
            scan.surface(kotlin.math.floor(here.x+cos(a)*r).toInt(),kotlin.math.floor(here.z+sin(a)*r).toInt(),here.y,6.0,12.0)?.let { ledges.add(scan.location(it)) }
        }
        recoveryGoal = (options+ledges).filter { it.y > here.y - 25 }.minByOrNull { hDist(it.x, it.z) } ?: options.firstOrNull()
        }
        val goal = recoveryGoal ?: return
        if (!recovering) {
            recovering = true
            navStats.recoveries++
            dbg("over the void, recovering toward ${"%.1f %.1f %.1f".format(goal.x, goal.y, goal.z)}")
        }
        // Under the island (blocks overhead)? Get out from under it first, then jump up.
        val ceiling = hasCeiling(here, goal.y + 2)
        val aim = if (ceiling) clearSkyNear(here, goal.y + 2) ?: goal else goal

        // Humans aim their recovery properly: no aim error here.
        val (yaw, _) = lookAngles(player.eyeLocation, aim)
        turnTowards(yaw, 0f, d.turnSpeed * 2f)
        // Steer toward it so we coast onto it rather than over it.
        // (Under a ceiling, hurry out: pretend the spot is just below us so we move fast.)
        val (f, s) = steerTo(if (ceiling) aim.clone().also { it.y = here.y - 2 } else aim)
        setInputs(f, s, false)

        // Double jump once we're falling, if coasting won't get us back (or we're below it).
        // (Hanging in a cobweb is not falling, and a jump spent there is a jump lost.)
        if (nms.deltaMovement.y >= 0 || inWeb()) return
        if (ceiling && hasCeiling(here, goal.y + 2)) return // still under it: a jump would hit it
        val v = nms.deltaMovement
        val remaining = hDist(goal.x, goal.z)
        val reach = coastDistance(hypot(v.x, v.z), ticksUntilBelow(goal.y, v.y, here.y))
        val needed = here.y < goal.y + 0.5 || reach < remaining - 0.5
        if (!needed || !decide || rng.nextDouble() > d.recoverySkill) return
        if (canDoubleJump() && tick - lastDoubleJumpTick > 6) {
            aimedDoubleJump(goal, "recovery")
        } else if (holdUntilTick < 0) {
            if (tryRecoveryAbility() || webLaunch(goal)) dbg("used a movement ability to recover")
        }
    }

    // ------------------------------------------------------------------ jump physics
    // Player physics per tick: y += vy; vy = (vy - 0.08) * 0.98; horizontal speed *= 0.91 in air.

    /** Ticks until, falling from [y0] with vertical speed [vy0], we drop below height [y] (max 60). */
    private fun ticksUntilBelow(y: Double, vy0: Double, y0: Double): Int {
        var py = y0
        var v = vy0
        for (t in 1..60) {
            py += v
            v = (v - 0.08) * 0.98
            if (v < 0 && py <= y) return t
        }
        return 60
    }

    /** Highest point reached starting at [y0] with vertical speed [vy0]. */
    private fun peakHeight(vy0: Double, y0: Double): Double {
        var py = y0
        var v = vy0
        while (v > 0) {
            py += v
            v = (v - 0.08) * 0.98
        }
        return py
    }

    /** Horizontal distance covered in [ticks] ticks starting at speed [v] (air drag only). */
    private fun coastDistance(v: Double, ticks: Int): Double = v * (1 - Math.pow(0.91, ticks.toDouble())) / 0.09

    /** Is there a solid block above us (from head height up to [topY])? */
    private fun hasCeiling(loc: Location, topY: Double): Boolean {
        val world = loc.world ?: return false
        for (y in (loc.blockY + 2)..topY.toInt()) {
            if (world.getBlockAt(loc.blockX, y, loc.blockZ).type.isSolid) return true
        }
        return false
    }

    /** Nearest spot (within 5 blocks) with open sky up to [topY], to get out from under an island. */
    private fun clearSkyNear(loc: Location, topY: Double): Location? {
        for (r in listOf(1.5, 2.5, 3.5, 5.0)) {
            val options =
                (0 until 12).map { i ->
                    val a = Math.toRadians(i * 30.0)
                    loc.clone().add(cos(a) * r, 0.0, sin(a) * r)
                }.filter { !hasCeiling(it, topY) }
            if (options.isNotEmpty()) {
                // Of the open spots, take the one nearest to where we were heading.
                val g = lastSafe ?: return options.first()
                return options.minBy { hypot(it.x - g.x, it.z - g.z) }
            }
        }
        return null
    }

    /**
     * Air control: movement input that brings our horizontal velocity to "the speed that coasts
     * to a stop at [p]". In the air players can only nudge themselves, so this gently steers and
     * brakes instead of overshooting.
     */
    private fun steerTo(p: Location): Pair<Float, Float> {
        val here = player.location
        val dx = p.x - here.x
        val dz = p.z - here.z
        val r = hypot(dx, dz)
        val v = nms.deltaMovement
        // The speed that covers the remaining distance in the time we have before dropping
        // below the target's height (air drag 0.91/tick).
        val ticks = ticksUntilBelow(p.y, v.y, here.y).coerceAtLeast(1)
        val desired = minOf(0.7, r * 0.09 / (1 - Math.pow(0.91, ticks.toDouble())))
        val ux = if (r > 1e-3) dx / r else 0.0
        val uz = if (r > 1e-3) dz / r else 0.0
        val ex = ux * desired - v.x
        val ez = uz * desired - v.z
        if (hypot(ex, ez) < 0.02) return 0f to 0f
        // Convert to forward/strafe in the bot's own frame (same axes as moveDir()).
        val yawRad = Math.toRadians(nms.yRot.toDouble())
        var f = ex * -sin(yawRad) + ez * cos(yawRad)
        var s = ex * cos(yawRad) + ez * sin(yawRad)
        val m = hypot(f, s)
        if (m > 1e-6) {
            f /= m
            s /= m
        }
        return f.toFloat() to s.toFloat()
    }

    /** Horizontal and vertical speed this kit's air jump gives when looking at [pitch] degrees (negative is up). */
    private fun jumpLaunch(pitch: Double): Pair<Double, Double> {
        val rad = Math.toRadians(pitch)
        val v = KitService.getKitForPlayer(player)?.jumpPassive()?.launchVelocity(Vector(0.0, -sin(rad), cos(rad)))
        if (v != null) return hypot(v.x, v.z) to v.y
        val h = cos(rad)
        val norm = sqrt(h * h + 0.81)
        return 0.9 * h / norm to minOf(0.9, 0.81 / norm)
    }

    /**
     * Double jump toward [p] with just enough horizontal speed to coast there. The double jump
     * launches along our look direction, so we pick the pitch for the distance.
     */
    private fun aimedDoubleJump(p: Location, reason: String) {
        val here = player.location
        val r = hypot(p.x - here.x, p.z - here.z)
        // Every kit's jump is different: the standard one is flatter = further and steeper =
        // higher, a spider leaps exactly where it looks, a slime jumps further. Ask the kit, try
        // pitches and keep the one whose simulated arc lands closest to the target (it must clear
        // the target's height; else take the highest jump there is).
        var bestPitch = 0.0
        var bestErr = Double.MAX_VALUE
        var highest = -Double.MAX_VALUE
        var highestPitch = -85.0
        var p2 = 20.0
        while (p2 >= -88.0) {
            val (horizontal, vertical) = jumpLaunch(p2)
            val peak = peakHeight(vertical, here.y)
            // Of jumps that go equally high (every flap of a chicken does), the steepest: on
            // the way up a tower the bot wants to stay beside it, not be thrown against it.
            if (peak >= highest - 0.02) { highest = maxOf(highest, peak); highestPitch = p2 }
            if (peak >= p.y + 0.3) {
                val dist = coastDistance(horizontal, ticksUntilBelow(p.y, vertical, here.y))
                // Slightly prefer overshooting by a bit to falling short.
                var err = if (dist >= r) dist - r else (r - dist) * 1.5
                // The perfect arc is no use if there is a roof in it. One that comes down a
                // little long, with the brakes on, beats one that stops against an overhang.
                if (err < bestErr && !arcClear(p, horizontal, vertical)) err += 5.0
                if (err < bestErr) {
                    bestErr = err
                    bestPitch = p2
                }
            }
            p2 -= 4.0
        }
        val pitch = (if (bestErr == Double.MAX_VALUE) highestPitch else bestPitch).toFloat()
        val (yaw, _) = lookAngles(player.eyeLocation, p)
        nms.setYRot(yaw)
        nms.setYHeadRot(yaw)
        nms.setXRot(pitch)
        doubleJump("$reason, ${"%.1f".format(r)} blocks, pitch ${"%.0f".format(pitch)}")
    }

    /**
     * Flies an air jump from here at this launch speed toward [p] the way the game would, and
     * says whether it gets there without bumping its head. Running into a wall on the way up is
     * nothing (the jump slides up it and carries on over the top: that is how every ledge is
     * taken); coming up underneath an overhang ends the jump.
     */
    private fun arcClear(p: Location, horizontal: Double, vertical: Double): Boolean {
        val map = lastMap ?: return true
        val here = player.location
        val r = hypot(p.x - here.x, p.z - here.z)
        if (r < 0.5) return true
        val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
        val ux = (p.x - here.x) / r
        val uz = (p.z - here.z) / r
        var along = 0.0
        var y = here.y
        var vh = horizontal
        var vy = vertical
        for (t in 1..50) {
            // Up (or down) first: something overhead on the way up is the end of the jump,
            // something underfoot on the way down is a landing.
            if (!nav.clear(here.x + ux * along, y + vy, here.z + uz * along)) return vy <= 0
            y += vy
            // Then across: a wall stops that for now, and steering pushes on once over it.
            if (nav.clear(here.x + ux * (along + vh), y, here.z + uz * (along + vh))) along += vh else vh = 0.0
            vh = vh * 0.91 + 0.02
            vy = (vy - 0.08) * 0.98
            if ((vy < 0 && y <= p.y + 0.3) || along >= r - 0.3) return true
        }
        return true
    }

    private fun startGapJump(gap: Gap) {
        val landing = gap.landing ?: return
        navStats.gapJumps++
        val here = player.location
        // The block the bot is standing on, if it is the one the route says it jumps from.
        val from = routeAt?.takeIf { abs(it.y - here.y) < .6 && hypot(it.x + .5 - here.x, it.z + .5 - here.z) < 1.0 }
        val jump = GapJump(landing, tick, here.clone(), from)
        if (liquidOnTheWay(landing, jump.high)) {
            // Water has got into the way since the route was planned (it flows). Stand still, not
            // on over the edge, leave this jump alone for a while and look at the arena again.
            dbg("water in the way of the jump to ${"%.1f %.1f %.1f".format(landing.x, landing.y, landing.z)}; finding another way")
            val to = RouteNode(landing.blockX, (landing.y * 16).roundToInt(), landing.blockZ)
            if (from != null) blockedEdges[from to to] = tick + 200 else blockedSteps[to] = tick + 200
            lastMap?.let { forgetTerrain(landing.blockX, landing.blockZ, it); forgetTerrain(here.blockX, here.blockZ, it) }
            route.clear(); routeSearch = null; nextRouteTick = tick
            setInputs(0f, 0f, false)
            return
        }
        jump.stepOff = stepOffSpeed(landing)
        gapJump = jump
        dbg((if (jump.stepOff > 0.0) "stepping off the edge" else "gap jump") +
            ": ${"%.1f".format(gap.length)} blocks toward ${"%.1f %.1f %.1f".format(landing.x, landing.y, landing.z)}")
        continueGapJump(lastMap ?: return)
    }

    /**
     * Is there water or lava where the feet go on a jump from here to [landing]? In the arena
     * touching it is death, and it flows: it can be there now where it was not when the route was
     * planned. The path looked at is the one the jump was planned along (straight up and over for
     * a ledge taken that way, out and down for a drop, an arc for the rest), a low arc and a high
     * one, because the real jump runs somewhere between the two.
     */
    private fun liquidOnTheWay(landing: Location, straightUp: Boolean): Boolean {
        val here = player.location
        val world = here.world ?: return false
        fun wet(x: Double, y: Double, z: Double) =
            world.getBlockAt(kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt(), kotlin.math.floor(z).toInt()).isLiquid
        val dx = landing.x - here.x
        val dz = landing.z - here.z
        val steps = ceil(hypot(dx, dz) * 3).toInt().coerceAtLeast(2)
        val drop = here.y - landing.y > 3.0
        if (straightUp || drop) {
            // One end of the jump is a column: up it from the take-off, or down it onto the landing.
            val columnX = if (straightUp) here.x else landing.x
            val columnZ = if (straightUp) here.z else landing.z
            var y = minOf(here.y, landing.y) + 0.1
            while (y <= maxOf(here.y, landing.y) + 1.0) { if (wet(columnX, y, columnZ)) return true; y += 1.0 }
        }
        val level = if (straightUp) landing.y else here.y
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val x = here.x + dx * t
            val z = here.z + dz * t
            // The height of the feet along the way: level out to a drop or over to a ledge, a slope otherwise.
            val base = if (straightUp || drop) level else here.y + (landing.y - here.y) * t
            val lift = kotlin.math.sin(Math.PI * t)
            if (wet(x, base + 0.1 + lift * 0.9, z) || wet(x, base + 0.1 + lift * 1.8, z)) return true
        }
        return false
    }

    /** A landing with open air on most sides: a block to come down on exactly, not one to run across. */
    private fun narrow(at: Location): Boolean {
        var held = 0
        for (side in CARDINALS) if (hasSolidBelow(Location(at.world, at.x + side.first, at.y + 0.3, at.z + side.second), 2)) held++
        return held <= 2
    }

    /** See [GapJump.stepOff]: the pace to walk off the edge at for a landing well below, or zero if it needs a jump. */
    private fun stepOffSpeed(landing: Location): Double {
        val here = player.location
        if (here.y - landing.y < 3.0 || !nms.onGround()) return 0.0
        val r = hypot(landing.x - here.x, landing.z - here.z)
        if (r < 0.3) return 0.0
        val map = lastMap ?: return 0.0
        val ux = (landing.x - here.x) / r
        val uz = (landing.z - here.z) / r
        // The floor has to end within a step in that direction, with nothing standing on its edge.
        val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
        if (!nav.clear(here.x + ux * 0.6, here.y + 0.05, here.z + uz * 0.6) || !nav.clear(here.x + ux * 1.2, here.y + 0.05, here.z + uz * 1.2)) return 0.0
        if (hasSolidBelow(Location(here.world, here.x + ux * 1.25, here.y - 0.1, here.z + uz * 1.25), 2)) return 0.0
        // Leaving the edge (about 0.6 away) at speed v, air drag lets the bot cover v/0.09 at most.
        val fall = ticksUntilBelow(landing.y, 0.0, here.y)
        val speed = (r - 0.6).coerceAtLeast(0.1) * 0.09 / (1 - Math.pow(0.91, fall.toDouble()))
        return if (speed <= 0.28) speed else 0.0
    }

    private var lastMap: GameMapDef? = null

    private fun doubleJump(reason: String) {
        if (!canDoubleJump()) return
        lastDoubleJumpTick = tick
        navStats.doubleJumps++
        dbg("double jump ($reason)")
        // Same event the server fires when a real player double-taps space.
        PlayerToggleFlightEvent(player, true).callEvent()
    }

    // ------------------------------------------------------------------ wandering / idle

    private fun wander(map: GameMapDef) {
        if (map.spawnPoints.isEmpty()) return releaseInputs()
        val cx = map.spawnPoints.map { it.x }.average()
        val cz = map.spawnPoints.map { it.z }.average()
        val here = player.location
        if (hypot(cx - here.x, cz - here.z) < 4) return releaseInputs()
        val (yaw, _) = lookAngles(player.eyeLocation, Location(here.world, cx, here.y + 1.6, cz))
        turnTowards(yaw, 0f, d.turnSpeed)
        val forward = if (nms.onGround() && !groundAhead(0.6f, 0f)) 0f else 0.6f
        setInputs(forward, 0f, nms.horizontalCollision && nms.onGround())
    }

    private fun setInputs(forward: Float, strafe: Float, jump: Boolean) {
        nms.forwardInput = forward
        nms.strafeInput = strafe
        nms.jumpInput = jump
        nms.isSprinting = forward > 0.8f && !player.isSneaking && !player.isFlying
    }

    private fun releaseInputs() {
        setInputs(0f, 0f, false)
        if (holdUntilTick >= 0 || bowReleaseTick>0) {
            nms.stopUsingItem()
            holdUntilTick = -1
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    private fun lookAngles(from: Location, to: Location): Pair<Float, Float> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz)))).toFloat()
        return yaw to pitch
    }

    private fun wrapDegrees(a: Float): Float {
        var x = a % 360f
        if (x >= 180f) x -= 360f
        if (x < -180f) x += 360f
        return x
    }

    private fun turnTowards(yaw: Float, pitch: Float, maxStep: Float) {
        val dy = wrapDegrees(yaw - nms.yRot).coerceIn(-maxStep, maxStep)
        val dp = (pitch - nms.xRot).coerceIn(-maxStep, maxStep)
        val newYaw = wrapDegrees(nms.yRot + dy)
        nms.setYRot(newYaw)
        nms.setYHeadRot(newYaw)
        nms.setXRot((nms.xRot + dp).coerceIn(-90f, 90f))
    }

    /** Unit vector (x, z) the bot moves in with this input, or null if not moving. */
    private fun moveDir(forward: Float, strafe: Float): Pair<Double, Double>? {
        if (forward == 0f && strafe == 0f) return null
        val yawRad = Math.toRadians(nms.yRot.toDouble())
        val mx = -sin(yawRad) * forward + cos(yawRad) * strafe
        val mz = cos(yawRad) * forward + sin(yawRad) * strafe
        val len = hypot(mx, mz)
        if (len < 1e-3) return null
        return (mx / len) to (mz / len)
    }

    /** True if the bot would still have ground under it after moving [dist] blocks with this input. */
    private fun groundAhead(forward: Float, strafe: Float, dist: Double = 1.3): Boolean {
        val (mx, mz) = moveDir(forward, strafe) ?: return true
        return hasSolidBelow(player.location.clone().add(mx * dist, 0.0, mz * dist), 4)
    }

    /** In the air: is there any ground (all the way down to the void) where this input takes us? */
    private fun groundAheadToVoid(forward: Float, strafe: Float, map: GameMapDef): Boolean {
        val (mx, mz) = moveDir(forward, strafe) ?: return true
        return hasSolidBelow(player.location.clone().add(mx * 1.5, 0.0, mz * 1.5), depthToVoid(map))
    }

    /** A gap along a straight line: its length and the first ground on the far side. */
    private class Gap(val length: Double, val landing: Location?)

    /**
     * Walks a straight line toward [to] and measures the first gap (no ground within 6 blocks
     * below). Length 0 = solid ground the whole way, MAX_VALUE = the ground never comes back.
     */
    private fun gapToward(to: Location, maxDist: Double = 16.0): Gap {
        val from = player.location
        val dx = to.x - from.x
        val dz = to.z - from.z
        val len = hypot(dx, dz)
        if (len < 1.0) return Gap(0.0, null)
        val ux = dx / len
        val uz = dz / len
        // If the target is lower, ground down at their level counts too (we can just drop down).
        val depth = maxOf(6, (from.y - to.y).toInt() + 3).coerceAtMost(40)
        var gapStart = -1.0
        var s = 0.5
        while (s <= minOf(len, maxDist)) {
            val ground = hasSolidBelow(from.clone().add(ux * s, 0.0, uz * s), depth)
            if (!ground && gapStart < 0) gapStart = s
            if (ground && gapStart >= 0) {
                // Aim a block past the edge so we land on solid ground, not the lip - and make sure
                // it really is a floor we can reach, not the wall of a pit.
                val land = from.clone().add(ux * (s + 1.0), 0.0, uz * (s + 1.0))
                val surface = standableSurfaceY(land, depth) ?: return Gap(Double.MAX_VALUE, null)
                val climb = if (player.allowFlight) 2.5 else 1.0
                if (surface + 1 - from.y > climb) return Gap(Double.MAX_VALUE, null)
                land.y = surface + 1.0
                return Gap(s - gapStart, land)
            }
            s += 0.5
        }
        return Gap(if (gapStart >= 0) Double.MAX_VALUE else 0.0, null)
    }

    /**
     * Height of the floor you could stand on at this x/z: the highest solid block (from a little
     * above us down to [depth] below) with two free blocks over it. Null if it's lava/water or none.
     */
    private fun standableSurfaceY(loc: Location, depth: Int): Int? {
        val world = loc.world ?: return null
        val x = loc.blockX
        val z = loc.blockZ
        for (y in (loc.blockY + 3) downTo (loc.blockY - depth)) {
            if (y < world.minHeight) return null
            val type = world.getBlockAt(x, y, z).type
            if (ArenaTerrain.watery(type)) return null
            if (type.isSolid && !world.getBlockAt(x, y + 1, z).type.isSolid && !world.getBlockAt(x, y + 2, z).type.isSolid) {
                return y
            }
        }
        return null
    }

    private fun hasSolidBelow(loc: Location, depth: Int): Boolean {
        val world = loc.world ?: return false
        val x = loc.blockX
        val z = loc.blockZ
        val top = loc.blockY
        for (y in top downTo (top - depth)) {
            if (y < world.minHeight) return false
            val type = world.getBlockAt(x, y, z).type
            // Lava (or water, with or without weed growing in it) on top of solid ground is not safe ground.
            if (ArenaTerrain.watery(type)) return false
            if (type.isSolid) return true
        }
        return false
    }

    private companion object {
        /** Enough to search a whole arena; the cached graph makes each step cheap. */
        const val ROUTE_LIMIT = 20000
        /** Wall climbing drains a full bar in 200 ticks at 0.4 blocks a tick: this much per block. */
        const val CLIMB_ENERGY_PER_BLOCK = 0.0125
        /** Route steps a dash must have ahead of it, all level, and how long the lane must be. */
        const val DASH_NODES = 12
        const val DASH_LENGTH = 9.5
        /** Ticks into the jump at which the leap is fired: just before the top of it. */
        const val DASH_DELAY = 4L
        val DASH_SHOULDERS = doubleArrayOf(-0.3, 0.3)
        val CARDINALS = listOf(1.0 to 0.0, -1.0 to 0.0, 0.0 to 1.0, 0.0 to -1.0)
    }

    private fun hasGroundNear(loc: Location, depth: Int): Boolean =
        hasSolidBelow(loc, depth) ||
            hasSolidBelow(loc.clone().add(0.7, 0.0, 0.0), depth) ||
            hasSolidBelow(loc.clone().add(-0.7, 0.0, 0.0), depth) ||
            hasSolidBelow(loc.clone().add(0.0, 0.0, 0.7), depth) ||
            hasSolidBelow(loc.clone().add(0.0, 0.0, -0.7), depth)
}

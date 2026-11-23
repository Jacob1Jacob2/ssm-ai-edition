package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.combat.*
import dev.betrix.superSmashMobsBrawl.bots.nms.BotPlayer
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.items.ArenaItem
import dev.betrix.superSmashMobsBrawl.items.ItemEffects
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
     * @param aim where it is pointed, when that is not the middle of the opponent (the floor beside
     *   them, for a Blink or a skull); when it gives null there is no longer anywhere to point it,
     *   and the shot is dropped
     */
    private class Shot(val name: String, val speed: Double, val lob: Boolean, val born: Long, val aim: (() -> Location?)? = null, val fire: () -> Unit)
    private var shot: Shot? = null
    /** Projectile speed of the ability being charged, so the bot keeps leading while it holds. */
    private var holdSpeed = 0.0
    /** Where the ability being held is pointed, when that is not the middle of the opponent. */
    private var holdAim: (() -> Location)? = null
    // A ride under way (a Milk Spiral, a Flame Dash): since when, whether it was taken to get back
    // over ground, and when the bot got off.
    private var rideSince = -1L
    private var rideHome = false
    private var rideOffTick = -100L
    /** A Blink that would have come out over nothing is not lined up again at once. */
    private var blinkRestUntil = 0L
    private var blinkScanTick = -100L
    // Something a kit threw is coming at it ([DodgeTactics]): where it is stepping to and until
    // when, since when it has seen things coming, and when it last did.
    private var dodgeTo: Location? = null
    private var dodgeUntil = 0L
    private var threatSince = -1L
    private var threatSeen = -1000L
    private var dodgeSaidTick = -1000L

    // The item being carried: what it is, since when, and how often using it has failed.
    private var carried: dev.betrix.superSmashMobsBrawl.items.ArenaItem? = null
    private var carriedSince = 0L
    private var carriedFails = 0
    private var nextItemTick = 0L
    private var channelUntil = 0L
    private var wasRiding = false
    private var carRestUntil = 0L
    /** A Rain Staff being held on somebody: see [tickRain]. */
    private class Rain(val slot: Int, val born: Long) {
        /** The tick the first volley went (the button is down from then), or -1 while the eye is still getting onto the spot. */
        var since = -1L
        /** Ticks in a row with no rain to be had on the opponent. */
        var off = 0
        /** The last tick this was looked after: a bot busy with something else (falling, typing) lets go. */
        var served = born
        /** The slot that was in hand before the staff. */
        var back = 0
        /** Volleys the staff had in it when the button went down (debug: how many this hold let go). */
        var charge = 0
        /** Damage this bot had done to anybody when the rain last landed on somebody, and the tick it did. */
        var dealt = 0.0
        var wet = 0L
    }
    private var rain: Rain? = null
    /** What this bot knows is about to go off, or is going on, around it, and the tick each came to its notice. See [tickDangers]. */
    private val noticed = HashMap<dev.betrix.superSmashMobsBrawl.items.ItemArena.Danger, Long>()
    /** A getting-away in progress: from what (and where its middle was when the way out was picked), to where, and how it is going. */
    private class Escape(val from: dev.betrix.superSmashMobsBrawl.items.ItemArena.Danger, val middle: Location) {
        var refuge: Location? = null
        var refugeTick = 0L
        var gap = Double.MAX_VALUE
        val tried = HashSet<Pair<Int, Int>>()
        var said = false
        /** Gone the long way round, by the routes, to a spawn point outside it. */
        var byRoute = false
    }
    private var escape: Escape? = null
    /** No way out of these was found: not tried again until the tick given. */
    private val noWayOut = HashMap<dev.betrix.superSmashMobsBrawl.items.ItemArena.Danger, Long>()
    /** A step to the side round something lying on the ground, and until when it is kept to. */
    private var roundTo: Location? = null
    private var roundUntil = 0L
    private var dangerSaidTick = -1000L
    /** A Monster Egg clone that has come for this bot: see [swatClone]. */
    private var pest: org.bukkit.entity.LivingEntity? = null
    private var pestTick = -1000L
    /** The last sign this bot had of where each opponent was: seen, heard, hit by them, or hit by it. See [SightTactics] and [ShadowTactics]. */
    private class Sign(val tick: Long, val at: Location)
    private val sensed = HashMap<java.util.UUID, Sign>()
    /** When each opponent it knows of came to its notice, this time round ([SightTactics]). */
    private val noticedAt = HashMap<java.util.UUID, Long>()
    /**
     * Since when it has had nobody in mind (-1: it has somebody) and which way it was facing then,
     * when it last saw or heard anybody at all, and when it may look at its compass again.
     */
    private var nobodySince = -1L
    private var nobodyYaw = 0f
    private var lastPerceived = -1000L
    private var compassAt = 0L
    private var unseenSaidTick = -1000L
    private var phantomSaidTick = -1000L
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

    /** A rooted bot fights from where it stands and never takes a step: the stand-in for a camping player in tests. */
    @Volatile var rooted = false
    /** Who this bot is fighting right now, for the bots on its side (two teams fight the same opponent where they can). */
    val targetId: java.util.UUID? get() = target?.uniqueId
    /** Two teams: what a player on this bot's side asked of it and it is still doing ([TeamTalk]); read afresh every time it decides. */
    private var teamOrderNow: TeamTalk.Standing? = null
    /** Until this tick the place it was asked to go to counts as out of reach, and it fights where it is. */
    private var teamUnreachableUntil = 0L
    /** The tick it last set off for somewhere a teammate asked it to be (debug). */
    private var teamTravelTick = -1000L
    /** The asking (by when it runs out) for which it has already said it cannot get there. */
    private var teamCantFor = -1L
    /** How many searches for a way there have come up empty during the asking [teamMissFor]. */
    private var teamMisses = 0
    private var teamMissFor = -1L
    /** Where the player it was asked to come to last stood on something. */
    private var teamLeaderAt: Location? = null

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
        bridge=null;crossing=null;leap=null;explored.clear();exploredSet=null;failedLeaps.clear();reachable=emptySet()
        lastSafe=null;recovering=false;recoveryGoal=null;comboQueue.clear();shot=null
        fleeGoal=null;fleeFailedUntil=0;itemGoal=null
        forgetDeaths()
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

    /** The tick a step of the route last asked for a goat's leap to be charged. */
    private var wantCharge = -100L
    private var chargeNoteTick = -100L

    /** How much of its leap a goat has charged (0 to 1); null for everybody else. */
    private fun chargeInHand(): Float? =
        (KitService.getKitForPlayer(player)?.jumpPassive() as? dev.betrix.superSmashMobsBrawl.passives.GoatedLeapPassive)?.charged()

    /** The kit whose air jump has been held up against what the planner assumes of it. */
    private var jumpChecked: String? = null

    /**
     * The planner flies each kit's jumps with numbers written down in [JumpSim]; the kit's own
     * come from its passive and kits.yml. Change one and not the other, and the routes are
     * promises about a jump the kit does not have: this says so in the log, once for each kit.
     */
    private fun checkJumpKind() {
        val kit = KitService.getKitForPlayer(player) ?: return
        if (jumpChecked == kit.id) return
        jumpChecked = kit.id
        if (kit.jumpPassive() == null) return
        val kind = mobility().jumps
        // (A chicken plans with the standard jump on purpose, and a villager's changes with its art.)
        if (kind == JumpKind.FLAPS || kit.id == "villager") return
        for (pitch in doubleArrayOf(0.0, -40.0, -80.0)) {
            val (across, lift) = jumpLaunch(pitch)
            val (plannedAcross, plannedLift) = kind.launch.speeds(pitch)
            // (A goat's lift goes up with what it has charged; across is the part that stays put.)
            val off = abs(across - plannedAcross) > 0.02 ||
                (if (kit.id == "goat") lift < plannedLift - 0.02 else abs(lift - plannedLift) > 0.02)
            if (off) {
                if (warnedJumps.add(kit.id)) org.bukkit.Bukkit.getLogger().warning("[super-smash-mobs-brawl] [jumps] the air jump of ${kit.id} is not the one its routes are " +
                    "planned with: looking ${pitch.toInt()} it gives ${"%.3f".format(across)} across and ${"%.3f".format(lift)} up, the planner has " +
                    "${"%.3f".format(plannedAcross)} and ${"%.3f".format(plannedLift)} (JumpSim and Mobility.of want updating)")
                return
            }
        }
    }

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

    /** The blocks the last search that found no way could get to, and when that was. */
    private var reachable: Set<RouteNode> = emptySet()
    private var reachableTick = -100000L
    /** Did the search in progress turn away from ground this bot has been killed on? */
    private var searchBarred: () -> Boolean = { false }
    /** Did it turn away from ground within reach of a wall that hurts whoever touches it ([WallTactics])? */
    private var searchWalled: () -> Boolean = { false }
    /** [ArenaJumps.misses] when the search in progress began: if it has gone up since, the search did not see every jump. */
    private var searchMisses = 0L
    /** A route planned without all the jumps of the arena is planned again at this tick, wherever the bot has got to. */
    private var routeRetryTick = 0L
    /** Where the bot stood when the search in progress began. */
    private var searchFrom: Location? = null
    /** The tick it last had the ground under it looked at again, standing where the routes do not go. */
    private var offRoutesLookTick = -100L

    /**
     * [passing]: [to] is where somebody is this moment on their way through the air (knocked off,
     * in the middle of a jump). No floor near it then says nothing about whether there is a way
     * to them ([ReachTactics]): it is asked again in a moment.
     * [standsAt]: where somebody being gone for is this moment, when [to] is where they are
     * reckoned to be going. Where they are going may be nowhere a fighter can stand (run on a
     * second and they would be inside the hillside); where they are standing is. Without it a
     * bot wrote off 46 opponents in ten matches who were standing on plain stone in front of it.
     */
    private fun routeToward(to: Location, map: GameMapDef, passing: Boolean = false, standsAt: Location? = null,
                            unreachable: () -> Unit = { target?.let(::markUnreachable) }): RouteStep? {
        val here = player.location
        // Keep walking a usable path, but not to where the target used to be: replan once the
        // target has moved a good part of the distance still to cover.
        if (routeSearch == null && route.isNotEmpty() && tick >= nextRouteTick &&
            (routeGoal?.distance(to) ?: 0.0) > maxOf(3.0, here.distance(to) * 0.3)) route.clear()
        if (routeSearch == null && routeRetryTick in 1..tick) {
            // The jumps that were missing when this was planned are known by now.
            routeRetryTick = 0
            if (gapJump == null && climb == null) { route.clear(); nextRouteTick = minOf(nextRouteTick, tick) }
        }
        if (route.isEmpty() && tick >= nextRouteTick && routeSearch == null) {
            blockedSteps.entries.removeIf { it.value <= tick }
            blockedEdges.entries.removeIf { it.value <= tick }
            failedClimbs.removeIf { it.second <= tick }
            // The arena's walking graph is shared by every bot of the same kind and kept between searches.
            val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
            // A fighter on the lip of a block has its centre over thin air; route from and to
            // the block it is really standing on.
            val start = nav.nearest(here, 5.0, if (nms.onGround()) 0.6 else 0.05)
            val goal = nav.nearest(to, 12.0) ?: standsAt?.takeIf { it.world == to.world }?.let { nav.nearest(it, 12.0) }
            if(start != null && goal != null) {
                navStats.searches++
                // Ground it was killed on by somebody still sitting there is not ground to walk on.
                // (Standing on some of it already, the bot may move off it, not further in.)
                // (Nor is ground something is about to go off on, or where a mine or a trap is lying: see [tickDangers].)
                val zones = (dangerZones(crossing == null) + itemZones(to)).map { it to minOf(it.r, it.gap(start) - 1.0) }
                // (Nor ground within reach of a wall that hurts whoever touches it: the way goes round the
                // end of it. Standing in one already, the bot is on its way out of it: see [roundWalls].)
                val fences = if (walls.isEmpty() || tick - wallsTick > 1) emptyList() else walls.filter { !wallBars(it, start) }
                var barred = false
                var walled = false
                routeSearch = ArenaRoute(start, goal, { n ->
                    nav.neighbours(n).filter {
                        it.node !in blockedSteps && (blockedEdges.isEmpty() || (n to it.node) !in blockedEdges) &&
                            (!it.climb || failedClimbs.none { (foot, _) -> abs(foot.x-n.x)<=3 && abs(foot.z-n.z)<=3 && abs(foot.y-n.y)<3.0 }) &&
                            abs(it.node.x-start.x)<=72 && abs(it.node.z-start.z)<=72 &&
                            (!memory.anyHops || !memory.hopBarred(n, it.node, tick)) &&
                            (zones.isEmpty() || zones.none { (zone, r) -> zone.bars(it.node, r) }.also { open -> if (!open) barred = true }) &&
                            (fences.isEmpty() || fences.none { w -> wallBars(w, it.node) }.also { open -> if (!open) walled = true })
                    }
                }, ROUTE_LIMIT)
                searchBarred = { barred }
                searchWalled = { walled }
                searchFrom = here.clone()
                searchMisses = ArenaJumps.find(player.world, mobility())?.misses ?: 0L
                routeGoal = to.clone()
                nextRouteTick = tick + if(d.smartTargeting) 30 else 50
            } else {
                nextRouteTick = tick + 10
                // Nowhere to stand anywhere near them (hanging in the air, far out over the void):
                // that is as unreachable as it gets, not a reason to stand and wait for a route.
                if (start != null && goal == null) {
                    if (passing) {
                        if (tick - passingSaidTick > 40) { passingSaidTick = tick; dbg("no floor under ${target?.name} this moment (in the air): keeping after them") }
                    } else {
                        // (For the log: whether that is the arena, or only the picture of it being behind the arena.)
                        noWayWhy = "nowhere to stand within 12 blocks of where they are" + if (!BotManager.debug) "" else
                            if (freshGround(to, 12.0, map)) "; the world has ground there that the picture of it has not"
                            else "; none in the world either, over ${to.clone().add(0.0, -0.1, 0.0).block.type}"
                        unreachable()
                    }
                }
            }
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
                val remaining=search.remainingFrom(here.x,here.y,here.z,result)
                route.addAll(remaining)
                routeSearch=null
                routeReached=search.reached
                // A search that found no way has been everywhere this bot can get to: worth
                // remembering for whatever makes a way of its own (a Snowman's ice bridge).
                if(!search.reached) { reachable=search.visited();reachableTick=tick }
                // Where the next step sets off from: the last block of the way the bot is already
                // past (it moved while the search ran, or stands nearer the second block than
                // the first), and the block the search began at if it is past none.
                val skipped=result.size-remaining.size
                routeAt=if(remaining.isNotEmpty() && skipped in 1..result.size) result[skipped-1].node else search.start
                // Standing on something the routes do not count as ground (carried onto a lamp or
                // the top of a post by an ability, knocked onto a fence): the search began at the
                // nearest block the routes do know, and that is not where the bot is. Its way
                // begins with getting onto that. (The route used to come back empty instead,
                // "there already", every quarter of a second: a Skeleton its roped arrow had put
                // on a perch over its opponent stood there and looked down at them.)
                val began=search.start
                if(remaining.isEmpty() && nms.onGround() && (abs(began.y-here.y)>=1.3 || hypot(began.x+.5-here.x,began.z+.5-here.z)>=1.8) &&
                    searchFrom?.let { it.world==here.world && it.distance(here)<1.0 }==true) {
                    val onto=wayBackOn(began,result)
                    if(onto!=null) {
                        val at=result.indexOfFirst { it.node==onto.node }
                        route.add(onto)
                        route.addAll(if(at>=0) result.drop(at+1) else result)
                        routeAt=null
                        dbg("standing where the routes do not go: first onto ${onto.node.x},${"%.1f".format(onto.node.y)},${onto.node.z}${standingOn(here, map)}")
                    } else if(tick-offRoutesLookTick>40) {
                        // No jump from here onto any of it is seen to land. It may be standing on
                        // something put there since the arena was last looked at (a wool mine, an
                        // ice bridge): look at the ground here again, and the routes will know it.
                        offRoutesLookTick=tick
                        forgetTerrain(here.blockX,here.blockZ,map)
                        dbg("standing where the routes do not go, with no jump back onto them: looking at the ground here again${standingOn(here, map)}")
                    }
                }
                nextRouteTick=tick+5
                // No way through, and the bot already stands where the best attempt ends.
                val atEnd=result.isEmpty() || result.last().node.let { hypot(it.x+.5-here.x,it.z+.5-here.z)<1.5 && abs(it.y-here.y)<1.0 }
                val jumps=ArenaJumps.find(player.world, mobility())
                if(!search.reached && jumps!=null && (jumps.waiting>0 || jumps.misses!=searchMisses)) {
                    // Some of the jumps on the way were still being worked out (the first match
                    // on this map in this shape, or a ledge nobody has stood on before). "No way"
                    // is not known yet: set off on what there is and plan again in a moment.
                    routeRetryTick=tick+6
                    dbg("route so far; some of the jumps round here are still being worked out (${jumps.waiting} asked for, ${jumps.misses-searchMisses} met in this search)")
                } else if(!search.reached && searchBarred()) {
                    // The way there goes over ground it has been killed on. Walking as far as
                    // the edge of that ground is walking back into range: stay where it is.
                    route.clear()
                    dbg("no way there that keeps off the ground it was killed on")
                    unreachable()
                } else if(!search.reached && searchWalled()) {
                    // Only a wall that will be down in a few seconds is in the way: as near as the
                    // way goes, and wait. That is no reason to give somebody up as out of reach.
                    nextRouteTick=tick+20
                    dbg("no way there that keeps out of the wall: as near as the way goes, until it comes down")
                } else if(!search.reached && atEnd) {
                    noWayWhy = "the search from ${search.start.x},${"%.1f".format(search.start.y)},${search.start.z} got no nearer in ${search.expanded} steps"
                    unreachable()
                }
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
                if (BotManager.debug) dbg("the next step of its way is not there to stand on any more: ${n.x},${"%.1f".format(n.y)},${n.z} is " +
                    "${player.world.getBlockAt(n.x, kotlin.math.floor(n.y - 0.1).toInt(), n.z).type} under ${player.world.getBlockAt(n.x, kotlin.math.floor(n.y + 0.1).toInt(), n.z).type}")
                forgetTerrain(n.x,n.z,map)
                route.clear();routeSearch=null;nextRouteTick=tick
            }
        }
        return route.firstOrNull()
    }

    private var nextJumpWarning = 0L

    /** Spends a little of the tick's route budget working out the arena's walking graph ahead of need. */
    private fun warmNav(world: org.bukkit.World, map: GameMapDef) {
        // The long jumps of this arena: read from disk if it has been played in this shape
        // before, otherwise worked out a few milliseconds a tick until they are all known.
        // (Whatever goes wrong in there must not stop a bot from fighting: it only costs it the long jumps.)
        try {
            checkJumpKind()
            // (A bot that has not been given its kit yet has no jump to work anything out for.)
            val jumps = mobility().takeIf { it.airborne }?.let { ArenaJumps.of(world, map.voidLevel.toInt(), map, it) }
            if (jumps != null && !jumps.complete) {
                val allowance = RouteWorkBudget.jumpSlice(ArenaJumps.unfinished(world))
                if (allowance > 0) {
                    val started = System.nanoTime()
                    jumps.work(allowance, map.spawnPoints.map { Location(world, it.x, it.y, it.z) })
                    RouteWorkBudget.jumpUsed(System.nanoTime() - started)
                }
            }
        } catch (e: Exception) {
            if (tick >= nextJumpWarning) {
                nextJumpWarning = tick + 6000
                org.bukkit.Bukkit.getLogger().warning("[super-smash-mobs-brawl] [jumps] could not work on ${world.name}: ${e.stackTraceToString().take(1500)}")
            }
        }
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
        /** Debug: the air jump as it was flown in the head, a point for each tick, and the tick it was made. */
        var flown: List<JumpSim.Flight>? = null
        var flownTick = 0L
        /** Debug: the furthest the bot has been from that flight, across and up or down. */
        var flownOff = 0.0
        var flownOffUp = 0.0
        /** Debug: the same for the jump off the ground, as far as its air jump. */
        var planned: List<JumpSim.Flight>? = null
        var plannedTick = 0L
        var plannedOff = 0.0
        var plannedOffUp = 0.0
    }

    /**
     * How long a jump that did not come off is left alone. A step or a hop the rules are sure of
     * usually failed for a passing reason (knocked aside, a block in the way that is gone again):
     * ten seconds. A long jump flown in the head is another matter: it was a promise, it was
     * broken, and a bot that goes back to it every ten seconds looks like it cannot learn. Each
     * miss rests it four times as long as the last.
     */
    private val missedLongJumps = HashMap<Pair<RouteNode, RouteNode>, Int>()
    private fun missedJumpRest(from: RouteNode, to: RouteNode): Long {
        if (ArenaJumps.find(player.world, mobility())?.of(from)?.any { it.node == to && it.cost >= 0 } != true) return 200
        val misses = missedLongJumps.merge(from to to, 1, Int::plus) ?: 1
        dbg("long jump from ${from.x},${"%.1f".format(from.y)},${from.z} to ${to.x},${"%.1f".format(to.y)},${to.z} missed ($misses)")
        return 600L shl (2 * (misses - 1)).coerceAtMost(8)
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
            lookTowards(yaw, pitch, d.turnSpeed)
            return setInputs(0f, 0f, false)
        }
        travel(goal, map) { withdrawUntil = 0 }
    }

    // ------------------------------------------------------------------ learning from deaths

    /** A hit this bot took: who from, where it stood, and whether it was on the ground. */
    private class Blow(val tick: Long, val by: java.util.UUID, val at: Location, val grounded: Boolean)
    private val blows = ArrayDeque<Blow>()
    /** Damage this bot has done to each opponent since it last spawned. */
    private val dealtThisLife = HashMap<java.util.UUID, Double>()
    /** Where each opponent has been staying put, and since when. */
    private class Stay(val at: Location, val since: Long)
    private val stays = HashMap<java.util.UUID, Stay>()
    private var lastGroundAt: Location? = null
    private var lastGroundTick = 0L
    /** How the bot was last trying to get in to somebody other than on foot ("jump", "cross:DROP"...), and when. */
    private var lastWay = "walk"
    private var lastWayTick = -1000L
    /** The jump the bot last set off on (from a block, to a block), and when. */
    private var lastHop: Pair<RouteNode, RouteNode>? = null
    private var lastHopTick = -1000L

    /**
     * What dying has taught this bot, kept for the whole match, through its respawns: the traps
     * it has walked into (an opponent who stays put and kills it from there), the ways in that
     * cost it a life, and the jumps it fell to its death on. See [DeathMemory] for the rules.
     * Deaths in the same trap before the lesson takes hold: the better the player, the sooner.
     */
    private val memory = DeathMemory(when (d) { BotDifficulty.NORMAL -> 3; BotDifficulty.HARD -> 2; else -> 1 })

    private fun Location.point() = DeathMemory.Point(x, y, z)
    private fun DeathMemory.Point.distance(l: Location) = distance(l.point())

    private fun forgetDeaths() {
        memory.clear(); stays.clear(); blows.clear(); dealtThisLife.clear()
        lastHop = null; lastHopTick = -1000; lastWayTick = -1000
    }

    /** Called for every hit this bot takes from somebody else. */
    fun struck(attacker: Player) {
        if (attacker.uniqueId == player.uniqueId) {
            if (BotManager.debug) {
                val here = player.location
                val skull = ownSkulls().isNotEmpty() || holdUntilTick >= 0
                dbg("hurt by something of its own" + (if (skull) " (a skull out)" else "") +
                    (target?.takeIf { it.world == here.world }?.let { ", %.1f blocks from ${it.name}".format(it.location.distance(here)) } ?: ""))
            }
            return
        }
        // A hit from nearby says where it came from, seen or not; so does one from further off
        // when whoever threw it is there to be seen by turning round. (One from behind a wall or
        // across the map, an anvil or a clone's, says only that they are somewhere.)
        if (near(attacker) || (!BotManager.allSeeing && attacker.world == player.world && !ItemService.hidden(attacker) &&
                attacker.location.distanceSquared(player.location) <= SightTactics.HIT_FROM * SightTactics.HIT_FROM &&
                player.hasLineOfSight(attacker))) notice(attacker, "hit by them")
        // A hit put down to them with their clone standing next to this bot is the clone's: felt, if not seen.
        if (ItemEffects.cloneOwners.isNotEmpty()) {
            val here = player.location
            if (ItemService.hostileClones(player).any { ItemEffects.cloneOwners[it.uniqueId] == attacker &&
                    it.location.distanceSquared(here) <= CloneTactics.FELT_RANGE * CloneTactics.FELT_RANGE }) pestFelt = tick
        }
        // (The same with something their kit has sent to fight for them standing next to it: a Wither Image, a bear cub.)
        if (!BotManager.leaveSummons && dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons.owners.isNotEmpty()) {
            val summons = dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons.owners
            if (player.world.getNearbyLivingEntities(player.location, CloneTactics.FELT_RANGE).any {
                    it !is Player && summons[it.uniqueId] == attacker && SummonTactics.fights(it.type.name) }) pestFelt = tick
        }
        blows.addLast(Blow(tick, attacker.uniqueId, player.location.clone(), nms.onGround()))
        while (blows.size > 48 || tick - blows.first().tick > 400) blows.removeFirst()
        // Shot at by somebody it is keeping its distance from, and down to half health: the
        // distance it is keeping is not enough. Further back.
        if (memory.traps.isNotEmpty() && player.health <= maxHealth(player) * 0.5) {
            val here = player.location.point()
            val trap = memory.trapOf(attacker.uniqueId, attacker.location.point())
            if (trap != null && memory.hitFrom(trap, here)) {
                prowlGoal = null; holdSince = -1; prowlRestUntil = 0
                dbg("hit by ${attacker.name} from their spot at ${"%.0f".format(trap.camp.distance(here))} blocks: keeping ${"%.0f".format(trap.keepOut)} away now")
            }
        }
    }

    /** Called for damage this bot does to somebody else. */
    fun dealt(victim: Player, amount: Double) {
        dealtThisLife.merge(victim.uniqueId, amount, Double::plus)
        // A hit that lands on somebody nearby says where they are, seen or not.
        if (near(victim) && victim.uniqueId != player.uniqueId) notice(victim, "hit them")
    }

    /** Close enough that a hit given or taken shows where the other one is. */
    private fun near(other: Player) = other.world == player.world && other.location.distanceSquared(player.location) <= 144.0

    // ------------------------------------------------------------------ what is thrown at it

    /** Which side of the straight line it is walking on (1, -1, or 0: straight), and until when. */
    private var weaveSide = 0
    private var weaveUntil = 0L
    /** When each opponent last had something in the air that this bot saw coming at it. */
    private val shotAtBy = HashMap<java.util.UUID, Long>()

    /**
     * Whether to come at [t] in a zigzag this tick ([WeaveTactics]); [weaveSide] is the side. Only
     * at somebody who is looking its way, from between arm's length and as far as anything is
     * thrown, and only with floor to both sides.
     */
    private fun weaves(t: Player, dist: Double, sees: Boolean): Boolean {
        if (BotManager.noAbilityDodge || BotManager.noWeave || !WeaveTactics.weaves(d) || !sees || !nms.onGround()) return false
        if (dist < WeaveTactics.FROM || dist > WeaveTactics.TO) { weaveSide = 0; weaveUntil = 0; return false }
        // Only at somebody who has been throwing things at it: nobody jinks at an opponent who is only looking at them.
        if (tick - (shotAtBy[t.uniqueId] ?: -100000L) > WeaveTactics.WARY) return false
        // (And never in the last steps before a jump or a climb: those are lined up for, and taken straight.)
        if (gapJump != null || climb != null) return false
        for (i in 0 until minOf(route.size, WeaveTactics.STRAIGHT)) { val s = route[i]; if (s.jump || s.climb || s.long) return false }
        val (toMe, _) = lookAngles(t.eyeLocation, player.eyeLocation)
        if (!WeaveTactics.facing(wrapDegrees(toMe - t.location.yaw).toDouble())) return false
        if (tick >= weaveUntil) {
            val here = player.location
            val across = t.location.toVector().subtract(here.toVector()).setY(0)
            val room = across.lengthSquared() > 1e-6 && across.normalize().let { u ->
                // (Square to the way to them, out to each side.)
                hasSolidBelow(here.clone().add(-u.z * WeaveTactics.ROOM, 0.5, u.x * WeaveTactics.ROOM), 2) &&
                    hasSolidBelow(here.clone().add(u.z * WeaveTactics.ROOM, 0.5, -u.x * WeaveTactics.ROOM), 2)
            }
            weaveSide = if (!room) 0 else if (weaveSide != 0) -weaveSide else if (rng.nextBoolean()) 1 else -1
            weaveUntil = tick + WeaveTactics.stretch(rng.nextDouble())
        }
        return weaveSide != 0
    }

    /**
     * Something a kit threw is coming at this bot ([DodgeTactics]): it has to see it, it takes a
     * moment, and then it steps across the thing's path, if there is floor to step onto. Only the
     * step is decided here; [applyMovement] takes it, so the bot goes on aiming and hitting while
     * it moves. (A bot used to walk down the line of a fireball it had a full second to step out of.)
     */
    private fun tickThreats(game: BrawlMinigame) {
        if (BotManager.noAbilityDodge) { dodgeTo = null; return }
        val here = player.location
        val world = here.world ?: return
        val flights = dev.betrix.superSmashMobsBrawl.abilities.Flights.inWorld(world)
        val eye = player.eyeLocation
        var worst: dev.betrix.superSmashMobsBrawl.abilities.Flights.Flight? = null
        var worstPass: DodgeTactics.Pass? = null
        for (f in flights) {
            if (f.owner == player || game.arePlayersOnSameTeam(player, f.owner)) continue
            val gap = f.at.distance(eye)
            if (gap > DodgeTactics.SEEN) continue
            val pass = DodgeTactics.pass(f.at.x, f.at.z, f.velocity.x, f.velocity.z, here.x, here.z) ?: continue
            if (worstPass != null && pass.ticks >= worstPass.ticks) continue
            if (!DodgeTactics.threatens(pass, f.radius, f.at.y + f.velocity.y * pass.ticks - here.y)) continue
            // Seen coming: in front of it with nothing in between, or right on top of it.
            if (gap > DodgeTactics.FELT) {
                val (yaw, pitch) = lookAngles(eye, f.at)
                if (!SightTactics.inView(wrapDegrees(yaw - nms.yRot).toDouble(), (pitch - nms.xRot).toDouble())) continue
                val line = f.at.toVector().subtract(eye.toVector()).multiply(1 / gap)
                if (world.rayTraceBlocks(eye, line, gap - 0.3, org.bukkit.FluidCollisionMode.NEVER, true) != null) continue
            }
            worst = f; worstPass = pass
        }
        val f = worst
        val pass = worstPass
        if (f == null || pass == null) {
            if (tick - threatSeen > DodgeTactics.LULL) threatSince = -1
            // Clear of it: a couple of ticks more and it is back to what it was doing.
            if (dodgeTo != null) { if (tick >= dodgeUntil) dodgeTo = null else dodgeUntil = minOf(dodgeUntil, tick + 3) }
            return
        }
        threatSeen = tick
        shotAtBy[f.owner.uniqueId] = tick
        if (threatSince < 0) threatSince = tick
        if (tick - threatSince < DodgeTactics.readyAfter(d)) return
        // (In the air there is no stepping aside.)
        if (!nms.onGround()) return
        // Where it is already stepping to, if that is out of the way of this one too: no going back and forth.
        val going = dodgeTo?.takeIf { it.world == world && tick < dodgeUntil + 4 &&
            DodgeTactics.clear(DodgeTactics.offLine(f.at.x, f.at.z, f.velocity.x, f.velocity.z, it.x, it.z), f.radius) }
        val to = going ?: run {
            val (ax, az) = DodgeTactics.aside(f.velocity.x, f.velocity.z, pass.side)
            val base = floorUnder(here, 2.0)?.let { Location(world, here.x, it.y, here.z) } ?: here
            // To the side it is nearer first; across the thing's path if there is no floor on that side.
            listOf(1, -1).map { sign -> Location(world, base.x + ax * sign * DodgeTactics.SIDESTEP, base.y, base.z + az * sign * DodgeTactics.SIDESTEP) }
                .firstOrNull { walkable(base, it) } ?: return
        }
        dodgeTo = to
        dodgeUntil = tick + ceil(pass.ticks).toLong() + 2
        if (tick - dodgeSaidTick > 20) {
            dodgeSaidTick = tick
            dbg("stepping out of the way of ${f.owner.name}'s ${f.what}: on it in %.0f ticks, %.1f blocks from its line".format(pass.ticks, pass.miss))
        }
    }

    // Something winding up beside it ([BlastTactics]): since when it has known, when it last did, and where it is running to.
    private var blastSince = -1L
    private var blastSeen = -1000L
    private var blastTo: Location? = null
    /** The tick it is waiting outside something that is winding up, fighting on from where it stands; and what it is keeping out of. */
    private var blastHoldTick = -1000L
    private var blastsHeld: List<dev.betrix.superSmashMobsBrawl.abilities.Flights.Blast> = emptyList()
    private var carrySaidTick = -1000L

    /**
     * Would something of its own that carries it [far] blocks toward ([ux], [uz]) (a dash, a leap,
     * a charge) take it into what it is waiting outside of, or through it?
     */
    private fun carriesIntoBlast(ux: Double, uz: Double, far: Double): Boolean {
        // (This tick, or the last one: some of what a bot does comes before it has looked round again.)
        if (tick - blastHoldTick > 1 || far <= 0.0) return false
        val length = hypot(ux, uz)
        if (length < 1e-6) return false
        val here = player.location
        return blastsHeld.any { b -> BlastTactics.carriesIn(here.x - b.at.x, here.z - b.at.z, ux / length, uz / length, far, b.radius) }
    }

    /**
     * Something is winding up to go off within reach of this bot (a Creeper has begun to hiss):
     * it hears it, takes a moment, and runs, straight away from it for as far as there is floor,
     * and then waits outside for it to go off ([BlastTactics]). Returns true while it is doing that.
     */
    private fun tickBlasts(game: BrawlMinigame, t: Player?, map: GameMapDef): Boolean {
        if (BotManager.noAbilityDodge) return false
        val here = player.location
        val world = here.world ?: return false
        var worst: dev.betrix.superSmashMobsBrawl.abilities.Flights.Blast? = null
        var waits = false
        val near = ArrayList<dev.betrix.superSmashMobsBrawl.abilities.Flights.Blast>(2)
        for (b in dev.betrix.superSmashMobsBrawl.abilities.Flights.blastsIn(world)) {
            if (b.owner == player || game.arePlayersOnSameTeam(player, b.owner)) continue
            val gap = b.at.distance(here)
            if (gap > BlastTactics.HEARD) continue
            near += b
            if (BlastTactics.waitsOutside(gap, b.radius)) waits = true
            if (!BlastTactics.inReach(gap, b.radius)) continue
            if (worst == null || b.ticksLeft < worst.ticksLeft) worst = b
        }
        val b = worst
        if (b == null) {
            if (!waits) { if (tick - blastSeen > DodgeTactics.LULL) { blastSince = -1; blastTo = null }; return false }
            // Out of it, and it has not gone off yet: no walking back in after whoever is hissing.
            if (blastSince < 0 || tick - blastSince < BlastTactics.readyAfter(d)) return false
            blastSeen = tick
            if (BotManager.watchBlasts) {
                t?.let { val (yaw, pitch) = lookAngles(player.eyeLocation, aimPointOf(it)); lookTowards(yaw, pitch, d.turnSpeed) }
                setInputs(0f, 0f, false)
                return true
            }
            // It fights on from where it stands, with whatever it has that reaches. What it does
            // this tick may not take it back in: [applyMovement] and [useAbility] see to that.
            if (blastHoldTick != tick - 1) dbg("out of reach of ${near.firstOrNull()?.owner?.name}'s ${near.firstOrNull()?.what}: fighting on from here until it has gone off")
            blastHoldTick = tick
            blastsHeld = near
            // (Standing still on purpose says nothing about the way it was on.)
            patientTick = tick
            return false
        }
        blastSeen = tick
        if (blastSince < 0) blastSince = tick
        if (tick - blastSince < BlastTactics.readyAfter(d) || b.ticksLeft < BlastTactics.TOO_LATE) return false
        val base = floorUnder(here, 3.0)?.let { Location(world, here.x, it.y, here.z) } ?: here
        var to = blastTo?.takeIf { it.world == world && hypot(it.x - here.x, it.z - here.z) > 0.6 }
        if (to == null) {
            // (In the air there is nothing to run on.)
            if (!nms.onGround()) return false
            val dx = here.x - b.at.x
            val dz = here.z - b.at.z
            val out = BlastTactics.run(hypot(dx, dz), b.radius)
            to = (listOf(out) + BlastTactics.SHORTER.filter { it < out }).asSequence()
                .flatMap { far -> BlastTactics.TURNS.asSequence().map { turn -> BlastTactics.way(dx, dz, turn, far) } }
                .map { (ox, oz) -> Location(world, base.x + ox, base.y, base.z + oz) }
                .firstOrNull { walkable(base, it) }
                // Nowhere to run: it takes it where it stands, and fights on.
                ?: return false
            blastTo = to
            dbg("running from ${b.owner.name}'s ${b.what}: it goes off in ${b.ticksLeft} ticks, %.1f blocks from here; to ${to.blockX},${to.blockY},${to.blockZ}".format(hypot(dx, dz)))
        }
        rain?.let { endRain("something is about to go off") }
        val (yaw, _) = lookAngles(player.eyeLocation, to.clone().add(0.0, 1.2, 0.0))
        turnTowards(yaw, 0f, d.turnSpeed * 2)
        val input = inputToward(to)
        applyMovement(null, input.first, input.second, nms.horizontalCollision && nms.onGround(), map)
        return true
    }

    /** The walls that hurt whoever touches them which this bot knows of and keeps out of, as of [wallsTick] ([WallTactics]). */
    private val walls = ArrayList<dev.betrix.superSmashMobsBrawl.abilities.Flights.Standing>()
    private var wallsTick = -1000L
    /** Whose wall it first knew of when, and whose it has begun to keep out of. */
    private val wallSince = HashMap<java.util.UUID, Long>()
    private val wallKnown = HashSet<java.util.UUID>()
    /** The end it is going round one by (1: turning left of its way, -1: right), and until when it keeps to that. */
    private var wallTurn = 0
    private var wallTurnUntil = 0L
    private var wallSaidTick = -1000L
    private var wallReplanTick = 0L

    /** Would a bot standing on [n] be within reach of [w]? */
    private fun wallBars(w: dev.betrix.superSmashMobsBrawl.abilities.Flights.Standing, n: RouteNode) =
        WallTactics.level(n.y, w.minY, w.maxY, w.reach) &&
            WallTactics.touches(n.x + .5, n.z + .5, w.minX, w.minZ, w.maxX, w.maxZ, w.reach, 0.0)

    /**
     * Which walls that hurt whoever touches them this bot knows of ([WallTactics]): one in front
     * of it, or one it has all but walked into, and after the moment it takes to do anything about
     * it. What it then does about them is in [roundWalls] (where it walks) and [routeToward] (the
     * way it plans).
     */
    private fun tickWalls(game: BrawlMinigame) {
        walls.clear()
        wallsTick = tick
        val here = player.location
        val world = here.world ?: return
        val standing = if (BotManager.noAbilityDodge) emptyList() else dev.betrix.superSmashMobsBrawl.abilities.Flights.standingIn(world)
        if (standing.isEmpty()) {
            if (wallSince.isNotEmpty()) { wallSince.clear(); wallKnown.clear() }
            return
        }
        val eye = player.eyeLocation
        val up = HashSet<java.util.UUID>()
        for (w in standing) {
            if (w.owner == player || game.arePlayersOnSameTeam(player, w.owner)) continue
            val id = w.owner.uniqueId
            up += id
            var since = wallSince[id]
            if (since == null) {
                val gap = WallTactics.gap(here.x, here.z, w.minX, w.minZ, w.maxX, w.maxZ)
                if (gap > WallTactics.SEEN || abs((w.minY + w.maxY) / 2 - here.y) > WallTactics.SEEN) continue
                if (gap > WallTactics.FELT) {
                    val middle = Location(world, (w.minX + w.maxX) / 2, (w.minY + w.maxY) / 2, (w.minZ + w.maxZ) / 2)
                    val (yaw, pitch) = lookAngles(eye, middle)
                    if (!SightTactics.inView(wrapDegrees(yaw - nms.yRot).toDouble(), (pitch - nms.xRot).toDouble())) continue
                }
                since = tick
                wallSince[id] = tick
            }
            if (tick - since < WallTactics.readyAfter(d)) continue
            walls += w
            if (wallKnown.add(id)) {
                // Whatever way it was going was planned without knowing about this.
                route.clear(); routeSearch = null; nextRouteTick = tick
                dbg("${w.owner.name}'s ${w.what} is standing %.1f blocks off: keeping out of it".format(
                    WallTactics.gap(here.x, here.z, w.minX, w.minZ, w.maxX, w.maxZ)))
            }
        }
        wallSince.keys.retainAll(up)
        wallKnown.retainAll(up)
        // A way planned from inside one (when anywhere out of it would do) that still leads through it: again, from outside.
        if (walls.isNotEmpty() && tick >= wallReplanTick && routeSearch == null && gapJump == null && climb == null) {
            val next = route.firstOrNull()?.node
            if (next != null && walls.any { wallBars(it, next) } &&
                walls.none { WallTactics.level(here.y, it.minY, it.maxY, it.reach) && WallTactics.touches(here.x, here.z, it.minX, it.minZ, it.maxX, it.maxZ, it.reach, 0.0) }) {
                wallReplanTick = tick + 10
                route.clear(); nextRouteTick = tick
            }
        }
    }

    /**
     * The movement keys [forward] and [strafe], kept out of every wall this bot knows of: as they
     * were if the way is clear, turned aside by as little as gets past the end of it if not, and
     * let go if there is no getting past. Standing in one (it went up where the bot stood, or the
     * bot was knocked into it), the keys are for the shortest way out instead.
     */
    private fun roundWalls(forward: Float, strafe: Float): Pair<Float, Float> {
        val here = player.location
        fun touched(x: Double, z: Double, margin: Double) = walls.firstOrNull {
            WallTactics.level(here.y, it.minY, it.maxY, it.reach) && WallTactics.touches(x, z, it.minX, it.minZ, it.maxX, it.maxZ, it.reach, margin) }
        fun say(text: String) { if (tick - wallSaidTick > 30) { wallSaidTick = tick; dbg(text) } }
        touched(here.x, here.z, 0.0)?.let { w ->
            val out = WallTactics.waysOut(here.x, here.z, w.minX, w.minZ, w.maxX, w.maxZ, w.reach)
                .map { (dx, dz) -> Location(here.world, here.x + dx, here.y, here.z + dz) }
                .firstOrNull { touched(it.x, it.z, 0.0) == null && hasSolidBelow(it.clone().add(0.0, 0.5, 0.0), 3) &&
                    it.clone().add(0.0, 0.1, 0.0).block.isPassable && it.clone().add(0.0, 1.1, 0.0).block.isPassable }
                ?: return forward to strafe
            say("in reach of ${w.owner.name}'s ${w.what}: stepping out of it")
            return inputToward(out)
        }
        val (mx, mz) = moveDir(forward, strafe) ?: return forward to strafe
        fun inTheWay(ux: Double, uz: Double) = touched(here.x + ux * WallTactics.AHEAD, here.z + uz * WallTactics.AHEAD, WallTactics.MARGIN)
            ?: touched(here.x + ux * WallTactics.AHEAD / 2, here.z + uz * WallTactics.AHEAD / 2, WallTactics.MARGIN)
        val w = inTheWay(mx, mz) ?: return forward to strafe
        // Round the end the way already leans to, and by that end until it is past.
        val first = if (tick < wallTurnUntil && wallTurn != 0) wallTurn else {
            val (lf, ls) = WeaveTactics.turned(forward, strafe, 90.0)
            val (lx, lz) = moveDir(lf, ls) ?: (0.0 to 0.0)
            if (lx * ((w.minX + w.maxX) / 2 - here.x) + lz * ((w.minZ + w.maxZ) / 2 - here.z) <= 0.0) 1 else -1
        }
        for (sign in intArrayOf(first, -first)) for (turn in WallTactics.TURNS) {
            val (f2, s2) = WeaveTactics.turned(forward, strafe, turn * sign)
            val (ux, uz) = moveDir(f2, s2) ?: continue
            if (inTheWay(ux, uz) != null || !groundAhead(f2, s2, 1.3)) continue
            wallTurn = sign; wallTurnUntil = tick + WallTactics.KEEP
            say("${w.owner.name}'s ${w.what} is in its way: going round the end of it")
            return f2 to s2
        }
        say("${w.owner.name}'s ${w.what} is in its way and there is no getting round it: staying out of it")
        return 0f to 0f
    }

    // ------------------------------------------------------------------ what it sees and hears

    private class Looked(val tick: Long, val open: Boolean)
    /** When each opponent in front of it was last looked at properly, and whether anything was in the way: that is not worked out afresh every time. */
    private val looked = HashMap<java.util.UUID, Looked>()

    /**
     * Somebody has come to this bot's notice, or is still in it ([how]: for the log). The first
     * sign of them after a while with none starts the moment it takes to do anything about them
     * ([SightTactics.readyAfter]), unless it comes [atOnce] (somebody it went looking for).
     */
    private fun notice(p: Player, how: String, atOnce: Boolean = false) {
        val id = p.uniqueId
        val old = sensed[id]
        val at = p.location
        val new = old == null || old.at.world != p.world || tick - old.tick > SightTactics.memoryTicks(d)
        // Kept in mind, but not where it took them to be: as good as new. (Not the one it is
        // after already: that is only where they turned out to be.)
        val elsewhere = !new && old != null && p != target && tick - old.tick > SightTactics.FRESH &&
            old.at.distanceSquared(at) > SightTactics.SURPRISE * SightTactics.SURPRISE
        if (new || elsewhere) {
            noticedAt[id] = if (atOnce) tick - SightTactics.readyAfter(d) else tick
            if (BotManager.debug && !BotManager.allSeeing) {
                dbg("noticed ${p.name} ($how), %.0f blocks off".format(at.distance(player.location)) +
                    if (elsewhere && old != null) ", %.0f from where it took them to be".format(old.at.distance(at)) else "")
            }
        }
        sensed[id] = Sign(tick, at)
        lastPerceived = tick
    }

    /**
     * Seen or heard this moment, as opposed to kept in mind. (Somebody in a Shadow Walk is never
     * seen: what a bot does about them is [ShadowTactics]' business, and is left as it is.)
     */
    private fun aware(p: Player): Boolean =
        BotManager.allSeeing || ItemService.hidden(p) || sensed[p.uniqueId]?.let { tick - it.tick <= SightTactics.FRESH } == true

    /** Where this bot takes [p] to be: where they are if it has them in view or earshot, where it last did if not. */
    private fun whereIs(p: Player): Location =
        if (aware(p)) p.location else sensed[p.uniqueId]?.at?.takeIf { it.world == p.world } ?: p.location

    /** Somebody in its match has died (the kill feed says so): wherever it took them to be, they are not there now. */
    fun heardOfDeath(p: Player) {
        val id = p.uniqueId
        sensed.remove(id); noticedAt.remove(id); looked.remove(id)
    }

    /** No longer in mind: gone from where they were, or nowhere it can get to. */
    private fun forget(p: Player, why: String) {
        val id = p.uniqueId
        sensed.remove(id); noticedAt.remove(id); looked.remove(id)
        dbg("${p.name} is $why: lost them")
    }

    /**
     * Whether [t], whom it is after, is somebody to go looking for: in mind but not in view. Not
     * somebody holding a spot it cannot get to or has learned to keep away from: where they are
     * is no secret, and what it does about them is worked out from where they are.
     */
    private fun seeks(t: Player) = !aware(t) && !besieging(t) && standingOff(t) == null && trapOf(t) == null

    private var seekSaidTick = -1000L

    /**
     * After somebody it has not seen or heard for a moment: to where it last knew them to be,
     * looking where it is going, to find them there or not. (It used to make for where they were
     * now, wherever that was.)
     */
    private fun seek(t: Player, map: GameMapDef) {
        val at = sensed[t.uniqueId]?.at?.takeIf { it.world == player.world } ?: return wander(map)
        val here = player.location
        if (hypot(at.x - here.x, at.z - here.z) < SightTactics.ARRIVED && abs(at.y - here.y) < 3.0) {
            forget(t, "not where it last saw them")
            return setInputs(0f, 0f, false)
        }
        if (tick - seekSaidTick > 40) {
            seekSaidTick = tick
            dbg("${t.name} is out of its sight: to where it last saw them, %.0f blocks off (they are %.0f from there now)".format(
                at.distance(here), at.distance(t.location)))
        }
        travel(at, map) { forget(t, "somewhere it has no way to") }
    }

    /**
     * What this bot sees and hears of its opponents this moment ([SightTactics]): whoever is in
     * front of it with nothing in between, whoever is close by, and whoever is being hit not far off.
     */
    private fun perceive(game: BrawlMinigame) {
        val eye = player.eyeLocation
        for (entry in game.allPlayers()) {
            val p = entry.player ?: continue
            if (p == player || p.world != player.world || p.gameMode != GameMode.SURVIVAL || game.arePlayersOnSameTeam(player, p)) continue
            val id = p.uniqueId
            // (Back to life is somewhere else on the map: what was known of them is no guide to it.)
            if (game.respawnManager.isRespawning(p)) { sensed.remove(id); noticedAt.remove(id); looked.remove(id); continue }
            // Somebody in a Shadow Walk is known only by what they do: a hit given or taken.
            if (ItemService.hidden(p)) continue
            val at = aimPointOf(p)
            val gap = eye.distance(at)
            if (gap > SightTactics.FAR) continue
            if (gap <= SightTactics.HEARD) { notice(p, "heard them close by"); continue }
            if (p.noDamageTicks > 0 && gap <= SightTactics.FIGHT_HEARD) { notice(p, "heard them being hit"); continue }
            val (yaw, pitch) = lookAngles(eye, at)
            if (!SightTactics.inView(wrapDegrees(yaw - nms.yRot).toDouble(), (pitch - nms.xRot).toDouble())) continue
            val last = looked[id]
            val open = if (last != null && tick - last.tick < SightTactics.LOOK_EVERY) last.open
                else player.hasLineOfSight(p).also { looked[id] = Looked(tick, it) }
            if (open) notice(p, "saw them")
        }
    }

    /**
     * A look at the Player Compass every fighter carries: it points at the nearest opponent and
     * says how far off they are. What a player does who has looked round and seen nobody.
     */
    private fun readCompass(game: BrawlMinigame) {
        val here = player.location
        val nearest = game.allPlayers().mapNotNull { it.player }.filter {
            it != player && it.world == player.world && it.gameMode == GameMode.SURVIVAL && !ItemService.hidden(it) &&
                !game.arePlayersOnSameTeam(player, it) && !game.respawnManager.isRespawning(it) &&
                !game.connectionManager.isDisconnected(it)
        }.minByOrNull { it.location.distanceSquared(here) } ?: return
        notice(nearest, "by its compass", atOnce = true)
    }

    /** Called as this bot dies: was that something it should not walk into a second time? [fell] is a death by the void or water. */
    fun died(fell: Boolean) {
        val last = blows.lastOrNull()
        // A fall is somebody's doing if they hit the bot shortly before it last stood on anything, or after.
        val killerId = last?.takeIf { if (fell) it.tick >= lastGroundTick - 60 else tick - it.tick <= 200 }?.by
        val killer = killerId?.let { Bukkit.getPlayer(it) }?.takeIf { it.isOnline && it.world == player.world }
        try {
            if (BotManager.noLearning || passive || rooted) return
            if (killer != null) trapped(killer, fell) else if (fell) fellAlone()
        } finally {
            blows.clear(); dealtThisLife.clear()
            lastHopTick = -1000; lastWayTick = -1000
        }
    }

    private fun wayNow(): String =
        crossing?.let { "cross:${it.kind}" } ?: (if (bridge != null) "bridge" else null) ?: lastWay.takeIf { tick - lastWayTick <= 160 } ?: "walk"

    private fun spot(p: DeathMemory.Point) = "%.1f,%.1f,%.1f".format(p.x, p.y, p.z)

    private fun trapped(killer: Player, fell: Boolean) {
        val id = killer.uniqueId
        val at = killer.location
        // Only somebody holding a spot: a fight that moves about the map leaves nothing to stay away from next time.
        val holding = stays[id]?.let { it.at.world == at.world && tick - it.since >= CAMP_TICKS && it.at.distanceSquared(at) <= CAMP_RADIUS * CAMP_RADIUS } == true
        val mine = blows.filter { it.by == id && tick - it.tick <= 240 }
        val standing = mine.filter { it.grounded || hasSolidBelow(it.at, 3) }.map { it.at.point() } +
            listOfNotNull(lastGroundAt?.takeIf { tick - lastGroundTick <= 240 }?.point())
        val way = wayNow()
        val trap = memory.killedBy(id, at.point(), holding, fell, exchanged = (dealtThisLife[id] ?: 0.0) >= maxHealth(killer) * 0.25,
            hitAt = mine.map { it.at.point() }, standing = standing, way = way, now = tick) ?: return
        val quiet = ((trap.keepOutUntil - tick) / 20).toInt()
        if (memory.learned(trap)) {
            // Said out loud as well: the next thing this bot does is not walk back in.
            val bot = player.uniqueId
            val times = trap.deaths
            publish { TrapLearned(bot, id, times, it) }
        }
        plugin.logger.info("[bots] ${player.name} lesson: killed ${trap.deaths}x by ${killer.name} holding ${spot(trap.camp)} " +
            "(going in by $way, hit at ${standing.map { "${it.x.toInt()},${it.y.toInt()},${it.z.toInt()}" }.distinct().take(5).joinToString(" ")}); " +
            (if (!memory.learned(trap)) "not learned yet"
            else "keeps off that ground, and for ${quiet}s out of their sight within ${"%.0f".format(trap.keepOut)} blocks; " +
                (if (tick < trap.standoffUntil) "no going in at all for that long" else "ways in not to try again: ${trap.ways.joinToString(",")}")))
    }

    /** Fell to its death with nobody's help: whatever jump it was on is one to leave alone. */
    private fun fellAlone() {
        val way = wayNow()
        val t = target
        if (way != "walk" && way != "leap" && t != null) {
            memory.fellTrying(way, t.location.point())
            plugin.logger.info("[bots] ${player.name} lesson: fell trying a $way to ${t.name} at ${spot(t.location.point())}; not trying that there again")
        }
        val hop = lastHop?.takeIf { lastGroundTick <= lastHopTick + 40 && tick - lastHopTick <= 400 } ?: return
        val count = memory.fellOn(hop, tick)
        plugin.logger.info("[bots] ${player.name} lesson: fell on the jump from ${hop.first.x},${"%.1f".format(hop.first.y)},${hop.first.z} to " +
            "${hop.second.x},${"%.1f".format(hop.second.y)},${hop.second.z}; leaving it alone ${if (count >= 2) "for the rest of the match" else "for a minute"}")
    }

    /**
     * Down to three hunger bars. Only hurting somebody fills them, and an empty bar kills: a
     * bot this hungry stops keeping its distance from a trap. Going in may cost a life; staying
     * out certainly will.
     */
    private fun hungry() = player.foodLevel <= dev.betrix.superSmashMobsBrawl.utils.HungerRules.HUNGRY

    /** The trap [t] is sitting in right now, if this bot has died in it often enough to have learned (and can afford to care). */
    private fun trapOf(t: Player): DeathMemory.Trap? = if (memory.traps.isEmpty() || hungry()) null else memory.trapOf(t.uniqueId, t.location.point())

    /** The trap [t] is sitting in, while this bot has given up going in to them. */
    private fun standingOff(t: Player): DeathMemory.Trap? = trapOf(t)?.takeIf { tick < it.standoffUntil }

    /** Has this way of getting in to [t] (a "jump", a "cross:DROP", a "bridge") already cost a life where they are now? */
    private fun wayBarred(t: Player, way: String): Boolean = !hungry() && memory.wayBarred(t.uniqueId, t.location.point(), way, tick)

    /** Ground not to walk on: [r] blocks around a point, within [h] of its height, and only what is [seen] from there if that is given. */
    private class Zone(val x: Double, val y: Double, val z: Double, val r: Double, val h: Double, val seen: ((RouteNode) -> Boolean)? = null) {
        /** How far [n] is from the middle of this zone, if it is at its height at all. */
        fun gap(n: RouteNode) = if (abs(n.y - y) < h) hypot(n.x + .5 - x, n.z + .5 - z) else Double.MAX_VALUE
        /** Is [n] ground to keep off, taking this zone to reach [within] blocks? */
        fun bars(n: RouteNode, within: Double = r) = gap(n) < within && (seen == null || seen.invoke(n))
    }

    /** Which blocks the camper of a trap can see from where they sit: worked out once a block, kept while they stay put. */
    private val exposure = HashMap<RouteNode, Boolean>()
    private var exposureFrom: Triple<Int, Int, Int>? = null
    private var exposureOf: java.util.UUID? = null

    /** Could [k] see (and so shoot) a fighter standing on [n] from where they are now? */
    private fun exposedTo(k: Player, n: RouteNode): Boolean {
        val at = k.location
        val from = Triple(at.blockX, at.blockY, at.blockZ)
        if (exposureOf != k.uniqueId || exposureFrom != from || exposure.size > 30000) {
            exposure.clear(); exposureOf = k.uniqueId; exposureFrom = from
        }
        return exposure.getOrPut(n) {
            val eye = k.eyeLocation
            // The head or the legs showing is enough to be shot at.
            doubleArrayOf(1.5, 0.5).any { up ->
                val gap = Vector(n.x + .5 - eye.x, n.y + up - eye.y, n.z + .5 - eye.z)
                val length = gap.length()
                length < 1.0 || eye.world.rayTraceBlocks(eye, gap.multiply(1.0 / length), length, org.bukkit.FluidCollisionMode.NEVER, true) == null
            }
        }
    }

    /**
     * The ground this bot was killed on by somebody who is still sitting where they killed it
     * from. With [keepOut], also all the ground they can see from there, out to a little beyond
     * where they hit it from: walking anywhere, the bot stays out of their line of fire. (Without,
     * for the walk to a crossing: a way in it has not died on is worth its one try.)
     */
    private fun dangerZones(keepOut: Boolean): List<Zone> {
        if (memory.traps.isEmpty() || hungry()) return emptyList()
        val zones = ArrayList<Zone>()
        for (trap in memory.traps) {
            if (!memory.learned(trap)) continue
            val k = Bukkit.getPlayer(trap.killer) ?: continue
            if (!k.isOnline || k.world != player.world || k.gameMode != GameMode.SURVIVAL || trap.camp.distance(k.location) >= DeathMemory.CAMP) continue
            trap.spots.forEach { zones += Zone(it.x, it.y, it.z, TRAP_RADIUS, 4.0) }
            if (keepOut && tick < trap.keepOutUntil) zones += Zone(trap.camp.x, trap.camp.y, trap.camp.z, trap.keepOut, 30.0) { n -> exposedTo(k, n) }
        }
        return zones
    }

    /** Notes where every opponent is holding still, so a death can be told from a trap. */
    private fun trackStays() {
        if (tick % 10 != 0L) return
        for (o in opponents) {
            val at = o.location
            val stay = stays[o.uniqueId]
            if (stay == null || stay.at.world != at.world || stay.at.distanceSquared(at) > CAMP_RADIUS * CAMP_RADIUS) stays[o.uniqueId] = Stay(at.clone(), tick)
        }
    }

    private var nextNoWayLog = 0L

    /** For the log: does a look at the world as it is this moment find somewhere to stand within two columns of [at], no more than [down] below it? */
    private fun freshGround(at: Location, down: Double, map: GameMapDef): Boolean {
        val fresh = ArenaTerrain(player.world, map.voidLevel.toInt(), canDoubleJump())
        for (dx in -2..2) for (dz in -2..2) if (fresh.surface(at.blockX + dx, at.blockZ + dz, at.y, 1.25, down) != null) return true
        return false
    }

    /** For the log: what a bot standing where the routes do not go is standing on, and whether the world itself has a place to stand there. */
    private fun standingOn(here: Location, map: GameMapDef): String {
        if (!BotManager.debug) return ""
        val under = here.clone().add(0.0, -0.1, 0.0).block.type
        val stands = ArenaTerrain(player.world, map.voidLevel.toInt(), canDoubleJump()).surface(here.blockX, here.blockZ, here.y, 0.6, 0.6) != null
        return " (on $under; the world ${if (stands) "has a place to stand here that the picture of it has not" else "has no place to stand here either"})"
    }

    /** The last change to the arena this bot has taken in ([ArenaChanges.stamp]). */
    private var groundStamp = 0
    private var groundSaidTick = -1000L

    /**
     * Blocks have changed somewhere in the arena since this bot last looked ([ArenaChanges]): if
     * that was beside the way it is going, or where a way is being worked out, the way is
     * planned again from the arena as it is now. (It used to walk on until it met the hole.)
     */
    private fun groundChanged() {
        if (gapJump != null || climb != null) return
        val world = player.world
        val here = player.location
        // (Right on the way: a block of it, or the one beside. And a way still being worked out that
        // starts or ends where the steps have just been forgotten is worked out afresh.)
        val onWay = (0 until minOf(route.size, 16)).any { ArenaChanges.touched(world, route[it].node.x, route[it].node.z, 1) }
        val planning = routeSearch != null && (ArenaChanges.within(world, here.blockX, here.blockZ) ||
            routeGoal?.let { it.world == world && ArenaChanges.within(world, it.blockX, it.blockZ) } == true)
        if (!onWay && !planning) return
        route.clear(); routeSearch = null; nextRouteTick = tick
        if (tick - groundSaidTick > 20) { groundSaidTick = tick; dbg("the ground has changed ${if (onWay) "on its way" else "where it is planning a way"}: planning it again") }
    }

    /** For the log: what the route search that found no way had to go on ([markUnreachable] says it once and forgets it). */
    private var noWayWhy = ""
    private var passingSaidTick = -1000L
    /** Who it is fighting and since when they have been off the ground (below zero: standing), for [ReachTactics]. */
    private var airOf: java.util.UUID? = null
    private var airSince = -1L
    private var airWatched = -1000L

    /** How long [t] has been off the ground, as far as this bot has watched them: below zero if they are standing. */
    private fun airTicks(t: Player): Long {
        val grounded = (t as org.bukkit.entity.Entity).isOnGround
        // (Somebody else, or the same one after it had its eyes off them: whether they came down in between is not known.)
        if (airOf != t.uniqueId || tick - airWatched > ReachTactics.WATCHED) { airOf = t.uniqueId; airSince = if (grounded) -1 else tick }
        else if (grounded) airSince = -1
        else if (airSince < 0) airSince = tick
        airWatched = tick
        return if (airSince < 0) -1 else tick - airSince
    }

    private fun markUnreachable(t: Player) {
        unreachableUntil[t.uniqueId] = tick + 300
        unreachableAt[t.uniqueId] = t.location.clone()
        navStats.unreachable++
        if (BotManager.debug) {
            dbg("no way to ${t.name}, %.0f blocks off%s: they are %s, it is %s".format(t.location.distance(player.location),
                if (noWayWhy.isEmpty()) "" else " ($noWayWhy)",
                if ((t as org.bukkit.entity.Entity).isOnGround) "on the ground" else "in the air", if (nms.onGround()) "on the ground" else "in the air"))
            noWayWhy = ""
        }
        // Where a person found to stand that this bot cannot get to: the thing to test against next time.
        if (!BotManager.isBot(t) && tick >= nextNoWayLog) {
            nextNoWayLog = tick + 600
            plugin.logger.info("[bots] ${player.name} (${KitService.getKitForPlayer(player)?.id}) has no way to ${t.name} at " +
                "${"%.1f %.1f %.1f".format(t.x, t.y, t.z)} on ${lastMap?.id} from ${"%.1f %.1f %.1f".format(player.x, player.y, player.z)}")
        }
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
        // (How far round from where it was looking the one it now goes for is: a bot that knows
        // only what it has seen or heard seldom turns on somebody far off behind its back.)
        if (BotManager.debug && next != null && next.world == player.world) {
            val (yaw, _) = lookAngles(player.eyeLocation, aimPointOf(next))
            val sign = sensed[next.uniqueId]
            dbg("going for ${next.name}: %.0f blocks off, %.0f degrees round from where it is looking, ".format(
                next.location.distance(player.location), abs(wrapDegrees(yaw - nms.yRot))) +
                if (sign == null) "never seen or heard"
                else "last seen or heard ${tick - sign.tick} ticks ago %.0f blocks from where they are".format(sign.at.distance(next.location)))
        }
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
        // What bots have been sent about blocks changing is taken into their picture of the arenas
        // once a tick, by whichever of them thinks first ([ArenaChanges]): in a match or out of one.
        ArenaChanges.settle()

        if (holdUntilTick >= 0 && tick >= holdUntilTick) {
            nms.stopUsingItem()
            holdUntilTick = -1
            holdAim = null
        }
        // A Rain Staff held down while the bot was busy with something else (knocked off the map,
        // typing, out of the match) is let go: it rains wherever the eye wanders otherwise.
        rain?.let { if (tick - it.served > 1) endRain("busy with something else") }

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
            // What it learned by dying lasts the match: through its respawns, not into the next game.
            if (game == null || game.getState() != MinigameState.IN_PROGRESS) forgetDeaths()
            target = null
            lastSafe = null
            gapJump = null
            climb = null
            route.clear();routeSearch=null;nextRouteTick=0
            blockedSteps.clear();blockedEdges.clear();missedLongJumps.clear();refusedJumps.clear();lineUp=null;stuckRef=null
            comboQueue.clear();bowReleaseTick=0;player.isSneaking=false
            shot=null;carried=null;channelUntil=0;perch=null;bridge=null;crossing=null;leap=null
            noticed.clear();noWayOut.clear();escape=null;roundTo=null;pest=null;sensed.clear()
            noticedAt.clear();looked.clear();nobodySince = -1;lastPerceived = -1000;compassAt = 0
            dodgeTo = null;threatSince = -1;blastSince = -1;blastTo = null
            walls.clear();wallSince.clear();wallKnown.clear();wallTurn = 0;shotAtBy.clear()
            enterState(CombatState.IDLE)
            opponents=emptyList();healthLosses.clear();lastHealth=0.0
            itemGoal=null;ignoredItems.clear();fleeFailedUntil=0;trackedId=null
            crystalGoal=null;crystalIgnoredUntil=0
            teamOrderNow=null;teamUnreachableUntil=0
            releaseInputs()
            return
        }
        val map = game.worldManager.getWorld()?.data ?: return releaseInputs()
        dev.betrix.superSmashMobsBrawl.smash.SmashService.botTick(player)
        lastMap = map
        // What has changed in the arena since the last tick has been taken into the picture of it
        // ([ArenaChanges]): a way that went past any of it is planned again.
        ArenaChanges.stamp(player.world).let { if (it != groundStamp) { groundStamp = it; groundChanged() } }
        warmNav(player.world, map)

        val onGround = nms.onGround()
        // "Safe" is somewhere a fall can be steered back onto: properly on a block. Standing on
        // the lip of one, with the middle of the body over the drop, is not such a place; a
        // recovery aimed at that exact point comes down past the block.
        if (onGround && hasSolidBelow(player.location.clone().add(0.0, -0.05, 0.0), 1)) lastSafe = player.location.clone()
        // What a death is later read from: where it last stood, the jump it set off on, how it was going in.
        if (onGround) { lastGroundAt = player.location.clone(); lastGroundTick = tick }
        crossing?.let { lastWay = "cross:${it.kind}"; lastWayTick = tick }
        if (bridge != null) { lastWay = "bridge"; lastWayTick = tick }
        trackStays()
        // Which walls that hurt whoever touches them it knows of: whatever it does this tick keeps out of them.
        tickWalls(game)
        if (wasOnGround && !onGround) {
            val from = routeAt
            val to = route.firstOrNull()?.node
            // Only a jump of its own: pushed or blown off a block, the step it was on is not to blame.
            if (from != null && to != null && from != to && tick - jumpReasonTick <= 2) { lastHop = from to to; lastHopTick = tick }
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
            teamOrderNow = if (game.sides.isEmpty()) null
                else dev.betrix.superSmashMobsBrawl.services.BotConversationService.orderFor(game, player.uniqueId)
            chooseTarget(game)
            // Swinging at somebody who cannot be seen is swinging at where they probably are.
            val unsteady = d.aimErrorDegrees + if (target?.let { ItemService.hidden(it) } == true) ShadowTactics.BLUR else 0.0
            // Off the mark the same way for a moment, drifting: not a fresh error at every decision,
            // which made the head shiver on the spot ([LookTactics]).
            val keep = if (BotManager.stiffLook) 0.0 else LookTactics.keeps(d.reactionTicks)
            yawError = LookTactics.nextError(yawError.toDouble(), rng.nextGaussian(), unsteady, keep).toFloat()
            pitchError = LookTactics.nextError(pitchError.toDouble(), rng.nextGaussian(), unsteady * 0.5, keep).toFloat()
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
                    if(next.mechanical || (isReady(step.id) && dist >= hint.minRange - 1 && dist <= hint.maxRange + 2)) {
                        if (step.id == "blink" && !BotManager.oldKitPlay) {
                            // A Blink goes 16 blocks the way its player is looking, which here was at
                            // the opponent: it came out as far past them as they were near, over
                            // whatever was there. It is lined up on the floor beside them like any other.
                            if (blinkAim(t) != null) shot = Shot("blink", 0.0, false, tick, { blinkAim(t) }) { useAbility("blink", hint, false) }
                        } else useAbility(step.id,hint,false)
                    }
                    else comboQueue.clear()
                }
                is ComboStep.Melee -> meleeClick()
            }
        }
        // A shot nobody got round to firing (the fight moved on) is forgotten.
        if (shot?.let { tick - it.born > 20 } == true) shot = null
        // (Nothing is used on somebody it is only on its way to look for.)
        considerItem(target?.takeIf { it.isOnline && it.world == player.world && !seeks(it) }, map)
        // At the wheel of a Flying Car: the car goes where it is driven (ItemEffects.botKeys), and
        // nothing the legs or the abilities could do from the seat is of any use.
        if (player.isInsideVehicle) return releaseInputs()
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
            // A goat charges its leap crouching on the ground. Crouched there is no sprinting, and
            // a charged leap goes up whether or not up is any use (fifteen blocks, under a roof),
            // so it charges on purpose and only then: at the foot of a leap its route needs the
            // charge for. (It used to crouch all the way to a far opponent, after every jump.)
            "goat" -> nms.onGround() && gapJump==null && tick-wantCharge<=1
            "enderman" -> nms.onGround() && target?.let { it.location.distanceSquared(player.location)>400 && player.hasLineOfSight(it) } == true
            "phantom" -> player.isFlying && target?.let {
                if (BotManager.oldKitPlay) hypot(it.x-player.x,it.z-player.z)<3 && it.y<player.y-1
                else PhantomPlay.bombs(hypot(it.x-player.x,it.z-player.z), player.y-it.y, player.exp.toDouble())
            } == true
            else -> false
        }
        // On a Milk Spiral or in a Flame Dash: it goes where it was pointed, and all there is left
        // to decide is when to get off.
        if (tickRide(target?.takeIf { it.isOnline && it.world == player.world }, map)) return
        if(player.isFlying && kitId=="phantom" && !BotManager.oldKitPlay) {
            // Flown as the kit flies ([PhantomPlay]): at its own speed, over where the opponent will be
            // when a bomb comes down, out of reach of a hit from the ground; and back to ground it
            // knows is there before the tank is dry.
            val t = target?.takeIf { it.isOnline && it.world == player.world }
            val here = player.location
            val home = lastSafe?.takeIf { it.world == here.world && (t == null || PhantomPlay.headsHome(player.exp.toDouble())) }
            val dest = home?.clone() ?: t?.let {
                val at = it.location
                val over = Location(at.world, at.x + trackedVelocity.x * PhantomPlay.FALL_TICKS, at.y, at.z + trackedVelocity.z * PhantomPlay.FALL_TICKS)
                val roof = player.world.rayTraceBlocks(over.clone().add(0.0, 1.9, 0.0), Vector(0, 1, 0), PhantomPlay.HEIGHT + 2.0,
                    org.bukkit.FluidCollisionMode.NEVER, true)?.hitPosition?.y
                // (Room for the Phantom itself under whatever is overhead.)
                over.add(0.0, PhantomPlay.height(roof?.let { top -> top - at.y - 2.0 }), 0.0)
            }
            if (dest != null) {
                val dir = dest.toVector().subtract(here.toVector())
                val gap = dir.length()
                player.velocity = if (gap > 0.3) dir.multiply(minOf(PhantomPlay.SPEED, gap) / gap) else Vector()
                val (yaw, pitch) = lookAngles(player.eyeLocation, t?.let { aimPointOf(it) } ?: dest)
                turnTowards(yaw, pitch, d.turnSpeed)
                if (decide && t != null && home == null && shot == null) {
                    planAbility(t, here.distance(t.location))
                    // Already facing them in flight, so there is nothing to line up.
                    shot?.let { shot = null; it.fire() }
                }
                if (home != null && tick - phantomSaidTick > 60) { phantomSaidTick = tick; dbg("phantom: ${"%.0f".format(player.exp * 100)}% in the tank, back to the ground") }
            }
            setInputs(0f, 0f, false); return
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
            if (!nms.onGround() && canDoubleJump() && gapJump == null && climb == null && !recovering &&
                // (Not into what it is waiting outside of: [tickBlasts].)
                !carriesIntoBlast(aim.x - player.x, aim.z - player.z, DASH_LENGTH)) {
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
        perch?.let { if (tickPerch(it, map)) return }
        bridge?.let { if (it.stage > 0 && tickBridge(it, map)) return }
        crossing?.let { if (it.stage > 0 && tickCrossing(it, map)) return }

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
        // Something a kit threw is coming at it: a step to the side, taken by whatever it does next.
        tickThreats(game)
        // Something is winding up beside it (a Creeper has begun to hiss): away from it.
        if (tickBlasts(game, t, map)) return
        // Something is about to go off where it stands (its own Crafty Bomb, somebody's grenade): out
        // from under it before anything else, and no walking back in.
        if (tickDangers(t, decide, map)) return
        // A Monster Egg clone has come for it: that is dealt with before the fight goes on.
        if (swatClone(decide, map)) return
        // Holding a Rain Staff on somebody.
        rain?.let { if (tickRain(it, map)) return }
        // Two teams: where a player on this bot's side asked it to be comes before its own plan.
        if (obeyTeam(game, t, decide, map)) return
        when (states.state) {
            // Starving, running away only finishes the job: the one cure is to hit somebody.
            CombatState.FLEE -> if (t == null) wander(map) else if (player.foodLevel > 0) flee(t, decide, map) else fight(t, decide, map)
            CombatState.GET_ITEM -> itemGoal?.let { collect(it, map) } ?: wander(map)
            CombatState.GET_CRYSTAL -> crystalGoal?.let { claimCrystal(it, t, map) } ?: wander(map)
            // (Somebody it has in mind but cannot see is gone to look for, where it last saw them.)
            else -> if (t == null) wander(map) else if (seeks(t)) seek(t, map) else fight(t, decide, map)
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
        // Watching them: should they vanish, this is where it last knew them to be. (Watching is
        // having them in front of it with nothing in between, or close by, which [perceive] sees to;
        // a bot that knows where everybody is has them every tick.)
        if (t != null && at != null && !ItemService.hidden(t) && BotManager.allSeeing) sensed[t.uniqueId] = Sign(tick, at)
    }

    /**
     * Whether this bot can go for [p] at all: whether it knows of them ([SightTactics]). Somebody it
     * has seen, heard, hit or been hit by not long ago, once the moment it takes to react has passed.
     * Somebody in a Shadow Walk is not there to be seen, but a bot that was watching them, was just
     * hit by them or just hit them knows where to swing for a moment longer, as a player does. (It
     * used to know of everybody all the time, and to forget a Shadow Walker the instant they vanished.)
     */
    private fun knows(p: Player): Boolean {
        val hidden = ItemService.hidden(p)
        if (!hidden && BotManager.allSeeing) return true
        if (hidden && BotManager.oldItemPlay) return false
        // (Somebody is also unseen for two seconds after coming back to life, somewhere else on
        // the map: the last sign of them is from before, and is no guide to where they are now.)
        val sign = sensed[p.uniqueId]?.takeIf { it.at.world == p.world } ?: return false
        val since = tick - sign.tick
        if (!ShadowTactics.followed(sign.at.distance(p.location), since)) return false
        return if (hidden) ShadowTactics.knows(true, since, d)
            else SightTactics.knows(since, tick - (noticedAt[p.uniqueId] ?: -100_000L), d)
    }

    /** For the log: which of the things [knows] asks for is missing. */
    private fun whyUnknown(p: Player): String {
        if (ItemService.hidden(p)) return "cannot be seen"
        val sign = sensed[p.uniqueId]?.takeIf { it.at.world == p.world } ?: return "no sign of them"
        val since = tick - sign.tick
        val moved = sign.at.distance(p.location)
        if (!ShadowTactics.followed(moved, since)) return "%.0f blocks from the last sign of them, $since ticks ago".format(moved)
        if (since > SightTactics.memoryTicks(d)) return "no sign of them for $since ticks"
        return "new to it ${tick - (noticedAt[p.uniqueId] ?: -100_000L)} ticks ago"
    }

    private fun updateState(t: Player?) {
        val here = player.location
        val max = maxHealth(player)
        // Stuck on someone no route leads to: what is lying about the map may be the way to them.
        val needed = if (t != null && d.smartTargeting && besieging(t) && crossing == null && bridge == null && perch == null)
            siegePickup(t) else null
        if (needed != null && t != null) unreachableUntil[t.uniqueId] = maxOf(unreachableUntil[t.uniqueId] ?: 0L, tick + 100)
        itemWanted = needed != null
        val item = needed ?: nearestItem()
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
            targetDistance = t?.let { whereIs(it).distance(here) },
            itemDistance = item?.distance(here),
            itemEnemyDistance = item?.let { i -> opponents.filter { it.world == i.world }.minOfOrNull { whereIs(it).distance(i) } },
            recentDamage = healthLosses.sumOf { it.second } / max,
            attacksReady = kitAbilityIds().count { AbilityHints.of(it).attack && isReady(it) },
            itemNeeded = needed != null,
        )
        states.update(situation, tick)?.let(::stateChanged)
    }

    private fun itemKey(at: Location) = Triple(at.blockX, at.blockY, at.blockZ)

    /** The hotbar slot of the pickup this bot is carrying, if it has one. */
    private fun carriedSlot(): Int? = (0..8).firstOrNull { ItemService.kind(player.inventory.getItem(it)) != null }

    private fun nearestItem(): Location? {
        ignoredItems.entries.removeIf { it.value <= tick }
        // A Mystery Ability's copper tool is an item in hand: nothing else can be picked up with it.
        if ((0..8).any { dev.betrix.superSmashMobsBrawl.abilities.MysteryAbility.isBorrowed(player.inventory.getItem(it)) }) return null
        val here = player.location
        val slot = carriedSlot()
        val have = slot?.let { ItemService.kind(player.inventory.getItem(it)) }
        // With one in hand already, only the things that go straight onto the fighter can be taken.
        val full = slot != null
        if (BotManager.plainPickups) return ItemService.pickups(player)
            .filter { (kind, at) -> at.world == here.world && itemKey(at) !in ignoredItems && abs(at.y - here.y) < 12 &&
                (!full || kind.tool || kind == dev.betrix.superSmashMobsBrawl.items.ArenaItem.DIAMOND_HELMET) }
            .map { it.second }
            .minByOrNull { it.distanceSquared(here) }
            ?.takeIf { it.distanceSquared(here) < 900 }
        // Not whatever is nearest: every pickup shows what it is, and the rarer one is worth a longer
        // walk ([ItemChoice]). And what is in the hand is let go for something much better lying near,
        // by those who think that far, unless it is in use this moment (at the wheel, raining, mid-shot).
        val health = player.health / maxHealth(player)
        val held = have?.takeIf { d.smartTargeting && !player.isInsideVehicle && rain == null && shot == null && !ItemService.coolingDown(player) }
            ?.let { ItemChoice.worth(it, health) }
        return ItemService.pickups(player).asSequence()
            .filter { (_, at) -> at.world == here.world && itemKey(at) !in ignoredItems && abs(at.y - here.y) < 12 }
            .map { (kind, at) -> Triple(kind, at, at.distance(here)) }
            .filter { (kind, _, gap) -> gap < 30.0 && (!full || kind.tool || kind == dev.betrix.superSmashMobsBrawl.items.ArenaItem.DIAMOND_HELMET ||
                (held != null && kind != have && ItemChoice.swaps(held, ItemChoice.worth(kind, health), gap))) }
            .maxByOrNull { (kind, _, gap) -> ItemChoice.pull(ItemChoice.worth(kind, health), gap) }
            ?.second
    }

    /** The places the last failed route search got to, by column. */
    private var reachableIndex = HashMap<Long, MutableList<RouteNode>>()
    private var reachableIndexed: Set<RouteNode>? = null
    private fun columnKey(x: Int, z: Int) = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    private fun reachableByColumn(): Map<Long, List<RouteNode>> {
        if (reachableIndexed !== reachable) {
            val index = HashMap<Long, MutableList<RouteNode>>()
            for (n in reachable) index.getOrPut(columnKey(n.x, n.z)) { ArrayList(2) }.add(n)
            reachableIndex = index
            reachableIndexed = reachable
        }
        return reachableIndex
    }

    /** Can the bot walk to within arm's length of [at]? As good as the last search that found no way to someone. */
    private fun canWalkTo(at: Location): Boolean {
        val index = reachableByColumn()
        for (dx in -1..1) for (dz in -1..1) {
            if (index[columnKey(at.blockX + dx, at.blockZ + dz)]?.any { abs(it.y - at.y) < 2.0 } == true) return true
        }
        return false
    }

    /** The pickup being fetched is one the bot needs against an opponent it cannot get to. */
    private var itemWanted = false
    private var wantedKey: Triple<Int, Int, Int>? = null

    /**
     * The opponent sits where no route leads. A player in that spot looks at what is lying about:
     * something to get across with (a jetpack, a cloud, a teleporter), failing that something to
     * hit them with from here. This is the best such pickup the bot can walk to, if it beats what
     * it is carrying; whatever is in the hand and no use here is used up or let go to make room.
     */
    private fun siegePickup(t: Player): Location? {
        if (reachable.isEmpty() || tick - reachableTick > 1500) return null
        if ((0..8).any { dev.betrix.superSmashMobsBrawl.abilities.MysteryAbility.isBorrowed(player.inventory.getItem(it)) }) return null
        ignoredItems.entries.removeIf { it.value <= tick }
        val here = player.location
        fun worth(kind: dev.betrix.superSmashMobsBrawl.items.ArenaItem): Int =
            // A trampoline only goes up.
            if (kind == dev.betrix.superSmashMobsBrawl.items.ArenaItem.POCKET_TRAMPOLINE && t.y < here.y + 2.0) 0 else ItemTactics.siegeValue(kind)
        val slot = carriedSlot()
        val have = slot?.let { ItemService.kind(player.inventory.getItem(it)) }
        // Something that has not found its moment in forty seconds counts for less than a fresh idea.
        val haveWorth = when {
            have == null -> 0
            tick - carriedSince > 800 -> 1
            else -> maxOf(1, worth(have))
        }
        val best = ItemService.pickups(player)
            .filter { (kind, at) -> at.world == here.world && kind != have && itemKey(at) !in ignoredItems &&
                worth(kind) > maxOf(1, haveWorth) && canWalkTo(at) }
            .maxWithOrNull(compareBy<Pair<dev.betrix.superSmashMobsBrawl.items.ArenaItem, Location>>({ worth(it.first) }, { -it.second.distanceSquared(here) }))
            ?: return null
        if (slot != null && have != null) {
            if (ItemTactics.usedUpFreely(have)) spendItem(slot, have, null)
            if (ItemService.kind(player.inventory.getItem(slot)) != null) {
                player.inventory.setItem(slot, null)
                dbg("letting go of ${have.label}: ${best.first.label} is more use against someone it cannot get to")
                if (dev.betrix.superSmashMobsBrawl.items.ItemStats.on) dev.betrix.superSmashMobsBrawl.items.ItemStats.row(ItemService.arena(player)?.game, "discard", have, player, extra = "swapped for a better one")
            }
            carried = null
        }
        if (wantedKey != itemKey(best.second)) {
            wantedKey = itemKey(best.second)
            dbg("no way to ${t.name}: going for the ${best.first.label} at ${best.second.blockX},${best.second.blockY},${best.second.blockZ}")
        }
        return best.second
    }

    private fun ignoreItem(at: Location, ticks: Long) {
        ignoredItems[itemKey(at)] = tick + ticks
        itemGoal = null
    }

    /** Walks to a pickup; gives up on one it cannot reach or cannot use. */
    private fun collect(goal: Location, map: GameMapDef) {
        // Going for something better than what is in the hand: that is let go on getting there, not
        // on setting off (somebody else may get to it first). Eaten or drunk if that costs nothing.
        val slot = carriedSlot()
        if (slot != null && !itemWanted && !BotManager.plainPickups &&
            player.location.distanceSquared(goal) < ItemChoice.LET_GO_AT * ItemChoice.LET_GO_AT) {
            val have = ItemService.kind(player.inventory.getItem(slot))
            val found = ItemService.pickups(player).firstOrNull { itemKey(it.second) == itemKey(goal) }?.first
            val health = player.health / maxHealth(player)
            // (Still much the better of the two: what is in the hand may not be what it set off with.)
            if (have != null && found != null && found != have && !found.tool && found != ArenaItem.DIAMOND_HELMET &&
                ItemChoice.swaps(ItemChoice.worth(have, health), ItemChoice.worth(found, health), 0.0)) {
                if (ItemTactics.usedUpFreely(have)) spendItem(slot, have, null)
                if (ItemService.kind(player.inventory.getItem(slot)) != null) {
                    player.inventory.setItem(slot, null)
                    if (dev.betrix.superSmashMobsBrawl.items.ItemStats.on) dev.betrix.superSmashMobsBrawl.items.ItemStats.row(
                        ItemService.arena(player)?.game, "discard", have, player, extra = "swapped for a better one")
                }
                dbg("letting go of ${have.label} for the ${found.label} lying here")
                carried = null
            }
        }
        val near = player.location.distanceSquared(goal) < 4.0
        if (!near) itemNearSince = -1 else if (itemNearSince < 0) itemNearSince = tick
        // One it needs may be the whole map away.
        if ((near && tick - itemNearSince > 30) || states.ticksInState(tick) > (if (itemWanted) 700 else 300)) return ignoreItem(goal, 600)
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
            t?.let { val (yaw, pitch) = lookAngles(player.eyeLocation, aimPointOf(it)); lookTowards(yaw, pitch, d.turnSpeed) }
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
        val step = routeToward(goal, map, unreachable = unreachable)
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
        if(nms.onGround() && step.jump && player.exp<.999f &&
            KitService.getKitForPlayer(player)?.jumpPassive() is dev.betrix.superSmashMobsBrawl.passives.FlapPassive) {
            // A chicken that has just flown here has to get its flaps back before a tower, and
            // before a gap as well: a few jumps one after the other use the bar up, the game lets
            // it try a flap it cannot pay for, and the jump ends in the gap. Standing on the
            // ground gets them back in a couple of seconds. (Only a chicken: nobody else gets
            // more jumps by waiting, and a bar that is already full has no more to give.)
            val rise=dest.y-player.y
            val gap=hypot(dest.x-player.x,dest.z-player.z)
            // (Across a gap: the flap it takes and one to spare, two and one over a long one.)
            val need=if(rise>1.25) airJumpsFor(rise) else if(gap>7.0) 3 else if(gap>3.0) 2 else 0
            if(airJumpsLeft()<need) {
                if(tick-patientTick>40) dbg("out of flaps for ${if(rise>1.25) "a ${"%.0f".format(rise)} block rise" else "a ${"%.0f".format(gap)} block gap"}; waiting")
                patientTick=tick
                return Triple(0f,0f,false)
            }
        }
        // (A jump it looked at and would not take from where it stood is one it lines up for as well.)
        if(lineUp!=null && lineUp?.let { it.first==routeAt && it.second==step.node }!=true) lineUp=null
        val careful=step.long || lineUp!=null
        if(nms.onGround() && step.jump && (dest.y-player.y>1.25 || careful)) {
            // A high ledge is climbed straight up from the middle of the block below it; from
            // anywhere else the way up may be under the ledge's own overhang. And a long jump
            // was flown in the head from the middle of its block: the bot lines up there, the
            // way a player does before a jump that matters, so that it is that jump it makes.
            val from=routeAt
            if(from!=null && abs(from.y-player.y)<.6) {
                val off=hypot(from.x+.5-player.x,from.z+.5-player.z)
                // (For a second or so. The middle of a block is not always somewhere a body can get
                // to, with a post or a pane standing in it: then the jump is looked at from here.)
                val key=from to step.node
                if(liningUp!=key || tick-liningUpSince>200) { liningUp=key;liningUpSince=tick }
                if(off>(if(careful) JumpSim.LINE_UP-.03 else .25) && off<1.6 && tick-liningUpSince<=LINE_UP_TICKS) {
                    val input=inputToward(Location(player.world,from.x+.5,from.y,from.z+.5))
                    val gentle=if(off<.6) .45f else 1f
                    return Triple(input.first*gentle,input.second*gentle,false)
                }
            }
        }
        if(nms.onGround() && step.charge>0f) {
            // A leap that takes charging (a goat's, onto something far overhead): crouch here,
            // lined up, until there is enough of it. Three seconds at the most.
            // (All of it: the leap was planned with exactly this much, and one planned to the
            // last half block does not come off with a thirtieth less.)
            val have=chargeInHand()
            if(have!=null && have<step.charge-.001f) {
                wantCharge=tick
                patientTick=tick
                if(tick-chargeNoteTick>80) {
                    chargeNoteTick=tick
                    dbg("charging the leap to ${"%.1f %.1f %.1f".format(dest.x,dest.y,dest.z)}: ${"%.2f".format(have)} of ${"%.2f".format(step.charge)}")
                }
                return Triple(0f,0f,false)
            }
        }
        if(nms.onGround() && step.jump && dest.y-player.y>maxOf(mobility().up+1.6,mobility().jumps.rise+.6)) {
            // Far more than this kit can jump: the bot is not where the route was planned from
            // (it fell off the way there). Jumping at it anyway got nowhere; plan again from here.
            route.clear();routeSearch=null;nextRouteTick=tick
            return Triple(0f,0f,false)
        }
        if(nms.onGround() && step.jump) {
            val across=hypot(dest.x-player.x,dest.z-player.z)
            // (A long drop is steered the same way, so the bot comes down on the ledge and not past it.
            // So is a hop onto a block with nothing around it, or with nothing beyond it the way
            // the bot is going: run at that like any other step and the bot is off the far side
            // of it. A running hop up one block comes down two and a half blocks on.)
            if(across>1.6 || dest.y-player.y>1.25 || player.y-dest.y>3.0 || (across>.9 && narrow(dest)) || (across>.5 && !runOutPast(dest))) {
                if(!jumpIsStep(dest)) { startGapJump(Gap(player.location.distance(dest),dest),look=true,flown=step.long);return null }
                // Something low is right in front (a slab, the bottom of a stair): the game makes
                // a step of a jump at that, not a jump. So the bot walks onto it, and goes on
                // from there.
                val onto=inputToward(dest)
                return Triple(onto.first,onto.second,false)
            }
        }
        val input=inputToward(dest)
        // Bumping into something on a walking step, a hop gets over it (a lip, a post). Not on a
        // step down, where there is nothing to get over and a running hop sails past the block
        // below; and not onto a perch with nothing round it, for the same reason. (A bot brushed
        // a leaf beside a branch in a tree, hopped, and came down two blocks past its end.)
        val bumped=nms.horizontalCollision && step.node.y>player.y-.3 && !narrow(dest)
        val jump=nms.onGround() && (step.jump || step.node.y>player.y+.6 || bumped)
        if(jump)noteJump("route step")
        else if(nms.onGround() && input.first>.8f) dashLane()?.takeUnless { carriesIntoBlast(it.x-player.x,it.z-player.z,DASH_LENGTH) }?.let { lane ->
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
    private fun applyMovement(step: RouteStep?, f: Float, s: Float, hop: Boolean, map: GameMapDef): Pair<Float, Float> {
        var forward=f
        var strafe=s
        var jump=hop
        // Stepping out of the way of something coming at it comes before where it was going ([tickThreats]).
        dodgeTo?.takeIf { tick < dodgeUntil && nms.onGround() && it.world == player.world }?.let { spot ->
            if (hypot(spot.x - player.x, spot.z - player.z) > 0.25) {
                val input = inputToward(spot)
                forward = input.first; strafe = input.second; jump = false
            }
        }
        // Not into a wall that hurts whoever touches it: round the end of it, or not at all ([roundWalls]).
        // (A jump is left as it was planned: turned aside in the air, it misses what it was for.)
        if (walls.isNotEmpty() && tick - wallsTick <= 1 && !jump && nms.onGround() && gapJump == null && climb == null) {
            val input = roundWalls(forward, strafe)
            forward = input.first; strafe = input.second
        }
        // Waiting outside something that is winding up ([tickBlasts]): whatever it is doing, not a step back
        // toward it, and not a running jump that comes down beside it (in the air there is no stopping).
        if (blastHoldTick == tick && nms.onGround() && gapJump == null && climb == null) moveDir(forward, strafe)?.let { (mx, mz) ->
            val here = player.location
            if (jump && carriesIntoBlast(mx, mz, BlastTactics.HOP)) jump = false
            if (blastsHeld.any { b -> BlastTactics.walksIn(hypot(here.x - b.at.x, here.z - b.at.z),
                    hypot(here.x + mx - b.at.x, here.z + mz - b.at.z), b.radius) }) { forward = 0f; strafe = 0f; jump = false }
        }
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
                    if(from!=null) blockedEdges[from to to]=tick+missedJumpRest(from,to) else blockedSteps[to]=tick+200
                    forgetTerrain(g.landing.blockX,g.landing.blockZ,map)
                }
                route.clear();routeSearch=null;nextRouteTick=tick
            }
            dbg("gap jump finished (reached landing=$landed${if(beside) ", came down beside it" else ""})" +
                (if (g.planned != null) "; off the jump in the head by at most %.3f across, %.3f up".format(g.plannedOff, g.plannedOffUp) else "") +
                (if (g.flown != null) "; off the flight in the head by at most %.3f across, %.3f up".format(g.flownOff, g.flownOffUp) else ""))
            gapJump = null
            return false
        }
        // Facing the landing, all the way round at once if need be: a running jump goes the way
        // the bot faces, which way it faces decides whether a push in the air is a sprinting one,
        // and the jump was flown in the head facing its landing every tick.
        val (yaw, _) = lookAngles(player.eyeLocation, g.landing)
        turnTowards(yaw, 0f, 180f)
        if (!g.leftGround && g.stepOff > 0.0) {
            // Walk off the edge; no faster than lands on the ledge below.
            setInputs((g.stepOff / 0.216).toFloat().coerceIn(0.25f, 1f), 0f, false)
            return true
        }
        if (!g.leftGround && !g.high && age <= 6 && nms.onGround() && nms.sinceTimedJump() <= JUMP_PUSH_NANOS) {
            // Straight after another jump the game gives no push to this one (see startGapJump):
            // those few ticks are waited out on the ground.
            setInputs(0f, 0f, false)
            return true
        }
        if (!g.leftGround) {
            // Hold jump through the vanilla jump delay instead of treating a missed
            // one-tick input as a successful landing at our original position.
            // (For a high ledge the jump is straight up: running at it puts the bot under
            // the ledge's lip, where it only hits its head.)
            noteJump("gap jump")
            setInputs(if (g.high) 0f else 1f, 0f, true)
            if (BotManager.debug && nms.onGround() && g.stepOff == 0.0) {
                // Debug: the jump as it is flown in the head from this very tick, to hold what follows up against.
                val seen = ArrayList<JumpSim.Flight>()
                comesOff(g, canDoubleJump(), seen)
                g.planned = seen
                g.plannedTick = tick
                if (BotManager.debugFlights) player.location.let { at -> nms.deltaMovement.let { v ->
                    dbg("pushing off: at %.3f %.3f %.3f going %.3f %.3f %.3f yaw %.2f sprint=%s speed=%.4f high=%s last jump %d ms ago, toward %.2f %.2f %.2f".format(
                        at.x, at.y, at.z, v.x, v.y, v.z, nms.yRot, nms.isSprinting, nms.speed, g.high, nms.sinceTimedJump() / 1_000_000,
                        g.landing.x, g.landing.y, g.landing.z)) } }
            }
            return true
        }
        // In the air: steer so we coast onto the landing spot instead of past it. Below the
        // lip of a high ledge, hold the take-off column instead and go across once above it.
        val rising = g.high && player.location.y < g.landing.y - 0.15
        val steerAt = if (rising) g.takeoff.clone().also { it.y = player.location.y - 1.0 } else g.landing
        val (f, s) = steerTo(steerAt)
        setInputs(f, s, false)
        if (BotManager.debug && !g.doubleJumped) g.planned?.let { planned ->
            val at = player.location
            val v = nms.deltaMovement
            val n = (tick - g.plannedTick).toInt()
            val head = planned.getOrNull(n - 1)
            if (head != null) {
                g.plannedOff = maxOf(g.plannedOff, hypot(head.x - at.x, head.z - at.z))
                g.plannedOffUp = maxOf(g.plannedOffUp, abs(at.y - head.y))
            }
            if (BotManager.debugFlights) dbg("jump $n: at %.3f %.3f %.3f going %.3f %.3f %.3f yaw %.1f keys %.2f %.2f%s; in the head %s".format(at.x, at.y, at.z, v.x, v.y, v.z,
                nms.yRot, f, s, if (nms.isSprinting) " sprint" else "",
                head?.let { "%.3f %.3f %.3f (off by %.3f across, %.3f up)".format(it.x, it.y, it.z, hypot(it.x - at.x, it.z - at.z), at.y - it.y) } ?: "nothing more"))
        }
        if (BotManager.debug) g.flown?.let { flown ->
            val at = player.location
            val v = nms.deltaMovement
            val n = (tick - g.flownTick).toInt()
            val head = flown.getOrNull(n - 1)
            if (head != null) {
                g.flownOff = maxOf(g.flownOff, hypot(head.x - at.x, head.z - at.z))
                g.flownOffUp = maxOf(g.flownOffUp, abs(at.y - head.y))
            }
            if (BotManager.debugFlights) dbg("flight $n: at %.3f %.3f %.3f going %.3f %.3f %.3f yaw %.1f keys %.2f %.2f%s; in the head %s".format(at.x, at.y, at.z, v.x, v.y, v.z,
                nms.yRot, f, s, if (nms.isSprinting) " sprint" else "",
                head?.let { "%.3f %.3f %.3f (off by %.3f across, %.3f up)".format(it.x, it.y, it.z, hypot(it.x - at.x, it.z - at.z), at.y - it.y) } ?: "it was down"))
        }
        // Double jump only if a plain jump won't make it: from the top of the arc on, the jump
        // as it is going is flown on in the head every tick, and the air jump is for when that
        // no longer comes down on the landing. (A guess at it used to decide: so far to go, so
        // fast, so long to fall. It left out what steering makes up, so the air jump went on
        // hops the plain jump was making anyway.) Kits with more than one air jump (a chicken's
        // flaps, a spider that touched a wall) get to use the next one the same way.
        if (age >= 3 && !nms.onGround() && nms.deltaMovement.y < 0.05 && canDoubleJump() && tick - lastDoubleJumpTick > 8) {
            if (!landsAsItGoes(g)) {
                g.doubleJumped = true
                aimedDoubleJump(g.landing, "gap jump")
                // The controls of this tick were set for the speed it had, and the air jump has
                // just replaced that. Set again for the speed it has now, which is how the jump
                // was flown in the head. (A tick of the old ones put a wolf four centimetres
                // ahead of the flight it had picked: enough to clip the corner that flight
                // cleared, lose its speed across on it and fall.)
                val (f2, s2) = steerTo(steerAt)
                setInputs(f2, s2, false)
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
            if (BotManager.debug) dbg("gap jump falling short, recovering" +
                // (Blown away since the route was planned, or never where the planner thought?)
                if (hasSolidBelow(g.landing.clone().add(0.0, 0.3, 0.0), 2)) "" else ": what it was jumping onto is not there")
            gapJump = null
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ two teams

    /** The fighters on this bot's own side who are in the fight right now, in a match of two teams. */
    private fun teammates(game: BrawlMinigame): List<Player> {
        val side = game.sideOf(player) ?: return emptyList()
        return game.allPlayers().mapNotNull { it.player }.filter {
            it != player && it.world == player.world && it.gameMode == GameMode.SURVIVAL &&
                game.sideOf(it) == side && !game.respawnManager.isRespawning(it)
        }
    }

    /** Where a teammate asked this bot to be: with them (COME), or at a spot (HOLD). */
    private fun teamAnchor(order: TeamTalk.Standing?): Location? = when (order?.ask) {
        TeamTalk.Ask.COME -> org.bukkit.Bukkit.getPlayer(order.leader)
            ?.takeIf { it.world == player.world && it.gameMode == GameMode.SURVIVAL }?.let { leader ->
                // Where they last had something under their feet: a player in mid jump (or mid fall)
                // is not at a place anybody can walk to, and is not there a moment later either.
                val at = leader.location
                if (teamLeaderAt?.world != at.world || hasSolidBelow(at.clone().add(0.0, -0.05, 0.0), 2)) teamLeaderAt = at
                teamLeaderAt
            }
        TeamTalk.Ask.HOLD -> order.spot?.let { Location(player.world, it.first, it.second, it.third) }
        else -> null
    }

    /**
     * Two teams: how much each opponent is worth going after for the side's sake, in blocks of
     * distance. Two on one wins fights, so somebody a teammate is fighting, or who is on a
     * teammate (a player above all: the bots are there to play with them), comes before somebody
     * merely closer. A bot asked to stay with a player or at a spot fights what is there.
     */
    private fun teamPulls(game: BrawlMinigame, candidates: List<Player>): Map<java.util.UUID, Double> {
        if (game.sides.isEmpty()) return emptyMap()
        val mates = teammates(game)
        val anchor = teamAnchor(teamOrderNow)
        if (mates.isEmpty() && anchor == null) return emptyMap()
        val hurting = mates.associateWith { CombatLogService.recentAttackers(it, 4000) }
        return candidates.associate { foe ->
            val hitBy = CombatLogService.recentAttackers(foe, 4000)
            var pull = 0.0
            for (mate in mates) {
                val person = !BotManager.isBot(mate) || mate.uniqueId in BotManager.standIns
                if (mate.uniqueId in hitBy) pull += if (person) 8.0 else 4.0
                if (foe.uniqueId in hurting.getValue(mate)) pull += if (person) 6.0 else 4.0
                if (BotManager.brainOf(mate)?.targetId == foe.uniqueId) pull += 2.0
            }
            pull = pull.coerceAtMost(14.0)
            if (anchor != null) {
                val far = foe.location.distance(anchor)
                pull += if (far < 10.0) 12.0 else if (far > 24.0) -20.0 else 0.0
            }
            foe.uniqueId to pull
        }
    }

    /**
     * Two teams: does what a player on this bot's side asked of it ([TeamTalk]) where that means
     * going somewhere rather than fighting on. True when this tick's movement was decided here.
     *
     * Asked to come, it goes to the player and then stays within a few blocks of them, fighting
     * whoever turns up there; asked to hold, the same around a spot; asked to fall back, it runs.
     * Somebody right on top of it is dealt with first: nobody walks away from a fight at arm's length.
     */
    private fun obeyTeam(game: BrawlMinigame, t: Player?, decide: Boolean, map: GameMapDef): Boolean {
        val order = teamOrderNow ?: return false
        val here = player.location
        val pressed = t != null && t.location.distanceSquared(here) < 16.0
        if (order.ask == TeamTalk.Ask.RETREAT) {
            // Running needs somebody to run from; starving, running only finishes the job.
            if (t == null || player.foodLevel <= 0) return false
            flee(t, decide, map)
            return true
        }
        if (tick < teamUnreachableUntil) return false
        val goal = teamAnchor(order) ?: return false
        val gap = goal.distance(here)
        val near = if (order.ask == TeamTalk.Ask.HOLD) 3.0 else TEAM_NEAR
        // There already, or busy with somebody who is at the place it is meant to be.
        if (gap <= near || pressed) return false
        if (gap <= TEAM_FAR && t != null && t.location.distance(goal) <= TEAM_FAR) return false
        if (tick - teamTravelTick > 60) dbg("team: ${order.ask.name.lowercase()}, ${"%.0f".format(gap)} blocks to go")
        teamTravelTick = tick
        travel(goal, map) {
            // One search coming up empty may be the moment it was made in (the player was moving, the
            // bot was in the air): it tries again shortly. Twice running, there is no way, and it
            // says so: once per asking, not every time it has another look.
            if (order.until != teamMissFor) { teamMissFor = order.until; teamMisses = 0 }
            teamMisses++
            if (teamMisses < 2) teamUnreachableUntil = tick + 30
            else {
                dbg("team: no way to where it was asked to be, fighting from here")
                if (order.until != teamCantFor) {
                    teamCantFor = order.until
                    dev.betrix.superSmashMobsBrawl.services.BotConversationService.cannotReach(game, player.uniqueId)
                }
                teamUnreachableUntil = tick + 200
            }
        }
        return true
    }

    // ------------------------------------------------------------------ targeting

    private fun chooseTarget(game: BrawlMinigame) {
        val me = player.location
        // Two teams: the opponent a player on this bot's side sent it after ("get cqsey") comes first,
        // for as long as they are in the match.
        val order = teamOrderNow
        if (order?.ask == TeamTalk.Ask.FOCUS && game.allPlayers().none { it.uniqueId == order.target && it.isOnline &&
                !game.connectionManager.isDisconnected(it) && (game.teamManager.findTeamOf(it)?.metadata?.get("stocks") as? Int ?: 0) > 0 }) {
            dbg("team: the one it was sent after is out of the match")
            dev.betrix.superSmashMobsBrawl.services.BotConversationService.orderDone(game, player.uniqueId)
            teamOrderNow = null
        }
        // Whoever this bot told the lobby it is going after ("lets team on him").
        val wanted = teamOrderNow?.takeIf { it.ask == TeamTalk.Ask.FOCUS }?.target
            ?: dev.betrix.superSmashMobsBrawl.services.BotConversationService.grudge(game, player.uniqueId)
        // (What it sees and hears is kept up either way, so that the two can be compared: a bot
        // that knows where everybody is takes no notice of it.)
        perceive(game)
        if (!BotManager.allSeeing) {
            // Somebody it was told to go for, or swore to, it need not have seen for itself: it was told where they are.
            if (wanted != null) game.allPlayers().firstOrNull { it.uniqueId == wanted }?.player
                ?.takeIf { it.world == player.world && !ItemService.hidden(it) && !game.respawnManager.isRespawning(it) }
                ?.let { notice(it, "told of them", atOnce = true) }
        }
        val alive =
            game.allPlayers().mapNotNull { it.player }.filter {
                it != player &&
                    it.world == player.world &&
                    it.gameMode == GameMode.SURVIVAL && knows(it) &&
                    !game.arePlayersOnSameTeam(player, it) &&
                    !game.respawnManager.isRespawning(it)
            }
        opponents = alive
        if (!BotManager.allSeeing) {
            if (alive.isNotEmpty()) nobodySince = -1
            else if (nobodySince < 0) { nobodySince = tick; nobodyYaw = nms.yRot }
            // Nobody in mind and a look round has found nobody; or the only ones in mind have not
            // been seen or heard for a while (round a corner, across the map): a look at the compass.
            val lost = if (alive.isEmpty()) tick - nobodySince >= SightTactics.COMPASS_AFTER else tick - lastPerceived >= SightTactics.COMPASS_REST
            if (lost && tick >= compassAt) {
                compassAt = tick + SightTactics.COMPASS_REST
                readCompass(game)
            }
        }
        // Prefer someone the bot can get to; with nobody else left, an unreachable opponent is
        // still the one to deal with.
        val candidates = alive.filter { !besieging(it) && standingOff(it) == null }.ifEmpty { alive }
        // Only the smarter selector reads edges and attackers, so only it pays for them.
        val attackers = if (d.smartTargeting) CombatLogService.recentAttackers(player, 4000) else emptySet()
        val pulls = teamPulls(game, candidates)
        val chosen = selector.choose(
            candidates.map {
                // (As far off as it takes them to be: somebody it cannot see is where it last saw them.)
                val at = whereIs(it)
                TargetCandidate(it.uniqueId, at.distance(me), it.health,
                    d.smartTargeting && !hasGroundNear(at, 2), it.uniqueId in attackers, it.uniqueId == wanted,
                    pulls[it.uniqueId] ?: 0.0)
            },
            target?.uniqueId,
        )
        val next = candidates.find { it.uniqueId == chosen }
        // (For the log: why somebody it was after, who is still there to be fought, is no longer the one.)
        if (BotManager.debug) target?.takeIf { it != next && it.isOnline && it.world == player.world && it.gameMode == GameMode.SURVIVAL &&
                !game.respawnManager.isRespawning(it) }?.let { old ->
            val why = when {
                old !in alive -> "not in mind (${whyUnknown(old)})"
                old !in candidates -> if (besieging(old)) "no way to them" else "keeping off them"
                else -> "somebody better"
            }
            dbg("leaves ${old.name}, %.0f blocks off, for ${next?.name ?: "nobody"}%s: $why".format(old.location.distance(me),
                next?.let { ", %.0f blocks off".format(it.location.distance(me)) } ?: ""))
        }
        if(next != target) {
            route.clear();routeSearch=null;routeGoal=null;nextRouteTick=tick
        }
        if (next != null && ItemService.hidden(next) && tick - unseenSaidTick > 40) {
            unseenSaidTick = tick
            dbg("${next.name} cannot be seen: going for where they were ${tick - (sensed[next.uniqueId]?.tick ?: tick)} ticks ago")
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
        // A Sheep with no way over and no shot from here makes itself a place to shoot from.
        if (decide && ((sieging && (!sees || dist > 36.0)) || (BotManager.forcePerch && t.y < player.y - 3 && dist > 10)) &&
            startPerch(t, dist)) return
        // A Snowman with no way over lays one: off to the ledge its ice bridge starts from.
        if (decide && sieging && bridge == null) startBridge(t, map)
        bridge?.let { if (it.stage == 0 && approachBridge(it, t, map)) return }
        // Anyone else with no way over looks for one of their own: a drop in under an overhang,
        // or whatever the item in their hand makes possible.
        if (decide && sieging && bridge == null && crossing == null) startCrossing(t, map)
        crossing?.let { if (it.stage == 0 && approachCrossing(it, t, map)) return }
        // Or the long jump onto ground the routes do not go to.
        if (decide && sieging && bridge == null && crossing == null && leap == null) startLeap(t, map)
        leap?.let { if (tickLeap(it, t, map)) return }
        if (sieging && d.smartTargeting) {
            // (Hurt by them, that is: going hungry is no reason to walk away from the only meal.)
            if (tick >= withdrawUntil && blows.any { it.by == t.uniqueId && tick - it.tick <= 40 } && !canHarmAt(dist)) {
                withdrawUntil = tick + 160
                dbg("cannot reach or hit ${t.name}; backing out of their range")
            }
            if (tick < withdrawUntil) return withdraw(t, map)
        }
        // (Somebody on their way through the air is kept after, floor under them or not: [ReachTactics].)
        val passing = ReachTactics.passing(airTicks(t))
        val step = if(!sieging && (dist>reach || !sees)) routeToward(chaseGoal(t,dist),map,passing,t.location) else null

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
            // (A look that eases onto its mark trails behind somebody running past: it is pointed
            // that much ahead of them, by as much as the player knows their own hand.)
            val ahead = if (BotManager.stiffLook) 0.0 else LookTactics.ahead(d)
            val onto = if (holdUntilTick >= 0) lead(holdAim?.invoke() ?: aimPoint, dist, holdSpeed)
                else aimPoint.clone().add(trackedVelocity.x * ahead, trackedVelocity.y * ahead * 0.3, trackedVelocity.z * ahead)
            val (yaw, pitch) = lookAngles(player.eyeLocation, onto)
            lookTowards(yaw + yawError, pitch + pitchError, d.turnSpeed)
        } else lookAlongRoute(step)

        // Abilities.
        if (decide && sees && shot == null && holdUntilTick < 0 && bowReleaseTick==0L && comboQueue.isEmpty() && rng.nextDouble() < d.abilityChance &&
            !(BotManager.forcePerch && perchesDone == 0)) {
            planAbility(t, dist)
        }

        // A Wither Skeleton with a skull on its way to somebody keeps out of what is about to go off
        // there: its blast reaches its own player like anybody. (A bot used to run at the opponent it
        // was guiding the skull onto, and took a third of all the damage its skulls ever did.)
        val keepsBack = !BotManager.oldKitPlay && skullComing(t)
        if (keepsBack) standOff(t, map)
        if (!keepsBack && sieging && trySiegeJump(t, dist, map)) return
        if (!sieging) prowlGoal = null
        // With no way to them, keep moving around them looking for one instead of standing and staring.
        if (!keepsBack && !(sieging && dist > reach + 0.6 && prowl(t, dist, sees, map))) {
            var forward=0f
            var strafe=0f
            var jump=false
            if(step!=null) {
                val (f,s,j)=stepInputs(step) ?: return
                forward=f;strafe=s;jump=j
            } else if(dist>reach-.4 && sees && (dist<=reach+1.5 || trapOf(t)==null)) {
                // (Not at somebody sitting where they have killed this bot before: seeing them
                // from across their own ground is no reason to walk onto it.)
                val input=inputToward(t.location);forward=input.first;strafe=input.second
            } else if(d.strafes && dist<reach+1) {
                if(tick>=nextStrafeFlip){strafeDir=if(rng.nextBoolean())1f else -1f;nextStrafeFlip=tick+20}
                strafe=strafeDir*.65f
            }
            // Coming at somebody who is facing it, from further off than arm's length: not in a
            // straight line ([WeaveTactics]). (Never off a ledge for it: with no floor that way, straight on.)
            if (!jump && (forward != 0f || strafe != 0f) && weaves(t, dist, sees)) {
                val (f2, s2) = WeaveTactics.turned(forward, strafe, WeaveTactics.ANGLE * weaveSide)
                if (groundAhead(f2, s2, 1.5)) { forward = f2; strafe = s2 }
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
    /** Since when the bot has stood where it can shoot at an opponent it cannot get to, and until when it is off looking for a way across instead. */
    private var holdSince = -1L
    private var lookRoundUntil = 0L

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
        // Somebody sitting where they have killed this bot before is watched from outside their reach.
        val trap = trapOf(t)
        val zones = if (trap != null) dangerZones(true) else emptyList()
        fun clearOf(at: Location) = zones.isEmpty() ||
            RouteNode(at.blockX, (at.y * 16).roundToInt(), at.blockZ).let { n -> zones.none { it.bars(n) } }
        var goal = prowlGoal
        if (goal != null && (tick >= prowlUntil || goal.world != here.world ||
                (hypot(goal.x - here.x, goal.z - here.z) < 1.2 && abs(goal.y - here.y) < 1.5))) {
            // Arrived (or it is taking too long): have a look from here before moving on.
            prowlGoal = null; goal = null
            // Watching somebody who will not come out is mostly standing and waiting.
            prowlRestUntil = tick + if (trap != null) rng.nextInt(60, 180) else rng.nextInt(8, 26)
            route.clear(); routeSearch = null; nextRouteTick = tick
        }
        if (goal == null) {
            // (No standing about where somebody who has killed it from their spot can see it.)
            if (tick < prowlRestUntil && clearOf(here)) return false
            // With something that reaches them (a ranged attack, an item to throw), the place to
            // be is one that sees them: go there and shoot for a while, then take a look round
            // for a way across (a jump from a new angle) before coming back.
            val range = if (d.smartTargeting && tick >= lookRoundUntil) sightRange() else 0.0
            if (range > 0.0 && sees && dist <= range && clearOf(here)) {
                if (holdSince < 0) holdSince = tick
                if (tick - holdSince < 120) { prowlRestUntil = tick + 10; return false }
                // Long enough in one place: a look round for a way across, then back to shooting.
                holdSince = -1
                lookRoundUntil = tick + 200
            } else if (range > 0.0) {
                holdSince = -1
                val spot = vantage(t, range, trap?.takeIf { tick < it.keepOutUntil })
                    ?: standOff(t).takeIf { carriedSlot()?.let { ItemService.kind(player.inventory.getItem(it)) } == ArenaItem.MISSILE_BATTERY }
                        ?.takeIf(::clearOf)
                if (spot != null && (hypot(spot.x - here.x, spot.z - here.z) > 1.5 || abs(spot.y - here.y) > 1.5)) {
                    prowlGoal = spot
                    prowlUntil = tick + 300
                    route.clear(); routeSearch = null; nextRouteTick = tick
                    dbg("no way to ${t.name}; going to ${spot.blockX},${spot.blockY},${spot.blockZ} for a shot at them")
                    goal = spot
                }
            }
        }
        if (goal == null) {
            val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
            // Around them, as close as the ground comes; around a trap, out of their sight or out of their reach.
            val at = trap?.camp?.let { Location(here.world, it.x, it.y, it.z) } ?: t.location
            val reach = trap?.let { it.keepOut + 4.0 } ?: 8.0
            for (attempt in 1..8) {
                val angle = rng.nextDouble() * Math.PI * 2
                val radius = 2.5 + rng.nextDouble() * reach
                // Ground around their height first, else whatever is underneath.
                val probe = Location(at.world, at.x + cos(angle) * radius, at.y + rng.nextInt(-1, 4), at.z + sin(angle) * radius)
                val node = nav.nearest(probe, 14.0, 3.0) ?: continue
                val spot = Location(at.world, node.x + .5, node.y, node.z + .5)
                // Somewhere new, not the block the bot is already on.
                if (hypot(spot.x - here.x, spot.z - here.z) < 3.0 && abs(spot.y - here.y) < 2.0) continue
                if (zones.any { it.bars(node) }) continue
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
        if (wayBarred(t, "jump")) return false
        nextSiegeJumpTick = tick + 70
        lastWay = "jump"; lastWayTick = tick
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
    private fun jumpReaches(p: Location, here: Location = player.location): Boolean {
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
        // The kit's own abilities, then any tool a Mystery Ability handed out.
        val own = dataService.getKit(kit.id)?.abilities?.map { it.id } ?: emptyList()
        return own + (kit.abilityIds() - own.toSet())
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
        val old = BotManager.oldKitPlay
        val situation by lazy { fightSituation(t, dist) }
        val options =
            kitAbilityIds().filter { id ->
                val hint = AbilityHints.of(id)
                hint.attack && isReady(id) && dist in hint.minRange..hint.maxRange &&
                    (!hint.recovery || (hasGroundNear(t.location,4) && !isOverVoid(lastMap ?: return@filter false))) &&
                    !(id=="phantom_flight" && player.isFlying) &&
                    // (Nor taken off on a nearly empty tank, to come down again before getting anywhere.)
                    !(id=="phantom_flight" && !BotManager.oldKitPlay && player.exp < PhantomPlay.TAKE_OFF) &&
                    !(id=="slime_rocket" && player.exp<.2f) &&
                    // (Waiting outside something that is winding up: nothing that would carry it at them and so back in.)
                    !(hint.moves > 0 && carriesIntoBlast(t.x - player.x, t.z - player.z, hint.moves)) &&
                    // What carries, throws or swaps its player is pressed only where that leaves them on the map.
                    (old || kitMoveIsSafe(id, t, dist) { situation })
            }
        if (options.isEmpty() || !player.hasLineOfSight(t)) return
        val choice = AbilityPlanner.choose(
            options.map { id ->
                // (A Blink is looked at for where it comes out, exactly: not for floor all the way there, which it has no need of.)
                val hint = AbilityHints.of(id).let { if (!old && id == "blink") it.copy(moves = 0.0) else it }
                AbilityOption(id, hint, dataService.getAbility(id)?.cooldown ?: 3.0)
            },
            situation, d, kitBehavior(),
        ) ?: return
        val aim: (() -> Location?)? = if (old) null else when (choice.id) {
            "guided_wither_skull" -> { -> skullAim(t) }
            "blink" -> { -> blinkAim(t) }
            else -> null
        }
        val speed = if (!old && choice.id == "guided_wither_skull") SkullPlay.speed(dist, SkullPlay.SHORTEST + 1) else choice.hint.speed
        shot = Shot(choice.id, speed, false, tick, aim) { useAbility(choice.id, choice.hint) }
    }

    // ------------------------------------------------------------------ abilities that move their player

    private fun kitAbility(id: String) = KitService.getKitForPlayer(player)?.getAbility(id)

    /**
     * Is this a moment at which [id] leaves the bot on the map ([SkullPlay] and the objects after
     * it)? True for every ability that does not carry, throw or swap its player.
     */
    private fun kitMoveIsSafe(id: String, t: Player, dist: Double, situation: () -> FightSituation): Boolean = when (id) {
        "guided_wither_skull" -> skullIsClear(t)
        "wither_image" -> {
            val image = witherImage()
            // None out: the press sends one, along the floor at them (off an edge it is only thrown away).
            if (image == null) situation().groundAhead >= minOf(dist, 5.0)
            // One out: the press swaps places with it. (Not with a skull of its own on its way to
            // them: one bot swapped itself into the middle of its own blast.)
            else imageStands(image) && ImagePlay.swapsToFight(dist, image.location.distance(t.location)) && !skullComing(t)
        }
        "magma_blast" -> recoilHasFloor(t)
        "blink" -> blinkAim(t) != null
        else -> true
    }

    // -- Wither Skeleton

    private fun ownSkulls(): List<Location> =
        (kitAbility("guided_wither_skull") as? dev.betrix.superSmashMobsBrawl.abilities.GuidedWitherSkullAbility)?.skullsInFlight() ?: emptyList()

    /** A skull of the bot's own is on its way to [t]: not past them yet, so it may go off on them at any moment. */
    private fun skullComing(t: Player): Boolean {
        val skulls = ownSkulls()
        if (skulls.isEmpty()) return false
        val here = player.location
        val gap = here.distance(t.location)
        return skulls.any { it.world == here.world && it.distance(here) < gap + 1.5 }
    }

    /**
     * Where a skull is pointed: at the floor under them when that can be seen from here, at them
     * when not. Its blast reaches six blocks, so it has no need to meet the body, and a skull that
     * misses the body flies on to wherever, while one aimed at the floor goes off where they were.
     */
    private fun skullAim(t: Player): Location {
        val eye = player.eyeLocation
        val feet = t.location
        val floor = floorUnder(feet, 3.0) ?: return aimPointOf(t)
        // (Not from where an unsteady hand would put it into the floor short of them, inside its own blast.)
        if (!SkullPlay.aimsAtFloor(hypot(feet.x - eye.x, feet.z - eye.z), eye.y - floor.y, d.aimErrorDegrees * 0.5)) return aimPointOf(t)
        val spot = Location(feet.world, feet.x, floor.y + 0.05, feet.z)
        val line = spot.toVector().subtract(eye.toVector())
        val length = line.length()
        if (length < 1.5) return aimPointOf(t)
        val blocked = player.world.rayTraceBlocks(eye, line.multiply(1 / length), length - 0.4, org.bukkit.FluidCollisionMode.NEVER, true)
        return if (blocked == null) spot else aimPointOf(t)
    }

    /**
     * Everything near the bot that an ability of its own goes off on: opponents, and what
     * opponents have summoned (somebody else's Wither Image stands right on whoever it is after).
     */
    private fun hostileBodies(radius: Double): List<org.bukkit.entity.LivingEntity> {
        val game = minigameService.getMinigameForPlayer(player) ?: return emptyList()
        return player.world.getNearbyLivingEntities(player.location, radius).filter { e ->
            if (e == player || !e.isValid || e.isDead) false
            else if (e is Player) e.gameMode == GameMode.SURVIVAL && minigameService.getMinigameForPlayer(e) === game &&
                !game.respawnManager.isRespawning(e) && !game.arePlayersOnSameTeam(player, e)
            else dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons.owners[e.uniqueId]
                ?.let { owner -> owner != player && !game.arePlayersOnSameTeam(player, owner) } == true
        }
    }

    /**
     * Whether a skull sent at [t] now would go off clear of the bot itself ([SkullPlay]): along
     * the line to where it is to be pointed, or (as it is let go) along [look], the way the bot
     * is in fact looking.
     */
    private fun skullIsClear(t: Player, look: Vector? = null): Boolean {
        val eye = player.eyeLocation
        val here = player.location
        val at = t.location
        // (How fast they are coming at it now: somebody in the middle of a dash meets the skull much nearer.)
        val to = at.toVector().subtract(here.toVector())
        val gap = to.length()
        val closing = if (gap > 1e-6 && t.uniqueId == trackedId) -trackedVelocity.dot(to) / gap else 0.0
        if (!SkullPlay.clearToSend(to.x, to.y, to.z, closing)) return false
        val line = look?.clone() ?: skullAim(t).toVector().subtract(eye.toVector())
        val length = line.length()
        if (length < 1e-6) return false
        line.multiply(1 / length)
        // Nobody else near in its way, and nothing of theirs: a skull goes off on the first body it
        // meets. (One in seven still went off in its own player's face with the opponent ten and
        // twenty blocks away: on the other Wither Skeleton's image, come to hit it.)
        if (hostileBodies(SkullPlay.CROWD).any { o ->
                if (o == t) return@any false
                val to = o.location.toVector().add(Vector(0.0, 0.9, 0.0)).subtract(eye.toVector())
                val along = to.dot(line)
                to.length() < SkullPlay.BESIDE || (along > -0.5 && to.subtract(line.clone().multiply(along)).length() < SkullPlay.BESIDE)
            }) return false
        // Nor on a block: where its way first meets one, if that is short of them.
        val reach = if (look != null) SkullPlay.BLAST + SkullPlay.MARGIN + 2.0 else length
        val hit = player.world.rayTraceBlocks(eye, line, reach, org.bukkit.FluidCollisionMode.NEVER, true)?.hitPosition ?: return true
        return !SkullPlay.inBlast(hit.x - here.x, hit.y - here.y, hit.z - here.z)
    }

    /**
     * With a skull on its way to [t]: stays outside what it would reach from where they are,
     * without turning away from them (backwards when they are too near, still when not).
     */
    private fun standOff(t: Player, map: GameMapDef) {
        val here = player.location
        val at = t.location
        val away = here.toVector().subtract(at.toVector()).setY(0)
        val gap = away.length()
        if (gap < 1e-3 || SkullPlay.keepsClear(at.x - here.x, at.y - here.y, at.z - here.z)) { setInputs(0f, 0f, false); return }
        away.multiply(1 / gap)
        val input = inputToward(here.clone().add(away.x * 2, 0.0, away.z * 2))
        applyMovement(null, input.first, input.second, false, map)
    }

    private fun witherImage(): org.bukkit.entity.LivingEntity? =
        (kitAbility("wither_image") as? dev.betrix.superSmashMobsBrawl.abilities.WitherImageAbility)?.imageOut()

    /** Whether the image is somewhere to be: on something (or about to be), with room for a body ([ImagePlay]). */
    private fun imageStands(image: org.bukkit.entity.LivingEntity): Boolean {
        val at = image.location
        if (at.world != player.world || at.y <= (lastMap?.voidLevel ?: -1.0e9) + 3) return false
        return ImagePlay.stands(image.isOnGround, hasSolidBelow(at, ImagePlay.FLOOR), image.velocity.y) &&
            at.block.isPassable && at.clone().add(0.0, 1.0, 0.0).block.isPassable
    }

    /** Falling, with its image standing on the map: one press and the two have changed places. */
    private fun swapWithImage(): Boolean {
        if (BotManager.oldKitPlay || !isReady("wither_image")) return false
        val image = witherImage() ?: return false
        if (!imageStands(image)) return false
        if (!useAbility("wither_image", AbilityHints.of("wither_image"), false)) return false
        dbg("over the void: changed places with its Wither Image")
        return true
    }

    // -- Magma Cube

    /**
     * Whether there is floor for as far back as a Magma Blast would throw the bot, looking at [t]
     * from here ([RecoilPlay]): no drop, and no lava or water. (Holding it back for that cost the
     * kit nothing over 40 matches: a Magma Cube's blasts did more, not less, for its living longer.)
     */
    private fun recoilHasFloor(t: Player): Boolean {
        val here = player.location
        val (yaw, pitch) = lookAngles(player.eyeLocation, aimPointOf(t))
        val height = if (nms.onGround()) 0.0 else floorUnder(here, 8.0)?.let { (here.y - it.y).coerceAtLeast(0.0) } ?: return false
        val back = RecoilPlay.carried(pitch.toDouble(), height) + RecoilPlay.SPARE
        val rad = Math.toRadians(yaw.toDouble())
        // (Away from where it is looking.)
        val ux = sin(rad)
        val uz = -cos(rad)
        for (i in 1..ceil(back).toInt().coerceAtMost(RecoilPlay.LOOK_BACK)) {
            if (!hasSolidBelow(here.clone().add(ux * i, 0.5, uz * i), 6)) return false
        }
        return true
    }

    // -- Enderman

    private fun blinkAbility() = kitAbility("blink") as? dev.betrix.superSmashMobsBrawl.abilities.BlinkAbility

    /** Whether a body can be where a Blink comes out: room for it, and floor no more than [drop] blocks under. */
    private fun blinkStands(out: Location, drop: Int): Boolean =
        out.y > (lastMap?.voidLevel ?: -1.0e9) + 3 && hasSolidBelow(out, drop) &&
            out.block.isPassable && out.clone().add(0.0, 1.0, 0.0).block.isPassable

    /**
     * Where to look to Blink onto [t]: at the floor under them (a Blink stops just short of the
     * first block its player's look meets, so that is where it comes out), or failing that at them,
     * if looking there brings the bot out beside them with something to stand on. Null if neither does.
     */
    private fun blinkAim(t: Player): Location? {
        if (tick < blinkRestUntil) return null
        val blink = blinkAbility() ?: return null
        val eye = player.eyeLocation
        val feet = t.location
        val now = player.location.distance(feet)
        val spots = listOfNotNull(floorUnder(feet, 3.0)?.let { Location(feet.world, feet.x, it.y - 0.3, feet.z) }, aimPointOf(t))
        for (spot in spots) {
            val look = spot.toVector().subtract(eye.toVector())
            if (look.lengthSquared() < 1.0) continue
            val out = blink.comesOut(look)
            val gap = out.distance(feet)
            if (gap <= BlinkPlay.NEAR && gap < now - 2.0 && blinkStands(out, BlinkPlay.DROP)) return spot
        }
        return null
    }

    /** Blocks of air under [at] before something to land on, [most] at the most; null when there is nothing, or only lava or water. */
    private fun airUnder(at: Location, most: Int): Int? {
        val world = at.world ?: return null
        for (i in 0..most) {
            val y = at.blockY - i
            if (y < world.minHeight) return null
            val type = world.getBlockAt(at.blockX, y, at.blockZ).type
            if (ArenaTerrain.watery(type)) return null
            if (type.isSolid) return i
        }
        return null
    }

    /**
     * Falling: the look that brings a Blink out over floor, the nearer underfoot and the nearer
     * where the bot was heading the better ([BlinkPlay]), and the press. (A bot used to Blink level,
     * the way it was facing: past the island it was falling beside, as often as onto it.)
     */
    private fun blinkHome(goal: Location): Boolean {
        val blink = blinkAbility() ?: return false
        if (tick - blinkScanTick < 4) return false
        blinkScanTick = tick
        val eye = player.eyeLocation
        val (toGoal, straight) = lookAngles(eye, goal.clone().add(0.0, 0.3, 0.0))
        var best: Pair<Float, Float>? = null
        var bestScore = Double.MAX_VALUE
        fun consider(yaw: Float, pitch: Float) {
            val out = blink.comesOut(Location(eye.world, 0.0, 0.0, 0.0, yaw, pitch).direction)
            if (out.y <= (lastMap?.voidLevel ?: -1.0e9) + 3 || !out.block.isPassable || !out.clone().add(0.0, 1.0, 0.0).block.isPassable) return
            val score = BlinkPlay.landing(airUnder(out, BlinkPlay.FALL), hypot(out.x - goal.x, out.z - goal.z)) ?: return
            if (score < bestScore) { bestScore = score; best = yaw to pitch }
        }
        consider(toGoal, straight)
        for (side in BlinkPlay.YAWS) for (pitch in BlinkPlay.PITCHES) consider(toGoal + side.toFloat(), pitch.toFloat())
        val (yaw, pitch) = best ?: return false
        nms.setYRot(yaw)
        nms.setYHeadRot(yaw)
        nms.setXRot(pitch)
        return useAbility("blink", AbilityHints.of("blink"), false)
    }

    // -- Cow, Magma Cube: the rides

    /** Let go of now, at the speed it has, would the bot come down on something? Followed tick by tick, the way a player falls. */
    private fun coastsOntoFloor(v: Vector, map: GameMapDef): Boolean {
        val world = player.world
        val at = player.location
        var vx = v.x
        var vy = minOf(v.y, 0.0)
        var vz = v.z
        for (i in 1..40) {
            val step = Vector(vx, vy, vz)
            val length = step.length()
            if (length > 1e-6) {
                val hit = world.rayTraceBlocks(at, step.clone().multiply(1 / length), length, org.bukkit.FluidCollisionMode.ALWAYS, true)
                if (hit != null) {
                    val block = hit.hitBlock ?: return false
                    // Into the side of something is not onto it, and lava is not floor.
                    if (ArenaTerrain.watery(block.type) || hit.hitBlockFace != BlockFace.UP) return false
                    // Down: what is left of its speed slides it a little further, and that has to be floor as well.
                    if (hypot(vx, vz) < 0.05) return true
                    val on = hit.hitPosition
                    return hasSolidBelow(Location(world, on.x + vx * 1.2, on.y + 0.5, on.z + vz * 1.2), 3)
                }
            }
            at.add(vx, vy, vz)
            vy = (vy - 0.08) * 0.98
            vx *= 0.91
            vz *= 0.91
            if (at.y <= map.voidLevel + 1) return false
        }
        return false
    }

    /**
     * Riding a Milk Spiral or a Flame Dash. Both go on until they are over or their button is
     * pressed again, sixteen blocks and twenty-seven, and a bot never pressed it again: it rode
     * past whoever it had gone for and off the map behind them ([RidePlay]). Now it gets off on
     * them, or where the floor runs out; and ridden back from over the void, as soon as getting off
     * brings it down on something. Returns true while a ride is under way (nothing else the bot
     * could do changes where a ride goes).
     */
    private fun tickRide(t: Player?, map: GameMapDef): Boolean {
        if (BotManager.oldKitPlay) return false
        val kit = KitService.getKitForPlayer(player) ?: return false
        val dash = (kit.getAbility("flame_dash") as? dev.betrix.superSmashMobsBrawl.abilities.FlameDashAbility)?.dashingNow == true
        val spiral = !dash && (kit.getAbility("milk_spiral") as? dev.betrix.superSmashMobsBrawl.abilities.MilkSpiralAbility)?.ridingNow == true
        if (!dash && !spiral) { rideSince = -1; return false }
        val here = player.location
        val v = nms.deltaMovement
        if (rideSince < 0) {
            rideSince = tick
            // Taken to get back over ground, or at somebody?
            rideHome = recovering || isOverVoid(map)
            gapJump = null; comboQueue.clear(); shot = null
        }
        // Which way it is being carried: the way it is moving, or (in its first tick) the way it is looking.
        val speed = hypot(v.x, v.z)
        val look = here.direction
        val flat = hypot(look.x, look.z)
        val ux = if (speed > 0.05) v.x / speed else if (flat > 1e-6) look.x / flat else 0.0
        val uz = if (speed > 0.05) v.z / speed else if (flat > 1e-6) look.z / flat else 0.0
        fun floor(ahead: Double, deep: Int) = hasSolidBelow(here.clone().add(ux * ahead, 0.0, uz * ahead), deep)
        val name = if (dash) "Flame Dash" else "Milk Spiral"
        var why: String? = null
        if (rideHome || !floor(0.0, 6)) {
            // Over the void. A Milk Spiral's rider stops dead when it gets off and drops; a Flame
            // Dash's keeps its speed and comes down further on.
            val deep = depthToVoid(map)
            val lands = if (dash) coastsOntoFloor(Vector(v.x, v.y, v.z), map) else floor(0.0, deep) && floor(0.5, deep)
            if (lands) why = "over ground again"
        } else {
            // (Somebody well above or below is not got at by stopping under them.)
            val at = t?.takeIf { abs(it.y - here.y) < 3.0 }
            val gap = at?.let { hypot(it.x - here.x, it.z - here.z) } ?: Double.MAX_VALUE
            val ahead = at == null || (at.x - here.x) * ux + (at.z - here.z) * uz > 0.0
            val onThem = if (dash) RidePlay.DASH_ON_THEM else RidePlay.SPIRAL_ON_THEM
            // In the air a rider that gets off is carried further than one sliding on the floor.
            val stop = if (!dash) RidePlay.SPIRAL_STOP else if (nms.onGround()) RidePlay.DASH_STOP else RidePlay.DASH_STOP + RidePlay.DASH_AIR
            val floorAhead = floor(stop, 4) && floor(stop * 2 / 3, 4) && floor(stop / 3, 4)
            if (RidePlay.getsOff(gap, ahead, onThem, floorAhead)) {
                why = if (!floorAhead) "the floor ends here" else if (gap <= onThem) "on ${at?.name}" else "past ${at?.name}"
            }
        }
        if (why != null && tick > rideSince && tick - rideOffTick > 3) {
            // The same button again.
            rideOffTick = tick
            if (useAbility(if (dash) "flame_dash" else "milk_spiral", AbilityHints.of(if (dash) "flame_dash" else "milk_spiral"), false)) {
                dbg("off the $name after ${tick - rideSince} ticks: $why")
            }
        }
        // Facing whoever it is for, to be on them when it ends.
        t?.let {
            val (yaw, pitch) = lookAngles(player.eyeLocation, aimPointOf(it))
            lookTowards(yaw, pitch, d.turnSpeed)
        }
        setInputs(0f, 0f, false)
        return true
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
        // (Pointed somewhere of its own, and there is no longer anywhere to point it: dropped.)
        val onto = if (lining.aim == null) aimPoint else lining.aim.invoke() ?: run { shot = null; return true }
        val facing = aimAt(lead(onto, dist, lining.speed), d.turnSpeed * 2, raise)
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
        lookTowards(wantYaw, wantPitch, maxStep)
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
            // (Nor is the drop that is part of a crossing the bot planned: see [Crossing].)
            falling = gapJump == null && crossing?.takeIf { it.stage > 0 } == null && isOverVoid(map) && nms.deltaMovement.y < 0,
            airJump = canDoubleJump(),
            targetDistance = t?.location?.distance(here),
            targetVisible = t != null && player.hasLineOfSight(t),
            targetHealth = t?.let { it.health / maxHealth(it) } ?: 1.0,
            targetSpeed = hypot(trackedVelocity.x, trackedVelocity.z),
            targetNearEdge = t != null && !hasGroundNear(t.location, 2),
            targetUnreachable = t != null && besieging(t),
            voidBehind = away != null && (2..8 step 2).any { !hasSolidBelow(here.clone().add(away.x * it, 0.0, away.z * it), 12) },
            nearestEnemy = opponents.filter { it.world == here.world }.minOfOrNull { whereIs(it).distance(here) } ?: Double.MAX_VALUE,
            heldSeconds = ((tick - carriedSince) / 20).toInt(),
            riding = player.isInsideVehicle,
        )
    }

    /**
     * Looks at the item the bot is carrying and uses it if this is the moment for it. Runs in every
     * state, because the moment for a heal is while fleeing and for a cloud is while falling.
     */
    private fun considerItem(t: Player?, map: GameMapDef) {
        if (tick < nextItemTick || shot != null || holdUntilTick >= 0 || bowReleaseTick > 0L || rain != null) return
        nextItemTick = tick + 5
        val slot = (0..8).firstOrNull { ItemService.kind(player.inventory.getItem(it)) != null }
        val kind = slot?.let { ItemService.kind(player.inventory.getItem(it)) }
        if (slot == null || kind == null) { carried = null; return }
        if (kind != carried) { carried = kind; carriedSince = tick; carriedFails = 0 }
        if (kind.tool || kind == dev.betrix.superSmashMobsBrawl.items.ArenaItem.DIAMOND_HELMET) return
        // Between two uses (a rocket has a second between launches), a click does nothing: that is
        // not the item being of no use, and four of them are no reason to throw it away. Nor is a
        // car that cannot be got into from inside a cage.
        if (ItemService.coolingDown(player) || (kind == ArenaItem.FLYING_CAR && ItemService.caged(player))) return
        val charge = ItemService.uses(player.inventory.getItem(slot))
        // A Flying Car run dry would fill again if it were left to, a second of flying for every
        // four it stands. A bot has better things to do with its one free hand than wait for that.
        if (kind == ArenaItem.FLYING_CAR && charge <= 0 && !player.isInsideVehicle) {
            dbg("the Flying Car is out of fuel: thrown away")
            player.inventory.setItem(slot, null)
            if (dev.betrix.superSmashMobsBrawl.items.ItemStats.on) dev.betrix.superSmashMobsBrawl.items.ItemStats.row(ItemService.arena(player)?.game, "discard", kind, player, extra = "out of fuel")
            carried = null
            return
        }
        val situation = itemSituation(t, map).copy(charge = charge)
        // Just out of the Flying Car (nobody left to chase, or no way to them by car): not straight
        // back into it, unless it is that or a fall.
        if (wasRiding && !situation.riding) carRestUntil = tick + 100
        wasRiding = situation.riding
        if (kind == ArenaItem.FLYING_CAR && tick < carRestUntil && !(situation.falling && !situation.airJump)) return
        // Against someone no route leads to, a few items are used with more care than a throw in their direction.
        if (t != null && d.smartTargeting && situation.targetUnreachable && !situation.falling && siegeItem(slot, kind, t, situation)) return
        when (val aim = ItemTactics.plan(kind, situation, d, BotManager.oldItemPlay) ?: return) {
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
            // Not a shot to line up and let go: the eye goes to the ground under them and the button stays down.
            ItemAim.GROUND -> if (t != null && BotManager.oldItemPlay) {
                shot = Shot(kind.label, 1.2, false, tick) { spendItem(slot, kind, null) }
            } else if (t != null && nms.onGround() && gapJump == null && climb == null && !recovering && rainSpot(t) != null) {
                // (From the ground, and not in the middle of a jump or a climb: those steer themselves, and the staff would rain where they look.)
                rain = Rain(slot, tick)
                dbg("Rain Staff: onto the ground under ${t.name}, %.1f blocks off".format(t.location.distance(player.location)))
            }
        }
    }

    // ------------------------------------------------------------------ the Rain Staff

    /** The top of the ground under [at], no more than [depth] blocks down; null over a drop. */
    private fun floorUnder(at: Location, depth: Double): Location? {
        val world = at.world ?: return null
        val hit = world.rayTraceBlocks(at.clone().add(0.0, 0.6, 0.0), Vector(0, -1, 0), depth + 0.6, org.bukkit.FluidCollisionMode.NEVER, true)
            ?.hitPosition ?: return null
        return Location(world, hit.x, hit.y, hit.z)
    }

    /**
     * Where to point a Rain Staff so that it rains on [t]: the ground they will be over when a volley
     * let go now comes down, a little off for an unsteady hand. Null when there is no such shot:
     * nothing under them, something between the bot's eye and that ground, or a roof over it.
     * The staff rains on the first block its holder's look meets, which is what is checked here.
     */
    private fun rainSpot(t: Player): Location? {
        val world = player.world
        val at = t.location
        val (x, z) = RainTactics.lead(at.x, at.z, trackedVelocity.x, trackedVelocity.z, d.smartTargeting)
        // Where they are going, or (should that be out over nothing) where they are.
        val under = floorUnder(Location(world, x, at.y, z), 8.0) ?: floorUnder(at, 8.0) ?: return null
        val want = Location(world, under.x + yawError * RainTactics.SLIP, under.y + 0.05, under.z + pitchError * 2 * RainTactics.SLIP)
        val eye = player.eyeLocation
        val line = want.toVector().subtract(eye.toVector())
        if (line.lengthSquared() < 1.0) return null
        val met = world.rayTraceBlocks(eye, line.clone().normalize(), 24.0, org.bukkit.FluidCollisionMode.NEVER, true)?.hitPosition ?: return null
        if (!RainTactics.catches(hypot(met.x - under.x, met.z - under.z)) || abs(met.y - under.y) > 3.0) return null
        // The volley starts twelve blocks over what the look meets: a roof between there and them, and nothing arrives.
        val top = Location(world, met.x, met.y + 12.0, met.z)
        val roof = world.rayTraceBlocks(top, Vector(0, -1, 0), 12.5, org.bukkit.FluidCollisionMode.NEVER, true)?.hitPosition
        if (roof != null && roof.y > under.y + 2.2) return null
        return want
    }

    /**
     * Holds a Rain Staff on the bot's opponent: the eye on the ground under them, following as they
     * move, the button down (a volley every two ticks) for as long as there is rain to be had on
     * them. Returns true while it is doing that; it owns where the bot looks and stands.
     */
    private fun tickRain(r: Rain, map: GameMapDef): Boolean {
        r.served = tick
        val t = target?.takeIf { it.isOnline && it.world == player.world }
        val staff = ItemService.kind(player.inventory.getItem(r.slot)) == ArenaItem.RAIN_STAFF
        if (!staff) return endRain("the staff is used up")
        if (t == null) return endRain("nobody to rain on")
        // Something else took the hand off it (a combo's second press): the rain has stopped with that.
        if (r.since >= 0 && (player.inventory.heldItemSlot != r.slot || !nms.isUsingItem)) return endRain("the hand came off it")
        if (r.since >= 0 && tick - r.since >= RainTactics.HOLD_TICKS) return endRain("held long enough")
        // Twenty volleys with nothing landing on anybody: whatever the eye says, the rain is not
        // getting to them (a roof, a lip of rock). Not more of the same: let go, and not again for a while.
        if (r.since >= 0) {
            val dealt = dealtThisLife.values.sum()
            if (dealt > r.dealt) { r.dealt = dealt; r.wet = tick }
            if (RainTactics.dry(tick - maxOf(r.since, r.wet))) return endRain("nothing is landing on ${t.name}", RainTactics.DRY_REST)
        }
        val spot = rainSpot(t)?.takeIf { RainTactics.inReach(t.location.distance(player.location), r.since >= 0) }
        if (spot == null) {
            // Not yet raining: there is nothing to start on. Raining: a moment's grace (they jumped a gap, went behind a pillar).
            if (r.since < 0 || ++r.off > RainTactics.PATIENCE) return endRain("no rain to be had on ${t.name}")
        } else {
            r.off = 0
            val (yaw, pitch) = lookAngles(player.eyeLocation, spot)
            turnTowards(yaw, pitch, d.turnSpeed * 2)
            if (r.since < 0 && abs(wrapDegrees(yaw - nms.yRot)) < 1f && abs(pitch - nms.xRot) < 0.5f) {
                // On the spot: the first volley, and the button stays down.
                comboQueue.clear()
                r.back = player.inventory.heldItemSlot
                r.charge = ItemService.uses(player.inventory.getItem(r.slot))
                player.inventory.heldItemSlot = r.slot
                if (!ItemService.use(player, false, null)) {
                    player.inventory.heldItemSlot = r.back
                    return endRain("the staff would not go off")
                }
                nms.startUsingItem(InteractionHand.MAIN_HAND)
                r.since = tick
                r.dealt = dealtThisLife.values.sum()
                carriedFails = 0
                dbg("used item ${ArenaItem.RAIN_STAFF.label}: holding it on ${t.name}")
            }
        }
        if (r.since < 0 && tick - r.born > RainTactics.SETTLE_TICKS) return endRain("could not get the eye onto the spot")
        // The feet: side to side, as anybody holding a button down on somebody does, and never off an edge.
        if (d.strafes) {
            if (tick >= nextStrafeFlip) { strafeDir = if (rng.nextBoolean()) 1f else -1f; nextStrafeFlip = tick + 20 }
            applyMovement(null, 0f, strafeDir * .5f, false, map)
        } else setInputs(0f, 0f, false)
        return true
    }

    /** Lets go of the Rain Staff, and leaves it alone for [rest] ticks. Always false, for [tickRain] to hand back. */
    private fun endRain(why: String, rest: Int = 10): Boolean {
        val r = rain ?: return false
        rain = null
        if (r.since >= 0) {
            nms.stopUsingItem()
            if (player.inventory.heldItemSlot == r.slot) player.inventory.heldItemSlot = r.back
            val staff = player.inventory.getItem(r.slot)?.takeIf { ItemService.kind(it) == ArenaItem.RAIN_STAFF }
            val volleys = staff?.let { "${(r.charge - ItemService.uses(it)).coerceAtLeast(0)} volleys" } ?: "with the staff gone (used up, or lost with a life)"
            dbg("Rain Staff let go after ${tick - r.since} ticks and $volleys: $why")
        } else dbg("Rain Staff not used: $why")
        nextItemTick = tick + rest
        return false
    }

    // ------------------------------------------------------------------ a clone, and the bot's own bomb

    /**
     * A Monster Egg clone that has got to this bot: turned on and hit until it is gone, which is one
     * or two hits. The same for what an opponent's kit has sent to fight for it (a Wither Image, a
     * Polar Bear's cub), if a few swings will finish it ([SummonTactics]). Returns true while that
     * is what the bot is doing (it owns the look and the feet).
     */
    private fun swatClone(decide: Boolean, map: GameMapDef): Boolean {
        if (BotManager.oldItemPlay && BotManager.leaveSummons) return false
        if (decide || tick - pestTick > 10) {
            pestTick = tick
            val here = player.location
            pestsLeftAlone.values.removeIf { it <= tick }
            val clones = if (BotManager.oldItemPlay) emptyList() else ItemService.hostileClones(player)
            val summons = dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons.owners
            val game = if (BotManager.leaveSummons || summons.isEmpty()) null else minigameService.getMinigameForPlayer(player)
            val sent = if (game == null) emptyList() else {
                val damage = KitService.getKitForPlayer(player)?.getMeleeDamage() ?: 0.0
                // (Only one that is after this bot: it goes for whoever is nearest to it, and somebody else's trouble is theirs.)
                hostileBodies(CloneTactics.NOTICE).filter { e -> e !is Player && e.uniqueId !in ItemEffects.cloneOwners &&
                    SummonTactics.fights(e.type.name) && SummonTactics.worthIt(e.health, damage) &&
                    summons[e.uniqueId]?.let { owner -> comingForMe(e, owner, game) } == true }
            }
            pest = (clones + sent)
                .filter { it.uniqueId !in pestsLeftAlone && it.location.distanceSquared(here) <= CloneTactics.NOTICE * CloneTactics.NOTICE }
                .minByOrNull { it.location.distanceSquared(here) }
        }
        val clone = pest?.takeIf { it.isValid && !it.isDead && it.world == player.world } ?: run { pest = null; pestOn = null; return false }
        val reach = KitService.getKitForPlayer(player)?.getMeleeReach() ?: 3.0
        val box = clone.boundingBox
        val aim = Location(clone.world, box.centerX, box.minY + box.height * 0.6, box.centerZ)
        val dist = player.eyeLocation.distance(aim)
        // Only a clone it knows about: one coming at it from where it is looking, or one that has
        // just hit it. One coming up behind gets its first swing in, as it would on anybody.
        if (pestNoticed != clone.uniqueId) {
            val (toward, _) = lookAngles(player.eyeLocation, aim)
            val seen = CloneTactics.inView(wrapDegrees(toward - nms.yRot).toDouble()) && player.hasLineOfSight(clone)
            if (!seen && tick - pestFelt > CloneTactics.FELT_TICKS) return false
            pestNoticed = clone.uniqueId
            pestNoticedTick = tick
        }
        if (tick - pestNoticedTick < CloneTactics.readyAfter(d)) return false
        // Mid-shot, mid-charge or drawing a bow, the hands are full.
        if (holdUntilTick >= 0 || bowReleaseTick > 0L || shot != null) return false
        if (!CloneTactics.turnsOn(dist, reach) || !player.hasLineOfSight(clone)) { pestOn = null; return false }
        // One it cannot get a hit on (up on a ledge, behind a fence) is not stood under and swung at
        // for ever: with nothing to show for it after a couple of seconds, it is left alone a while.
        if (pestOn != clone.uniqueId || clone.health < pestHealth) { pestOn = clone.uniqueId; pestSince = tick; pestHealth = clone.health }
        if (CloneTactics.givesUp(tick - pestSince)) {
            pestsLeftAlone[clone.uniqueId] = tick + CloneTactics.LEFT_ALONE_TICKS
            dbg("cannot get a hit on ${pestName(clone, "the")}: leaving it alone")
            pest = null; pestOn = null
            return false
        }
        rain?.let { endRain("a clone is on it") }
        val (yaw, pitch) = lookAngles(player.eyeLocation, aim)
        lookTowards(yaw + yawError, pitch + pitchError, d.turnSpeed * 2)
        if (CloneTactics.swings(dist, reach) && tick >= nextClickTick) {
            meleeClick()
            val interval = 20.0 / d.clicksPerSecond
            nextClickTick = tick + (interval * (0.7 + rng.nextDouble() * 0.6)).toLong().coerceAtLeast(1)
            if (tick - pestSaidTick > 40) { pestSaidTick = tick; dbg("${pestName(clone, "a")} is on it: hitting it") }
        }
        // It comes to the bot. A step toward it if it hangs back, a sidestep otherwise.
        var forward = 0f
        var strafe = 0f
        if (dist > reach - .4) { val input = inputToward(clone.location); forward = input.first; strafe = input.second }
        else if (d.strafes) {
            if (tick >= nextStrafeFlip) { strafeDir = if (rng.nextBoolean()) 1f else -1f; nextStrafeFlip = tick + 20 }
            strafe = strafeDir * .65f
        }
        applyMovement(null, forward, strafe, false, map)
        return true
    }
    private var pestSaidTick = -1000L
    /** Is [summon], sent by [owner], nearer to this bot than to anybody else it could be after? */
    private fun comingForMe(summon: org.bukkit.entity.LivingEntity, owner: Player, game: BrawlMinigame): Boolean {
        val at = summon.location
        val mine = at.distanceSquared(player.location)
        return game.allPlayers().mapNotNull { it.player }.none { p ->
            p != player && p != owner && p.world == at.world && p.gameMode == GameMode.SURVIVAL &&
                !game.arePlayersOnSameTeam(owner, p) && !game.respawnManager.isRespawning(p) &&
                p.location.distanceSquared(at) < mine }
    }
    /** What to call something being hit back at, for the log: "a clone of X", or "X's wither skeleton (16 health)". */
    private fun pestName(e: org.bukkit.entity.LivingEntity, article: String): String =
        ItemEffects.cloneOwners[e.uniqueId]?.let { "$article clone of ${it.name}" }
            ?: "${dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons.owners[e.uniqueId]?.name}'s ${e.type.name.lowercase().replace('_', ' ')} (%.0f health)".format(e.health)
    /** The clone being hit at, since when without a hit landing, and what health it had then. */
    private var pestOn: java.util.UUID? = null
    private var pestSince = 0L
    private var pestHealth = 0.0
    /** Clones this bot could not get a hit on, and until when it leaves each alone. */
    private val pestsLeftAlone = HashMap<java.util.UUID, Long>()
    /** The clone this bot has noticed (seen coming, or felt), and when; and when it was last hit with a clone beside it. */
    private var pestNoticed: java.util.UUID? = null
    private var pestNoticedTick = 0L
    private var pestFelt = -1000L

    /**
     * Things that are about to go off, or are lying in wait: a grenade on its fuse, an Air Strike's
     * flare, a singularity before it opens, a cloud of poison, a Crafty Bomb, a mine or a Cage Trap
     * on the ground. The bot's own it knows about; anybody else's it has to see, or hear land beside
     * it, and then a moment passes before it does anything ([DangerTactics]). Then: out from under
     * whatever it is standing in, no walking back in after an opponent who is still in it, and
     * round what is lying on the ground. (It used to know none of this, and was hurt by its own
     * Singularity Nuke as often as not.) Returns true while it is doing any of that.
     */
    private fun tickDangers(t: Player?, decide: Boolean, map: GameMapDef): Boolean {
        val arena = if (BotManager.oldItemPlay || BotManager.noDodging) null else ItemService.arena(player)
        val all = arena?.dangers
        if (arena == null || all.isNullOrEmpty()) {
            if (noticed.isNotEmpty()) { noticed.clear(); noWayOut.clear() }
            escape = null; roundTo = null
            return false
        }
        val here = player.location
        val eye = player.eyeLocation
        // What has come to its notice: its own at once, anybody else's when it sees it or hears it land.
        if (decide) {
            noticed.keys.removeIf { it !in all }
            noWayOut.keys.removeIf { it !in all }
            for (danger in all) {
                if (danger in noticed) continue
                val c = danger.at() ?: continue
                if (c.world != here.world || !concerns(danger, arena.game)) continue
                val mine = danger.owner == player.uniqueId
                val gap = c.distance(here)
                if (!mine) {
                    if (gap > DangerTactics.SIGHT) continue
                    val (toward, _) = lookAngles(eye, c)
                    val inView = abs(wrapDegrees(toward - nms.yRot)) <= DangerTactics.VIEW
                    val line = c.clone().add(0.0, 0.3, 0.0).toVector().subtract(eye.toVector())
                    val open = inView && (line.lengthSquared() < 1.0 ||
                        player.world.rayTraceBlocks(eye, line.clone().normalize(), line.length(), org.bukkit.FluidCollisionMode.NEVER, true) == null)
                    if (!DangerTactics.noticed(gap, inView, open)) continue
                }
                noticed[danger] = tick
                // Whatever way it was going was planned without knowing about this.
                route.clear(); routeSearch = null; nextRouteTick = tick
                dbg("${if (mine) "its own" else "saw a"} ${danger.kind.label}%s, %.1f blocks off".format(
                    if (danger.trigger) " on the ground" else " that goes off in ${(danger.from - arena.tick).coerceAtLeast(0)} ticks", gap))
            }
        }
        // What it is ready to do something about: a moment after it noticed, the better the player the shorter.
        val live = ArrayList<Pair<dev.betrix.superSmashMobsBrawl.items.ItemArena.Danger, Location>>()
        for ((danger, since) in noticed) {
            if (tick - since < DangerTactics.readyAfter(d, danger.owner == player.uniqueId)) continue
            val c = danger.at() ?: continue
            if (c.world == here.world) live += danger to c
        }
        if (live.isEmpty()) { escape = null; roundTo = null; return false }

        // Standing in something that has not gone off yet (or that it can still walk out of): out.
        // (Not from something still in the air, which is not yet where it will go off, unless its
        // fuse is nearly out anyway.)
        val inside = live.filter { (danger, c) -> !danger.trigger && (arena.tick < danger.from || !danger.holds) &&
            DangerTactics.actsOn(danger.still(), danger.from - arena.tick) &&
            covers(danger, c, here, DangerTactics.MARGIN) && (noWayOut[danger] ?: 0L) <= tick }
            .minByOrNull { (danger, _) -> danger.from - arena.tick }
        if (inside != null) {
            val (danger, c) = inside
            var e = escape
            // A new one, or the one it was getting away from is still in the air and has moved on.
            if (e == null || e.from !== danger || e.middle.distanceSquared(c) > 2.25) { e = Escape(danger, c.clone()); escape = e }
            roundTo = null
            val mine = danger.owner == player.uniqueId
            val what = "${if (mine) "its own" else "the"} ${danger.kind.label}"
            var goal = e.refuge
            if (goal != null && !e.byRoute && tick - e.refugeTick >= DangerTactics.STUCK_TICKS) {
                // Not getting any nearer to it (a wall, a drop in the way): somewhere else.
                val gap = hypot(goal.x - here.x, goal.z - here.z)
                if (gap > e.gap - 0.6) { e.tried += goal.blockX to goal.blockZ; e.refuge = null; goal = null }
                else { e.gap = gap; e.refugeTick = tick }
            }
            if (goal == null) {
                // The nearest clear ground that can be walked to in a straight line: out between two
                // of a Crafty Bomb's lines, straight away from anything round and then round to either side.
                val ways = if (danger.cross) CraftyTactics.refuges(here.x - c.x, here.z - c.z)
                    else DangerTactics.waysOut(here.x - c.x, here.z - c.z, danger.radius)
                // Measured along the ground: a bot in the middle of a jump has no floor at the height
                // of its feet, found no way out in a straight line, and ran for a far corner instead.
                val base = floorUnder(here, 6.0)?.let { Location(here.world, here.x, it.y, here.z) } ?: here
                goal = ways.asSequence().map { (dx, dz) -> Location(here.world, c.x + dx, base.y, c.z + dz) }
                    .filter { spot -> (spot.blockX to spot.blockZ) !in e.tried && walkable(base, spot) &&
                        live.none { (other, oc) -> !other.trigger && covers(other, oc, spot, DangerTactics.MARGIN) } }
                    .firstOrNull()
                // Nothing in a straight line (the edge of an island, a wall): by the routes, to the
                // nearest place fighters start from that is out of it.
                if (goal == null && !e.byRoute) {
                    goal = map.spawnPoints.map { Location(here.world, it.x, it.y, it.z) }
                        .filter { spot -> live.none { (other, oc) -> !other.trigger && covers(other, oc, spot, DangerTactics.MARGIN) } }
                        .minByOrNull { it.distanceSquared(here) }
                    e.byRoute = goal != null
                }
                if (goal == null) {
                    // Nowhere to go: the fight goes on where it is, and this is not gone over again every tick.
                    dbg("no way out of $what: staying in the fight")
                    noWayOut[danger] = tick + 40
                    escape = null
                    return false
                }
                e.refuge = goal; e.refugeTick = tick; e.gap = hypot(goal.x - here.x, goal.z - here.z)
                dbg("$what goes off in ${(danger.from - arena.tick).coerceAtLeast(0)} ticks: out to ${goal.blockX},${goal.blockY},${goal.blockZ}" + if (e.byRoute) " (the long way)" else "")
            }
            rain?.let { endRain("something is about to go off") }
            if (e.byRoute) {
                travel(goal, map) { noWayOut[danger] = tick + 40; escape = null }
                return true
            }
            val (yaw, _) = lookAngles(eye, goal.clone().add(0.0, 1.2, 0.0))
            turnTowards(yaw, 0f, d.turnSpeed * 2)
            val input = inputToward(goal)
            applyMovement(null, input.first, input.second, nms.horizontalCollision && nms.onGround(), map)
            return true
        }
        escape?.let { e ->
            if (!e.said && arena.tick < e.from.until) {
                e.said = true
                dbg("clear of ${if (e.from.owner == player.uniqueId) "its own" else "the"} ${e.from.kind.label} with ${(e.from.from - arena.tick).coerceAtLeast(0)} ticks to go")
            }
        }

        // Clear of everything. The way it is going may not be: toward somebody standing in something
        // that is about to go off, or across a singularity that has opened and holds whoever steps
        // in. Not that way: here it waits, at the edge, and hits anybody who comes within a hit. (A
        // fight used to walk a bot back under its own bomb, and a chase took it through its own singularity.)
        val toward = route.firstOrNull()?.node?.let { Location(here.world, it.x + .5, here.y, it.z + .5) } ?: t?.location
        val keptOut = if (toward == null || toward.world != here.world) null else {
            val wayX = toward.x - here.x
            val wayZ = toward.z - here.z
            val length = hypot(wayX, wayZ)
            val steps = (minOf(length, DangerTactics.WAY_AHEAD) / DangerTactics.WAY_STEP).toInt()
            // (Only what it is outside of: caught in something with no way out, it fights on where it is.)
            if (length < 0.3 || steps < 1) null else live.firstOrNull { (danger, c) -> !danger.trigger && arena.tick <= danger.until &&
                !covers(danger, c, here, 0.0) &&
                (1..steps).any { i ->
                    val along = i * DangerTactics.WAY_STEP / length
                    covers(danger, c, Location(here.world, here.x + wayX * along, here.y, here.z + wayZ * along), DangerTactics.MARGIN * 0.5)
                } }
        }
        if (keptOut != null) {
            val reach = KitService.getKitForPlayer(player)?.getMeleeReach() ?: 3.0
            val aim = t?.let { aimPointOf(it) } ?: keptOut.second
            val (yaw, pitch) = lookAngles(eye, aim)
            lookTowards(yaw + yawError, pitch + pitchError, d.turnSpeed)
            if (t != null && holdUntilTick < 0 && bowReleaseTick == 0L && tick >= nextClickTick && eye.distance(aim) <= reach + 0.6 && player.hasLineOfSight(t)) {
                meleeClick()
                val interval = 20.0 / d.clicksPerSecond
                nextClickTick = tick + (interval * (0.7 + rng.nextDouble() * 0.6)).toLong().coerceAtLeast(1)
            }
            if (tick - dangerSaidTick > 40) { dangerSaidTick = tick; dbg("the way ${t?.let { "to ${it.name} " } ?: ""}leads into the ${keptOut.first.kind.label}: waiting at the edge of it") }
            roundTo = null
            setInputs(0f, 0f, false)
            return true
        }
        if (escape != null && live.none { (danger, _) -> danger === escape?.from }) escape = null

        // Something lying on the ground right where it is going (a mine, a Cage Trap): round it.
        val going = nms.deltaMovement
        val speed = hypot(going.x, going.z)
        var side = roundTo?.takeIf { tick < roundUntil && it.world == here.world && hypot(it.x - here.x, it.z - here.z) > 0.4 }
        if (side == null && speed > 0.05 && nms.onGround()) {
            val toX = here.x + going.x / speed * DangerTactics.AHEAD
            val toZ = here.z + going.z / speed * DangerTactics.AHEAD
            for ((danger, c) in live) {
                if (!danger.trigger || abs(c.y - here.y) > 1.6) continue
                val (x, z) = DangerTactics.round(here.x, here.z, toX, toZ, c.x, c.z, danger.radius + DangerTactics.STEP_WIDE) ?: continue
                val spot = Location(here.world, x, here.y, z)
                if (!walkable(here, spot)) continue
                side = spot
                roundTo = spot; roundUntil = tick + DangerTactics.ROUND_TICKS
                if (tick - dangerSaidTick > 40) { dangerSaidTick = tick; dbg("a ${danger.kind.label} on the ground in its way: going round it") }
                break
            }
        }
        if (side != null) {
            val (yaw, _) = lookAngles(eye, side.clone().add(0.0, 1.2, 0.0))
            turnTowards(yaw, 0f, d.turnSpeed * 2)
            val input = inputToward(side)
            applyMovement(null, input.first, input.second, false, map)
            return true
        }
        roundTo = null
        return false
    }

    /** Whether something listed as about to go off would do anything to this bot: its own and its opponents' do, its own side's blast does not. */
    private fun concerns(danger: dev.betrix.superSmashMobsBrawl.items.ItemArena.Danger, game: BrawlMinigame): Boolean {
        if (danger.everybody || danger.owner == player.uniqueId) return true
        val owner = Bukkit.getPlayer(danger.owner) ?: return true
        return !game.arePlayersOnSameTeam(player, owner)
    }

    /** Is [at] within reach of [danger], whose middle is at [c], with [margin] to spare? */
    private fun covers(danger: dev.betrix.superSmashMobsBrawl.items.ItemArena.Danger, c: Location, at: Location, margin: Double): Boolean {
        val dx = at.x - c.x
        val dy = at.y - c.y
        val dz = at.z - c.z
        return when {
            danger.cross -> CraftyTactics.inBlast(dx, dy, dz, margin)
            danger.trigger -> DangerTactics.onTrigger(dx, dy, dz, danger.radius, margin)
            else -> DangerTactics.inRound(dx, dy, dz, danger.radius, margin)
        }
    }

    /** Ground the routes keep off: whatever it knows is about to go off or is lying in wait, except what its goal [to] is itself inside (that it walks up to the edge of). */
    private fun itemZones(to: Location): List<Zone> {
        if (noticed.isEmpty() || BotManager.oldItemPlay || BotManager.noDodging) return emptyList()
        val zones = ArrayList<Zone>()
        for ((danger, since) in noticed) {
            if (tick - since < DangerTactics.readyAfter(d, danger.owner == player.uniqueId)) continue
            val c = danger.at() ?: continue
            if (c.world != to.world || covers(danger, c, to, 0.0)) continue
            zones += Zone(c.x, c.y, c.z, danger.radius + if (danger.trigger) DangerTactics.STEP_WIDE else DangerTactics.MARGIN, 4.0)
        }
        return zones
    }

    /** Level ground all the way from [from] to [to], a block at a time: somewhere that can be walked straight to. */
    private fun walkable(from: Location, to: Location): Boolean {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val length = hypot(dx, dz)
        if (length < 0.5) return true
        val steps = ceil(length).toInt()
        for (i in 1..steps) {
            val at = from.clone().add(dx / steps * i, 0.0, dz / steps * i)
            // Floor no more than a couple of blocks down, and nothing solid at the feet or the head.
            if (!hasSolidBelow(at.clone().add(0.0, -0.1, 0.0), 2)) return false
            if (!at.clone().add(0.0, 0.1, 0.0).block.isPassable || !at.clone().add(0.0, 1.1, 0.0).block.isPassable) return false
        }
        return true
    }

    /** Uses the item in [slot]. [contact] is the opponent for items applied in melee range. */
    private fun spendItem(slot: Int, kind: dev.betrix.superSmashMobsBrawl.items.ArenaItem, contact: Player?) {
        if (ItemService.kind(player.inventory.getItem(slot)) != kind) return
        val previous = player.inventory.heldItemSlot
        player.inventory.heldItemSlot = slot
        val used = when (kind) {
            dev.betrix.superSmashMobsBrawl.items.ArenaItem.PLAYER_GRAPPLE -> contact != null && ItemService.botGrapple(player, contact)
            // (On the ground under them: somebody in the middle of a jump has no block beneath their feet to set it on.)
            // (Getting away from it afterwards is [tickDangers]: the bomb is listed in the arena like anything else on a fuse.)
            dev.betrix.superSmashMobsBrawl.items.ArenaItem.CRAFTY_BOMB ->
                contact?.let { ItemService.botPlaceCrafty(player, floorUnder(it.location, 4.0) ?: it.location) } != null
            dev.betrix.superSmashMobsBrawl.items.ArenaItem.SMASHER -> ItemService.use(player, true, null)
            dev.betrix.superSmashMobsBrawl.items.ArenaItem.KNOCKBACK_ROD -> ItemService.use(player, contact != null, contact)
            else -> ItemService.use(player, false, null)
        }
        player.inventory.heldItemSlot = previous
        if (used) {
            carriedFails = 0
            dbg("used item ${kind.label}")
            if (kind == dev.betrix.superSmashMobsBrawl.items.ArenaItem.EXTRA_LIFE) channelUntil = tick + 104
        } else if (ItemTactics.shouldDiscard(++carriedFails, kind)) {
            // An item this bot cannot spend would block every later pickup: let it go.
            dbg("discarding unusable item ${kind.label}")
            player.inventory.setItem(slot, null)
            if (dev.betrix.superSmashMobsBrawl.items.ItemStats.on) dev.betrix.superSmashMobsBrawl.items.ItemStats.row(ItemService.arena(player)?.game, "discard", kind, player, extra = "no use found")
            carried = null
        }
    }

    /**
     * The carried item against an opponent the bot cannot walk to, where the ordinary rule would
     * waste it. True when this has dealt with it (used, or deliberately kept for now).
     */
    private fun siegeItem(slot: Int, kind: dev.betrix.superSmashMobsBrawl.items.ArenaItem, t: Player, s: ItemSituation): Boolean {
        return when (kind) {
            // Exactly onto their ledge, or not at all: one that stops against the wall under them is a fall.
            ArenaItem.INSTANT_TELEPORTER -> {
                if (crossing?.takeIf { it.stage > 0 } == null && nms.onGround() && teleportTo(t.location, slot)) {
                    dbg("no way to ${t.name}: teleported to them")
                    arrivedAt(t.uniqueId, t.location)
                }
                true
            }
            // Rockets take the rock away: with no sight of them, dig toward them from far enough back.
            ArenaItem.MISSILE_BATTERY, ArenaItem.ROCKET_LAUNCHER -> if (s.targetVisible) false else { wallShot(t, slot, kind); true }
            else -> false
        }
    }

    /** Where an Instant Teleporter used at [from], looking along [dir], puts its user. The item's own rule. */
    private fun teleportEnd(from: Location, dir: Vector): Location {
        val world = from.world ?: return from
        var destination = from
        for (i in 1..88) {
            val at = from.clone().add(dir.clone().multiply(i * .25))
            if (at.y <= world.minHeight + 1 || at.y >= world.maxHeight - 2 || !at.block.isPassable || !at.clone().add(0.0, 1.0, 0.0).block.isPassable) break
            destination = at
        }
        return destination
    }

    /**
     * Would a teleporter used at [from] put its user on the ground at [spot]? The teleport runs
     * from the feet, along where the eyes look: aimed so the feet end up just over that floor.
     */
    private fun teleportLands(from: Location, spot: Location, minDist: Double): Location? {
        val gap = spot.clone().add(0.0, 0.4, 0.0).toVector().subtract(from.toVector())
        val dist = gap.length()
        if (dist < minDist || dist > 21.0) return null
        val end = teleportEnd(from, gap.clone().normalize())
        return end.takeIf { hypot(it.x - spot.x, it.z - spot.z) <= 2.0 && abs(it.y - spot.y) <= 1.6 && hasSolidBelow(it, 3) }
    }

    /** Teleports onto the ground at [spot] if the teleporter in [slot] would really put the bot there. */
    private fun teleportTo(spot: Location, slot: Int, minDist: Double = 3.0): Boolean {
        val here = player.location
        val end = teleportLands(here, spot, minDist) ?: return false
        val (yaw, pitch) = lookAngles(here, spot.clone().add(0.0, 0.4, 0.0))
        nms.setYRot(yaw); nms.setYHeadRot(yaw); nms.setXRot(pitch)
        spendItem(slot, dev.betrix.superSmashMobsBrawl.items.ArenaItem.INSTANT_TELEPORTER, null)
        return player.location.distanceSquared(end) < 4.0
    }

    /** The bot has got to an opponent it had no route to: forget that they were out of reach. */
    private fun arrivedAt(id: java.util.UUID, at: Location) {
        unreachableUntil.remove(id); unreachableAt.remove(id)
        route.clear(); routeSearch = null; nextRouteTick = tick
        prowlGoal = null
        lastMap?.let { forgetTerrain(at.blockX, at.blockZ, it) }
    }

    private var wallShotTick = -1000L

    /**
     * A rocket at an opponent behind rock. A Missile Battery takes a bite out of whatever it hits,
     * so sixteen of them are a tunnel; the one big rocket reaches ten blocks through anything. Both
     * hurt whoever fires them into the ground at their own feet, so only from far enough back.
     */
    private fun wallShot(t: Player, slot: Int, kind: dev.betrix.superSmashMobsBrawl.items.ArenaItem): Boolean {
        if (!nms.onGround() || shot != null) return false
        val big = kind == dev.betrix.superSmashMobsBrawl.items.ArenaItem.ROCKET_LAUNCHER
        val eye = player.eyeLocation
        val aim = aimPointOf(t)
        val gap = aim.toVector().subtract(eye.toVector())
        val dist = gap.length()
        if (dist < 4.0 || dist > (if (big) 26.0 else 22.0)) return false
        val wall = player.world.rayTraceBlocks(eye, gap.clone().normalize(), dist, org.bukkit.FluidCollisionMode.NEVER, true)?.hitPosition ?: return false
        val fromMe = wall.distance(eye.toVector())
        val fromThem = wall.distance(aim.toVector())
        if (if (big) fromMe < 11.0 || fromThem > 7.0 else fromMe < 2.8) return false
        val (yaw, pitch) = lookAngles(eye, aim)
        nms.setYRot(yaw); nms.setYHeadRot(yaw); nms.setXRot(pitch)
        if (tick - wallShotTick > 100) dbg("no sight of ${t.name}: firing ${kind.label} through ${"%.1f".format(fromThem)} blocks of ground")
        wallShotTick = tick
        spendItem(slot, kind, null)
        return true
    }

    /**
     * A solid block within arm's reach, for an ability that starts by picking one up: the one the
     * bot is standing on, or failing that the nearest around its feet. Null in mid-air.
     */
    private fun blockInReach(): org.bukkit.block.Block? {
        val feet = player.location
        val under = feet.clone().add(0.0, -0.2, 0.0).block
        if (under.type.isSolid) return under
        var best: org.bukkit.block.Block? = null
        var bestGap = Double.MAX_VALUE
        val eye = player.eyeLocation
        for (dx in -2..2) for (dy in -2..1) for (dz in -2..2) {
            val block = under.getRelative(dx, dy, dz)
            if (!block.type.isSolid) continue
            val gap = block.location.add(0.5, 0.5, 0.5).distanceSquared(eye)
            if (gap < bestGap && gap <= KitPlay.ARM * KitPlay.ARM) { best = block; bestGap = gap }
        }
        return best
    }

    private fun tryRecoveryAbility(): Boolean {
        if (BotManager.oldKitPlay) {
            val id = kitAbilityIds().firstOrNull { AbilityHints.of(it).recovery && isReady(it) } ?: return false
            return useAbility(id, AbilityHints.of(id))
        }
        val goal = recoveryGoal
        val here = player.location
        val eye = player.eyeLocation
        for (id in kitAbilityIds()) {
            if (!AbilityHints.of(id).recovery || !isReady(id)) continue
            when (id) {
                // A Blink is aimed, at wherever it comes out over floor (and not pressed when it comes out over none).
                "blink" -> { if (goal != null && blinkHome(goal)) return true else continue }
                // A Flame Dash goes level and sags: it is a way back only to ground below its rider.
                "flame_dash" -> {
                    if (goal == null || !RidePlay.dashReaches(here.y - goal.y, hypot(goal.x - here.x, goal.z - here.z))) continue
                    // (It goes the way its rider is facing when it begins.)
                    val (yaw, _) = lookAngles(eye, goal)
                    nms.setYRot(yaw)
                    nms.setYHeadRot(yaw)
                }
                // A Milk Spiral goes where its rider was looking when it began: at a point over the ground.
                "milk_spiral" -> {
                    val over = goal?.clone()?.add(0.0, RidePlay.SPIRAL_OVER, 0.0) ?: continue
                    if (eye.distance(over) > RidePlay.SPIRAL_REACH) continue
                    val (yaw, pitch) = lookAngles(eye, over)
                    nms.setYRot(yaw)
                    nms.setYHeadRot(yaw)
                    nms.setXRot(pitch)
                }
            }
            if (useAbility(id, AbilityHints.of(id))) return true
        }
        return false
    }

    private fun useAbility(id: String, hint: AbilityHint, followup: Boolean = true): Boolean {
        if(followup && !isReady(id))return false
        val slot = findAbilitySlot(id) ?: return false
        val newPlay = !BotManager.oldKitPlay
        if (newPlay && id == "blink") {
            // Wherever it was meant to go, it goes where the bot is looking at this moment: that is what is checked.
            val out = blinkAbility()?.comesOut(player.eyeLocation.direction)
            if (out == null || !blinkStands(out, if (recovering) BlinkPlay.FALL else BlinkPlay.DROP)) {
                blinkRestUntil = tick + 10
                dbg("a Blink from here would come out over nothing: not pressed")
                return false
            }
        }
        // Waiting outside something that is winding up, nothing of its own carries it back in: it goes
        // where the bot is looking at this moment.
        if (hint.moves > 0 && player.location.direction.let { carriesIntoBlast(it.x, it.z, hint.moves) }) {
            if (tick - carrySaidTick > 20) { carrySaidTick = tick; dbg("$id would carry it into what is about to go off: not pressed") }
            return false
        }
        // (The opponent may have closed in, or the bot left the floor, while it was turning onto the shot.)
        if (newPlay && followup) target?.takeIf { it.isOnline && it.world == player.world }?.let { t ->
            if (id == "magma_blast" && !recoilHasFloor(t)) return false
            if (id == "guided_wither_skull" && !skullIsClear(t, player.eyeLocation.direction)) return false
        }
        // An ability that starts by picking something up (an Enderman's Block Toss) is a click on a
        // block, the nearest one to hand: with none in reach (in the air) it cannot be used. A bot's
        // click used to be on thin air always, so no Enderman bot ever threw a block.
        val grabbed = if (id in KitPlay.NEEDS_A_BLOCK && !BotManager.oldKitPlay) (blockInReach() ?: return false) else null
        player.inventory.heldItemSlot = slot
        if (hint.aimUp != 0f) nms.setXRot((nms.xRot - hint.aimUp).coerceIn(-90f, 90f))

        val usage = dataService.getAbility(id)?.usage ?: AbilityUsage.RIGHT_CLICK
        // A skull is let go as soon as the kit allows: guided it crawls, and its player ran into it ([SkullPlay]).
        val skull = newPlay && id == "guided_wither_skull"
        val holdFor = if (id == "static_laser") laserHold() else if (skull) SkullPlay.holdTicks(rng.nextInt(3))
            else if (hint.holdTicks.last > 0) hint.holdTicks.random() else 0
        if (holdFor > 0) {
            // Charge abilities fire when the player stops "blocking" with the item.
            nms.startUsingItem(InteractionHand.MAIN_HAND)
            holdUntilTick = tick + holdFor
            val at = target?.takeIf { skull && it.isOnline && it.world == player.world }
            holdSpeed = if (at != null) SkullPlay.speed(player.eyeLocation.distance(aimPointOf(at)), holdFor) else hint.speed
            holdAim = if (at != null) { -> skullAim(at) } else null
        }

        val action = if (usage == AbilityUsage.LEFT_CLICK) Action.LEFT_CLICK_AIR
            else if (grabbed != null) Action.RIGHT_CLICK_BLOCK else Action.RIGHT_CLICK_AIR
        if (usage == AbilityUsage.LEFT_CLICK) player.swingMainHand()
        PlayerInteractEvent(player, action, player.inventory.itemInMainHand, grabbed, if (grabbed != null) BlockFace.UP else BlockFace.SELF, EquipmentSlot.HAND)
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
        if (BotManager.debug) dbg("ability $id" + (if (followup) "" else " (follow-up)") +
            (if (skull) target?.takeIf { it.world == player.world }?.let { " at ${it.name}, %.1f blocks off".format(player.eyeLocation.distance(aimPointOf(it))) } ?: "" else ""))
        // (For a balance run: every ability a bot presses, under the name its hits are recorded by, so that hits can be put against presses.)
        dev.betrix.superSmashMobsBrawl.items.ItemStats.let { stats ->
            if (stats.on) stats.row(minigameService.getMinigameForPlayer(player), "cast", actor = player,
                extra = KitService.getKitForPlayer(player)?.getAbility(id)?.displayName ?: id)
        }
        val cooldownMs = ((dataService.getAbility(id)?.cooldown ?: 3.0) * 1000).toLong()
        abilityReadyAtMs[id] = System.currentTimeMillis() + cooldownMs + 250
        return true
    }

    /**
     * A Static Laser reaches 40 blocks at full charge and proportionally less before that (3.5% a
     * tick), so it is held long enough to get to the opponent, with a little to spare.
     */
    private fun laserHold(): Int {
        val t = target ?: return 20
        val dist = player.eyeLocation.distance(aimPointOf(t))
        return (ceil((dist + 3.0) / 40.0 / 0.035).toInt() + 1 + rng.nextInt(3)).coerceIn(12, 29)
    }

    // ------------------------------------------------------------------ sheep: wool mine perch

    /**
     * A Sheep that cannot get to an opponent and cannot see them (or is out of laser range) from
     * where it stands makes itself somewhere to stand: it throws the Wool Mine out over the drop,
     * sets it in mid-air where the opponent is in view, drops onto it, fires the laser, then sets
     * the mine off under itself and rides the blast, the Wooly Rocket and the double jump home.
     */
    private class Perch(val home: Location, val yaw: Float, val pitch: Float, var since: Long) {
        var stage = 0
        var block: Location? = null
        var setTick = 0L
        var fired = false
        var cool = 0
        var rocketed = false
        var jumpTick = 0L
    }
    private var perch: Perch? = null
    private var nextPerchTick = 0L
    private var perchesDone = 0

    private fun woolMine() =
        KitService.getKitForPlayer(player)?.getAbility("wool_mine") as? dev.betrix.superSmashMobsBrawl.abilities.WoolMineAbility

    private fun startPerch(t: Player, dist: Double): Boolean {
        if (perch != null || tick < nextPerchTick || !d.smartTargeting) return false
        if (KitService.getKitForPlayer(player)?.id != "sheep" || !nms.onGround()) return false
        if (holdUntilTick >= 0 || shot != null || gapJump != null || climb != null || dist !in 6.0..48.0) return false
        if (player.health < 10.0 || !canDoubleJump()) return false
        if (!isReady("wool_mine") || !isReady("static_laser") || !isReady("wooly_rocket")) return false
        val mine = woolMine() ?: return false
        if (mine.inFlight() || mine.mineBlock() != null) return false
        val eye = player.eyeLocation
        val (yaw, toTarget) = lookAngles(eye, aimPointOf(t))
        // Only from the edge of a drop on their side: thrown from the middle of a floor the mine
        // just lands on it.
        val ahead = eye.clone().also { it.yaw = yaw; it.pitch = 0f }.direction
        val feet = player.location
        if ((2..4).any { hasSolidBelow(feet.clone().add(ahead.x * it, 0.5, ahead.z * it), 5) }) {
            nextPerchTick = tick + 15
            return false
        }
        // There has to be open air to throw into: down at them if the ledge allows, flatter if not.
        val pitch = listOf((toTarget + 10f).coerceIn(25f, 55f), 35f, 25f, 15f, 5f).firstOrNull { p ->
            val dir = eye.clone().also { it.yaw = yaw; it.pitch = p }.direction
            eye.world.rayTraceBlocks(eye, dir, 8.0, org.bukkit.FluidCollisionMode.NEVER, true) == null
        } ?: run {
            nextPerchTick = tick + 40
            return false
        }
        perch = Perch(player.location.clone(), yaw, pitch, tick)
        comboQueue.clear()
        dbg("no way to ${t.name} and no shot from here: making a wool mine perch")
        return true
    }

    private fun endPerch(reason: String, rest: Long = 300) {
        dbg("wool mine perch over: $reason")
        if ((perch?.stage ?: 0) >= 2) perchesDone++
        perch = null
        nextPerchTick = tick + rest
        recoveryGoal = null
    }

    /** Would a mine set at [at] be somewhere the bot can drop onto and see [t] from? */
    private fun perchSpot(at: Location, home: Location, t: Player): Boolean {
        val b = at.block
        if (!b.type.isAir || !b.getRelative(0, 1, 0).isPassable || !b.getRelative(0, 2, 0).isPassable) return false
        val top = b.location.add(0.5, 1.0, 0.5)
        if (home.y - top.y !in -1.0..10.0 || hypot(top.x - home.x, top.z - home.z) !in 2.0..10.5) return false
        val eye = top.clone().add(0.0, 1.5, 0.0)
        val aim = aimPointOf(t)
        val dist = eye.distance(aim)
        if (dist !in 3.0..35.0) return false
        val dir = aim.toVector().subtract(eye.toVector()).normalize()
        return eye.world.rayTraceBlocks(eye, dir, dist, org.bukkit.FluidCollisionMode.NEVER, true) == null
    }

    /** One tick of the perch play. False once it is over and the normal brain should carry on. */
    private fun tickPerch(p: Perch, map: GameMapDef): Boolean {
        val t = target?.takeIf { it.isOnline && it.world == player.world && it.gameMode == GameMode.SURVIVAL }
        val mine = woolMine() ?: run { endPerch("no wool mine"); return false }
        val hint = AbilityHints.of("wool_mine")
        val age = tick - p.since
        fun next(stage: Int) { p.stage = stage; p.since = tick }
        when (p.stage) {
            // Line up and throw.
            0 -> {
                if (t == null || !nms.onGround() || age > 30) { endPerch("could not line up the throw", 100); return false }
                setInputs(0f, 0f, false)
                turnTowards(p.yaw, p.pitch, d.turnSpeed * 2)
                if (abs(wrapDegrees(p.yaw - nms.yRot)) < 3f && abs(p.pitch - nms.xRot) < 3f) {
                    useAbility("wool_mine", hint, false)
                    if (!mine.inFlight()) { endPerch("the mine did not launch", 100); return false }
                    next(1)
                }
            }
            // Watch it fly; set it the moment it is somewhere worth standing.
            1 -> {
                setInputs(0f, 0f, false)
                val at = mine.projectileLocation()
                if (t == null || at == null || age > 50) { endPerch("the mine found no spot in the air", 200); return false }
                // (The mine cannot be set for 0.8 seconds after the throw.)
                if (age >= 18 && perchSpot(at, p.home, t)) {
                    useAbility("wool_mine", hint, false)
                    val block = mine.mineBlock() ?: run { endPerch("the mine would not set", 200); return false }
                    p.block = block.location
                    p.setTick = tick
                    forgetTerrain(block.x, block.z, map)
                    dbg("mine set at ${block.x},${block.y},${block.z}; dropping onto it")
                    next(2)
                }
            }
            // Get onto it: walk off the edge, then steer (and jump) like a recovery aimed at the mine.
            2 -> {
                lastSafe = p.home
                val block = p.block!!
                val top = block.clone().add(0.5, 1.0, 0.5)
                if (mine.mineBlock() == null) { endPerch("the mine went off early"); return false }
                if (age > 90) { endPerch("could not get onto the mine"); return false }
                val here = player.location
                if (nms.onGround()) {
                    if (hypot(here.x - top.x, here.z - top.z) < 0.85 && abs(here.y - top.y) < 0.4) {
                        setInputs(0f, 0f, false)
                        next(3)
                        return true
                    }
                    if (here.y < p.home.y - 1.5) { endPerch("landed somewhere else"); return false }
                    val (yaw, _) = lookAngles(player.eyeLocation, top)
                    turnTowards(yaw, 0f, d.turnSpeed * 2)
                    setInputs(if (abs(wrapDegrees(yaw - nms.yRot)) < 20f) 1f else 0f, 0f, nms.horizontalCollision)
                } else if (hypot(here.x - top.x, here.z - top.z) < 0.9 && here.y >= top.y - 0.1) {
                    // Right above it: just come down, no jump needed.
                    val (f, s) = steerTo(top)
                    setInputs(f, s, false)
                } else {
                    recoveryGoal = top
                    recoveryScanTick = tick
                    recover(map, true)
                }
            }
            // On the mine: laser them.
            3 -> {
                lastSafe = p.home
                setInputs(0f, 0f, false)
                if (mine.mineBlock() == null) { endPerch("the mine went off under the bot"); return false }
                if (t != null) {
                    val (yaw, pitch) = lookAngles(player.eyeLocation, aimPointOf(t))
                    turnTowards(yaw, pitch, d.turnSpeed * 2)
                    if (!p.fired && holdUntilTick < 0 && isReady("static_laser") && player.hasLineOfSight(t) &&
                        abs(wrapDegrees(yaw - nms.yRot)) < 3f && abs(pitch - nms.xRot) < 3f) {
                        useAbility("static_laser", AbilityHints.of("static_laser"), false)
                        p.fired = true
                    }
                }
                if (p.fired && holdUntilTick < 0) p.cool++
                // The mine goes off by itself eight seconds after it is set: be gone before then.
                if (p.cool >= 3 || t == null || tick - p.setTick > 110 || (!p.fired && age > 50)) next(4)
            }
            // Jump, and set the mine off underneath for the lift.
            4 -> {
                lastSafe = p.home
                val (yaw, _) = lookAngles(player.eyeLocation, p.home)
                turnTowards(yaw, -30f, 90f)
                // The blast throws whoever is on the mine away from its middle, hard. Standing on
                // the half nearest the ledge makes that the way home instead of anywhere at all.
                val block = p.block!!
                val here = player.location
                val hx = p.home.x - (block.x + 0.5)
                val hz = p.home.z - (block.z + 0.5)
                val far = hypot(hx, hz).coerceAtLeast(0.01)
                val lean = ((here.x - (block.x + 0.5)) * hx + (here.z - (block.z + 0.5)) * hz) / far
                if (p.jumpTick == 0L) {
                    if (lean < 0.25 && age < 12 && nms.onGround()) {
                        val (f, s) = inputToward(p.home)
                        setInputs(f * 0.5f, s * 0.5f, false)
                    } else {
                        setInputs(0f, 0f, true)
                        p.jumpTick = tick
                    }
                } else {
                    setInputs(0f, 0f, false)
                    if (tick - p.jumpTick >= 3) {
                        if (player.health >= 9.0 && mine.mineBlock() != null) useAbility("wool_mine", hint, false)
                        next(5)
                    }
                }
            }
            // Ride it home: the rocket on the way up, then the double jump toward the ledge.
            else -> {
                lastSafe = p.home
                if (nms.onGround() && age > 6) { endPerch("back on the ground"); return false }
                if (age > 160) { endPerch("the way back took too long"); return false }
                recoveryGoal = p.home.clone()
                recoveryScanTick = tick
                // The rocket goes straight up and cancels the sideways throw: a tick of the blast, then up.
                if (!p.rocketed && age >= 1 && holdUntilTick < 0 && isReady("wooly_rocket") && player.location.y < p.home.y + 1.0) {
                    useAbility("wooly_rocket", AbilityHints.of("wooly_rocket"), false)
                    p.rocketed = true
                } else if (!nms.onGround()) recover(map, true)
            }
        }
        return true
    }

    // ------------------------------------------------------------------ snowman: ice bridge

    /**
     * A Snowman that cannot get to an opponent makes its own way: it goes to a ledge it can reach
     * with open air between it and them, lays an Ice Path aimed at where they stand, and runs
     * across before it melts, jumping from the far end if the ice stops short.
     */
    private class Bridge(val targetId: java.util.UUID, val launch: Location, val landing: Location, var since: Long) {
        /** 0 walking to the ledge, 1 lining up, 2 on the ice, 3 in the jump off its end. */
        var stage = 0
        var origin: Location? = null
        var line: BridgeLine? = null
    }
    /**
     * The straight line an Ice Path takes: unit direction over the ground, blocks risen per block
     * across, how far the ice goes, and whether that is all the way to the landing.
     */
    private class BridgeLine(val ux: Double, val uz: Double, val slope: Double, val reach: Double, val direct: Boolean)
    private var bridge: Bridge? = null
    private var nextBridgeTick = 0L

    /** What the ice would stand on (or be) in this block of the path, or null if the path has a hole or a wall there. */
    private fun bridgeTop(world: org.bukkit.World, x: Int, y: Int, z: Int): Int? {
        val type = world.getBlockAt(x, y, z).type
        var top = y + 1
        // Ice only forms in air and thin snow. Solid ground in the line is a floor as it is;
        // anything else (a plant, water) stays what it is and leaves a hole.
        if (type != org.bukkit.Material.AIR && type != org.bukkit.Material.SNOW) {
            if (!type.isSolid || ArenaTerrain.watery(type)) return null
            while (top - y <= 3 && world.getBlockAt(x, top, z).type.isSolid) top++
            if (top - y > 3) return null
        }
        if (!world.getBlockAt(x, top, z).isPassable || !world.getBlockAt(x, top + 1, z).isPassable) return null
        return top
    }

    /** The Ice Path from [from] toward [landing], if it makes a way a fighter can run. */
    private fun bridgeLine(from: Location, landing: Location): BridgeLine? {
        val world = from.world ?: return null
        val dx = landing.x - from.x
        val dz = landing.z - from.z
        val across = hypot(dx, dz)
        if (across < 3.0 || across > BRIDGE_AHEAD + 6.5) return null
        val ux = dx / across
        val uz = dz / across
        // The ice forms in the block the line passes through, so its top is the next whole block
        // up: aim half a block under the landing and the last of it comes out level with it.
        val rise = landing.y - 0.5 - from.y
        var slope = if (rise <= 0) rise / across else rise / minOf(across, BRIDGE_AHEAD)
        if (rise > 0) slope = rise / minOf(across, BRIDGE_AHEAD / sqrt(1 + slope * slope))
        // Up is a jump for every block of it, so no steeper than one in two; the path itself
        // gives out at one in one.
        if (slope > 0.5 || slope < -1.0) return null
        val reach = BRIDGE_AHEAD / sqrt(1 + slope * slope)
        val direct = across <= reach + 0.3
        val last = if (direct) across - 0.5 else reach
        // The path lifts whoever lays it a block, onto its first ice.
        var prev = kotlin.math.floor(from.y + 1e-6).toInt() + 1
        var top = prev
        var h = 0.8
        while (h <= last) {
            top = bridgeTop(world, kotlin.math.floor(from.x + ux * h).toInt(), kotlin.math.floor(from.y + slope * h).toInt(),
                kotlin.math.floor(from.z + uz * h).toInt()) ?: return null
            if (top - prev > 1 || prev - top > 5) return null
            prev = top
            h += 0.4
        }
        if (direct) {
            if (landing.y - top > 1.05 || top - landing.y > 5.0) return null
        } else {
            // The ice stops short: the rest is a jump from its end, with nothing in the way.
            val end = Location(world, from.x + ux * (reach - 1.5), top.toDouble(), from.z + uz * (reach - 1.5))
            if (across - reach > 5.5 || !jumpReaches(landing, end)) return null
            val eye = end.clone().add(0.0, 1.7, 0.0)
            val gap = landing.clone().add(0.0, 1.7, 0.0).toVector().subtract(eye.toVector())
            if (world.rayTraceBlocks(eye, gap.clone().normalize(), gap.length(), org.bukkit.FluidCollisionMode.NEVER, true) != null) return null
        }
        return BridgeLine(ux, uz, slope, reach, direct)
    }

    /** Looks for a ledge to bridge from, among the places the last failed route search got to. */
    private fun startBridge(t: Player, map: GameMapDef): Boolean {
        if (tick < nextBridgeTick || !d.smartTargeting || "ice_path" !in kitAbilityIds()) return false
        // In the middle of a shot or a jump: look again at the next decision, not in two seconds.
        if (holdUntilTick >= 0 || shot != null || gapJump != null || climb != null || !nms.onGround()) return false
        nextBridgeTick = tick + 40
        if (!isReady("ice_path") || !canDoubleJump() || tick - reachableTick > 1500 || wayBarred(t, "bridge")) return false
        val world = player.world
        // Where they are standing, as the walking graph sees it.
        val node = ArenaNav.of(world, map.voidLevel.toInt(), true).nearest(t.location, 3.0) ?: return false
        val landing = Location(world, node.x + .5, node.y, node.z + .5)
        val here = player.location
        // Nearest to them first, with a little in it for being near the bot.
        val ledges = reachable.asSequence()
            .filter { abs(it.x + .5 - landing.x) <= BRIDGE_AHEAD + 6.5 && abs(it.z + .5 - landing.z) <= BRIDGE_AHEAD + 6.5 && it.y - landing.y in -7.5..14.0 }
            .map { it to hypot(it.x + .5 - landing.x, it.z + .5 - landing.z) }
            .filter { it.second in 3.0..(BRIDGE_AHEAD + 6.5) }
            .sortedBy { (n, across) -> across + 0.25 * hypot(n.x + .5 - here.x, n.z + .5 - here.z) }
            .take(80).toList()
        for ((n, _) in ledges) {
            val launch = Location(world, n.x + .5, n.y, n.z + .5)
            if (bridgeLine(launch, landing) == null) continue
            bridge = Bridge(t.uniqueId, launch, landing, tick)
            prowlGoal = null
            route.clear(); routeSearch = null; nextRouteTick = tick
            comboQueue.clear()
            dbg("no way to ${t.name}: going to ${n.x},${"%.1f".format(n.y)},${n.z} to lay an ice bridge to ${node.x},${"%.1f".format(node.y)},${node.z}")
            return true
        }
        nextBridgeTick = tick + 100
        return false
    }

    private fun endBridge(reason: String, rest: Long = 200) {
        dbg("ice bridge over: $reason")
        bridge = null
        nextBridgeTick = tick + rest
    }

    /** Walks to the ledge the bridge starts from. False once the plan is off and the fight should carry on. */
    private fun approachBridge(b: Bridge, t: Player, map: GameMapDef): Boolean {
        val here = player.location
        if (t.uniqueId != b.targetId || t.world != here.world || hypot(t.x - b.landing.x, t.z - b.landing.z) > 3.5 || abs(t.y - b.landing.y) > 3.0) {
            endBridge("they are not where the bridge was going", 40); return false
        }
        if (tick - b.since > 400 || !isReady("ice_path")) { endBridge("did not get to the ledge with the path ready"); return false }
        // They have not moved: no need to go looking for a route to them again halfway there.
        unreachableUntil[t.uniqueId] = maxOf(unreachableUntil[t.uniqueId] ?: 0L, tick + 100)
        if (nms.onGround() && hypot(b.launch.x - here.x, b.launch.z - here.z) < 0.45 && abs(b.launch.y - here.y) < 1.0) {
            setInputs(0f, 0f, false)
            b.stage = 1; b.since = tick
            return true
        }
        travel(b.launch, map) { endBridge("no way to the ledge") }
        return true
    }

    /** One tick of laying the bridge and running it. False once it is over and the normal brain should carry on. */
    private fun tickBridge(b: Bridge, map: GameMapDef): Boolean {
        val t = target?.takeIf { it.isOnline && it.world == player.world && it.gameMode == GameMode.SURVIVAL && it.uniqueId == b.targetId }
        val here = player.location
        val age = tick - b.since
        fun across() {
            unreachableUntil.remove(b.targetId); unreachableAt.remove(b.targetId)
            route.clear(); routeSearch = null; nextRouteTick = tick
            lastMap?.let { forgetTerrain(b.landing.blockX, b.landing.blockZ, it) }
            endBridge("across", 100)
        }
        fun arrived() = nms.onGround() && hypot(b.landing.x - here.x, b.landing.z - here.z) < 2.2 && abs(here.y - b.landing.y) < 1.5
        when (b.stage) {
            // Stand still, face down the line, lay it.
            1 -> {
                if (t == null || age > 40 || !isReady("ice_path")) { endBridge("could not line the bridge up", 100); return false }
                if (!nms.onGround()) return false
                if (hypot(t.x - b.landing.x, t.z - b.landing.z) > 3.5) { endBridge("they moved", 40); return false }
                setInputs(0f, 0f, false)
                val line = bridgeLine(here, b.landing) ?: run { endBridge("no clear line from here", 60); return false }
                val yaw = Math.toDegrees(atan2(-line.ux, line.uz)).toFloat()
                val pitch = (-Math.toDegrees(atan2(line.slope, 1.0))).toFloat()
                turnTowards(yaw, pitch, d.turnSpeed * 2)
                val v = nms.deltaMovement
                if (abs(wrapDegrees(yaw - nms.yRot)) < 4f && abs(pitch - nms.xRot) < 4f && hypot(v.x, v.z) < 0.03) {
                    // The path goes exactly where the bot is looking: no rounding off.
                    nms.setYRot(yaw); nms.setYHeadRot(yaw); nms.setXRot(pitch)
                    useAbility("ice_path", AbilityHints.of("ice_path"), false)
                    if (isReady("ice_path")) { endBridge("the path would not lay", 100); return false }
                    b.origin = here.clone(); b.line = line
                    b.stage = 2; b.since = tick
                    dbg("ice bridge laid toward ${"%.1f %.1f %.1f".format(b.landing.x, b.landing.y, b.landing.z)}" +
                        " (${"%.1f".format(hypot(b.landing.x - here.x, b.landing.z - here.z))} across, ice for ${"%.1f".format(line.reach)})")
                }
            }
            // Run it. The ice forms a block a tick ahead of the runner and melts six seconds later.
            2 -> {
                val o = b.origin!!
                val line = b.line!!
                lastSafe = b.launch
                if (arrived()) { across(); return false }
                val h = (here.x - o.x) * line.ux + (here.z - o.z) * line.uz
                if (age > 220 || (age > 25 && here.y < o.y + line.slope * h - 3.0)) { endBridge("fell off or ran out of time"); return false }
                // Keep to the middle of it: head for a point on the line a few blocks on.
                val on = h + 3.0
                val (yaw, _) = lookAngles(player.eyeLocation, Location(here.world, o.x + line.ux * on, here.y + 1.6, o.z + line.uz * on))
                turnTowards(yaw, 0f, 30f)
                // Lifted and dropped back onto the first of the ice: nothing to do until it lands.
                if (!nms.onGround() && age < 16) { setInputs(0f, 0f, false); return true }
                fun iceAt(ahead: Double) = hasSolidBelow(Location(here.world, here.x + line.ux * ahead, here.y + 0.6, here.z + line.uz * ahead), 6)
                val toEnd = line.reach - h
                if (!line.direct && toEnd < 1.6) {
                    // The end of the ice: the rest is a jump, taken before the bot slides off.
                    if (nms.onGround() && jumpReaches(b.landing) && clearHop(b.landing, map)) {
                        b.stage = 3; b.since = tick
                        dbg("end of the ice: jumping the last ${"%.1f".format(hypot(b.landing.x - here.x, b.landing.z - here.z))} blocks")
                        startGapJump(Gap(hypot(b.landing.x - here.x, b.landing.z - here.z), b.landing))
                        return true
                    }
                    setInputs(0f, 0f, false)
                }
                // Ice slides: only as fast as there is ice to stop on.
                val forward = if (iceAt(1.0) && iceAt(2.6)) 1f else if (iceAt(1.0)) 0.7f else 0f
                setInputs(forward, 0f, forward > 0f && nms.horizontalCollision && nms.onGround())
            }
            // The jump off the end has finished, one way or the other.
            else -> {
                if (arrived()) { across(); return false }
                endBridge(if (nms.onGround()) "came down somewhere else" else "the jump off the end fell short")
                return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ crossings: a way of its own to someone no route leads to

    private enum class CrossKind {
        /** Off a ledge, down past their hiding place, and an air jump in under the overhang. */
        DROP,
        /** The same drop, and an Instant Teleporter straight onto their ledge from the air beside it. */
        TELEPORT,
        /** The same drop, and a Cloud in a Bottle beside their ledge to walk or jump in from. */
        CLOUD,
        /** Straight through the air on a Jetpack. */
        FLIGHT,
        /** Straight up off a Pocket Trampoline at the foot of their perch. */
        TRAMPOLINE,
        /** A kit with several air jumps (a chicken): off a ledge, down past their hiding place, and in under it on the wing. */
        FLAP,
    }

    /**
     * What a player does about someone camping where nothing walks: gets there anyway. From
     * [launch], a ledge the bot can walk to, through [air] (the point beside their hiding place
     * where the drop turns into the way in; unused by a flight or a trampoline), onto [landing].
     */
    private class Crossing(val kind: CrossKind, val targetId: java.util.UUID, val launch: Location, val air: Location, val landing: Location, var since: Long) {
        /** 0 walking to the ledge, 1 leaving it, 2 in the air on the way to the turn (or flying), 3 on the way in, 4 on its cloud, 5 in the jump off the cloud. */
        var stage = 0
        var nextBoost = 0L
        var boosts = 0
        /** Air jumps a flight on the wing sets off with. */
        var need = 0
    }
    private var crossing: Crossing? = null
    private var nextCrossingTick = 0L

    /** Looks for a crossing to an opponent no route leads to, with whatever the bot is carrying and without. */
    private fun startCrossing(t: Player, map: GameMapDef): Boolean {
        if (tick < nextCrossingTick || !d.smartTargeting) return false
        // In the middle of a shot or a jump: look again at the next decision.
        if (holdUntilTick >= 0 || shot != null || gapJump != null || climb != null || perch != null || !nms.onGround()) return false
        nextCrossingTick = tick + 60
        if (!canDoubleJump() || reachable.isEmpty() || tick - reachableTick > 1500) return false
        val world = player.world
        // Where they are standing, as the walking graph sees it.
        val node = ArenaNav.of(world, map.voidLevel.toInt(), true).nearest(t.location, 3.0) ?: return false
        val landing = Location(world, node.x + .5, node.y, node.z + .5)
        val have = carriedSlot()?.let { ItemService.kind(player.inventory.getItem(it)) }
        val kinds = listOfNotNull(
            CrossKind.FLIGHT.takeIf { have == ArenaItem.JETPACK },
            CrossKind.TELEPORT.takeIf { have == ArenaItem.INSTANT_TELEPORTER },
            CrossKind.CLOUD.takeIf { have == ArenaItem.CLOUD_IN_A_BOTTLE },
            CrossKind.TRAMPOLINE.takeIf { have == ArenaItem.POCKET_TRAMPOLINE },
            CrossKind.DROP,
            CrossKind.FLAP.takeIf { KitService.getKitForPlayer(player)?.jumpPassive() is dev.betrix.superSmashMobsBrawl.passives.FlapPassive },
        )
        var need = 0
        for (kind in kinds) {
            // A way in that has already cost a life against them here is not tried a second time.
            if (wayBarred(t, "cross:$kind")) continue
            val (launch, air) = when (kind) {
                CrossKind.FLIGHT -> flightFrom(landing, map)?.let { it to landing }
                CrossKind.TRAMPOLINE -> padFor(landing, map)?.let { it to landing }
                CrossKind.FLAP -> flapFor(landing, map)?.let { (ledge, turn, flaps) -> need = flaps; ledge to turn }
                else -> dropFor(kind, landing, map)
            } ?: continue
            crossing = Crossing(kind, t.uniqueId, launch, air, landing, tick).also { it.need = need }
            prowlGoal = null
            route.clear(); routeSearch = null; nextRouteTick = tick
            comboQueue.clear()
            dbg("no way to ${t.name}: going to ${"%.1f %.1f %.1f".format(launch.x, launch.y, launch.z)} for a ${kind.name.lowercase()} crossing to " +
                "${"%.1f %.1f %.1f".format(landing.x, landing.y, landing.z)}" +
                if (kind == CrossKind.FLIGHT || kind == CrossKind.TRAMPOLINE) "" else " by way of ${"%.1f %.1f".format(air.x, air.z)}")
            return true
        }
        nextCrossingTick = tick + 100
        return false
    }

    /**
     * A drop past their hiding place: a column of open air beside [landing], with a ledge the bot
     * can walk to right next to its top, from which the last part (an air jump, a teleport, a
     * cloud) gets in to them. Returns the ledge and the column (at the height of the landing).
     */
    private fun dropFor(kind: CrossKind, landing: Location, map: GameMapDef): Pair<Location, Location>? {
        val world = landing.world ?: return null
        val nav = ArenaNav.of(world, map.voidLevel.toInt(), mobility())
        val index = reachableByColumn()
        val here = player.location
        val radii = when (kind) {
            CrossKind.TELEPORT -> doubleArrayOf(3.0, 5.0, 8.0, 12.0, 16.0)
            CrossKind.CLOUD -> doubleArrayOf(2.5, 3.5, 5.0, 6.5, 8.0)
            else -> doubleArrayOf(2.0, 3.0, 4.0, 5.0, 6.0)
        }
        var best: Pair<Location, Location>? = null
        var bestScore = Double.MAX_VALUE
        for (r in radii) for (i in 0 until 16) {
            val a = i * Math.PI / 8
            val px = landing.x + cos(a) * r
            val pz = landing.z + sin(a) * r
            // Open air from the height of the landing up to wherever the bot steps off.
            if (!nav.clear(px, landing.y + 0.25, pz)) continue
            var open = landing.y + 0.25
            while (open < landing.y + 24.0 && nav.clear(px, open + 0.5, pz)) open += 0.5
            if (open < landing.y + 1.75) continue
            // A ledge right beside the top of it: near enough to step off and steer onto the column.
            var ledge: RouteNode? = null
            var ledgeScore = bestScore
            val bx = kotlin.math.floor(px).toInt()
            val bz = kotlin.math.floor(pz).toInt()
            for (dx in -3..3) for (dz in -3..3) for (n in index[columnKey(bx + dx, bz + dz)] ?: continue) {
                val off = hypot(n.x + .5 - px, n.z + .5 - pz)
                val fall = n.y - landing.y
                if (fall < 1.5 || n.y > open || off < 0.9 || off > (if (fall >= 3.0) 2.6 else 1.8)) continue
                val score = r * 2 + off + 0.3 * hypot(n.x + .5 - here.x, n.z + .5 - here.z)
                if (score >= ledgeScore || !nav.clear((n.x + .5 + px) / 2, n.y + 0.05, (n.z + .5 + pz) / 2)) continue
                ledge = n
                ledgeScore = score
            }
            val from = ledge ?: continue
            val air = Location(world, px, landing.y, pz)
            if (!dropWorks(kind, air, landing, open, map)) continue
            best = Location(world, from.x + .5, from.y, from.z + .5) to air
            bestScore = ledgeScore
        }
        return best
    }

    /**
     * The same drop for a kit that flies: the column of open air may be a long way from their
     * hiding place, as long as there is room to fly in a straight line from the foot of it to
     * them, at the height of their floor. Looked for outward from where they stand, so the
     * nearest way in is found first. Returns the ledge, the column and the flaps to set off with.
     */
    private fun flapFor(landing: Location, map: GameMapDef): Triple<Location, Location, Int>? {
        val flap = KitService.getKitForPlayer(player)?.jumpPassive() as? dev.betrix.superSmashMobsBrawl.passives.FlapPassive ?: return null
        val world = landing.world ?: return null
        val nav = ArenaNav.of(world, map.voidLevel.toInt(), mobility())
        val index = reachableByColumn()
        val here = player.location
        val most = flap.capacity()
        var best: Triple<Location, Location, Int>? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until 32) {
            val a = i * Math.PI / 16
            val ux = cos(a)
            val uz = sin(a)
            var half = 2
            while (true) {
                val r = half * 0.5
                // One flap for the turn at the foot of the column, one in hand, and the flying.
                val flaps = ceil(r / FLAP_STRIDE).toInt() + 2
                if (flaps > most || r * FLAP_COST >= bestScore) break
                val px = landing.x + ux * r
                val pz = landing.z + uz * r
                // The way in: room for a fighter at the height of their floor, all the way out to here.
                if (!nav.clear(px, landing.y + 0.2, pz) || !nav.clear(px, landing.y + 0.9, pz)) break
                half++
                if (r < 2.5 || half % 2 == 0) continue
                // Open air from there up to wherever the bot steps off.
                var open = landing.y + 0.25
                while (open < landing.y + 30.0 && nav.clear(px, open + 0.5, pz)) open += 0.5
                if (open < landing.y + 2.0) continue
                val bx = kotlin.math.floor(px).toInt()
                val bz = kotlin.math.floor(pz).toInt()
                for (dx in -3..3) for (dz in -3..3) for (n in index[columnKey(bx + dx, bz + dz)] ?: continue) {
                    val off = hypot(n.x + .5 - px, n.z + .5 - pz)
                    val fall = n.y - landing.y
                    if (fall < 1.5 || n.y > open || off < 0.9 || off > (if (fall >= 3.0) 2.6 else 1.8)) continue
                    val score = r * FLAP_COST + off + 0.3 * hypot(n.x + .5 - here.x, n.z + .5 - here.z)
                    if (score >= bestScore || !nav.clear((n.x + .5 + px) / 2, n.y + 0.05, (n.z + .5 + pz) / 2)) continue
                    best = Triple(Location(world, n.x + .5, n.y, n.z + .5), Location(world, px, landing.y, pz), flaps)
                    bestScore = score
                }
            }
        }
        // Or straight across open air to them, from a ledge at about their height or below it:
        // the gap a single air jump does not cover is a few flaps wide.
        val ledges = reachable.asSequence()
            .map { it to hypot(it.x + .5 - landing.x, it.z + .5 - landing.z) }
            .filter { (n, across) -> across >= 3.0 && across <= FLAP_STRIDE * (most - 2) && landing.y - n.y >= -2.0 && landing.y - n.y <= 12.0 }
            .sortedBy { (n, across) -> across + 0.25 * hypot(n.x + .5 - here.x, n.z + .5 - here.z) }
            .take(40).toList()
        for ((n, across) in ledges) {
            val rise = landing.y - n.y
            val flaps = ceil(across / FLAP_STRIDE).toInt() + ceil(rise.coerceAtLeast(0.0) / 3.0).toInt() + 1
            val score = across * FLAP_COST + rise.coerceAtLeast(0.0) + 0.3 * hypot(n.x + .5 - here.x, n.z + .5 - here.z)
            if (flaps > most || score >= bestScore) continue
            val ux = (landing.x - (n.x + .5)) / across
            val uz = (landing.z - (n.z + .5)) / across
            var h = 1.0
            var open = true
            while (open && h <= across - 0.8) {
                val y = n.y + rise * h / across
                open = nav.clear(n.x + .5 + ux * h, y + 0.6, n.z + .5 + uz * h) && nav.clear(n.x + .5 + ux * h, y + 2.4, n.z + .5 + uz * h)
                h += 0.5
            }
            // Off the edge of something, not across a floor: there must be a drop on the way.
            if (!open || (1..3).all { hasSolidBelow(Location(world, n.x + .5 + ux * it, n.y + 0.5, n.z + .5 + uz * it), 3) }) continue
            best = Triple(Location(world, n.x + .5, n.y, n.z + .5), landing.clone(), flaps)
            bestScore = score
        }
        return best
    }

    /** A flap toward [p], looking [pitch] degrees (0 is level: as far as one goes). */
    private fun flapAt(p: Location, pitch: Double, reason: String) {
        val (yaw, _) = lookAngles(player.eyeLocation, p)
        nms.setYRot(yaw)
        nms.setYHeadRot(yaw)
        nms.setXRot(pitch.toFloat())
        doubleJump(reason)
    }

    /**
     * Flies a flap from here toward [p], looking [pitch] degrees, the way the game would: up or
     * down first, then across. Every flap goes up three blocks if it can, so under a low roof
     * the question is what hangs in the way: a roof overhead only stops the rise, but the side
     * of a block is the end of the flap. 1 = it comes down on [p]; 0 = it flies free for as long
     * as a flap lasts; -1 = it runs into something, or comes down somewhere else.
     */
    private fun flapOutcome(p: Location, pitch: Double, map: GameMapDef): Int {
        val here = player.location
        val r = hypot(p.x - here.x, p.z - here.z)
        if (r < 0.3) return 1
        val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
        val ux = (p.x - here.x) / r
        val uz = (p.z - here.z) / r
        var (vh, vy) = jumpLaunch(pitch)
        var along = 0.0
        var y = here.y
        for (t in 1..16) {
            val x = here.x + ux * along
            val z = here.z + uz * along
            if (nav.clear(x, y + vy, z)) y += vy
            else if (vy > 0) vy = 0.0
            // Down on something: their floor, or not.
            else return if (r - along < 1.0 && abs(y - p.y) < 1.2) 1 else -1
            // Against a wall: beside their floor and over it is as good as on it.
            if (!nav.clear(x + ux * vh, y, z + uz * vh)) return if (r - along < 0.9 && y >= p.y - 0.1 && y < p.y + 2.5) 1 else -1
            along += vh
            vh = vh * 0.91 + 0.02
            vy = (vy - 0.08) * 0.98
            if (along >= r - 0.3) return if (y < p.y - 0.2) -1 else if (y <= p.y + 2.5) 1 else 0
        }
        // Still in the air: free flight, unless it has lost height it did not have to spare.
        return if (y >= minOf(p.y, here.y) - 2.0) 0 else -1
    }

    /** Does the last part of a drop get from the column at [air] onto [landing]? */
    private fun dropWorks(kind: CrossKind, air: Location, landing: Location, open: Double, map: GameMapDef): Boolean = when (kind) {
        CrossKind.DROP -> {
            // Two heights running from which the air jump comes down on the spot: the bot is falling, and gets one go.
            var run = 0
            var found = false
            for (h in doubleArrayOf(3.0, 2.25, 1.5, 0.75, 0.0, -0.75)) {
                run = if (landing.y + h <= open && airJumpPitch(air.clone().add(0.0, h, 0.0), landing, map) != null) run + 1 else 0
                if (run >= 2) found = true
            }
            found
        }
        CrossKind.TELEPORT -> doubleArrayOf(1.5, 0.5).all { teleportLands(air.clone().add(0.0, it, 0.0), landing, 1.0) != null }
        CrossKind.CLOUD -> {
            val world = air.world
            // The platform comes out level with their floor, or a step over it.
            val top = ceil(landing.y - 0.01)
            val r = hypot(landing.x - air.x, landing.z - air.z)
            if (world == null || !world.getBlockAt(air.blockX, top.toInt() - 1, air.blockZ).isEmpty) false
            // It is five blocks square: a ledge this close is a step away.
            else if (r <= 3.4) true
            else {
                // Otherwise a jump off its near edge, with nothing in the way.
                val edge = Location(world, air.x + (landing.x - air.x) / r * 1.8, top, air.z + (landing.z - air.z) / r * 1.8)
                val eye = edge.clone().add(0.0, 1.7, 0.0)
                val gap = landing.clone().add(0.0, 1.7, 0.0).toVector().subtract(eye.toVector())
                jumpReaches(landing, edge) &&
                    world.rayTraceBlocks(eye, gap.clone().normalize(), gap.length(), org.bukkit.FluidCollisionMode.NEVER, true) == null
            }
        }
        else -> false
    }

    /** The pitch for an air jump from [from] (in the air) that comes down on [p] with nothing in the way, or null. */
    private fun airJumpPitch(from: Location, p: Location, map: GameMapDef): Float? {
        val r = hypot(p.x - from.x, p.z - from.z)
        var best: Float? = null
        var bestErr = Double.MAX_VALUE
        var pitch = 20.0
        while (pitch >= -88.0) {
            val (horizontal, vertical) = jumpLaunch(pitch)
            if (peakHeight(vertical, from.y) >= p.y + 0.3) {
                val dist = coastDistance(horizontal, ticksUntilBelow(p.y, vertical, from.y))
                // Steering in the air makes up a little either way; more than that is a miss.
                val err = abs(dist - r)
                if (dist >= r - 0.8 && dist <= r + 2.0 && err < bestErr && arcLands(from, p, horizontal, vertical, map)) {
                    bestErr = err
                    best = pitch.toFloat()
                }
            }
            pitch -= 4.0
        }
        return best
    }

    /**
     * Flies an air jump from [from] at this launch speed toward [p] and says whether it comes
     * down there. Unlike [arcClear] this is for a jump in under an overhang with the void below:
     * a wall on the way is the end of it, and landing on anything but the spot is a miss.
     */
    private fun arcLands(from: Location, p: Location, horizontal: Double, vertical: Double, map: GameMapDef): Boolean {
        val r = hypot(p.x - from.x, p.z - from.z)
        if (r < 0.3) return true
        val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
        val ux = (p.x - from.x) / r
        val uz = (p.z - from.z) / r
        var along = 0.0
        var y = from.y
        var vh = horizontal
        var vy = vertical
        for (t in 1..60) {
            if (!nav.clear(from.x + ux * along, y + vy, from.z + uz * along)) return vy <= 0 && along >= r - 1.2 && abs(y - p.y) < 1.3
            y += vy
            if (!nav.clear(from.x + ux * (along + vh), y, from.z + uz * (along + vh))) return false
            along += vh
            vh = vh * 0.91 + 0.02
            vy = (vy - 0.08) * 0.98
            if (along >= r - 0.3) return y >= p.y - 0.2
            if (vy < 0 && y < p.y - 0.5) return false
        }
        return false
    }

    /** A ledge to take off from with a Jetpack: open air in a straight line to [landing], and fuel for it. */
    private fun flightFrom(landing: Location, map: GameMapDef): Location? {
        val world = landing.world ?: return null
        val fuel = carriedSlot()?.let { ItemService.uses(player.inventory.getItem(it)) } ?: return null
        val nav = ArenaNav.of(world, map.voidLevel.toInt(), mobility())
        val here = player.location
        val ledges = reachable.asSequence()
            .map { it to hypot(it.x + .5 - landing.x, it.z + .5 - landing.z) }
            .filter { (n, across) -> across in 4.0..34.0 && landing.y - n.y < 20.0 && n.y - landing.y < 12.0 }
            .sortedBy { (n, across) -> across + 0.25 * hypot(n.x + .5 - here.x, n.z + .5 - here.z) }
            .take(60).toList()
        for ((n, across) in ledges) {
            val rise = landing.y - n.y
            // A burst lifts about five blocks and lasts about five across; the jump off the ledge covers the first few.
            val bursts = ceil((across - 4.0).coerceAtLeast(0.0) / 5.0).toInt() + ceil(rise.coerceAtLeast(0.0) / 4.0).toInt() + (if (rise < -5.0) 1 else 0)
            if (bursts + 1 > fuel) continue
            val ux = (landing.x - (n.x + .5)) / across
            val uz = (landing.z - (n.z + .5)) / across
            var h = 1.0
            var open = true
            while (open && h <= across - 0.8) {
                val y = n.y + rise * h / across
                open = nav.clear(n.x + .5 + ux * h, y + 0.6, n.z + .5 + uz * h) && nav.clear(n.x + .5 + ux * h, y + 2.4, n.z + .5 + uz * h)
                h += 0.5
            }
            if (open) return Location(world, n.x + .5, n.y, n.z + .5)
        }
        return null
    }

    /** Somewhere at the foot of their perch to go straight up from on a Pocket Trampoline, and across onto it. */
    private fun padFor(landing: Location, map: GameMapDef): Location? {
        val world = landing.world ?: return null
        val nav = ArenaNav.of(world, map.voidLevel.toInt(), mobility())
        val index = reachableByColumn()
        val here = player.location
        var best: Location? = null
        var bestScore = Double.MAX_VALUE
        for (dx in -5..5) for (dz in -5..5) for (n in index[columnKey(landing.blockX + dx, landing.blockZ + dz)] ?: continue) {
            val off = hypot(n.x + .5 - landing.x, n.z + .5 - landing.z)
            val rise = landing.y - n.y
            if (off < 1.2 || off > 5.0 || rise < 2.5 || rise > 12.0) continue
            val score = off + 0.3 * hypot(n.x + .5 - here.x, n.z + .5 - here.z)
            if (score >= bestScore) continue
            var open = true
            var y = n.y + 0.25
            while (open && y <= landing.y + 1.5) { open = nav.clear(n.x + .5, y, n.z + .5); y += 0.5 }
            val steps = (off * 3).toInt().coerceAtLeast(2)
            for (i in 1..steps) {
                if (!open) break
                val part = i.toDouble() / steps
                open = nav.clear(n.x + .5 + (landing.x - n.x - .5) * part, landing.y + 0.6, n.z + .5 + (landing.z - n.z - .5) * part)
            }
            if (!open) continue
            best = Location(world, n.x + .5, n.y, n.z + .5)
            bestScore = score
        }
        return best
    }

    private fun endCrossing(reason: String, rest: Long = 200) {
        dbg("crossing over: $reason")
        crossing = null
        nextCrossingTick = tick + rest
    }

    /** Still carrying what this crossing is made with? */
    private fun crossingItem(c: Crossing): Boolean {
        val need = when (c.kind) {
            CrossKind.FLIGHT -> ArenaItem.JETPACK
            CrossKind.TELEPORT -> ArenaItem.INSTANT_TELEPORTER
            CrossKind.CLOUD -> ArenaItem.CLOUD_IN_A_BOTTLE
            CrossKind.TRAMPOLINE -> ArenaItem.POCKET_TRAMPOLINE
            CrossKind.DROP, CrossKind.FLAP -> return true
        }
        return carriedSlot()?.let { ItemService.kind(player.inventory.getItem(it)) } == need
    }

    /** Uses the carried item if it is a [kind]. True if a use of it went. */
    private fun useCarried(kind: dev.betrix.superSmashMobsBrawl.items.ArenaItem): Boolean {
        val slot = carriedSlot() ?: return false
        if (ItemService.kind(player.inventory.getItem(slot)) != kind) return false
        val before = ItemService.uses(player.inventory.getItem(slot))
        spendItem(slot, kind, null)
        return ItemService.kind(player.inventory.getItem(slot)) != kind || ItemService.uses(player.inventory.getItem(slot)) < before
    }

    /** Walks to the ledge the crossing starts from. False once the plan is off and the fight should carry on. */
    private fun approachCrossing(c: Crossing, t: Player, map: GameMapDef): Boolean {
        val here = player.location
        if (t.uniqueId != c.targetId || t.world != here.world || hypot(t.x - c.landing.x, t.z - c.landing.z) > 3.5 || abs(t.y - c.landing.y) > 3.0) {
            endCrossing("they are not where it was going", 20); return false
        }
        if (tick - c.since > 400 || !crossingItem(c)) { endCrossing("did not get to the ledge with what it takes"); return false }
        // They have not moved: no need to go looking for a route to them again halfway there.
        unreachableUntil[t.uniqueId] = maxOf(unreachableUntil[t.uniqueId] ?: 0L, tick + 100)
        if (nms.onGround() && hypot(c.launch.x - here.x, c.launch.z - here.z) < 0.45 && abs(c.launch.y - here.y) < 1.0) {
            setInputs(0f, 0f, false)
            // With the air jump in hand, or not at all. A flight waits here for its flaps to come back.
            if (canDoubleJump() && (c.kind != CrossKind.FLAP || airJumpsLeft() >= c.need)) { c.stage = 1; c.since = tick }
            return true
        }
        travel(c.launch, map) { endCrossing("no way to the ledge") }
        return true
    }

    /** One tick of a crossing under way. False once it is over and the normal brain should carry on. */
    private fun tickCrossing(c: Crossing, map: GameMapDef): Boolean {
        val here = player.location
        val age = tick - c.since
        val v = nms.deltaMovement
        val toLanding = hypot(c.landing.x - here.x, c.landing.z - here.z)
        fun arrived() = nms.onGround() && toLanding < 2.2 && abs(here.y - c.landing.y) < 1.5
        fun across(): Boolean { arrivedAt(c.targetId, c.landing); endCrossing("across", 60); return false }
        fun yawTo(p: Location) = lookAngles(player.eyeLocation, Location(here.world, p.x, here.y + 1.6, p.z)).first
        lastSafe = c.launch
        when (c.stage) {
            // Off the ledge.
            1 -> {
                if (age > 60) { endCrossing("could not get off the ledge"); return false }
                when (c.kind) {
                    CrossKind.TRAMPOLINE -> {
                        if (!nms.onGround()) return false
                        setInputs(0f, 0f, false)
                        turnTowards(yawTo(c.landing), 0f, d.turnSpeed * 2)
                        if (age >= 3 && hypot(v.x, v.z) < 0.03) {
                            if (!useCarried(ArenaItem.POCKET_TRAMPOLINE)) { endCrossing("the trampoline would not go off"); return false }
                            dbg("trampoline: up ${"%.1f".format(c.landing.y - here.y)} blocks to their ledge")
                            c.stage = 3; c.since = tick
                        }
                    }
                    CrossKind.FLIGHT -> {
                        if (!nms.onGround()) { c.stage = 2; c.since = tick; return true }
                        val yaw = yawTo(c.landing)
                        turnTowards(yaw, 0f, d.turnSpeed * 2)
                        if (abs(wrapDegrees(yaw - nms.yRot)) < 6f) {
                            dbg("jetpack: taking off for ${"%.1f".format(toLanding)} blocks of air")
                            noteJump("flight")
                            setInputs(1f, 0f, true)
                        } else setInputs(0f, 0f, false)
                    }
                    else -> {
                        if (c.kind == CrossKind.FLAP && c.air.distanceSquared(c.landing) < 0.01) {
                            // Straight across: a running jump at them, and on the wing from the top of it.
                            if (!nms.onGround()) { c.stage = 6; c.since = tick; return true }
                            val yaw = yawTo(c.landing)
                            turnTowards(yaw, 0f, d.turnSpeed * 2)
                            if (abs(wrapDegrees(yaw - nms.yRot)) < 6f) {
                                if (age % 20 == 3L) dbg("taking off for ${"%.1f".format(toLanding)} blocks of air on the wing")
                                noteJump("flight")
                                setInputs(1f, 0f, true)
                            } else setInputs(0f, 0f, false)
                            return true
                        }
                        if (!nms.onGround()) {
                            c.stage = 2; c.since = tick
                            dbg("off the ledge: ${"%.1f".format(here.y - c.landing.y)} blocks down to the turn")
                            return true
                        }
                        val yaw = yawTo(c.air)
                        turnTowards(yaw, 0f, d.turnSpeed * 2)
                        setInputs(if (abs(wrapDegrees(yaw - nms.yRot)) < 12f) 0.6f else 0f, 0f, false)
                    }
                }
            }
            2 -> if (c.kind == CrossKind.FLIGHT) {
                if (nms.onGround() && age > 3) { if (arrived()) return across(); endCrossing("came down somewhere else"); return false }
                if (age > 320 || here.y < minOf(c.launch.y, c.landing.y) - 8.0) { endCrossing("the flight came to nothing"); return false }
                turnTowards(yawTo(c.landing), 0f, 30f)
                val (f, s) = if (toLanding < 3.0) steerTo(c.landing) else 1f to 0f
                setInputs(f, s, false)
                // Never under the straight line from the ledge to their floor, and over the lip at the end.
                val total = hypot(c.landing.x - c.launch.x, c.landing.z - c.launch.z).coerceAtLeast(1.0)
                val line = c.launch.y + (c.landing.y - c.launch.y) * (total - toLanding).coerceIn(0.0, total) / total
                val low = here.y < maxOf(line, if (toLanding < 6.0) c.landing.y else line) + 0.5
                val over = toLanding < 1.0 && here.y >= c.landing.y - 0.1
                if (v.y < -0.05 && low && !over && tick >= c.nextBoost) {
                    if (useCarried(ArenaItem.JETPACK)) {
                        c.nextBoost = tick + 9
                        c.boosts++
                        dbg("jetpack burst ${c.boosts}, ${"%.1f".format(toLanding)} blocks to go")
                    } else if (canDoubleJump() && tick - lastDoubleJumpTick > 6) {
                        aimedDoubleJump(c.landing, "flight, out of fuel")
                        c.nextBoost = tick + 20
                    } else if (here.y < c.landing.y - 2.0) { endCrossing("out of fuel"); return false }
                }
            } else {
                // Down the column beside their hiding place, to the height where the way in starts.
                if (nms.onGround()) { if (arrived()) return across(); endCrossing("came down somewhere else"); return false }
                if (age > 80) { endCrossing("the drop took too long"); return false }
                turnTowards(yawTo(c.landing), 0f, 40f)
                val (f, s) = steerTo(Location(here.world, c.air.x, c.landing.y + 1.0, c.air.z))
                setInputs(f, s, false)
                when (c.kind) {
                    CrossKind.DROP -> if (here.y <= c.landing.y + 3.2) {
                        val pitch = if (canDoubleJump()) airJumpPitch(here, c.landing, map) else null
                        if (pitch != null) {
                            val (yaw, _) = lookAngles(player.eyeLocation, c.landing)
                            nms.setYRot(yaw); nms.setYHeadRot(yaw); nms.setXRot(pitch)
                            doubleJump("in under the ledge, ${"%.1f".format(toLanding)} blocks, pitch ${"%.0f".format(pitch)}")
                            c.stage = 3; c.since = tick
                        } else if (here.y < c.landing.y - 1.0) {
                            // Too low for the jump it planned: the best one there is.
                            if (canDoubleJump()) aimedDoubleJump(c.landing, "in under the ledge, late")
                            c.stage = 3; c.since = tick
                        }
                    }
                    CrossKind.FLAP -> if (here.y + v.y * 2 <= c.landing.y + 3.0) {
                        // Coming down to the height of their floor: the turn, and the way in on the wing.
                        if (!canDoubleJump()) { endCrossing("no flap left for the turn"); return false }
                        dbg("at the turn, ${"%.1f".format(toLanding)} blocks to fly")
                        c.stage = 6; c.since = tick
                    }
                    CrossKind.TELEPORT -> if (here.y <= c.landing.y + 2.2) {
                        val slot = carriedSlot()
                        if (slot != null && teleportTo(c.landing, slot, 1.0)) {
                            dbg("teleported in from beside their ledge")
                            c.stage = 3; c.since = tick
                        } else if (here.y < c.landing.y - 1.0) {
                            if (canDoubleJump()) aimedDoubleJump(c.landing, "in under the ledge, late")
                            c.stage = 3; c.since = tick
                        }
                    }
                    else -> {
                        // The cloud forms under the feet: popped in the block over their floor it comes out level with it.
                        val top = ceil(c.landing.y - 0.01)
                        if (here.y < top + 1.0 || here.y + v.y < top) {
                            if (here.y < top - 3.0 || !useCarried(ArenaItem.CLOUD_IN_A_BOTTLE)) { endCrossing("missed the height for the cloud"); return false }
                            dbg("cloud beside their ledge, ${"%.1f".format(toLanding)} blocks from them")
                            forgetTerrain(here.blockX, here.blockZ, map)
                            c.stage = 4; c.since = tick
                        }
                    }
                }
            }
            // The last of the way in, steering onto their ledge.
            3 -> {
                if (nms.onGround() && age > 2) { if (arrived()) return across(); endCrossing("came down somewhere else"); return false }
                val floor = if (c.kind == CrossKind.TRAMPOLINE) c.launch.y - 3.0 else c.landing.y - 4.0
                if (age > 90 || here.y < floor) { endCrossing("fell short"); return false }
                turnTowards(yawTo(c.landing), 0f, 40f)
                val (f, s) = steerTo(c.landing)
                setInputs(f, s, false)
                // A trampoline that stops short of their floor: the air jump does the rest.
                if (c.kind == CrossKind.TRAMPOLINE && v.y < 0.15 && here.y < c.landing.y + 0.4 && canDoubleJump() && tick - lastDoubleJumpTick > 6)
                    aimedDoubleJump(c.landing, "off the trampoline")
            }
            // On the wing, in under whatever they are hiding beneath: a flap whenever it is about
            // to sink below their floor short of them, and the last one aimed onto it.
            6 -> {
                if (nms.onGround() && age > 2) { if (arrived()) return across(); endCrossing("came down somewhere else"); return false }
                if (age > 240 || here.y < minOf(c.landing.y, c.launch.y) - 5.0) { endCrossing("fell short"); return false }
                turnTowards(yawTo(c.landing), 0f, 40f)
                val (f, s) = if (toLanding < 3.0) steerTo(c.landing) else 1f to 0f
                setInputs(f, s, false)
                if (v.y < 0.0 && canDoubleJump() && tick - lastDoubleJumpTick > 6 && here.y + v.y * 2 < c.landing.y + 0.4) {
                    val glide = coastDistance(hypot(v.x, v.z), ticksUntilBelow(c.landing.y, v.y, here.y))
                    if (glide < toLanding - 0.4 || here.y < c.landing.y - 0.2) {
                        // The flap that comes down on their floor if there is one from here, else
                        // a level one that flies free. With neither, sink a little further: from
                        // lower down the same flap goes under whatever hangs in the way.
                        val pitches = if (toLanding <= FLAP_LAST + 2.0) FLAP_PITCHES else FLAP_LEVEL
                        val tried = pitches.map { it to flapOutcome(c.landing, it, map) }
                        val pick = tried.firstOrNull { it.second == 1 } ?: tried.firstOrNull { it.second == 0 }
                        if (pick != null) {
                            c.boosts++
                            flapAt(c.landing, pick.first, "flap ${c.boosts}${if (pick.second == 1) " onto their ledge" else ""}, " +
                                "${"%.1f".format(toLanding)} blocks to go, pitch ${"%.0f".format(pick.first)}")
                        } else if (here.y < minOf(c.landing.y, c.launch.y) - FLAP_DIP) {
                            c.boosts++
                            aimedDoubleJump(c.landing, "flap ${c.boosts}, nothing better")
                        }
                    }
                }
            }
            // On the cloud: it lasts five seconds.
            4 -> {
                if (arrived()) return across()
                if (age > 70) { endCrossing("ran out of cloud"); return false }
                if (!nms.onGround()) {
                    if (age > 12) { endCrossing("fell off the cloud"); return false }
                    setInputs(0f, 0f, false)
                    return true
                }
                if (toLanding > 1.5 && jumpReaches(c.landing) && clearHop(c.landing, map)) {
                    c.stage = 5; c.since = tick
                    startGapJump(Gap(toLanding, c.landing))
                    return true
                }
                val yaw = yawTo(c.landing)
                turnTowards(yaw, 0f, 60f)
                val facing = abs(wrapDegrees(yaw - nms.yRot)) < 15f
                setInputs(if (facing) 0.7f else 0f, 0f, facing && nms.horizontalCollision)
            }
            // The jump off the cloud has finished, one way or the other.
            else -> {
                if (arrived()) return across()
                endCrossing(if (nms.onGround()) "came down somewhere else" else "the jump off the cloud fell short")
                return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ leaps onto ground no route leads to

    /**
     * A jump the route planner does not promise. Its routes only cross gaps a jump is sure to
     * make, which on a map of islands with wide gaps between them can leave a bot with nowhere
     * to go but the island it spawned on. A player in that spot takes the long jump: a running
     * jump with the air jump at the top of it, onto the nearest ground they have not been on.
     */
    private class Leap(val targetId: java.util.UUID, val from: Location, val to: Location, val fromNode: RouteNode, val toNode: RouteNode, var since: Long) {
        var jumped = false
    }
    private var leap: Leap? = null
    private var nextLeapTick = 0L
    /** Everywhere the bot has been able to walk since it lost the way to this opponent: ground a leap does not go back to. */
    private val explored = HashSet<RouteNode>()
    private var exploredFor: java.util.UUID? = null
    private var exploredSet: Set<RouteNode>? = null
    /** Leaps that did not come off, and until when each is left alone. */
    private val failedLeaps = HashMap<Pair<RouteNode, RouteNode>, Long>()

    /**
     * Can a running jump from [a], with this kit's air jump at the top of it, come down on [b]?
     * Flown the way the game would, pushing forward all the way, and with a block in hand.
     */
    private fun leapReaches(a: Location, b: Location): Boolean {
        val r = hypot(b.x - a.x, b.z - a.z)
        // The jump off the ground, from a standing start, to the top of it.
        var along = 0.0
        var y = a.y
        var vy = 0.42
        var vh = 0.30
        repeat(6) { y += vy; along += vh; vy = (vy - 0.08) * 0.98; vh = vh * 0.91 + 0.02 }
        var pitch = 10.0
        while (pitch >= -80.0) {
            var (h, v) = jumpLaunch(pitch)
            var d = along
            var yy = y
            for (t in 1..70) {
                yy += v; d += h
                v = (v - 0.08) * 0.98; h = h * 0.91 + 0.02
                if (v < 0 && yy <= b.y) { if (d >= r + LEAP_MARGIN) return true; break }
            }
            pitch -= 10.0
        }
        return false
    }

    /** Is there room to go up from [from] and then across onto [landing]? */
    private fun hopClear(from: Location, landing: Location, map: GameMapDef): Boolean {
        val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
        val top = maxOf(from.y, landing.y) + 1.2
        var y = from.y + 0.2
        while (y <= top) {
            if (!nav.clear(from.x, y, from.z)) return false
            y += 0.25
        }
        val steps = (hypot(landing.x - from.x, landing.z - from.z) * 3).toInt().coerceAtLeast(2)
        for (i in 1..steps) {
            val part = i.toDouble() / steps
            if (!nav.clear(from.x + (landing.x - from.x) * part, top, from.z + (landing.z - from.z) * part)) return false
        }
        return true
    }

    /**
     * Looks for a leap off the ground the bot can walk on, onto ground it has not been able to
     * get to. For every difficulty: this is getting about the map, not playing it well, and a
     * Normal bot left standing on the island it spawned on is not an easier opponent, just a
     * broken one.
     */
    private fun startLeap(t: Player, map: GameMapDef): Boolean {
        if (tick < nextLeapTick) return false
        if (holdUntilTick >= 0 || shot != null || gapJump != null || climb != null || perch != null || !nms.onGround()) return false
        nextLeapTick = tick + 40
        if (!canDoubleJump() || reachable.isEmpty() || tick - reachableTick > 1500 || standingOff(t) != null) return false
        if (exploredFor != t.uniqueId) { explored.clear(); exploredFor = t.uniqueId; exploredSet = null }
        if (exploredSet !== reachable) { explored += reachable; exploredSet = reachable }
        failedLeaps.entries.removeIf { it.value <= tick }
        val world = player.world
        val nav = ArenaNav.of(world, map.voidLevel.toInt(), mobility())
        val index = reachableByColumn()
        val at = t.location
        val here = player.location
        // Only from the rim of what it can walk on: a block with open air on some side of it.
        fun rim(n: RouteNode): Boolean {
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                if (index[columnKey(n.x + dx, n.z + dz)]?.any { abs(it.y - n.y) <= 1.3 } != true) return true
            }
            return false
        }
        val ledges = reachable.filter(::rim).let { if (it.size > LEAP_LEDGES) it.shuffled(java.util.Random(tick)).take(LEAP_LEDGES) else it }
        // Worth it only onto ground about as near to them as the bot can already walk, or
        // nearer: not onto some block on the far side of the map because it is new.
        val nearest = reachable.minOf { hypot(it.x + .5 - at.x, it.z + .5 - at.z) + abs(it.y - at.y) }
        var best: Leap? = null
        var bestScore = Double.MAX_VALUE
        val began = System.nanoTime()
        for (a in ledges) {
            if (System.nanoTime() - began > 3_000_000L) break
            val from = Location(world, a.x + .5, a.y, a.z + .5)
            for (i in 0 until 16) {
                val angle = i * Math.PI / 8
                for (r in LEAP_RADII) {
                    val bx = kotlin.math.floor(from.x + cos(angle) * r).toInt()
                    val bz = kotlin.math.floor(from.z + sin(angle) * r).toInt()
                    val b = nav.surface(bx, bz, a.y + 4.2, 0.0, 16.0) ?: continue
                    if (b in explored || (a to b) in failedLeaps || memory.hopBarred(a, b, tick)) continue
                    val to = Location(world, b.x + .5, b.y, b.z + .5)
                    val away = hypot(to.x - at.x, to.z - at.z) + abs(to.y - at.y)
                    if (away > nearest + LEAP_DETOUR) continue
                    val score = away + 0.15 * (hypot(from.x - here.x, from.z - here.z) + abs(from.y - here.y))
                    if (score >= bestScore || !leapReaches(from, to) || !hopClear(from, to, map)) continue
                    best = Leap(t.uniqueId, from, to, a, b, tick)
                    bestScore = score
                }
            }
        }
        val found = best ?: return false
        leap = found
        prowlGoal = null
        route.clear(); routeSearch = null; nextRouteTick = tick
        comboQueue.clear()
        dbg("no way to ${t.name}: going to ${"%.1f %.1f %.1f".format(found.from.x, found.from.y, found.from.z)} for a leap of " +
            "${"%.1f".format(hypot(found.to.x - found.from.x, found.to.z - found.from.z))} blocks onto ${"%.1f %.1f %.1f".format(found.to.x, found.to.y, found.to.z)}")
        return true
    }

    /** Walks to where the leap starts and takes it. False once it is over and the fight should carry on. */
    private fun tickLeap(l: Leap, t: Player, map: GameMapDef): Boolean {
        val here = player.location
        fun over(reason: String, rest: Long = 60): Boolean { dbg("leap over: $reason"); leap = null; nextLeapTick = tick + rest; return false }
        if (l.jumped) {
            // The jump has finished, one way or the other.
            val made = nms.onGround() && hypot(here.x - l.to.x, here.z - l.to.z) < 2.0 && abs(here.y - l.to.y) < 1.5
            val elsewhere = nms.onGround() && ArenaNav.of(player.world, map.voidLevel.toInt(), mobility()).nearest(here, 2.0)?.let { it !in explored } == true
            if (made || elsewhere) {
                // New ground: look for the way from here.
                route.clear(); routeSearch = null; nextRouteTick = tick
                unreachableUntil.remove(t.uniqueId)
                return over("across", 20)
            }
            failedLeaps[l.fromNode to l.toNode] = tick + 1200
            return over(if (nms.onGround()) "came down where it started" else "fell short")
        }
        if (t.world != here.world) return over("nobody to go after", 20)
        // They may wander about over there as much as they like, and in a free-for-all the bot
        // may change its mind about who it is after: the leap is off this ground, not onto
        // theirs. It is only off if whoever it wants now has come to where the bot can walk.
        if (canWalkTo(t.location)) { unreachableUntil.remove(t.uniqueId); return over("they have come within reach", 20) }
        if (tick - l.since > 500) { failedLeaps[l.fromNode to l.toNode] = tick + 600; return over("did not get to where it starts") }
        // Still no way to them, wherever they have got to: no new search for one on the way to the jump.
        unreachableUntil[t.uniqueId] = maxOf(unreachableUntil[t.uniqueId] ?: 0L, tick + 100)
        unreachableAt[t.uniqueId] = t.location.clone()
        if (nms.onGround() && hypot(l.from.x - here.x, l.from.z - here.z) < 0.4 && abs(l.from.y - here.y) < 1.0) {
            if (!canDoubleJump()) { setInputs(0f, 0f, false); return true }
            l.jumped = true
            lastHop = l.fromNode to l.toNode; lastHopTick = tick
            lastWay = "leap"; lastWayTick = tick
            dbg("leaping ${"%.1f".format(hypot(l.to.x - here.x, l.to.z - here.z))} blocks")
            startGapJump(Gap(hypot(l.to.x - here.x, l.to.z - here.z), l.to))
            return true
        }
        travel(l.from, map) { failedLeaps[l.fromNode to l.toNode] = tick + 600; leap = null; nextLeapTick = tick + 40 }
        return true
    }

    // ------------------------------------------------------------------ somewhere to shoot from

    /** How far this bot can do anything to someone it can see: its kit's attacks and what it is carrying. 0 for nothing worth standing off for. */
    private fun sightRange(): Double {
        val bow = KitService.getKitForPlayer(player)?.id.let { it == "skeleton" || it == "zombie" }
        val kit = maxOf(if (bow) 25.0 else 0.0,
            kitAbilityIds().maxOfOrNull { AbilityHints.of(it).let { h -> if (h.attack && h.damage > 0) h.maxRange else 0.0 } } ?: 0.0)
        val item = carriedSlot()?.let { ItemService.kind(player.inventory.getItem(it)) }
            ?.takeIf { ItemTactics.siegeValue(it) >= 2 }?.let(ItemTactics::reach) ?: 0.0
        val range = maxOf(kit, item)
        return if (range >= 8.0) range * 0.9 else 0.0
    }

    /**
     * Ground the bot can walk to with a clear line to [t] inside [range]: where a player goes when
     * they cannot get to someone but have something to throw. A sample of the places the last
     * failed route search got to, the nearest to the opponent that sees them.
     */
    private fun vantage(t: Player, range: Double, trap: DeathMemory.Trap? = null): Location? {
        if (reachable.isEmpty() || tick - reachableTick > 1500) return null
        val world = player.world
        val aim = aimPointOf(t)
        val here = player.location
        val near = reachable.filter {
            abs(it.x + .5 - aim.x) <= range && abs(it.z + .5 - aim.z) <= range &&
                Location(world, it.x + .5, it.y + 1.62, it.z + .5).distanceSquared(aim).let { d2 -> d2 >= 9.0 && d2 <= range * range } &&
                // Not from inside the reach of somebody who has killed this bot from where they sit.
                (trap == null || trap.camp.distance(Location(world, it.x + .5, it.y, it.z + .5)) >= trap.keepOut)
        }
        if (near.isEmpty()) return null
        var best: Location? = null
        var bestScore = Double.MAX_VALUE
        repeat(minOf(90, near.size)) {
            val n = near[rng.nextInt(near.size)]
            val eye = Location(world, n.x + .5, n.y + 1.62, n.z + .5)
            val gap = aim.toVector().subtract(eye.toVector())
            val score = gap.length() + 0.5 * (hypot(n.x + .5 - here.x, n.z + .5 - here.z) + abs(n.y - here.y))
            if (score < bestScore && world.rayTraceBlocks(eye, gap.clone().normalize(), gap.length(), org.bukkit.FluidCollisionMode.NEVER, true) == null) {
                best = Location(world, n.x + .5, n.y, n.z + .5)
                bestScore = score
            }
        }
        return best
    }

    /** With rockets and no sight of them: ground far enough back that the rockets do not land at the bot's own feet. */
    private fun standOff(t: Player): Location? {
        if (reachable.isEmpty() || tick - reachableTick > 1500) return null
        val at = t.location
        val here = player.location
        return reachable.asSequence()
            .filter { hypot(it.x + .5 - at.x, it.z + .5 - at.z) in 11.0..17.0 && abs(it.y - at.y) < 12.0 }
            .minByOrNull { hypot(it.x + .5 - here.x, it.z + .5 - here.z) + abs(it.y - here.y) }
            ?.let { Location(player.world, it.x + .5, it.y, it.z + .5) }
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
        // (If it is still there. With the floor blown out from under it (a Smasher takes eleven
        // blocks of it, its user's included) the place a bot last stood is a hole, and it used to
        // jump straight up at that hole until it had fallen out of the world: one Smasher in six
        // cost its own user a life.)
        val options =
            (listOfNotNull(lastSafe?.takeIf { it.world == here.world }) +
                map.spawnPoints.map { Location(here.world, it.x, it.y, it.z) })
                .filter { hasSolidBelow(it.clone().add(0.0, 0.5, 0.0), 3) }
        if(recoveryGoal==null || tick-recoveryScanTick>=10) {
        recoveryScanTick=tick
        val scan=ArenaTerrain(player.world,map.voidLevel.toInt(),canDoubleJump())
        val ledges=ArrayList<Location>()
        fun look(radii: List<Int>, spokes: Int) {
            for(r in radii) for(i in 0 until spokes) {
                val a=Math.toRadians(i*360.0/spokes)
                scan.surface(kotlin.math.floor(here.x+cos(a)*r).toInt(),kotlin.math.floor(here.z+sin(a)*r).toInt(),here.y,6.0,12.0)?.let { ledges.add(scan.location(it)) }
            }
        }
        look(listOf(2,4,6,9,12), 8)
        fun nearest() = (options+ledges).filter { it.y > here.y - 25 }.minByOrNull { hDist(it.x, it.z) }
        var found = nearest()
        // Nothing near and nowhere it knows of: further out, whatever is left standing.
        if (found == null) { look(listOf(15,18,22,26), 12); found = nearest() }
        recoveryGoal = found ?: options.firstOrNull()
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
        } else if (holdUntilTick < 0 && perch?.stage != 2) {
            // (Dropping onto its own Wool Mine, a Sheep keeps the rocket for the way back.)
            // (A Wither Skeleton whose image is standing on the map is back on it with one press.)
            if (swapWithImage() || tryRecoveryAbility() || webLaunch(goal)) dbg("used a movement ability to recover")
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

    /**
     * For a bot standing off the routes: the block to get onto first. The one the search began
     * at, or one of the first few of the way it found, whichever a jump from here is seen to
     * come down on. Null if none is.
     */
    private fun wayBackOn(began: RouteNode, result: List<RouteStep>): RouteStep? {
        val here = player.location
        for (node in listOf(began) + result.take(3).map { it.node }) {
            val to = Location(here.world, node.x + .5, node.y, node.z + .5)
            if (comesOff(GapJump(to, tick, here.clone(), null), canDoubleJump())) return RouteStep(node, true)
        }
        return null
    }

    /** Would the game make a step of a running jump from here at [landing], and not a jump at all? (See [JumpSim.stepsUp].) */
    private fun jumpIsStep(landing: Location): Boolean {
        val map = lastMap ?: return false
        if (!nms.onGround()) return false
        val here = player.location
        val from = routeAt?.takeIf { abs(it.y - here.y) < .6 && hypot(it.x + .5 - here.x, it.z + .5 - here.z) < 1.0 }
        // (Straight up first, there is no running at anything.)
        if (GapJump(landing, tick, here.clone(), from).high) return false
        return try {
            val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
            val v = nms.deltaMovement
            JumpSim.stepsUp({ x, y, z, foot -> nav.clear(x, y, z, foot) }, here.x, here.y, here.z, landing.x, landing.y, landing.z,
                v.x, v.z, nms.speed.toDouble(), nms.sinceTimedJump() > JUMP_PUSH_NANOS)
        } catch (e: Exception) {
            false
        }
    }

    /** Does the gap jump under way come down on its landing as it is going, with no (further) air jump? */
    private fun landsAsItGoes(g: GapJump): Boolean {
        val here = player.location
        val v = nms.deltaMovement
        try {
            val map = lastMap
            if (map != null) {
                val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
                val space = JumpSim.Space { x, y, z, foot -> nav.clear(x, y, z, foot) }
                return JumpSim.lands(space, JumpSim.Flight(here.x, here.y, here.z, v.x, v.y, v.z), g.landing.x, g.landing.y, g.landing.z,
                    g.high, g.takeoff.x, g.takeoff.z)
            }
        } catch (e: Exception) {
            // Not able to tell: the old guess below.
        }
        val remaining = hypot(g.landing.x - here.x, g.landing.z - here.z)
        val fall = ticksUntilBelow(g.landing.y, v.y, here.y)
        val reach = coastDistance(hypot(v.x, v.z), fall)
        val steer = if (fall >= 12) minOf(1.5, 0.006 * fall * fall) else 0.0
        return !(reach + steer < remaining - 0.3 || here.y < g.landing.y - 0.3)
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
        // Is there floor to run on past the landing, the way the jump is going?
        val runOut = runOutPast(p)
        // Every kit's jump is different: the standard one is flatter = further and steeper =
        // higher, a spider leaps exactly where it looks, a slime jumps further. So the kit is
        // asked what each pitch gives, and the most promising are flown in the head, through
        // whatever is really in the way, until one comes down on the landing (see JumpSim.aim:
        // the route planner promised this jump by flying it the same way).
        val nav = lastMap?.let { ArenaNav.of(player.world, it.voidLevel.toInt(), mobility()) }
        val space = JumpSim.Space { x, y, z, foot -> nav?.clear(x, y, z, foot) ?: true }
        val v = nms.deltaMovement
        val g = gapJump?.takeIf { it.landing === p }
        val aim = JumpSim.aim(space, { jumpLaunch(it) }, JumpSim.Flight(here.x, here.y, here.z, v.x, v.y, v.z), p.x, p.y, p.z, runOut,
            high = g?.high == true, holdX = g?.takeoff?.x ?: here.x, holdZ = g?.takeoff?.z ?: here.z)
        val pitch = aim.pitch.toFloat()
        if (BotManager.debug && g != null) {
            g.flown = JumpSim.path(space, { jumpLaunch(it) }, JumpSim.Flight(here.x, here.y, here.z, v.x, v.y, v.z), aim.pitch, p.x, p.y, p.z,
                g.high, g.takeoff.x, g.takeoff.z)
            g.flownTick = tick
        }
        val (yaw, _) = lookAngles(player.eyeLocation, p)
        nms.setYRot(yaw)
        nms.setYHeadRot(yaw)
        nms.setXRot(pitch)
        doubleJump("$reason, ${"%.1f".format(r)} blocks, pitch ${"%.0f".format(pitch)}${if (aim.lands) "" else ", best guess"}")
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

    /** The jump of the route the bot looked at and would not take from where it stood: it lines up for that one. */
    private var lineUp: Pair<RouteNode, RouteNode>? = null
    /** The jump it is walking to the middle of its block for, and since when. */
    private var liningUp: Pair<RouteNode, RouteNode>? = null
    private var liningUpSince = 0L
    /** Jumps it has looked at from the middle of their block and still not taken, and how often. */
    private val refusedJumps = HashMap<Pair<RouteNode, RouteNode>, Int>()
    /** Since when it has been standing at a jump waiting for its air jump to come back. */
    private var airJumpWait = -1L
    /** The tick it last stood still to look at a jump again from a standstill, and the tick that wait began. */
    private var standStillTick = -100L
    private var standStillFrom = -100L
    /** The tick it last stood a moment before a running jump, to get the full push of one, and the tick that wait began. */
    private var pushWaitTick = -100L
    private var pushWaitFrom = -100L

    /** Is there floor to run on past [p], the way a jump from here is going? */
    private fun runOutPast(p: Location): Boolean {
        val here = player.location
        val r = hypot(p.x - here.x, p.z - here.z)
        return r > 0.5 && (1..2).all {
            hasSolidBelow(Location(here.world, p.x + (p.x - here.x) / r * it, p.y + 0.3, p.z + (p.z - here.z) / r * it), 1)
        }
    }

    /**
     * Looks before it leaps: flies the jump in the head from where the bot stands, with the speed
     * it has and its own kit's air jump ([airJump] false: without one). A route is a promise made
     * earlier, from the middle of a block, about the arena as it was then; this is the bot's own
     * eyes on the jump now.
     */
    private fun comesOff(jump: GapJump, airJump: Boolean, seen: MutableList<JumpSim.Flight>? = null): Boolean {
        val map = lastMap ?: return true
        val here = player.location
        val nav = ArenaNav.of(player.world, map.voidLevel.toInt(), mobility())
        val space = JumpSim.Space { x, y, z, foot -> nav.clear(x, y, z, foot) }
        val v = nms.deltaMovement
        val to = jump.landing
        return try {
            JumpSim.jump(space, if (airJump) JumpSim.Launch { jumpLaunch(it) } else null,
                here.x, here.y, here.z, to.x, to.y, to.z, jump.high, runOutPast(to), v.x, v.z, seen, nms.speed.toDouble(),
                boost = nms.sinceTimedJump() > JUMP_PUSH_NANOS)
        } catch (e: Exception) {
            true // Not able to tell is no reason to stand there: jump as it always did.
        }
    }

    /**
     * [look]: the jump is a step of a route, to be looked at first ([comesOff]). If it would not
     * come off from here, the bot lines up for it in the middle of its block and looks again; if
     * not from there either, it leaves the jump alone and finds another way.
     * [flown]: it is a long jump the planner flew in the head. It is made the way it was flown, as
     * a jump, even where the landing is near enough below to be walked off the edge to.
     */
    private fun startGapJump(gap: Gap, look: Boolean = false, flown: Boolean = false) {
        val landing = gap.landing ?: return
        val here = player.location
        // Waiting outside something that is winding up ([tickBlasts]): not a jump that comes down beside it.
        // It stands where it is; the way is still there when the thing has gone off.
        if (tick - blastHoldTick <= 1 && blastsHeld.any { b -> BlastTactics.walksIn(hypot(here.x - b.at.x, here.z - b.at.z),
                hypot(landing.x - b.at.x, landing.z - b.at.z), b.radius) }) {
            setInputs(0f, 0f, false)
            return
        }
        navStats.gapJumps++
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
        jump.stepOff = if (flown) 0.0 else stepOffSpeed(landing)
        // A running jump is the jump it was looked at as only with the push the game gives a
        // sprint jump, and this server gives a player that push only a quarter of a second after
        // its last jump or later. Straight after a hop the bot waits those few ticks, on the
        // ground. (Jumping again at once, it left the ground a fifth of a block a tick slower
        // than planned, and came down short of what it had every reason to think it would reach.)
        // (Never for long, whatever the clock says: after six ticks it jumps with what it gets,
        // and looks at the jump as the weaker one it then is.)
        if (look && jump.stepOff == 0.0 && !jump.high && nms.onGround() && nms.sinceTimedJump() <= JUMP_PUSH_NANOS) {
            if (tick - pushWaitTick > 1) {
                pushWaitFrom = tick
                dbg("a moment on the ground before the jump to ${"%.1f %.1f %.1f".format(landing.x, landing.y, landing.z)}: just jumped")
            }
            pushWaitTick = tick
            if (tick - pushWaitFrom <= 6) {
                navStats.gapJumps--
                patientTick = tick
                setInputs(0f, 0f, false)
                return
            }
        }
        // (Only a real jump, and only for a kit whose jump is one push off the ground and one air
        // jump: a chicken flies on, flap after flap, and a spider grabs the wall it runs into,
        // which is not what gets flown here.)
        if (look && jump.stepOff == 0.0 && nms.onGround() && mobility().let { it.airborne && it.jumps.flown }) {
            val inHand = canDoubleJump()
            if (!comesOff(jump, inHand)) {
                navStats.gapJumps--
                setInputs(0f, 0f, false)
                val to = RouteNode(landing.blockX, (landing.y * 16).roundToInt(), landing.blockZ)
                val spot = "${"%.1f %.1f %.1f".format(landing.x, landing.y, landing.z)}"
                // It came in at a run, and what it was promised is the jump from a standstill. (A
                // jump straight up drifts with whatever speed it has: into the lip it means to
                // clear, or out from under the ledge.) Stand for a moment and look again before
                // giving the jump up.
                if (nms.deltaMovement.let { hypot(it.x, it.z) } > 0.02) {
                    if (tick - standStillTick > 1) standStillFrom = tick
                    standStillTick = tick
                    if (tick - standStillFrom <= 8) {
                        patientTick = tick
                        return
                    }
                }
                if (!inHand && comesOff(jump, true) && (airJumpWait < 0 || tick - airJumpWait < 60)) {
                    // It takes the air jump, and that is not back yet: a moment's wait, not another way.
                    if (airJumpWait < 0) { airJumpWait = tick; dbg("waiting for the air jump before the jump to $spot") }
                    patientTick = tick
                    return
                }
                airJumpWait = -1
                // (Unless lining up has been tried and the middle of the block cannot be got to.)
                if (from != null && hypot(from.x + .5 - here.x, from.z + .5 - here.z) > JumpSim.LINE_UP &&
                    !(liningUp == from to to && tick - liningUpSince > LINE_UP_TICKS)) {
                    if (lineUp == null) dbg("the jump to $spot would not come off from here: lining up for it")
                    lineUp = from to to
                    return
                }
                lineUp = null
                liningUp = null
                val times = if (from != null) refusedJumps.merge(from to to, 1, Int::plus) ?: 1 else 1
                val rest = 600L shl (2 * (times - 1)).coerceAtMost(8)
                if (from != null) blockedEdges[from to to] = tick + rest else blockedSteps[to] = tick + 200
                // (Leaving the jump alone is leaving one step of the graph alone. If the way found
                // next comes to the same landing all the same, it is the landing that is left alone.)
                if (times >= 3) blockedSteps[to] = tick + 200L * (times - 2).coerceAtMost(30)
                // (Whoever promised it saw the arena differently: have it looked at again.)
                lastMap?.let { forgetTerrain(landing.blockX, landing.blockZ, it); forgetTerrain(here.blockX, here.blockZ, it) }
                route.clear(); routeSearch = null; nextRouteTick = tick
                dbg("the jump to $spot would not come off (looked at it $times time${if (times == 1) "" else "s"}): finding another way")
                return
            }
        }
        airJumpWait = -1
        lineUp = null
        liningUp = null
        // (Up from a crouch: a goat that was charging pushes off at a run like anybody else.)
        if (player.isSneaking && chargeInHand() != null) player.isSneaking = false
        if (from != null) { lastHop = from to RouteNode(landing.blockX, (landing.y * 16).roundToInt(), landing.blockZ); lastHopTick = tick }
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
        if (!canDoubleJump() || rooted) return
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
        // With nobody in mind it looks round, to both sides and behind it ([SightTactics.sweep]):
        // first from where it stands, and then on its way to the middle of the map.
        val looking = !BotManager.allSeeing && nobodySince >= 0
        val swing = if (looking) SightTactics.sweep(tick - nobodySince).toFloat() else 0f
        val atMiddle = hypot(cx - here.x, cz - here.z) < 4
        if (atMiddle || (looking && tick - nobodySince < SightTactics.COMPASS_AFTER)) {
            if (!looking) return releaseInputs()
            turnTowards(wrapDegrees(nobodyYaw + swing), 0f, d.turnSpeed)
            return setInputs(0f, 0f, false)
        }
        val middle = Location(here.world, cx, here.y + 1.6, cz)
        val (yaw, _) = lookAngles(player.eyeLocation, middle)
        turnTowards(wrapDegrees(yaw + swing), 0f, d.turnSpeed)
        if (!looking) {
            val forward = if (nms.onGround() && !groundAhead(0.6f, 0f)) 0f else 0.6f
            return setInputs(forward, 0f, nms.horizontalCollision && nms.onGround())
        }
        // (It goes the way it was going whichever way its head is turned.)
        val (f, s) = inputToward(middle)
        val forward = f * 0.6f
        val strafe = s * 0.6f
        if (nms.onGround() && !groundAhead(forward, strafe)) setInputs(0f, 0f, false)
        else setInputs(forward, strafe, nms.horizontalCollision && nms.onGround())
    }

    private fun setInputs(forward: Float, strafe: Float, jump: Boolean) {
        if (rooted) {
            nms.forwardInput = 0f; nms.strafeInput = 0f; nms.jumpInput = false; nms.isSprinting = false
            return
        }
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
            holdAim = null
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

    /** How fast the look is turning, in degrees a tick, side to side and up and down ([LookTactics]), and the tick it last turned. */
    private var yawSwing = 0.0
    private var pitchSwing = 0.0
    private var lastTurnTick = -10L

    /**
     * Turns the look toward somebody (or something being aimed at), no faster than [maxStep]
     * degrees a tick: the turn eases in, slows as it arrives and runs a little past, as a hand on
     * a mouse does ([LookTactics]). For looking and aiming only. Where the bot has to face a way to
     * walk or jump it, that is [turnTowards], which gets there at one speed and stops on the mark:
     * its jumps were measured with that.
     */
    private fun lookTowards(yaw: Float, pitch: Float, maxStep: Float) {
        if (BotManager.stiffLook) return turnTowards(yaw, pitch, maxStep)
        // Picked up again after a pause, a turn starts from rest.
        if (tick - lastTurnTick > 1) { yawSwing = 0.0; pitchSwing = 0.0 }
        lastTurnTick = tick
        val pull = LookTactics.pull(d)
        val carry = LookTactics.carry(d)
        yawSwing = LookTactics.swing(yawSwing, wrapDegrees(yaw - nms.yRot).toDouble(), pull, carry, maxStep.toDouble())
        pitchSwing = LookTactics.swing(pitchSwing, (pitch - nms.xRot).toDouble(), pull, carry, maxStep.toDouble())
        val newYaw = wrapDegrees(nms.yRot + yawSwing.toFloat())
        nms.setYRot(newYaw)
        nms.setYHeadRot(newYaw)
        nms.setXRot((nms.xRot + pitchSwing.toFloat()).coerceIn(-90f, 90f))
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
        /** Two teams: asked to come to a player, a bot within this many blocks of them is with them. */
        const val TEAM_NEAR = 5.0
        /** ... and further than this it goes back to them even from a fight (unless somebody is right on top of it). */
        const val TEAM_FAR = 13.0
        /** An opponent who has kept within this many blocks of one spot for [CAMP_TICKS] is holding it. */
        /** Kits whose air jump turned out not to be the one the planner has for them (said once each). */
        val warnedJumps = HashSet<String>()
        /** Ticks a bot spends getting to the middle of its block for a jump before it takes the jump as it finds it. */
        const val LINE_UP_TICKS = 25L
        /** The game gives a player the push of a sprint jump only this long after its last jump, or later. */
        const val JUMP_PUSH_NANOS = 250_000_000L
        const val CAMP_RADIUS = 5.0
        const val CAMP_TICKS = 160L
        /** How much further than the middle of the landing a leap must be able to go before it is tried. */
        const val LEAP_MARGIN = 0.8
        /** How much further from the opponent than the bot can already walk a leap may land. */
        const val LEAP_DETOUR = 4.0
        /** How many blocks on the rim of its ground a bot looks for a leap from at a time. */
        const val LEAP_LEDGES = 160
        val LEAP_RADII = doubleArrayOf(3.5, 5.0, 6.5, 8.0, 9.5, 11.0)
        /** Blocks a flap is counted on for when a flight is planned (it goes further in open air, less under a low roof). */
        const val FLAP_STRIDE = 3.5
        /** What a block of flying counts for against a block of walking when choosing where to take off. */
        const val FLAP_COST = 1.5
        /** Within this of the landing the flap is aimed to come down on it; further out it is flown level. */
        const val FLAP_LAST = 5.5
        /** How far below their floor a flight lets itself sink while it waits for a flap that clears what is in the way. */
        const val FLAP_DIP = 2.5
        val FLAP_LEVEL = doubleArrayOf(0.0)
        val FLAP_PITCHES = doubleArrayOf(0.0, -20.0, -40.0, -55.0, -70.0, -82.0)
        /** How far around the ground it was killed on a bot keeps off. */
        const val TRAP_RADIUS = 4.0
        /** Wall climbing drains a full bar in 200 ticks at 0.4 blocks a tick: this much per block. */
        const val CLIMB_ENERGY_PER_BLOCK = 0.0125
        /** Route steps a dash must have ahead of it, all level, and how long the lane must be. */
        const val DASH_NODES = 12
        const val DASH_LENGTH = 9.5
        /** Ticks into the jump at which the leap is fired: just before the top of it. */
        const val DASH_DELAY = 4L
        /** An Ice Path runs 14 blocks ahead of whoever lays it; the last of it is not counted on. */
        const val BRIDGE_AHEAD = 13.0
        /** An air jump still covering this much a tick as it comes down is a flat, fast one (see [aimedDoubleJump]). */
        const val FAST_ARC = 0.45
        /**
         * How far short of the middle of its landing block a fighter may be at the start of the
         * tick it comes down in: the block's edge is half a block before the middle, the fighter
         * is 0.3 either side of its own, and a tenth is kept in hand.
         */
        const val LIP_REACH = 0.7
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

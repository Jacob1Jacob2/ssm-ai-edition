package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.bots.nms.BotPlayer
import dev.betrix.superSmashMobsBrawl.services.LobbyService
import org.bukkit.Location
import kotlin.math.*
import kotlin.random.Random

/** Real player movement inputs, bounded terrain searches, and deliberate jumps; no combat in hubs. */
class LobbyWander(private val nms: BotPlayer) {
    private val player get() = nms.bukkitEntity
    private val random = Random.Default
    private var tick = 0
    private var origin: Location? = null
    private var search: ArenaRoute? = null
    private val path = ArrayDeque<RouteStep>()
    private var restUntil = 0
    private var lastJump = -100
    private var movingSince = 0
    private var progress: Location? = null
    private var lastSafe: Location? = null
    private var sprint = true
    private var gaze = 0f
    private var airborneTarget: Location? = null
    private var trackedStep: RouteNode? = null
    private var stepSince = 0

    fun tick() {
        tick++
        val here = player.location
        val hub = LobbyService.hub(LobbyService.lobbyOf(player)) ?: return stop()
        if (here.world != hub.world) return stop()
        if (origin?.world != here.world) {
            origin = dev.betrix.superSmashMobsBrawl.utils.createLocation(hub.world, hub.data.spawnPoints.first())
            lastSafe = origin!!.clone(); progress = here.clone()
            path.clear(); search = null; restUntil = tick + random.nextInt(5, 35)
            gaze = here.yaw + random.nextInt(-120, 121)
        }
        val home = origin!!
        // Only recover after an actual fall out of the playable lobby, never as normal locomotion.
        if (here.y < home.y - 12 || here.distanceSquared(home) > 80.0 * 80) {
            stop(); player.teleport(lastSafe ?: home); path.clear(); search = null
            airborneTarget = null; restUntil = tick + 25; return
        }
        val onGround = player.isOnGround
        if (onGround && tick % 10 == 0 && here.y >= home.y - 3) lastSafe = here.clone()
        val landing = airborneTarget
        if (landing != null) {
            if ((onGround && tick > lastJump + 3) || tick > lastJump + 30) airborneTarget = null
            else { walkTo(landing, false, true); return }
        }
        if (tick < restUntil) {
            stop()
            val neighbour = if (tick % 15 == 0) here.world.players.firstOrNull {
                it != player && !BotManager.isBot(it) && it.location.distanceSquared(here) < 64
            } else null
            if (neighbour != null) gaze = yawTo(neighbour.location)
            turn(gaze, if (neighbour == null) 0f else -5f)
            return
        }
        if (search == null && path.isEmpty() && onGround) plan(home)
        search?.let { pending ->
            if (RouteWorkBudget.available()) {
                val began = System.nanoTime()
                val result = pending.advance(20, began + 350_000)
                RouteWorkBudget.used(System.nanoTime() - began)
                if (result != null) {
                    path.addAll(pending.remainingFrom(here.x, here.y, here.z, result)); search = null
                    movingSince = tick; progress = here.clone()
                    if (path.isEmpty()) restUntil = tick + random.nextInt(8, 25)
                }
            }
        }
        while (path.isNotEmpty()) {
            val node = path.first().node
            if (hypot(node.x + .5 - here.x, node.z + .5 - here.z) < .38 && abs(node.y - here.y) < .65) path.removeFirst()
            else break
        }
        val step = path.firstOrNull()
        if (step == null) {
            stop()
            if (search == null) {
                restUntil = tick + random.nextInt(10, 55); gaze = here.yaw + random.nextInt(-95, 96)
            }
            return
        }
        if (trackedStep != step.node) { trackedStep = step.node; stepSince = tick }
        // Moving in circles around a missed landing is still stuck, even if position changes.
        if (tick - stepSince > 65) {
            path.clear(); search = null; trackedStep = null; restUntil = tick + 15; stop(); return
        }
        if (tick % 25 == 0) {
            val old = progress
            if (old != null && old.distanceSquared(here) < .2 || tick - movingSince > 260) {
                path.clear(); search = null; restUntil = tick + random.nextInt(8, 20); stop(); return
            }
            progress = here.clone()
            // Revalidate edited blocks without retaining stale collision geometry forever.
            if (ArenaTerrain(here.world, here.world.minHeight, false).surface(step.node.x, step.node.z, step.node.y, .1, .1) == null) {
                path.clear(); stop(); return
            }
        }
        val to = Location(here.world, step.node.x + .5, step.node.y, step.node.z + .5)
        val hop = !step.jump && onGround && tick - lastJump > 45 && random.nextDouble() < .008 &&
            path.size > 3 && abs(to.y - here.y) < .2 &&
            ArenaTerrain(here.world, here.world.minHeight, false).clear(here.x, here.y + 1.25, here.z)
        val jump = onGround && tick - lastJump > 10 && (step.jump || hop)
        walkTo(to, jump, step.jump || sprint)
        if (jump && nms.jumpInput) { lastJump = tick; if (step.jump) airborneTarget = to }
    }

    private fun plan(home: Location) {
        val here = player.location
        val terrain = ArenaTerrain(here.world, here.world.minHeight, false)
        val start = terrain.at(here) ?: return
        val goals = (0 until 18).mapNotNull {
            val angle = random.nextDouble(2 * PI)
            val radius = random.nextDouble(4.0, 15.0)
            val x = floor(here.x + cos(angle) * radius).toInt()
            val z = floor(here.z + sin(angle) * radius).toInt()
            if (hypot(x - home.x, z - home.z) > 38) null
            else terrain.surface(x, z, here.y, 5.0, 3.0)
        }.filter { it != start }
        // Often choose a reachable raised surface: stairs, logs, stones and small parkour gaps.
        val goal = (if (random.nextBoolean()) goals.filter { it.y > here.y + .5 } else emptyList()).randomOrNull()
            ?: goals.randomOrNull() ?: return
        search = ArenaRoute(start, goal, { n -> terrain.neighbours(n).filter {
            it.node.y >= home.y - 4 && hypot(it.node.x - home.x, it.node.z - home.z) < 40
        } }, 450)
        sprint = random.nextDouble() < .8
    }

    private fun yawTo(to: Location) = Math.toDegrees(atan2(-(to.x - player.location.x), to.z - player.location.z)).toFloat()
    private fun wrap(angle: Float) = ((angle + 180f) % 360f + 360f) % 360f - 180f
    private fun turn(yaw: Float, pitch: Float) {
        val delta = wrap(yaw - nms.yRot)
        nms.setYRot(wrap(nms.yRot + delta.coerceIn(-18f, 18f))); nms.setYHeadRot(nms.yRot)
        nms.setXRot(nms.xRot + (pitch - nms.xRot).coerceIn(-5f, 5f))
    }
    private fun walkTo(to: Location, jump: Boolean, fast: Boolean) {
        val yaw = yawTo(to)
        turn(yaw, if (to.y > player.location.y + .6) -12f else 0f)
        val error = abs(wrap(yaw - nms.yRot))
        nms.forwardInput = if (error < 50) 1f else 0f
        nms.strafeInput = 0f; nms.jumpInput = jump && error < 30
        player.isSprinting = fast && error < 30
        player.isSneaking = false
        if (airborneTarget != null && hypot(player.location.x - to.x, player.location.z - to.z) < .65)
            nms.forwardInput = 0f
    }
    private fun stop() { nms.forwardInput = 0f; nms.strafeInput = 0f; nms.jumpInput = false; player.isSprinting = false }
}

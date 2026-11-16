package dev.betrix.superSmashMobsBrawl.bots.combat

import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import dev.betrix.superSmashMobsBrawl.items.ArenaItem
import dev.betrix.superSmashMobsBrawl.items.ArenaItem.*

/** How a bot points an item before using it. */
enum class ItemAim {
    /** No aiming: heals, buffs and things that act on the bot itself. */
    NONE,
    /** Straight at the opponent, leading them: rockets, snowballs, the teleporter. */
    TARGET,
    /** Thrown in an arc that lands on the opponent: grenades and other fused items. */
    LOBBED,
    /** Toward safe ground: the teleporter used to get back on the map. */
    ESCAPE,
    /** Used on the opponent directly in melee range (knockback rod hit, grapple, placed bomb). */
    CONTACT,
}

/** What the bot knows when it decides whether the item in its hotbar is worth using now. */
data class ItemSituation(
    /** Own health and energy bar, 0..1. */
    val health: Double,
    val energy: Double = 1.0,
    val lives: Int = 4,
    val fleeing: Boolean = false,
    /** Dropping with nothing underneath. */
    val falling: Boolean = false,
    /** Still has its double jump (or flap, leap...). */
    val airJump: Boolean = true,
    /** Distance to the current opponent, or null with nobody to fight. */
    val targetDistance: Double? = null,
    val targetVisible: Boolean = false,
    val targetHealth: Double = 1.0,
    /** Opponent's horizontal speed in blocks per tick; under about 0.08 they are standing still. */
    val targetSpeed: Double = 0.0,
    val targetNearEdge: Boolean = false,
    /** The route planner knows no way to the opponent. */
    val targetUnreachable: Boolean = false,
    /** There is a drop behind the bot, so anything thrown over its shoulder leaves the map. */
    val voidBehind: Boolean = false,
    /** Distance to the nearest opponent of any kind. */
    val nearestEnemy: Double = Double.MAX_VALUE,
    /** How long the item has been carried. */
    val heldSeconds: Int = 0,
)

/**
 * When an item is worth using. Every item has a job (heal, recover, finish, punish a camper), and
 * a bot that fires each one the second it picks it up wastes nearly all of them.
 *
 * Normal bots know the job of each item but not the timing: they throw at anything in range.
 * Expert bots wait for the moment the item pays off, and keep recovery items for a fall.
 */
object ItemTactics {
    private fun skill(d: BotDifficulty) = when (d) {
        BotDifficulty.NORMAL -> 0.25
        BotDifficulty.HARD -> 0.5
        BotDifficulty.HARDER -> 0.8
        BotDifficulty.EXPERT -> 1.0
    }

    /** How to use [item] right now, or null to keep holding it. */
    fun plan(item: ArenaItem, s: ItemSituation, difficulty: BotDifficulty): ItemAim? {
        val skill = skill(difficulty)
        val careful = skill >= 0.75
        val dist = s.targetDistance
        val seen = s.targetVisible && dist != null
        val still = s.targetSpeed < 0.08
        fun within(range: ClosedFloatingPointRange<Double>) = seen && dist!! in range
        // Nobody keeps an item for ever: past this, any reasonable chance is taken.
        val impatient = s.heldSeconds > 20 + (40 * skill).toInt()
        fun at(aim: ItemAim, condition: Boolean) = if (condition) aim else null

        return when (item) {
            DIAMOND_AXE, DIAMOND_SHOVEL, DIAMOND_SWORD, DIAMOND_HELMET -> null // applied on pickup

            // --- healing: not at full health, and the big ones not until they are needed
            FOOD -> at(ItemAim.NONE, s.health <= 0.9)
            FIRST_AID_KIT -> at(ItemAim.NONE, s.health <= 0.75 - 0.25 * skill)
            AWESOME_SOUP -> at(ItemAim.NONE, s.health <= 0.6 - 0.25 * skill || (s.fleeing && s.health <= 0.5))
            GILDED_APPLE -> at(ItemAim.NONE, within(0.0..12.0) || s.health <= 0.6)
            REGENERATE -> at(ItemAim.NONE, s.energy <= 0.3)
            EXTRA_LIFE -> at(ItemAim.NONE, s.lives < 8 && !s.falling && s.nearestEnemy > 14)

            // --- movement: speed for a chase or an escape, invisibility for an approach or an escape
            SPEED -> at(ItemAim.NONE, s.fleeing || (dist != null && dist > 6))
            SHADOW_WALK -> at(ItemAim.NONE, s.fleeing || s.health <= 0.45 || within(5.0..16.0))

            // --- recovery: careful bots keep these for a fall they cannot jump out of
            CLOUD_IN_A_BOTTLE -> at(ItemAim.NONE, s.falling && (!careful || !s.airJump))
            JETPACK -> at(ItemAim.NONE, s.falling && !s.airJump)
            // A pad in mid-air and a launch off it: the same moment as a cloud.
            POCKET_TRAMPOLINE -> at(ItemAim.NONE, s.falling && (!careful || !s.airJump))
            // Whatever comes out is worth more in the hand than in the bundle.
            MYSTERY_ABILITY -> ItemAim.NONE
            INSTANT_TELEPORTER -> when {
                s.falling && !s.airJump -> ItemAim.ESCAPE
                // The answer to someone sitting where nothing walks: appear next to them.
                within(9.0..22.0) && (s.targetUnreachable || !careful || impatient) -> ItemAim.TARGET
                else -> null
            }

            // --- shot straight at the opponent
            HOMING_SNOWBALL -> at(ItemAim.TARGET, within(3.0..30.0))
            FIRE_FLOWER, MISSILE_BATTERY, RAIN_STAFF -> at(ItemAim.TARGET, within(3.0..24.0))
            // One rocket: careful bots want a slow or close target, and never their own feet.
            ROCKET_LAUNCHER -> at(ItemAim.TARGET, within(8.0..28.0) && (!careful || still || dist!! < 14 || impatient))
            SHOTBOW -> at(ItemAim.TARGET, within(0.0..9.0))

            // --- finishers: worth most when the opponent is at an edge or already hurt
            KNOCKBACK_ROD -> when {
                within(0.0..3.6) && (!careful || s.targetNearEdge || s.targetHealth < 0.4 || impatient) -> ItemAim.CONTACT
                within(3.6..12.0) && s.targetNearEdge -> ItemAim.TARGET
                else -> null
            }
            // Throws the opponent far behind the bot, so it wants the drop at its back.
            PLAYER_GRAPPLE -> at(ItemAim.CONTACT, within(0.0..14.0) && (!careful || s.voidBehind || impatient))
            SMASHER -> at(ItemAim.CONTACT, within(0.0..7.0))

            // --- three-second fuses: careful bots save them for someone who is not moving
            AIR_STRIKE -> at(ItemAim.LOBBED, within(8.0..22.0) && (!careful || still || impatient))
            // The mist stays for five seconds, so it also works on someone who is merely near.
            POISON_MIST -> at(ItemAim.LOBBED, within(5.0..16.0) && (!careful || still || s.targetSpeed < 0.2 || impatient))
            ICE_CUBE -> at(ItemAim.LOBBED, within(6.0..16.0) && (!careful || still || s.targetNearEdge || impatient))
            SINGULARITY_GRENADE -> at(ItemAim.LOBBED, within(6.0..16.0) && (!careful || still || impatient))
            SINGULARITY_NUKE -> at(ItemAim.LOBBED, within(9.0..18.0) && (!careful || still || impatient))
            CLUSTER_GRENADE -> at(ItemAim.LOBBED, within(6.0..16.0) && (!careful || still || impatient))
            FRAG_GRENADE -> at(ItemAim.LOBBED, within(4.0..14.0))
            CRAFTY_BOMB -> at(ItemAim.CONTACT, within(0.0..6.0) && (!careful || still || impatient))
            // A mine goes where the opponent is about to walk: at a chaser, or between the two.
            PROXIMITY_MINE -> at(ItemAim.LOBBED, (s.fleeing && within(0.0..12.0)) || within(4.0..12.0))
            MONSTER_EGG -> at(ItemAim.LOBBED, within(0.0..20.0))
        }
    }

    /** Items a bot cannot spend should not block it from picking up others. */
    fun shouldDiscard(failedUses: Int) = failedUses >= 4
}

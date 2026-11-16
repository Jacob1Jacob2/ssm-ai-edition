package dev.betrix.superSmashMobsBrawl.bots

/**
 * How a bot should use each ability.
 *
 * @param minRange / maxRange distance to the target (blocks) at which the ability is worth using
 * @param holdTicks for charge abilities (they fire when the player stops "blocking"), how long to
 *   hold the item: a random value in this range. 0..0 means a single click.
 * @param recovery the ability moves the user and can save them when falling off the map
 * @param aimUp extra degrees to aim above the target (arcing projectiles)
 * @param damage roughly what a hit takes off a 20-point health bar
 * @param knockback how hard a hit launches; 2 and up is a finisher on a hurt or cornered opponent
 * @param speed projectile speed in blocks per tick; 0 for anything instant or in melee range
 * @param moves how far the ability carries the user along its aim, so it is never used to dive
 *   off the map; negative for recoil that pushes the user backwards
 * @param grounded only hits opponents standing on the ground
 */
data class AbilityHint(
    val minRange: Double,
    val maxRange: Double,
    val holdTicks: IntRange = 0..0,
    val recovery: Boolean = false,
    val attack: Boolean = true,
    val aimUp: Float = 0f,
    val damage: Double = 5.0,
    val knockback: Double = 1.0,
    val speed: Double = 0.0,
    val moves: Double = 0.0,
    val grounded: Boolean = false,
)

object AbilityHints {
    private val hints =
        mapOf(
            // Blaze
            "inferno" to AbilityHint(0.0, 6.0, holdTicks = 15..40, damage = 6.0, speed = 1.8),
            "firefly" to AbilityHint(3.0, 14.0, recovery = true, damage = 7.0, knockback = 2.0, moves = 12.0),
            // Wolf
            "cub_tackle" to AbilityHint(2.0, 14.0, damage = 5.0, speed = 1.8),
            "wolf_strike" to AbilityHint(2.0, 8.0, recovery = true, damage = 7.0, knockback = 2.0, moves = 7.0),
            // Magma cube
            "magma_blast" to AbilityHint(3.0, 18.0, damage = 8.0, knockback = 2.0, speed = 0.9, moves = -3.0),
            "flame_dash" to AbilityHint(2.0, 12.0, recovery = true, damage = 6.0, knockback = 1.5, moves = 10.0),
            // Wither skeleton
            "guided_wither_skull" to AbilityHint(3.0, 22.0, holdTicks = 12..30, damage = 10.0, knockback = 2.0, speed = 0.8),
            "wither_image" to AbilityHint(0.0, 12.0, damage = 5.0),
            // Villager
            "sonic_hurr" to AbilityHint(0.0, 6.0, damage = 6.0, knockback = 2.0),
            "trade_scatter" to AbilityHint(0.0, 8.0, damage = 4.0, moves = 4.0),
            "villager_arts" to AbilityHint(4.0, 16.0, damage = 0.0),
            // Creeper
            "sulphur_bomb" to AbilityHint(3.0, 16.0, aimUp = 4f, damage = 6.5, knockback = 2.5, speed = 1.5),
            "explode" to AbilityHint(0.0, 4.0, damage = 8.0, knockback = 2.5),
            // Skeleton
            "bone_blast" to AbilityHint(0.0, 5.0, damage = 6.0, knockback = 2.5),
            "roped_arrow" to AbilityHint(4.0, 20.0, recovery = true, aimUp = 3f, damage = 6.0, speed = 2.0, moves = 12.0),
            // Zombie
            "bile_blaster" to AbilityHint(0.0, 6.0, damage = 6.0, knockback = 0.5, speed = 0.8),
            "deaths_grasp" to AbilityHint(2.0, 8.0, damage = 2.0, knockback = 2.5, moves = 6.0),
            // Cow
            "angry_herd" to AbilityHint(0.0, 8.0, damage = 5.0, knockback = 1.25, speed = 0.8, grounded = true),
            "milk_spiral" to AbilityHint(2.0, 9.0, recovery = true, damage = 6.0, moves = 8.0),
            // Enderman
            "blink" to AbilityHint(6.0, 16.0, recovery = true, damage = 0.0, moves = 12.0),
            "block_toss" to AbilityHint(3.0, 16.0, holdTicks = 12..24, aimUp = 5f, damage = 8.0, knockback = 2.5, speed = 1.2),
            // Chicken
            "egg_blaster" to AbilityHint(2.0, 12.0, holdTicks = 10..15, damage = 4.0, speed = 1.5),
            "chicken_missile" to AbilityHint(3.0, 20.0, damage = 8.0, knockback = 1.6, speed = 0.6),
            // Spider
            "needler" to AbilityHint(2.0, 14.0, damage = 5.0, speed = 2.0),
            "spin_web" to AbilityHint(2.0, 10.0, damage = 3.0, moves = 8.0),
            // Witch
            "daze_potion" to AbilityHint(3.0, 14.0, aimUp = 8f, damage = 7.0, knockback = 2.0, speed = 1.0),
            "bat_wave" to AbilityHint(2.0, 12.0, damage = 4.0, knockback = 1.5, moves = 8.0),
            // Squid
            "ink_shotgun" to AbilityHint(0.0, 10.0, damage = 6.0, knockback = 1.5, speed = 2.0),
            "super_squid" to AbilityHint(4.0, 10.0, holdTicks = 8..16, recovery = true, damage = 0.0, moves = 8.0),
            "fish_flurry" to AbilityHint(0.0, 6.0, damage = 8.0, grounded = true),
            // Guardian
            "whirlpool_axe" to AbilityHint(0.0, 5.0, damage = 4.0, knockback = 0.0, speed = 1.6),
            "water_splash" to AbilityHint(0.0, 6.0, recovery = true, damage = 12.0),
            "target_laser" to AbilityHint(3.0, 11.0, damage = 2.0),
            // Snowman
            "blizzard" to AbilityHint(2.0, 12.0, holdTicks = 16..32, damage = 4.0, speed = 1.5),
            "ice_path" to AbilityHint(4.0, 14.0, recovery = true, damage = 0.0, moves = 10.0),
            "snow_turret" to AbilityHint(2.0, 12.0, damage = 4.0),
            // Slime
            "slime_rocket" to AbilityHint(4.0, 18.0, holdTicks = 15..45, damage = 9.0, knockback = 2.0, speed = 1.2),
            "slime_slam" to AbilityHint(2.0, 7.0, recovery = true, damage = 7.0, knockback = 2.0, moves = 5.0),
            // Pig
            "bouncy_bacon" to AbilityHint(2.0, 12.0, damage = 4.0, speed = 1.2),
            "baby_bacon_bomb" to AbilityHint(2.0, 10.0, damage = 4.0, knockback = 1.5),
            // Iron golem
            "iron_hook" to AbilityHint(5.0, 16.0, damage = 5.0, knockback = 0.0, speed = 1.5),
            "fissure" to AbilityHint(2.0, 10.0, damage = 6.0, knockback = 1.5, grounded = true),
            "seismic_slam" to AbilityHint(0.0, 6.0, recovery = true, damage = 7.0, knockback = 2.4, moves = 4.0, grounded = true),
            // Goat
            "goated_hurr" to AbilityHint(3.0,14.0, damage = 6.0),
            "goated_ram" to AbilityHint(2.0,9.0,recovery=true, damage = 7.0, knockback = 2.0, moves = 6.0),
            // Mooshroom
            "mushroom_wall" to AbilityHint(0.0,5.0, damage = 4.0, knockback = 1.5),
            "strawbury_swirl" to AbilityHint(2.0, 14.0, damage = 4.5, speed = 1.2),
            "moostrike" to AbilityHint(0.0, 5.0, damage = 10.0, knockback = 2.0),
            "fungal_trampoline" to AbilityHint(0.0, 6.0, recovery = true, damage = 0.0),
            // Phantom
            "ravenous_membrane" to AbilityHint(3.0,20.0, damage = 5.0, speed = 1.5),
            "phantom_flight" to AbilityHint(5.0,16.0,recovery=true, damage = 0.0),
            // Polar bear
            "fish_gun" to AbilityHint(2.0,24.0, damage = 5.0, speed = 1.6),
            "cub_carrier" to AbilityHint(3.0,20.0, damage = 3.0, speed = 1.2),
            "bitter_smash" to AbilityHint(3.0,12.0,holdTicks=8..16,recovery=true, damage = 5.0, knockback = 2.0, moves = 8.0),
            // Classic Skeletal Horse
            "bone_kick" to AbilityHint(0.0, 3.5, damage = 6.5, knockback = 4.0),
            "bone_rush" to AbilityHint(2.0, 10.0, recovery = true, damage = 4.0, moves = 10.0),
            // Legacy modern horse definitions (not in the classic roster).
            "hoof_bash" to AbilityHint(0.0, 3.0, damage = 6.5, knockback = 4.0),
            "harvest_rush" to AbilityHint(3.0, 10.0, damage = 4.0, moves = 8.0),
            "gliding_gallop" to AbilityHint(6.0, 16.0, recovery = true, damage = 0.0, moves = 10.0),
            // Sheep
            "static_laser" to AbilityHint(4.0, 25.0, holdTicks = 15..28, damage = 7.0, knockback = 3.0),
            "wool_mine" to AbilityHint(0.0, 6.0, damage = 14.0, knockback = 2.0),
            "wooly_rocket" to AbilityHint(4.0, 20.0, recovery = true, damage = 8.0, knockback = 2.5, moves = 12.0),
        )

    private val default = AbilityHint(0.0, 8.0)

    fun of(abilityId: String): AbilityHint = hints[abilityId] ?: default
}

package dev.betrix.superSmashMobsBrawl.kits

import dev.betrix.superSmashMobsBrawl.abilities.*
import dev.betrix.superSmashMobsBrawl.disguises.*
import dev.betrix.superSmashMobsBrawl.extensions.sendDebugMessage
import dev.betrix.superSmashMobsBrawl.passives.*
import dev.betrix.superSmashMobsBrawl.services.DataService
import dev.betrix.superSmashMobsBrawl.services.MinigameService
import gg.flyte.twilight.extension.feed
import gg.flyte.twilight.extension.heal
import java.util.logging.Logger
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

open class BrawlKit(val id: String, val player: Player) : KoinComponent {
    private val minigameService: MinigameService by inject()
    private val dataService: DataService by inject()
    private val logger: Logger by inject()

    protected val minigameInstance = minigameService.getMinigameForPlayer(player)
    protected val kitData =
        dataService.getKit(id) ?: throw RuntimeException("No kit found in DataService with id $id")

    protected val abilities = arrayListOf<BrawlAbility>()
    protected val passives = arrayListOf<BrawlPassive>()

    protected var disguise: BrawlDisguise? = null

    private var invincible: Boolean = false
    private var passivesStarted = false

    fun startPassives() {
        if (passivesStarted || tornDown) return
        passivesStarted = true
        passives.forEach { it.setup() }
    }

    /** Refill persistent resource counters as well as kits that store energy directly in XP. */
    fun refillEnergy() {
        (getAbility("phantom_flight") as? PhantomFlightAbility)?.refill()
        (getAbility("inferno") as? InfernoAbility)?.refill()
        (getAbility("blizzard") as? BlizzardAbility)?.refill()
        (getPassive("goated_leap") as? GoatedLeapPassive)?.refill()
        player.exp = 1f
    }

    private fun art() = (getAbility("villager_arts") as? VillagerArtsAbility)?.activeArt
    private fun fuel() = getPassive("fuel_the_fire") as? FuelTheFirePassive
    fun getArmor(): Double {
        val kitArmor = if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"pig:3") && player.health<10) 10.0 else (if (dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"pig:3")) 7.5 else if ((getPassive("nether_pig") as? NetherPigPassive)?.active==true) 7.5 else if (dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"villager:3")) 7.5 else art()?.armor ?: kitData.armor) + (fuel()?.armorBonus ?: 0.0) + (dev.betrix.superSmashMobsBrawl.smash.SmashService.session(player)?.armorBonus() ?: 0.0)
        return kitArmor + dev.betrix.superSmashMobsBrawl.items.DiamondHelmet.armorBonus(player) + dev.betrix.superSmashMobsBrawl.services.KitStatsService.armorBonus(player, kitData)
    }

    fun getHurtSound(): org.bukkit.Sound = kitData.hurtSound ?: org.bukkit.Sound.ENTITY_PLAYER_HURT

    fun getMeleeDamage(): Double = (if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"villager:3")) 7.0 else art()?.damage ?: kitData.meleeDamage) +
        (if ((getAbility("goated_ram") as? GoatedRamAbility)?.boosted == true) 1.0 else 0.0) + ((getPassive("ravage") as? RavagePassive)?.bonus() ?: 0.0) + (fuel()?.stacks ?: 0) + (dev.betrix.superSmashMobsBrawl.smash.SmashService.session(player)?.meleeBonus() ?: 0.0) + dev.betrix.superSmashMobsBrawl.services.KitStatsService.damageBonus(player, kitData)

    fun getOutgoingKnockbackMultiplier(): Double = (if ((getAbility("goated_ram") as? GoatedRamAbility)?.boosted == true) 1.6 else 1.0) * (if (dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"villager:1")) 1.3 else 1.0) * if (art() == VillagerArt.BUTCHER && !dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"villager:3")) 0.75 else 1.0

    fun getMeleeReach(): Double = kitData.meleeReach

    fun getKnockbackMultiplier(): Double = kitData.knockbackMultiplier * (fuel()?.knockbackScale ?: 1.0)

    fun isInvincible(): Boolean = invincible

    fun setInvincible(value: Boolean) {
        invincible = value
    }

    /** This kit's air jump (double jump, flap, leap...), whichever passive provides it. */
    fun jumpPassive(): dev.betrix.superSmashMobsBrawl.passives.DoubleJumpPassive? =
        passives.firstOrNull { it is dev.betrix.superSmashMobsBrawl.passives.DoubleJumpPassive } as? dev.betrix.superSmashMobsBrawl.passives.DoubleJumpPassive

    fun getPassive(id: String): BrawlPassive? {
        return passives.find { it.id == id }
    }

    fun getAbility(id: String): BrawlAbility? {
        return abilities.find { it.id == id }
    }

    open fun setup() {
        dev.betrix.superSmashMobsBrawl.services.KitStatsService.wear(player, kitData.id)
        disguise = kitData.disguiseId?.let { BrawlDisguiseFactory.create(player, it) }
        if (kitData.disguiseId != null && disguise == null) {
            logger.severe("No disguise known with id ${kitData.disguiseId}")
        }

        kitData.abilities.forEach {
            val ability = createAbility(it.id, player)
            if (ability == null) logger.severe("No ability found with id ${it.id} reference on kit ${kitData.id}") else abilities.add(ability)
        }

        kitData.passives.forEach {
            if (minigameInstance?.isPassiveValid(it.id) == false) {
                return@forEach
            }

            passives.add(
                when (it.id) {
                    "goated_leap" -> GoatedLeapPassive(player)
                    "ender_teleport" -> EnderTeleportPassive(player)
                    "ravage" -> RavagePassive(player)
                    "fuel_the_fire" -> FuelTheFirePassive(player)
                    "wolf_jump" -> WolfJumpPassive(player)
                    "villager_jump" -> VillagerJumpPassive(player)
                    "deadly_bones" -> DeadlyBonesPassive(player)
                    "double_jump" -> DoubleJumpPassive(player = player)
                    "regeneration" -> RegenerationPassive(player)
                    "hunger" -> HungerPassive(player)
                    "arrow_recharge" -> ArrowRechargePassive(player)
                    "barrage" -> BarragePassive(player)
                    "stampede" -> StampedePassive(player)
                    "potion_effect" -> PotionEffectPassive(player)
                    "flap" -> FlapPassive(player)
                    "wall_climb" -> WallClimbPassive(player)
                    "spider_leap" -> SpiderLeapPassive(player)
                    "nether_pig" -> NetherPigPassive(player)
                    "corrupted_arrow" -> CorruptedArrowPassive(player)
                    "giga_slime" -> GigaSlimePassive(player)
                    "arctic_aura" -> ArcticAuraPassive(player)
                    "exp_charge" -> ExpChargePassive(player)
                    "overgrowth" -> OvergrowthPassive(player)
                    else -> {
                        logger.severe(
                            "No passive found with id ${it.id} reference on kit ${kitData.id}"
                        )
                        return@forEach
                    }
                }
            )
        }

        player.clearActivePotionEffects()
        player.exp = 0f
        player.level = 0
        player.totalExperience = 0
        abilities.forEach { ability -> ability.setup() }
        if (minigameInstance == null || minigameInstance.getState() == dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS) startPassives()
        dev.betrix.superSmashMobsBrawl.services.MatchCompass.equip(player, kitData)
        if (id == "skeleton" || id == "zombie") player.inventory.setItem(3, ItemStack.of(Material.ARROW, 3))

        player.gameMode = GameMode.SURVIVAL
        player.heal()
        player.feed()
        if (id == "iron_golem") {
            player.addPotionEffect(org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, Int.MAX_VALUE, 0, false, false))
        }
        // Equip armor if specified
        kitData.armorItems?.let { armor ->
            val helmetMat = armor.helmet?.let { Material.matchMaterial(it.uppercase()) }
            val chestMat = armor.chestplate?.let { Material.matchMaterial(it.uppercase()) }
            val legsMat = armor.leggings?.let { Material.matchMaterial(it.uppercase()) }
            val bootsMat = armor.boots?.let { Material.matchMaterial(it.uppercase()) }

            helmetMat?.let { player.inventory.setItem(EquipmentSlot.HEAD, ItemStack.of(it)) }
            chestMat?.let { player.inventory.setItem(EquipmentSlot.CHEST, ItemStack.of(it)) }
            legsMat?.let { player.inventory.setItem(EquipmentSlot.LEGS, ItemStack.of(it)) }
            bootsMat?.let { player.inventory.setItem(EquipmentSlot.FEET, ItemStack.of(it)) }
        }

        // 1.8 PVP feel adjustments: high attack speed removes the 1.9+ swing cooldown.
        // (Upstream used Attribute.valueOf("GENERIC_ATTACK_SPEED"), which no longer exists, so this
        // was silently skipped.)
        player.getAttribute(Attribute.ATTACK_SPEED)?.baseValue = 32.0
        dev.betrix.superSmashMobsBrawl.services.KitStatsService.showArmor(player, kitData.id)
        // Clear offhand to avoid shield mechanics
        try {
            player.inventory.setItemInOffHand(ItemStack.of(Material.AIR))
        } catch (_: Throwable) {}

        // Apply the visual after game-mode/equipment changes so it cannot be removed mid-setup.
        disguise?.setup()
        player.sendDebugMessage("You have been given the $id kit")
    }

    private var tornDown = false

    open fun teardown() {
        // SSM AI Edition: an ability's cleanup can trigger a death, which tears this same kit down
        // again mid-loop (ConcurrentModificationException), leaving passives/armor behind. Run once,
        // on copies of the lists, and don't let one broken piece stop the rest.
        if (tornDown) return
        tornDown = true
        dev.betrix.superSmashMobsBrawl.items.DiamondHelmet.clear(player)
        dev.betrix.superSmashMobsBrawl.services.KitStatsService.showArmor(player, null)
        dev.betrix.superSmashMobsBrawl.services.MatchCompass.remove(player)
        dev.betrix.superSmashMobsBrawl.smash.SmashService.clear(player)
        invincible = false
        player.fireTicks = 0

        fun safely(what: String, block: () -> Unit) =
            runCatching(block).onFailure {
                logger.warning("Kit $id teardown of $what failed for ${player.name}: $it")
            }

        safely("disguise") { disguise?.teardown() }
        disguise = null

        val oldAbilities = abilities.toList()
        abilities.clear()
        oldAbilities.forEach { a -> safely(a.id) { a.teardown() } }

        val oldPassives = passives.toList()
        passives.clear()
        oldPassives.forEach { p -> safely(p.id) { p.teardown() } }

        // Remove armor given by this kit, if any
        kitData.armorItems?.let {
            val empty = ItemStack.empty()
            player.inventory.setItem(EquipmentSlot.HEAD, empty)
            player.inventory.setItem(EquipmentSlot.CHEST, empty)
            player.inventory.setItem(EquipmentSlot.LEGS, empty)
            player.inventory.setItem(EquipmentSlot.FEET, empty)
        }

        player.clearActivePotionEffects()

        player.sendDebugMessage("The $id kit has been removed")
    }

    /** A Mystery Ability tool: one more ability for this life, set up at once. */
    fun borrow(ability: BrawlAbility) {
        if (tornDown) return
        abilities.add(ability)
        ability.setup()
    }

    /** Takes a spent borrowed ability away again. Does nothing once the kit itself is gone. */
    fun release(ability: BrawlAbility) {
        if (abilities.remove(ability)) runCatching { ability.teardown() }
            .onFailure { logger.warning("Kit $id release of ${ability.id} failed for ${player.name}: $it") }
    }

    fun abilityIds(): List<String> = abilities.map { it.id }

    companion object {
        /** The ability with this id for [player], or null for an id nothing implements. */
        fun createAbility(id: String, player: Player): BrawlAbility? =
            when (id) {
        "fish_gun" -> PolarBearFishAbility(player)
        "cub_carrier" -> PolarBearCubAbility(player)
        "bitter_smash" -> BitterSmashAbility(player)
        "goated_hurr" -> GoatedHurrAbility(player)
        "goated_ram" -> GoatedRamAbility(player)
        "mushroom_wall" -> MushroomWallAbility(player)
        "ravenous_membrane" -> RavenousMembraneAbility(player)
        "phantom_flight" -> PhantomFlightAbility(player)
        "inferno" -> InfernoAbility(player)
        "firefly" -> FireflyAbility(player)
        "cub_tackle" -> CubTackleAbility(player)
        "wolf_strike" -> WolfStrikeAbility(player)
        "magma_blast" -> MagmaBlastAbility(player)
        "flame_dash" -> FlameDashAbility(player)
        "guided_wither_skull" -> GuidedWitherSkullAbility(player)
        "wither_image" -> WitherImageAbility(player)
        "sonic_hurr" -> SonicHurrAbility(player)
        "trade_scatter" -> TradeScatterAbility(player)
        "villager_arts" -> VillagerArtsAbility(player)
        "sulphur_bomb" -> SulphurBombAbility(player)
        "explode" -> ExplodeAbility(player)
        "roped_arrow" -> RopedArrowAbility(player)
        "bone_blast" -> BoneBlastAbility(player)
        "angry_herd" -> AngryHerdAbility(player)
        "milk_spiral" -> MilkSpiralAbility(player)
        "blink" -> BlinkAbility(player)
        "block_toss" -> BlockTossAbility(player)
        "egg_blaster" -> EggBlasterAbility(player)
        "chicken_missile" -> ChickenMissileAbility(player)
        "needler" -> NeedlerAbility(player)
        "spin_web" -> SpinWebAbility(player)
        "daze_potion" -> DazePotionAbility(player)
        "bat_wave" -> BatWaveAbility(player)
        "ink_shotgun" -> InkShotgunAbility(player)
        "super_squid" -> SuperSquidAbility(player)
        "fish_flurry" -> FishFlurryAbility(player)
        "bouncy_bacon" -> BouncyBaconAbility(player)
        "baby_bacon_bomb" -> BabyBaconBombAbility(player)
        "whirlpool_axe" -> WhirlpoolAxeAbility(player)
        "water_splash" -> WaterSplashAbility(player)
        "target_laser" -> TargetLaserAbility(player)
        "bile_blaster" -> BileBlasterAbility(player)
        "deaths_grasp" -> DeathsGraspAbility(player)
        "slime_rocket" -> SlimeRocketAbility(player)
        "slime_slam" -> SlimeSlamAbility(player)
        "iron_hook" -> IronHookAbility(player)
        "seismic_slam" -> SeismicSlamAbility(player)
        "blizzard" -> BlizzardAbility(player)
        "ice_path" -> IcePathAbility(player)
        "snow_turret" -> SnowTurretAbility(player)
        "fissure" -> FissureAbility(player)
        "strawbury_swirl" -> StrawburySwirlAbility(player)
        "moostrike" -> MoostrikeAbility(player)
        "fungal_trampoline" -> FungalTrampolineAbility(player)
        "bone_kick" -> BoneKickAbility(player)
        "bone_rush" -> BoneRushAbility(player)
        "hoof_bash" -> HoofBashAbility(player)
        "harvest_rush" -> HarvestRushAbility(player)
        "gliding_gallop" -> GlidingGallopAbility(player)
        "static_laser" -> StaticLaserAbility(player)
        "wool_mine" -> WoolMineAbility(player)
        "wooly_rocket" -> WoolyRocketAbility(player)
                else -> null
            }
    }
}
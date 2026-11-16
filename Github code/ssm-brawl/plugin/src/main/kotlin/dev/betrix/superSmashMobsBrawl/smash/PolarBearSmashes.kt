package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.abilities.*
import dev.betrix.superSmashMobsBrawl.services.KitService
import kotlin.math.ceil

class PolarBearSmashes(private val session: SmashSession) {
    private fun ability(id: String) = KitService.getKitForPlayer(session.player)?.getAbility(id)
    fun begin(power: SmashPower) {
        if (power.spec.tier == SmashTier.ULTRA) (ability("fish_gun") as? PolarBearFishAbility)?.startUltra()
    }
    fun end(power: SmashPower) {
        if (power.spec.tier == SmashTier.ULTRA) (ability("fish_gun") as? PolarBearFishAbility)?.endUltra()
    }
    fun cooldown() = ceil(((ability("fish_gun") as? PolarBearFishAbility)?.progress?.cooldown ?: .5) * 20).toInt().coerceAtLeast(1)
    fun click(power: SmashPower): Boolean = when (power.spec.tier) {
        SmashTier.LIGHT, SmashTier.SUPER -> {
            (ability("cub_carrier") as? PolarBearCubAbility)?.throwPack(power.spec.tier == SmashTier.SUPER, power.spec.name)
            true
        }
        SmashTier.REGULAR -> (ability("bitter_smash") as? BitterSmashAbility)?.launchSmash() == true
        SmashTier.ULTRA -> (ability("fish_gun") as? PolarBearFishAbility)?.fireUltra() == true
    }
}

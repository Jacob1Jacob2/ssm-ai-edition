package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.bots.chat.CombatChatBridge
import dev.betrix.superSmashMobsBrawl.bots.combat.*
import dev.betrix.superSmashMobsBrawl.items.ArenaItem
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID
import kotlin.random.Random

class CombatFrameworkTest : StringSpec({
    val human = BotDialogue.Fighter(UUID(0,1), "Jacob", "Chicken", 4, false)
    val bot = BotDialogue.Fighter(UUID(0,2), "Dino", "Guardian", 3, true)
    val other = BotDialogue.Fighter(UUID(0,3), "Fox", "Creeper", 2, true)
    val facts = listOf(human, bot, other)
    class Match(mode: BotPersonality = BotPersonality.SALTY) {
        val bus = CombatEventBus()
        val dialogue = BotDialogue(Random(2), LineMemory(), 1.0).also { it.mode = mode }
        init { CombatChatBridge(bus, dialogue) { it == bot.id || it == other.id } }
        fun say(time: Long) = dialogue.poll(time, facts) { a, b -> a != b }
        fun stand(id: UUID, y: Double, from: Long, to: Long, x: Double = 0.0) {
            var t = from
            while (t <= to) { bus.publish(FighterSample(id, x, y, 0.0, 20.0, 20.0, t)); t += 250 }
        }
    }
    fun situation(health: Double = 1.0, targetHealth: Double? = 1.0, distance: Double? = 4.0, item: Double? = null,
                  itemEnemy: Double? = null, recent: Double = 0.0, ready: Int = 1) =
        CombatSituation(health, targetHealth, distance, item, itemEnemy, recent, ready)

    "a landed crystal is taken whatever else is going on" {
        val landed = situation(health = 0.1, distance = 3.0).copy(crystalDistance = 30.0, crystalLandsIn = 0)
        NormalPolicy.decide(landed, CombatState.ATTACK, 100) shouldBe CombatState.GET_CRYSTAL
        ExpertPolicy.decide(landed.copy(targetHealth = 0.1), CombatState.FLEE, 10) shouldBe CombatState.GET_CRYSTAL
    }

    "an expert sets off for a falling crystal at once, a basic bot only when it has to" {
        val far = situation(distance = 12.0).copy(crystalDistance = 20.0, crystalLandsIn = 400)
        ExpertPolicy.decide(far, CombatState.ATTACK, 0) shouldBe CombatState.GET_CRYSTAL
        NormalPolicy.decide(far, CombatState.ATTACK, 0) shouldBe CombatState.ATTACK
        NormalPolicy.decide(far.copy(crystalLandsIn = 150), CombatState.ATTACK, 0) shouldBe CombatState.GET_CRYSTAL
        // Mid-exchange, a basic bot finishes trading blows first.
        NormalPolicy.decide(far.copy(targetDistance = 3.0, crystalLandsIn = 150), CombatState.ATTACK, 0) shouldBe CombatState.ATTACK
    }

    "a bot waiting on the pad fights whoever comes for the crystal and otherwise holds it" {
        val waiting = situation(distance = 20.0).copy(crystalDistance = 1.0, crystalLandsIn = 200)
        ExpertPolicy.decide(waiting, CombatState.GET_CRYSTAL, 0) shouldBe CombatState.GET_CRYSTAL
        ExpertPolicy.decide(waiting.copy(targetDistance = 6.0), CombatState.GET_CRYSTAL, 0) shouldBe CombatState.ATTACK
        NormalPolicy.decide(waiting.copy(targetDistance = 6.0), CombatState.GET_CRYSTAL, 0) shouldBe CombatState.ATTACK
    }

    "an expert finishes a kill before walking to a crystal that is still falling" {
        val finishing = situation(distance = 4.0, targetHealth = 0.2).copy(crystalDistance = 25.0, crystalLandsIn = 300)
        ExpertPolicy.decide(finishing, CombatState.ATTACK, 100) shouldBe CombatState.ATTACK
    }

    // ---------------------------------------------------------------- event bus

    "listeners only receive their own event type and a failing listener cannot stop the rest" {
        val errors = mutableListOf<CombatEvent>()
        val bus = CombatEventBus { e, _ -> errors += e }
        val seen = mutableListOf<String>()
        bus.on<MatchStarted> { error("broken listener") }
        bus.on<MatchStarted> { seen += "start" }
        bus.on<AbilityCast> { seen += it.ability }
        bus.onAny { seen += "any" }
        bus.publish(MatchStarted(1)); bus.publish(AbilityCast(human.id, "Egg Blaster", 2))
        seen shouldBe listOf("start", "any", "Egg Blaster", "any")
        errors.size shouldBe 1
    }

    // ---------------------------------------------------------------- combat -> chat

    "rapid damage to a bot makes that bot, and only that bot, call hacks" {
        val m = Match()
        repeat(3) { m.bus.publish(DamageDealt(human.id, bot.id, 4.0, 20.0, "Chicken Missile", 1000L + it * 300)) }
        m.say(5000)!!.speaker shouldBe bot.id
    }
    "the same damage spread over time, or one big hit, is not suspicious" {
        val slow = Match()
        repeat(3) { slow.bus.publish(DamageDealt(human.id, bot.id, 4.0, 20.0, "Attack", 1000L + it * 2000)) }
        slow.say(9000) shouldBe null
        val single = Match()
        single.bus.publish(DamageDealt(human.id, bot.id, 12.0, 20.0, "Explode", 1000))
        single.say(5000) shouldBe null
    }
    "Chill compliments the same burst instead of accusing" {
        val m = Match(BotPersonality.CHILL)
        repeat(3) { m.bus.publish(DamageDealt(human.id, bot.id, 4.0, 20.0, "Attack", 1000L + it * 300)) }
        val text = m.say(5000)!!.text
        listOf("hack", "reported", "banned", "sus", "reach").forEach { text.shouldNotContain(it) }
    }
    "camping needs one Y-level, one area, and a bot that has been unable to hit them" {
        val m = Match()
        m.bus.publish(BotTargetChanged(bot.id, null, human.id, 0))
        m.stand(bot.id, 60.0, 0, 21_000, x = 30.0)
        m.stand(human.id, 70.0, 0, 21_000)
        m.say(25_000)!!.speaker shouldBe bot.id
    }
    "standing still while nobody is hunting you is not camping" {
        val m = Match()
        m.stand(human.id, 70.0, 0, 30_000)
        m.say(34_000) shouldBe null
    }
    "a fighter the bot keeps hitting is not camping" {
        val m = Match()
        m.bus.publish(BotTargetChanged(bot.id, null, human.id, 0))
        m.bus.publish(DamageDealt(bot.id, human.id, 1.0, 20.0, "Attack", 15_000))
        m.stand(human.id, 70.0, 0, 21_000)
        m.say(25_000) shouldBe null
    }
    "changing Y-level restarts the camping clock" {
        val m = Match()
        m.bus.publish(BotTargetChanged(bot.id, null, human.id, 0))
        m.stand(human.id, 70.0, 0, 12_000)
        m.stand(human.id, 64.0, 12_250, 24_000)
        m.say(28_000) shouldBe null
    }
    "an unreachable target is called out by the bot that could not path to them" {
        val m = Match()
        m.stand(human.id, 70.0, 0, 19_000)
        m.bus.publish(TargetUnreachable(other.id, human.id, 19_500))
        m.stand(human.id, 70.0, 20_000, 21_000)
        m.say(25_000)!!.speaker shouldBe other.id
    }
    "a bot says ez after its own kill" {
        val lines = (0..40).mapNotNull { seed ->
            val bus = CombatEventBus(); val d = BotDialogue(Random(seed), LineMemory(), 1.0).also { it.mode = BotPersonality.SALTY }
            CombatChatBridge(bus, d) { it == bot.id }
            bus.publish(FighterDied(human.id, bot.id, "Target Laser", 1000))
            d.poll(5000, facts) { a, b -> a != b }?.also { it.speaker shouldBe bot.id }?.text
        }
        lines.any { it.startsWith("ez") } shouldBe true
    }
    "a fleeing bot speaks for itself, and Silent hears none of it" {
        val m = Match()
        m.bus.publish(BotTargetChanged(bot.id, null, human.id, 0))
        m.bus.publish(BotStateChanged(bot.id, CombatState.ATTACK, CombatState.FLEE, 1000))
        m.say(5000)!!.speaker shouldBe bot.id
        val silent = Match(BotPersonality.SILENT)
        repeat(3) { silent.bus.publish(DamageDealt(human.id, bot.id, 4.0, 20.0, "Attack", 1000L + it * 300)) }
        silent.bus.publish(BotStateChanged(bot.id, CombatState.ATTACK, CombatState.FLEE, 2000))
        silent.say(6000) shouldBe null
    }
    "salty lines stay about the game" {
        val banned = listOf("mom", "gay", "kys", "retard", "fat", "touched")
        val said = mutableListOf<String>()
        for (seed in 0..60) {
            val bus = CombatEventBus(); val d = BotDialogue(Random(seed), LineMemory(), 1.0).also { it.mode = BotPersonality.SALTY }
            CombatChatBridge(bus, d) { it == bot.id }
            when (seed % 3) {
                0 -> repeat(3) { bus.publish(DamageDealt(human.id, bot.id, 4.0, 20.0, "Attack", 1000L + it * 300)) }
                1 -> { bus.publish(BotTargetChanged(bot.id, null, human.id, 0))
                    var t = 0L; while (t <= 21_000) { bus.publish(FighterSample(human.id, 0.0, 70.0, 0.0, 20.0, 20.0, t)); t += 250 } }
                else -> bus.publish(FighterDied(human.id, bot.id, "Attack", 1000))
            }
            d.poll(5000, facts) { a, b -> a != b }?.let { said += it.text.lowercase() }
            d.poll(25_000, facts) { a, b -> a != b }?.let { said += it.text.lowercase() }
        }
        (said.size > 20) shouldBe true
        said.forEach { line -> banned.forEach { word -> Regex("\\b$word\\b").containsMatchIn(line) shouldBe false } }
    }
    "old Toxic saves load as Salty" {
        BotPersonality.restore("TOXIC") shouldBe BotPersonality.SALTY
    }

    // ---------------------------------------------------------------- state machine

    "Normal bots flee at low health, for a limited time, then fight again" {
        val machine = CombatStateMachine(NormalPolicy)
        machine.update(situation(), 0); machine.state shouldBe CombatState.ATTACK
        machine.update(situation(health = 0.2), 30); machine.state shouldBe CombatState.ATTACK // just engaged
        machine.update(situation(health = 0.2), 70); machine.state shouldBe CombatState.FLEE
        machine.update(situation(health = 0.2), 120); machine.state shouldBe CombatState.FLEE
        machine.update(situation(health = 0.2), 175); machine.state shouldBe CombatState.ATTACK
    }
    "Normal bots grab a nearby item unless someone is right on top of them" {
        NormalPolicy.decide(situation(distance = 9.0, item = 6.0), CombatState.ATTACK, 0) shouldBe CombatState.GET_ITEM
        NormalPolicy.decide(situation(distance = 3.0, item = 6.0), CombatState.ATTACK, 0) shouldBe CombatState.ATTACK
        NormalPolicy.decide(situation(distance = null, item = 30.0), CombatState.ROAM, 0) shouldBe CombatState.ROAM
    }
    "Expert bots finish a weak opponent instead of running or detouring" {
        val s = situation(health = 0.15, targetHealth = 0.1, distance = 3.0, item = 2.0, ready = 0)
        ExpertPolicy.decide(s, CombatState.ATTACK, 100) shouldBe CombatState.ATTACK
    }
    "Expert bots only disengage while losing with nothing to press, and only briefly" {
        val losing = situation(health = 0.2, targetHealth = 0.9, distance = 3.0, ready = 0)
        ExpertPolicy.decide(losing, CombatState.ATTACK, 100) shouldBe CombatState.FLEE
        ExpertPolicy.decide(losing.copy(attacksReady = 2), CombatState.ATTACK, 100) shouldBe CombatState.ATTACK
        ExpertPolicy.decide(losing, CombatState.FLEE, 80) shouldBe CombatState.ATTACK
    }
    "Expert bots only race for an item they will reach first" {
        ExpertPolicy.decide(situation(distance = 20.0, item = 8.0, itemEnemy = 15.0), CombatState.ATTACK, 0) shouldBe CombatState.GET_ITEM
        ExpertPolicy.decide(situation(distance = 20.0, item = 8.0, itemEnemy = 4.0), CombatState.ATTACK, 0) shouldBe CombatState.ATTACK
    }
    "forced states report the state they replaced exactly once" {
        val machine = CombatStateMachine(NormalPolicy)
        machine.force(CombatState.RECOVER, 5) shouldBe CombatState.IDLE
        machine.force(CombatState.RECOVER, 6) shouldBe null
        machine.ticksInState(25) shouldBe 20L
    }

    // ---------------------------------------------------------------- targeting, prediction, kits

    "Normal targets the closest fighter; Expert prefers the weak one near an edge" {
        val near = TargetCandidate(UUID(1,1), 5.0, 20.0)
        val weak = TargetCandidate(UUID(1,2), 9.0, 4.0, nearEdge = true)
        NormalTargeting.choose(listOf(near, weak), null) shouldBe near.id
        ExpertTargeting.choose(listOf(near, weak), null) shouldBe weak.id
    }
    "the current target is kept unless another is clearly better" {
        val a = TargetCandidate(UUID(1,1), 10.0, 20.0)
        val b = TargetCandidate(UUID(1,2), 6.0, 20.0)
        NormalTargeting.choose(listOf(a, b), a.id) shouldBe a.id
        NormalTargeting.choose(listOf(a.copy(distance = 30.0), b), a.id) shouldBe b.id
        NormalTargeting.choose(emptyList(), a.id) shouldBe null
    }
    "a fighter the bot said it would go after outranks a closer one" {
        val near = TargetCandidate(UUID(1,1), 5.0, 20.0)
        val far = TargetCandidate(UUID(1,2), 25.0, 20.0, wanted = true)
        NormalTargeting.choose(listOf(near, far), near.id) shouldBe far.id
        ExpertTargeting.choose(listOf(near, far), near.id) shouldBe far.id
        NormalTargeting.choose(listOf(near, far.copy(wanted = false)), near.id) shouldBe near.id
        // Not across the whole map, though.
        NormalTargeting.choose(listOf(near, far.copy(distance = 70.0)), near.id) shouldBe near.id
    }
    "prediction leads a runner and lands a falling fighter on the floor" {
        val start = Prediction.Point(0.0, 64.0, 0.0)
        Prediction.position(start, 0.2, 0.0, 0.0, true, 10).x shouldBe 2.0
        val fall = Prediction.position(start, 0.3, 0.0, 0.0, false, 15, floorY = 62.0)
        fall.y shouldBe 62.0
        fall.x shouldBeLessThan 0.3 * 15
        Prediction.leadTicks(100.0) shouldBe 15
        Prediction.negligible(start, Prediction.Point(0.2, 64.0, 0.2)) shouldBe true
    }
    "mechanical combos never drop, and kits without combos have none" {
        repeat(20) {
            AbilityPlanner.comboFor("goated_ram", KitBehaviors.of("goat"), BotDifficulty.NORMAL, Random(it))!!.steps.size shouldBe 1
        }
        AbilityPlanner.comboFor("goated_ram", KitBehaviors.of("pig"), BotDifficulty.EXPERT) shouldBe null
        KitBehaviors.of("wolf").combos.first().opener shouldBe "cub_tackle"
    }

    // ---------------------------------------------------------------- ability strategy

    fun option(id: String, cooldown: Double = 6.0) = AbilityOption(id, AbilityHints.of(id), cooldown)
    fun expert(options: List<AbilityOption>, s: FightSituation, seed: Int = 1) =
        AbilityPlanner.choose(options, s, BotDifficulty.EXPERT, DefaultKitBehavior, Random(seed))

    "an ordinary ability in range against an ordinary target is used" {
        repeat(20) { expert(listOf(option("sulphur_bomb", 3.0)), FightSituation(distance = 8.0, targetSpeed = 0.1), it)!!.id shouldBe "sulphur_bomb" }
        expert(emptyList(), FightSituation(distance = 8.0)) shouldBe null
    }
    "Expert saves the big knockback for an opponent at the edge or already hurt" {
        val options = listOf(option("needler", 2.0), option("sulphur_bomb", 3.0))
        repeat(20) {
            expert(options, FightSituation(distance = 8.0, targetNearEdge = true), it)!!.id shouldBe "sulphur_bomb"
            expert(options, FightSituation(distance = 8.0, targetHealth = 0.3), it)!!.id shouldBe "sulphur_bomb"
        }
    }
    "Expert never dashes, leaps or recoils off the map" {
        repeat(20) {
            expert(listOf(option("wolf_strike", 8.0)), FightSituation(distance = 6.0, groundAhead = 3.0), it) shouldBe null
            expert(listOf(option("magma_blast")), FightSituation(distance = 8.0, groundBehind = 1.0), it) shouldBe null
            expert(listOf(option("wolf_strike", 8.0)), FightSituation(distance = 6.0, groundAhead = 12.0), it)!!.id shouldBe "wolf_strike"
        }
    }
    "Expert keeps its way back onto the map while it stands at the edge without a double jump" {
        repeat(20) {
            expert(listOf(option("firefly", 8.0)), FightSituation(distance = 8.0, exposed = true, airJump = false), it) shouldBe null
            expert(listOf(option("firefly", 8.0)), FightSituation(distance = 8.0, exposed = true, airJump = true), it)!!.id shouldBe "firefly"
        }
    }
    "a slow missile is held for a target that cannot outrun it" {
        val missile = listOf(option("chicken_missile", 7.0))
        repeat(20) {
            expert(missile, FightSituation(distance = 18.0, targetSpeed = 0.25), it) shouldBe null
            expert(missile, FightSituation(distance = 18.0, targetSpeed = 0.0), it)!!.id shouldBe "chicken_missile"
            expert(missile, FightSituation(distance = 5.0, targetSpeed = 0.25), it)!!.id shouldBe "chicken_missile"
        }
    }
    "ground attacks wait for the opponent to land" {
        repeat(20) { expert(listOf(option("fissure", 8.0)), FightSituation(distance = 6.0, targetAirborne = true), it) shouldBe null }
    }
    "Normal bots misjudge the moment that Expert bots read exactly" {
        val missile = listOf(option("chicken_missile", 7.0))
        val bad = FightSituation(distance = 18.0, targetSpeed = 0.25)
        val normal = (0 until 300).count { AbilityPlanner.choose(missile, bad, BotDifficulty.NORMAL, DefaultKitBehavior, Random(it)) != null }
        val expertUses = (0 until 300).count { expert(missile, bad, it) != null }
        expertUses shouldBe 0
        (normal in 40..250) shouldBe true
    }

    // ---------------------------------------------------------------- item strategy

    fun plan(item: ArenaItem, s: ItemSituation, d: BotDifficulty = BotDifficulty.EXPERT) = ItemTactics.plan(item, s, d)
    val calm = ItemSituation(health = 1.0)
    val fight = ItemSituation(health = 1.0, targetDistance = 10.0, targetVisible = true, targetSpeed = 0.2, nearestEnemy = 10.0)

    "heals are for when the bot is hurt, and the full heal waits until it is badly hurt" {
        plan(ArenaItem.FOOD, calm) shouldBe null
        plan(ArenaItem.FOOD, calm.copy(health = 0.8)) shouldBe ItemAim.NONE
        plan(ArenaItem.AWESOME_SOUP, calm.copy(health = 0.5)) shouldBe null
        plan(ArenaItem.AWESOME_SOUP, calm.copy(health = 0.3)) shouldBe ItemAim.NONE
        plan(ArenaItem.AWESOME_SOUP, calm.copy(health = 0.5), BotDifficulty.NORMAL) shouldBe ItemAim.NONE
        plan(ArenaItem.REGENERATE, calm.copy(energy = 0.9)) shouldBe null
        plan(ArenaItem.REGENERATE, calm.copy(energy = 0.1)) shouldBe ItemAim.NONE
    }
    "recovery items are kept for a fall the bot cannot jump out of" {
        for (item in listOf(ArenaItem.CLOUD_IN_A_BOTTLE, ArenaItem.JETPACK, ArenaItem.INSTANT_TELEPORTER)) {
            plan(item, fight) shouldBe null
            plan(item, fight.copy(falling = true, airJump = true)) shouldBe null
        }
        plan(ArenaItem.CLOUD_IN_A_BOTTLE, fight.copy(falling = true, airJump = false)) shouldBe ItemAim.NONE
        plan(ArenaItem.JETPACK, fight.copy(falling = true, airJump = false)) shouldBe ItemAim.NONE
        plan(ArenaItem.INSTANT_TELEPORTER, fight.copy(falling = true, airJump = false)) shouldBe ItemAim.ESCAPE
        // A Normal bot at least waits until it is falling.
        plan(ArenaItem.CLOUD_IN_A_BOTTLE, fight, BotDifficulty.NORMAL) shouldBe null
        plan(ArenaItem.CLOUD_IN_A_BOTTLE, fight.copy(falling = true), BotDifficulty.NORMAL) shouldBe ItemAim.NONE
    }
    "the teleporter answers an opponent nothing can walk to" {
        plan(ArenaItem.INSTANT_TELEPORTER, fight.copy(targetDistance = 15.0, targetUnreachable = true)) shouldBe ItemAim.TARGET
        plan(ArenaItem.INSTANT_TELEPORTER, fight.copy(targetDistance = 15.0), BotDifficulty.NORMAL) shouldBe ItemAim.TARGET
    }
    "fused explosives are saved for someone standing still" {
        for (item in listOf(ArenaItem.AIR_STRIKE, ArenaItem.CLUSTER_GRENADE, ArenaItem.SINGULARITY_GRENADE)) {
            plan(item, fight) shouldBe null
            plan(item, fight.copy(targetSpeed = 0.0)) shouldBe ItemAim.LOBBED
            plan(item, fight, BotDifficulty.NORMAL) shouldBe ItemAim.LOBBED
        }
    }
    "finishers wait for an opponent at the edge or nearly dead" {
        val close = fight.copy(targetDistance = 3.0)
        plan(ArenaItem.KNOCKBACK_ROD, close) shouldBe null
        plan(ArenaItem.KNOCKBACK_ROD, close.copy(targetNearEdge = true)) shouldBe ItemAim.CONTACT
        plan(ArenaItem.KNOCKBACK_ROD, close.copy(targetHealth = 0.3)) shouldBe ItemAim.CONTACT
        plan(ArenaItem.KNOCKBACK_ROD, fight.copy(targetNearEdge = true)) shouldBe ItemAim.TARGET
        plan(ArenaItem.PLAYER_GRAPPLE, fight) shouldBe null
        plan(ArenaItem.PLAYER_GRAPPLE, fight.copy(voidBehind = true)) shouldBe ItemAim.CONTACT
    }
    "nothing is thrown at an opponent the bot cannot see or reach with it" {
        val offensive = listOf(ArenaItem.HOMING_SNOWBALL, ArenaItem.FIRE_FLOWER, ArenaItem.ROCKET_LAUNCHER, ArenaItem.FRAG_GRENADE,
            ArenaItem.SHOTBOW, ArenaItem.SMASHER, ArenaItem.MONSTER_EGG, ArenaItem.AIR_STRIKE)
        for (d in BotDifficulty.entries) for (item in offensive) {
            plan(item, calm, d) shouldBe null
            plan(item, fight.copy(targetVisible = false), d) shouldBe null
            plan(item, fight.copy(targetDistance = 60.0), d) shouldBe null
        }
        plan(ArenaItem.HOMING_SNOWBALL, fight) shouldBe ItemAim.TARGET
        plan(ArenaItem.SMASHER, fight.copy(targetDistance = 4.0)) shouldBe ItemAim.CONTACT
    }
    "an item held too long is used at the next fair chance, and upgrades are never 'used'" {
        plan(ArenaItem.AIR_STRIKE, fight.copy(heldSeconds = 90)) shouldBe ItemAim.LOBBED
        for (tool in listOf(ArenaItem.DIAMOND_AXE, ArenaItem.DIAMOND_SWORD, ArenaItem.DIAMOND_HELMET)) plan(tool, fight.copy(heldSeconds = 500)) shouldBe null
        ItemTactics.shouldDiscard(3) shouldBe false
        ItemTactics.shouldDiscard(4) shouldBe true
    }
    "an extra life is only channelled with nobody near" {
        plan(ArenaItem.EXTRA_LIFE, calm.copy(nearestEnemy = 30.0)) shouldBe ItemAim.NONE
        plan(ArenaItem.EXTRA_LIFE, calm.copy(nearestEnemy = 8.0)) shouldBe null
        plan(ArenaItem.EXTRA_LIFE, calm.copy(nearestEnemy = 30.0, lives = 5)) shouldBe null
    }
    "skill combos are flawless for Expert and often dropped by Normal" {
        val kit = object : KitBehavior {
            override val combos = listOf(Combo("needler", listOf(ComboStep.Melee(4), ComboStep.Ability("spin_web", 6))))
        }
        val expert = (0 until 200).count { AbilityPlanner.comboFor("needler", kit, BotDifficulty.EXPERT, Random(it)) != null }
        val normal = (0 until 200).count { AbilityPlanner.comboFor("needler", kit, BotDifficulty.NORMAL, Random(it)) != null }
        (expert > 180) shouldBe true
        (normal < 80) shouldBe true
    }
})

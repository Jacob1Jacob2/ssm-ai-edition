package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.bots.chat.CombatChatBridge
import dev.betrix.superSmashMobsBrawl.bots.combat.*
import dev.betrix.superSmashMobsBrawl.items.ArenaItem
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID
import kotlin.random.Random

class CombatFrameworkTest : StringSpec({
    val human = BotDialogue.Fighter(UUID(0,1), "Jacob", "Chicken", 4, false)
    val bot = BotDialogue.Fighter(UUID(0,2), "Dino", "Guardian", 3, true)
    val other = BotDialogue.Fighter(UUID(0,3), "Fox", "Creeper", 2, true)
    val facts = listOf(human, bot, other)
    class Match(mode: BotPersonality = BotPersonality.TOXIC) {
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
    "a bot that has learned a campers trap says so itself, in chat style, and more firmly the second time" {
        for (mode in listOf(BotPersonality.TOXIC, BotPersonality.CHILL, BotPersonality.SALTY)) {
            val m = Match(mode)
            m.bus.publish(TrapLearned(bot.id, human.id, 1, 1000))
            val first = m.say(5000)!!
            first.speaker shouldBe bot.id
            first.text shouldBe first.text.lowercase()
            listOf(",", ".", "'").forEach { first.text.shouldNotContain(it) }
            m.bus.publish(TrapLearned(bot.id, human.id, 2, 60_000))
            m.say(64_000)!!.speaker shouldBe bot.id
        }
        // Somebody on the bots own team did not set it a trap.
        val friendly = Match()
        friendly.bus.publish(TrapLearned(bot.id, bot.id, 1, 1000))
        friendly.say(5000) shouldBe null
    }
    "a bot that is out of the match does not tell a camper to come and fight" {
        for (seed in 0..20) {
            val bus = CombatEventBus()
            val d = BotDialogue(Random(seed), LineMemory(), 1.0).also { it.mode = BotPersonality.CHILL }
            CombatChatBridge(bus, d) { it == bot.id || it == other.id }
            var t = 0L
            while (t <= 19_000) { bus.publish(FighterSample(human.id, 0.0, 70.0, 0.0, 20.0, 20.0, t)); t += 250 }
            bus.publish(TargetUnreachable(other.id, human.id, 19_500))
            t = 20_000
            while (t <= 21_000) { bus.publish(FighterSample(human.id, 0.0, 70.0, 0.0, 20.0, 20.0, t)); t += 250 }
            // The bot that could not get to them has since lost its last life.
            val out = other.copy(lives = 0, fighting = false)
            d.poll(25_000, listOf(human, bot, out)) { a, b -> a != b }?.speaker shouldNotBe other.id
        }
    }
    "a bots chase ends with its life: from the respawn screen it is not hunting anyone" {
        val m = Match()
        m.bus.publish(BotTargetChanged(other.id, null, human.id, 0))
        m.bus.publish(FighterDied(other.id, human.id, "Attack", 5_000))
        m.stand(human.id, 70.0, 0, 30_000)
        m.say(31_000) shouldBe null
    }
    "a bot says ez after its own kill" {
        val lines = (0..40).mapNotNull { seed ->
            val bus = CombatEventBus(); val d = BotDialogue(Random(seed), LineMemory(), 1.0).also { it.mode = BotPersonality.TOXIC }
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
            val bus = CombatEventBus(); val d = BotDialogue(Random(seed), LineMemory(), 1.0).also { it.mode = BotPersonality.TOXIC }
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
    "saves from before Toxic was split off load their Salty as Toxic" {
        BotPersonality.restore("SALTY", legacy = true) shouldBe BotPersonality.TOXIC
        BotPersonality.restore("SALTY") shouldBe BotPersonality.SALTY
        BotPersonality.restore("TOXIC") shouldBe BotPersonality.TOXIC
        BotPersonality.restore("CHILL", legacy = true) shouldBe BotPersonality.CHILL
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
        for (food in ArenaItem.foods) {
            plan(food, calm) shouldBe null
            plan(food, calm.copy(health = 0.8)) shouldBe ItemAim.NONE
        }
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
    "the new items: a trampoline for a fall, a mist for someone staying put, a mystery opened at once" {
        plan(ArenaItem.POCKET_TRAMPOLINE, fight) shouldBe null
        plan(ArenaItem.POCKET_TRAMPOLINE, fight.copy(falling = true, airJump = true)) shouldBe null
        plan(ArenaItem.POCKET_TRAMPOLINE, fight.copy(falling = true, airJump = false)) shouldBe ItemAim.NONE
        plan(ArenaItem.POISON_MIST, fight) shouldBe null
        plan(ArenaItem.POISON_MIST, fight.copy(targetSpeed = 0.0)) shouldBe ItemAim.LOBBED
        plan(ArenaItem.POISON_MIST, fight.copy(targetDistance = 30.0, targetSpeed = 0.0)) shouldBe null
        plan(ArenaItem.POISON_MIST, fight, BotDifficulty.NORMAL) shouldBe ItemAim.LOBBED
        plan(ArenaItem.MYSTERY_ABILITY, calm) shouldBe ItemAim.NONE
    }
    "the car, the rocket, the anvil and the cage trap each have their moment" {
        // The Flying Car is got into for a chase, to reach a camper, or to be caught by when falling: and not got out of again by the same thought.
        plan(ArenaItem.FLYING_CAR, fight) shouldBe ItemAim.NONE
        plan(ArenaItem.FLYING_CAR, fight.copy(riding = true)) shouldBe null
        plan(ArenaItem.FLYING_CAR, fight.copy(targetDistance = 3.0)) shouldBe null
        plan(ArenaItem.FLYING_CAR, calm) shouldBe null
        plan(ArenaItem.FLYING_CAR, calm.copy(falling = true, airJump = false)) shouldBe ItemAim.NONE
        plan(ArenaItem.FLYING_CAR, fight.copy(targetDistance = 3.0, targetUnreachable = true)) shouldBe ItemAim.NONE
        // A few seconds in the tank are not worth a chase, but they are worth being caught by; an empty one is worth nothing.
        plan(ArenaItem.FLYING_CAR, fight.copy(charge = ItemTactics.CAR_WORTH)) shouldBe ItemAim.NONE
        plan(ArenaItem.FLYING_CAR, fight.copy(charge = ItemTactics.CAR_WORTH - 1)) shouldBe null
        plan(ArenaItem.FLYING_CAR, calm.copy(falling = true, airJump = false, charge = 1)) shouldBe ItemAim.NONE
        plan(ArenaItem.FLYING_CAR, calm.copy(falling = true, airJump = false, charge = 0)) shouldBe null
        // The Personal Rocket is ridden at an opponent, seen or (with no way to them) not.
        plan(ArenaItem.PERSONAL_ROCKET, fight) shouldBe ItemAim.TARGET
        plan(ArenaItem.PERSONAL_ROCKET, fight.copy(targetDistance = 2.0)) shouldBe null
        plan(ArenaItem.PERSONAL_ROCKET, fight.copy(targetVisible = false)) shouldBe null
        plan(ArenaItem.PERSONAL_ROCKET, fight.copy(targetVisible = false, targetUnreachable = true)) shouldBe ItemAim.TARGET
        plan(ArenaItem.PERSONAL_ROCKET, calm) shouldBe null
        // A Falling Anvil lands where they were half a second before: on a walk (0.22 blocks a tick) or
        // a run (0.28), but not on someone running and jumping (0.36), unless the bot knows no better.
        plan(ArenaItem.ANVIL, fight.copy(targetSpeed = 0.36)) shouldBe null
        plan(ArenaItem.ANVIL, fight.copy(targetSpeed = 0.36), BotDifficulty.NORMAL) shouldBe ItemAim.NONE
        plan(ArenaItem.ANVIL, fight.copy(targetSpeed = 0.28)) shouldBe ItemAim.NONE
        plan(ArenaItem.ANVIL, fight.copy(targetSpeed = 0.36, targetVisible = false, targetDistance = 50.0, heldSeconds = 90)) shouldBe ItemAim.NONE
        plan(ArenaItem.ANVIL, calm) shouldBe null
        // A Cage Trap goes where a mine would.
        for (s in listOf(fight, fight.copy(targetDistance = 2.0), fight.copy(fleeing = true, targetDistance = 2.0), fight.copy(targetDistance = 30.0), calm))
            plan(ArenaItem.CAGE_TRAP, s) shouldBe plan(ArenaItem.PROXIMITY_MINE, s)
        ItemTactics.siegeValue(ArenaItem.FLYING_CAR) shouldBe 3
        ItemTactics.siegeValue(ArenaItem.PERSONAL_ROCKET) shouldBe 3
    }
    "lives go up to eight: an extra life is worth taking until then, and each count has its colour" {
        plan(ArenaItem.EXTRA_LIFE, calm.copy(nearestEnemy = 30.0, lives = 7)) shouldBe ItemAim.NONE
        plan(ArenaItem.EXTRA_LIFE, calm.copy(nearestEnemy = 30.0, lives = 8)) shouldBe null
        dev.betrix.superSmashMobsBrawl.items.ItemEffects.MAX_LIVES shouldBe 8
        val feedback = dev.betrix.superSmashMobsBrawl.utils.CombatFeedback
        val named = net.kyori.adventure.text.format.NamedTextColor.NAMES
        (1..7).map { named.key(feedback.livesColor(it)) } shouldBe listOf("red", "gold", "yellow", "green", "aqua", "light_purple", "white")
        // Eight lives: every letter its own colour, and the colours move with the shift.
        val plainText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        val rainbow = feedback.livesText("8 Jacob", 8)
        plainText.serialize(rainbow) shouldBe "8 Jacob"
        rainbow.children().map { it.color() }.toSet().size shouldBe 7
        (feedback.livesText("8 Jacob", 8, 3) == rainbow) shouldBe false
        feedback.livesText("7 Jacob", 7).children().size shouldBe 0
    }
    "the rainbow dye is a dye-shaped icon whose colours slide from frame to frame" {
        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(dev.betrix.superSmashMobsBrawl.services.MenuAssets.rainbowDye()))
        image.width shouldBe 16
        (image.height % 16) shouldBe 0
        (image.getRGB(0, 0) ushr 24) shouldBe 0
        (image.getRGB(8, 8) ushr 24) shouldBe 255
        (image.getRGB(8, 8) == image.getRGB(8, 8 + 16 * 6)) shouldBe false
        (image.getRGB(4, 8) == image.getRGB(11, 8)) shouldBe false
    }
    "the teleporter answers an opponent nothing can walk to" {
        plan(ArenaItem.INSTANT_TELEPORTER, fight.copy(targetDistance = 15.0, targetUnreachable = true)) shouldBe ItemAim.TARGET
        plan(ArenaItem.INSTANT_TELEPORTER, fight.copy(targetDistance = 15.0), BotDifficulty.NORMAL) shouldBe ItemAim.TARGET
    }
    "against a camper nothing walks to, items are fetched from across the map and used without waiting" {
        val camper = fight.copy(targetUnreachable = true)
        // Fused explosives and the big rocket do not wait for a camper to stand still.
        plan(ArenaItem.AIR_STRIKE, camper) shouldBe ItemAim.LOBBED
        plan(ArenaItem.ROCKET_LAUNCHER, camper.copy(targetDistance = 20.0)) shouldBe ItemAim.TARGET
        // The Smasher goes through the floor: no sight needed, only range.
        plan(ArenaItem.SMASHER, camper.copy(targetVisible = false, targetDistance = 8.0)) shouldBe ItemAim.NONE
        plan(ArenaItem.SMASHER, camper.copy(targetVisible = false, targetDistance = 12.0)) shouldBe null
        plan(ArenaItem.SMASHER, fight.copy(targetVisible = false, targetDistance = 8.0)) shouldBe null
        // A rod shot knocks them off their perch; a grapple fetches them out of it.
        plan(ArenaItem.KNOCKBACK_ROD, camper) shouldBe ItemAim.TARGET
        plan(ArenaItem.PLAYER_GRAPPLE, camper) shouldBe ItemAim.CONTACT
        plan(ArenaItem.PLAYER_GRAPPLE, fight) shouldBe null
        // What is worth walking the map for: a way across first, something to hit them with second.
        ItemTactics.siegeValue(ArenaItem.JETPACK) shouldBe 3
        ItemTactics.siegeValue(ArenaItem.CLOUD_IN_A_BOTTLE) shouldBe 3
        ItemTactics.siegeValue(ArenaItem.ROCKET_LAUNCHER) shouldBe 2
        ItemTactics.siegeValue(ArenaItem.STEAK) shouldBe 0
        ItemTactics.usedUpFreely(ArenaItem.STEAK) shouldBe true
        ItemTactics.usedUpFreely(ArenaItem.EXTRA_LIFE) shouldBe false
        // A needed item is fetched however far away it is; an ordinary one that far is left lying.
        val far = situation(distance = 12.0, item = 40.0, itemEnemy = 60.0)
        ExpertPolicy.decide(far, CombatState.ATTACK, 0) shouldBe CombatState.ATTACK
        ExpertPolicy.decide(far.copy(itemNeeded = true), CombatState.ATTACK, 0) shouldBe CombatState.GET_ITEM
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
        plan(ArenaItem.EXTRA_LIFE, calm.copy(nearestEnemy = 30.0, lives = 8)) shouldBe null
    }

    // What 500 recorded bot matches showed the bots doing wrong with four items (November 2026).

    "a Rain Staff is pointed at the ground under an opponent, from near enough, and held there" {
        // (Pointed at the opponent, as a rocket is, it rained on whatever was behind them: 2 projectiles in 120 landed.)
        plan(ArenaItem.RAIN_STAFF, fight) shouldBe ItemAim.GROUND
        plan(ArenaItem.RAIN_STAFF, fight, BotDifficulty.NORMAL) shouldBe ItemAim.GROUND
        plan(ArenaItem.RAIN_STAFF, fight.copy(targetDistance = 2.0)) shouldBe null
        plan(ArenaItem.RAIN_STAFF, fight.copy(targetDistance = 20.0)) shouldBe null
        plan(ArenaItem.RAIN_STAFF, fight.copy(targetVisible = false)) shouldBe null
        plan(ArenaItem.RAIN_STAFF, calm) shouldBe null
        ItemTactics.reach(ArenaItem.RAIN_STAFF) shouldBe RainTactics.FAR
        // A volley is ten ticks coming down: it goes where a runner will be by then, for those who lead a shot at all.
        RainTactics.lead(10.0, 5.0, 0.25, -0.5, leads = true) shouldBe (12.5 to 0.0)
        RainTactics.lead(10.0, 5.0, 0.25, -0.5, leads = false) shouldBe (10.0 to 5.0)
        // It falls 2.5 blocks either way of where it is pointed.
        RainTactics.catches(1.5) shouldBe true
        RainTactics.catches(3.0) shouldBe false
        // Once it is raining, somebody who backs off a little is still rained on; somebody in the bot's face is hit instead.
        RainTactics.inReach(17.0, raining = false) shouldBe false
        RainTactics.inReach(17.0, raining = true) shouldBe true
        RainTactics.inReach(3.0, raining = true) shouldBe false
        RainTactics.inReach(10.0, raining = false) shouldBe true
        // Twenty volleys with nothing landing: the rain is not reaching them, and more of it will not either.
        RainTactics.dry(40) shouldBe false
        RainTactics.dry(41) shouldBe true
        (RainTactics.DRY_REST > RainTactics.DRY_TICKS) shouldBe true
        // How both were played before, kept to measure against: the staff anywhere inside 24 blocks, the bomb only on somebody standing still.
        ItemTactics.plan(ArenaItem.RAIN_STAFF, fight.copy(targetDistance = 20.0), BotDifficulty.EXPERT, old = true) shouldBe ItemAim.GROUND
        ItemTactics.plan(ArenaItem.CRAFTY_BOMB, fight.copy(targetDistance = 3.0, targetSpeed = 0.25), BotDifficulty.EXPERT, old = true) shouldBe null
        ItemTactics.plan(ArenaItem.CRAFTY_BOMB, fight.copy(targetDistance = 3.0, targetSpeed = 0.0), BotDifficulty.EXPERT, old = true) shouldBe ItemAim.CONTACT
    }
    "a Crafty Bomb is set down beside whoever is in the fight, not saved for somebody standing still" {
        // (Within six blocks and standing still is nobody: two in three were still in the hand when their owner died.)
        val close = fight.copy(targetDistance = 3.0, targetSpeed = 0.25)
        plan(ArenaItem.CRAFTY_BOMB, close) shouldBe ItemAim.CONTACT
        // Further off, a careful bot wants them slow, or to have carried it too long.
        plan(ArenaItem.CRAFTY_BOMB, close.copy(targetDistance = 5.5)) shouldBe null
        plan(ArenaItem.CRAFTY_BOMB, close.copy(targetDistance = 5.5, targetSpeed = 0.1)) shouldBe ItemAim.CONTACT
        plan(ArenaItem.CRAFTY_BOMB, close.copy(targetDistance = 5.5, heldSeconds = 90)) shouldBe ItemAim.CONTACT
        plan(ArenaItem.CRAFTY_BOMB, close.copy(targetDistance = 5.5), BotDifficulty.NORMAL) shouldBe ItemAim.CONTACT
        plan(ArenaItem.CRAFTY_BOMB, close.copy(targetDistance = 8.0)) shouldBe null
        plan(ArenaItem.CRAFTY_BOMB, close.copy(targetVisible = false)) shouldBe null
        // With no room to set it down here it is kept for somewhere else, not thrown away after four tries like something of no use.
        ItemTactics.shouldDiscard(4, ArenaItem.CRAFTY_BOMB) shouldBe false
        ItemTactics.shouldDiscard(ItemTactics.CRAFTY_TRIES, ArenaItem.CRAFTY_BOMB) shouldBe true
        ItemTactics.shouldDiscard(4, ArenaItem.FRAG_GRENADE) shouldBe true
    }
    "whoever sets a Crafty Bomb down gets out between two of its lines" {
        // Feet on the ground the bomb stands on are a block under its middle.
        fun hit(dx: Double, dz: Double, margin: Double = 0.0) = CraftyTactics.inBlast(dx, -1.0, dz, margin)
        hit(0.0, 0.0) shouldBe true
        hit(3.0, 2.0) shouldBe true        // within four blocks of the middle
        hit(10.0, 0.0) shouldBe true       // out along a line
        hit(-17.0, 1.0) shouldBe true
        hit(0.0, -12.0) shouldBe true
        hit(21.0, 0.0) shouldBe false      // past the end of a line
        hit(10.0, 2.5) shouldBe false      // beside one
        hit(4.0, 4.0) shouldBe false       // between two
        // Clear is clear by a block: just outside is no place to stand and wait.
        hit(10.0, 2.5, CraftyTactics.MARGIN) shouldBe true
        // A floor well over or under a line is out of it; one over the bomb itself is not.
        CraftyTactics.inBlast(10.0, 3.0, 0.0) shouldBe false
        CraftyTactics.inBlast(1.0, 3.0, 1.0) shouldBe true
        // Everywhere offered is clear by that block, and the first is the nearest to where the bot stands.
        val offered = CraftyTactics.refuges(2.0, 1.0)
        offered.size shouldBe 28
        offered.all { (x, z) -> !hit(x, z, CraftyTactics.MARGIN) } shouldBe true
        offered.first() shouldBe (4.0 to 4.0)
        CraftyTactics.refuges(-2.0, -1.0).first() shouldBe (-4.0 to -4.0)
        CraftyTactics.refuges(1.0, -3.0).first() shouldBe (4.0 to -4.0)
        // Three seconds is long enough to walk there: four or five blocks at 0.22 a tick.
        (CraftyTactics.FUSE_TICKS * 0.22 > 8.0) shouldBe true
    }
    "a bot goes for the rarer of two things lying about, and lets go of what it has for something much better" {
        // (It used to go for whatever was nearest and keep whatever it had: every item was picked up as often as every other.)
        // Worth is rarity: 0 for Common up to 5 for Ultimate.
        ItemChoice.worth(ArenaItem.SPEED, 1.0) shouldBe 0
        ItemChoice.worth(ArenaItem.MISSILE_BATTERY, 1.0) shouldBe 4
        ItemChoice.worth(ArenaItem.ANVIL, 1.0) shouldBe 5
        // Food counts for more to somebody badly hurt, and for less to somebody who is not hurt at all.
        ItemChoice.worth(ArenaItem.STEAK, 0.3) shouldBe 2
        ItemChoice.worth(ArenaItem.STEAK, 0.7) shouldBe 0
        ItemChoice.worth(ArenaItem.STEAK, 1.0) shouldBe -1
        ItemChoice.worth(ArenaItem.AWESOME_SOUP, 0.3) shouldBe 6
        // A rarity is worth five blocks of walking: the Legendary twelve blocks off, over the Common three blocks off.
        (ItemChoice.pull(4, 12.0) > ItemChoice.pull(0, 3.0)) shouldBe true
        (ItemChoice.pull(1, 9.0) > ItemChoice.pull(0, 3.0)) shouldBe false
        // What is in the hand goes for something two rarities better, if it is near: not for one better, and not across the map.
        ItemChoice.swaps(held = 0, found = 5, distance = 6.0) shouldBe true
        ItemChoice.swaps(held = 2, found = 4, distance = 6.0) shouldBe true
        ItemChoice.swaps(held = 2, found = 3, distance = 6.0) shouldBe false
        ItemChoice.swaps(held = 0, found = 5, distance = 20.0) shouldBe false
        ItemChoice.swaps(held = 5, found = 5, distance = 1.0) shouldBe false
    }
    "an ability that starts by picking up a block is clicked on a block, not on thin air" {
        // (An Enderman's Block Toss does nothing on a click at the air, which is all a bot's click ever was:
        // over 500 matches Enderman bots did 1% of their damage with abilities and won 1.9% of the time.)
        ("block_toss" in KitPlay.NEEDS_A_BLOCK) shouldBe true
        ("blink" in KitPlay.NEEDS_A_BLOCK) shouldBe false
        // And it is one the bots are told to hold: picked up, charged, let go.
        (AbilityHints.of("block_toss").holdTicks.first > 0) shouldBe true
        (KitPlay.ARM > 3.0) shouldBe true
    }
    "a Phantom is flown out of reach, over where its bombs will land, and brought down before the tank is dry" {
        // (It used to hover three blocks up, in reach of a hit from the ground, slower than its opponent ran: 0.5% of its matches won.)
        // Out of reach: a hit reaches about three blocks from eyes 1.6 up, and a jump adds 1.25.
        (PhantomPlay.HEIGHT > 3.0 + 1.6 + 1.25) shouldBe true
        // Faster than a run (0.28 blocks a tick), within what the kit allows (0.52).
        (PhantomPlay.SPEED > 0.36) shouldBe true
        (PhantomPlay.SPEED <= 0.52) shouldBe true
        // Under a roof it hovers as high as there is room for, and never so low as to be standing in the fight.
        PhantomPlay.height(null) shouldBe PhantomPlay.HEIGHT
        PhantomPlay.height(4.0) shouldBe 4.0
        PhantomPlay.height(0.5) shouldBe PhantomPlay.LOWEST
        PhantomPlay.height(30.0) shouldBe PhantomPlay.HEIGHT
        // A bomb goes when they are underneath and near enough for its 3.5 blocks to reach; not from beside them, and not on the last of the tank.
        PhantomPlay.bombs(flatGap = 1.0, above = 6.0, fuel = 0.8) shouldBe true
        PhantomPlay.bombs(flatGap = 5.0, above = 6.0, fuel = 0.8) shouldBe false
        PhantomPlay.bombs(flatGap = 1.0, above = 0.5, fuel = 0.8) shouldBe false
        PhantomPlay.bombs(flatGap = 1.0, above = 6.0, fuel = 0.1) shouldBe false
        // The last of the tank is for getting back to the ground; and it does not take off to attack on less than half.
        PhantomPlay.headsHome(0.1) shouldBe true
        PhantomPlay.headsHome(0.5) shouldBe false
        (PhantomPlay.TAKE_OFF > PhantomPlay.RESERVE * 2) shouldBe true
    }
    "a careful bot fires the Rocket Launcher from outside its own blast" {
        // (Ten blocks, its owner included: from eight it cost the bot a fifth of a health bar of its own.)
        plan(ArenaItem.ROCKET_LAUNCHER, fight.copy(targetDistance = 9.0)) shouldBe null
        plan(ArenaItem.ROCKET_LAUNCHER, fight.copy(targetDistance = 12.0)) shouldBe ItemAim.TARGET
        // Further off it still wants somebody slow, or to have carried it too long.
        plan(ArenaItem.ROCKET_LAUNCHER, fight.copy(targetDistance = 22.0)) shouldBe null
        plan(ArenaItem.ROCKET_LAUNCHER, fight.copy(targetDistance = 22.0, targetSpeed = 0.0)) shouldBe ItemAim.TARGET
        // A Normal bot knows no better, and the old rule is kept to measure against.
        plan(ArenaItem.ROCKET_LAUNCHER, fight.copy(targetDistance = 9.0), BotDifficulty.NORMAL) shouldBe ItemAim.TARGET
        ItemTactics.plan(ArenaItem.ROCKET_LAUNCHER, fight.copy(targetDistance = 9.0), BotDifficulty.EXPERT, old = true) shouldBe ItemAim.TARGET
        (ItemTactics.ROCKET_CLEAR > 10.0) shouldBe true
    }
    "what is about to go off is noticed when it is seen coming or heard landing, and then got away from" {
        // Its own a bot knows about. Anybody else's it has to see (in front of it, nothing in the way) or hear land beside it.
        DangerTactics.noticed(12.0, inView = true, openLine = true) shouldBe true
        DangerTactics.noticed(12.0, inView = true, openLine = false) shouldBe false
        DangerTactics.noticed(12.0, inView = false, openLine = true) shouldBe false
        DangerTactics.noticed(3.0, inView = false, openLine = false) shouldBe true
        DangerTactics.noticed(25.0, inView = true, openLine = true) shouldBe false
        // Then a moment passes before it moves: none for its own, longer the slower the player.
        DangerTactics.readyAfter(BotDifficulty.EXPERT, own = true) shouldBe 0
        DangerTactics.readyAfter(BotDifficulty.EXPERT, own = false) shouldBe 4
        DangerTactics.readyAfter(BotDifficulty.NORMAL, own = false) shouldBe 18
        // A Frag Grenade reaches three blocks: a bot wants to be a block past that.
        DangerTactics.inRound(2.0, 0.0, 2.0, 3.0) shouldBe true
        DangerTactics.inRound(3.0, 0.0, 2.0, 3.0) shouldBe false
        DangerTactics.inRound(3.0, 0.0, 2.0, 3.0, DangerTactics.MARGIN) shouldBe true
        // A mine is stepped on: on the flat, and at about its height.
        DangerTactics.onTrigger(1.0, 0.0, 0.5, 1.35) shouldBe true
        DangerTactics.onTrigger(1.0, 3.0, 0.5, 1.35) shouldBe false
        DangerTactics.onTrigger(2.0, 0.0, 0.5, 1.35) shouldBe false
        // Out is straight away from it first, just past its reach, then round to either side: and every way offered is clear.
        val out = DangerTactics.waysOut(1.0, 0.0, 3.0)
        out.size shouldBe 7
        out.first().first shouldBe (4.5 plusOrMinus 1e-9)
        out.first().second shouldBe (0.0 plusOrMinus 1e-9)
        out.all { (x, z) -> !DangerTactics.inRound(x, 0.0, z, 3.0, DangerTactics.MARGIN) } shouldBe true
        // Standing on its very middle, any way is away.
        DangerTactics.waysOut(0.0, 0.0, 3.0).size shouldBe 7
    }
    "a mine or a trap lying in the way is gone round, on the side the way already leans to" {
        // Walking east with a mine dead ahead and a little to the south: past it on the north.
        val (x, z) = DangerTactics.round(0.0, 0.0, 4.0, 0.0, 2.0, 0.3, 1.95)!!
        x shouldBe (2.0 plusOrMinus 1e-9)
        (z < -1.9) shouldBe true
        // A little to the north: past it on the south.
        (DangerTactics.round(0.0, 0.0, 4.0, 0.0, 2.0, -0.3, 1.95)!!.second > 1.9) shouldBe true
        // Off to one side, behind, or beyond where the bot is going: nothing to go round.
        DangerTactics.round(0.0, 0.0, 4.0, 0.0, 2.0, 3.0, 1.95) shouldBe null
        DangerTactics.round(0.0, 0.0, 4.0, 0.0, -4.0, 0.0, 1.95) shouldBe null
        DangerTactics.round(0.0, 0.0, 4.0, 0.0, 9.0, 0.0, 1.95) shouldBe null
        DangerTactics.round(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.95) shouldBe null
    }
    "a Monster Egg clone that has come for a bot is turned on and hit" {
        // (Bots used to hit one only when it stood between them and a player, and took every swing it had.)
        CloneTactics.turnsOn(4.4, 3.0) shouldBe true
        CloneTactics.turnsOn(5.0, 3.0) shouldBe false
        CloneTactics.swings(3.5, 3.0) shouldBe true
        CloneTactics.swings(3.7, 3.0) shouldBe false
        // It swings from 2.65 blocks: by then it has been turned on, and is within a hit.
        CloneTactics.swings(2.65, 3.0) shouldBe true
        (CloneTactics.NOTICE > 3.0 + 1.5) shouldBe true
        // One it cannot get a hit on (up on a ledge) is not swung at for ever: given up on after a couple of seconds, for ten.
        // Nobody has eyes in the back of their head: a clone coming from behind or the side gets its
        // first swing in, and is turned on once it has been felt. The slower the player, the later.
        CloneTactics.inView(0.0) shouldBe true
        CloneTactics.inView(-59.0) shouldBe true
        CloneTactics.inView(61.0) shouldBe false
        CloneTactics.inView(180.0) shouldBe false
        CloneTactics.readyAfter(BotDifficulty.EXPERT) shouldBe 4
        CloneTactics.readyAfter(BotDifficulty.NORMAL) shouldBe 18
        (CloneTactics.FELT_RANGE >= 2.65) shouldBe true
        CloneTactics.givesUp(50) shouldBe false
        CloneTactics.givesUp(51) shouldBe true
        (CloneTactics.LEFT_ALONE_TICKS >= 4 * CloneTactics.PATIENCE) shouldBe true
    }
    "somebody in a Shadow Walk is swung at for a moment longer, and again whenever a hit gives them away" {
        // In plain sight there is nothing to remember.
        for (d in BotDifficulty.entries) ShadowTactics.knows(false, 10_000, d) shouldBe true
        ShadowTactics.knows(true, 0, BotDifficulty.EXPERT) shouldBe true
        ShadowTactics.knows(true, 45, BotDifficulty.EXPERT) shouldBe true
        ShadowTactics.knows(true, 46, BotDifficulty.EXPERT) shouldBe false
        // The better the player, the longer they keep their bearings.
        BotDifficulty.entries.map { ShadowTactics.memoryTicks(it) } shouldBe listOf(15, 25, 35, 45)
        ShadowTactics.knows(true, 20, BotDifficulty.NORMAL) shouldBe false
        // Never anything like the whole thirty seconds: somebody who gets away unseen is lost.
        (ShadowTactics.memoryTicks(BotDifficulty.EXPERT) < ArenaItem.SHADOW_WALK_TICKS / 4) shouldBe true
        (ShadowTactics.BLUR > 0.0) shouldBe true
        // Lost, too, if they went some way no legs take them: a teleport, or a death and a new life
        // across the map (everybody is unseen for their first two seconds back).
        ShadowTactics.followed(3.0, 0) shouldBe true
        ShadowTactics.followed(6.0, 10) shouldBe true
        ShadowTactics.followed(30.0, 10) shouldBe false
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

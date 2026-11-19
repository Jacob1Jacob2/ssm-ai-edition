package dev.betrix.superSmashMobsBrawl.bots

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID
import kotlin.random.Random

class TeamUpsTest : StringSpec({
    val human = UUID(0, 1)
    fun bot(n: Int) = UUID(0, 100L + n)
    /** The player and one bot for each personality given, everybody on four lives. */
    fun lobby(vararg personalities: BotPersonality) = listOf(TeamUps.Fighter(human, 4, false, true, BotPersonality.SILENT)) +
        personalities.mapIndexed { i, p -> TeamUps.Fighter(bot(i), 4, true, true, p) }
    fun engine(seed: Int = 1) = TeamUps(Random(seed)).also { it.enabled = true }

    /**
     * The match as the rules see it: the clock moves a quarter of a second at a time, the player
     * is reported camping while [camping] says so, and everything that happens is collected.
     */
    class Match(val ups: TeamUps, val fighters: List<TeamUps.Fighter>, val player: UUID) {
        var now = 0L
        val events = ArrayList<TeamUps.Event>()
        fun run(seconds: Int, camping: Boolean = false, until: (TeamUps.Event) -> Boolean = { false }): TeamUps.Event? {
            repeat(seconds * 4) {
                now += 250
                if (camping) ups.camping(player, now)
                val fresh = ups.poll(now, fighters)
                events += fresh
                fresh.firstOrNull(until)?.let { return it }
            }
            return null
        }
        /** Camps until the bots team up; null if they never do in [most] seconds. */
        fun campUntilTeamed(most: Int = 120) = run(most, camping = true) { it is TeamUps.Formed } as TeamUps.Formed?
    }

    "a bot that is about to call for it warns the player first, once" {
        val match = Match(engine(), lobby(BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.TOXIC), human)
        val formed = match.campUntilTeamed()!!
        val warnings = match.events.filterIsInstance<TeamUps.Warned>()
        warnings.size shouldBe 1
        warnings.single().target shouldBe human
        // A few seconds before, not in the same breath.
        (match.events.indexOf(warnings.single()) < match.events.indexOf(formed)) shouldBe true
        match.events.indexOf(formed) shouldBeGreaterThan 0
    }

    "nothing is counted and nobody teams outside the Team-Ups mode" {
        val ups = TeamUps(Random(1))
        val match = Match(ups, lobby(BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.TOXIC), human)
        match.campUntilTeamed() shouldBe null
        ups.heatOf(human) shouldBe 0.0
        ups.pact shouldBe null
    }

    "a toxic lobby teams up on a camper long before a chill one does" {
        val toxic = Match(engine(), lobby(BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.TOXIC), human)
        val chill = Match(engine(), lobby(BotPersonality.CHILL, BotPersonality.CHILL, BotPersonality.CHILL), human)
        toxic.campUntilTeamed() shouldNotBe null
        chill.campUntilTeamed() shouldNotBe null
        // (Camping is only reported once a bot has been after the camper for a while: this is on top of that.)
        (toxic.now / 1000).toInt() shouldBeLessThan 25
        (chill.now / 1000).toInt() shouldBeGreaterThan 34
        (chill.now - toxic.now).toInt() shouldBeGreaterThan 12_000
    }

    "salty bots sit between the two, and silent ones team without being the first to" {
        fun seconds(p: BotPersonality) = Match(engine(), lobby(p, p, p), human).also { it.campUntilTeamed() shouldNotBe null }.now / 1000.0
        seconds(BotPersonality.TOXIC) shouldBeLessThan seconds(BotPersonality.SILENT)
        seconds(BotPersonality.SALTY) shouldBeLessThan seconds(BotPersonality.CHILL)
        seconds(BotPersonality.SILENT) shouldBeLessThan seconds(BotPersonality.CHILL)
    }

    "the bots of a pact are partners, and all after the same player" {
        val match = Match(engine(), lobby(BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.TOXIC), human)
        // (Bots have tempers of their own: when the first two have had enough the third may not have yet.)
        val formed = match.campUntilTeamed()!!
        formed.pact.target shouldBe human
        formed.pact.reason shouldBe TeamUps.Reason.CAMPING
        formed.pact.members.size shouldBeGreaterThan 1
        val (a, b) = formed.pact.members.toList()
        match.ups.allied(a, b) shouldBe true
        match.ups.allied(a, human) shouldBe false
        match.ups.targetOf(a) shouldBe human
        match.ups.targetOf(human) shouldBe null
        formed.declined.forEach { out -> match.ups.allied(a, out) shouldBe false; match.ups.targetOf(out) shouldBe null }
    }

    "it takes two: a bot with nobody to agree with it is no team" {
        Match(engine(), lobby(BotPersonality.TOXIC), human).campUntilTeamed() shouldBe null
    }

    "a chill bot stays out of what the toxic ones start" {
        val match = Match(engine(), lobby(BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.CHILL), human)
        val formed = match.campUntilTeamed()!!
        formed.pact.members shouldNotContain bot(2)
        formed.declined shouldContain bot(2)
    }

    "the pact ends when the player loses a life, and the lobby then leaves them alone for a while" {
        val match = Match(engine(), lobby(BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.TOXIC), human)
        val formed = match.campUntilTeamed()!!
        val hot = match.ups.heatOf(human)
        match.ups.death(human, formed.pact.leader, match.now)
        val ended = match.run(1) { it is TeamUps.Ended } as TeamUps.Ended
        ended.how shouldBe TeamUps.End.KILLED
        match.ups.pact shouldBe null
        match.ups.heatOf(human) shouldBeLessThan hot / 2
        // Straight back to the same spot: no new pact before the lobby has had its rest.
        val again = match.campUntilTeamed()!!
        (again.pact.since - match.events.filterIsInstance<TeamUps.Formed>().first().pact.since >= TeamUps.REST_MS) shouldBe true
    }

    "bots lose interest in a player they cannot get" {
        val match = Match(engine(), lobby(BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.TOXIC), human)
        val formed = match.campUntilTeamed()!!
        val ended = match.run(60, camping = true) { it is TeamUps.Ended } as TeamUps.Ended
        ended.how shouldBe TeamUps.End.TIMEOUT
        (match.now - formed.pact.since) shouldBe TeamUps.PACT_MS
    }

    "a pact on a player who is out of the match is over, and so is one with too few bots left" {
        val gone = Match(engine(), lobby(BotPersonality.TOXIC, BotPersonality.TOXIC), human)
        gone.campUntilTeamed() shouldNotBe null
        (gone.ups.poll(gone.now + 250, gone.fighters.map { if (it.id == human) it.copy(lives = 0) else it }).single() as TeamUps.Ended).how shouldBe TeamUps.End.GONE
        val broken = Match(engine(), lobby(BotPersonality.TOXIC, BotPersonality.TOXIC), human)
        broken.campUntilTeamed() shouldNotBe null
        (broken.ups.poll(broken.now + 250, broken.fighters.map { if (it.id == bot(0)) it.copy(lives = 0) else it }).single() as TeamUps.Ended).how shouldBe TeamUps.End.BROKEN
    }

    "only a player draws a team: what bots do to each other does not count" {
        val ups = engine()
        val fighters = lobby(BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.TOXIC)
        ups.poll(0, fighters)
        repeat(200) { ups.camping(bot(0), it * 250L) }
        repeat(6) { ups.death(bot(1), bot(0), 1000L * it) }
        repeat(12) { ups.cast(bot(0), "Sulphur Bomb", 1000L * it) }
        ups.heatOf(bot(0)) shouldBe 0.0
        ups.poll(60_000, fighters).shouldBeEmpty()
    }

    "being farmed is a grievance: the salty bot that keeps dying is the one that calls for help" {
        val match = Match(engine(), lobby(BotPersonality.SALTY, BotPersonality.TOXIC, BotPersonality.TOXIC), human)
        match.run(1)
        var formed: TeamUps.Formed? = null
        // The player kills the same bot every five seconds.
        for (kill in 1..8) {
            match.ups.death(bot(0), human, match.now)
            formed = match.run(5) { it is TeamUps.Formed } as TeamUps.Formed?
            if (formed != null) break
        }
        formed!!.pact.leader shouldBe bot(0)
        formed.pact.reason shouldBe TeamUps.Reason.FARMING
        match.ups.killsOf(human, bot(0), match.now) shouldBeGreaterThan 1
    }

    "one kill, or fighting somebody new each time, is just playing" {
        val match = Match(engine(), lobby(BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.TOXIC), human)
        match.run(1)
        // A kill every twenty seconds, never the same bot twice running.
        repeat(9) { round ->
            match.ups.death(bot(round % 3), human, match.now)
            match.run(20) shouldBe null
        }
        match.ups.pact shouldBe null
    }

    "pressing one ability over and over counts; playing the kit does not" {
        val ups = engine()
        val fighters = lobby(BotPersonality.TOXIC, BotPersonality.TOXIC)
        ups.poll(0, fighters)
        // Three presses in the window, of each of three abilities.
        for (i in 0 until 3) for (ability in listOf("Egg Blaster", "Chicken Missile", "Flap")) ups.cast(human, ability, 1000L + i * 9000)
        ups.heatOf(human) shouldBe 0.0
        ups.spammed(human, 20_000) shouldBe "Egg Blaster"
        repeat(10) { ups.cast(human, "Chicken Missile", 21_000L + it * 2000) }
        ups.heatOf(human) shouldBeGreaterThan 30.0
        ups.spammed(human, 41_000) shouldBe "Chicken Missile"
    }

    "trash talk heats the lobby up, but not every line of it" {
        val ups = engine()
        ups.poll(0, lobby(BotPersonality.TOXIC, BotPersonality.TOXIC))
        ups.said(human, "what kit are you", 1000)
        ups.heatOf(human) shouldBe 0.0
        ups.said(human, "ez", 2000)
        ups.heatOf(human) shouldBe TeamUps.MOUTH_HEAT
        ups.said(human, "ur all trash", 3000)
        ups.heatOf(human) shouldBe TeamUps.MOUTH_HEAT
        ups.said(human, "get good", 2000 + TeamUps.MOUTH_GAP_MS)
        ups.heatOf(human) shouldBe TeamUps.MOUTH_HEAT * 2
    }

    "saying sorry makes the chill ones leave; the others stay on" {
        val match = Match(engine(), lobby(BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.CHILL), human)
        // The chill bot is only in on it once it has gone on for a good while: the first pact is without it.
        var formed = match.campUntilTeamed()!!
        while (bot(2) !in formed.pact.members) {
            match.run(120, camping = true) { it is TeamUps.Ended } shouldNotBe null
            formed = match.campUntilTeamed(300)!!
        }
        val hot = match.ups.heatOf(human)
        match.ups.said(human, "ok sorry", match.now)
        match.ups.heatOf(human) shouldBe hot - TeamUps.APOLOGY_COOLS
        val left = match.run(1) { it is TeamUps.Left } as TeamUps.Left
        left.quitters shouldBe listOf(bot(2))
        match.ups.pact!!.members shouldBe setOf(bot(0), bot(1))
        match.ups.allied(bot(0), bot(2)) shouldBe false
    }

    "a pact of chill bots and one other is called off by an apology" {
        val match = Match(engine(), lobby(BotPersonality.CHILL, BotPersonality.CHILL, BotPersonality.SALTY), human)
        var formed = match.campUntilTeamed(300)!!
        while (formed.pact.members.size < 3) {
            match.run(120, camping = true) { it is TeamUps.Ended } shouldNotBe null
            formed = match.campUntilTeamed(300)!!
        }
        match.ups.said(human, "my bad", match.now)
        (match.run(1) { it is TeamUps.Ended } as TeamUps.Ended).how shouldBe TeamUps.End.FORGIVEN
        match.ups.pact shouldBe null
    }

    "a toxic bot may turn on a partner when it is over; nobody else ever does" {
        var turned = 0
        for (seed in 1..60) {
            val match = Match(engine(seed), lobby(BotPersonality.TOXIC, BotPersonality.SALTY, BotPersonality.SALTY), human)
            val formed = match.campUntilTeamed()!!
            match.ups.death(human, formed.pact.leader, match.now)
            val ended = match.run(1) { it is TeamUps.Ended } as TeamUps.Ended
            if (ended.turncoat != null) {
                turned++
                ended.turncoat shouldBe bot(0)
                formed.pact.members shouldContain ended.betrayed!!
                ended.betrayed shouldNotBe bot(0)
            }
        }
        turned shouldBeGreaterThan 8
        turned shouldBeLessThan 40
        for (seed in 1..30) {
            val match = Match(engine(seed), lobby(BotPersonality.SALTY, BotPersonality.SALTY, BotPersonality.CHILL), human)
            val formed = match.campUntilTeamed(300)!!
            match.ups.death(human, formed.pact.leader, match.now)
            (match.run(1) { it is TeamUps.Ended } as TeamUps.Ended).turncoat shouldBe null
        }
    }

    "heat fades when the player behaves, and dying takes most of it away" {
        val match = Match(engine(), lobby(BotPersonality.CHILL, BotPersonality.CHILL), human)
        match.run(10, camping = true)
        val hot = match.ups.heatOf(human)
        hot shouldBeGreaterThan 20.0
        // (For the first two seconds after they stop it is not yet clear that they have stopped.)
        match.run(10)
        match.ups.heatOf(human) shouldBe ((hot - 8 * TeamUps.FADE_PER_SECOND) plusOrMinus 1e-6)
        match.ups.death(human, null, match.now)
        match.ups.heatOf(human) shouldBeLessThan 1.0
    }
})

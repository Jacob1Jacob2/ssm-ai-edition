package dev.betrix.superSmashMobsBrawl.smash

/** Gameplay descriptions for both the individual kit page and Sam's tier guide. */
object SmashDescriptions {
    private val descriptions = mapOf(
        "polar_bear" to listOf(
            "Throw six killable polar bear cubs that chase and attack enemies. Eight packs, keeping Cub Carrier's 15-second cooldown.",
            "Hold to leap higher, then release to dive faster in your facing direction. Landing freezes a nine-block-radius circle of ice and packed ice, bounces enemies up, then shatters for 6 damage and 160% knockback. Twelve uses without a recharge delay.",
            "Throw a stronger adult polar bear with 30 health and 6 attack damage. Ten faster bears with no recharge delay; they remain until killed or your life ends.",
            "Fire one raw salmon every 0.5 seconds. Eat its cooked drop to reduce recharge by 0.01 seconds, down to 0.05. The red XP bar tracks 45 upgrades; misses lose one upgrade, requiring an extra consecutive miss above each 10% threshold. Lasts until you die."),
        "goat" to listOf(
            "Hold to fire sixty musical notes per second with spread and varied pitches. Each three-note burst uses one of 125 charges.",
            "Launch 23 notes that spread outward, hover for one second, then home toward enemies one by one at eight notes per second. Eight volleys.",
            "A fast, gently bobbing ring of musical notes circles you at a 4.5-block radius for this life, damaging enemies who touch it.",
            "Fast, bobbing layers of musical notes surround you at 3.5, 5.5 and 7.5-block radii for this life, damaging enemies on contact. Grants Speed III."),
        "mooshroom" to listOf(
            "Ten strawberry swirls with a wider radius and 20% more damage.",
            "Eight 5x5 mushroom trampolines with stronger launches and spores that deal 8 damage to enemies.",
            "Raise ten 5x5 Spore Walls moving three times faster with 50% more contact damage and dangerous spores.",
            "Overgrowth can hold six absorption hearts. An eight-block-radius spore aura surrounds you for this life, dealing 2 damage at its edge and up to 6 damage close to you."),
        "phantom" to listOf(
            "Twelve membrane volleys that split into seven fragments and deal 20% more damage.",
            "Refills flight fuel and reduces flight drain by 40% for this life. Bombs still cost energy.",
            "For this life, Phantom Bombs are 20% larger, deal 20% more damage, consume 20% less bomb fuel and destroy more terrain with explosive sound effects.",
            "For 20 seconds, night falls and your hotbar is hidden. You must stay in fast flight until Nightfall ends, with full fuel and stronger terrain-breaking bombs dropping twice per second."),

        "skeleton" to listOf(
            "Your bow becomes a crossbow that fires instantly without charging.",
            "Empties your hotbar and fires twenty arrows per second with shotgun spread for ten seconds.",
            "Ten larger Bone Explosions scatter 144 bones with layered skeleton sounds, a wider radius and increased damage.",
            "For twenty-three seconds, fires three arrows each tick with spread and homing. Your hotbar stays empty and damage is limited to two hits per second."),
        "iron_golem" to listOf(
            "An instant Seismic Slam without jumping, with a 20% larger radius and the normal slam's ground effects.",
            "Your fissure turns to follow moving enemies, traveling three times faster and farther than before. Jump over it to dodge.",
            "An earthquake shakes the entire arena, damaging grounded enemies and launching them upward.",
            "A stronger earthquake deals triple its former damage with vertical knockback and smoke along the floor."),
        "spider" to listOf(
            "Needler fires larger homing arrows with 20% more damage.",
            "Launches seventy-five webs behind you and propels you forward. The webs last twenty seconds. Twelve uses with the normal Spin Web sound.",
            "Sneak to move automatically in your viewing direction, including through the air. Twenty seconds of fuel, spent only while sneaking.",
            "An eighteen-block-radius web dome lasts thirty seconds. During that time, lifesteal gradually earns up to five extra hearts and ability recharges become one second."),
        "slime" to listOf(
            "Hold to fly and slam into enemies for increased damage. Collisions bounce you backward. Release to save fuel; you can still be hurt.",
            "Slime Rocket charges three times faster, uses only 10% of the normal charging energy, and deals 20% more damage. Energy regenerates three times faster when not charging.",
            "Doubles your size at every energy level. More energy grants more armor, damage and jump power.",
            "Become a gigantic Slime. Nearby enemies take damage and massive knockback while your normal hotbar is hidden."),
        "squid" to listOf(
            "Hold Ultra Squid to fly 25% faster than Super Squid. Release to conserve your fuel.",
            "Hold to fire twenty ink shots per second with a small spread and the Ink Shotgun sound. Each shot deals the same damage as an Ink Shotgun pellet and uses one durability.",
            "Fire a huge ink projectile that explodes on impact, damages enemies and destroys terrain.",
            "Darkens the sky with a thunderstorm. Fly freely and hold Use with the Lightning Squid star to repeatedly call delayed lightning where you aim. You are immune to damage but can still be knocked back."),
        "creeper" to listOf(
            "Launches larger homing charcoal bombs with 20% more damage.",
            "Explode instantly for increased damage, then launch in the direction you are looking.",
            "Hover for two seconds, then detonate stacked explosions with twice the former damage and blast radius, leaving a large crater.",
            "Hover for three seconds, then detonate many stacked explosions with triple the former damage and three times the terrain destruction."),
        "enderman" to listOf(
            "Sneak to teleport instantly onto the surface under your cursor. The XP bar shows remaining teleports.",
            "Hold on a solid block to charge, then release to throw three copies of that block at increased speed.",
            "When an enemy looks into your eyes, scream and gain armor, 80% faster movement without the extreme FOV zoom, and extra melee damage for this life.",
            "Ride a terrain-destroying Ender Dragon that deals massive knockback. It follows your view with responsive steering, animated wings and dragon sounds."),
        "snowman" to listOf(
            "Arctic Aura gains five blocks of range and stronger slowness for this life.",
            "Hold to build a five-block-wide packed-ice path in any viewing direction, up to 100 blocks long. Enemies on it receive Slowness X.",
            "Use the star to summon an automatic turret that faces its target and fires mildly homing snowballs. Three summons; the star's durability tracks remaining uses. Each turret lasts 20 seconds, then vanishes in white smoke.",
            "Use the star for three double-sized snowman turrets that turn toward targets and rapidly fire triple snowballs with stronger homing. Durability tracks remaining summons. Each lasts 20 seconds, then vanishes in white smoke."),
        "wolf" to listOf(
            "For this life, your melee combo passive gains one bonus damage per successful hit, up to five stacks. Each stack lasts seven seconds. The blue XP bar tracks your stacks.",
            "Wolf Fly launches you 50% farther and deals 20% more damage.",
            "Throws adult wolves forward like Cub Tackle. They land and chase enemies for 18 seconds, dealing 20% more damage.",
            "For 30 seconds, move 60% faster without the extreme FOV zoom, gain Regeneration III and Strength III, and recharge abilities 80% faster."),
        "magma_cube" to listOf(
            "Your kill passive grants two armor points and 25% knockback resistance per kill.",
            "Hold to fire 36 small fireballs in pairs every three ticks. Each deals one-third of the original damage and breaks a few blocks on impact.",
            "Hold to Flame Dash 50% faster without contact damage. Releasing or hitting a wall ends the dash in a wider explosion. Fuel lasts 12 seconds in total.",
            "For 20 seconds, fireballs rain around you and nearby enemies. More are aimed at enemies when a target is in range."),
        "witch" to listOf(
            "Daze Potion becomes a splash potion with 20% more damage and a random weakness, slowness, poison, blindness or harming effect. Twelve throws with no cooldown.",
            "Throws lingering potions with random harmful effects. Their clouds continue affecting enemies after impact. Fifteen throws with no cooldown.",
            "Launch an eight-block-wide wall of squeaking bats with increased speed, damage and knockback. Click again to latch on or release. Fourteen waves with no cooldown.",
            "Fly in Bat Form and use the Sonic Witch star to send a sonic shockwave in your viewing direction, even in open air. It bursts on the first opponent or block without destroying terrain."),
        "wither_skeleton" to listOf(
            "Launches a skull that homes automatically and deals increased damage and knockback.",
            "Summons three persistent Wither Images. Click to cycle positions with them. All three must die before you summon another pack.",
            "Become a flying Wither. Attack to fire terrain-breaking skulls every half second. Your hotbar stays empty.",
            "Become an armored-looking Wither. Attack to fire two homing skulls every half second, with larger terrain explosions. Your hotbar stays empty."),
        "zombie" to listOf(
            "Charge your enchanted bow to fire a burst of arrows, with charge clicks. Ten volleys. Left-click still uses Death's Grasp.",
            "Hold to spew 250 redstone bile shots with normal spread and gravity, 20% more damage, lifesteal and no cooldown.",
            "For 27 seconds, two zombies emerge each second across the arena and near opponents. They do not burn in daylight.",
            "For 30 seconds, giants emerge across the arena, hunt opponents and break through obstacles. No blindness."),
        "cow" to listOf(
            "Your Stampede energy charges 50% faster for this life.",
            "Your herd becomes larger and follows enemies, with increased damage and knockback.",
            "Hold for a faster golden Milk Spiral with more damage and knockback. Fuel only drains while active.",
            "For 30 seconds, a layered milk dome extends ten blocks around you. Enemies closer to its center take more damage."),
        "horse" to listOf(
            "Shoot a Bone Kick projectile for 20% more damage and 50% more knockback.",
            "For this entire life, leave a bone mine every two seconds. Each stays for 20 seconds or explodes near an enemy.",
            "Hold for a surrounding Bone Rush with 20% faster travel and 75% more damage than the original rush. Bones can hit without the previous five-tick delay. Release to preserve fuel.",
            "Rain explosive bones over a 30-block radius around you. Bones explode when they hit an opponent or terrain."),
        "pig" to listOf(
            "Bouncy Bacon fires stronger carrots with no cooldown. Hits drop a golden carrot that is eaten automatically when collected, restoring health and energy with heart particles.",
            "Launch adult pig bombs with more damage and terrain destruction. Each uses the same energy as normal Bouncy Bacon.",
            "Launch larger bouncing bacon with more knockback, no cooldown and normal Bouncy Bacon energy cost. After bouncing, it homes toward enemies within ten blocks.",
            "Become an armored Pigman and refill your energy. Below 10 health, become a heavily armored Piglin Brute with double movement speed. Abilities use less energy."),
        "blaze" to listOf(
            "Hold to stream three normal Inferno embers per shot, with gravity and no energy cost. Each triple shot uses one of 120 charges.",
            "Hold a faster Firefly with more damage and knockback. Release to conserve fuel.",
            "Firefly regenerates your energy three times faster while active, even while using Inferno.",
            "Become a fast, freely steered Phoenix with no charge time, normal Firefly flames and sounds, with powerful contact damage."),
        "chicken" to listOf(
            "Temporarily hides your hotbar, gives unlimited flaps and automatically fires a constant stream of eggs.",
            "Gain unlimited flaps and launch three Chicken Missiles per second. Every hit launches an extra missile while active.",
            "Egg Blaster fires chicken spawn eggs with 50% larger visuals and hitboxes, and the normal 2.5-second cooldown. Only hits on enemies hatch baby chickens that chase and attack that target.",
            "Fire faster adult Chicken Missiles with stronger explosions and terrain damage. Durability is only lost when a missile misses."),
        "guardian" to listOf(
            "The normal guardian Target Laser reaches 15 blocks, marks an opponent and strengthens your attacks.",
            "Ten bursts of nine Whirlpool projectiles with normal gravity, water trails and stronger damage. Hits pull enemies toward you.",
            "Hold to jump and hover, then release to launch in your viewing direction. You can launch again in midair. Landing triggers Water Splash; twenty seconds of held fuel.",
            "Four guardians orbit an elder faster and faster for ten seconds. Monument blocks spread, static lasers hinder opponents, then the group explodes."),
        "sheep" to listOf(
            "Use Wooly Rocket with 50% more jump height and no recharge delay.",
            "A stronger Static Laser curves toward enemies instead of traveling straight.",
            "Launch Wool Mines quickly. They explode immediately when they land.",
            "Summons a rainbow flying baby sheep with 50 health per enemy. These Homing Sheeple bleat rapidly, navigate around obstacles and explode on contact for 19.2 damage, knockback and terrain destruction."),
        "villager" to listOf(
            "A stronger swirling Sonic Hurr reaches two blocks farther than the previous upgrade.",
            "A green firework grants a 30% bonus to your non-defensive stats for this life.",
            "Hold the Netherite hoe to fire twenty emerald, gold and iron projectiles per second with spread and no hit invulnerability. Each shot costs one durability.",
            "Combines the Villager professions into Perfection and gives you a magenta Cleric robe for this life.")
    )

    fun get(spec: SmashSpec): String = descriptions.getValue(spec.kit)[spec.tier.ordinal]
}

package dev.betrix.superSmashMobsBrawl.items

import org.bukkit.Material
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.random.Random

/** [block] and [pane] are the two things that open a rarity's row in the item menus: the block says which rarity it is. */
enum class ItemRarity(val label: String, val weight: Int, val color: NamedTextColor, val block: Material, val pane: Material) {
    COMMON("Common", 35, NamedTextColor.WHITE, Material.WHITE_CONCRETE, Material.WHITE_STAINED_GLASS_PANE),
    UNCOMMON("Uncommon", 25, NamedTextColor.GREEN, Material.LIME_CONCRETE, Material.LIME_STAINED_GLASS_PANE),
    RARE("Rare", 20, NamedTextColor.BLUE, Material.BLUE_CONCRETE, Material.BLUE_STAINED_GLASS_PANE),
    EPIC("Epic", 12, NamedTextColor.DARK_PURPLE, Material.MAGENTA_CONCRETE, Material.MAGENTA_STAINED_GLASS_PANE),
    LEGENDARY("Legendary", 6, NamedTextColor.GOLD, Material.YELLOW_CONCRETE, Material.YELLOW_STAINED_GLASS_PANE),
    ULTIMATE("Ultimate", 2, NamedTextColor.AQUA, Material.LIGHT_BLUE_CONCRETE, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
}

/**
 * Where everything sits in the item menus (Carl's Item Guide, Choose Items, the sandbox's Spawn
 * Items): one row for each rarity, opened by that rarity's block and a pane of glass, then its
 * items in the order they are listed in [ArenaItem]. The last slot of the chest goes back.
 */
object ItemMenuLayout {
    const val SIZE = 54
    const val BACK = 53
    /** Seven to a row, after the block and the glass. */
    const val PER_ROW = 7
    fun blockSlot(rarity: ItemRarity) = rarity.ordinal * 9
    fun paneSlot(rarity: ItemRarity) = rarity.ordinal * 9 + 1
    fun inRow(rarity: ItemRarity) = ArenaItem.entries.filter { it.rarity == rarity }
    fun slotOf(item: ArenaItem) = item.rarity.ordinal * 9 + 2 + inRow(item.rarity).indexOf(item)
}

/**
 * The flight of a Personal Rocket and the fall of an Anvil, with nothing of the server in them.
 */
object RocketRules {
    /** Blocks a tick while the rocket burns: about four times a sprint, the pace of a firework under an elytra pulled in a little for the size of the maps. */
    const val SPEED = 1.25
    /** How long it burns if it meets nothing. */
    const val BURN_TICKS = 40
    /** The blast where it hits: "pretty big", and smaller than the Rocket Launcher's ten blocks and 90%. */
    const val BLAST_RADIUS = 7.0
    const val BLAST_DAMAGE = 60.0
    const val BLAST_KNOCKBACK = 2.4
    /** How much of the map it takes with it: less than it hurts, so the rider is not left over a hole. */
    const val CRATER = 5.0
}

object AnvilRules {
    /** High enough to come out of the sky on any map, and down in half a second all the same. */
    const val HEIGHT = 30.0
    /**
     * Half a second. (It was 0.8 at first, and bots got out from under it at their ordinary pace:
     * 0.8 seconds is 4.5 blocks at a run.)
     */
    const val FALL_TICKS = 10
    /** Percent of a full health bar (90 at first). */
    const val DAMAGE = 85.0
    /**
     * What it takes out of the map where it lands: a dish as wide as the anvil and two blocks deep
     * (a ball of this radius, its middle [CRATER_LIFT] over the ground).
     */
    const val CRATER = 3.0
    const val CRATER_LIFT = 1.0
    /**
     * The anvil is drawn five blocks wide and lands on whoever is under it, out to its corners.
     * In the half second it takes, someone walking covers 2.2 blocks and someone running 2.8: both
     * are still under it. Only someone already running and jumping, 3.6 blocks, is just clear.
     */
    const val SCALE = 5.0
    const val REACH = 3.5
    /** How far the ring of dust thrown up by the landing runs, and for how many ticks. */
    const val SHOCK_REACH = 7.0
    const val SHOCK_TICKS = 8
    /** How high the anvil is over where it will land, [age] ticks after it appeared. */
    fun heightAt(age: Int) = HEIGHT * (1.0 - (age.coerceIn(0, FALL_TICKS) / FALL_TICKS.toDouble()))
    /**
     * Does an anvil land on someone ([dx], [dy], [dz]) from where it comes down? Whoever is under it
     * or at its edge, on the ground there or in the air over it: it came down through them.
     */
    fun lands(dx: Double, dy: Double, dz: Double) = dx * dx + dz * dz <= REACH * REACH && dy > -1.5 && dy < 6.0
}

/** The Flying Car: what the three keys do to it, how it gathers speed, and what its fuel is. */
object CarRules {
    /** Blocks a tick: forward at a little over three times a sprint (18 blocks a second), up and down at two thirds of that. */
    const val FORWARD = 0.9
    const val CLIMB = 0.6
    /**
     * How much of the way to the speed its keys ask for the car gets each tick. It gathers speed,
     * loses it and swings round over about a third of a second, in place of going from standing to
     * flat out, or from one heading to another, between one tick and the next.
     */
    const val EASE = 0.3
    /** How many ticks every player's game spreads each of the car's moves over, so that it glides instead of stepping. */
    const val GLIDE_TICKS = 2
    /**
     * What the car takes up for the purposes of not flying through things: as wide as a player
     * and a little shorter than one, so that wherever its driver could stand to get in, it fits.
     */
    const val HALF_WIDTH = 0.3
    const val HEIGHT = 1.4
    /** Seconds of flying in a full tank. */
    const val FUEL = 40
    /** Parked this long, the tank begins to fill again: a second of flying for every [REFUEL_TICKS] it stands. */
    const val REST_TICKS = 200
    const val REFUEL_TICKS = 80
    /**
     * The car's own noise, for as long as it is going anywhere: started again every [HUM_TICKS]
     * (the sound is longer than that, so there is no gap in it) and cut off when the car stops.
     * Slower than [HUM_SPEED] blocks a tick it is standing.
     */
    const val HUM_TICKS = 30
    const val HUM_SPEED = 0.05
    /** Is it time to start the car's noise again, [since] ticks after it was last started? (Straight away when it has just got going.) */
    fun hums(moving: Boolean, humming: Boolean, since: Long): Boolean = moving && (!humming || since >= HUM_TICKS)

    /** One tick of gathering speed: from [now] toward [wanted], both in blocks a tick. */
    fun eased(now: Double, wanted: Double): Double = (now + (wanted - now) * EASE).let { if (kotlin.math.abs(it) < 0.002 && wanted == 0.0) 0.0 else it }

    /** Does a car that has stood for [parkedTicks] get a second of fuel back this tick? */
    fun refuels(parkedTicks: Int): Boolean = parkedTicks > REST_TICKS && (parkedTicks - REST_TICKS) % REFUEL_TICKS == 0

    /** Seconds of fuel a car has got back after standing for [parkedTicks] (and not past [FUEL] with what it had). */
    fun refuelled(parkedTicks: Int): Int = ((parkedTicks - REST_TICKS).coerceAtLeast(0)) / REFUEL_TICKS
    /** Percent of a health bar for whoever it runs into, and no more often than once a second each. */
    const val RAM_DAMAGE = 25.0
    const val RAM_KNOCKBACK = 1.6
    const val RAM_EVERY_TICKS = 20
    /** The car is only dangerous while it is moving. */
    const val RAM_SPEED = 0.2
    /**
     * A bot at the wheel with something solid in the way: half a second of pressing on and moving
     * less than [STUCK_STEP] a tick, and it swerves up and to one side for [DODGE_TICKS], the other
     * side the next time; after [DODGES] of those it gives the chase up.
     */
    const val STUCK_STEP = 0.08
    const val STUCK_TICKS = 10
    const val DODGE_TICKS = 14
    const val DODGES = 4

    /**
     * Where the car goes this tick: forward the way the driver faces ([yaw], in degrees, level: looking
     * up or down does not climb), up for the jump key, down for the sneak key, and nowhere with no key held.
     */
    fun velocity(yaw: Float, forward: Boolean, up: Boolean, down: Boolean): Triple<Double, Double, Double> {
        val angle = Math.toRadians(yaw.toDouble())
        val along = if (forward) FORWARD else 0.0
        val rise = (if (up) CLIMB else 0.0) - (if (down) CLIMB else 0.0)
        return Triple(-kotlin.math.sin(angle) * along, rise, kotlin.math.cos(angle) * along)
    }
}

/** The Cage Trap: where the bars of the cage go around whoever stepped on it. */
object CageRules {
    const val HOLD_TICKS = 160
    /** Each bar as (dx, dy, dz) from the block the feet are in: four sides, two high, and a lid. */
    val BARS: List<Triple<Int, Int, Int>> = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).flatMap { (x, z) -> listOf(Triple(x, 0, z), Triple(x, 1, z)) } + Triple(0, 2, 0)
    /** Is someone at ([dx], [dy], [dz]) from the middle of the trapdoor standing on it? */
    fun steppedOn(dx: Double, dy: Double, dz: Double) = kotlin.math.abs(dx) <= 0.8 && kotlin.math.abs(dz) <= 0.8 && dy > -0.6 && dy < 1.2
}

/**
 * How a Shotbow volley flies, with nothing of the server in it. Each of its arrows is an arrow off
 * an ordinary bow: as fast as the bow was drawn, slowing a little, and falling. (They used to fly
 * dead level for ever, and never slower than 40% of full speed, so a bow barely drawn threw its
 * arrows as far as one drawn all the way.)
 */
object ShotbowRules {
    /** An ordinary bow's arrow leaves at three blocks a tick fully drawn, and in proportion below that. */
    const val FULL_SPEED = 3.0
    /** Percent of a full health bar a fully drawn volley takes; less in proportion, as an arrow's damage follows its speed. */
    const val FULL_DAMAGE = 6.25
    /** What every arrow loses to the air each tick, and to the ground. */
    const val DRAG = 0.99
    const val GRAVITY = 0.05
    /** A bow drawn less than this does not loose at all. */
    const val LEAST = 0.1

    /** How far a bow is drawn after being held for [ticks] ticks: 0 to 1, full after one second. */
    fun force(ticks: Int): Double { val drawn = ticks.coerceAtLeast(0) / 20.0; return minOf(1.0, (drawn * drawn + drawn * 2) / 3) }
    fun speed(force: Double) = FULL_SPEED * force.coerceIn(0.0, 1.0)
    fun damage(force: Double) = FULL_DAMAGE * force.coerceIn(0.0, 1.0)
    /** Only a fully drawn bow leaves the sparkling trail. */
    fun trail(force: Double) = force >= 1.0

    /** Blocks an arrow shot level has covered by the time it has fallen [fall] blocks (eye height, by default). */
    fun reach(force: Double, fall: Double = 1.62): Double {
        var along = 0.0; var down = 0.0; var forward = speed(force); var sink = 0.0
        while (down < fall && forward > 1e-6) {
            along += forward; down += sink
            forward *= DRAG; sink = sink * DRAG + GRAVITY
        }
        return along
    }
}

/**
 * The kinds of sound an item makes when it is thrown away with the drop key. (Which sound each one
 * is, is up to [ItemService]: this is only what the item is made of.)
 */
enum class DropFeel {
    /** A tool or weapon: the snap of one breaking. */
    TOOL,
    /** Something of metal: a clank. */
    METAL,
    /** Something to eat: a soft pop, nothing breaking. */
    FOOD,
    /** A bowl of something: poured out. */
    SPILL,
    /** A bottle, or ice: it shatters. */
    GLASS,
    /** Something with a fuse or a flame: put out. */
    FUSE,
    POWDER, SLIME, SNOW,
    /** A fishing line reeled in. */
    REEL,
    PLANT, WOOD, EGG, CLOTH, ENDER, GEM, BONE, PAPER, FISH, BAG,
    /** Anything else (a Mystery Ability's pumpkin or feather): the pop of an item leaving the hand. */
    PLAIN;

    companion object {
        /** For an item that is not in the catalogue, by what it is: the copper tools of a Mystery Ability, mostly. */
        fun of(material: String): DropFeel = when {
            Regex(".*_(SWORD|AXE|SHOVEL|PICKAXE|HOE)").matches(material) || material in setOf("BOW", "CROSSBOW", "TRIDENT", "MACE", "SHEARS") -> TOOL
            material == "FISHING_ROD" -> REEL
            material.endsWith("_BUNDLE") || material == "BUNDLE" -> BAG
            else -> PLAIN
        }
    }
}

/**
 * Every item, a rarity at a time and in the order its row of the item menus shows it
 * ([ItemMenuLayout]): seven to a rarity at most, six in the last one, which shares its row with
 * the way back.
 */
enum class ArenaItem(val label: String, val material: Material, val uses: Int,
                     val rarity: ItemRarity, val description: String) {
    // --- common
    FRAG_GRENADE("Frag Grenade", Material.FIRE_CHARGE, 1, ItemRarity.COMMON,
        "Right-click to throw. Explodes after two seconds for up to three hearts and breaks a few nearby blocks."),
    SPEED("Speed", Material.SUGAR, 1, ItemRarity.COMMON,
        "Right-click for 30 seconds of Speed II."),
    INSTANT_TELEPORTER("Instant Teleporter", Material.ENDER_EYE, 1, ItemRarity.COMMON,
        "Right-click to teleport up to 22 blocks in the direction you are looking. Stops before solid blocks."),
    CLOUD_IN_A_BOTTLE("Cloud in a Bottle", Material.EXPERIENCE_BOTTLE, 1, ItemRarity.COMMON,
        "Right-click to create a 5x5 platform beneath you. It begins fading after five seconds."),
    HOMING_SNOWBALL("Homing Snowball of Doom", Material.SNOWBALL, 1, ItemRarity.COMMON,
        "Right-click to throw a tracking snowball. Deals 15% health damage on contact."),
    PORKCHOP("Porkchop", Material.COOKED_PORKCHOP, 1, ItemRarity.COMMON,
        "Right-click to immediately restore 11% of your maximum health."),
    STEAK("Steak", Material.COOKED_BEEF, 1, ItemRarity.COMMON,
        "Right-click to immediately restore 12% of your maximum health."),

    // --- uncommon
    ICE_CUBE("Ice Cube", Material.ICE, 1, ItemRarity.UNCOMMON,
        "Right-click to throw. After three seconds, freezes everyone within four blocks for ten seconds: Slowness III and no double jump."),
    MONSTER_EGG("Monster Egg", Material.ZOMBIE_SPAWN_EGG, 1, ItemRarity.UNCOMMON,
        "Right-click to throw. After three seconds, summons your fighting clone with 20% health and no regeneration."),
    CLUSTER_GRENADE("Cluster Grenade", Material.SLIME_BALL, 1, ItemRarity.UNCOMMON,
        "Right-click to throw. After three seconds, splits into five TNT bombs that scatter and explode on landing."),
    JETPACK("Jetpack", Material.FLINT_AND_STEEL, 8, ItemRarity.UNCOMMON,
        "Right-click for a small upward boost."),
    POCKET_TRAMPOLINE("Pocket Trampoline", Material.SLIME_BLOCK, 1, ItemRarity.UNCOMMON,
        "Right-click to put a 3x3 slime pad under your feet and launch yourself high into the air. The pad fades after five seconds."),
    POISON_MIST("Poison Mist", Material.PUFFERFISH, 1, ItemRarity.UNCOMMON,
        "Right-click to throw. After three seconds it bursts into a mist that lingers for five seconds and gives everyone who touches it Poison II for six seconds."),
    CAGE_TRAP("Cage Trap", Material.OAK_TRAPDOOR, 1, ItemRarity.UNCOMMON,
        "Right-click to throw. Sets on the ground after three seconds, then cages whoever walks over it for eight seconds."),

    // --- rare
    SHOTBOW("The Shotbow", Material.BOW, 3, ItemRarity.RARE,
        "Draw and release to fire a cone of sixteen arrows. Each opponent takes damage once per volley. Three shots."),
    PROXIMITY_MINE("Proximity Mine", Material.OAK_PRESSURE_PLATE, 1, ItemRarity.RARE,
        "Right-click to throw. Arms on a full block after three seconds, then explodes when any player walks over it."),
    CRAFTY_BOMB("Crafty Bomb", Material.TNT, 1, ItemRarity.RARE,
        "Place TNT on a block. It grows into a 2x2 floor, then a 2x2x2 cube, and explodes in four directions for up to 55% health damage."),
    SINGULARITY_GRENADE("Singularity Grenade", Material.GUNPOWDER, 1, ItemRarity.RARE,
        "Right-click to throw. After three seconds, pulls in blocks and players through a portal charge, then blasts them outward."),
    REGENERATE("XP Fill", Material.EMERALD, 1, ItemRarity.RARE,
        "Right-click to refill your XP bar. Does not work for Magma Cube."),
    FIRST_AID_KIT("First Aid Kit", Material.PAPER, 1, ItemRarity.RARE,
        "Right-click to heal 15% of your missing health and gain Regeneration I for 20 seconds."),
    KNOCKBACK_ROD("Knockback Rod", Material.STICK, 8, ItemRarity.RARE,
        "Hit an opponent for strong knockback without damage (one use). Right-click to fire a knockback projectile (two uses)."),

    // --- epic
    DIAMOND_AXE("Axe Ability Upgrade", Material.DIAMOND_AXE, 4, ItemRarity.EPIC,
        "Upgrades four axe ability casts: 20% larger and faster projectiles, or 20% more movement and knockback. Adapts to a tool your kit can use."),
    DIAMOND_SHOVEL("Shovel Ability Upgrade", Material.DIAMOND_SHOVEL, 4, ItemRarity.EPIC,
        "Upgrades four shovel ability casts by 20%. Adapts to a tool your kit can use."),
    DIAMOND_SWORD("Sword Ability Upgrade", Material.DIAMOND_SWORD, 4, ItemRarity.EPIC,
        "Upgrades four sword ability casts by 20%. Adapts to a tool your kit can use."),
    DIAMOND_HELMET("Armor Upgrade", Material.DIAMOND_HELMET, 4, ItemRarity.EPIC,
        "Automatically equips on pickup. Adds 2.5 armor bars for four damaging hits, up to the normal armor cap. Another pickup refreshes the four hits."),
    FIRE_FLOWER("Fire Flower", Material.POPPY, 6, ItemRarity.EPIC,
        "Right-click to shoot a fireball that damages players and starts fires without destroying blocks."),
    SINGULARITY_NUKE("Singularity Nuke", Material.CAKE, 1, ItemRarity.EPIC,
        "A much larger singularity. Pulls in blocks and players through a portal charge, then throws them outward."),
    GILDED_APPLE("Gilded Apple", Material.GOLDEN_APPLE, 1, ItemRarity.EPIC,
        "Right-click to instantly eat for five absorption hearts. Stacks with other apples and Mooshroom absorption; does not heal your red hearts."),

    // --- legendary
    SHADOW_WALK("Shadow Walk", Material.SKELETON_SKULL, 1, ItemRarity.LEGENDARY,
        "Right-click to become invisible for 30 seconds, including your disguise."),
    PLAYER_GRAPPLE("Player Grapple", Material.FISHING_ROD, 3, ItemRarity.LEGENDARY,
        "Right-click to grapple an opponent and launch them far behind you."),
    MISSILE_BATTERY("Missile Battery", Material.GOLDEN_PICKAXE, 16, ItemRarity.LEGENDARY,
        "Right-click to launch a miniature rocket that damages opponents and destroys terrain. You take half damage from nearby explosions."),
    AIR_STRIKE("Air Strike", Material.BLAZE_ROD, 1, ItemRarity.LEGENDARY,
        "Throw a signal flare. After three seconds, lightning tears through the marked area, dealing up to 97.5% health damage."),
    AWESOME_SOUP("Awesome Soup", Material.MUSHROOM_STEW, 1, ItemRarity.LEGENDARY,
        "Right-click to immediately restore full health."),
    RAIN_STAFF("Rain Staff", Material.GOLDEN_HOE, 125, ItemRarity.LEGENDARY,
        "Hold right-click to rain projectiles where you aim. Contains 125 volleys of six mixed projectiles, each dealing 5% health damage."),
    MYSTERY_ABILITY("Mystery Ability", Material.ORANGE_BUNDLE, 1, ItemRarity.LEGENDARY,
        "Right-click for a random ability from another kit, as a copper tool with seven uses and half the usual cooldown."),

    // --- ultimate
    SMASHER("The Smasher", Material.GOLDEN_AXE, 1, ItemRarity.ULTIMATE,
        "Left-click to smash a large area of terrain, damaging and launching nearby opponents."),
    ROCKET_LAUNCHER("Rocket Launcher", Material.DIAMOND_PICKAXE, 1, ItemRarity.ULTIMATE,
        "Right-click to fire a powerful explosive rocket. Direct hits burn for another 25% health damage."),
    EXTRA_LIFE("Extra Life", Material.TOTEM_OF_UNDYING, 1, ItemRarity.ULTIMATE,
        "Right-click, then stand still or move slowly for five seconds to gain a life. Maximum eight lives. Moving quickly interrupts the channel."),
    PERSONAL_ROCKET("Personal Rocket", Material.FIREWORK_ROCKET, 4, ItemRarity.ULTIMATE,
        "Right-click to ride a rocket the way you are looking. It explodes on impact; the blast does not hurt you."),
    ANVIL("Falling Anvil", Material.ANVIL, 1, ItemRarity.ULTIMATE,
        "Right-click to drop an anvil out of the sky onto every opponent. It lands in half a second for 85% health damage and smashes the ground."),
    FLYING_CAR("Flying Car", Material.MINECART, CarRules.FUEL, ItemRarity.ULTIMATE,
        "Right-click: in or out. Ramming hurts.\nW drives, jump rises, sneak sinks.\nFuel regens after 10 seconds parked.");

    val id get() = name.lowercase()
    /**
     * The description as a tooltip shows it: broken into lines no tooltip is too wide for. (As one
     * line, the end of a long one ran off the side of the screen: the Flying Car's last sentence,
     * that it refuels, was never seen.) A line break in the description starts a new line.
     */
    val descriptionLines: List<String> get() = wrapped(description)
    val tool get() = this in setOf(DIAMOND_AXE, DIAMOND_SHOVEL, DIAMOND_SWORD)
    /** Something to eat. The foods are one find between them: see [roll]. */
    val food get() = healPercent > 0
    /** Percent of a full health bar a food gives back. */
    val healPercent get() = when (this) { PORKCHOP -> 11; STEAK -> 12; else -> 0 }

    /** What throwing this item away sounds like: the thing it is, not one sound for everything. */
    val dropFeel: DropFeel get() = when (this) {
        DIAMOND_AXE, DIAMOND_SHOVEL, DIAMOND_SWORD, SMASHER, SHOTBOW, ROCKET_LAUNCHER, MISSILE_BATTERY, RAIN_STAFF -> DropFeel.TOOL
        DIAMOND_HELMET, JETPACK, EXTRA_LIFE, ANVIL, FLYING_CAR -> DropFeel.METAL
        PORKCHOP, STEAK, GILDED_APPLE -> DropFeel.FOOD
        AWESOME_SOUP -> DropFeel.SPILL
        ICE_CUBE, CLOUD_IN_A_BOTTLE -> DropFeel.GLASS
        AIR_STRIKE, CRAFTY_BOMB, FRAG_GRENADE, PERSONAL_ROCKET -> DropFeel.FUSE
        SINGULARITY_GRENADE, SPEED -> DropFeel.POWDER
        CLUSTER_GRENADE, POCKET_TRAMPOLINE -> DropFeel.SLIME
        HOMING_SNOWBALL -> DropFeel.SNOW
        PLAYER_GRAPPLE -> DropFeel.REEL
        FIRE_FLOWER -> DropFeel.PLANT
        PROXIMITY_MINE, KNOCKBACK_ROD, CAGE_TRAP -> DropFeel.WOOD
        MONSTER_EGG -> DropFeel.EGG
        SINGULARITY_NUKE -> DropFeel.CLOTH
        INSTANT_TELEPORTER -> DropFeel.ENDER
        REGENERATE -> DropFeel.GEM
        SHADOW_WALK -> DropFeel.BONE
        FIRST_AID_KIT -> DropFeel.PAPER
        POISON_MIST -> DropFeel.FISH
        MYSTERY_ABILITY -> DropFeel.BAG
    }
    /** "4 uses", "1 use", "3 hits", or for the Flying Car what is left in its tank. */
    fun usesText(count: Int = uses) = when (this) {
        FLYING_CAR -> "$count ${if (count == 1) "second" else "seconds"} of fuel"
        DIAMOND_HELMET -> "$count ${if (count == 1) "hit" else "hits"}"
        else -> "$count ${if (count == 1) "use" else "uses"}"
    }

    companion object {
        /** The widest a line of a tooltip gets, in characters. */
        const val TOOLTIP_WIDTH = 42
        /** [text] in lines of at most [width] characters, broken between words and at every line break in it. */
        fun wrapped(text: String, width: Int = TOOLTIP_WIDTH): List<String> = text.split('\n').flatMap { paragraph ->
            val lines = mutableListOf<String>()
            var line = ""
            for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                if (line.isNotEmpty() && line.length + 1 + word.length > width) { lines += line; line = "" }
                line += (if (line.isEmpty()) "" else " ") + word
            }
            if (line.isNotEmpty()) lines += line
            lines
        }
        const val CATALOG_VERSION = 3
        /** How long Shadow Walk lasts. */
        const val SHADOW_WALK_TICKS = 600
        /** Percent of a full health bar the Homing Snowball takes (30 while it was a rare find). */
        const val SNOWBALL_DAMAGE = 15.0
        /** The items each catalogue version brought in. */
        private val addedIn get() = mapOf(1 to setOf(DIAMOND_HELMET), 2 to setOf(POCKET_TRAMPOLINE, POISON_MIST, MYSTERY_ABILITY),
            3 to setOf(CAGE_TRAP, PERSONAL_ROCKET, ANVIL, FLYING_CAR))
        /** What the single item "Food" of the first two versions is now. */
        val foods get() = entries.filter { it.food }

        /** Include new drops for previous Enable All selections without resetting custom lists. */
        fun restoreSelection(saved: String?, version: Int = 0): Set<ArenaItem> {
            if (saved == null) return entries.toSet()
            var enabled = saved.split('|').flatMap { id -> if (id == "food") foods else listOfNotNull(parse(id)) }.toSet()
            for (step in version + 1..CATALOG_VERSION) {
                // Everything that existed before this step was enabled: the new items join in.
                val existed = entries.toSet() - addedIn.filterKeys { it >= step }.values.flatten().toSet()
                if (enabled.containsAll(existed)) enabled = enabled + addedIn.getValue(step)
            }
            return enabled
        }

        fun parse(name: String): ArenaItem? {
            val key = name.lowercase().replace(Regex("[^a-z0-9]"), "").removePrefix("the")
            return entries.firstOrNull { it.id.replace("_", "") == key ||
                it.label.lowercase().replace(Regex("[^a-z0-9]"), "").removePrefix("the") == key }
        }

        /**
         * Roll a rarity once, then choose uniformly within that rarity. Disabled tiers renormalize.
         * The foods count as one thing to find between them, however many there are: more kinds of
         * food do not make food turn up more often.
         */
        fun roll(enabled: Set<ArenaItem>, random: Random = Random.Default): ArenaItem? {
            if (enabled.isEmpty()) return null
            val tiers = ItemRarity.entries.filter { tier -> enabled.any { it.rarity == tier } }
            var roll = random.nextInt(tiers.sumOf { it.weight })
            val tier = tiers.first { roll -= it.weight; roll < 0 }
            val (eaten, others) = entries.filter { it in enabled && it.rarity == tier }.partition { it.food }
            val finds = others.map { listOf(it) } + listOfNotNull(eaten.takeIf { it.isNotEmpty() })
            return finds.random(random).random(random)
        }
    }
}

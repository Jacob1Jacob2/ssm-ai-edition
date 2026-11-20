package dev.betrix.superSmashMobsBrawl.items

import org.bukkit.Material
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.random.Random

enum class ItemRarity(val label: String, val weight: Int, val color: NamedTextColor) {
    COMMON("Common", 35, NamedTextColor.WHITE),
    UNCOMMON("Uncommon", 25, NamedTextColor.GREEN),
    RARE("Rare", 20, NamedTextColor.BLUE),
    EPIC("Epic", 12, NamedTextColor.DARK_PURPLE),
    LEGENDARY("Legendary", 6, NamedTextColor.GOLD),
    ULTIMATE("Ultimate", 2, NamedTextColor.AQUA);
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

enum class ArenaItem(val label: String, val material: Material, val uses: Int,
                     val rarity: ItemRarity, val description: String) {
    DIAMOND_AXE("Axe Ability Upgrade", Material.DIAMOND_AXE, 4, ItemRarity.EPIC,
        "Upgrades four axe ability casts: 20% larger and faster projectiles, or 20% more movement and knockback. Adapts to a tool your kit can use."),
    DIAMOND_SHOVEL("Shovel Ability Upgrade", Material.DIAMOND_SHOVEL, 4, ItemRarity.EPIC,
        "Upgrades four shovel ability casts by 20%. Adapts to a tool your kit can use."),
    DIAMOND_SWORD("Sword Ability Upgrade", Material.DIAMOND_SWORD, 4, ItemRarity.EPIC,
        "Upgrades four sword ability casts by 20%. Adapts to a tool your kit can use."),
    DIAMOND_HELMET("Armor Upgrade", Material.DIAMOND_HELMET, 4, ItemRarity.EPIC,
        "Automatically equips on pickup. Adds 2.5 armor bars for four damaging hits, up to the normal armor cap. Another pickup refreshes the four hits."),
    SMASHER("The Smasher", Material.GOLDEN_AXE, 1, ItemRarity.ULTIMATE,
        "Left-click to smash a large area of terrain, damaging and launching nearby opponents."),
    HOMING_SNOWBALL("Homing Snowball of Doom", Material.SNOWBALL, 1, ItemRarity.RARE,
        "Right-click to throw a tracking snowball. Deals 30% health damage on contact."),
    SHOTBOW("The Shotbow", Material.BOW, 3, ItemRarity.RARE,
        "Draw and release to fire a cone of sixteen arrows. Each opponent takes damage once per volley. Three shots."),
    PLAYER_GRAPPLE("Player Grapple", Material.FISHING_ROD, 3, ItemRarity.LEGENDARY,
        "Right-click to grapple an opponent and launch them far behind you."),
    FIRE_FLOWER("Fire Flower", Material.POPPY, 6, ItemRarity.EPIC,
        "Right-click to shoot a fireball that damages players and starts fires without destroying blocks."),
    ROCKET_LAUNCHER("Rocket Launcher", Material.DIAMOND_PICKAXE, 1, ItemRarity.ULTIMATE,
        "Right-click to fire a powerful explosive rocket. Direct hits burn for another 25% health damage."),
    MISSILE_BATTERY("Missile Battery", Material.GOLDEN_PICKAXE, 16, ItemRarity.LEGENDARY,
        "Right-click to launch a miniature rocket that damages opponents and destroys terrain. You take half damage from nearby explosions."),
    AIR_STRIKE("Air Strike", Material.BLAZE_ROD, 1, ItemRarity.LEGENDARY,
        "Throw a signal flare. After three seconds, lightning tears through the marked area, dealing up to 97.5% health damage."),
    ICE_CUBE("Ice Cube", Material.ICE, 1, ItemRarity.UNCOMMON,
        "Right-click to throw. After three seconds, freezes everyone within four blocks for ten seconds: Slowness III and no double jump."),
    PROXIMITY_MINE("Proximity Mine", Material.OAK_PRESSURE_PLATE, 1, ItemRarity.RARE,
        "Right-click to throw. Arms on a full block after three seconds, then explodes when any player walks over it."),
    MONSTER_EGG("Monster Egg", Material.ZOMBIE_SPAWN_EGG, 1, ItemRarity.UNCOMMON,
        "Right-click to throw. After three seconds, summons your fighting clone with 20% health and no regeneration."),
    CRAFTY_BOMB("Crafty Bomb", Material.TNT, 1, ItemRarity.RARE,
        "Place TNT on a block. It grows into a 2x2 floor, then a 2x2x2 cube, and explodes in four directions for up to 55% health damage."),
    FRAG_GRENADE("Frag Grenade", Material.FIRE_CHARGE, 1, ItemRarity.COMMON,
        "Right-click to throw. Explodes after two seconds for up to three hearts and breaks a few nearby blocks."),
    CLUSTER_GRENADE("Cluster Grenade", Material.SLIME_BALL, 1, ItemRarity.UNCOMMON,
        "Right-click to throw. After three seconds, splits into five TNT bombs that scatter and explode on landing."),
    SINGULARITY_GRENADE("Singularity Grenade", Material.GUNPOWDER, 1, ItemRarity.RARE,
        "Right-click to throw. After three seconds, pulls in blocks and players through a portal charge, then blasts them outward."),
    SINGULARITY_NUKE("Singularity Nuke", Material.CAKE, 1, ItemRarity.EPIC,
        "A much larger singularity. Pulls in blocks and players through a portal charge, then throws them outward."),
    SPEED("Speed", Material.SUGAR, 1, ItemRarity.COMMON,
        "Right-click for 30 seconds of Speed II."),
    INSTANT_TELEPORTER("Instant Teleporter", Material.ENDER_EYE, 1, ItemRarity.COMMON,
        "Right-click to teleport up to 22 blocks in the direction you are looking. Stops before solid blocks."),
    CLOUD_IN_A_BOTTLE("Cloud in a Bottle", Material.EXPERIENCE_BOTTLE, 1, ItemRarity.COMMON,
        "Right-click to create a 5x5 platform beneath you. It begins fading after five seconds."),
    JETPACK("Jetpack", Material.FLINT_AND_STEEL, 8, ItemRarity.UNCOMMON,
        "Right-click for a small upward boost."),
    FOOD("Food", Material.APPLE, 1, ItemRarity.COMMON,
        "Right-click to immediately restore 10% of your maximum health."),
    AWESOME_SOUP("Awesome Soup", Material.MUSHROOM_STEW, 1, ItemRarity.LEGENDARY,
        "Right-click to immediately restore full health."),
    GILDED_APPLE("Gilded Apple", Material.GOLDEN_APPLE, 1, ItemRarity.EPIC,
        "Right-click to instantly eat for five absorption hearts. Stacks with other apples and Mooshroom absorption; does not heal your red hearts."),
    REGENERATE("XP Fill", Material.EMERALD, 1, ItemRarity.RARE,
        "Right-click to refill your XP bar. Does not work for Magma Cube."),
    EXTRA_LIFE("Extra Life", Material.TOTEM_OF_UNDYING, 1, ItemRarity.ULTIMATE,
        "Right-click, then stand still or move slowly for five seconds to gain a life. Maximum eight lives. Moving quickly interrupts the channel."),
    SHADOW_WALK("Shadow Walk", Material.SKELETON_SKULL, 1, ItemRarity.EPIC,
        "Right-click to become invisible for 15 seconds, including your disguise."),
    FIRST_AID_KIT("First Aid Kit", Material.PAPER, 1, ItemRarity.RARE,
        "Right-click to heal 15% of your missing health and gain Regeneration I for 20 seconds."),
    RAIN_STAFF("Rain Staff", Material.GOLDEN_HOE, 125, ItemRarity.LEGENDARY,
        "Hold right-click to rain projectiles where you aim. Contains 125 volleys of six mixed projectiles, each dealing 5% health damage."),
    KNOCKBACK_ROD("Knockback Rod", Material.STICK, 8, ItemRarity.RARE,
        "Hit an opponent for strong knockback without damage (one use). Right-click to fire a knockback projectile (two uses)."),
    POCKET_TRAMPOLINE("Pocket Trampoline", Material.SLIME_BLOCK, 1, ItemRarity.UNCOMMON,
        "Right-click to put a 3x3 slime pad under your feet and launch yourself high into the air. The pad fades after five seconds."),
    POISON_MIST("Poison Mist", Material.PUFFERFISH, 1, ItemRarity.UNCOMMON,
        "Right-click to throw. After three seconds it bursts into a mist that lingers for five seconds and gives everyone who touches it Poison II for six seconds."),
    MYSTERY_ABILITY("Mystery Ability", Material.ORANGE_BUNDLE, 1, ItemRarity.LEGENDARY,
        "Right-click for a random ability from another kit, as a copper tool with seven uses and half the usual cooldown.");

    val id get() = name.lowercase()
    val tool get() = this in setOf(DIAMOND_AXE, DIAMOND_SHOVEL, DIAMOND_SWORD)

    /** What throwing this item away sounds like: the thing it is, not one sound for everything. */
    val dropFeel: DropFeel get() = when (this) {
        DIAMOND_AXE, DIAMOND_SHOVEL, DIAMOND_SWORD, SMASHER, SHOTBOW, ROCKET_LAUNCHER, MISSILE_BATTERY, RAIN_STAFF -> DropFeel.TOOL
        DIAMOND_HELMET, JETPACK, EXTRA_LIFE -> DropFeel.METAL
        FOOD, GILDED_APPLE -> DropFeel.FOOD
        AWESOME_SOUP -> DropFeel.SPILL
        ICE_CUBE, CLOUD_IN_A_BOTTLE -> DropFeel.GLASS
        AIR_STRIKE, CRAFTY_BOMB, FRAG_GRENADE -> DropFeel.FUSE
        SINGULARITY_GRENADE, SPEED -> DropFeel.POWDER
        CLUSTER_GRENADE, POCKET_TRAMPOLINE -> DropFeel.SLIME
        HOMING_SNOWBALL -> DropFeel.SNOW
        PLAYER_GRAPPLE -> DropFeel.REEL
        FIRE_FLOWER -> DropFeel.PLANT
        PROXIMITY_MINE, KNOCKBACK_ROD -> DropFeel.WOOD
        MONSTER_EGG -> DropFeel.EGG
        SINGULARITY_NUKE -> DropFeel.CLOTH
        INSTANT_TELEPORTER -> DropFeel.ENDER
        REGENERATE -> DropFeel.GEM
        SHADOW_WALK -> DropFeel.BONE
        FIRST_AID_KIT -> DropFeel.PAPER
        POISON_MIST -> DropFeel.FISH
        MYSTERY_ABILITY -> DropFeel.BAG
    }
    val useLabel get() = if (this == DIAMOND_HELMET) "hits" else "uses"

    companion object {
        const val CATALOG_VERSION = 2
        /** The items each catalogue version brought in. */
        private val addedIn get() = mapOf(1 to setOf(DIAMOND_HELMET), 2 to setOf(POCKET_TRAMPOLINE, POISON_MIST, MYSTERY_ABILITY))

        /** Include new drops for previous Enable All selections without resetting custom lists. */
        fun restoreSelection(saved: String?, version: Int = 0): Set<ArenaItem> {
            if (saved == null) return entries.toSet()
            var enabled = saved.split('|').mapNotNull(::parse).toSet()
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

        /** Roll a rarity once, then choose uniformly within that rarity. Disabled tiers renormalize. */
        fun roll(enabled: Set<ArenaItem>, random: Random = Random.Default): ArenaItem? {
            if (enabled.isEmpty()) return null
            val tiers = ItemRarity.entries.filter { tier -> enabled.any { it.rarity == tier } }
            var roll = random.nextInt(tiers.sumOf { it.weight })
            val tier = tiers.first { roll -= it.weight; roll < 0 }
            return entries.filter { it in enabled && it.rarity == tier }.random(random)
        }
    }
}

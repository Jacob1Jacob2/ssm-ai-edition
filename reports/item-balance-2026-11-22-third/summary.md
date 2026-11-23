# Item balance report, third run: 22 November 2026

## What changed since the second run

Only how the bots play. No item and no kit was changed.

- **Getting out of the way:** a bot that sees or hears something about to go off (a grenade, an Air Strike flare, a singularity, a Crafty Bomb, a mine or a Cage Trap on the ground) walks out of it, its own throwables included.
- **Choosing between items:** it goes for the better of two pickups and swaps what it holds for something better it finds.
- **Kits it could not work:** an Enderman now picks up a block for Block Toss, and a Phantom is flown at its own speed and out of reach.
- **Kits that took their own player off the map:** Wither Skeleton, Cow, Magma Cube and Enderman each have an ability that carries its player further than the bots allowed for. They now end, aim or hold back those abilities the way a player does.
- **Knocked off the map:** a bot steers back to ground that is still there, not to where it last stood if that has been blown away.
- **Sight:** a bot goes by what it has seen and heard, and looks for somebody where it last saw them.
- **Head movement:** a look that eases onto its mark, not one that snaps.

Everything else is as in [the second report](../item-balance-2026-11-22/summary.md): 500 bot matches, 8 Expert bots, free-for-all, 4 lives, an item every 3 seconds, random kits, every map in turn. 250 at the real drop rates ([real-drops.md](real-drops.md)) and 250 with every item equally likely ([equal-drops.md](equal-drops.md)).

No match had to be stopped and there was not one error in the 500. The run was played in two sittings (the PC slept in between); the 8 matches that were under way when it was stopped are left out.

## What the changes did

### Bots dying by themselves

Deaths with nobody to blame fell from 352 of 15,456 (2.3%) to 198 of 15,457 (1.3%).

| Kit | Its deaths with nobody to blame, second run | Third run |
|---|---|---|
| Wither Skeleton | 70 of 683 (10.2%) | 14 of 636 (2.2%) |
| Cow | 37 of 664 (5.6%) | 3 of 605 (0.5%) |
| Magma Cube | 26 of 567 (4.6%) | 6 of 483 (1.2%) |
| Enderman | 21 of 651 (3.2%) | 3 of 606 (0.5%) |

Two more things that were costing lives:

- **Guided Wither Skull:** 31% of all the damage a Wither Skeleton's skulls did was to itself. Now 5%.
- **The Smasher:** its user died within 8 seconds, with nobody to blame, after 19% of uses. Now 8%. It still takes the floor from under its own user, so some of that is the item.

One kit went the other way: Horse, from 8 such deaths to 17. That may be chance; I have not looked into it.

### The kits, in bot hands

An even share of wins would be 12.5%.

| Kit | Won, second run | Won, third run | Damage dealt per match |
|---|---|---|---|
| Wither Skeleton | 3.5% | 20.6% | 476 to 591 |
| Enderman | 3.7% | 10.9% | 233 to 387 |
| Phantom | 1.2% | 4.8% | 228 to 374 |
| Magma Cube | 24.7% | 35.0% | 542 to 611 |
| Cow | 10.0% | 9.7% | 437 to 446 |

Wins are shared out, so other kits came down as these came up (Witch 23% to 16%, Creeper 18% to 10%, Iron Golem 31% to 26%). Their play did not change. This table is how the kits do when bots play them, not a list of things to change: the kits are as they were.

### Items on a fuse

| Item | Hit someone | Damage per pickup | Hurt its user |
|---|---|---|---|
| Frag Grenade | 12% to 6% | 2.1% to 1.1% | 1.1% to 0.3% |
| Cluster Grenade | 10% to 4% | 2.3% to 0.9% | 1.9% to 0.3% |
| Air Strike | 22% to 13% | 13.2% to 6.6% | 3.5% to 0.7% |
| Singularity Grenade | 30% to 24% | 20.2% to 15.5% | 5.6% to 4.3% |
| Singularity Nuke | 40% to 29% | 39.0% to 26.9% | 9.7% to 10.1% |
| Crafty Bomb | 32% to 18% | 16.7% to 9.6% | 3.0% to 2.5% |
| Proximity Mine | 27% to 15% | 18.5% to 10.3% | 5.4% to 2.3% |
| Poison Mist | 30% to 17% | not counted | none |

Everything with a fuse or a trigger hits about half as often against opponents who step out of it. What these items do to their own user fell too, except for the Singularity Nuke: it reaches 12 blocks and holds whoever is inside, and its user is as often caught in it as before.

**Rocket Launcher.** Bots now fire it only from outside its own blast (11 blocks and more). It hurts its user a third as much (7.3% to 2.5%) but lands less often (60% to 47%) and does less (63.7% to 45.7%). Its holder wins as often as before (24%).

### What gets picked up

| Tier | Picked up, second run | Third run |
|---|---|---|
| Ultimate | 67% | 80% |
| Legendary | 67% | 76% |
| Epic | 78% | 79% |
| Rare | 68% | 70% |
| Uncommon | 68% | 64% |
| Common | 69% | 59% |

Bots swapped a held item for a better one 1,169 times (7% of pickups; 175 times before). That is why "thrown away" is up for the cheap items in the tables below: Cloud in a Bottle and Instant Teleporter 23%, Knockback Rod 22%, Pocket Trampoline 19%.

### The matches

A match now lasts 2:23 on average, up from 2:04, with the same number of deaths. Fighters lose fewer lives to their own mistakes and to things they could step out of.

## Where the tiers stand now

Damage per pickup, in percent of a health bar, for the items in each tier that deal damage:

| Tier | Its attack items | Typical | Typical in the second run |
|---|---|---|---|
| Ultimate | 309, 148, 144, 97.0, 45.7 | 144 | 140 |
| Legendary | 59.8, 47.7, 6.6 | 48 | 48 |
| Epic | 28.8, 26.9 | 28 | 36 |
| Rare | 15.5, 13.9, 10.3, 9.6 | 12 | 18 |
| Uncommon | 5.0, 0.9 | 3 | 4 |
| Common | 6.0, 1.1 | 4 | 4 |

The tiers are still in order. What is out of step:

- **Falling Anvil (309%)** is twice its tier in an 8-player free-for-all, as before.
- **Rocket Launcher (45.7%)** is now under both Legendary attack items that work (Missile Battery 59.8%, Rain Staff 47.7%), and a third of what is typical for an Ultimate.
- **Air Strike (6.6%)** does what a Common item does. It hits somebody on one pickup in eight.
- **Frag Grenade and Cluster Grenade (about 1%)** do next to nothing against opponents who walk away from them.
- **Crafty Bomb and Proximity Mine (about 10%)** have fallen behind the other two Rare attack items (15.5% and 13.9%).

At the real drop rates items make 7% of the kills (535 of 7,711; 575 of 7,704 before), and no one item stands out: Falling Anvil 48, Singularity Grenade 44, Flying Car 42, Missile Battery 42, Fire Flower 39, Singularity Nuke 39, Personal Rocket 37, Knockback Rod 35.

## Who wins

The holder of an average item won 19% of the matches they had it in. Clearly above that, both halves together:

- **Shadow Walk:** +12.8 points, give or take 2.8. Its holder won 32%. It has been clearly ahead in all three reports (+17, +8, +13).
- **Extra Life:** +9.6, give or take 2.9.
- **Falling Anvil:** +8.1, give or take 2.9.
- **Personal Rocket:** +6.6, give or take 2.9.

Clearly below it: the Homing Snowball of Doom (-4.5, give or take 1.6).

**The Smasher** went from -4.3 (its holder won 16%) to +4.1 (24%) now that its user more often survives it. The Shotbow and the Instant Teleporter, clearly below average last time, are no longer clearly so.

## What I would change in the items, as it stands

These are your items, so this is only what the figures suggest.

1. **Shadow Walk:** the one item whose holder clearly wins more, three reports running. A shorter walk is the obvious change.
2. **Frag Grenade, Cluster Grenade and Air Strike:** a two or three second fuse is long enough to walk away from. A shorter fuse or a bigger blast, or accept them as items that clear a spot and do not hurt anybody.
3. **Rocket Launcher and Missile Battery:** still the wrong way round, and more so now. Fewer rockets in the battery (16 to 10), or more damage from the launcher.
4. **Singularity Nuke:** it costs its own user a tenth of a health bar per pickup, the same as before the bots learned to step away.
5. **Falling Anvil:** leave it if 4v4 is what matters (about 175% there by the same hit rate).
6. **Monster Egg (5%):** weak for an Uncommon, as in the second run.

## Every item, both halves together

Damage, healing and self-damage are in percent of a full health bar per pickup. "Holder won" is how often a fighter who held the item at any point won the match. Lift is the difference from the holder of an average item, in points, give or take the figure after it: a lift smaller than twice that figure is as likely chance as the item.

### Ultimate

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Falling Anvil | 252 | 97% | 308.6 | 160.7 | - | - | - | 28% | +8.1 ±2.9 |
| Personal Rocket | 242 | 92% | 144.4 | 126.0 | - | - | - | 26% | +6.6 ±2.9 |
| Flying Car | 258 | 96% | 148.3 | 126.0 | - | - | 1% | 19% | -0.7 ±2.5 |
| The Smasher | 259 | 85% | 97.0 | 79.9 | - | 1.1 | 3% | 24% | +4.1 ±2.7 |
| Rocket Launcher | 256 | 47% | 45.7 | 37.9 | 2.5 | - | 4% | 24% | +5.1 ±2.8 |
| Extra Life | 260 | - | - | - | - | - | 2% | 29% | +9.6 ±2.9 |

### Legendary

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Missile Battery | 307 | 87% | 59.8 | 49.5 | 1.6 | - | 6% | 19% | -0.5 ±2.3 |
| Rain Staff | 281 | 69% | 47.7 | 41.3 | - | - | 8% | 21% | +1.3 ±2.4 |
| Player Grapple | 315 | 73% | - | 36.2 | - | - | 8% | 20% | +0.6 ±2.3 |
| Air Strike | 309 | 13% | 6.6 | 5.2 | 0.7 | - | 5% | 20% | +0.5 ±2.3 |
| Awesome Soup | 270 | - | - | - | - | 52.8 | - | 25% | +5.1 ±2.7 |
| Mystery Ability | 293 | - | - | - | - | - | - | 20% | +0.2 ±2.4 |
| Shadow Walk | 299 | - | - | - | - | - | - | 32% | +12.8 ±2.8 |

### Epic

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Singularity Nuke | 353 | 29% | 26.9 | 26.1 | 10.1 | - | 8% | 20% | +0.1 ±2.2 |
| Fire Flower | 374 | 79% | 28.8 | 21.1 | - | - | 9% | 19% | -0.8 ±2.1 |
| Gilded Apple | 341 | - | - | - | - | 49.4 | - | 23% | +3.4 ±2.3 |
| Axe Ability Upgrade | 670 | - | - | - | - | - | - | 18% | -1.2 ±1.6 |
| Shovel Ability Upgrade | 258 | - | - | - | - | - | - | 24% | +4.1 ±2.7 |
| Sword Ability Upgrade | 289 | - | - | - | - | - | - | 18% | -1.0 ±2.4 |
| Armor Upgrade | 430 | - | - | - | - | - | - | 22% | +2.8 ±2.1 |

### Rare

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Singularity Grenade | 434 | 24% | 15.5 | 15.4 | 4.3 | - | 12% | 18% | -1.5 ±1.9 |
| Knockback Rod | 393 | 65% | - | 14.0 | - | - | 22% | 21% | +1.5 ±2.1 |
| The Shotbow | 474 | 82% | 13.9 | 11.6 | - | - | 12% | 17% | -2.6 ±1.8 |
| Proximity Mine | 439 | 15% | 10.3 | 7.3 | 2.3 | - | 8% | 19% | -0.2 ±2.0 |
| Crafty Bomb | 470 | 18% | 9.6 | 6.4 | 2.5 | - | 14% | 20% | +0.5 ±1.9 |
| First Aid Kit | 434 | - | - | - | - | 7.6 | - | 19% | 0.0 ±1.9 |
| XP Fill | 462 | - | - | - | - | - | 5% | 21% | +1.2 ±2.0 |

### Uncommon

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Monster Egg | 432 | 21% | 5.0 | 1.4 | - | - | 6% | 17% | -2.5 ±1.9 |
| Poison Mist | 432 | 17% | - | 0.7 | - | - | 8% | 18% | -1.7 ±1.9 |
| Cluster Grenade | 460 | 4% | 0.9 | 0.4 | 0.3 | - | 12% | 16% | -3.4 ±1.8 |
| Cage Trap | 447 | - | - | - | - | - | 8% | 17% | -2.6 ±1.8 |
| Pocket Trampoline | 443 | - | - | - | - | - | 19% | 18% | -1.3 ±1.9 |
| Ice Cube | 436 | - | - | - | - | - | 9% | 18% | -1.3 ±1.9 |
| Jetpack | 473 | - | - | - | - | - | 15% | 18% | -1.8 ±1.8 |

### Common

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Homing Snowball of Doom | 559 | 40% | 6.0 | 3.8 | - | - | 7% | 15% | -4.5 ±1.6 |
| Frag Grenade | 565 | 6% | 1.1 | 0.7 | 0.3 | - | 13% | 17% | -2.6 ±1.6 |
| Steak | 339 | - | - | - | - | 10.4 | - | 16% | -3.2 ±2.0 |
| Porkchop | 383 | - | - | - | - | 9.1 | - | 19% | -0.4 ±2.1 |
| Cloud in a Bottle | 603 | - | - | - | - | - | 23% | 17% | -2.0 ±1.6 |
| Instant Teleporter | 547 | - | - | - | - | - | 23% | 17% | -2.1 ±1.7 |
| Speed | 565 | - | - | - | - | - | - | 17% | -2.8 ±1.7 |

## How far to trust this

- **Bots played every fighter.** Each figure is the item in an Expert bot's hands, against Expert bots.
- **Expert bots step out of things quickly.** The slower difficulties notice later and react later, so the items on a fuse will do better against them than these figures say. That was not measured here.
- **Free-for-all with eight, not 4v4.** Items that reach every opponent count for more here than they would in a team match.
- **Not counted:** poison (the Poison Mist shows only that it reached someone on 17% of pickups), the damage of the tool upgrades, Armor Upgrade and Mystery Ability (it is counted as the kit's), and regeneration over time.
- **Few pickups at the real rates.** Each Ultimate was picked up 23 to 34 times in the real-rate half; their figures come almost entirely from the equal-drop half.
- **Several bot changes at once.** The comparison with the second run is all of them together. What each did by itself was measured separately on smaller runs, before it was installed.

## Files

- [real-drops.md](real-drops.md) and [real-drops.csv](real-drops.csv): the 250 matches at the real drop rates.
- [equal-drops.md](equal-drops.md) and [equal-drops.csv](equal-drops.csv): the 250 matches with every item equally likely.
- The raw records, every event of every match, are kept on the PC in `backups\balance-records-20261122-third` (not on GitHub).

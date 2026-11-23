# Item balance report, fourth run: 23 November 2026

## What changed since the third run

Only how the bots play. No item and no kit was changed.

- **A Creeper's hiss:** a bot that hears a Creeper winding up runs out of its blast and waits outside until it has gone off.
- **A Mooshroom's Spore Wall:** a bot goes round the end of the wall and plans its way round it, where it used to walk back into it.
- **Throws:** a bot steps across the path of something it sees coming, and comes in a zigzag at somebody who has been throwing things at it.
- **Summons:** a bot turns on a Wither Image or a Polar Bear's cub that is after it and kills it.
- **Opponents in the air:** a bot stays on an opponent who has been knocked off the ground, where it used to write them off and turn to somebody across the map.
- **Changed ground:** a bot is told at once when blocks are blown away or put back, and plans its way again.

Everything else is as in [the third report](../item-balance-2026-11-22-third/summary.md): 500 bot matches, 8 Expert bots, free-for-all, 4 lives, an item every 3 seconds, random kits, every map in turn. 250 at the real drop rates ([real-drops.md](real-drops.md)) and 250 with every item equally likely ([equal-drops.md](equal-drops.md)).

No match had to be stopped and there was not one error in the 500. The run was played in two parts: 423 matches in, Windows cleaned out the temp folder the test servers were running from and no new match could start. The servers were repaired and the remaining 77 matches played. The matches that could not start are not in the figures.

## What the changes did

### The abilities bots now answer

Hits and damage are for each fighter playing that kit, added up over a match. Damage is in percent of a health bar.

| Ability | Hits per match, third run | Fourth run | Damage per match, third run | Fourth run |
|---|---|---|---|---|
| Creeper: Explode | 5.0 | 4.4 | 79% | 53% |
| Mooshroom: Spore Wall | 11.7 | 7.4 | 132% | 84% |
| Wither Skeleton: Wither Image | 11.5 | 7.1 | 166% | 101% |
| Polar Bear: Cub Carrier | 13.2 | 3.6 | 113% | 31% |

Explode, the Spore Wall and the Wither Image do a third less than they did. The bear cubs do less than a third of what they did: a cub stays until somebody kills it, and nobody used to.

Stepping out of the way of throws and coming in on a zigzag did not show up anywhere. Over all the other abilities, hits and damage are where they were.

### The kits, in bot hands

An even share of wins would be 12.5%. "Average place" is where fighters of that kit finished on average, 1 being the last one standing and 8 the first one out.

| Kit | Won, third run | Won, fourth run | Average place |
|---|---|---|---|
| Wither Skeleton | 20.6% | 11.8% | 4.2 to 4.2 |
| Polar Bear | 10.8% | 7.5% | 4.7 to 5.0 |
| Mooshroom | 34.0% | 29.7% | 3.0 to 3.2 |
| Magma Cube | 35.0% | 30.2% | 2.9 to 3.2 |
| Creeper | 10.4% | 16.8% | 4.8 to 4.2 |
| Witch | 15.6% | 22.2% | 4.3 to 4.1 |
| Slime | 5.8% | 1.2% | 5.0 to 5.1 |

- **Wither Skeleton:** it reaches the last two as often as before (29% of the time) but wins the last fight much less. Its image used to go unanswered there.
- **Creeper:** Explode does a third less, and yet the Creeper finishes half a place higher and is in the last two 29% of the time (21% before). An opponent who backs off from the hiss also stops hitting the Creeper. Backing off is what a player does too, so this is the kit working as it was made to, but it is the one place where a bot change helped the kit it was aimed at.
- **Slime:** 2 wins in 169. Its average place and its damage did not move, so most of that is chance in who wins the last fight. It also died with nobody to blame more often (5 times to 12). I have not looked into it.
- **The rest:** wins are shared out, so other kits moved a few points as these four changed (Chicken 13% to 17.5%, Wolf 8% to 13%). Their play did not change.

This table is how the kits do when bots play them, not a list of things to change: the kits are as they were.

### Bots dying by themselves

Deaths with nobody to blame went from 198 of 15,457 (1.3%) to 172 of 15,467 (1.1%). Horse, which had 17 such deaths last time and looked like a problem, has 11 this time: that was chance.

### The matches

A match lasts 2:22 on average (2:23 before), with the same number of deaths.

### The items

Nothing about the items or how bots use them was changed, and most figures are where they were. What moved:

| Item | Hit someone | Damage per pickup | Hurt its user |
|---|---|---|---|
| Rocket Launcher | 47% to 41% | 45.7% to 33.8% | 2.5% to 2.0% |
| Singularity Grenade | 24% to 17% | 15.5% to 10.6% | 4.3% to 4.2% |
| Crafty Bomb | 18% to 15% | 9.6% to 7.2% | 2.5% to 2.1% |
| Missile Battery | 87% to 93% | 59.8% to 66.7% | 1.6% to 1.7% |
| Rain Staff | 69% to 75% | 47.7% to 54.5% | none |
| Singularity Nuke | 29% to 30% | 26.9% to 26.2% | 10.1% to 8.2% |

Bots also swapped a held item for a better one less often (811 times, 1,169 before) and used what they held more (27,912 uses, 26,350 before). "Thrown away" is down for most of the cheap items because of that: Knockback Rod 22% to 13%, Pocket Trampoline 19% to 10%, Singularity Grenade 12% to 5%. My reading is that a bot which stays on one opponent uses what it is holding sooner. That was not measured by itself.

## Where the tiers stand now

Damage per pickup, in percent of a health bar, for the items in each tier that deal damage:

| Tier | Its attack items | Typical | Typical in the third run |
|---|---|---|---|
| Ultimate | 296, 149, 136, 98.3, 33.8 | 136 | 144 |
| Legendary | 66.7, 54.5, 5.6 | 55 | 48 |
| Epic | 30.0, 26.2 | 28 | 28 |
| Rare | 15.8, 11.9, 10.6, 7.2 | 11 | 12 |
| Uncommon | 5.0, 1.0 | 3 | 3 |
| Common | 6.4, 1.0 | 4 | 4 |

The tiers are still in order. What is out of step is what was out of step last time, a little more so:

- **Falling Anvil (296%)** is twice its tier in an 8-player free-for-all.
- **Rocket Launcher (33.8%)** is now at half of the Missile Battery (66.7%), well under the Rain Staff (54.5%), and a quarter of what is typical for an Ultimate.
- **Air Strike (5.6%)** does what a Common item does. It hits somebody on one pickup in ten.
- **Frag Grenade and Cluster Grenade (1%)** do next to nothing against opponents who walk away from them.
- **Crafty Bomb (7.2%)** is the weakest Rare attack item by some way.

At the real drop rates items make 7% of the kills (526 of 7,696; 535 of 7,711 before), and no one item stands out: Rain Staff 56, Falling Anvil 52, Missile Battery 51, Flying Car 47, Knockback Rod 45, Personal Rocket 40, Shotbow 38, Singularity Nuke 36.

| Tier | Picked up, third run | Fourth run |
|---|---|---|
| Ultimate | 80% | 78% |
| Legendary | 76% | 77% |
| Epic | 79% | 80% |
| Rare | 70% | 68% |
| Uncommon | 64% | 63% |
| Common | 59% | 58% |

## Who wins

The holder of an average item won 19% of the matches they had it in. Clearly above that, both halves together:

- **Extra Life:** +9.7 points, give or take 3.0.
- **Shadow Walk:** +8.6, give or take 2.8. Its holder won 28%. It has been clearly ahead in all four reports (+17, +8, +13, +9).
- **Personal Rocket:** +6.9, give or take 3.0.
- **First Aid Kit:** +5.3, give or take 2.2. It was at 0.0 last time and heals 8% of a bar, so I take this for chance.

Clearly below it:

- **Frag Grenade:** -4.7, give or take 1.5.
- **XP Fill:** -5.2, give or take 1.8. It was at +1.2 last time, so I take this for chance too.

The Falling Anvil (+5.2) and The Smasher (+5.2) are above average but no longer clearly so. The Homing Snowball of Doom, clearly below last time, is not this time (-1.4).

## What I would change in the items, as it stands

These are your items, so this is only what the figures suggest. It is the same list as last time; nothing in this run speaks against any of it.

1. **Shadow Walk:** the one item whose holder clearly wins more, four reports running. A shorter walk is the obvious change.
2. **Frag Grenade, Cluster Grenade and Air Strike:** a two or three second fuse is long enough to walk away from. A shorter fuse or a bigger blast, or accept them as items that clear a spot and do not hurt anybody.
3. **Rocket Launcher and Missile Battery:** the wrong way round, and further apart each time (33.8% against 66.7%). Fewer rockets in the battery (16 to 10), or more damage from the launcher.
4. **Singularity Nuke:** it costs its own user 8% of a health bar per pickup.
5. **Falling Anvil:** leave it if 4v4 is what matters (about 170% there by the same hit rate).
6. **Crafty Bomb (7%):** now the weakest Rare attack item. Its lines are easy to stand between.

## Every item, both halves together

Damage, healing and self-damage are in percent of a full health bar per pickup. "Holder won" is how often a fighter who held the item at any point won the match. Lift is the difference from the holder of an average item, in points, give or take the figure after it: a lift smaller than twice that figure is as likely chance as the item.

### Ultimate

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Falling Anvil | 257 | 95% | 296.0 | 142.4 | - | - | - | 24% | +5.2 ±2.8 |
| Flying Car | 235 | 96% | 149.2 | 117.0 | - | - | 1% | 19% | -0.4 ±2.6 |
| Personal Rocket | 227 | 93% | 136.5 | 111.9 | - | - | - | 26% | +6.9 ±3.0 |
| The Smasher | 250 | 87% | 98.3 | 76.4 | - | 1.3 | - | 24% | +5.2 ±2.8 |
| Rocket Launcher | 248 | 41% | 33.8 | 24.2 | 2.0 | - | - | 21% | +2.2 ±2.6 |
| Extra Life | 245 | - | - | - | - | - | - | 29% | +9.7 ±3.0 |

### Legendary

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Missile Battery | 286 | 93% | 66.7 | 62.9 | 1.7 | - | - | 20% | +1.0 ±2.4 |
| Rain Staff | 284 | 75% | 54.5 | 49.3 | - | - | 2% | 23% | +3.5 ±2.5 |
| Player Grapple | 314 | 77% | - | 29.3 | - | - | 1% | 19% | +0.3 ±2.3 |
| Air Strike | 277 | 10% | 5.6 | 4.3 | 0.7 | - | - | 19% | -0.4 ±2.4 |
| Awesome Soup | 286 | - | - | - | - | 55.6 | - | 19% | -0.5 ±2.3 |
| Mystery Ability | 294 | - | - | - | - | - | - | 20% | +0.7 ±2.3 |
| Shadow Walk | 281 | - | - | - | - | - | - | 28% | +8.6 ±2.8 |

### Epic

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Singularity Nuke | 351 | 30% | 26.2 | 24.2 | 8.2 | - | 4% | 19% | -0.1 ±2.1 |
| Fire Flower | 337 | 81% | 30.0 | 22.0 | - | - | 3% | 16% | -2.7 ±2.0 |
| Gilded Apple | 363 | - | - | - | - | 49.6 | - | 22% | +3.2 ±2.2 |
| Axe Ability Upgrade | 671 | - | - | - | - | - | - | 21% | +1.6 ±1.7 |
| Shovel Ability Upgrade | 260 | - | - | - | - | - | - | 23% | +3.9 ±2.7 |
| Sword Ability Upgrade | 297 | - | - | - | - | - | - | 16% | -3.2 ±2.2 |
| Armor Upgrade | 398 | - | - | - | - | - | - | 23% | +4.2 ±2.2 |

### Rare

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Knockback Rod | 437 | 62% | - | 15.1 | - | - | 13% | 18% | -1.5 ±1.9 |
| The Shotbow | 428 | 88% | 15.8 | 14.0 | - | - | 8% | 19% | -0.4 ±1.9 |
| Singularity Grenade | 427 | 17% | 10.6 | 8.0 | 4.2 | - | 5% | 17% | -2.1 ±1.8 |
| Proximity Mine | 392 | 16% | 11.9 | 7.9 | 3.5 | - | 3% | 15% | -3.6 ±1.9 |
| Crafty Bomb | 457 | 15% | 7.2 | 3.5 | 2.1 | - | 9% | 19% | -0.1 ±1.9 |
| First Aid Kit | 386 | - | - | - | - | 7.7 | - | 24% | +5.3 ±2.2 |
| XP Fill | 374 | - | - | - | - | - | 3% | 14% | -5.2 ±1.8 |

### Uncommon

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Monster Egg | 458 | 19% | 5.0 | 1.7 | - | - | 6% | 21% | +2.4 ±2.0 |
| Cluster Grenade | 459 | 5% | 1.0 | 1.1 | 0.1 | - | 7% | 17% | -2.4 ±1.8 |
| Poison Mist | 443 | 17% | - | 0.5 | - | - | 6% | 19% | +0.3 ±1.9 |
| Cage Trap | 441 | - | - | - | - | - | 6% | 16% | -3.2 ±1.8 |
| Pocket Trampoline | 453 | - | - | - | - | - | 10% | 16% | -2.7 ±1.8 |
| Ice Cube | 434 | - | - | - | - | - | 5% | 16% | -2.7 ±1.8 |
| Jetpack | 471 | - | - | - | - | - | 16% | 18% | -0.8 ±1.8 |

### Common

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Homing Snowball of Doom | 614 | 43% | 6.4 | 2.8 | - | - | 6% | 18% | -1.4 ±1.6 |
| Frag Grenade | 593 | 6% | 1.0 | 0.3 | 0.3 | - | 8% | 14% | -4.7 ±1.5 |
| Steak | 346 | - | - | - | - | 9.9 | - | 20% | +1.1 ±2.2 |
| Porkchop | 346 | - | - | - | - | 9.3 | - | 16% | -2.9 ±2.0 |
| Cloud in a Bottle | 549 | - | - | - | - | - | 26% | 18% | -0.9 ±1.7 |
| Instant Teleporter | 547 | - | - | - | - | - | 25% | 17% | -2.3 ±1.7 |
| Speed | 547 | - | - | - | - | - | - | 18% | -1.4 ±1.7 |

## How far to trust this

- **Bots played every fighter.** Each figure is the item or the kit in an Expert bot's hands, against Expert bots.
- **Several bot changes at once.** The comparison with the third run is all six together. What each did by itself was measured separately on smaller runs before it was installed, and those agree with this: Explode a quarter less, the Spore Wall and the Wither Image a third less, the cubs three quarters less.
- **Why some item figures moved is not known.** The Rocket Launcher, the Singularity Grenade and the Crafty Bomb fell and two Legendary items rose with no change to any of them. Some of that will be chance (each rests on 250 to 450 pickups) and some the bots staying on one opponent; they were not told apart.
- **Free-for-all with eight, not 4v4.** Items that reach every opponent count for more here than they would in a team match.
- **Not counted:** poison (the Poison Mist shows only that it reached someone on 17% of pickups), the damage of the tool upgrades, Armor Upgrade and Mystery Ability (it is counted as the kit's), and regeneration over time.
- **Few pickups at the real rates.** Each Ultimate was picked up 29 to 46 times in the real-rate half; their figures come almost entirely from the equal-drop half.

## Files

- [real-drops.md](real-drops.md) and [real-drops.csv](real-drops.csv): the 250 matches at the real drop rates.
- [equal-drops.md](equal-drops.md) and [equal-drops.csv](equal-drops.csv): the 250 matches with every item equally likely.
- The raw records, every event of every match, are kept on the PC in `backups\balance-records-20261123-fourth` (not on GitHub).

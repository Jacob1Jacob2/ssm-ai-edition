# Item balance report, second run: 22 November 2026

## What changed since the first run

Only how the bots play four items. No item and no kit was changed.

- **Rain Staff:** pointed at the ground under an opponent and held, not pointed at the opponent and clicked.
- **Crafty Bomb:** set down beside whoever the bot is fighting, and then walked away from.
- **Monster Egg:** a clone that a bot sees coming, or that has just hit it, is hit back.
- **Shadow Walk:** somebody who vanishes is still swung at for about two seconds, and again whenever a hit gives them away.

Everything else is as in [the first report](../item-balance-2026-11-21/summary.md): 500 bot matches, 8 Expert bots, free-for-all, 4 lives, an item every 3 seconds, random kits, every map in turn. 250 at the real drop rates ([real-drops.md](real-drops.md)) and 250 with every item equally likely ([equal-drops.md](equal-drops.md)).

No match had to be stopped, and there was not one error in the 500 (the first run had about 25 in each half).

## What the four fixes did

Both runs together, before and after:

| Item | Damage per pickup | Kills per 100 pickups | Hit someone | Holder won |
|---|---|---|---|---|
| Rain Staff | 9.7% to 47.8% | 8.7 to 43.8 | 62% to 75% | 12% to 23% |
| Crafty Bomb | 6.1% to 16.7% | 3.7 to 14.1 | 12% to 32% | 16% to 16% |
| Monster Egg | 33.0% to 5.9% | 22.2 to 4.0 | 46% to 20% | 21% to 19% |
| Shadow Walk | none | none | none | 37% to 28% |

**Rain Staff.** Its weakness was all in how the bots used it. Held on the ground under somebody it is the second-best attack item in its tier (the Missile Battery does 66.5%), and its holder no longer wins less often than everybody else.

**Crafty Bomb.** Placed twice as often (0.32 uses per pickup to 0.66), and it now sits with the other Rare attack items, which all do 17 to 20%. Stepping clear of it afterwards took what it costs its own user from 7.8% of a health bar per use to 4.5%. One thing got worse: it is thrown away on 10% of pickups, up from 5%, because a bot gave up after four tries with no room to set it down. That is being changed to more patience.

**Monster Egg.** From 33% to 6%: bots now kill the clone, which takes one or two hits. This is the item as it is against anybody who answers it, and it is now on the weak side even for an Uncommon. Whether the clone should be tougher is yours to decide.

**Shadow Walk.** Its holder still wins more often than the holder of an average item, by 8 points instead of 17. Half of what it had was the bots forgetting whoever used it.

## Where the tiers stand now

Damage per pickup, in percent of a health bar, for the items in each tier that deal damage:

| Tier | Its attack items | Typical |
|---|---|---|
| Ultimate | 294, 146, 140, 102, 64 | 140 |
| Legendary | 66.5, 47.8, 13.2 | 48 |
| Epic | 39.0, 33.2 | 36 |
| Rare | 20.2, 18.5, 17.0, 16.7 | 18 |
| Uncommon | 5.9, 2.3 | 4 |
| Common | 6.6, 2.1 | 4 |

The tiers are in order now, and Rare is very even. What is still out of step:

- **Falling Anvil (294%)** is twice its tier in an 8-player free-for-all, where it has seven opponents to land on. With four, as in a 4v4, the same hit rate gives about 170%.
- **Air Strike (13%)** does a quarter of what the other two Legendary attack items do, and hits somebody on one pickup in five.
- **Rocket Launcher (64%)**, an Ultimate, is still under the Missile Battery (66.5%), a Legendary.
- **Cluster Grenade and Frag Grenade (2% each)** hit somebody one time in ten.
- **Uncommon attack items now do no more than Common ones.**

At the real drop rates items make 7.5% of the kills (575 of 7,704; 6% before), and no one item stands out: Falling Anvil 59, Personal Rocket 43, Singularity Nuke 42, Flying Car 42, Singularity Grenade 41, Missile Battery 40. In the first run the Monster Egg was first with 55.

## Who wins

Holding one item still rarely decides an 8-player match. The clearest, both runs together: Falling Anvil (+8.5, give or take 3.6), Shadow Walk (+8.1, give or take 2.9) and Extra Life (+6.7, give or take 3.4) above the average; The Shotbow (-5.0, give or take 1.8) and the Instant Teleporter (-4.1, give or take 1.6) below it. Of those only Shadow Walk was also clear in the first report. The Cage Trap's holder was well under the average again in the equal-drop half (-6.5, the same as in the first run) but level with it in the real-rate half (-0.8), so that one is still open.

## What this run does not show

It was started before the next round of bot changes, which are built but not yet installed: getting out of the way of anything about to go off, choosing between items, head movement, and playing Enderman and Phantom properly. The dodging will move the items on a fuse again, so the grenades, the Air Strike and the singularities should be judged from the run after this one, not from this one.

## What I would change in the items, as it stands

1. **Missile Battery and Rocket Launcher:** still the wrong way round. Fewer rockets in the battery (16 to 10), or a bigger blast on the launcher.
2. **Air Strike:** two seconds of warning instead of three.
3. **Monster Egg:** leave it, or give the clone more than a fifth of its owner's health. It is no longer too strong.
4. **Grenades and the Anvil:** wait for the next run (the grenades), and leave the Anvil at 85% for 4v4.

## Every item, both runs together

Damage, healing and self-damage are in percent of a full health bar per pickup. "Holder won" is how often a fighter who held the item at any point won the match; the holder of an average item won 19%. Lift is the difference in points, give or take the figure after it: a lift smaller than twice that figure is as likely chance as the item.

### Ultimate

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Falling Anvil | 170 | 96% | 294.0 | 167.1 | - | - | - | 29% | +8.5 ±3.6 |
| Flying Car | 207 | 92% | 140.3 | 127.5 | - | - | 1% | 20% | -1.1 ±2.7 |
| Personal Rocket | 193 | 92% | 146.0 | 122.3 | - | - | - | 27% | +5.3 ±3.2 |
| The Smasher | 215 | 87% | 102.2 | 86.0 | - | 1.2 | 1% | 16% | -4.3 ±2.5 |
| Rocket Launcher | 202 | 60% | 63.7 | 57.4 | 7.3 | - | 2% | 24% | +3.4 ±3.1 |
| Extra Life | 185 | - | - | - | - | - | 1% | 27% | +6.7 ±3.4 |

### Legendary

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Missile Battery | 230 | 94% | 66.5 | 63.0 | 1.8 | - | 3% | 20% | +0.1 ±2.7 |
| Rain Staff | 219 | 75% | 47.8 | 43.8 | - | - | 4% | 23% | +2.6 ±2.8 |
| Player Grapple | 229 | 85% | - | 38.9 | - | - | 4% | 21% | +0.1 ±2.7 |
| Air Strike | 207 | 22% | 13.2 | 13.0 | 3.5 | - | 2% | 16% | -3.8 ±2.6 |
| Awesome Soup | 206 | - | - | - | - | 52.6 | - | 20% | -0.3 ±2.8 |
| Shadow Walk | 244 | - | - | - | - | - | - | 28% | +8.1 ±2.9 |
| Mystery Ability | 233 | - | - | - | - | - | 1% | 18% | -2.4 ±2.5 |

### Epic

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Singularity Nuke | 295 | 40% | 39.0 | 34.9 | 9.7 | - | 2% | 16% | -4.3 ±2.2 |
| Fire Flower | 302 | 88% | 33.2 | 25.2 | - | - | 2% | 22% | +1.6 ±2.4 |
| Gilded Apple | 296 | - | - | - | - | 49.5 | - | 24% | +4.5 ±2.5 |
| Shovel Ability Upgrade | 253 | - | - | - | - | - | - | 25% | +4.8 ±2.8 |
| Sword Ability Upgrade | 249 | - | - | - | - | - | - | 15% | -4.3 ±2.4 |
| Armor Upgrade | 336 | - | - | - | - | - | - | 24% | +3.2 ±2.4 |
| Axe Ability Upgrade | 622 | - | - | - | - | - | - | 22% | +2.1 ±1.8 |

### Rare

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Singularity Grenade | 362 | 30% | 20.2 | 18.0 | 5.6 | - | 1% | 18% | -2.2 ±2.0 |
| The Shotbow | 388 | 92% | 17.0 | 14.7 | - | - | 3% | 15% | -5.0 ±1.8 |
| Knockback Rod | 400 | 68% | - | 14.3 | - | - | 10% | 18% | -1.6 ±2.0 |
| Crafty Bomb | 370 | 32% | 16.7 | 14.1 | 3.0 | - | 10% | 16% | -3.4 ±2.0 |
| Proximity Mine | 358 | 27% | 18.5 | 10.6 | 5.4 | - | 3% | 21% | +1.1 ±2.2 |
| First Aid Kit | 375 | - | - | - | - | 7.7 | - | 19% | -0.3 ±2.1 |
| XP Fill | 381 | - | - | - | - | - | 4% | 20% | +0.2 ±2.1 |

### Uncommon

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Monster Egg | 404 | 20% | 5.9 | 4.0 | - | - | 1% | 19% | -0.5 ±2.0 |
| Cluster Grenade | 389 | 10% | 2.3 | 2.3 | 1.9 | - | 3% | 16% | -3.7 ±1.9 |
| Poison Mist | 385 | 30% | - | 1.6 | - | - | 2% | 21% | +1.5 ±2.1 |
| Jetpack | 417 | - | - | - | - | - | - | 22% | +2.1 ±2.1 |
| Cage Trap | 434 | - | - | - | - | - | 2% | 16% | -3.6 ±1.8 |
| Pocket Trampoline | 393 | - | - | - | - | - | 3% | 24% | +4.4 ±2.2 |
| Ice Cube | 427 | - | - | - | - | - | 1% | 16% | -3.5 ±1.8 |

### Common

| Item | Pickups | Hit someone | Damage | Kills per 100 | Hurt its user | Healed | Thrown away | Holder won | Lift |
|---|---|---|---|---|---|---|---|---|---|
| Homing Snowball of Doom | 560 | 44% | 6.6 | 4.5 | - | - | 1% | 19% | -0.3 ±1.8 |
| Frag Grenade | 547 | 12% | 2.1 | 2.2 | 1.1 | - | 2% | 17% | -1.9 ±1.7 |
| Steak | 362 | - | - | - | - | 11.4 | - | 20% | -0.9 ±2.1 |
| Porkchop | 403 | - | - | - | - | 10.6 | - | 22% | +2.8 ±2.1 |
| Cloud in a Bottle | 605 | - | - | - | - | - | - | 18% | -1.6 ±1.6 |
| Speed | 635 | - | - | - | - | - | - | 18% | -1.3 ±1.6 |
| Instant Teleporter | 552 | - | - | - | - | - | 1% | 15% | -4.1 ±1.6 |

## How far to trust this

- **Bots played every fighter.** Each figure is the item in an Expert bot's hands, against Expert bots. Four items have now been checked for whether the bots use and answer them properly; the rest have not.
- **Free-for-all with eight, not 4v4.** Items that reach every opponent count for more here than they would in a team match.
- **Not counted:** poison (the Poison Mist shows only that it reached someone on 30% of pickups), the damage of the tool upgrades, Armor Upgrade and Mystery Ability (it is counted as the kit's), and regeneration over time.
- **Few pickups at the real rates.** Each Ultimate was picked up 15 to 30 times in the real-rate run; their figures come almost entirely from the equal-drop run.

## Files

- [real-drops.md](real-drops.md) and [real-drops.csv](real-drops.csv): the 250 matches at the real drop rates.
- [equal-drops.md](equal-drops.md) and [equal-drops.csv](equal-drops.csv): the 250 matches with every item equally likely.
- The raw records, every event of every match, are kept on the PC in `backups\balance-records-20261122` (not on GitHub).

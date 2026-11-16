package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.events.*
import dev.betrix.superSmashMobsBrawl.minigames.*
import dev.betrix.superSmashMobsBrawl.services.*
import gg.flyte.twilight.event.event
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.EventPriority
import org.bukkit.event.player.*
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

object SmashService : KoinComponent {
    private val plugin:SuperSmashMobsBrawl by inject()
    private val games:MinigameService by inject()
    private val arenas=mutableMapOf<BrawlMinigame,SmashArena>()
    private val sessions=mutableMapOf<UUID,SmashSession>()
    private val previewDrops=mutableMapOf<UUID,CrystalDrop>()
    fun session(player:Player)=sessions[player.uniqueId]
    fun permitsStorm(world:World)=arenas.values.any {it.world==world && it.hasStorm}
    fun existingArena(world:World)=arenas.values.firstOrNull {it.world==world}
    fun arena(game:BrawlMinigame)=arenas.getOrPut(game) { SmashArena(game) }
    fun has(player:Player,key:String)=session(player)?.has(key)==true
    fun ownsXp(player:Player)=session(player)?.powers?.values?.any {it.spec.fuelSeconds>0 || it.spec.key in setOf("enderman:0","zombie:0","polar_bear:3") || (it.spec.key=="slime:1" && it.active)}==true
    fun protectsCrystal(block:org.bukkit.block.Block)=existingArena(block.world)?.protectsCrystal(block)==true || previewDrops[block.world.uid]?.protects(block)==true
    fun hasStar(player:Player)=player.inventory.contents.any { it?.itemMeta?.persistentDataContainer?.has(SmashItems.STAR,PersistentDataType.INTEGER)==true }
    fun giveStar(player:Player,tier:SmashTier):Boolean {
        if (hasStar(player) || session(player)?.form()!=null) return false
        val slot=if(player.inventory.getItem(8)==null) 8 else player.inventory.firstEmpty()
        if(slot<0)return false
        player.inventory.setItem(slot,SmashItems.star(tier,KitService.getKitForPlayer(player)?.id))
        SmashFeedback.collected(player,tier,games.getMinigameForPlayer(player)?.allPlayers()?.mapNotNull { it.player } ?: listOf(player))
        return true
    }
    /** Admin item command replaces an existing unspent crystal, without removing kit weapons. */
    fun giveTestStar(player:Player,tier:SmashTier):Boolean {
        val slot=player.inventory.contents.indexOfFirst {it?.itemMeta?.persistentDataContainer?.has(SmashItems.STAR,PersistentDataType.INTEGER)==true}
        if(slot<0)return giveStar(player,tier)
        player.inventory.setItem(slot,SmashItems.star(tier,KitService.getKitForPlayer(player)?.id))
        return true
    }
    fun spawnCrystal(player:Player,tier:SmashTier):String {
        val game=games.getMinigameForPlayer(player)
        if(game!=null && game.getState()!=MinigameState.IN_PROGRESS)return "Wait until the match has started."
        if(game==null && !HubService.isPlayerInHub(player))return "Use this command in the lobby or a match."
        if(game != null) {
            if(game.worldManager.getWorld()!!.data.crystalSpawnPoints.isEmpty()) return "This arena has no yellow-wool Smash Crystal markers."
            return if(arena(game).spawnDrop(tier)) ""
                else "A crystal is already descending or waiting to be collected here."
        }
        val target=player.getTargetBlockExact(32)
        val at=target?.location?.add(0.5,1.0,0.5) ?: player.location.block.location.add(0.5,0.0,0.5)
        var searched=0
        while(at.y>at.world.minHeight+2 && at.clone().subtract(0.0,1.0,0.0).block.isPassable && searched++<64)at.subtract(0.0,1.0,0.0)
        if(at.clone().subtract(0.0,1.0,0.0).block.isPassable || at.y>=at.world.maxHeight-2)return "Look at a solid platform within 32 blocks first."
        val created=run {
            if(previewDrops.containsKey(player.world.uid))false else {
                val world=player.world
                previewDrops[world.uid]=CrystalDrop(world,at,tier,{world.players.filter {it.gameMode!=GameMode.SPECTATOR}},false)
                true
            }
        }
        return if(created) "" else "A crystal is already descending or waiting to be collected here."
    }
    fun activate(player:Player,tier:SmashTier):Boolean {
        val game=games.getMinigameForPlayer(player) ?: return false
        if(game.getState()!=MinigameState.IN_PROGRESS || player.gameMode!=GameMode.SURVIVAL || game.respawnManager.isRespawning(player))return false
        val kit=KitService.getKitForPlayer(player) ?: return false
        if(SmashCatalog.specs.none { it.kit==kit.id })return false
        val session=sessions.getOrPut(player.uniqueId) { SmashSession(player,kit.id,arena(game)) }
        val spec=SmashCatalog.get(kit.id,tier)
        session.add(spec)
        if(dev.betrix.superSmashMobsBrawl.bots.BotManager.debug)plugin.logger.info("[smashdbg] ${player.name} activated ${spec.key} (${spec.name})")
        if(spec.kind==SmashKind.SUMMON)session.click(spec.key)
        dev.betrix.superSmashMobsBrawl.services.BotConversationService.crystal(player, tier.title)
        return true
    }
    fun clear(player:Player,restore:Boolean=false) {
        sessions.remove(player.uniqueId)?.close(restore)
        player.inventory.contents.forEachIndexed { slot,item ->
            if(item?.itemMeta?.persistentDataContainer?.has(SmashItems.STAR,PersistentDataType.INTEGER)==true)player.inventory.setItem(slot,null)
        }
    }
    /** For bots: the crystal [player] could still claim, as its pad and the ticks until it lands. */
    fun crystalFor(player:Player):Pair<Location,Int>? {
        if(hasStar(player) || session(player)?.form()!=null)return null
        val kit=KitService.getKitForPlayer(player) ?: return null
        if(SmashCatalog.specs.none { it.kit==kit.id })return null
        return games.getMinigameForPlayer(player)?.let { arenas[it]?.crystalLanding() }
    }
    fun botTick(player:Player) {
        games.getMinigameForPlayer(player)?.let { arenas[it]?.botStrikeCrystal(player) }
        val star=player.inventory.contents.withIndex().firstOrNull {it.value?.itemMeta?.persistentDataContainer?.has(SmashItems.STAR,PersistentDataType.INTEGER)==true}
        if(star!=null) {
            val tier=star.value!!.itemMeta.persistentDataContainer.get(SmashItems.STAR,PersistentDataType.INTEGER)!!
            player.inventory.setItem(star.index,null);activate(player,SmashTier.entries[tier])
        }
        val s=session(player) ?: return
        if(s.arena.tick%10L!=0L)return
        if(s.form()!=null) {s.clickForm();return}
        val power=s.powers.values.filter {it.spec.kind==SmashKind.TOOL || it.spec.kind==SmashKind.SUMMON}.randomOrNull()
        if(power!=null) {
            val slot=(0..8).firstOrNull {player.inventory.getItem(it)?.itemMeta?.persistentDataContainer?.get(SmashItems.POWER,PersistentDataType.STRING)==power.spec.key}
            if(slot!=null){player.inventory.heldItemSlot=slot;s.click(power.spec.key)}
        }
        if(s.has("spider:2"))player.isSneaking=s.enemies().any{it.location.distanceSquared(player.location)<225}
        if(s.has("enderman:0") && Math.random()<0.15)s.sneak()
    }
    fun setup() {
        event<io.papermc.paper.event.player.PrePlayerAttackEntityEvent>(priority=EventPriority.HIGHEST) {
            if(arenas.values.any { it.strikeCrystal(attacked,player) } || previewDrops.values.any { it.strike(attacked,player) }) isCancelled=true
        }
        event<PlayerInteractEvent>(priority=EventPriority.LOWEST) {
            if(hand!=null && hand!=EquipmentSlot.HAND)return@event
            val current=session(player)
            if(current?.form()!=null) {
                isCancelled=true
                if(action.isRightClick || (action.isLeftClick && current.kit=="wither_skeleton"))current.clickForm()
                if(action.isRightClick && current.kit in setOf("squid","witch")) {
                    setUseItemInHand(org.bukkit.event.Event.Result.ALLOW)
                    if(current.kit=="witch")Bukkit.getScheduler().runTask(plugin,Runnable {
                        if(player.isOnline) {(player as org.bukkit.craftbukkit.entity.CraftPlayer).handle.stopUsingItem();player.updateInventory()}
                    })
                }
                return@event
            }
            val held=item ?: return@event
            val star=held.itemMeta?.persistentDataContainer?.get(SmashItems.STAR,PersistentDataType.INTEGER)
            if(star!=null && action.isRightClick) {
                isCancelled=true
                if(star !in SmashTier.entries.indices)return@event
                // Remove the consumable before taking a clean inventory snapshot.
                player.inventory.setItemInMainHand(org.bukkit.inventory.ItemStack.empty())
                if(!activate(player,SmashTier.entries[star]))player.inventory.setItemInMainHand(held)
                return@event
            }
            val power=held.itemMeta?.persistentDataContainer?.get(SmashItems.POWER,PersistentDataType.STRING)
            if(power!=null) {
                setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
                setUseItemInHand(if(action.isRightClick && (held.type==Material.BOW || held.hasData(io.papermc.paper.datacomponent.DataComponentTypes.BLOCKS_ATTACKS))) org.bukkit.event.Event.Result.ALLOW else org.bukkit.event.Event.Result.DENY)
                if(power=="enderman:1" && action.isRightClick)current?.powers?.get(power)?.takeIf {!it.active}?.let {
                    it.blockData=clickedBlock?.takeIf {block->block.type.isSolid}?.blockData
                }
                session(player)?.click(power,action.isLeftClick)
                if(action.isRightClick && !SmashItems.heldControl(power)) {
                    Bukkit.getScheduler().runTask(plugin,Runnable {
                        if(player.isOnline && player.inventory.itemInMainHand.itemMeta?.persistentDataContainer?.get(SmashItems.POWER,PersistentDataType.STRING)==power) {
                            (player as org.bukkit.craftbukkit.entity.CraftPlayer).handle.stopUsingItem()
                            player.updateInventory()
                        }
                    })
                }
            } else if(action.isRightClick) session(player)?.clickForm()
        }
        event<PlayerItemDamageEvent>(priority=EventPriority.HIGHEST) {
            if(item.itemMeta?.persistentDataContainer?.has(SmashItems.POWER,PersistentDataType.STRING)==true)isCancelled=true
        }
        event<PlayerItemConsumeEvent>(priority=EventPriority.HIGHEST) {
            if(item.itemMeta?.persistentDataContainer?.has(SmashItems.FOOD,PersistentDataType.BYTE)!=true)return@event
            if(isCancelled)return@event
            player.health=(player.health+2).coerceAtMost(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value)
            player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION,80,0))
        }
        event<PlayerToggleSneakEvent> { if(isSneaking)session(player)?.sneak() }
        event<PlayerItemHeldEvent> { session(player)?.release() }
        event<org.bukkit.event.entity.EntityShootBowEvent>(priority=EventPriority.HIGHEST) {
            val p=entity as? Player ?: return@event
            val key=bow?.itemMeta?.persistentDataContainer?.get(SmashItems.POWER,PersistentDataType.STRING) ?: return@event
            val s=session(p) ?: return@event
            isCancelled=true;projectile.remove()
            if(key=="zombie:0")s.powers[key]?.let {s.moves.releaseBow(it)}
        }
        event<BrawlDeathEvent>(priority=EventPriority.MONITOR) { clear(player) }
        event<PlayerQuitEvent> { clear(player) }
        event<PlayerChangedWorldEvent> { clear(player) }
        event<org.bukkit.event.world.WorldUnloadEvent> { previewDrops.remove(world.uid)?.close() }
        event<org.bukkit.event.entity.EntityDamageByEntityEvent>(priority=EventPriority.HIGHEST) {
            val damageOwner=(damager as? org.bukkit.entity.ComplexEntityPart)?.parent ?: damager
            if(damageOwner.uniqueId in SmashVisuals.ids) {isCancelled=true;return@event}
            if(entity.uniqueId !in SmashSummons.owners)return@event
            isCancelled=true
            val attacker=damager as? Player ?: return@event
            BrawlDamageEvent(entity as LivingEntity,Damager.DamagerLivingEntity(attacker),KitService.getKitForPlayer(attacker)?.getMeleeDamage() ?: 4.0).callEvent()
        }
        event<org.bukkit.event.entity.EntityExplodeEvent>(priority=EventPriority.HIGHEST) {
            if(entity.uniqueId in SmashVisuals.ids)isCancelled=true
        }
        event<BrawlDamageEvent>(priority=EventPriority.HIGHEST) {
            val owner=SmashSummons.owners[victim.uniqueId] ?: return@event
            val attacker=(damager as? Damager.DamagerLivingEntity)?.livingEntity as? Player ?: return@event
            if(isCancelled || attacker !in owner.enemies() || victim.noDamageTicks>0)return@event
            appliedDamage=damage.coerceAtLeast(0.0)
            if(damage>=victim.health)victim.remove() else {victim.health-=appliedDamage;victim.noDamageTicks=10;victim.playHurtAnimation(0f)}
        }
        event<BrawlDamageEvent>(priority=EventPriority.LOW) {
            val victimPlayer=victim as? Player
            val attacker=(damager as? Damager.DamagerLivingEntity)?.livingEntity as? Player
            if(attacker!=null) {
                if(victimPlayer!=null && sessions[attacker.uniqueId]?.arena?.game?.arePlayersOnSameTeam(attacker,victimPlayer)==true && attacker!=victimPlayer) { isCancelled=true;return@event }
                damage*=session(attacker)?.damageMultiplier() ?: 1.0
            }
            if(victimPlayer!=null && session(victimPlayer)?.invulnerable()==true) {
                // Lightning Squid resists damage, while attacks still move it.
                if(attacker!=null && attacker!=victimPlayer) victimPlayer.velocity=victimPlayer.location.toVector().subtract(attacker.location.toVector()).normalize().multiply(0.8).setY(0.4)
                isCancelled=true
            }
        }
        event<BrawlDamageEvent>(priority=EventPriority.MONITOR) {
            if(appliedDamage>0) {
                val attacker=(damager as? Damager.DamagerLivingEntity)?.livingEntity as? Player
                if(attacker!=null)session(attacker)?.afterHit(appliedDamage)
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin,Runnable {
            previewDrops.entries.removeIf { (_,drop) -> if(!drop.step()){drop.close();true}else false }
            games.activeMinigames().filter { it.getState()==MinigameState.IN_PROGRESS && it.minigameDef.id=="super_smash_mobs" }.forEach { arena(it) }
            arenas.entries.toList().forEach { (game,arena) ->
                if(game.getState()!=MinigameState.IN_PROGRESS) {
                    sessions.values.filter { it.arena===arena }.toList().forEach { clear(it.player) }
                    arena.close();arenas.remove(game)
                } else arena.step()
            }
            sessions.values.toList().forEach { s ->
                if(!s.player.isOnline || s.player.gameMode!=GameMode.SURVIVAL || KitService.getKitForPlayer(s.player)?.id!=s.kit)clear(s.player)
                else s.step()
            }
        },1L,1L)
    }
    fun teardown() { previewDrops.values.forEach {it.close()};previewDrops.clear();sessions.values.toList().forEach { it.close(false) };sessions.clear();arenas.values.forEach { it.close() };arenas.clear() }
}

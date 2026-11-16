package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.extensions.doKnockback
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import net.kyori.adventure.text.Component
import java.util.UUID

class SmashPower(val spec:SmashSpec,val started:Long) {
    var uses=spec.uses
    var fuel=spec.fuelSeconds*20
    var nextUse=0L
    var active=false
    var age=0
    var counter=0
    var charged=0
    var pressedAt=-100L
    var position:Location?=null
    var direction:org.bukkit.util.Vector?=null
    var blockData:org.bukkit.block.data.BlockData?=null
    val summons=mutableListOf<org.bukkit.entity.LivingEntity>()
    fun expired(tick:Long)=(uses==0 && !(spec.key=="wither_skeleton:1" && summons.any {it.isValid}) && !(spec.key=="witch:2" && active)) || (spec.fuelSeconds>0 && fuel<=0) || (spec.seconds>0 && tick-started>=spec.seconds*20)
}

class SmashSession(val player:Player,val kit:String,val arena:SmashArena) {
    val powers=linkedMapOf<String,SmashPower>()
    private val original=Array<ItemStack?>(9) { player.inventory.getItem(it)?.clone() }
    private val maxHealth=player.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 20.0
    private val armorBase=player.getAttribute(Attribute.ARMOR)?.baseValue ?: 0.0
    private val scale=player.getAttribute(Attribute.SCALE)?.baseValue ?: 1.0
    private val walkSpeed=player.walkSpeed
    private val flight=player.allowFlight
    private val flying=player.isFlying
    private val hits=mutableMapOf<UUID,Long>()
    private var formKey:String?=null
    private var closed=false
    private val savedFood=mutableListOf<ItemStack>()
    val alive get() = !closed
    var rage=false
    var drainedXp=0f
    val combat=SmashCombat(this)
    val moves=SmashMoves(this)
    fun has(key:String)=powers.containsKey(key)
    fun enemies()=arena.enemies(player)
    fun form()=powers.values.lastOrNull { it.spec.kind==SmashKind.FORM }
    fun add(spec:SmashSpec) {
        // One temporary transformation at a time; independent life-long upgrades survive it.
        if(spec.kind==SmashKind.FORM)powers.values.filter { it.spec.kind==SmashKind.FORM }.map { it.spec.key }.forEach { key -> powers.remove(key)?.let { moves.end(it) } }
        powers.remove(spec.key)?.let {old -> moves.end(old);old.summons.forEach {it.remove()} }
        val power=SmashPower(spec,arena.tick);powers[spec.key]=power
        moves.begin(power)
        rebuild()
        SmashFeedback.activated(player,spec,arena.game.allPlayers().mapNotNull { it.player })
    }
    fun rebuild() {
        (player as org.bukkit.craftbukkit.entity.CraftPlayer).handle.stopUsingItem()
        (KitService.getKitForPlayer(player)?.getPassive("barrage") as? dev.betrix.superSmashMobsBrawl.passives.BarragePassive)?.cancelCharge()
        val held=player.inventory.heldItemSlot
        for(i in 0..8)player.inventory.getItem(i)?.takeIf {it.itemMeta?.persistentDataContainer?.has(SmashItems.FOOD,PersistentDataType.BYTE)==true}?.let {savedFood+=it.clone()}
        val star=player.inventory.contents.firstOrNull { it?.itemMeta?.persistentDataContainer?.has(SmashItems.STAR,PersistentDataType.INTEGER)==true }?.clone()
        for(i in 0..8)player.inventory.setItem(i,if(form()!=null)null else original[i]?.clone())
        if(form()!=null) {
            player.inventory.heldItemSlot=0
            // A usable star provides right-click-in-air and held-use packets for these
            // forms. Wither forms fire on attack and keep their hotbar empty.
            form()?.takeIf {it.spec.key in setOf("squid:3","witch:3")}?.let {p ->
                player.inventory.setItem(0,SmashItems.tool(p))
            }
        }
        else powers.values.filter { it.spec.kind==SmashKind.TOOL || it.spec.kind==SmashKind.SUMMON }.forEach { power ->
            val slot=if(power.spec.ability==null)8 else original.indexOfFirst { it?.itemMeta?.persistentDataContainer?.get(NamespacedKey("super-smash-mobs-brawl","abilityid"),PersistentDataType.STRING)==power.spec.ability }.let {
                if(it<0 && power.spec.material in setOf(Material.BOW,Material.CROSSBOW))original.indexOfFirst { item -> item?.type==Material.BOW }.coerceAtLeast(0) else it.coerceAtLeast(0)
            }
            player.inventory.setItem(slot,SmashItems.tool(power))
        }
        if(star!=null && form()==null) { val slot=(8 downTo 0).firstOrNull { player.inventory.getItem(it)==null } ?: player.inventory.firstEmpty();if(slot>=0)player.inventory.setItem(slot,star) }
        if(has("villager:3"))(KitService.getKitForPlayer(player)?.getAbility("villager_arts") as? dev.betrix.superSmashMobsBrawl.abilities.VillagerArtsAbility)?.perfection()
        if(form()==null) {
            player.inventory.heldItemSlot=held
            savedFood.toList().forEach { food -> val slot=player.inventory.firstEmpty();if(slot>=0){player.inventory.setItem(slot,food);savedFood.remove(food)} }
        }
    }
    fun refreshTool(power:SmashPower) {
        for(slot in 0..8) {
            val item=player.inventory.getItem(slot) ?: continue
            if(item.itemMeta?.persistentDataContainer?.get(SmashItems.POWER,PersistentDataType.STRING)!=power.spec.key)continue
            if(power.spec.uses>0) {
                item.setData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE,(power.spec.uses-power.uses).coerceIn(0,power.spec.uses-1))
                item.editMeta {it.lore(SmashItems.toolLore(power))}
            }
        }
    }
    fun click(key:String,left:Boolean=false) {
        val p=powers[key] ?: return
        if(left && key=="zombie:0") {
            KitService.getKitForPlayer(player)?.getAbility("deaths_grasp")?.activateFromSmash();return
        }
        if(left && key!="iron_golem:0")return
        if(p.expired(arena.tick))return
        if(arena.tick<p.nextUse && !(key=="witch:2" && p.active)) {
            if(moves.cooldown(p)>1)dev.betrix.superSmashMobsBrawl.services.CombatLogService.showRecharge(player,p.spec.name,
                1.0-(p.nextUse-arena.tick).toDouble()/moves.cooldown(p),(p.nextUse-arena.tick)/20.0)
            return
        }
        if(form()!=null && form()!==p)return
        if(!p.active)p.pressedAt=arena.tick
        if(moves.click(p,left)) {
            p.nextUse=arena.tick+moves.cooldown(p)
            if(p.spec.uses>0 && !p.active && key!="chicken:3") {p.uses--;refreshTool(p)}
        }
    }
    fun clickForm() {form()?.let {click(it.spec.key)} }
    fun sneak() {powers["enderman:0"]?.let {moves.blink(it)} }
    fun release() {powers.values.forEach {
        if(it.spec.key=="guardian:2")moves.elemental.releaseSplash()
        if(it.spec.key=="magma_cube:2")moves.wolfMagma.stopFlash(it,true)
        if(it.spec.key=="slime:1" && it.active)moves.releaseRocket(it)
        if(it.spec.key=="enderman:1" && it.active)moves.releaseBlocks(it)
        if(it.active && it.spec.key=="snowman:1")it.uses--
        if(it.spec.fuelSeconds>0 || it.spec.key in setOf("blaze:0","snowman:1","squid:1","villager:2","goat:0","zombie:0","zombie:1","magma_cube:1")) {it.active=false;it.charged=0}
    } }
    fun step() {
        val expired=powers.values.filter {it.expired(arena.tick) && !(it.spec.seconds>0 && arena.tick-it.started==it.spec.seconds*20L)}
        expired.forEach { moves.end(it);powers.remove(it.spec.key) }
        if(expired.isNotEmpty())rebuild()
        powers.values.toList().forEach { p -> p.age++;moves.tick(p) }
        val completed=powers.values.filter {it.spec.seconds>0 && arena.tick-it.started>=it.spec.seconds*20L}
        completed.forEach {moves.end(it);powers.remove(it.spec.key)}
        if(completed.isNotEmpty())rebuild()
        val fuel=powers.values.lastOrNull {it.spec.fuelSeconds>0}
        if(fuel!=null)player.exp=(fuel.fuel.toFloat()/(fuel.spec.fuelSeconds*20)).coerceIn(0f,1f)
        powers["enderman:0"]?.let { player.exp=it.uses/10f }
        if(arena.tick%2L==0L)(form() ?: powers.values.lastOrNull {it.spec.seconds>0})?.let { power ->
            val remaining=(power.spec.seconds*20L-(arena.tick-power.started)).coerceAtLeast(0)
            val segments=(remaining*24/(power.spec.seconds*20L)).toInt().coerceIn(0,24)
            dev.betrix.superSmashMobsBrawl.services.EnergyHud.message(player,
                Component.text(power.spec.name+" ",net.kyori.adventure.text.format.NamedTextColor.WHITE)
                    .append(Component.text("\u258C".repeat(segments),net.kyori.adventure.text.format.NamedTextColor.GREEN))
                    .append(Component.text("\u258C".repeat(24-segments),net.kyori.adventure.text.format.NamedTextColor.RED))
                    .append(Component.text(" ${"%.1f".format(remaining/20.0)} Seconds",net.kyori.adventure.text.format.NamedTextColor.WHITE)))
        }
        val currentForm=form()?.spec?.key
        if(currentForm!=formKey) {
            if(formKey!=null) {player.isFlying=false;player.allowFlight=flight;SmashLooks.restore(player)}
            if(formKey=="slime:3") (player.disguise as? dev.betrix.superSmashMobsBrawl.disguises.SlimeDisguise)?.let {it.setSize(it.getSize())}
            formKey=currentForm
            currentForm?.let { SmashLooks.form(player,it) }
        }
        if(has("slime:2") && !has("slime:3")) {
            SmashLooks.slimeSize(player,(if(player.exp<=0.55)1 else if(player.exp<=0.8)2 else 4)*2)
            if(player.exp>=0.33)potion(PotionEffectType.JUMP_BOOST,(player.exp*3).toInt().coerceAtLeast(1)-1)
        }
        if(has("pig:3")) {
            SmashLooks.pig(player,player.health<10)
            player.getAttribute(Attribute.ARMOR)?.let {attribute ->
                val armor=(KitService.getKitForPlayer(player)?.getArmor() ?: 7.5)*2
                attribute.baseValue=(armor-(attribute.value-attribute.baseValue)).coerceAtLeast(0.0)
            }
        }
        if(has("villager:3"))SmashLooks.cleric(player)
        val xpPower=fuel ?: powers.values.lastOrNull {it.spec.key in setOf("wolf:0","enderman:0","slime:2","magma_cube:0","zombie:0","cow:0","pig:3","blaze:2","villager:1","polar_bear:3","phantom:1")}
        if(xpPower==null)SmashXpColor.clear(player)
        else if(xpPower.spec.key=="phantom:1")SmashXpColor.darkGreen(player)
        else SmashXpColor.set(player,xpPower.spec.tier)
    }
    fun holding(p:SmashPower):Boolean = player.inventory.itemInMainHand.itemMeta?.persistentDataContainer?.get(SmashItems.POWER,PersistentDataType.STRING)==p.spec.key &&
        (player.isHandRaised || arena.tick-p.pressedAt<=3 || (dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player) && p.active))
    fun consumeFuel(p:SmashPower):Boolean {if(p.fuel<=0)return false;p.fuel--;return true}
    fun hit(target:Player,damage:Double,kb:Double=1.0,vertical:Double?=null,source:String="Smash",origin:Location=player.location,hitInterval:Int=10):Boolean {
        if(!alive || target !in enemies() || arena.tick-(hits[target.uniqueId] ?: -100)<hitInterval)return false
        hits[target.uniqueId]=arena.tick
        val healthBefore=target.health
        val event=dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent(target,dev.betrix.superSmashMobsBrawl.events.Damager.DamagerLivingEntity(player),damage,kb,
            dev.betrix.superSmashMobsBrawl.events.BrawlDamageType.Projectile,source,false)
        if(hitInterval==0)target.noDamageTicks=0
        event.callEvent()
        if(hitInterval==0)target.noDamageTicks=0
        if(event.appliedDamage>0 && target in enemies()) {
            target.doKnockback(kb*(KitService.getKitForPlayer(player)?.getOutgoingKnockbackMultiplier() ?: 1.0),event.damage,healthBefore,origin.toVector(),null)
            if(vertical!=null)target.velocity=target.velocity.setY(vertical)
        }
        return event.appliedDamage>0
    }
    fun potion(type:PotionEffectType,amplifier:Int,ticks:Int=12) {player.addPotionEffect(PotionEffect(type,ticks,amplifier,false,false))}
    fun frenzySpeed(enabled:Boolean) {player.walkSpeed=if(enabled)(walkSpeed*1.6f).coerceAtMost(1f) else walkSpeed}
    fun pigmanSpeed(brute:Boolean) {player.walkSpeed=(walkSpeed*(if(brute)2f else 1f)).coerceAtMost(1f)}
    fun rageSpeed(enabled:Boolean) {player.walkSpeed=if(enabled)(walkSpeed*1.8f).coerceAtMost(1f) else walkSpeed}
    fun restoreHealthCap() {player.getAttribute(Attribute.MAX_HEALTH)?.baseValue=maxHealth;player.health=player.health.coerceAtMost(maxHealth)}
    fun damageMultiplier()=(if(has("villager:1"))1.3 else 1.0)
    fun armorBonus():Double=(if(has("enderman:2") && rage)3.0 else 0.0)+(if(has("slime:2"))4.0*player.exp else 0.0)
    fun meleeBonus():Double=(if(has("wolf:3"))9.0 else 0.0)+(if(has("enderman:2")&&rage)4.0 else 0.0)+(if(has("slime:2"))3.0*player.exp else 0.0)
    fun invulnerable()=has("squid:3") || powers.values.any {it.spec.key in setOf("squid:0","blaze:1","magma_cube:2") && it.active && holding(it)}
    fun cooldown(original:Double)=when {has("spider:3")->1.0;has("wolf:3")->original*0.2;else->original}
    fun rechargeMultiplier()=when {has("slime:1")->3f;has("cow:0")->1.5f;has("blaze:2")->3f;else->1f}
    fun afterHit(damage:Double) {
        if(!has("spider:3"))return
        val health=player.getAttribute(Attribute.MAX_HEALTH) ?: return
        health.baseValue=(health.baseValue+minOf(2.0,damage*0.25)).coerceAtMost(maxHealth+10.0)
        player.health=(player.health+damage*0.35).coerceAtMost(health.value)
    }
    fun close(restore:Boolean) {
        if(closed)return;closed=true
        (player as org.bukkit.craftbukkit.entity.CraftPlayer).handle.stopUsingItem()
        powers.values.forEach {moves.end(it);it.summons.toList().forEach {mob->mob.remove()}};powers.clear()
        (player.disguise as? dev.betrix.superSmashMobsBrawl.disguises.SlimeDisguise)?.let {it.setSize(it.getSize())}
        if(restore)for(i in 0..8)player.inventory.setItem(i,original[i])
        else player.inventory.contents.forEachIndexed {i,item->if(item?.itemMeta?.persistentDataContainer?.has(SmashItems.POWER,PersistentDataType.STRING)==true)player.inventory.setItem(i,null)}
        player.isFlying=false;player.allowFlight=flight;player.walkSpeed=walkSpeed
        player.getAttribute(Attribute.MAX_HEALTH)?.baseValue=maxHealth
        player.getAttribute(Attribute.SCALE)?.baseValue=scale
        player.getAttribute(Attribute.ARMOR)?.baseValue=armorBase
        player.health=player.health.coerceAtMost(maxHealth)
        savedFood.clear()
        player.resetPlayerTime();player.removePotionEffect(PotionEffectType.DARKNESS)
        SmashLooks.restore(player)
        SmashXpColor.clear(player)
    }
}

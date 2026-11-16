package dev.betrix.superSmashMobsBrawl.utils

import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

/** Interpolated flight followed by an actual rotating dropped item, shared by normal bacon and Smashes. */
class BaconFlight(
    private val owner:Player,raw:Material,private val cookedMaterial:Material,
    speed:Double=1.2,scale:Double=1.0,
    private val collision:(Location,Vector)->Pair<Location,LivingEntity?>?,
    private val hit:(LivingEntity,Location)->Boolean,
    private val eat:(Boolean)->Unit,
    private val track:(Entity)->Unit,
    private val homing:(Location)->Player?={null}
) {
    private var at=owner.eyeLocation.clone()
    private var velocity=at.direction.multiply(speed)
    private var age=0
    private var bouncedAt=-1
    private var cooked=false
    private var ground:Item?=null
    private val visual=at.world.spawn(at,ItemDisplay::class.java) {
        it.setItemStack(ItemStack.of(raw));it.itemDisplayTransform=ItemDisplay.ItemDisplayTransform.GROUND
        it.teleportDuration=2;it.interpolationDuration=2
        it.transformation=it.transformation.also {t->t.scale.mul(scale.toFloat())}
        it.isPersistent=false
    }.also(track)
    fun step():Boolean {
        if(age++>=160 || !owner.isOnline)return false
        ground?.let {at=it.location}
        if(bouncedAt>=0 && age-bouncedAt>=5 && owner.boundingBox.clone().expand(.7).contains(at.toVector())) {
            eat(cooked);return false
        }
        if(ground!=null)return ground!!.isValid
        if(bouncedAt>=0 && !cooked)homing(at)?.let {target ->
            val desired=target.boundingBox.center.subtract(at.toVector())
            if(desired.lengthSquared()>.001)velocity=velocity.multiply(.65).add(desired.normalize().multiply(.5))
        }
        val impact=collision(at,velocity)
        if(impact!=null && (bouncedAt<0 || (!cooked && impact.second!=null))) {
            at=impact.first.clone().subtract(velocity.clone().normalize().multiply(.2))
            cooked=impact.second?.let {hit(it,at)}==true
            if(cooked) {
                visual.setItemStack(ItemStack.of(cookedMaterial))
                HitFeedback.play(owner)
            }
            val toward=owner.location.toVector().subtract(at.toVector())
            velocity=if(toward.lengthSquared()<.001)Vector(0.0,.5,0.0) else toward.normalize().add(Vector(0.0,.4,0.0)).multiply(.5+.035*owner.location.distance(at))
            bouncedAt=age
            at.world.players.filter {it!=owner}.forEach {it.playSound(at,Sound.ENTITY_ITEM_PICKUP,1f,.5f)}
            owner.playSound(owner.location,Sound.ENTITY_ITEM_PICKUP,1f,.5f)
        } else if(impact!=null && impact.second==null && bouncedAt>=0) {
            at=impact.first.clone().subtract(velocity.clone().normalize().multiply(.12))
            ground=at.world.dropItem(at,visual.itemStack) {
                it.pickupDelay=Int.MAX_VALUE;it.setCanPlayerPickup(false);it.setCanMobPickup(false);it.velocity=Vector();it.isPersistent=false
            }.also(track)
            visual.remove();return true
        } else at.add(velocity)
        velocity.y-=.04
        visual.teleport(at)
        return true
    }
    fun close() {visual.remove();ground?.remove()}
}

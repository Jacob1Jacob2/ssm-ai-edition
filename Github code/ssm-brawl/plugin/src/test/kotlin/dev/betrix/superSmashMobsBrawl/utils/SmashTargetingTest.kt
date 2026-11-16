package dev.betrix.superSmashMobsBrawl.utils

import dev.betrix.superSmashMobsBrawl.smash.SmashTargeting
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector

class SmashTargetingTest:StringSpec({
    "fast homing arrows intersect a moving low target instead of orbiting above it" {
        var at=Vector(0.0,1.62,0.0)
        var velocity=Vector(0.0,0.0,3.2)
        var hit=false
        repeat(30) {tick ->
            val target=Vector(3.0+tick*.15,.4,12.0)
            velocity=SmashTargeting.steer(at,velocity,target,Vector(.15,0.0,0.0),3.2,.48)
            val box=BoundingBox.of(target,.6,.4,.6)
            if(box.contains(at) || box.rayTrace(at,velocity.clone().normalize(),velocity.length())!=null)hit=true
            at.add(velocity)
        }
        hit shouldBe true
    }
    "soft snowballs bend less sharply than strong snowballs" {
        val soft=SmashTargeting.steer(Vector(),Vector(0,0,2),Vector(12,1,12),Vector(),2.0,.12)
        val strong=SmashTargeting.steer(Vector(),Vector(0,0,2),Vector(12,1,12),Vector(),2.0,.55)
        (soft.x<strong.x) shouldBe true
        (kotlin.math.abs(soft.length()-2)<1e-9) shouldBe true
        (kotlin.math.abs(strong.length()-2)<1e-9) shouldBe true
    }
    "point blank and directly reversing targets never create invalid vectors" {
        listOf(Vector(),Vector(0,0,-1)).forEach {target ->
            val v=SmashTargeting.steer(Vector(),Vector(0,0,3),target,Vector(),3.0,.5)
            (v.x.isFinite() && v.y.isFinite() && v.z.isFinite()) shouldBe true
            (v.lengthSquared()>0) shouldBe true
        }
    }
})

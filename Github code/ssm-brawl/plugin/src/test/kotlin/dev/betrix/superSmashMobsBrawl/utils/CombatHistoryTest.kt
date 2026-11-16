package dev.betrix.superSmashMobsBrawl.utils

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CombatHistoryTest:StringSpec({
    val victim=UUID(0,1);val killer=UUID(0,2);val assistant=UUID(0,3);val second=UUID(0,4)
    val now=100_000L
    fun hit(id:UUID,amount:Double,label:String,ago:Long=0)=CombatHistory.Hit(id,id.toString(),amount,label,now-ago)
    "screenshot credits one opponent and retains self-damage with correct ability and order" {
        val s=CombatHistory.summarize(listOf(hit(victim,1.0,"Egg Blaster",6300),hit(assistant,10.0,"Sulphur Bomb",5400),hit(killer,18.0,"Static Laser")),victim,now)
        s.killer?.attackerId shouldBe killer;s.assists shouldBe 1
        s.rows.map {it.last.label to it.total} shouldBe listOf("Static Laser" to 18.0,"Sulphur Bomb" to 10.0,"Egg Blaster" to 1.0)
    }
    "two distinct other opponents count as two assists despite repeated hits" {
        val s=CombatHistory.summarize(listOf(hit(assistant,2.0,"Attack",3000),hit(second,2.0,"Attack",2000),hit(assistant,3.0,"Attack",1000),hit(killer,1.0,"Attack")),victim,now)
        s.assists shouldBe 2;s.rows.first {it.last.attackerId==assistant}.total shouldBe 5.0
    }
    "one player's different abilities are not mislabeled as one total" {
        val s=CombatHistory.summarize(listOf(hit(killer,10.0,"Sulphur Bomb",1000),hit(killer,2.0,"Attack")),victim,now)
        s.assists shouldBe 0;s.rows.map {it.last.label to it.total} shouldBe listOf("Attack" to 2.0,"Sulphur Bomb" to 10.0)
    }
    "final killer is first even when a larger earlier contribution or later self hit exists" {
        val s=CombatHistory.summarize(listOf(hit(assistant,40.0,"Attack",2000),hit(killer,2.0,"Needler",100),hit(victim,5.0,"Explode")),victim,now)
        s.killer?.attackerId shouldBe killer;s.assists shouldBe 1
        s.rows.map {it.last.attackerId} shouldBe listOf(killer,victim,assistant)
    }
    "recent zero damage knockback earns primary credit without fake damage or an assist" {
        val push=CombatHistory.Hit(killer,"Killer",0.0,"Player Grapple",now,true)
        val s=CombatHistory.summarize(listOf(hit(assistant,3.0,"Attack",1000),push),victim,now)
        s.killer shouldBe push;s.assists shouldBe 1;s.rows.first().total shouldBe 0.0
        CombatHistory.summarize(listOf(push.copy(attackerId=assistant,time=now-1000),hit(killer,2.0,"Attack")),victim,now).assists shouldBe 0
    }
    "self damage alone never awards a kill or assist" {
        val s=CombatHistory.summarize(listOf(hit(victim,20.0,"Egg Blaster")),victim,now)
        s.killer shouldBe null;s.assists shouldBe 0;s.rows.size shouldBe 1
    }
    "stale assists and kill credit expire at their existing boundaries" {
        val stale=hit(assistant,10.0,"Attack",15001)
        val s=CombatHistory.summarize(listOf(stale,hit(second,1.0,"Attack",15000),hit(killer,1.0,"Attack",10000)),victim,now)
        s.assists shouldBe 1;s.killer?.attackerId shouldBe killer;s.rows.size shouldBe 2
        CombatHistory.summarize(listOf(hit(killer,1.0,"Attack",10001)),victim,now).killer shouldBe null
    }
    "same millisecond final blow uses record order rather than largest hit" {
        CombatHistory.summarize(listOf(hit(assistant,18.0,"Attack"),hit(killer,1.0,"Attack")),victim,now).killer?.attackerId shouldBe killer
    }
    "all contributors are retained and invalid or unaccepted zero damage cannot earn credit" {
        val contributors=(10L..15L).map {hit(UUID(0,it),1.0,"Attack")}
        val s=CombatHistory.summarize(contributors+listOf(hit(victim,1.0,"Self"),hit(assistant,Double.NaN,"Invalid"),hit(assistant,0.0,"Rejected")),victim,now)
        s.rows.size shouldBe 7;s.assists shouldBe 5
    }
})

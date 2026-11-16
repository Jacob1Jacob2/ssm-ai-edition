package dev.betrix.superSmashMobsBrawl.utils

import dev.betrix.superSmashMobsBrawl.bots.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ArenaRouteTest : StringSpec({
    fun solve(search: ArenaRoute): List<RouteStep> {
        repeat(2000) { search.advance(1)?.let { return it } }
        error("search did not terminate")
    }
    "a barrier requires a safe detour rather than a direct line into the void" {
        val floor=(0..8).flatMap {x->(0..6).map {z->RouteNode(x,160*16,z)}}.toSet()
            .filterNot {it.x==4 && it.z<6}.toSet()
        val start=RouteNode(0,2560,0);val goal=RouteNode(8,2560,0)
        val search=ArenaRoute(start,goal,{a->listOf(1 to 0,-1 to 0,0 to 1,0 to -1).map { (x,z)->RouteNode(a.x+x,a.y16,a.z+z) }.filter {it in floor}.map {RouteEdge(it,false,1.0)}})
        val route=solve(search)
        search.reached shouldBe true
        route.last().node shouldBe goal
        route.all {it.node in floor} shouldBe true
        route.any {it.node.z==6} shouldBe true
        route.size shouldBe 20
    }
    "jump transitions and fractional surfaces survive route reconstruction" {
        val a=RouteNode(0,2560,0);val b=RouteNode(1,2568,0);val c=RouteNode(4,2576,0)
        val search=ArenaRoute(a,c,{when(it){a->listOf(RouteEdge(b,false,1.4));b->listOf(RouteEdge(c,true,6.4));else->emptyList()}})
        solve(search) shouldBe listOf(RouteStep(b,false),RouteStep(c,true))
    }
    "unreachable islands produce a bounded partial route" {
        val search=ArenaRoute(RouteNode(0,0,0),RouteNode(100,0,0),{a->if(a.x<5)listOf(RouteEdge(a.copy(x=a.x+1),false,1.0))else emptyList()})
        solve(search).last().node.x shouldBe 5
        search.reached shouldBe false
        search.finished shouldBe true
    }
    "per tick budgets do not expand the whole map" {
        val search=ArenaRoute(RouteNode(0,0,0),RouteNode(100,0,0),{a->listOf(RouteEdge(a.copy(x=a.x+1),false,1.0))},20)
        search.advance(3) shouldBe null
        search.expanded shouldBe 3
        solve(search).last().node.x shouldBe 19
        search.expanded shouldBe 20
    }
    "Phantom gets twenty seconds at takeoff altitude and doubles fuel cost each ten blocks" {
        NewKitMath.flightDrain(0.0)*400 shouldBe 1.0
        NewKitMath.flightDrain(-100.0) shouldBe NewKitMath.flightDrain(0.0)
        NewKitMath.flightDrain(10.0) shouldBe .005
        NewKitMath.flightDrain(20.0) shouldBe .01
        NewKitMath.flightDrain(1000.0) shouldBe .04
    }
    "a completed search skips steps already walked while it was running" {
        val search=ArenaRoute(RouteNode(0,160,0),RouteNode(8,160,0),{a->
            if(a.x<8)listOf(RouteEdge(a.copy(x=a.x+1),false,1.0))else emptyList()})
        val result=solve(search)
        search.remainingFrom(4.5,10.0,.5,result).map {it.node.x} shouldBe listOf(5,6,7,8)
        search.remainingFrom(.5,10.0,.5,result) shouldBe result
        search.remainingFrom(8.5,10.0,.5,result) shouldBe emptyList()
    }
    "knockback off the computed route requires a new route instead of walking backward through walls" {
        val search=ArenaRoute(RouteNode(0,160,0),RouteNode(8,160,0),{a->
            if(a.x<8)listOf(RouteEdge(a.copy(x=a.x+1),false,1.0))else emptyList()})
        val result=solve(search)
        search.remainingFrom(4.5,10.0,5.5,result) shouldBe emptyList()
        search.remainingFrom(4.5,15.0,.5,result) shouldBe emptyList()
    }
    "Goat range and Mooshroom absorption respect the requested limits" {
        NewKitMath.hurrDamage(0.0) shouldBe 1.0
        NewKitMath.hurrDamage(14.0) shouldBe 9.0
        NewKitMath.hurrDamage(30.0) shouldBe 9.0
        NewKitMath.hurrRadius(14.0)*2 shouldBe 2.35
        NewKitMath.absorption(0.0,10.0) shouldBe 3.0
        NewKitMath.absorption(5.0,10.0) shouldBe 6.0
        NewKitMath.absorption(11.0,10.0,12.0) shouldBe 12.0
    }
})

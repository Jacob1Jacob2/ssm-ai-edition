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
    "a wall climb keeps its mark through route reconstruction" {
        val foot=RouteNode(0,2560,0);val wall=RouteNode(1,2560,0);val roof=RouteNode(2,2560+17*16,0)
        val search=ArenaRoute(foot,roof,{when(it){
            foot->listOf(RouteEdge(wall,false,1.0))
            wall->listOf(RouteEdge(roof,false,16.6,climb=true))
            else->emptyList()}})
        solve(search) shouldBe listOf(RouteStep(wall,false),RouteStep(roof,false,climb=true))
        search.reached shouldBe true
    }
    "leaning toward the goal still gets round a wall, by a way not much longer than the shortest" {
        // A wide open floor with a long wall across it and one gap at the far end.
        val floor=(0..30).flatMap {x->(0..30).map {z->RouteNode(x,2560,z)}}.filterNot {it.x==15 && it.z<28}.toSet()
        val start=RouteNode(0,2560,0);val goal=RouteNode(30,2560,0)
        val search=ArenaRoute(start,goal,{a->listOf(1 to 0,-1 to 0,0 to 1,0 to -1).map { (x,z)->RouteNode(a.x+x,a.y16,a.z+z) }.filter {it in floor}.map {RouteEdge(it,false,1.0)}})
        val route=solve(search)
        search.reached shouldBe true
        route.all {it.node in floor} shouldBe true
        // The shortest way is 30 across plus 28 up and 28 back down the wall.
        (route.size in 86..(86*1.4).toInt()) shouldBe true
    }
    "a chicken and a spider are planned for as what they are, and everyone else by their double jump" {
        Mobility.of("chicken", false) shouldBe Mobility.FLIER
        Mobility.of("spider", false) shouldBe Mobility.CLIMBER
        Mobility.of("skeleton", true) shouldBe Mobility.JUMPER
        Mobility.of("skeleton", false) shouldBe Mobility.WALKER
        Mobility.of(null, true) shouldBe Mobility.JUMPER
        Mobility.WALKER.airborne shouldBe false
        listOf(Mobility.JUMPER, Mobility.FLIER, Mobility.CLIMBER).all { it.airborne } shouldBe true
        // Only the spider climbs, and a chicken out-flies every jump.
        listOf(Mobility.WALKER, Mobility.JUMPER, Mobility.FLIER).all { it.climb == 0.0 } shouldBe true
        (Mobility.CLIMBER.climb > 20 && Mobility.FLIER.up > Mobility.JUMPER.up * 3) shouldBe true
    }
    "a high ledge is taken straight up when the air jump alone covers the distance, and at a run when it does not" {
        // Right beside the take-off: always straight up, whatever the height.
        Mobility.straightUp(2.0, 1.0) shouldBe true
        Mobility.straightUp(4.5, 1.42) shouldBe true
        // Four up and four across is the most the air jump does on its own; five across needs the run.
        Mobility.straightUp(4.0, 4.0) shouldBe true
        Mobility.straightUp(4.0, 5.0) shouldBe false
        // Lower ledges leave more of the jump for distance.
        Mobility.straightUp(2.0, 5.0) shouldBe true
        Mobility.straightUp(2.0, 6.0) shouldBe false
        // A step or a hop is neither: it is just a jump.
        Mobility.straightUp(1.0, 1.0) shouldBe false
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

package dev.betrix.superSmashMobsBrawl.utils

import dev.betrix.superSmashMobsBrawl.models.SpawnPoint
import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import dev.betrix.superSmashMobsBrawl.services.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class MapTransformTest : StringSpec({
    val map=GameMapDef("fixture",64.0,-20.0,4,spawnPoints=listOf(
        SpawnPoint(-2.5,12.0,4.5,yaw=90f),SpawnPoint(7.5,16.0,8.5,yaw=-90f)),
        spectatorSpawnPoint=SpawnPoint(2.5,30.0,0.5),worldBorderCenterX=2.5)
    "left reflection replaces the right half, including its spawn height and facing" {
        val result=MapTransform(reflect=ReflectMode.LEFT).definition(map)
        result.spawnPoints shouldBe listOf(SpawnPoint(-2.5,12.0,4.5,yaw=90f),SpawnPoint(7.5,12.0,4.5,yaw=-90f))
    }
    "right reflection keeps the opposite terrain and spawn information" {
        val result=MapTransform(reflect=ReflectMode.RIGHT).definition(map)
        result.spawnPoints.toSet() shouldBe setOf(SpawnPoint(7.5,16.0,8.5,yaw=-90f),SpawnPoint(-2.5,16.0,8.5,yaw=90f))
    }
    "big cubes scale height as well as horizontal coordinates" {
        val t=MapTransform(scale=MapScale.BIG)
        t.position(SpawnPoint(-.5,103.0,2.5)) shouldBe SpawnPoint(-1.0,206.0,5.0)
        t.unscaledCell(-1) shouldBe -1
        t.unscaledCell(-2) shouldBe -1
        t.unscaledCell(-3) shouldBe -2
        t.definition(map).voidLevel shouldBe -40.0
    }
    "shrunk spawns stand above the reduced support cube, even at negative coordinates" {
        MapTransform(scale=MapScale.SMALL).position(SpawnPoint(-.5,103.0,2.5)) shouldBe SpawnPoint(-.5,52.0,1.5)
    }
    "reverse remains whole-map mirroring and composes with reflection and scale" {
        MapTransform(true,ReflectMode.LEFT,MapScale.BIG).definition(map).spawnPoints.first() shouldBe SpawnPoint(5.0,24.0,9.0,yaw=-90f)
    }
    "random resolves to both possibilities while fixed modes are deterministic" {
        val random=Random(24)
        (1..100).map{ReverseMode.RANDOM.resolve(random)}.toSet() shouldBe setOf(true,false)
        ReverseMode.OFF.resolve(random) shouldBe false
        ReverseMode.ON.resolve(random) shouldBe true
    }
})

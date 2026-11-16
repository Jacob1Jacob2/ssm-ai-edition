package dev.betrix.superSmashMobsBrawl.utils

import dev.betrix.superSmashMobsBrawl.smash.SmashAssets
import dev.betrix.superSmashMobsBrawl.services.EnergyHud
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import javax.imageio.ImageIO

class SmashAssetsTest : StringSpec({
    "crystal rarity adds detail inside unchanged bounds with atlas-ready native texture" {
        val files=mutableMapOf<String,ByteArray>();SmashAssets.addTo(files)
        val texture=ImageIO.read(files.getValue("assets/ssm/textures/block/crystal_native.png").inputStream())
        (texture.width / texture.height) shouldBe 2
        val counts=mutableListOf<Int>()
        for(name in listOf("crystal_cage_0","crystal_cage_1","crystal_cage_2","crystal_cage_3","crystal_core")) {
            val model=com.google.gson.JsonParser.parseString(files.getValue("assets/ssm/models/item/$name.json").decodeToString()).asJsonObject
            model.getAsJsonObject("textures").get("crystal").asString shouldBe "ssm:block/crystal_native"
            // The client rejects mixed item/block atlas faces, even when every PNG exists.
            model.getAsJsonObject("textures").entrySet().forEach { (_, value) ->
                value.asString.substringAfter(':').substringBefore('/') shouldBe "block"
            }
            val elements=model.getAsJsonArray("elements");counts+=elements.size()
            elements.forEach {e ->
                for(key in listOf("from","to")) e.asJsonObject.getAsJsonArray(key).forEach {v -> (v.asDouble in 0.0..16.0) shouldBe true}
            }
            files.containsKey("assets/ssm/items/$name.json") shouldBe true
        }
        counts shouldBe listOf(1,5,9,21,1)
    }
    "shining stars retain the vanilla silhouette on every frame without orbiting pixels" {
        val files=mutableMapOf<String,ByteArray>(); SmashAssets.addTo(files)
        val original=ImageIO.read(SmashAssets::class.java.getResourceAsStream("/pack/nether_star.png"))
        for(name in listOf("super","ultra")) {
            val image=ImageIO.read(files.getValue("assets/ssm/textures/item/smash_star_$name.png").inputStream())
            image.width shouldBe 16
            image.height shouldBe 16*48
            for(frame in 0..47) for(y in 0..15) for(x in 0..15)
                (image.getRGB(x,frame*16+y).ushr(24)>0) shouldBe (original.getRGB(x,y).ushr(24)>0)
        }
        files.getValue("assets/ssm/models/item/smash_crystal_0.json").decodeToString().contains("light_blue_stained_glass") shouldBe true
    }
    "join pack contains every XP fill and leaves native progress transparent" {
        val files=mutableMapOf<String,ByteArray>(); EnergyHud.addAssets(files)
        val vanilla=ImageIO.read(EnergyHud::class.java.getResourceAsStream("/pack/xp_progress.png"))
        for(fill in 1..182) {
            val image=ImageIO.read(files.getValue("assets/ssm/textures/font/energy/$fill.png").inputStream())
            image.width shouldBe fill; image.height shouldBe 5
            val original=ImageIO.read(files.getValue("assets/ssm/textures/font/energy/vanilla_$fill.png").inputStream())
            for(x in 0 until fill) for(y in 0..4) original.getRGB(x,y) shouldBe vanilla.getRGB(x,y)
        }
        val empty=ImageIO.read(files.getValue("assets/minecraft/textures/gui/sprites/hud/experience_bar_progress.png").inputStream())
        for(x in 0..181) for(y in 0..4) empty.getRGB(x,y).ushr(24) shouldBe 0
    }
})


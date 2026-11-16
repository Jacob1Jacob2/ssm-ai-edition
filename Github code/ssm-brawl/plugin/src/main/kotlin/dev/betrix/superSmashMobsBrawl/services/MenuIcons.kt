package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import dev.betrix.superSmashMobsBrawl.smash.SmashItems
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack

/** Slot numbers follow the supplied six-row chest reference, including future kits. */
object KitMenuLayout {
    val slots=linkedMapOf("skeleton" to 1,"iron_golem" to 2,"spider" to 3,"slime" to 4,
        "goat" to 5,"squid" to 6,"creeper" to 7,"enderman" to 10,"snowman" to 11,"wolf" to 12,
        "magma_cube" to 13,"witch" to 14,"wither_skeleton" to 15,"zombie" to 16,
        "cow" to 19,"horse" to 20,"pig" to 21,"blaze" to 22,"chicken" to 23,
        "guardian" to 24,"mooshroom" to 25,"sheep" to 28,"villager" to 29,"phantom" to 30,"polar_bear" to 31)
    val comingSoon=listOf(32,33,34,38,39,40,41)
}

object MenuIcons {
    fun navigation(start: Boolean) = ItemStack.of(Material.PAPER).apply {
        editMeta { it.itemModel = NamespacedKey("ssm", if(start) "menu_start" else "menu_back") }
    }

    val CRYSTAL_COLORS=listOf(NamedTextColor.BLUE,NamedTextColor.GREEN,NamedTextColor.YELLOW,NamedTextColor.RED,NamedTextColor.LIGHT_PURPLE)
    fun difficulty(value:BotDifficulty?)=when(value) {
        BotDifficulty.NORMAL->Material.STONE_SWORD
        BotDifficulty.HARD->Material.IRON_SWORD
        BotDifficulty.HARDER->Material.DIAMOND_SWORD
        BotDifficulty.EXPERT->Material.NETHERITE_SWORD
        null->Material.NETHERITE_SPEAR
    }
    fun items(enabled:Boolean,seconds:Int)=if(!enabled)Material.BARRIER else when(seconds) {
        3->Material.COPPER_CHEST;9->Material.EXPOSED_COPPER_CHEST
        30->Material.WEATHERED_COPPER_CHEST;90->Material.OXIDIZED_COPPER_CHEST
        else->Material.ENDER_CHEST
    }
    fun crystals(enabled:Boolean,seconds:Int)=if(!enabled)Material.BARRIER else when(seconds) {
        60->Material.COPPER_BLOCK;180->Material.IRON_BLOCK;540->Material.GOLD_BLOCK
        1200->Material.DIAMOND_BLOCK;else->Material.NETHERITE_BLOCK
    }
    fun star(item:ItemStack,index:Int) {SmashItems.styleStar(item,index)}
    fun kit(id:String,fallback:Material)=ItemStack.of(when(id) {
        "skeleton"->Material.BOW;"wolf"->Material.BONE;"spider"->Material.SPIDER_EYE;"sheep"->Material.PLAYER_HEAD;"polar_bear"->Material.COD;else->fallback
    }).apply {
        if(id=="goat") {
            editMeta { it.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP) }
            setData(DataComponentTypes.TOOLTIP_DISPLAY,
                io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay()
                    .addHiddenComponents(DataComponentTypes.INSTRUMENT).build())
        }
        if(id=="sheep")editMeta {it.itemModel=NamespacedKey("ssm","wool_head")}
        if(id=="witch")setData(DataComponentTypes.TOOLTIP_DISPLAY,
            io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.POTION_CONTENTS).build())
    }
}

object MenuAssets {
    // The odd-width vanilla eye is centered on pixel 8, half a texel beyond the tile center.
    // Move the entire model (eye plus overlay) together; never blur or resample its pixels.
    const val CENTERED_EYE_GUI = "\"display\":{\"gui\":{\"translation\":[-0.5,0.5,0]}}"
    fun addTo(files:MutableMap<String,ByteArray>) {
        files["assets/ssm/textures/item/kit_question.png"] = questionTexture()
        files["assets/ssm/models/item/kit_random.json"] = """{"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:item/ender_eye","layer1":"ssm:item/kit_question"},$CENTERED_EYE_GUI}""".toByteArray()
        files["assets/ssm/items/kit_random.json"] = """{"model":{"type":"minecraft:model","model":"ssm:item/kit_random"}}""".toByteArray()
        // Player-head special rendering ignores vanilla glint. Keep its real skin and add
        // a translucent animated glint layer, plus a small check mark for selected opponents.
        files["assets/ssm/textures/item/opponent_selected.png"] = selectionShimmer()
        files["assets/ssm/textures/item/opponent_selected.png.mcmeta"] = """{"animation":{"frametime":2,"interpolate":false}}""".toByteArray()
        files["assets/ssm/models/item/opponent_selected_overlay.json"] = """{"gui_light":"front","ambientocclusion":false,"textures":{"layer0":"ssm:item/opponent_selected"},"elements":[{"from":[0,0,8],"to":[16,16,8],"shade":false,"light_emission":15,"faces":{"south":{"uv":[0,0,16,16],"texture":"#layer0"},"north":{"uv":[16,0,0,16],"texture":"#layer0"}}}],"display":{"gui":{"translation":[0,0,8]}}}""".toByteArray()
        files["assets/ssm/items/opponent_selected.json"] = """{"model":{"type":"minecraft:composite","models":[{"type":"minecraft:special","base":"minecraft:item/template_skull","model":{"type":"minecraft:player_head"},"transformation":{"left_rotation":[1,0,0,0],"right_rotation":[0,0,0,1],"scale":[1,1,1],"translation":[0.5,0,0.5]}},{"type":"minecraft:model","model":"ssm:item/opponent_selected_overlay"}]}}""".toByteArray()
        // Shadow Walk keeps the vanilla skeleton skull, with an animated glint when enabled.
        files["assets/ssm/textures/item/shadow_walk_enabled.png"] = selectionShimmer(showCheck=false)
        files["assets/ssm/textures/item/shadow_walk_enabled.png.mcmeta"] = """{"animation":{"frametime":2,"interpolate":false}}""".toByteArray()
        files["assets/ssm/models/item/shadow_walk_enabled_overlay.json"] = """{"parent":"minecraft:item/generated","textures":{"layer0":"ssm:item/shadow_walk_enabled"},"display":{"gui":{"translation":[0,0,8]}}}""".toByteArray()
        files["assets/ssm/items/shadow_walk_enabled.json"] = """{"model":{"type":"minecraft:composite","models":[{"type":"minecraft:special","base":"minecraft:item/template_skull","model":{"type":"minecraft:head","kind":"skeleton"},"transformation":{"left_rotation":[1,0,0,0],"right_rotation":[0,0,0,1],"scale":[1,1,1],"translation":[0.5,0,0.5]}},{"type":"minecraft:model","model":"ssm:item/shadow_walk_enabled_overlay"}]}}""".toByteArray()
        for ((name,color) in listOf("eye_reflect" to 0x58D9FF,"eye_expand" to 0xFF9952,"eye_shrink" to 0xE58DFF)) {
            files["assets/ssm/textures/item/$name.png"]=transformEye(name,color)
            files["assets/ssm/models/item/$name.json"]="""{"parent":"minecraft:item/generated","textures":{"layer0":"ssm:item/$name"},$CENTERED_EYE_GUI}""".toByteArray()
            files["assets/ssm/items/$name.json"]="""{"model":{"type":"minecraft:model","model":"ssm:item/$name"}}""".toByteArray()
        }
        for (name in listOf("menu_back", "menu_start")) {
            files["assets/ssm/textures/item/$name.png"] = navigationTexture(name == "menu_start")
            files["assets/ssm/models/item/$name.json"] = """{"parent":"minecraft:item/generated","textures":{"layer0":"ssm:item/$name"}}""".toByteArray()
            files["assets/ssm/items/$name.json"] = """{"model":{"type":"minecraft:model","model":"ssm:item/$name"}}""".toByteArray()
        }
        files["assets/ssm/models/item/large_mushroom.json"]="""{"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:block/red_mushroom"},"display":{"gui":{"scale":[1.3,1.3,1.3],"translation":[0,5.85,0]}}}""".toByteArray()
        files["assets/ssm/items/large_mushroom.json"]="""{"model":{"type":"minecraft:model","model":"ssm:item/large_mushroom"}}""".toByteArray()
        val faces=listOf("north","south","east","west","up","down").joinToString(",") {"\"$it\":{\"texture\":\"#wool\"}"}
        files["assets/ssm/models/item/wool_head.json"]="""{"parent":"minecraft:block/block","textures":{"wool":"minecraft:block/white_wool","particle":"minecraft:block/white_wool"},"elements":[{"from":[4,4,4],"to":[12,12,12],"faces":{$faces}}],"display":{"gui":{"rotation":[30,225,0],"scale":[1,1,1]}}}""".toByteArray()
        files["assets/ssm/items/wool_head.json"]="""{"model":{"type":"minecraft:model","model":"ssm:item/wool_head"}}""".toByteArray()
    }

    private fun questionTexture(): ByteArray {
        val img=java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val points=buildList {
            listOf(".###.","##.##","...##","..##.","..#..",".....","..#..").forEachIndexed { y,row ->
                row.forEachIndexed { x,ch -> if(ch=='#') add(x+6 to y+5) }
            }
        }
        for((x,y) in points) for(dx in -1..1) for(dy in -1..1) img.setRGB(x+dx,y+dy,0xFF193329.toInt())
        for((x,y) in points) img.setRGB(x,y,0xFFFFFFDE.toInt())
        return png(img)
    }

    internal fun transformEye(name:String,color:Int):ByteArray {
        val img=java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val base=MenuAssets::class.java.getResourceAsStream("/pack/ender_eye.png").use { javax.imageio.ImageIO.read(requireNotNull(it)) }
        // Odd-width eye and every arrow share the same pixel-8 axis. Sample symmetrically
        // from vanilla so downscaling cannot remove one edge but keep the opposite edge.
        val eyeSize=9
        val eyeX=4
        val eyeY=4
        for(y in 0 until eyeSize)for(x in 0 until eyeSize) {
            val px=base.getRGB(8+kotlin.math.round((x-4)*1.5).toInt(),8+kotlin.math.round((y-4)*1.5).toInt())
            if(px.ushr(24)==0)continue
            val light=(((px ushr 16) and 255)*.3+((px ushr 8) and 255)*.59+(px and 255)*.11)/220
            var tinted=px and -0x1000000
            for(shift in listOf(16,8,0))tinted=tinted or ((((color ushr shift) and 255)*light).toInt().coerceIn(0,255) shl shift)
            img.setRGB(x+eyeX,y+eyeY,tinted)
        }
        val points=hashSetOf<Pair<Int,Int>>()
        if(name=="eye_reverse") {
            // Matching single-pixel arrows above and below a centered eye.
            for(x in 5..11) { points.add(x to 3); points.add((16-x) to 13) }
            points.add(10 to 2); points.add(10 to 4)
            points.add(6 to 12); points.add(6 to 14)
        } else {
            val left=hashSetOf<Pair<Int,Int>>()
            val inward=name=="eye_shrink"
            // One-pixel shafts and two-step diagonal heads read as arrows, not plus signs.
            val tip=if(inward)5 else 2
            for(x in 2..5)left.add(x to 8)
            for(d in 1..2)for(y in listOf(8-d,8+d))left.add((tip+if(inward)-d else d) to y)
            for((x,y) in left) {
                points.add(x to y);points.add((16-x) to y)
                if(name=="eye_expand" || name=="eye_shrink") {
                    points.add(y to x);points.add(y to (16-x))
                }
            }
            if(name=="eye_reflect")for(y in listOf(2,3,13,14))points.add(8 to y)
        }
        // An equal outline on every side preserves the center; no one-sided drop shadow.
        for((x,y) in points)for((dx,dy) in listOf(-1 to 0,1 to 0,0 to -1,0 to 1))img.setRGB(x+dx,y+dy,0xFF19222C.toInt())
        for((x,y) in points)img.setRGB(x,y,0xFFF0FFFF.toInt())
        return png(img)
    }

    private fun navigationTexture(start: Boolean): ByteArray {
        val img=java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g=img.createGraphics()
        g.color=java.awt.Color(if(start)0x123823 else 0x461A23)
        g.fillRect(1,2,14,12);g.fillRect(2,1,12,14)
        g.color=java.awt.Color(if(start)0x44ED87 else 0xFF6171)
        g.fillRect(2,2,12,12)
        g.color=java.awt.Color(if(start)0x167342 else 0xA52D42)
        g.fillRect(3,4,10,9)
        g.color=java.awt.Color(0xF4FFF8)
        g.dispose()
        if(start) {
            // GO stays legible at 16px, with the menu name still labelled Start.
            val alphabet=mapOf('G' to listOf("111","100","101","101","111"),
                'O' to listOf("111","101","101","101","111"))
            "GO".forEachIndexed {i,c -> alphabet.getValue(c).forEachIndexed {y,row ->
                row.forEachIndexed {x,p -> if(p=='1') {
                    img.setRGB(5+i*4+x,6+y,0xFF0A3923.toInt())
                    img.setRGB(4+i*4+x,5+y,0xFFF5FFE8.toInt())
                }}
            }}
        } else {
            // Mirror around y=7.5 so both halves of the arrow are exactly even.
            for(y in 4..7)for(x in 4..11) {
                val head=x in (4+7-y)..(5+7-y)
                val shaft=y==7 && x>=5
                if(head||shaft) {
                    img.setRGB(x,y,0xFFFFF5F5.toInt())
                    img.setRGB(x,15-y,0xFFFFF5F5.toInt())
                }
            }
        }
        return png(img)
    }
    private fun selectionShimmer(showCheck:Boolean=true): ByteArray {
        val image=java.awt.image.BufferedImage(16,16*24,java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for(frame in 0 until 24) {
            for(y in 2..12)for(x in 3..12) {
                val band=Math.floorMod(x+y-frame,12)
                if(band<3)image.setRGB(x,frame*16+y,if(band==0)0xA0EFBAFF.toInt() else 0x607B38CC)
            }
            if(showCheck) {
                val check=listOf(10 to 12,11 to 13,12 to 12,13 to 11,14 to 10)
                check.forEach {(x,y)->image.setRGB(x,frame*16+y+1,0xFF16452D.toInt())}
                check.forEach {(x,y)->image.setRGB(x,frame*16+y,0xFF74FF9D.toInt())}
            }
        }
        return png(image)
    }
    private fun png(img:java.awt.image.BufferedImage) = java.io.ByteArrayOutputStream().also {
        javax.imageio.ImageIO.write(img,"png",it)
    }.toByteArray()
}

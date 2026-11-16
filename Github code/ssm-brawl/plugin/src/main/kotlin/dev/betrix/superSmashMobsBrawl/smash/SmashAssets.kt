package dev.betrix.superSmashMobsBrawl.smash

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object SmashAssets {
    fun addTo(files:MutableMap<String,ByteArray>) {
        CrystalVisual.addAssets(files)
        SmashTier.entries.forEach {tier ->
            val i=tier.ordinal
            val texture=if(i==3) "smash_star_ultra" else "smash_star_super"
            val layers=if(i>=2) "\"layer0\":\"ssm:item/$texture\"" else
                "\"layer0\":\"minecraft:item/nether_star\""
            files["assets/ssm/models/item/smash_star_$i.json"]="""{"parent":"minecraft:item/generated","textures":{$layers}}""".toByteArray()
            files["assets/ssm/items/smash_star_$i.json"]="""{"model":{"type":"minecraft:model","model":"ssm:item/smash_star_$i"}}""".toByteArray()
            if(i>=2) {
                files["assets/ssm/textures/item/$texture.png"]=enchantedStar(ultra=i==3)
                files["assets/ssm/textures/item/$texture.png.mcmeta"]="""{"animation":{"frametime":1,"interpolate":false}}""".toByteArray()
            }
            val color=listOf("light_blue","lime","yellow","red")[i]
            fun cube(from:String,to:String,texture:String,rotation:String=""):String {
                val faces=listOf("north","south","east","west","up","down").joinToString(",") {"\"$it\":{\"texture\":\"#$texture\"}"}
                return """{"from":[$from],"to":[$to],$rotation"faces":{$faces}}"""
            }
            val outer=cube("2,2,2","14,14,14","glass","\"rotation\":{\"origin\":[8,8,8],\"axis\":\"z\",\"angle\":45},")
            val middle=cube("4,4,4","12,12,12","white","\"rotation\":{\"origin\":[8,8,8],\"axis\":\"x\",\"angle\":45},")
            val core=cube("5,5,5","11,11,11","core")
            files["assets/ssm/models/item/smash_crystal_$i.json"]="""{"textures":{"glass":"minecraft:block/${color}_stained_glass","white":"minecraft:block/glass","core":"minecraft:block/${color}_concrete","particle":"minecraft:block/${color}_stained_glass"},"elements":[$outer,$middle,$core],"gui_light":"front"}""".toByteArray()
            files["assets/ssm/items/smash_crystal_$i.json"]="""{"model":{"type":"minecraft:model","model":"ssm:item/smash_crystal_$i"}}""".toByteArray()
        }
        files["assets/ssm/models/item/smash_star_random.json"]="""{"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:item/nether_star"}}""".toByteArray()
        files["assets/ssm/items/smash_star_random.json"]="""{"model":{"type":"minecraft:model","model":"ssm:item/smash_star_random","tints":[{"type":"minecraft:constant","value":16733695}]}}""".toByteArray()
    }

    /** Vanilla's exact 16px silhouette, with brighter animated facets contained entirely inside the star. */
    private fun enchantedStar(ultra:Boolean):ByteArray {
        val size=16
        val image=BufferedImage(size,size*48,BufferedImage.TYPE_INT_ARGB)
        val shape=SmashAssets::class.java.getResourceAsStream("/pack/nether_star.png").use { ImageIO.read(requireNotNull(it)) }
        fun solid(x:Int,y:Int)=x in 0..15 && y in 0..15 && shape.getRGB(x,y).ushr(24)>0
        fun pixel(x:Int,y:Int,frame:Int,color:Int) {if(x in 0..15 && y in 0..15)image.setRGB(x,y+16*frame,0xFF000000.toInt() or color)}
        for(frame in 0 until 48) {
            for(y in 0..15)for(x in 0..15) {
                if(!solid(x,y))continue
                val dx=x-8.0;val dy=y-8.0
                val edge=!solid(x-1,y)||!solid(x+1,y)||!solid(x,y-1)||!solid(x,y+1)
                val gem=kotlin.math.abs(dx)+kotlin.math.abs(dy)<2.5
                val shine=Math.floorMod(x+y-frame/2,if(ultra)12 else 24) <= if(ultra)1 else 0
                val color=when {
                    shine->0xFFFFFF
                    edge->if(ultra)0xB94461 else 0x76501E
                    gem && dx+dy< -1 ->if(ultra)0xFFB7C8 else 0xFFF6A0
                    gem && dx>dy->if(ultra)0xE12D51 else 0xFFBF32
                    gem->if(ultra)0x991B40 else 0xD87814
                    // Alternating ivory/silver and gold/crimson facets on the long points.
                    (x<8)==(y<8)->if(ultra)0xEFF7FF else 0xFFF8D6
                    kotlin.math.abs(dx)>kotlin.math.abs(dy)->if(ultra)0xF07B91 else 0xE9AE43
                    else->if(ultra)0x9EAFCB else 0xFFDE7C
                }
                pixel(x,y,frame,color)
            }

        }
        return ByteArrayOutputStream().also {ImageIO.write(image,"png",it)}.toByteArray()
    }
}

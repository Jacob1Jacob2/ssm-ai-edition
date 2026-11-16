package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.items.ItemService
import dev.betrix.superSmashMobsBrawl.services.ResourcePackService
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/** Display interpolation avoids EndCrystal's NO_OP client interpolation (20 Hz position snaps).
 * The three nested parts retain the vanilla crystal mesh/texture, without its vertical bobbing. */
internal class CrystalVisual(at:Location,private val tier:SmashTier):AutoCloseable {
    val hitbox=at.world.spawn(at,Interaction::class.java) {
        it.interactionWidth=2f;it.interactionHeight=2.3f;it.isPersistent=false
        it.isResponsive=true
    }
    private val packed=mutableListOf<ItemDisplay>()
    private val fallback=mutableListOf<BlockDisplay>()
    private val viewers=mutableMapOf<UUID,Boolean>()
    init {
        repeat(3) { part ->
            packed+=at.world.spawn(at.clone().add(0.0,.85,0.0),ItemDisplay::class.java) {
                setup(it)
                it.itemDisplayTransform=ItemDisplay.ItemDisplayTransform.NONE
                it.setItemStack(ItemStack.of(Material.END_CRYSTAL).apply {
                    editMeta { m -> m.itemModel=NamespacedKey("ssm",if(part==2)"crystal_core" else "crystal_cage_${tier.ordinal}") }
                })
            }
            // Declining the pack still gives a smoothly moving, rotating crystal.
            fallback+=at.world.spawn(at.clone().add(0.0,.85,0.0),BlockDisplay::class.java) {
                setup(it);it.block=(if(part==2)tier.glass else Material.GLASS).createBlockData()
            }
        }
        move(at,0)
    }
    private fun setup(display:Display) {
        display.isVisibleByDefault=false;display.isPersistent=false
        display.brightness=Display.Brightness(15,15);display.viewRange=2.5f
        display.teleportDuration=1;display.interpolationDuration=1
        display.displayWidth=3f;display.displayHeight=3f
    }
    fun move(at:Location,age:Int) {
        hitbox.teleport(at)
        val spin=Math.toRadians(age*3.0).toFloat()
        val tilt=Quaternionf().rotationAxis((Math.PI/3).toFloat(),.70710677f,0f,.70710677f)
        var rotation=Quaternionf().rotationY(spin).mul(tilt)
        for(part in 0..2) {
            if(part>0)rotation=Quaternionf(rotation).mul(tilt).rotateY(spin)
            val scale=when(part){0->1f;1->.875f;else->.6699219f}
            val center=at.clone().add(0.0,.85,0.0)
            packed[part].apply {
                teleport(center);interpolationDelay=0
                transformation=Transformation(Vector3f(),Quaternionf(rotation),Vector3f(scale),Quaternionf())
            }
            fallback[part].apply {
                teleport(center);interpolationDelay=0
                val offset=Vector3f(-.5f).mul(scale).rotate(rotation)
                transformation=Transformation(offset,Quaternionf(rotation),Vector3f(scale),Quaternionf())
            }
        }
        if(age%10==0)at.world.players.forEach { viewer ->
            val hasPack=ResourcePackService.hasPack(viewer)
            if(viewers.put(viewer.uniqueId,hasPack)==hasPack)return@forEach
            packed.forEach { if(hasPack)viewer.showEntity(ItemService.plugin,it) else viewer.hideEntity(ItemService.plugin,it) }
            fallback.forEach { if(hasPack)viewer.hideEntity(ItemService.plugin,it) else viewer.showEntity(ItemService.plugin,it) }
        }
    }
    override fun close(){hitbox.remove();packed.forEach {it.remove()};fallback.forEach {it.remove()};viewers.clear()}

    companion object {
        fun addAssets(files:MutableMap<String,ByteArray>) {
            // Cuboid models cannot mix atlases. Keep the native texture and block accents together.
            files["assets/ssm/textures/block/crystal_native.png"]=requireNotNull(
                CrystalVisual::class.java.getResourceAsStream("/pack/end_crystal.png")).use {it.readBytes()}
            fun model(name:String,elements:List<String>,extra:String="") {
                files["assets/ssm/items/$name.json"]="""{"model":{"type":"minecraft:model","model":"ssm:item/$name"}}""".toByteArray()
                files["assets/ssm/models/item/$name.json"]="""{"textures":{"crystal":"ssm:block/crystal_native","particle":"minecraft:block/glass"$extra},"elements":[${elements.joinToString(",")}]}""".toByteArray()
            }
            // Vanilla EndCrystalModel's 8x8x8 cubes use (0,0) and (32,0) on a 64x32 atlas.
            fun nativeCube(core:Boolean):String {
                val u=if(core)8 else 0
                val uv=mapOf("west" to listOf(u,4,u+2,8),"north" to listOf(u+2,4,u+4,8),
                    "east" to listOf(u+4,4,u+6,8),"south" to listOf(u+6,4,u+8,8),
                    "up" to listOf(u+2,0,u+4,4),"down" to listOf(u+4,0,u+6,4))
                val faces=uv.entries.joinToString(",") { (face,coords)->"\"$face\":{\"uv\":[${coords.joinToString(",")}],\"texture\":\"#crystal\"}" }
                return """{"from":[0,0,0],"to":[16,16,16],"shade":false,"faces":{$faces}}"""
            }
            fun gem(x:Int,y:Int,z:Int,dx:Int=2,dy:Int=2,dz:Int=2):String {
                val faces=listOf("up","down","north","south","west","east").joinToString(",") {"\"$it\":{\"texture\":\"#accent\"}"}
                return """{"from":[$x,$y,$z],"to":[${x+dx},${y+dy},${z+dz}],"faces":{$faces}}"""
            }
            model("crystal_core",listOf(nativeCube(true)))
            SmashTier.entries.forEach { tier ->
                val elements=mutableListOf(nativeCube(false))
                if(tier.ordinal>0)for(x in listOf(0,14))for(y in listOf(0,14))for(z in listOf(0,14))
                    if(tier.ordinal>=2 || (x+y+z)%28==0)elements+=gem(x,y,z)
                if(tier==SmashTier.ULTRA)for(a in listOf(0,15))for(b in listOf(0,15)) {
                    elements+=gem(a,b,0,1,1,16);elements+=gem(a,0,b,1,16,1);elements+=gem(0,a,b,16,1,1)
                }
                val accent=when(tier){SmashTier.LIGHT->"diamond_block";SmashTier.REGULAR->"emerald_block";SmashTier.SUPER->"gold_block";SmashTier.ULTRA->"redstone_block"}
                model("crystal_cage_${tier.ordinal}",elements,",\"accent\":\"minecraft:block/$accent\"")
            }
        }
    }
}

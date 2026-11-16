package dev.betrix.superSmashMobsBrawl.services

import org.bukkit.inventory.ItemStack
import org.bukkit.NamespacedKey
import kotlinx.serialization.json.*

object SelectionIcons {
    private val selectionKey=NamespacedKey("ssm","menu_selected")
    fun mark(item:ItemStack,selected:Boolean) { item.editMeta { it.persistentDataContainer.set(selectionKey,org.bukkit.persistence.PersistentDataType.BYTE,if(selected)1.toByte() else 0.toByte()) } }
    fun apply(item:ItemStack) {
        val flag=item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)
        val selected=item.itemMeta.persistentDataContainer.get(selectionKey,org.bukkit.persistence.PersistentDataType.BYTE)?.let {it==1.toByte()} ?: (flag==true)
        if(!selected)return
        val base=item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL) ?: item.type.key
        if(base.value().startsWith("selected_") || base.value()=="opponent_selected")return
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,net.kyori.adventure.key.Key.key("ssm","selected_${base.namespace()}_${base.value().replace('/','_')}"))

    }

    fun assets(files:MutableMap<String,ByteArray>) {
        java.util.zip.ZipInputStream(requireNotNull(javaClass.getResourceAsStream("/pack/selection-vanilla.zip"))).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break;files[entry.name]=zip.readBytes() }
        }
        // Minecraft chooses GUI lighting from the FIRST render layer, even for composites.
        // An empty front-lit layer prevents the 3D base from darkening the selection overlay.
        // Keep base geometry/textures and the base-before-overlay draw order intact.
        files["assets/ssm/models/item/selection_lighting.json"] =
            """{"gui_light":"front","elements":[]}""".toByteArray()
        val lighting=Json.parseToJsonElement("""{"type":"minecraft:model","model":"ssm:item/selection_lighting"}""")
        for (name in listOf("tnt", "ice", "skeleton_skull", "oak_pressure_plate", "slime_block")) {
            val path="assets/ssm/items/selected_minecraft_$name.json"
            val root=Json.parseToJsonElement(requireNotNull(files[path]).decodeToString()).jsonObject
            val composite=root.getValue("model").jsonObject
            val layers=composite.getValue("models").jsonArray
            files[path]=JsonObject(root + ("model" to JsonObject(composite +
                ("models" to JsonArray(listOf(lighting) + layers))))).toString().toByteArray()
        }
        val overlay=Json.parseToJsonElement("""{"type":"minecraft:model","model":"ssm:item/opponent_selected_overlay"}""")
        files.toMap().forEach { (path,bytes) ->
            if(path.startsWith("assets/ssm/items/") && !path.contains("/selected_") && !path.contains("opponent_selected")) {
                val root=Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                val model=root["model"] ?: return@forEach
                val result=JsonObject(root+ ("model" to buildJsonObject { put("type","minecraft:composite");put("models",JsonArray(listOf(model,overlay))) }))
                files[path.replace("assets/ssm/items/","assets/ssm/items/selected_ssm_")]=result.toString().toByteArray()
            }
        }
    }
}

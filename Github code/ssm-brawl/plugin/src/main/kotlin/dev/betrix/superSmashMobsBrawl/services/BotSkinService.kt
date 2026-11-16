package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.profile.PlayerProfile
import org.bukkit.craftbukkit.profile.CraftPlayerProfile
import com.mojang.authlib.properties.Property
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Resolve real account skins off-thread, cache them, and update menus on the server thread. */
object BotSkinService:KoinComponent {
    /** Copy only the skin: a bot keeps its synthetic UUID, roster name and team identity. */
    fun applyTo(player:org.bukkit.entity.Player,skin:PlayerProfile) {
        val profile=player.playerProfile
        val texture=(skin as CraftPlayerProfile).getProperty("textures") ?: return
        profile.setProperty(com.destroystokyo.paper.profile.ProfileProperty("textures",texture.value(),texture.signature()))
        player.playerProfile=profile // Paper refreshes the tab-list profile for every viewer.
    }
    private val plugin:SuperSmashMobsBrawl by inject()
    private val file get()=File(plugin.dataFolder,"bot-skins.yml")
    private val saved by lazy {YamlConfiguration.loadConfiguration(file)}
    private val cache=mutableMapOf<String,PlayerProfile>()
    private val waiting=mutableMapOf<String,MutableList<(PlayerProfile?)->Unit>>()
    private val queue=ArrayDeque<String>()
    private val failedUntil=mutableMapOf<String,Long>()
    private var running=0
    private var stopped=false
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private data class Skin(val uuid:UUID,val name:String,val value:String,val signature:String)
    private fun profile(skin:Skin):PlayerProfile = Bukkit.createPlayerProfile(skin.uuid,skin.name).apply {
        (this as CraftPlayerProfile).setProperty("textures",Property("textures",skin.value,skin.signature))
    }
    private fun json(url:String)=http.sendAsync(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(8)).GET().build(),HttpResponse.BodyHandlers.ofString()).thenApply {
        check(it.statusCode()==200) {"Profile service returned ${it.statusCode()}"}
        Json.parseToJsonElement(it.body()).jsonObject
    }
    private fun lookup(name:String):CompletableFuture<Skin> {
        require(name.matches(Regex("[A-Za-z0-9_]{1,16}")))
        // Resolve the real UUID by name, never the synthetic UUID used by the local AI bot.
        return json("https://api.minecraftservices.com/minecraft/profile/lookup/name/$name").thenCompose {account ->
            val id=account.getValue("id").jsonPrimitive.content
            val uuid=UUID.fromString("${id.substring(0,8)}-${id.substring(8,12)}-${id.substring(12,16)}-${id.substring(16,20)}-${id.substring(20)}")
            json("https://sessionserver.mojang.com/session/minecraft/profile/$id?unsigned=false").thenApply {profile ->
                val texture=profile.getValue("properties").jsonArray.first {it.jsonObject["name"]?.jsonPrimitive?.content=="textures"}.jsonObject
                Skin(uuid,account.getValue("name").jsonPrimitive.content,texture.getValue("value").jsonPrimitive.content,texture.getValue("signature").jsonPrimitive.content)
            }
        }
    }
    private fun deliver(receive:(PlayerProfile?)->Unit,profile:PlayerProfile?) {
        Bukkit.getScheduler().runTask(plugin,Runnable {receive(profile?.clone())})
    }

    fun cached(name:String):PlayerProfile? {
        val key=name.lowercase()
        cache[key]?.let {return it.clone()}
        val stored=runCatching {
            val uuid=saved.getString("$key.uuid") ?: return@runCatching null
            val value=saved.getString("$key.value") ?: return@runCatching null
            val signature=saved.getString("$key.signature") ?: return@runCatching null
            if(System.currentTimeMillis()-saved.getLong("$key.updated")>86_400_000)return@runCatching null
            profile(Skin(UUID.fromString(uuid),saved.getString("$key.name",name)!!,value,signature))
        }.getOrNull()
        if(stored!=null)cache[key]=stored
        return stored?.clone()
    }
    fun resolve(name:String,receive:(PlayerProfile?)->Unit) {
        if(stopped)return
        val key=name.lowercase()
        cached(name)?.let {deliver(receive,it);return}
        if((failedUntil[key] ?: 0L)>System.currentTimeMillis()) {deliver(receive,null);return}
        waiting[key]?.let {it+=receive;return}
        waiting[key]=mutableListOf(receive);queue+=name;pump()
    }

    private fun pump() {
        while(running<3 && queue.isNotEmpty()) {
            val name=queue.removeFirst();val key=name.lowercase();running++
            val lookup=runCatching {lookup(name)}.getOrElse {CompletableFuture.failedFuture(it)}
            lookup.orTimeout(15,TimeUnit.SECONDS).whenComplete {skin,error ->
                if(plugin.isEnabled && !stopped)Bukkit.getScheduler().runTask(plugin,Runnable {
                    running--
                    val valid=skin?.takeIf {error==null}?.let {result ->
                        profile(result)
                    }
                    if(valid!=null) {
                        cache[key]=valid.clone()
                        saved.set("$key.uuid",valid.uniqueId.toString());saved.set("$key.skin",valid.textures.skin.toString())
                        saved.set("$key.name",skin!!.name);saved.set("$key.value",skin.value);saved.set("$key.signature",skin.signature)
                        saved.set("$key.updated",System.currentTimeMillis())
                        runCatching {saved.save(file)}.onFailure {plugin.logger.fine("Skin cache save: ${it.message}")}
                    } else failedUntil[key]=System.currentTimeMillis()+300_000
                    waiting.remove(key)?.forEach {it(valid?.clone())}
                    pump()
                })
            }
        }
    }
    fun teardown() {stopped=true;queue.clear();waiting.clear();http.shutdownNow()}
}

package dev.betrix.superSmashMobsBrawl.bots

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * A language model that answers on this machine: `llama-server` from llama.cpp with a model file
 * loaded, spoken to over its OpenAI-style HTTP interface. Nothing here knows about the game, and
 * nothing is sent anywhere but to [base], which is this computer.
 *
 * Every call is asynchronous and gives null instead of failing: when the model is busy, slow, down
 * or talking nonsense, the bots say their scripted lines, as they did before there was a model.
 *
 * @param base where the server listens, like `http://127.0.0.1:8090`
 * @param timeoutMs how long an answer may take before the scripted line is said instead
 * @param most how many answers may be in the making at once; one asked for beyond that is not made
 */
class ChatModel(base: String, private val timeoutMs: Long = 1500, private val most: Int = 2) {
    private val root = base.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).version(HttpClient.Version.HTTP_1_1).build()
    private val busy = AtomicInteger()

    /** Is the server there with its model loaded? Blocks for up to a second: not for the server thread. */
    fun healthy(): Boolean = try {
        http.send(HttpRequest.newBuilder(URI.create("$root/health")).timeout(Duration.ofSeconds(1)).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode() == 200
    } catch (e: Exception) { false }

    /**
     * One short answer to [user] under [system], or null. The model stops at the end of the line.
     * [shots] are questions and answers it is shown as a conversation already had, before the real
     * one: the same every time, so the server keeps them in mind and only reads what is new.
     * [seed] makes it repeatable (tests); otherwise each answer is a new roll.
     */
    fun complete(system: String, user: String, shots: List<Pair<String, String>> = emptyList(), maxTokens: Int = 40, temperature: Double = 1.0,
                 seed: Long? = null): CompletableFuture<String?> {
        if (busy.incrementAndGet() > most) { busy.decrementAndGet(); return CompletableFuture.completedFuture(null) }
        val body = buildJsonObject {
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                for ((asked, answered) in shots) {
                    addJsonObject { put("role", "user"); put("content", asked) }
                    addJsonObject { put("role", "assistant"); put("content", answered) }
                }
                addJsonObject { put("role", "user"); put("content", user) }
            }
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("top_p", 0.95)
            putJsonArray("stop") { add(kotlinx.serialization.json.JsonPrimitive("\n")) }
            put("cache_prompt", true)
            if (seed != null) put("seed", seed)
        }.toString()
        val request = HttpRequest.newBuilder(URI.create("$root/v1/chat/completions")).timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)).build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).handle { response, error ->
            busy.decrementAndGet()
            if (error != null || response.statusCode() != 200) null else parse(response.body())
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The text of the first answer in a chat-completions reply, or null for anything else. */
        fun parse(body: String): String? = try {
            json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) { null }
    }
}

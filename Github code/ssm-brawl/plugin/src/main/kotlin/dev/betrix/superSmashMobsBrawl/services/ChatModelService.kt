package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.bots.BotDialogue
import dev.betrix.superSmashMobsBrawl.bots.BotSlang
import dev.betrix.superSmashMobsBrawl.bots.BotVoice
import dev.betrix.superSmashMobsBrawl.bots.ChatFilter
import dev.betrix.superSmashMobsBrawl.bots.ChatModel
import dev.betrix.superSmashMobsBrawl.bots.ChatPrompt
import java.io.File
import java.net.URI
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * The language model that words what the bots say, when there is one: a model file run on this
 * machine by `llama-server` (llama.cpp), which this starts with the server and stops with it.
 * Nothing a player types leaves the computer, and there is no account, key or bill.
 *
 * It is switched on by `chat-model.properties` in the plugin's folder (see [Settings]); without
 * that file there is no model and the bots say their scripted lines. They also do whenever the
 * model is not up, is slow, or writes something [ChatFilter] will not let through, so a model
 * that fails is never worse than no model.
 *
 * A model lives in the video card's memory, which the game being played on the same computer
 * needs too. So there can be two: a big one that writes well, used while there is room for it, and
 * a small one that takes over when there is not ([watch]).
 */
object ChatModelService {
    const val FILE = "chat-model.properties"

    /**
     * @param url where the model server listens (this computer)
     * @param runner `llama-server.exe`; empty when something else starts the server
     * @param runtime a folder of libraries the runner needs on its path (the CUDA runtime), or empty
     * @param model the model file (`.gguf`)
     * @param words a list of words that are never said, one to a line, or empty
     * @param idleSeconds after this long with nothing to write the model gives its memory back, and loads again when next asked
     * @param scope what [model] writes. `answers`: an answer to what a player types that the
     *   script has no line for (the script has one for the rest). `chat`: every answer to what a
     *   player types. `all`: everything the bots say but "gg" and "hi". How far to let a model
     *   depends on the model. Tried on 2026-11-24: Gemma 4 12B, given the bots' hand-written
     *   lines to put in its own words, did it well; Gemma 4 E4B made about one in three worse
     *   (who was being teased got mixed up, an accusation turned into a compliment), while what
     *   it answered to questions nobody had scripted was mostly what a player would say.
     * @param smallModel a smaller model file for when the video card has no room for [model], or empty
     * @param smallScope what [smallModel] writes
     * @param reserveMb [model] is only loaded while this much video memory would stay free with it
     */
    data class Settings(val enabled: Boolean = false, val url: String = "http://127.0.0.1:8090", val runner: String = "", val runtime: String = "",
                        val model: String = "", val words: String = "", val context: Int = 12288, val timeoutMs: Long = 1500, val idleSeconds: Int = 900,
                        val scope: String = "answers", val gpuLayers: Int = 99,
                        /** How freely the model words things: lower keeps closer to the likeliest answer. At 1.0 a small one came out with word salad now and then. */
                        val temperature: Double = 0.7,
                        /** Tell the model each bot's own filler word, laugh and typing habit. Off: a small model cannot carry them and an answer at once. */
                        val voices: Boolean = false,
                        val smallModel: String = "", val smallScope: String = "answers", val reserveMb: Int = 1500) {
        companion object {
            fun read(file: File): Settings {
                if (!file.isFile) return Settings()
                val p = Properties().also { props -> file.reader(Charsets.UTF_8).use(props::load) }
                fun text(key: String, default: String) = p.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default
                val d = Settings()
                return Settings(text("enabled", "false").toBoolean(), text("url", d.url), text("runner", ""), text("runtime", ""), text("model", ""),
                    text("words", ""), text("context", "${d.context}").toIntOrNull() ?: d.context, text("timeout-ms", "${d.timeoutMs}").toLongOrNull() ?: d.timeoutMs,
                    text("idle-seconds", "${d.idleSeconds}").toIntOrNull() ?: d.idleSeconds, text("scope", d.scope).lowercase(),
                    text("gpu-layers", "${d.gpuLayers}").toIntOrNull() ?: d.gpuLayers, text("temperature", "${d.temperature}").toDoubleOrNull() ?: d.temperature,
                    text("voices", "${d.voices}").toBoolean(), text("small-model", ""), text("small-scope", d.smallScope).lowercase(),
                    text("reserve-mb", "${d.reserveMb}").toIntOrNull() ?: d.reserveMb)
            }
        }
    }

    /** What came of asking for a line: [text] to say, or null with [note] saying why not. [ms] is how long the model took. */
    data class Outcome(val text: String?, val note: String, val ms: Long = 0, val silent: Boolean = false)

    /** Free and used video memory, in MiB. */
    data class Video(val total: Int, val used: Int) { val free: Int get() = total - used }

    @Volatile var ready = false; private set
    @Volatile private var model: ChatModel? = null
    @Volatile private var filter = ChatFilter()
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    /** The model that is loaded: the small one, or else the main one. */
    @Volatile private var small = false
    private var hooked = false
    private var settings = Settings()
    private var log: Logger = Logger.getLogger("ChatModel")
    val asked = AtomicInteger(); val used = AtomicInteger(); val refused = AtomicInteger(); val missed = AtomicInteger()

    fun setup(folder: File, logger: Logger) {
        log = logger; stopping = false
        settings = Settings.read(File(folder, FILE))
        if (!settings.enabled) return
        filter = ChatFilter(settings.words.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }?.readLines(Charsets.UTF_8).orEmpty())
        val client = ChatModel(settings.url, settings.timeoutMs)
        model = client
        // (The runner is this server's own: it goes when the server does, however the server goes.)
        if (!hooked) { hooked = true; Runtime.getRuntime().addShutdownHook(Thread { runCatching { process?.destroyForcibly() } }) }
        // Loading a model takes seconds: not on the server thread, and the bots talk from their scripts meanwhile.
        Thread({ run(client, File(folder, "chat-model.log")) }, "ssm-chat-model").apply { isDaemon = true }.start()
    }

    /** What the video card has in use, as `nvidia-smi` tells it; null when it cannot be asked. */
    fun video(): Video? = try {
        val p = ProcessBuilder("nvidia-smi", "--query-gpu=memory.total,memory.used", "--format=csv,noheader,nounits").redirectErrorStream(true).start()
        val line = p.inputStream.bufferedReader().use { it.readLine() }
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        line?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }?.let { Video(it[0], it[1]) }
    } catch (e: Exception) { null }

    /**
     * Is the video card too full for the big model? With it loaded: when hardly anything is free.
     * With it [asleep] (unloaded after a quiet while, to be loaded again at the next line): when
     * there is no longer the room to load it, which is what a game started in the meantime does.
     */
    internal fun short(free: Int, bigNeed: Int, asleep: Boolean) = free < (if (asleep) bigNeed + LOW_MB else LOW_MB)

    /** Would the big model fit, with [reserve] to spare, where the small one is now (which, [asleep], holds no memory itself)? */
    internal fun room(free: Int, bigNeed: Int, littleNeed: Int, asleep: Boolean, reserve: Int) =
        free - (if (asleep) bigNeed else (bigNeed - littleNeed).coerceAtLeast(0)) >= reserve

    private fun megabytes(file: File) = (file.length() / (1024 * 1024)).toInt()
    /** When a line was last asked for: a model nobody has asked anything for [Settings.idleSeconds] has unloaded itself. */
    @Volatile private var lastUse = 0L
    private fun asleep() = System.currentTimeMillis() - lastUse > settings.idleSeconds * 1000L + 3000

    /**
     * Starts the model and looks after it for as long as the server runs: the big one while the
     * video card has [Settings.reserveMb] to spare with it loaded, the small one when the card
     * runs short (twice running, half a minute: a game has been started on it), and the big one
     * again once there has been room for it for a few minutes.
     */
    private fun run(client: ChatModel, output: File) {
        try {
            if (client.healthy()) { ready = true; log.info("Chat model: found one already running at ${settings.url}"); return }
            val runner = File(settings.runner); val big = File(settings.model)
            val little = settings.smallModel.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
            if (settings.runner.isEmpty()) { log.warning("Chat model: nothing answers at ${settings.url} and no runner is set; the bots use their scripted lines"); return }
            if (!runner.isFile || !big.isFile) { log.warning("Chat model: ${if (!runner.isFile) runner else big} is not there; the bots use their scripted lines"); return }
            // What each takes of the video card: a guess from the file until it has been loaded once and measured.
            var bigNeed = megabytes(big) + 500
            var littleNeed = little?.let { megabytes(it) * 2 / 3 + 300 } ?: 0
            small = little != null && video()?.let { it.free < bigNeed + settings.reserveMb } == true
            var failures = 0
            while (!stopping) {
                val weights = if (small) little!! else big
                val before = video()
                if (!launch(runner, weights, client, output)) {
                    if (stopping) return
                    if (++failures >= 3) { log.warning("Chat model: gave up on ${weights.name} after three tries; the bots use their scripted lines"); return }
                    if (!small && little != null) small = true
                    Thread.sleep(5000); continue
                }
                lastUse = System.currentTimeMillis()
                video()?.let { now -> before?.let { was -> (now.used - was.used).takeIf { it in 800..40_000 }?.let { if (small) littleNeed = it else bigNeed = it } } }
                log.info("Chat model: ${weights.name} is up" + (if (little != null) " (the ${if (small) "small" else "big"} one)" else "") +
                    "; it writes ${if (small) settings.smallScope else settings.scope}")
                var low = 0; var roomy = 0; var changed = false
                while (!stopping && process?.isAlive == true && !changed) {
                    Thread.sleep(CHECK_MS)
                    if (little == null) continue
                    val free = video()?.free ?: continue
                    if (small) {
                        roomy = if (room(free, bigNeed, littleNeed, asleep(), settings.reserveMb)) roomy + 1 else 0
                        if (roomy >= ROOMY_CHECKS) { log.info("Chat model: the video card has had room for the big model for a while ($free MiB free): loading it"); changed = true }
                    } else {
                        low = if (short(free, bigNeed, asleep())) low + 1 else 0
                        if (low >= LOW_CHECKS) { log.info("Chat model: the video card has no room for the big model now ($free MiB free): the small one takes over"); changed = true }
                    }
                }
                if (stopping) return
                if (changed) { small = !small; failures = 0 }
                // (A runner that keeps dying is not started for ever.)
                else if (++failures >= 3) { log.warning("Chat model: the runner keeps stopping by itself; see ${output.name}. The bots use their scripted lines"); ready = false; halt(); return }
                else log.warning("Chat model: the runner stopped by itself; see ${output.name}. Starting it again")
                ready = false
                halt()
                Thread.sleep(1500)
            }
        } catch (e: InterruptedException) {
        } catch (e: Exception) {
            log.warning("Chat model: stopped looking after it (${e.message}); the bots use their scripted lines")
        }
    }

    /** Starts the runner with [weights] and waits until it answers. False when it does not come up. */
    private fun launch(runner: File, weights: File, client: ChatModel, output: File): Boolean {
        val port = URI.create(settings.url).port.takeIf { it > 0 } ?: 8090
        // On this machine only, with no web page of its own, nothing fetched from anywhere, and
        // no thinking out loud before an answer (a chat line is wanted, at once). Two answers
        // may be in the making at a time, each with half the context.
        val builder = ProcessBuilder(runner.path, "-m", weights.path, "--host", "127.0.0.1", "--port", "$port", "-ngl", "${settings.gpuLayers}",
            "-c", "${settings.context}", "--parallel", "2", "--no-webui", "--offline", "--cors-origins", "localhost", "--reasoning", "off",
            "--sleep-idle-seconds", "${settings.idleSeconds}")
        val env = builder.environment()
        val path = env.keys.firstOrNull { it.equals("PATH", true) } ?: "PATH"
        env[path] = listOf(settings.runtime, runner.parent, env[path].orEmpty()).filter { it.isNotEmpty() }.joinToString(File.pathSeparator)
        builder.directory(runner.parentFile).redirectErrorStream(true).redirectOutput(output)
        val started = System.currentTimeMillis()
        val p = builder.start()
        process = p
        while (!stopping && p.isAlive && !client.healthy()) {
            if (System.currentTimeMillis() - started > 180_000) { log.warning("Chat model: ${weights.name} not up after three minutes; see ${output.name}"); halt(); return false }
            Thread.sleep(500)
        }
        if (stopping) return false
        if (!p.isAlive) { log.warning("Chat model: the runner stopped with code ${p.exitValue()} loading ${weights.name}; see ${output.name}"); return false }
        ready = true
        return true
    }

    private fun halt() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
    }

    fun shutdown() {
        stopping = true; ready = false; model = null
        halt()
    }

    /** How far down [BotDialogue.Brief.Kind] the model that is loaded words the bots' lines ([Settings.scope]); null while there is none. */
    fun reach(): BotDialogue.Brief.Kind? = if (!ready) null else when (if (small) settings.smallScope else settings.scope) {
        "all" -> BotDialogue.Brief.Kind.REMARK
        "chat" -> BotDialogue.Brief.Kind.ANSWER
        else -> BotDialogue.Brief.Kind.UNSURE
    }

    /** A word in [text] that is never said or shown to the model, or null. */
    fun banned(text: String, names: Collection<String> = emptyList()): String? = filter.banned(text, names)

    /**
     * The line for [me] to say about [brief], worded by the model, once it has been through
     * [ChatFilter]. [scene] is how the match or lobby stands, [chat] what was last said there,
     * [fallback] the scripted line (empty when there is none) and [others] everybody else's name.
     */
    fun write(me: ChatPrompt.Persona, scene: String, chat: List<ChatPrompt.Said>, brief: BotDialogue.Brief, fallback: String,
              others: Collection<String>): CompletableFuture<Outcome> {
        val client = model?.takeIf { ready } ?: return CompletableFuture.completedFuture(Outcome(null, "no model"))
        // What a player typed goes to the model only if it could be said here itself.
        val names = others + me.name
        if (filter.banned(brief.event, names) != null) return CompletableFuture.completedFuture(Outcome(null, "not put to the model"))
        // (The stand-in "what?" for something the script did not understand is no guide to an answer: the model is not shown it.)
        val like = fallback.takeIf { it.isNotEmpty() && brief.kind != BotDialogue.Brief.Kind.UNSURE }
        val prompt = ChatPrompt.user(me, scene, chat.filter { filter.banned(it.text, names) == null }, brief, like, filler = settings.voices)
        asked.incrementAndGet()
        lastUse = System.currentTimeMillis()
        val began = System.nanoTime()
        return client.complete(ChatPrompt.SYSTEM, prompt, ChatPrompt.SHOTS, temperature = settings.temperature).thenApply { raw ->
            val ms = (System.nanoTime() - began) / 1_000_000
            if (raw == null) { missed.incrementAndGet(); return@thenApply Outcome(null, "no answer in time", ms) }
            when (val verdict = filter.check(raw, me.name, others, prompt)) {
                is ChatFilter.Line -> { used.incrementAndGet(); Outcome(called(verdict.text, me.name, others), "ok", ms) }
                is ChatFilter.Silent -> Outcome(null, "would say nothing", ms, silent = true)
                is ChatFilter.Rejected -> { refused.incrementAndGet(); Outcome(null, "${verdict.why}: \"${raw.take(80)}\"", ms) }
            }
        }
    }

    /**
     * [text] with people called what this speaker calls them. A model writes everybody's name out
     * in full, "chill kintheinfinite"; a player types "chill kin", some of them nearly always and
     * some hardly ever ([BotVoice.nicknames]), and the scripted lines do the same.
     */
    internal fun called(text: String, speaker: String, others: Collection<String>, random: kotlin.random.Random = kotlin.random.Random.Default): String {
        val habit = BotVoice.of(speaker).nicknames
        var out = text
        for (name in others) {
            val full = name.lowercase(); val nick = BotSlang.nick(name)
            if (nick == full || nick.length < 3 || others.count { BotSlang.nick(it) == nick } != 1) continue
            if (random.nextDouble() < habit) out = out.replace(Regex("(?<![a-z0-9_])${Regex.escape(full)}(?![a-z0-9_])"), nick)
        }
        return out
    }

    /** A request that only wakes a model that has gone to sleep, so that the next real one is answered in time. */
    fun wake() { model?.takeIf { ready }?.let { lastUse = System.currentTimeMillis(); it.complete(ChatPrompt.SYSTEM, "Reply with just: -", ChatPrompt.SHOTS, maxTokens = 1) } }

    fun status(): String = if (!settings.enabled) "off (no $FILE, or enabled=false)" else {
        val file = File(if (small) settings.smallModel else settings.model).name.ifEmpty { "no model set" }
        "${if (ready) "up" else "not up"} at ${settings.url}, $file, writes ${if (small) settings.smallScope else settings.scope}; " +
            "asked ${asked.get()}, said ${used.get()}, refused ${refused.get()}, too slow ${missed.get()}" +
            (video()?.let { "; video memory ${it.free} MiB free of ${it.total}" } ?: "")
    }

    /** How often the video card is looked at, and how many looks in a row decide a change of model. */
    private const val CHECK_MS = 15_000L
    private const val LOW_MB = 500
    private const val LOW_CHECKS = 2
    private const val ROOMY_CHECKS = 12
}

package ced.cedclient.utils.debug

import ced.cedclient.state.IslandState
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.DevAccess
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Bug reporter: notices when something in CedClient went wrong and sends it to Discord through the site Worker.
 *
 * Detectors (all only report problems that involve CedClient code):
 *  - errors thrown by our event handlers (EventBus catches them, so one bad feature no longer breaks the rest)
 *  - ERROR log lines from or about CedClient, and mixin warnings naming it (a Log4j appender)
 *  - uncaught exceptions on any thread whose stack goes through CedClient
 *  - crashes: on launch, crash reports written since the last launch that mention CedClient
 *  - freezes: the game thread stuck for [FREEZE_MS] with CedClient on its stack (a watchdog thread)
 *  - soft checks: features call [warn] / [check] when their state looks wrong (see callers)
 *
 * Same problem = one issue with a counter. Pending issues go out in one report every
 * [SEND_EVERY_MS] (and when the game closes), at most [MAX_MESSAGES] per launch (the Worker also rate limits). Everything is also written to the
 * log as "[CedDiag]", listed by "/cc dev diag", and shown in chat to the dev.
 *
 * Sent: Minecraft name, CedClient + MC version, island, OS and the error text / stack trace. Never: tokens, chat.
 * Opt out with sendReports=false in config/cedclient-diag.properties.
 */
object Diag {
    /**
     * The site Worker's /bug route: it checks the report and forwards it to Discord. The webhook URL is a Worker
     * secret (wrangler secret put DISCORD_WEBHOOK), so it is never in the jar.
     */
    private const val ENDPOINT = "https://cedclient.kasajonny420-dab.workers.dev/bug"

    private const val CONFIG_NAME = "cedclient-diag.properties"
    private const val SEND_EVERY_MS = 60_000L
    private const val MAX_MESSAGES = 25
    private const val FREEZE_MS = 5_000L
    private const val OUR_PACKAGE = "ced.cedclient"

    enum class Kind { ERROR, WARN, CRASH, FREEZE }

    class Issue(val kind: Kind, val source: String, val message: String, val detail: String?) {
        val first: Long = System.currentTimeMillis()
        var last = first
        var count = 0
        var sentCount = 0
    }

    private val issues = LinkedHashMap<String, Issue>()
    private val props = Properties()
    private var messagesSent = 0
    private val inDiag = ThreadLocal.withInitial { false }
    @Volatile private var lastTickAt = 0L
    private var mainThread: Thread? = null
    private var started = false

    // ------------------------------------------------------------------ API

    /** Something threw. [source] = feature or place ("Auto Experiments", "event TickEvent"). */
    @JvmStatic
    @JvmOverloads
    fun error(source: String, message: String, t: Throwable? = null) =
        record(Kind.ERROR, source, message, t?.let { stackText(it) }, t?.let { firstOurFrame(it) })

    /** A feature noticed its own state is wrong (no exception). */
    @JvmStatic
    fun warn(source: String, message: String) = record(Kind.WARN, source, message, null, null)

    /** [warn] unless [ok]; the message is only built when it fails. */
    inline fun check(ok: Boolean, source: String, message: () -> String) {
        if (!ok) warn(source, message())
    }

    /** Most recent issues first, for /cc dev diag. */
    fun recent(n: Int): List<Issue> = synchronized(issues) { issues.values.sortedByDescending { it.last }.take(n) }

    /** Send pending issues now (background thread). */
    fun flushAsync() = Thread({ flush() }, "CedClient-Diag-Flush").apply { isDaemon = true }.start()

    // ------------------------------------------------------------------ setup

    /** Called once from CedClient.onInitializeClient (the game thread). */
    fun start() {
        if (started) return
        started = true
        mainThread = Thread.currentThread()
        loadProps()
        installLogAppender()
        installUncaughtHandler()
        checkCrashReports()
        ClientTickEvents.END_CLIENT_TICK.register { _ -> lastTickAt = System.currentTimeMillis() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { flush() }
        Thread(::backgroundLoop, "CedClient-Diag").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ recording

    private fun record(kind: Kind, source: String, message: String, detail: String?, where: String?) {
        if (inDiag.get()) return // never report our own reporting
        inDiag.set(true)
        try {
            val key = "$kind|$source|${message.take(160)}|$where"
            var isNew = false
            val issue = synchronized(issues) {
                issues.getOrPut(key) { isNew = true; Issue(kind, source, message, detail) }.apply {
                    count++
                    last = System.currentTimeMillis()
                }
            }
            if (isNew) {
                ced.cedclient.utils.Debug.line("Diag", "$kind $source: ${message.take(200)}")
                LogManager.getLogger("CedDiag").warn("[CedDiag] {} {}: {}{}", kind, source, message, detail?.let { "\n$it" } ?: "")
                notifyDev(issue)
            }
        } catch (_: Throwable) {
        } finally {
            inDiag.set(false)
        }
    }

    private fun notifyDev(issue: Issue) {
        if (!DevAccess.isDev) return
        val mc = Minecraft.getInstance()
        mc.execute {
            mc.player?.sendSystemMessage(ChatPrefix.msg("§c[diag] §7${issue.kind} §f${issue.source}§7: ${issue.message.take(120)}"))
        }
    }

    private fun mentionsUs(t: Throwable?): Boolean {
        var cur = t
        var depth = 0
        while (cur != null && depth++ < 8) {
            if (cur.stackTrace.any { it.className.startsWith(OUR_PACKAGE) }) return true
            if (cur.message?.contains("cedclient", ignoreCase = true) == true) return true
            cur = cur.cause
        }
        return false
    }

    private fun firstOurFrame(t: Throwable): String? {
        var cur: Throwable? = t
        while (cur != null) {
            cur.stackTrace.firstOrNull { it.className.startsWith(OUR_PACKAGE) }?.let { return it.toString() }
            cur = cur.cause
        }
        return t.stackTrace.firstOrNull()?.toString()
    }

    /** Exception line + the top frames, our own frames kept further down (that's where the bug usually is). */
    private fun stackText(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth++ < 3) {
            if (depth > 1) sb.append("Caused by: ")
            sb.append(cur.toString()).append('\n')
            cur.stackTrace.withIndex()
                .filter { (i, f) -> i < 6 || (f.className.startsWith(OUR_PACKAGE) && i < 40) }
                .take(14)
                .forEach { sb.append("  at ").append(it.value).append('\n') }
            cur = cur.cause
        }
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------ detectors

    private object LogAppender : AbstractAppender("CedDiag", null, null, true, Property.EMPTY_ARRAY) {
        override fun append(e: LogEvent) {
            if (inDiag.get() || e.loggerName == "CedDiag") return
            val level = e.level
            val text = e.message?.formattedMessage ?: return
            val thrown = e.thrown
            // The saved Hypixel pack is named cedclient/hypixel_pack, but its broken files are Hypixel's, not ours.
            val ours = e.loggerName.contains("cedclient", ignoreCase = true) ||
                (text.contains("cedclient", ignoreCase = true) && !text.contains("cedclient/hypixel_pack")) || mentionsUs(thrown)
            val isError = level.isMoreSpecificThan(Level.ERROR)
            // Mixin problems are only WARN, but a broken injector means a feature silently does nothing.
            val mixinWarn = level == Level.WARN && e.loggerName.contains("mixin", ignoreCase = true)
            if (!ours || !(isError || mixinWarn)) return
            val source = "log ${e.loggerName.substringAfterLast('.')}"
            if (thrown != null) error(source, text, thrown)
            else record(if (isError) Kind.ERROR else Kind.WARN, source, text, null, null)
        }
    }

    private fun installLogAppender() {
        try {
            val ctx = LogManager.getContext(false) as LoggerContext
            LogAppender.start()
            ctx.configuration.rootLogger.addAppender(LogAppender, Level.WARN, null)
            ctx.updateLoggers()
        } catch (t: Throwable) {
            println("[CedDiag] log appender not installed: $t")
        }
    }

    private fun installUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            if (mentionsUs(t) || thread.name.startsWith("CedClient")) error("uncaught on ${thread.name}", t.toString(), t)
            previous?.uncaughtException(thread, t)
        }
    }

    /** The game can't send while it crashes, so crash reports are picked up on the next launch. */
    private fun checkCrashReports() {
        try {
            val seen = props.getProperty("lastCrashSeen")?.toLongOrNull()
            setProp("lastCrashSeen", System.currentTimeMillis().toString())
            if (seen == null) return // first launch with Diag: don't dig up old crashes
            val dir = FabricLoader.getInstance().gameDir.resolve("crash-reports")
            if (!Files.isDirectory(dir)) return
            Files.list(dir).use { files ->
                files.filter { Files.getLastModifiedTime(it).toMillis() > seen }.sorted().limit(3).forEach { file ->
                    val text = Files.readString(file)
                    if (!text.contains("cedclient", ignoreCase = true)) return@forEach
                    val lines = text.lines()
                    val descAt = lines.indexOfFirst { it.startsWith("Description:") }
                    val desc = lines.getOrNull(descAt)?.removePrefix("Description:")?.trim() ?: "crash"
                    // The stack trace right after the description, keeping our frames.
                    val trace = lines.drop(descAt + 1).dropWhile { it.isBlank() }.takeWhile { it.isNotBlank() }
                        .withIndex().filter { (i, l) -> i < 8 || (OUR_PACKAGE in l && i < 60) }.take(20)
                        .joinToString("\n") { it.value }
                    record(Kind.CRASH, "crash ${file.fileName}", desc, trace, file.fileName.toString())
                }
            }
        } catch (t: Throwable) {
            println("[CedDiag] crash report check failed: $t")
        }
    }

    /** Watchdog + sender. The game thread is only read (its stack), never touched. */
    private fun backgroundLoop() {
        var nextSend = System.currentTimeMillis() + SEND_EVERY_MS
        var freezeReported = false
        while (true) {
            try {
                TimeUnit.SECONDS.sleep(1)
                val now = System.currentTimeMillis()
                val stuck = lastTickAt != 0L && Minecraft.getInstance().level != null && now - lastTickAt > FREEZE_MS
                if (stuck && !freezeReported) {
                    freezeReported = true
                    val stack = mainThread?.stackTrace.orEmpty()
                    val ours = stack.firstOrNull { it.className.startsWith(OUR_PACKAGE) }
                    if (ours != null) {
                        val text = stack.withIndex().filter { (i, f) -> i < 10 || (f.className.startsWith(OUR_PACKAGE) && i < 60) }
                            .take(20).joinToString("\n") { "  at ${it.value}" }
                        record(Kind.FREEZE, "freeze", "game thread stuck ${(now - lastTickAt) / 1000}s", text, ours.toString())
                    }
                } else if (!stuck) freezeReported = false
                if (now >= nextSend) {
                    nextSend = now + SEND_EVERY_MS
                    flush()
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------ sending

    @Synchronized
    private fun flush() {
        try {
            val url = props.getProperty("endpoint")?.trim()?.takeIf { it.isNotEmpty() } ?: ENDPOINT // endpoint= for a local test Worker
            if (!sendEnabled() || messagesSent >= MAX_MESSAGES) return
            val pending = synchronized(issues) { issues.values.filter { it.count > it.sentCount }.take(10) }
            if (pending.isEmpty()) return
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildMessage(pending)))
                .build()
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() in 200..299) {
                messagesSent++
                synchronized(issues) { pending.forEach { it.sentCount = it.count } }
            }
        } catch (_: Throwable) {
            // A dead endpoint must never affect the game; pending issues retry next round.
        }
    }

    /** Plain report data; the Worker turns it into the Discord message (so the client can't post arbitrary content). */
    private fun buildMessage(pending: List<Issue>): String {
        val loader = FabricLoader.getInstance()
        fun version(id: String) = loader.getModContainer(id).map { it.metadata.version.friendlyString }.orElse("?")
        val time = DateTimeFormatter.ofPattern("HH:mm:ss")
        val user = Minecraft.getInstance().user
        val list = JsonArray()
        for (issue in pending) {
            list.add(JsonObject().apply {
                addProperty("kind", issue.kind.name)
                addProperty("source", issue.source.take(120))
                addProperty("message", issue.message.take(800))
                issue.detail?.let { addProperty("detail", it.take(3000)) }
                addProperty("count", issue.count)
                addProperty("again", issue.sentCount > 0)
                addProperty("first", LocalTime.ofInstant(Instant.ofEpochMilli(issue.first), ZoneId.systemDefault()).format(time))
            })
        }
        return JsonObject().apply {
            addProperty("uuid", user?.profileId?.toString() ?: "")
            addProperty("name", user?.name ?: "")
            addProperty("version", version("cedclient"))
            addProperty("mc", version("minecraft"))
            addProperty("island", IslandState.island?.toString() ?: "-")
            addProperty("os", System.getProperty("os.name"))
            add("issues", list)
        }.toString()
    }

    // ------------------------------------------------------------------ config

    private fun sendEnabled() = props.getProperty("sendReports", "true").trim().equals("true", ignoreCase = true)

    private fun configPath() = FabricLoader.getInstance().configDir.resolve(CONFIG_NAME)

    private fun loadProps() {
        try {
            val path = configPath()
            if (Files.exists(path)) Files.newBufferedReader(path).use { props.load(it) }
            if (props.getProperty("sendReports") == null) props.setProperty("sendReports", "true")
            saveProps()
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun setProp(key: String, value: String) {
        props.setProperty(key, value)
        saveProps()
    }

    private fun saveProps() {
        try {
            Files.newBufferedWriter(configPath()).use {
                props.store(it, " CedClient bug reports. sendReports=false turns sending off.\n" +
                    " Sent when something in CedClient breaks: your Minecraft name, mod + MC version, island, OS and the error.\n" +
                    " endpoint= sends to another URL instead (testing only; leave unset).")
            }
        } catch (_: Throwable) {
        }
    }
}

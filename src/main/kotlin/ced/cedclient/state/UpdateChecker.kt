package ced.cedclient.state

import ced.cedclient.config.CedPaths
import ced.cedclient.ui.UpdateScreen
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.concurrent.thread

/**
 * Checks GitHub for a newer CedClient release at launch and shows [UpdateScreen] on the title screen (after any
 * other mod's screen opened there is closed). "Update" downloads the jar matching this install (full / Legit /
 * 26.2) next to the running one as *.cedupdate, and a small helper process swaps it in once the game has exited.
 */
object UpdateChecker {
    private const val REPO = "CedNath/CedClient"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PENDING_SUFFIX = ".cedupdate"

    class Release(val version: String, val name: String, val notes: String, val pageUrl: String, val assetName: String?, val assetUrl: String?)

    @Volatile var latest: Release? = null
        private set

    /**
     * Set once a button on the update screen was used (Remind me later / Skip / Update): not shown again this
     * launch. If another mod's screen replaces ours first, this stays false and ours comes back afterwards.
     */
    var handled = false

    /** Ticks the title screen has been up on its own; we wait a bit so other mods' launch screens go first. */
    private var titleTicks = 0

    val currentVersion: String =
        // The jar version is "4.5-26.1.2": CedClient version, then the Minecraft version.
        FabricLoader.getInstance().getModContainer("cedclient").map { it.metadata.version.friendlyString }.orElse("0").substringBefore('-')

    /** The running jar, or null in a dev environment (classes folder: nothing to replace). */
    val ownJar: Path? by lazy {
        runCatching { FabricLoader.getInstance().getModContainer("cedclient").orElse(null)?.origin?.paths?.firstOrNull { it.toString().endsWith(".jar") } }.getOrNull()
    }

    val canInstall: Boolean get() = ownJar != null && latest?.assetUrl != null

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    fun start() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
        thread(name = "CedClient update check", isDaemon = true) {
            runCatching { check() }.onFailure { println("[CedClient] Update check failed: ${it.message}") }
        }
    }

    /** Opens the update screen once the title screen has been up for half a second (not another mod's screen). */
    private fun tick(mc: Minecraft) {
        if (handled) return
        if (mc.screen !is TitleScreen) { titleTicks = 0; return }
        if (++titleTicks < 10) return
        val release = latest ?: return
        if (release.version == skippedVersion()) { handled = true; return }
        mc.setScreen(UpdateScreen(mc.screen, release))
    }

    private fun get(url: String, accept: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).header("Accept", accept).header("User-Agent", "CedClient").build(),
            HttpResponse.BodyHandlers.ofString()
        )

    /** GitHub's API first; it only allows 60 calls an hour per connection (shared with other mods), so the release feed is the fallback. */
    private fun check() {
        val response = get(API, "application/vnd.github+json")
        if (response.statusCode() != 200) {
            checkFeed()
            return
        }
        val json = JsonParser.parseString(response.body()).asJsonObject
        val version = json["tag_name"]?.asString?.removePrefix("v") ?: return
        if (!isNewer(version, currentVersion)) return

        val wanted = assetNameFor(version)
        val asset = json.getAsJsonArray("assets")?.map { it.asJsonObject }?.firstOrNull { it["name"]?.asString == wanted }
        latest = Release(
            version = version,
            name = json["name"]?.asString ?: "CedClient $version",
            notes = json["body"]?.asString.orEmpty(),
            pageUrl = json["html_url"]?.asString ?: "https://github.com/$REPO/releases/latest",
            assetName = asset?.str("name"),
            assetUrl = asset?.str("browser_download_url"),
        )
    }

    private fun JsonObject.str(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString

    /**
     * The releases Atom feed (no rate limit): newest entry's tag, title and notes (HTML, turned back into the same
     * simple markdown the API gives). The jar link follows the release naming, as the feed has no assets.
     */
    private fun checkFeed() {
        val response = get("https://github.com/$REPO/releases.atom", "application/atom+xml")
        if (response.statusCode() != 200) return
        val entry = Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL).find(response.body())?.groupValues?.get(1) ?: return
        val page = Regex("""<link[^>]*href="([^"]+/releases/tag/[^"]+)"""").find(entry)?.groupValues?.get(1) ?: return
        val version = page.substringAfterLast('/').removePrefix("v")
        if (!isNewer(version, currentVersion)) return
        val title = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(entry)?.groupValues?.get(1)?.let(::unescape)
        val html = Regex("""<content[^>]*>(.*?)</content>""", RegexOption.DOT_MATCHES_ALL).find(entry)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        val asset = assetNameFor(version)
        latest = Release(
            version = version,
            name = title ?: "CedClient $version",
            notes = htmlToText(html),
            pageUrl = page,
            assetName = asset,
            assetUrl = "https://github.com/$REPO/releases/download/v$version/$asset",
        )
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

    private fun htmlToText(html: String): String = unescape(
        html.replace(Regex("""<h[1-6][^>]*>"""), "\n## ").replace(Regex("""</h[1-6]>"""), "\n")
            .replace(Regex("""<li[^>]*>"""), "- ").replace("</li>", "\n")
            .replace(Regex("""<br\s*/?>|</p>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
    ).replace(Regex("""\n\s*\n(?=- )"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n").trim()

    /** "CedClient-4.6-26.1.2-Legit.jar" style name for this install: same Minecraft version, Legit if this jar is. */
    private fun assetNameFor(version: String): String {
        val mc = FabricLoader.getInstance().getModContainer("minecraft").map { it.metadata.version.friendlyString }.orElse("")
        // The build itself decides (the file name lies: Modrinth's is "-legit", gc copies are "-cheat").
//#if CHEAT
        val legit = false
//#else
//$        val legit = true
//#endif
        return "CedClient-$version-$mc${if (legit) "-Legit" else ""}.jar"
    }

    /** Dotted numeric compare: 4.10 > 4.9, 4.5.1 > 4.5. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ---------------------------------------------------------------- skip

    private val skipFile: File by lazy { CedPaths.file("update_skip.txt") }
    private fun skippedVersion(): String? = runCatching { skipFile.readText().trim() }.getOrNull()
    fun skip(version: String) { runCatching { skipFile.parentFile?.mkdirs(); skipFile.writeText(version) } }

    // ---------------------------------------------------------------- install

    /**
     * Downloads the new jar next to the running one (as *.cedupdate, which Fabric ignores) and starts a helper that
     * waits for this game to exit, then removes the old jar and renames the new one. [progress] gets 0..1 (or -1
     * when the size is unknown); returns null on success or an error message.
     */
    fun install(progress: (Float) -> Unit): String? {
        val release = latest ?: return "No update found"
        val old = ownJar ?: return "Not running from a jar"
        val url = release.assetUrl ?: return "No download for this version"
        val mods = old.parent
        val final = mods.resolve(release.assetName!!)
        val pending = mods.resolve(release.assetName + PENDING_SUFFIX)
        return try {
            val request = HttpRequest.newBuilder(URI(url)).header("User-Agent", "CedClient").build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) return "Download failed (HTTP ${response.statusCode()})"
            val total = response.headers().firstValueAsLong("Content-Length").orElse(-1)
            val tmp = mods.resolve(release.assetName + ".part")
            response.body().use { input ->
                Files.newOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
            Files.move(tmp, pending, StandardCopyOption.REPLACE_EXISTING)
            startSwapHelper(old, pending, final)
            null
        } catch (e: Exception) {
            "Download failed: ${e.message}"
        }
    }

    private fun startSwapHelper(old: Path, pending: Path, final: Path) {
        val pid = ProcessHandle.current().pid()
        val windows = System.getProperty("os.name").lowercase().contains("win")
        // Single-quoted in both shells: PowerShell doubles a quote, sh closes, escapes and reopens it.
        fun q(p: Path) = p.toAbsolutePath().toString().replace("'", if (windows) "''" else "'\\''")
        val command = if (windows) listOf(
            "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
            "Wait-Process -Id $pid -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1; " +
                "Remove-Item -LiteralPath '${q(old)}' -Force -ErrorAction SilentlyContinue; " +
                "Move-Item -LiteralPath '${q(pending)}' -Destination '${q(final)}' -Force"
        ) else listOf(
            "sh", "-c",
            "while kill -0 $pid 2>/dev/null; do sleep 1; done; rm -f '${q(old)}'; mv -f '${q(pending)}' '${q(final)}'"
        )
        ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }
}

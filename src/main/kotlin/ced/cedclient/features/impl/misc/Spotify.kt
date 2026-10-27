package ced.cedclient.features.impl.misc

import ced.cedclient.utils.Keys
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.render.nvg.Image
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Color
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Windows only. Reads the playing track from the Spotify or Apple Music desktop window title and controls playback
 * with the system media keys (they work for any player), on keybinds, from clickable chat buttons (/cc spotify next | prev | pause | share)
 * or from the now-playing HUD, whose buttons can be clicked while chat is open.
 */
object Spotify : Module(
    "Music",
    Category.Misc,
    "Shows the current Spotify or Apple Music track in chat and controls playback with media keys."
), HudElement {
    private val mc get() = Minecraft.getInstance()

    private val source = DropdownSetting("Player", listOf("Auto", "Spotify", "Apple Music"), "Auto", "Which app to read the track from.")
    private val songMessage = BooleanSetting("Song Change Message", true, "Shows a chat message with clickable controls when the track changes.")
    private val prettyParty = BooleanSetting("Pretty Song Messages", true, "Party \"Now playing: ...\" lines (yours and other players') are shown with the artist and song in their own colours.")
    private val listenButton = BooleanSetting("Listen Button", true, "A [Listen] button on those lines that opens the song in your music app (Apple Music or Spotify, whichever you listen with). A song can't be started for you remotely, so it opens the search for it.").withDependency { prettyParty.value }
    private val chatArtistColor = ColorSetting("Chat Artist Color", Color(85, 255, 255), "Artist in party song lines.").withDependency { prettyParty.value }
    private val chatSongColor = ColorSetting("Chat Song Color", Color(255, 215, 90), "Song in party song lines.").withDependency { prettyParty.value }
    private val keybinds = BooleanSetting("Media Keybinds", true, "Use the keybinds below (outside of menus).")
    private val prevKey = KeybindSetting("Previous Key", Keys.UNKNOWN, "Previous track.")
    private val pauseKey = KeybindSetting("Play / Pause Key", Keys.UNKNOWN, "Play or pause.")
    private val nextKey = KeybindSetting("Next Key", Keys.UNKNOWN, "Next track.")

    // Now-playing HUD (all in the "HUD Options" popup).
    private val showHud = BooleanSetting("Show HUD", true, "A now-playing card. Open chat to click its buttons.")
    private val visibility = DropdownSetting("Visible", listOf("Always", "Chat Open Only"), "Always", "When the card is shown.")
    private val clickInventory = BooleanSetting("Click In Inventory", true, "Also lets you click the buttons while an inventory or container screen is open, not only chat. (Chat Open Only then counts those screens too.)")
    private val style = DropdownSetting("Style", listOf("Player", "Classic", "Compact"), "Player", "Player: cover, song, artist and a wave bar you can click or drag to seek (buttons show while chat is open). Classic: song, artist and buttons stacked. Compact: one line.")
    private val barColor = ColorSetting("Wave Color", Color(255, 120, 40), "The played part of the Player style's wave bar.")
    private val showIcon = BooleanSetting("Icon", true, "Little equalizer icon (still while paused).")
    private val showArtist = BooleanSetting("Artist Line", true, "Artist on its own line (Card style).")
    private val showProgress = BooleanSetting("Progress Bar", true, "A thin line at the bottom of the card showing how far into the song you are. Runs a small background PowerShell (the Windows media session).")
    private val maxWidth = NumberSetting("Text Width", 150.0, 60.0, 400.0, 10.0, "Longest the song / artist text gets before it scrolls or is cut off.")
    private val scroll = BooleanSetting("Scroll Long Titles", true, "Scroll text that does not fit (off: cut off with ...).")
    private val scrollSpeed = NumberSetting("Scroll Speed", 40.0, 10.0, 200.0, 5.0, "How fast long titles scroll, in pixels per second.").withDependency { scroll.value }
    private val textSize = NumberSetting("Text Size", 10.0, 6.0, 24.0, 1.0, "Size of the text (you can also scale the whole HUD in the HUD editor).")
    private val showButtons = BooleanSetting("Buttons", true, "Previous / play-pause / next. Clickable while chat is open.")
    private val showShare = BooleanSetting("Share Button", true, "Puts \"Now playing: ...\" in your chat box.").withDependency { showButtons.value }
    private val showModes = BooleanSetting("Shuffle / Repeat", true, "Shuffle and repeat buttons. Spotify only (Apple Music ignores them); they go through the Windows media session (needs PowerShell), not the media keys.").withDependency { showButtons.value }
    private val buttonSize = NumberSetting("Button Size", 12.0, 8.0, 24.0, 1.0, "Size of the buttons.").withDependency { showButtons.value }
    private val titleColor = ColorSetting("Song Color", Color(255, 255, 255), "Song title.")
    private val artistColor = ColorSetting("Artist Color", Color(180, 180, 190), "Artist line.")
    private val accentColor = ColorSetting("Accent Color", Color(30, 215, 96), "Icon and buttons.")
    private val hoverColor = ColorSetting("Hover Color", Color(255, 255, 255), "A button under your cursor.")
    private val dimPaused = BooleanSetting("Dim When Paused", true, "Fades the card and shows a play button while nothing is playing.")
    private val showCover = BooleanSetting("Cover Art", true, "Shows the album cover. Looked up by song name on Apple's public iTunes search (itunes.apple.com), so it can differ from the cover in Spotify.")
    private val coverSize = NumberSetting("Cover Size", 32.0, 16.0, 96.0, 2.0, "Size of the cover.").withDependency { showCover.value }
    private val coverSide = DropdownSetting("Cover Side", listOf("Left", "Right"), "Left", "Which side of the card the cover is on.").withDependency { showCover.value }
    private val coverRound = BooleanSetting("Rounded Cover", true, "Rounded corners on the cover.").withDependency { showCover.value }
    private val background = NumberSetting("Background", 0.0, 0.0, 1.0, 0.1, "Opacity of the card behind the text (0 = none).")

    private interface User32 : Library {
        fun interface EnumProc : Callback { fun callback(hwnd: Pointer, lParam: Pointer?): Boolean }
        fun EnumWindows(proc: EnumProc, lParam: Pointer?): Boolean
        fun IsWindowVisible(hwnd: Pointer): Boolean
        fun GetWindowTextW(hwnd: Pointer, buf: CharArray, max: Int): Int
        fun GetClassNameW(hwnd: Pointer, buf: CharArray, max: Int): Int
        fun GetWindowThreadProcessId(hwnd: Pointer, pid: IntByReference): Int
        fun keybd_event(vk: Byte, scan: Byte, flags: Int, extra: Pointer?)
    }

    private val user32: User32? by lazy {
        if (!System.getProperty("os.name").startsWith("Windows")) null
        else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    private const val VK_NEXT = 0xB0
    private const val VK_PREV = 0xB1
    private const val VK_PLAY_PAUSE = 0xB3
    private const val KEYUP = 2

    @Volatile private var track: String? = null
    /** The player's window shows a song right now (false while paused or closed; [track] then keeps the last one). */
    @Volatile private var playing = false

    /** "Artist - Song" of the track playing now (last one while paused), null if none was found. */
    @JvmStatic fun currentTrack(): String? = if (isEnabled) track else null
    private var announced: String? = null

    // Album cover: looked up once per track on a worker thread, turned into an NVG image on the render thread.
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build() }
    private var coverRequested: String? = null
    @Volatile private var coverBytes: ByteArray? = null
    @Volatile private var coverBytesFor: String? = null
    private var coverImage: Image? = null
    private var coverImageFor: String? = null

    // Shuffle / repeat: no media keys for them, so a small PowerShell script talks to the Windows media session.
    @Volatile private var shuffleOn = false
    /** 0 off, 1 repeat one, 2 repeat all. */
    @Volatile private var repeatMode = 0
    @Volatile private var ctlBusy = false
    @Volatile private var queuedAction: String? = null
    /** The song comes from Apple Music: it ignores shuffle / repeat commands, so those buttons are hidden. */
    @Volatile private var appleActive = false
    private var modesFor: String? = null
    private val ctlScript by lazy {
        val f = Files.createTempFile("cedclient-spotify", ".ps1").toFile().apply { deleteOnExit() }
        Spotify::class.java.getResourceAsStream("/assets/cedclient/spotify_ctl.ps1")!!.use { f.writeBytes(it.readBytes()) }
        f
    }
    private val ctlState = Regex("""shuffle=(\d) repeat=(\d)""")

    private fun psExe() = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\WindowsPowerShell\\v1.0\\powershell.exe").takeIf { it.exists() }?.absolutePath ?: "powershell"

    // Apple Music's main window title is just "Apple Music" (only its miniplayer carries the song), so for Auto / Apple Music the
    // track also comes from the media session, through a long-running "watch" script that prints a line a second.
    @Volatile private var smtcTrack: String? = null
    @Volatile private var smtcPlaying = false
    /** Song position (ms, as of [smtcUpdatedMs]), its length, and whether the session is Apple Music. */
    @Volatile private var smtcPosMs = 0L
    @Volatile private var smtcEndMs = 0L
    @Volatile private var smtcUpdatedMs = 0L
    @Volatile private var smtcApple = false
    private var watcher: Process? = null
    private var watcherApp = ""

    // Progress bar seeking: press on the bar, drag, release to jump there.
    @Volatile private var seekHoldUntil = 0L
    private var seeking = false
    private var seekFrac = 0f
    /** The bar's hit area of the last frame in GUI coordinates: x, y, width, height (null when it can't be seeked). */
    private var seekBar: FloatArray? = null

    private fun ensureWatcher() {
        if (user32 == null) return
        val app = when (source.value) { "Spotify" -> "Spotify"; "Apple Music" -> "AppleMusic|iTunes"; else -> "Spotify|AppleMusic|iTunes" }
        if (watcher?.isAlive == true && watcherApp == app) return
        if (watcher != null) stopWatcher() // the Player setting changed
        runCatching {
            watcherApp = app
            val p = ProcessBuilder(psExe(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", ctlScript.absolutePath, "-Action", "watch", "-App", app)
                .redirectErrorStream(true).start()
            p.outputStream.close()
            watcher = p
            Thread({
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        val f = line.split('\t')
                        if (f.size >= 6 && f[0] == "track") {
                            val artist = f[1].trim()
                            val title = f[2].trim()
                            smtcTrack = if (title.isBlank()) null else if (artist.isBlank()) title else "$artist - $title"
                            smtcPlaying = f[3] == "Playing"
                            // Just after a seek the player still reports the old spot for a moment.
                            if (f.size >= 10 && System.currentTimeMillis() >= seekHoldUntil) {
                                smtcPosMs = f[6].toLongOrNull() ?: 0L
                                smtcEndMs = f[7].toLongOrNull() ?: 0L
                                smtcUpdatedMs = f[8].toLongOrNull() ?: 0L
                                smtcApple = f[9].contains("Apple", ignoreCase = true) || f[9].contains("iTunes", ignoreCase = true)
                            }
                        } else { smtcTrack = null; smtcPlaying = false; smtcEndMs = 0L }
                    }
                }
            }, "CedClient-Spotify-watch").apply { isDaemon = true; start() }
        }
    }

    private fun stopWatcher() { watcher?.destroyForcibly(); watcher = null; smtcTrack = null; smtcPlaying = false; smtcEndMs = 0L }

    /** Runs the script with [action] ("state", "shuffle" or "repeat") and keeps the state it prints. One at a time. */
    private fun runCtl(action: String) {
        if (user32 == null) return
        if (ctlBusy) { queuedAction = action; return } // runs right after the current one
        ctlBusy = true
        // Shuffle / repeat: Apple Music takes the commands but ignores them. Seeking goes to whichever player the HUD is reading.
        val app = if (action.startsWith("seek:") && watcherApp.isNotEmpty()) watcherApp else "Spotify"
        Thread({
            var outText = ""
            try {
                // Full path: a launcher's trimmed PATH may not include the PowerShell folder.
                // "seek:<ms>" carries the position along with the action.
                val args = mutableListOf(psExe(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", ctlScript.absolutePath, "-Action", action.substringBefore(':'), "-App", app)
                if (action.startsWith("seek:")) args += listOf("-PositionMs", action.substringAfter(':'))
                val p = ProcessBuilder(args).redirectErrorStream(true).start()
                p.outputStream.close()
                // Read on a timer: a stuck script must not keep the buttons locked.
                val reader = Thread({ runCatching { outText = p.inputStream.bufferedReader().readText() } }, "CedClient-Spotify-ctl-read").apply { isDaemon = true; start() }
                if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
                reader.join(2000)
                val out = outText
                println("[Spotify] $action -> ${out.trim()}")
                ctlState.find(out)?.let { shuffleOn = it.groupValues[1] == "1"; repeatMode = it.groupValues[2].toInt() }
            } catch (e: Throwable) {
                println("[Spotify] $action failed: $e")
            } finally {
                ctlBusy = false
                queuedAction?.let { queuedAction = null; runCtl(it) }
            }
        }, "CedClient-Spotify-ctl").apply { isDaemon = true; start() }
    }

    private fun fetchCover(t: String) {
        val (artist, song) = split(t)
        Thread({
            val bytes = runCatching {
                val term = URLEncoder.encode("$artist $song".trim(), Charsets.UTF_8)
                val search = http.send(HttpRequest.newBuilder(URI.create("https://itunes.apple.com/search?term=$term&entity=song&limit=1")).timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString())
                val art = JsonParser.parseString(search.body()).asJsonObject.getAsJsonArray("results").firstOrNull()?.asJsonObject?.get("artworkUrl100")?.asString
                    ?: return@runCatching null
                http.send(HttpRequest.newBuilder(URI.create(art.replace("100x100", "300x300"))).timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofByteArray()).body()
            }.getOrNull()
            coverBytes = bytes
            coverBytesFor = t
        }, "CedClient-Spotify-cover").apply { isDaemon = true; start() }
    }
    private val down = HashSet<Int>()
    private var poller: Thread? = null

    init {
        addSettings(source, songMessage, prettyParty, listenButton, chatArtistColor, chatSongColor, keybinds, prevKey, pauseKey, nextKey)
        val columns = listOf(
            listOf(
                SettingsPopup.Section("Card", listOf(showHud, visibility, clickInventory, style, showIcon, showArtist, showProgress, maxWidth, scroll, scrollSpeed, textSize)),
                SettingsPopup.Section("Buttons", listOf(showButtons, showModes, showShare, buttonSize)),
            ),
            listOf(
                SettingsPopup.Section("Cover", listOf(showCover, coverSize, coverSide, coverRound)),
                SettingsPopup.Section("Look", listOf(titleColor, artistColor, accentColor, barColor, hoverColor, dimPaused, background)),
            ),
        )
        addSettings(ActionSetting("HUD Options", "The now-playing card: layout, buttons, colours.") {
            (mc.gui.screen() as? ClickGUI)?.openPopup(SettingsPopup("Spotify HUD", columns))
        })
        val all = columns.flatten().flatMap { it.settings }
        addSettings(*all.toTypedArray())
        all.forEach { it.inPopup = true }

        // Chat clicks come through ChatScreenMenuMixin; inventories through the screen's own click event (false = swallow the click).
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*>) ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
                !(clickInventory.value && onChatClick(event.x(), event.y(), event.button()))
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) return@register
            val t = track
            if (showCover.value && showHud.value && t != null && t != coverRequested) { coverRequested = t; fetchCover(t) }
            if (showHud.value && showButtons.value && showModes.value && !appleActive && t != null && t != modesFor) { modesFor = t; runCtl("state") }
            if (songMessage.value && t != null && t != announced) {
                announced = t
                mc.player?.sendSystemMessage(songLine(t))
            }
            if (!keybinds.value || mc.gui.screen() != null) { down.clear(); return@register }
            if (pressed(prevKey.value)) press(VK_PREV)
            if (pressed(pauseKey.value)) press(VK_PLAY_PAUSE)
            if (pressed(nextKey.value)) press(VK_NEXT)
        }
    }

    /** True once per key press. */
    private fun pressed(key: Int): Boolean {
        if (key < 0) return false
        if (!Keys.isDown(key)) { down.remove(key); return false }
        return down.add(key)
    }

    private fun press(vk: Int) {
        val u = user32 ?: return
        u.keybd_event(vk.toByte(), 0, 0, null)
        u.keybd_event(vk.toByte(), 0, KEYUP, null)
    }

    private fun button(label: String, command: String): Component =
        Component.literal(label).withStyle(Style.EMPTY.withClickEvent(ClickEvent.RunCommand(command)))

    private val NOW_PLAYING = Regex("""^(Party > .*?: )Now playing: (.+)$""")

    /**
     * Called from ChatComponentMixin for every chat line: a party "Now playing: Artist - Song" (PartyCommands' !song, or
     * the Share button) keeps its sender part and gets the song re-drawn in colours with a [Listen] button.
     */
    @JvmStatic
    fun transformChat(original: Component): Component {
        val text = original.string
        if (!isEnabled || !prettyParty.value) {
            if ("Now playing:" in text) ced.cedclient.utils.Debug.line("Music", "song line left plain: Spotify enabled=$isEnabled, Pretty Song Messages=${prettyParty.value}")
            return original
        }
        // Hypixel sends party lines as one literal with "§" colour codes inside, so match on the text without them.
        val m = NOW_PLAYING.matchEntire(text.replace(Regex("§."), "")) ?: run {
            if ("Now playing:" in text) ced.cedclient.utils.Debug.line("Music", "song line didn't match: \"$text\"")
            return original
        }
        val song = m.groupValues[2].trim()
        val cut = m.groupValues[1].length
        val out = Component.empty()
        if ('§' in text) out.append(Component.literal(text.substring(0, text.indexOf("Now playing:")))) // the font draws the codes
        else {
            var pos = 0
            original.visit({ style, seg ->
                if (pos < cut) out.append(Component.literal(seg.substring(0, minOf(seg.length, cut - pos))).withStyle(style))
                pos += seg.length
                java.util.Optional.empty<Unit>()
            }, Style.EMPTY)
        }
        val accent = accentColor.value.rgba and 0xFFFFFF
        val (artist, title) = split(song)
        out.append(Component.literal("♫ ").withColor(accent))
        if (artist.isNotEmpty()) {
            out.append(Component.literal(artist).withColor(chatArtistColor.value.rgba and 0xFFFFFF))
            out.append(Component.literal(" - ").withColor(0x777777))
        }
        out.append(Component.literal(title).withColor(chatSongColor.value.rgba and 0xFFFFFF))
        if (listenButton.value) {
            // Opens the search in the app you listen with yourself (Apple Music when that's the player, else Spotify).
            val apple = source.value == "Apple Music" || (source.value == "Auto" && appleActive)
            val query = java.net.URLEncoder.encode(song, Charsets.UTF_8).replace("+", "%20")
            val url = if (apple) "https://music.apple.com/search?term=$query" else "https://open.spotify.com/search/$query"
            val service = if (apple) "Apple Music" else "Spotify"
            out.append(Component.literal(" "))
            out.append(Component.literal("[Listen]").withStyle(
                Style.EMPTY.withColor(accent)
                    .withClickEvent(ClickEvent.OpenUrl(java.net.URI.create(url)))
                    .withHoverEvent(net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Open \"$song\" in $service")))
            ))
        }
        ced.cedclient.utils.Debug.line("Music", "restyled a party song line: $song")
        return out
    }

    /** The song change line; its buttons follow the HUD's button settings (Buttons, Shuffle / Repeat, Share Button). */
    private fun songLine(t: String): Component {
        val line = ChatPrefix.msgFor("Music", "§a♫ §f$t ")
        if (!showButtons.value) return line
        val modes = showModes.value && !appleActive
        val parts = ArrayList<Component>()
        if (modes) parts += button("§7[§fShuffle§7]", "/cc spotify shuffle")
        parts += button("§7[§f⏮§7]", "/cc spotify prev")
        parts += button("§7[§f⏯§7]", "/cc spotify pause")
        parts += button("§7[§f⏭§7]", "/cc spotify next")
        if (modes) parts += button("§7[§fRepeat§7]", "/cc spotify repeat")
        if (showShare.value) parts += button("§7[§bShare§7]", "/cc spotify share")
        parts.forEachIndexed { i, c -> if (i > 0) line.append(Component.literal(" ")); line.append(c) }
        return line
    }

    override fun onEnable() {
        if (user32 == null || poller?.isAlive == true) return
        announced = null
        poller = Thread({
            while (isEnabled) {
                runCatching {
                    // The watcher is Apple Music's track source and everyone's song position (Progress Bar).
                    if (source.value == "Spotify" && !showProgress.value && style.value != "Player") { if (watcher != null) stopWatcher() } else ensureWatcher()
                    val found = readTrack()
                    if (found != null) track = found
                    playing = found != null
                }
                Thread.sleep(1000)
            }
        }, "CedClient-Spotify-poll").apply { isDaemon = true; start() }
    }

    private fun wants(cmd: String): Boolean {
        val spotify = cmd.contains("Spotify", ignoreCase = true)
        val apple = cmd.contains("AppleMusic", ignoreCase = true) || cmd.contains("iTunes", ignoreCase = true)
        return when (source.value) { "Spotify" -> spotify; "Apple Music" -> apple; else -> spotify || apple }
    }

    /** The track from the player's window title, null while paused (the title then reads just the app name) or closed. */
    private fun readTrack(): String? {
        val u = user32 ?: return null
        var found: String? = null
        u.EnumWindows(User32.EnumProc { hwnd, _ ->
            if (!u.IsWindowVisible(hwnd)) return@EnumProc true
            val title = CharArray(256).also { u.GetWindowTextW(hwnd, it, 256) }.concatToString().trimEnd('\u0000')
            if (title.isBlank() || title in IDLE_TITLES) return@EnumProc true
            val pid = IntByReference().also { u.GetWindowThreadProcessId(hwnd, it) }.value
            val cmd = ProcessHandle.of(pid.toLong()).flatMap { it.info().command() }.orElse("")
            if (!wants(cmd)) return@EnumProc true
            // Both apps have helper windows ("PopupHost", Chromium ones ...); only a song window reads "Artist - Song".
            if (!title.contains(" - ") && !title.contains(" — ")) return@EnumProc true
            found = title.replace(" — ", " - "); false
        }, null)
        // Apple Music: the media session wins (its window titles are no use); in Auto it only fills in when no window had a song.
        val st = smtcTrack
        if (source.value != "Spotify" && st != null && (source.value == "Apple Music" || found == null)) {
            appleActive = smtcApple
            if (smtcPlaying) return st
            track = st // paused: keep showing it, dimmed
            return null
        }
        appleActive = false
        return found
    }

    private val IDLE_TITLES = setOf("Spotify", "Spotify Free", "Spotify Premium", "Apple Music", "iTunes", "PopupHost", "Default IME", "MSCTFIME UI")

    override fun onDisable() { poller = null; down.clear(); buttons = emptyList(); stopWatcher() }

    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("spotify")
            .then(ClientCommands.literal("next").executes { if (isEnabled) press(VK_NEXT); 1 })
            .then(ClientCommands.literal("prev").executes { if (isEnabled) press(VK_PREV); 1 })
            .then(ClientCommands.literal("pause").executes { if (isEnabled) press(VK_PLAY_PAUSE); 1 })
            .then(ClientCommands.literal("shuffle").executes { if (isEnabled) runCtl("shuffle"); 1 })
            .then(ClientCommands.literal("repeat").executes { if (isEnabled) runCtl("repeat"); 1 })
            .then(ClientCommands.literal("share").executes { ctx ->
                val t = track
                if (!isEnabled || t == null) ctx.source.sendFeedback(ChatPrefix.msgFor("Music", "§7No track has been detected yet."))
                else share(t)
                1
            })

    private fun share(t: String) = mc.gui.setScreen(ChatScreen("Now playing: $t", false))

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------

    private enum class Act { SHUFFLE, PREV, PAUSE, NEXT, REPEAT, SHARE }
    private class Btn(val act: Act, val x: Float, val y: Float, val w: Float, val h: Float)

    /** Clickable buttons of the last frame, in GUI coordinates. */
    @Volatile private var buttons: List<Btn> = emptyList()

    /** A screen the buttons can be clicked in: chat, or (Click In Inventory) an inventory / container. */
    private fun inClickScreen() = mc.gui.screen().let { it is ChatScreen || (clickInventory.value && it is AbstractContainerScreen<*>) }

    /** Mouse in GUI units (the same space as the HUD panel). */
    private fun mouseX() = mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.screenWidth
    private fun mouseY() = mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.screenHeight

    /** Mixed into ChatScreen.mouseClicked: a left click on a HUD button. True = used up. */
    @JvmStatic
    fun onChatClick(x: Double, y: Double, button: Int): Boolean {
        if (button != 0 || !isEnabled) return false
        seekBar?.let { b ->
            if (x >= b[0] && x <= b[0] + b[2] && y >= b[1] && y <= b[1] + b[3]) {
                seeking = true
                seekFrac = ((x - b[0]) / b[2]).toFloat().coerceIn(0f, 1f)
                return true
            }
        }
        val hit = buttons.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h } ?: return false
        when (hit.act) {
            Act.SHUFFLE -> runCtl("shuffle")
            Act.REPEAT -> runCtl("repeat")
            Act.PREV -> press(VK_PREV)
            Act.PAUSE -> press(VK_PLAY_PAUSE)
            Act.NEXT -> press(VK_NEXT)
            Act.SHARE -> track?.let { share(it) }
        }
        return true
    }

    /** Same as [onChatClick] for inventory / container screens, only when Click In Inventory is on. */
    @JvmStatic
    fun onContainerClick(x: Double, y: Double): Boolean = clickInventory.value && onChatClick(x, y, 0)

    /** The player inventory: the card is drawn in front of it (renderOnTop); other containers keep the normal HUD layer, still clickable. */
    private fun inInventory() = clickInventory.value && mc.gui.screen() is InventoryScreen

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || !showHud.value || mc.gui.hud.isHidden || track == null || (visibility.value == "Chat Open Only" && !inClickScreen())) {
            buttons = emptyList()
            return
        }
        if (inInventory()) return // drawn in front of the screen by renderOnTop instead, so it isn't dimmed or covered
        renderInternal(g)
    }

    /** Called at the end of an inventory / container screen's render (AbstractContainerScreen mixin): the card in front of the screen. */
    @JvmStatic
    fun renderOnTop(g: GuiGraphicsExtractor) {
        if (!isEnabled || !showHud.value || mc.gui.hud.isHidden || track == null || !inInventory()) return
        renderInternal(g)
    }

    private fun split(t: String): Pair<String, String> {
        val i = t.indexOf(" - ")
        return if (i < 0) "" to t else t.substring(0, i) to t.substring(i + 3)
    }

    /** While the bar is held: follow the mouse, and on release jump the song there. */
    private fun updateSeek() {
        if (!seeking) return
        val b = seekBar
        if (b != null) seekFrac = ((mouseX() - b[0]) / b[2]).toFloat().coerceIn(0f, 1f)
        if (mc.mouseHandler.isLeftPressed() && b != null) return
        seeking = false
        if (b == null || smtcEndMs <= 0) return
        val pos = (seekFrac * smtcEndMs).toLong()
        smtcPosMs = pos
        smtcUpdatedMs = System.currentTimeMillis()
        seekHoldUntil = smtcUpdatedMs + 2000
        runCtl("seek:$pos")
        ced.cedclient.utils.Debug.line("Music", "seek to ${pos / 1000}s of ${smtcEndMs / 1000}s")
    }

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    /** A fixed, track-seeded "waveform" height 0..1 for bar [i] (neighbours blended so it looks like audio, not noise). */
    private fun waveAmp(seed: Int, i: Int): Float {
        fun h(k: Int): Float {
            var x = (seed * 31 + k) * -0x61c88647
            x = x xor (x ushr 15); x *= 0x2c1b3c6d; x = x xor (x ushr 12)
            return (x and 0xFFFF) / 65535f
        }
        return 0.5f * h(i) + 0.3f * h(i + 1) + 0.2f * h(i - 1)
    }

    /**
     * The "Player" look: cover on the left, a dark rounded panel with the song (bold), the artist and a wave bar with the
     * times at both ends. In chat (or an inventory, Click In Inventory) the bar can be clicked or dragged to seek, and the
     * buttons show in a row under the card.
     */
    private fun renderPlayer(g: GuiGraphicsExtractor) {
        val real = track != null
        val (artist, song) = split(track ?: "Artist - Song")
        val isPlaying = !real || playing
        val dim = if (dimPaused.value && !isPlaying) 0.55f else 1f
        val s = panelScale
        val ts = textSize.value.toFloat() * s
        val smallTs = ts * 0.85f
        val pad = 7f * s
        val gap = 6f * s
        val titleH = ts * 1.35f
        val artistH = if (showArtist.value) ts * 1.2f else 0f
        val panelH = pad * 2 + titleH + artistH + gap * 0.8f + smallTs
        val coverS = if (showCover.value) panelH else 0f
        val coverW = if (coverS > 0f) coverS + gap else 0f
        val panelW = maxWidth.value.toFloat() * s + pad * 2
        val chat = inClickScreen()

        val bs = buttonSize.value.toFloat() * s
        val btnGap = 4f * s
        val modes = showModes.value && !appleActive
        val acts = buildList {
            if (modes) add(Act.SHUFFLE)
            add(Act.PREV); add(Act.PAUSE); add(Act.NEXT)
            if (modes) add(Act.REPEAT)
            if (showShare.value) add(Act.SHARE)
        }
        val shareW = HudCard.width("Share", ts * 0.9f) + 8f * s
        val btnRowW = acts.sumOf { (if (it == Act.SHARE) shareW else bs).toDouble() }.toFloat() + (acts.size - 1) * btnGap
        val withButtons = showButtons.value && chat
        val width = maxOf(HudCard.steady("Music", coverW + panelW, s), if (withButtons) btnRowW else 0f)
        val height = panelH + (if (withButtons) btnGap * 2 + bs else 0f)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()

        val frac = progressFraction()
        val end = if (real) smtcEndMs else 160_000L
        val leftT = if (frac >= 0f && end > 0) fmtTime((frac * end).toLong()) else "--:--"
        val rightT = if (end > 0) fmtTime(end) else "--:--"
        val seed = (track ?: "preview").hashCode()

        val mx = mouseX() - panelX
        val my = mouseY() - panelY
        val accent = accentColor.value.rgba or (0xFF shl 24)
        val hover = hoverColor.value.rgba or (0xFF shl 24)
        val played = barColor.value.rgba or (0xFF shl 24)
        val rects = ArrayList<Btn>()
        seekBar = null // set again inside the draw lambda, which HudCard.draw may run later

        HudCard.draw(g, panelX, panelY) {
            NVGRenderer.globalAlpha(dim)
            if (coverS > 0f) drawCover(0f, 0f, coverS, s)
            val px = coverW
            NVGRenderer.rect(px, 0f, panelW, panelH, 0xB0222B26.toInt(), 8f * s)
            val tx = px + pad
            val textW = panelW - pad * 2
            val titleCol = titleColor.value.rgba or (0xFF shl 24)
            val songW = HudCard.width(song, ts)
            drawText(song, tx, pad, ts, textW, titleCol, songW)
            if (songW <= textW + 0.5f) HudCard.text(song, tx + 0.45f * s, pad, ts, titleCol) // faux bold
            if (artist.isNotEmpty() && showArtist.value) drawText(artist, tx, pad + titleH, ts, textW, artistColor.value.rgba or (0xFF shl 24), HudCard.width(artist, ts))

            val rowY = panelH - pad - smallTs
            val timeW = HudCard.width("00:00", smallTs)
            HudCard.text(leftT, tx, rowY, smallTs, 0xFFE8E8E8.toInt())
            HudCard.text(rightT, px + panelW - pad - HudCard.width(rightT, smallTs), rowY, smallTs, 0xFFE8E8E8.toInt())
            val barX0 = tx + timeW + 5f * s
            val barW = (px + panelW - pad - timeW - 5f * s) - barX0
            val cy = rowY + smallTs / 2
            val maxH = smallTs * 1.4f
            val grab = chat && smtcEndMs > 0 && track != null
            val near = grab && (seeking || (mx >= barX0 && mx <= barX0 + barW && my >= cy - maxH && my <= cy + maxH))
            if (barW > 8f * s) {
                val n = (barW / (2.8f * s)).toInt().coerceAtLeast(6)
                val step = barW / n
                val bw = (step * 0.5f).coerceAtLeast(1f * s)
                for (i in 0 until n) {
                    val h = maxH * (0.28f + 0.72f * waveAmp(seed, i))
                    val done = frac >= 0f && (i + 0.5f) / n <= frac
                    NVGRenderer.rect(barX0 + i * step, cy - h / 2, bw, h, if (done) played else if (near) 0x77FFFFFF else 0x44FFFFFF, bw / 2)
                }
                if (near && frac >= 0f) NVGRenderer.rect(barX0 + barW * frac - 0.5f * s, cy - maxH * 0.75f, 1f * s, maxH * 1.5f, 0xFFFFFFFF.toInt())
                if (grab) seekBar = floatArrayOf(panelX + barX0, panelY + cy - maxH, barW, maxH * 2)
            }

            if (withButtons) {
                var bx = 0f
                val by = panelH + btnGap
                for (act in acts) {
                    val w = if (act == Act.SHARE) shareW else bs
                    val over = mx >= bx && mx <= bx + w && my >= by && my <= by + bs
                    if (over) NVGRenderer.rect(bx, by, w, bs, (hover and 0x00FFFFFF) or (0x33 shl 24), 4f * s)
                    val col = if (over) hover else accent
                    val modeCol = if (over) hover else if ((act == Act.SHUFFLE && shuffleOn) || (act == Act.REPEAT && repeatMode != 0)) accent else 0xFF777788.toInt()
                    when (act) {
                        Act.SHUFFLE -> drawShuffle(bx, by, bs, modeCol, shuffleOn)
                        Act.REPEAT -> drawRepeat(bx, by, bs, modeCol, repeatMode)
                        Act.PREV -> drawSkip(bx, by, bs, col, false)
                        Act.NEXT -> drawSkip(bx, by, bs, col, true)
                        Act.PAUSE -> drawPlayPause(bx, by, bs, col, isPlaying)
                        Act.SHARE -> HudCard.text("Share", bx + 4f * s, by + (bs - ts * 0.9f) / 2, ts * 0.9f, col)
                    }
                    rects += Btn(act, panelX + bx, panelY + by, w, bs)
                    bx += w + btnGap
                }
            }
            NVGRenderer.globalAlpha(1f)
        }
        buttons = if (chat) rects else emptyList()
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        updateSeek()
        if (style.value == "Player") { renderPlayer(g); return }
        val real = track != null
        val (artist, song) = split(track ?: "Artist - Song")
        val isPlaying = !real || playing
        val dim = if (dimPaused.value && !isPlaying) 0.55f else 1f
        val compact = style.value == "Compact"
        val s = panelScale
        val ts = textSize.value.toFloat() * s
        val lineH = ts * 1.25f
        val pad = 6f * s
        val gap = 4f * s
        val bs = buttonSize.value.toFloat() * s
        val limit = maxWidth.value.toFloat() * s
        val iconW = if (showIcon.value) 10f * s else 0f
        val iconGap = if (showIcon.value) 5f * s else 0f
        val withButtons = showButtons.value

        val firstLine = if (compact && artist.isNotEmpty()) "$artist - $song" else song
        val secondLine = if (!compact && showArtist.value && artist.isNotEmpty()) artist else ""
        val firstW = HudCard.width(firstLine, ts)
        val secondW = if (secondLine.isEmpty()) 0f else HudCard.width(secondLine, ts)
        // One fixed size: the text area is always Text Width wide, and rows / buttons are reserved whether or not there is
        // an artist, a progress bar or shuffle / repeat (Apple Music hides those) right now.
        val textW = limit

        val shareW = if (withButtons && showShare.value) HudCard.width("Share", ts * 0.9f) + 8f * s else 0f
        val modes = withButtons && showModes.value && !appleActive
        val btnCount = if (withButtons) 3 + (if (modes) 2 else 0) else 0
        val btnRowW = if (btnCount == 0) 0f else btnCount * bs + (btnCount - 1) * gap + (if (shareW > 0f) gap + shareW else 0f)
        val fullBtnCount = if (withButtons) 3 + (if (showModes.value) 2 else 0) else 0
        val fullBtnRowW = if (fullBtnCount == 0) 0f else fullBtnCount * bs + (fullBtnCount - 1) * gap + (if (shareW > 0f) gap + shareW else 0f)

        val cs = if (showCover.value) coverSize.value.toFloat() * s else 0f
        val coverW = if (cs > 0f) cs + gap * 2 else 0f
        val coverLeft = coverSide.value == "Left"
        val rowH = maxOf(lineH, if (compact && withButtons) bs else 0f, if (compact) cs else 0f)
        val textRows = lineH + (if (!compact && showArtist.value) lineH else 0f)
        val innerH = if (compact) rowH else textRows + (if (withButtons) bs + gap else 0f)
        val boxH = maxOf(innerH, cs)
        val yOff = (boxH - innerH) / 2
        val width = HudCard.steady("Music", if (compact) pad * 2 + coverW + iconW + iconGap + textW + (if (withButtons) gap * 2 + fullBtnRowW else 0f)
            else pad * 2 + coverW + iconW + iconGap + maxOf(textW, fullBtnRowW), s)
        val frac = progressFraction()
        val barH = if (showProgress.value) 2f * s else 0f
        val barGap = if (showProgress.value) 4f * s else 0f
        val height = pad * 2 + boxH + barGap + barH
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()

        val mx = mouseX() - panelX
        val my = mouseY() - panelY
        val chat = inClickScreen()
        val accent = accentColor.value.rgba or (0xFF shl 24)
        val hover = hoverColor.value.rgba or (0xFF shl 24)
        val rects = ArrayList<Btn>()
        seekBar = null // set again inside the draw lambda, which HudCard.draw may run later

        HudCard.draw(g, panelX, panelY) {
            if (background.value > 0.0) HudCard.card(width, height, s, background.value.toFloat() * dim)
            NVGRenderer.globalAlpha(dim)
            if (cs > 0f) {
                val cx = if (coverLeft) pad else width - pad - cs
                drawCover(cx, pad + (boxH - cs) / 2, cs, s)
            }
            val leftEdge = pad + (if (coverLeft) coverW else 0f)
            val textX = leftEdge + iconW + iconGap
            val textY = if (compact) pad + (rowH - lineH) / 2 else pad + yOff
            if (showIcon.value) drawEqualizer(leftEdge, textY + (lineH - ts) / 2, 10f * s, ts, accent, isPlaying)

            drawText(firstLine, textX, textY, ts, textW, titleColor.value.rgba or (0xFF shl 24), firstW)
            if (secondLine.isNotEmpty()) drawText(secondLine, textX, textY + lineH, ts, textW, artistColor.value.rgba or (0xFF shl 24), secondW)

            if (btnCount > 0) {
                val by = if (compact) pad + (rowH - bs) / 2 else pad + yOff + textRows + gap
                var bx = if (compact) width - pad - (if (coverLeft) 0f else coverW) - btnRowW else textX
                fun place(act: Act, w: Float) {
                    val over = chat && mx >= bx && mx <= bx + w && my >= by && my <= by + bs
                    if (over) NVGRenderer.rect(bx, by, w, bs, (hover and 0x00FFFFFF) or (0x33 shl 24), 4f * s)
                    val col = if (over) hover else accent
                    val modeCol = if (over) hover else if ((act == Act.SHUFFLE && shuffleOn) || (act == Act.REPEAT && repeatMode != 0)) accent else 0xFF777788.toInt()
                    when (act) {
                        Act.SHUFFLE -> drawShuffle(bx, by, bs, modeCol, shuffleOn)
                        Act.REPEAT -> drawRepeat(bx, by, bs, modeCol, repeatMode)
                        Act.PREV -> drawSkip(bx, by, bs, col, false)
                        Act.NEXT -> drawSkip(bx, by, bs, col, true)
                        Act.PAUSE -> drawPlayPause(bx, by, bs, col, isPlaying)
                        Act.SHARE -> HudCard.text("Share", bx + 4f * s, by + (bs - ts * 0.9f) / 2, ts * 0.9f, col)
                    }
                    rects += Btn(act, panelX + bx, panelY + by, w, bs)
                    bx += w + gap
                }
                if (modes) place(Act.SHUFFLE, bs)
                place(Act.PREV, bs); place(Act.PAUSE, bs); place(Act.NEXT, bs)
                if (modes) place(Act.REPEAT, bs)
                if (shareW > 0f) place(Act.SHARE, shareW)
            }
            if (frac >= 0f) {
                val by = pad + boxH + barGap
                val bw = width - pad * 2
                // In chat the bar can be dragged: it thickens under the mouse and shows a handle.
                val grab = chat && smtcEndMs > 0 && track != null
                val near = grab && (seeking || (mx >= pad && mx <= pad + bw && my >= by - 5f * s && my <= by + barH + 5f * s))
                val th = if (near) 4f * s else barH
                val ty = by + barH / 2 - th / 2
                NVGRenderer.rect(pad, ty, bw, th, 0x22FFFFFF, th / 2)
                if (frac > 0f) NVGRenderer.rect(pad, ty, bw * frac, th, if (near) accent else (accent and 0x00FFFFFF) or (0x99 shl 24), th / 2)
                if (near) NVGRenderer.circle(pad + bw * frac, by + barH / 2, 3.5f * s, accent)
                if (grab) seekBar = floatArrayOf(panelX + pad, panelY + by - 5f * s, bw, barH + 10f * s)
            }
            NVGRenderer.globalAlpha(1f)
        }
        buttons = if (chat) rects else emptyList()
    }

    /** How far into the song, 0..1 (extrapolated from the last position the player reported); -1 = no bar. Sample value in the HUD editor. */
    private fun progressFraction(): Float {
        if (!showProgress.value && style.value != "Player") return -1f
        if (track == null) return 0.4f
        if (smtcEndMs <= 0) return -1f
        if (seeking) return seekFrac
        val extra = if (smtcPlaying) System.currentTimeMillis() - smtcUpdatedMs else 0L
        return ((smtcPosMs + extra).toFloat() / smtcEndMs).coerceIn(0f, 1f)
    }

    /** The cover at ([x], [y]), or a dim placeholder while it loads / when none was found. Runs inside the NVG frame. */
    private fun drawCover(x: Float, y: Float, size: Float, s: Float) {
        val t = track
        if (t != null && coverBytesFor == t && coverImageFor != t) {
            coverImage?.let { NVGRenderer.deleteImage(it) }
            coverImage = null
            coverImageFor = t
            coverBytes?.let { b -> runCatching { coverImage = NVGRenderer.createImage(Image("spotify-cover:" + t.hashCode(), false, ByteArrayInputStream(b))) } }
        }
        val radius = if (coverRound.value) 5f * s else 0f
        val img = coverImage
        if (img != null && coverImageFor == t) NVGRenderer.image(img, x, y, size, size, radius)
        else NVGRenderer.rect(x, y, size, size, 0x66808090, radius)
    }

    /** [text] at ([x], [y]): cut off with ... or scrolling when wider than [maxW]. */
    private fun drawText(text: String, x: Float, y: Float, size: Float, maxW: Float, color: Int, fullW: Float) {
        if (fullW <= maxW + 0.5f) { HudCard.text(text, x, y, size, color); return }
        if (!scroll.value) { HudCard.text(NVGRenderer.truncate(text, maxW, size, HudCard.font), x, y, size, color); return }
        val loop = fullW + size * 2
        val off = (System.currentTimeMillis() * scrollSpeed.value / 1000.0 % loop.toDouble().coerceAtLeast(1.0)).toFloat()
        NVGRenderer.pushScissor(x, y - 1f, maxW, size * 1.4f)
        HudCard.text(text, x - off, y, size, color)
        HudCard.text(text, x - off + loop, y, size, color)
        NVGRenderer.popScissor()
    }

    private fun drawEqualizer(x: Float, y: Float, w: Float, h: Float, color: Int, playing: Boolean) {
        val bars = 3
        val bw = w / (bars * 2 - 1)
        val t = System.currentTimeMillis() / 180.0
        for (i in 0 until bars) {
            val frac = if (playing) 0.45f + 0.55f * (0.5f + 0.5f * Math.sin(t + i * 1.9).toFloat()) else 0.4f + 0.15f * i
            val bh = h * frac
            NVGRenderer.rect(x + i * bw * 2, y + h - bh, bw, bh, color)
        }
    }

    /** Two crossing arrows, with a dot under it while on (like Spotify's own). */
    private fun drawShuffle(x: Float, y: Float, s: Float, color: Int, on: Boolean) {
        val t = (s * 0.09f).coerceAtLeast(1f)
        NVGRenderer.line(x + s * 0.16f, y + s * 0.3f, x + s * 0.8f, y + s * 0.66f, t, color)
        NVGRenderer.line(x + s * 0.16f, y + s * 0.66f, x + s * 0.8f, y + s * 0.3f, t, color)
        NVGRenderer.polygon(floatArrayOf(x + s * 0.92f, y + s * 0.3f, x + s * 0.74f, y + s * 0.18f, x + s * 0.74f, y + s * 0.42f), color)
        NVGRenderer.polygon(floatArrayOf(x + s * 0.92f, y + s * 0.66f, x + s * 0.74f, y + s * 0.54f, x + s * 0.74f, y + s * 0.78f), color)
        if (on) NVGRenderer.circle(x + s * 0.5f, y + s * 0.92f, s * 0.05f, color)
    }

    /** A loop with an arrow head; a 1 in the middle for repeat one, a dot under it while on. */
    private fun drawRepeat(x: Float, y: Float, s: Float, color: Int, mode: Int) {
        val t = (s * 0.09f).coerceAtLeast(1f)
        NVGRenderer.hollowRect(x + s * 0.16f, y + s * 0.3f, s * 0.68f, s * 0.42f, t, color, s * 0.1f)
        NVGRenderer.polygon(floatArrayOf(x + s * 0.66f, y + s * 0.16f, x + s * 0.66f, y + s * 0.44f, x + s * 0.88f, y + s * 0.3f), color)
        if (mode == 1) HudCard.text("1", x + s * 0.43f, y + s * 0.36f, s * 0.3f, color)
        if (mode != 0) NVGRenderer.circle(x + s * 0.5f, y + s * 0.92f, s * 0.05f, color)
    }

    private fun drawSkip(x: Float, y: Float, s: Float, color: Int, next: Boolean) {
        val bar = s * 0.12f
        if (next) {
            NVGRenderer.polygon(floatArrayOf(x + s * 0.2f, y + s * 0.22f, x + s * 0.2f, y + s * 0.78f, x + s * 0.7f, y + s * 0.5f), color)
            NVGRenderer.rect(x + s * 0.74f, y + s * 0.22f, bar, s * 0.56f, color)
        } else {
            NVGRenderer.polygon(floatArrayOf(x + s * 0.8f, y + s * 0.22f, x + s * 0.8f, y + s * 0.78f, x + s * 0.3f, y + s * 0.5f), color)
            NVGRenderer.rect(x + s * 0.14f, y + s * 0.22f, bar, s * 0.56f, color)
        }
    }

    private fun drawPlayPause(x: Float, y: Float, s: Float, color: Int, playing: Boolean) {
        if (playing) {
            NVGRenderer.rect(x + s * 0.26f, y + s * 0.2f, s * 0.17f, s * 0.6f, color)
            NVGRenderer.rect(x + s * 0.57f, y + s * 0.2f, s * 0.17f, s * 0.6f, color)
        } else {
            NVGRenderer.polygon(floatArrayOf(x + s * 0.3f, y + s * 0.18f, x + s * 0.3f, y + s * 0.82f, x + s * 0.8f, y + s * 0.5f), color)
        }
    }

    override val label = "Music"
    private const val DEFAULT_X = 1144
    private const val DEFAULT_Y = 433
    private const val DEFAULT_SCALE = 0.7f
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = DEFAULT_SCALE
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile by lazy { ced.cedclient.config.CedPaths.hud("spotify") }

    init {
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
    }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = DEFAULT_SCALE }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }
}

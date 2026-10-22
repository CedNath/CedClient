package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.utils.ChatPrefix
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
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import org.lwjgl.glfw.GLFW

/**
 * Windows only. Reads the playing track from the Spotify or Apple Music desktop window title and controls playback
 * with the system media keys (they work for any player), on keybinds or from clickable chat buttons (/cc spotify next | prev | pause | share).
 */
object Spotify : Module(
    "Spotify",
    Category.Misc,
    "Shows the current Spotify track in chat and controls playback with media keys."
) {
    private val mc get() = Minecraft.getInstance()

    private val source = DropdownSetting("Player", listOf("Auto", "Spotify", "Apple Music"), "Auto", "Which app to read the track from. Apple Music needs the song in its window title.")
    private val songMessage = BooleanSetting("Song Change Message", true, "Shows a chat message with clickable controls when the track changes.")
    private val keybinds = BooleanSetting("Media Keybinds", true, "Use the keybinds below (outside of menus).")
    private val prevKey = KeybindSetting("Previous Key", GLFW.GLFW_KEY_UNKNOWN, "Previous track.")
    private val pauseKey = KeybindSetting("Play / Pause Key", GLFW.GLFW_KEY_UNKNOWN, "Play or pause.")
    private val nextKey = KeybindSetting("Next Key", GLFW.GLFW_KEY_UNKNOWN, "Next track.")

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

    /** "Artist - Song" of the track playing now (last one while paused), null if none was found. */
    @JvmStatic fun currentTrack(): String? = if (isEnabled) track else null
    private var announced: String? = null
    private val down = HashSet<Int>()
    private var poller: Thread? = null

    init {
        addSettings(source, songMessage, keybinds, prevKey, pauseKey, nextKey)

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) return@register
            val t = track
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
        if (!InputConstants.isKeyDown(mc.window, key)) { down.remove(key); return false }
        return down.add(key)
    }

    private fun press(vk: Int) {
        val u = user32 ?: return
        u.keybd_event(vk.toByte(), 0, 0, null)
        u.keybd_event(vk.toByte(), 0, KEYUP, null)
    }

    private fun button(label: String, command: String): Component =
        Component.literal(label).withStyle(Style.EMPTY.withClickEvent(ClickEvent.RunCommand(command)))

    private fun songLine(t: String): Component =
        ChatPrefix.msgFor("Spotify", "§a♫ §f$t ").append(button("§7[§f⏮§7]", "/cc spotify prev"))
            .append(Component.literal(" ")).append(button("§7[§f⏯§7]", "/cc spotify pause"))
            .append(Component.literal(" ")).append(button("§7[§f⏭§7]", "/cc spotify next"))
            .append(Component.literal(" ")).append(button("§7[§bShare§7]", "/cc spotify share"))

    override fun onEnable() {
        if (user32 == null || poller?.isAlive == true) return
        announced = null
        poller = Thread({
            while (isEnabled) {
                runCatching { track = readTrack() }
                Thread.sleep(1000)
            }
        }, "CedClient-Spotify-poll").apply { isDaemon = true; start() }
    }

    private fun wants(cmd: String): Boolean {
        val spotify = cmd.contains("Spotify", ignoreCase = true)
        val apple = cmd.contains("AppleMusic", ignoreCase = true) || cmd.contains("iTunes", ignoreCase = true)
        return when (source.value) { "Spotify" -> spotify; "Apple Music" -> apple; else -> spotify || apple }
    }

    /** The track from the player's window title; keeps the last one while paused (the title then reads just the app name). */
    private fun readTrack(): String? {
        val u = user32 ?: return track
        var found: String? = null
        u.EnumWindows(User32.EnumProc { hwnd, _ ->
            if (!u.IsWindowVisible(hwnd)) return@EnumProc true
            val title = CharArray(256).also { u.GetWindowTextW(hwnd, it, 256) }.concatToString().trimEnd('\u0000')
            if (title.isBlank() || title in IDLE_TITLES) return@EnumProc true
            val pid = IntByReference().also { u.GetWindowThreadProcessId(hwnd, it) }.value
            val cmd = ProcessHandle.of(pid.toLong()).flatMap { it.info().command() }.orElse("")
            if (!wants(cmd)) return@EnumProc true
            // Spotify has several Chromium windows; only the main one has "Artist - Song" as its title.
            if (cmd.contains("Spotify", ignoreCase = true) && !title.contains(" - ")) return@EnumProc true
            found = title.replace(" — ", " - "); false
        }, null)
        return found ?: track
    }

    private val IDLE_TITLES = setOf("Spotify", "Spotify Free", "Spotify Premium", "Apple Music", "iTunes", "Default IME", "MSCTFIME UI")

    override fun onDisable() { poller = null; down.clear() }

    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("spotify")
            .then(ClientCommands.literal("next").executes { if (isEnabled) press(VK_NEXT); 1 })
            .then(ClientCommands.literal("prev").executes { if (isEnabled) press(VK_PREV); 1 })
            .then(ClientCommands.literal("pause").executes { if (isEnabled) press(VK_PLAY_PAUSE); 1 })
            .then(ClientCommands.literal("share").executes { ctx ->
                val t = track
                if (!isEnabled || t == null) ctx.source.sendFeedback(ChatPrefix.msgFor("Spotify", "§7No Spotify track has been detected yet."))
                else mc.gui.setScreen(net.minecraft.client.gui.screens.ChatScreen("Now playing: $t", false))
                1
            })
}

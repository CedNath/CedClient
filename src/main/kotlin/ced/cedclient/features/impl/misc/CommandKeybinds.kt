package ced.cedclient.features.impl.misc

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.state.DungeonState
import ced.cedclient.state.IslandState
import ced.cedclient.ui.keybinds.CommandKeybindsScreen
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Debug
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import ced.cedclient.utils.Keys
import java.io.File

/** One bound key. [key] is an SDL scancode (see [Keys]), or MOUSE_BASE + mouse button; -1 = not set yet. */
data class KeyBind(
    var key: Int = -1,
    var command: String = "",
    var category: String = CommandKeybinds.GENERAL,
    var enabled: Boolean = true,
    /** IslandState.Island names it may fire on; empty = anywhere. */
    var islands: MutableList<String> = mutableListOf(),
    /** F7 / M7 boss phases ("P1".."P5") it may fire in; empty = any time. */
    var phases: MutableList<String> = mutableListOf(),
) {
    @Transient
    var wasDown = false
}

/**
 * Bind keys to commands or chat messages, organised in categories. Edit them in the GUI (`/cc binds`) or with
 * `/cc bind g /warp hub`. A bound text starting with "/" runs as a command (client commands included), anything
 * else is sent as chat. Only fires with no screen open, so typing in chat or a GUI never triggers a bind.
 */
object CommandKeybinds : Module(
    "Command Keybinds", Category.Misc,
    "Bind keys to commands, sorted into categories. /cc binds opens the editor.",
) {
    const val GENERAL = "General"
    const val MOUSE_BASE = 1000

    // Names -> SDL scancodes. The table below is written in the old GLFW numbers and converted with Keys.fromGlfw.
    private val keyCodes: Map<String, Int> = buildMap {
        val glfw = buildMap<String, Int> {
            for (c in 'a'..'z') put(c.toString(), 65 + (c - 'a'))
            for (d in 0..9) { put(d.toString(), 48 + d); put("numpad$d", 320 + d) }
            for (f in 1..12) put("f$f", 290 + f - 1)
            putAll(mapOf(
                "space" to 32, "tab" to 258, "enter" to 257,
                "up" to 265, "down" to 264, "left" to 263, "right" to 262,
                "home" to 268, "end" to 269, "insert" to 260, "delete" to 261, "pageup" to 266, "pagedown" to 267,
                "grave" to 96, "minus" to 45, "equals" to 61, "lbracket" to 91, "rbracket" to 93, "semicolon" to 59,
                "comma" to 44, "period" to 46, "slash" to 47,
                "lshift" to 340, "rshift" to 344, "lctrl" to 341, "rctrl" to 345, "lalt" to 342, "ralt" to 346,
            ))
        }
        for ((name, code) in glfw) Keys.fromGlfw(code).let { if (it != Keys.UNKNOWN) put(name, it) }
        put("mouse3", MOUSE_BASE + Keys.MOUSE_MIDDLE) // only the middle button can be polled in 26.3
    }
    private val keyNames: Map<Int, String> = keyCodes.entries.associate { it.value to it.key }

    val binds: MutableList<KeyBind> = mutableListOf()
    val categories: MutableList<String> = mutableListOf(GENERAL)

    @Volatile
    private var pendingOpen = false

    private val gson = Gson()
    private val file: File by lazy { CedPaths.file("command_keybinds.json") }

    init {
        addSettings(ActionSetting("Open Keybinds", "Add, sort and edit your keybinds.") { open() })
        load()
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    /** Opens the editor on the next tick (the chat screen would close it straight away otherwise). */
    fun open() { pendingOpen = true }

    fun keyLabel(code: Int): String = when {
        code < 0 -> "None"
        else -> keyNames[code]?.uppercase() ?: Keys.name(code)
    }

    fun addCategory(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || categories.any { it.equals(n, true) }) return false
        categories.add(n)
        save()
        return true
    }

    /** Binds in the deleted category move to General. */
    fun removeCategory(name: String) {
        if (name == GENERAL) return
        categories.remove(name)
        binds.filter { it.category == name }.forEach { it.category = GENERAL }
        save()
    }

    private fun isDown(client: Minecraft, code: Int): Boolean =
        if (code >= MOUSE_BASE) client.mouseHandler.isMiddlePressed else Keys.isDown(code)

    private fun tick(client: Minecraft) {
        if (pendingOpen) {
            pendingOpen = false
            client.gui.setScreen(CommandKeybindsScreen())
        }
        if (binds.isEmpty()) return
        for (bind in binds) {
            if (bind.key < 0) continue
            val down = isDown(client, bind.key)
            val pressed = down && !bind.wasDown
            bind.wasDown = down
            if (pressed && isEnabled && bind.enabled && bind.command.isNotBlank() && client.gui.screen() == null && whereMatches(bind)) run(client, bind)
        }
    }

    /** Island / F7-M7 phase conditions: an empty list means no restriction. */
    private fun whereMatches(bind: KeyBind): Boolean {
        if (bind.islands.isNotEmpty() && IslandState.island?.name !in bind.islands) return false
        if (bind.phases.isNotEmpty()) {
            val floor = DungeonState.floor
            val phase = DungeonState.f7Phase ?: return false
            if (!DungeonState.inDungeon || (floor != "F7" && floor != "M7") || "P$phase" !in bind.phases) return false
        }
        return true
    }

    /** Short text for the GUI's Where button. */
    fun whereLabel(bind: KeyBind): String {
        val parts = mutableListOf<String>()
        if (bind.islands.size == 1) parts += IslandState.Island.entries.firstOrNull { it.name == bind.islands[0] }?.displayName ?: bind.islands[0]
        else if (bind.islands.size > 1) parts += "${bind.islands.size} islands"
        if (bind.phases.isNotEmpty()) parts += bind.phases.sorted().joinToString("/")
        return if (parts.isEmpty()) "Anywhere" else parts.joinToString(" + ")
    }

    private fun run(client: Minecraft, bind: KeyBind) {
        val connection = client.player?.connection ?: return
        Debug.line(name, "${keyLabel(bind.key)} -> ${bind.command}")
        val text = bind.command.trim()
        if (text.startsWith("/")) connection.sendCommand(text.removePrefix("/")) else connection.sendChat(text)
    }

    fun save() {
        try {
            file.parentFile?.mkdirs()
            val root = JsonObject()
            root.add("categories", gson.toJsonTree(categories))
            root.add("binds", gson.toJsonTree(binds))
            file.writeText(gson.toJson(root))
        } catch (e: Exception) {
            println("[CommandKeybinds] Failed to save: ${e.message}")
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JsonParser.parseString(file.readText()).asJsonObject
            if (root.has("binds")) {
                root.getAsJsonArray("binds").forEach { binds.add(gson.fromJson(it, KeyBind::class.java)) }
                root.getAsJsonArray("categories")?.forEach { c -> c.asString.let { if (it !in categories) categories.add(it) } }
                binds.filter { it.category !in categories }.forEach { categories.add(it.category) }
            } else {
                // first version stored a flat { "g": "/warp hub" } map
                for ((k, v) in root.entrySet()) keyCodes[k]?.let { binds.add(KeyBind(it, v.asString)) }
            }
        } catch (e: Exception) {
            println("[CommandKeybinds] Failed to load: ${e.message}")
        }
    }

    /** Attached under /cedclient (and /cc): bind, unbind, binds. */
    fun commandTree(): List<LiteralArgumentBuilder<FabricClientCommandSource>> = listOf(
        ClientCommands.literal("bind").then(
            ClientCommands.argument("key", StringArgumentType.word()).then(
                ClientCommands.argument("command", StringArgumentType.greedyString()).executes { ctx ->
                    val key = StringArgumentType.getString(ctx, "key").lowercase()
                    val text = StringArgumentType.getString(ctx, "command").trim()
                    val code = keyCodes[key]
                    if (code == null) {
                        ctx.source.sendFeedback(ChatPrefix.msg("§cUnknown key '$key'. Use a letter, digit, f1-f12, numpad0-9, space, arrows, mouse3... or set it in /cc binds"))
                    } else {
                        // same key + General = replace, so re-running /cc bind edits instead of piling up
                        val existing = binds.firstOrNull { it.key == code && it.category == GENERAL }
                        if (existing != null) existing.command = text else binds.add(KeyBind(code, text))
                        save()
                        ctx.source.sendFeedback(ChatPrefix.msg("§fBound §e${keyLabel(code)} §f-> §7$text"))
                    }
                    1
                }
            )
        ),
        ClientCommands.literal("unbind").then(
            ClientCommands.argument("key", StringArgumentType.word()).executes { ctx ->
                val code = keyCodes[StringArgumentType.getString(ctx, "key").lowercase()]
                if (code != null && binds.removeAll { it.key == code }) {
                    save()
                    ctx.source.sendFeedback(ChatPrefix.msg("§fUnbound §e${keyLabel(code)}"))
                } else {
                    ctx.source.sendFeedback(ChatPrefix.msg("§cNothing bound to that key"))
                }
                1
            }
        ),
        ClientCommands.literal("keybinds").executes { open(); 1 },
        ClientCommands.literal("binds")
            .executes { open(); 1 }
            .then(ClientCommands.literal("list").executes { ctx ->
                if (binds.isEmpty()) ctx.source.sendFeedback(ChatPrefix.msg("§7No binds. /cc binds opens the editor."))
                else for (cat in categories) {
                    val inCat = binds.filter { it.category == cat }
                    if (inCat.isEmpty()) continue
                    ctx.source.sendFeedback(Component.literal("§b$cat"))
                    inCat.forEach { ctx.source.sendFeedback(Component.literal("  §e${keyLabel(it.key)} §f-> §7${it.command}")) }
                }
                1
            }),
    )
}

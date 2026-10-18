package ced.cedclient.features.impl.misc

import ced.cedclient.features.impl.render.ActionBarHud
import ced.cedclient.accessor.ChatHistoryAccess
import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.utils.Color
import ced.cedclient.utils.Colors
import com.google.common.collect.MapMaker
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.core.HolderLookup
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.resources.RegistryOps
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Colour codes, compiled once. */
private val CODES = Regex("§.")

/**
 * Chat that lasts: a longer history than vanilla's 100 lines, kept when you leave a server, saved to disk and put
 * back the next time you launch (with a [server] divider each time you join), timestamps, a right-click menu on
 * messages (copy message / sender / link, reply) and Ctrl + left-click to copy, plus name pings and chat alerts
 * ([ChatPing]). Was "Chat History" + "Chat Ping". Hooks: ChatHistoryMixin, GuiDisconnectMixin, ChatScreenMenuMixin.
 * Each feature's settings sit in a popup, so the panel only shows one button per feature.
 */
object ChatFeatures : Module(
    "Chat Features",
    Category.Misc,
    "Longer chat history that survives restarts, timestamps, click to copy / reply, and pings + alerts when chosen words are said.",
    defaultEnabled = true
) {
    private val maxLines = NumberSetting("Max Chat Lines", 10000.0, 1000.0, 30000.0, 1000.0, "Vanilla keeps 100.")
    private val keepOnDisconnect = BooleanSetting("Keep Chat On Disconnect", true)
    private val saveHistory = BooleanSetting("Save Between Launches", true, "Restores your chat (and sent messages) the first time you join after starting the game.")
    private val savedLines = NumberSetting("Lines To Save", 10000.0, 1000.0, 30000.0, 1000.0).withDependency { saveHistory.value }
    private val divider = BooleanSetting("Server Divider", true, "A [server] line in chat every time you join.")
    private val compactRepeats = BooleanSetting("Compact Repeats", true, "The same message again shows once with (x2), (x3) instead of filling chat.")
    private val menu = BooleanSetting("Right-Click Menu", true, "Right-click a message in chat: copy it, copy the sender or a link, or reply.")
    private val timestamps = BooleanSetting("Show Timestamps", false, "The time each message came in, in front of it. Restored chat keeps its original times.")
    private val timeFormat = DropdownSetting("Time Format", listOf("HH:mm:ss", "HH:mm"), "HH:mm:ss").withDependency { timestamps.value }
    private val timeColor = ColorSetting("Timestamp Color", Color(170, 170, 170)).withDependency { timestamps.value }
    private val ctrlCopy = BooleanSetting("Ctrl + Click Copy", true, "Ctrl + left-click a message in chat to copy it.")

    private val historySettings: List<RenderableSetting<*>> = listOf(maxLines, keepOnDisconnect, saveHistory, savedLines, divider, compactRepeats)
    private val timeSettings: List<RenderableSetting<*>> = listOf(timestamps, timeFormat, timeColor)
    private val clickSettings: List<RenderableSetting<*>> = listOf(menu, ctrlCopy)

    private fun popup(popup: SettingsPopup) { (mc.screen as? ClickGUI)?.openPopup(popup) }

    private val historyButton = ActionSetting("History", "Max lines, keep on disconnect, save between launches, server divider, compact repeats.") {
        popup(SettingsPopup("Chat History", listOf(listOf(SettingsPopup.Section("History", historySettings)))))
    }
    private val timeButton = ActionSetting("Timestamps", "Time in front of each message: on / off, format, colour.") {
        popup(SettingsPopup("Chat Timestamps", listOf(listOf(SettingsPopup.Section("Timestamps", timeSettings)))))
    }
    private val clickButton = ActionSetting("Message Clicks", "Right-click menu (copy / reply) and Ctrl + left-click to copy.") {
        popup(SettingsPopup("Message Clicks", listOf(listOf(SettingsPopup.Section("Clicks", clickSettings)))))
    }
    private val pingButton = ActionSetting("Ping", "Sound + highlight when your name or your extra words are said, and the Chat Alerts HUD.") {
        popup(SettingsPopup("Chat Ping", listOf(
            listOf(SettingsPopup.Section("Name Ping", ChatPing.nameSettings)),
            listOf(SettingsPopup.Section("Chat Alerts HUD", ChatPing.hudSettings)),
        ), listOf(ChatPing.test)))
    }

    init {
        val inPopups = historySettings + timeSettings + clickSettings + ChatPing.nameSettings + ChatPing.hudSettings
        inPopups.forEach { it.inPopup = true }
        // Popup settings are still added (hidden from the panel), so they save like any setting.
        addSettings(historyButton, timeButton, clickButton, pingButton, ChatPing.configure, *inPopups.toTypedArray())
    }

    @JvmStatic fun maxLines(vanilla: Int): Int = if (isEnabled) maxLines.value.toInt() else vanilla
    @JvmStatic fun extendsHistory(): Boolean = isEnabled
    @JvmStatic fun compactRepeats(): Boolean = isEnabled && compactRepeats.value
    @JvmStatic fun keepOnDisconnect(): Boolean = isEnabled && keepOnDisconnect.value

    private val mc get() = Minecraft.getInstance()
    private val chat get() = mc.gui.chat as ChatHistoryAccess

    // ---------------------------------------------------------------- timestamps

    /** When each message came in (epoch ms). Weak identity keys: GuiMessage is a record, equal copies are different lines. */
    private val receivedAt: MutableMap<GuiMessage, Long> = MapMaker().weakKeys().makeMap()
    private var formatter = DateTimeFormatter.ofPattern(timeFormat.value)
    private var shownAs = ""

    /** ChatHistoryMixin.insert: restored / divider lines carry their own time. */
    @JvmStatic fun stamp(message: GuiMessage, time: Long) { receivedAt[message] = time }

    /** ChatHistoryMixin: what a message is drawn as -- its content, after "[time] " when Timestamps is on. */
    @JvmStatic
    fun withTimestamp(message: GuiMessage): Component {
        // Recorded even while off, so turning Timestamps on later shows the real times.
        val time = receivedAt.getOrPut(message) { System.currentTimeMillis() }
        if (!timestamps.value) return message.content()
        val clock = LocalTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault()).format(formatter)
        return Component.literal("[$clock] ").withColor(timeColor.value.rgba and 0xFFFFFF).append(message.content())
    }

    /** Re-lays the chat out when Timestamps / Time Format / Timestamp Color change, so old lines follow too. */
    private fun refreshTimestamps() {
        val now = "${timestamps.value}${timeFormat.value}${timeColor.value.rgba}"
        if (now == shownAs) return
        if (shownAs.isNotEmpty() && isEnabled) mc.gui.chat.rescaleChat()
        shownAs = now
        formatter = DateTimeFormatter.ofPattern(timeFormat.value)
    }

    // ---------------------------------------------------------------- save / restore

    private val file: File by lazy { CedPaths.file("chat_history.json") }
    private val gson = GsonBuilder().create()
    private var restored = false
    private var registries: HolderLookup.Provider? = null
    private var ticksSinceSave = 0

    /** Called once from client init. */
    fun start() {
        ClientPlayConnectionEvents.JOIN.register { handler, _, client ->
            client.execute { onJoin(handler.registryAccess()) }
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register { save() }
        // A save every 5 minutes in game, so a crash loses little.
        ClientTickEvents.END_CLIENT_TICK.register {
            refreshTimestamps()
            if (mc.level != null && ++ticksSinceSave >= 6000) save()
        }
    }

    private fun onJoin(access: HolderLookup.Provider) {
        registries = access
        if (!isEnabled) return
        if (!restored) {
            restored = true
            if (saveHistory.value) restore(access)
        }
        // Hypixel island swaps also fire JOIN (new login on the same connection); only a real disconnect counts.
        val newConnection = disconnected
        disconnected = false
        if (newConnection && divider.value && chat.`cedclient$allMessages`().isNotEmpty()) {
            val server = mc.currentServer?.ip ?: "Singleplayer"
            val line = Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(server).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY))
            chat.`cedclient$insert`(line, mc.gui.guiTicks, System.currentTimeMillis())
        }
    }

    /** GuiDisconnectMixin: leaving a server is a good moment to save. */
    @JvmStatic
    fun onDisconnect() {
        disconnected = true
        save()
    }

    private var disconnected = true

    private fun save() {
        ticksSinceSave = 0
        if (!isEnabled || !saveHistory.value || !restored) return
        val access = mc.level?.registryAccess() ?: registries ?: return
        val ops = RegistryOps.create(JsonOps.INSTANCE, access)
        val messages = JsonArray()
        val times = JsonArray() // same order as messages: when each came in (epoch ms)
        // allMessages is newest first; the file keeps oldest first.
        chat.`cedclient$allMessages`().take(savedLines.value.toInt()).asReversed().forEach { message ->
            ComponentSerialization.CODEC.encodeStart(ops, message.content()).result().ifPresent {
                messages.add(it)
                times.add(receivedAt[message] ?: System.currentTimeMillis())
            }
        }
        val sent = JsonArray()
        mc.gui.chat.recentChat.forEach { sent.add(it) }
        val root = JsonObject().apply { add("messages", messages); add("times", times); add("sent", sent) }
        runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(root)) }
            .onFailure { println("[ChatFeatures] Failed to save chat: ${it.message}") }
    }

    private fun restore(access: HolderLookup.Provider) {
        if (!file.exists()) return
        runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val ops = RegistryOps.create(JsonOps.INSTANCE, access)
            // Old enough that they don't pop up over the game; they show when chat is opened.
            val addedTime = mc.gui.guiTicks - 100_000
            // Files from before Timestamps have no "times": those lines get the restore time.
            val times = root.getAsJsonArray("times")
            val now = System.currentTimeMillis()
            root.getAsJsonArray("messages")?.forEachIndexed { i, element ->
                val time = times?.takeIf { i < it.size() }?.get(i)?.asLong ?: now
                ComponentSerialization.CODEC.parse(ops, element).result().ifPresent { chat.`cedclient$insert`(it, addedTime, time) }
            }
            val recent = mc.gui.chat.recentChat
            if (recent.isEmpty()) root.getAsJsonArray("sent")?.forEach { recent.add(it.asString) }
        }.onFailure { println("[ChatFeatures] Failed to restore chat: ${it.message}") }
    }

    // ---------------------------------------------------------------- right-click menu

    object Menu {
        private class Item(val label: String, val action: (ChatScreen) -> Unit)

        private var items: List<Item> = emptyList()
        private var x = 0f
        private var y = 0f

        private const val ITEM_H = 12f
        private const val WIDTH = 110f
        private const val PAD = 3f
        private const val TEXT = 8.5f

        // "[MVP+] Name: hi", "Party > [VIP] Name: hi", "Guild > Name [Tag]: hi", "From [MVP+] Name: hi", vanilla "<Name> hi"
        private val SENDER = Regex("""^(?:(?:Guild|Party|Co-op|Officer) > |(?:From|To) )?(?:\[[^\]]+] )*(\w{1,16})(?: \[[^\]]+])*\s*:|^<(\w{1,16})> """)
        private val URL = Regex("""https?://\S+""")

        private val height get() = items.size * ITEM_H + PAD * 2

        @JvmStatic fun close() { items = emptyList() }

        /** True when the click was the menu's (opened it, picked an item, or closed it). */
        @JvmStatic
        fun onClick(screen: ChatScreen, mx: Double, my: Double, button: Int, ctrl: Boolean): Boolean {
            if (items.isNotEmpty()) {
                val inside = mx >= x && mx <= x + WIDTH && my >= y && my <= y + height
                if (inside && button == 0) {
                    val index = ((my - y - PAD) / ITEM_H).toInt()
                    items.getOrNull(index)?.action?.invoke(screen)
                }
                close()
                return inside || button == 1
            }
            if (button == 0 && ctrl && isEnabled && ctrlCopy.value) {
                val message = chat.`cedclient$messageAt`(mx, my) ?: return false
                mc.keyboardHandler.clipboard = message.content().string.replace(CODES, "")
                ActionBarHud.notify(Component.literal("Copied message").withStyle(ChatFormatting.GREEN))
                return true
            }
            if (button != 1 || !isEnabled || !menu.value) return false
            val message = chat.`cedclient$messageAt`(mx, my) ?: return false
            open(message, mx.toFloat(), my.toFloat())
            return true
        }

        private fun open(message: GuiMessage, mx: Float, my: Float) {
            val text = message.content().string.replace(CODES, "")
            val list = mutableListOf(Item("Copy Message") { mc.keyboardHandler.clipboard = text })
            SENDER.find(text)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }?.let { name ->
                list += Item("Copy Sender") { mc.keyboardHandler.clipboard = name }
                list += Item("Reply to $name") { it.insertText("/msg $name ", true) }
            }
            URL.find(text)?.let { url -> list += Item("Copy Link") { mc.keyboardHandler.clipboard = url.value } }
            items = list
            val screen = mc.window
            x = mx.coerceAtMost(screen.guiScaledWidth - WIDTH - 2f)
            y = (my - height).coerceAtLeast(2f)
        }

        @JvmStatic
        fun render(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
            if (items.isEmpty()) return
            HudCard.draw(g, 0, 0) {
                NVGRenderer.rect(x, y, WIDTH, height, Colors.gray26.rgba, 3f)
                NVGRenderer.hollowRect(x, y, WIDTH, height, 1f, ClickGUI.clickGUIColor.rgba, 3f)
                items.forEachIndexed { i, item ->
                    val iy = y + PAD + i * ITEM_H
                    if (mouseX >= x && mouseX <= x + WIDTH && mouseY >= iy && mouseY < iy + ITEM_H) {
                        NVGRenderer.rect(x + 2f, iy, WIDTH - 4f, ITEM_H, ClickGUI.clickGUIColor.rgba, 2f)
                    }
                    HudCard.text(item.label, x + 6f, iy + (ITEM_H - TEXT) / 2f, TEXT, Colors.WHITE.rgba)
                }
            }
        }
    }
}

package ced.cedclient.features.impl.misc

import ced.cedclient.config.CedPaths
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.features.impl.render.ChatEmotes
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.impl.render.nametag.NametagFormatting
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.ui.clickgui.ChatAlertsPopup
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Color
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import java.io.File
import java.util.Optional

/**
 * Bell + highlight when your name (or one of your extra words) shows up in chat, plus user-made [ChatAlert]s
 * (sound, highlight, line replacement, clipboard, and a message on the movable Chat Alerts HUD).
 * Defaults: note block bell, gold highlight.
 * Hooked from ChatComponentMixin ahead of CustomNametag, so it still sees your real username.
 * Part of [ChatFeatures] (was its own "Chat Ping" module): its settings live there, under the Ping / Alerts popups.
 */
object ChatPing : HudElement {
    private val isEnabled get() = ChatFeatures.isEnabled

    private val ownName = BooleanSetting("Ping On Your Name", true)
    private val extraWords = TextSetting("Extra Words", "", "Comma-separated, e.g. ced,carry", maxLength = 128)
    private val ignoreOwn = BooleanSetting("Ignore Own Messages", true, "No bell for lines you sent yourself.")
    private val highlight = BooleanSetting("Highlight", true)
    private val color = ColorSetting("Highlight Color", Color(255, 196, 0)).withDependency { highlight.value }
    private val sound = BooleanSetting("Sound", true)
    private val volume = NumberSetting("Volume", 1.0, 0.0, 1.0, 0.05).withDependency { sound.value }
    private val nameHud = TextSetting("Name HUD Message", "", "Shown on the Chat Alerts HUD. & colour codes, {line} = the chat line. Empty = none.", maxLength = 128)
    private val hudBackground = BooleanSetting("HUD Background", true, "Card behind the Chat Alerts HUD messages.")
    private val hudMcFont = BooleanSetting("HUD Minecraft Font", false, "Normal Minecraft text instead of the CedClient font.")
    private val hudShadow = BooleanSetting("HUD Text Shadow", true).withDependency { hudMcFont.value }
    private val hudAlign = DropdownSetting("HUD Alignment", listOf("Left", "Center", "Right"), "Center")
    val test = ActionSetting("Play Test Sound") { playSound(BELL, volume.value.toFloat(), 1f) }
    val configure = ActionSetting("Alerts", "Your own chat alerts: trigger words, sound, highlight, HUD message, copy.") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(ChatAlertsPopup())
    }

    /** Shown in Chat Features' Ping popup (saved under the setting names Chat Ping used). */
    val nameSettings: List<RenderableSetting<*>> = listOf(ownName, extraWords, ignoreOwn, highlight, color, sound, volume)
    val hudSettings: List<RenderableSetting<*>> = listOf(nameHud, hudBackground, hudMcFont, hudShadow, hudAlign)

    private const val BELL = "minecraft:block.note_block.bell"

    val soundPresets = linkedMapOf(
        "Bell" to BELL,
        "Pling" to "minecraft:block.note_block.pling",
        "Chime" to "minecraft:block.note_block.chime",
        "Level Up" to "minecraft:entity.player.levelup",
        "XP Orb" to "minecraft:entity.experience_orb.pickup",
        "Anvil" to "minecraft:block.anvil.land",
        "Totem" to "minecraft:item.totem.use",
        "None" to "",
    )

    // "[MVP+] Name: hi", "Party > [VIP] Name [Tag]: hi", "Guild > Name: hi", vanilla "<Name> hi"
    private val senderRegex = Regex("""^(?:(?:Guild|Party|Co-op|Officer) > )?(?:\[[^\]]+] )*(\w{1,16})(?: \[[^\]]+])*\s*:|^<(\w{1,16})> """)

    // ---- alerts ----

    val alerts = mutableListOf<ChatAlert>()
    private val gson = Gson()
    private val alertsFile: File by lazy { CedPaths.file("chat_alerts.json") }

    init { loadAlerts() }

    fun addAlert(triggerWords: String): ChatAlert {
        val alert = ChatAlert()
        alert.triggers.value = triggerWords
        alerts += alert
        saveAlerts()
        return alert
    }

    fun removeAlert(alert: ChatAlert) {
        alerts.remove(alert)
        saveAlerts()
    }

    /** Also called when the ClickGUI closes, so edits made in an alert's settings popup are kept. */
    @JvmStatic
    fun saveAlerts() {
        try {
            alertsFile.parentFile?.mkdirs()
            val array = JsonArray()
            alerts.forEach { array.add(it.toJson(gson)) }
            alertsFile.writeText(gson.toJson(array))
        } catch (e: Exception) {
            println("[ChatPing] Failed to save alerts: ${e.message}")
        }
    }

    private fun loadAlerts() {
        try {
            if (!alertsFile.exists()) return
            val array = JsonParser.parseString(alertsFile.readText()).asJsonArray
            alerts.clear()
            array.forEach { alerts += ChatAlert.fromJson(it.asJsonObject, gson) }
        } catch (e: Exception) {
            println("[ChatPing] Failed to load alerts: ${e.message}")
        }
    }

    // ---- matching ----

    private var nameKey = ""
    private var nameRegex: Regex? = null
    private var lastPing = 0L

    /** Whole-word, any-case regex for [words]; "§b" right before a word also counts as a boundary. Null if empty. */
    fun wordRegex(words: List<String>): Regex? {
        val list = words.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return null
        val alt = list.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        return Regex("""(?:(?<=§[0-9a-fk-or])|(?<![A-Za-z0-9_]))(?:$alt)(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)
    }

    private fun nameMatcher(): Regex? {
        val words = extraWords.value.split(',').toMutableList()
        if (ownName.value) Minecraft.getInstance().user?.name?.let { words += it }
        val key = words.joinToString("\u0000")
        if (key != nameKey) {
            nameKey = key
            nameRegex = wordRegex(words)
        }
        return nameRegex
    }

    /** Called from ChatComponentMixin for every chat line; returns the line with highlights / replacement applied. */
    @JvmStatic
    fun transform(original: Component): Component {
        if (!isEnabled) return original
        val text = ChatMessageEvent.stripFormatting(original.string)
        // ChatEmotes.liveChat: only real incoming chat fires sounds / HUD / clipboard, not another mod replaying history.
        val live = ChatEmotes.liveChat
        val own = isOwnMessage(text)
        var line = original

        nameMatcher()?.let { regex ->
            if (!regex.containsMatchIn(text)) return@let
            if (live && !(ignoreOwn.value && own)) {
                val now = System.currentTimeMillis()
                if (now - lastPing > 250) {
                    lastPing = now
                    if (sound.value) playSound(BELL, volume.value.toFloat(), 1f)
                    showOnHud(nameHud.value, text, 3.0)
                }
            }
            if (highlight.value) line = highlight(line, regex, color.value)
        }

        for (alert in alerts) {
            if (!alert.enabled) continue
            val regex = alert.matcher() ?: continue
            if (!regex.containsMatchIn(text) || alert.excluded(text)) continue
            if (live && !(alert.ignoreOwn.value && own)) fire(alert, text)
            if (alert.replaceLine.value.isNotBlank()) {
                line = NametagFormatting.parse(alert.replaceLine.value.replace("{line}", text))
            } else if (alert.highlight.value) {
                line = highlight(line, regex, alert.color.value)
            }
        }
        return line
    }

    /** Sound, HUD message and clipboard for [alert] (respecting its cooldown). Also the editor's Test button. */
    fun fire(alert: ChatAlert, text: String) {
        val now = System.currentTimeMillis()
        if (now - alert.lastFired < alert.cooldown.value * 1000) return
        alert.lastFired = now
        val id = if (alert.sound.value == "Custom") alert.customSound.value else soundPresets[alert.sound.value].orEmpty()
        playSound(id, alert.volume.value.toFloat(), alert.pitch.value.toFloat())
        showOnHud(alert.hudMessage.value, text, alert.hudSeconds.value)
        if (alert.copyLine.value) Minecraft.getInstance().keyboardHandler.clipboard = text
    }

    /** A chat line you sent: the sender before the colon is you. */
    private fun isOwnMessage(text: String): Boolean {
        val m = senderRegex.find(text) ?: return false
        val sender = m.groupValues[1].ifEmpty { m.groupValues[2] }
        return sender.equals(Minecraft.getInstance().user?.name, ignoreCase = true)
    }

    private fun playSound(id: String, volume: Float, pitch: Float) {
        if (id.isBlank()) return
        val event = Identifier.tryParse(id)?.let { BuiltInRegistries.SOUND_EVENT.getValue(it) } ?: return
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(event, pitch, volume))
    }

    // ---- highlighting ----

    private fun highlight(original: Component, regex: Regex, color: Color): Component {
        val out = Component.empty()
        var changed = false
        val rgb = color.rgba and 0xFFFFFF
        original.visit({ style, segment ->
            if (appendSegment(out, style, segment, regex, rgb)) changed = true
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return if (changed) out else original
    }

    /** Appends [segment] in [style], with every match split out in [rgb]. True if anything matched. */
    private fun appendSegment(out: MutableComponent, style: Style, segment: String, regex: Regex, rgb: Int): Boolean {
        val matches = regex.findAll(segment).filter { it.value.isNotEmpty() }.toList()
        if (matches.isEmpty()) {
            if (segment.isNotEmpty()) out.append(Component.literal(segment).withStyle(style))
            return false
        }
        var pos = 0
        for (m in matches) {
            if (m.range.first < pos) continue
            if (m.range.first > pos) out.append(Component.literal(segment.substring(pos, m.range.first)).withStyle(style))
            out.append(Component.literal(m.value).withStyle(style.withColor(TextColor.fromRgb(rgb))))
            pos = m.range.last + 1
            // Text after the match is a new literal: carry the §-codes that were active in the original segment.
            if (pos < segment.length) {
                val codes = activeCodes(segment, pos)
                if (codes.isNotEmpty()) {
                    val next = matches.firstOrNull { it.range.first >= pos }?.range?.first ?: segment.length
                    out.append(Component.literal(codes + segment.substring(pos, next)).withStyle(style))
                    pos = next
                }
            }
        }
        if (pos < segment.length) out.append(Component.literal(segment.substring(pos)).withStyle(style))
        return true
    }

    /** The legacy §-codes still in effect at [end]: last colour (or reset) plus any formats after it. */
    private fun activeCodes(s: String, end: Int): String {
        val codes = StringBuilder()
        var i = s.indexOf('§')
        while (i in 0 until end - 1) {
            val c = s[i + 1].lowercaseChar()
            when (c) {
                in '0'..'9', in 'a'..'f' -> { codes.setLength(0); codes.append('§').append(c) }
                'r' -> codes.setLength(0)
                in 'k'..'o' -> codes.append('§').append(c)
            }
            i = s.indexOf('§', i + 2)
        }
        return codes.toString()
    }

    // ---- Chat Alerts HUD ----

    private class HudLine(val text: Component, val until: Long)
    private val hudLines = ArrayDeque<HudLine>()

    private fun showOnHud(message: String, line: String, seconds: Double) {
        if (message.isBlank()) return
        hudLines.addFirst(HudLine(NametagFormatting.parse(message.replace("{line}", line)), System.currentTimeMillis() + (seconds * 1000).toLong()))
        while (hudLines.size > MAX_HUD_LINES) hudLines.removeLast()
    }

    override val label = "Chat Alerts"
    private const val MAX_HUD_LINES = 5
    private const val FADE_MS = 400L
    private const val DEFAULT_X = 564
    private const val DEFAULT_Y = 148
    private const val DEFAULT_SCALE = 2.3f

    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = DEFAULT_SCALE
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val hudFile: File by lazy { CedPaths.file("chat_alerts_hud.json") }

    init {
        try {
            if (hudFile.exists()) gson.fromJson(hudFile.readText(), HudPosition::class.java)?.let {
                panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.5f, 5f)
            }
        } catch (e: Exception) {
            println("[ChatPing] Failed to load HUD position: ${e.message}")
        }
    }

    override fun save() {
        try {
            hudFile.parentFile?.mkdirs()
            hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale)))
        } catch (e: Exception) {
            println("[ChatPing] Failed to save HUD position: ${e.message}")
        }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = DEFAULT_SCALE }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.5f, 5f) }

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        val now = System.currentTimeMillis()
        hudLines.removeAll { it.until <= now }
        if (!isEnabled || hudLines.isEmpty()) return
        draw(g, hudLines.toList(), now)
    }

    /** Editor preview: the live messages, or a sample when there are none. */
    override fun renderInternal(g: GuiGraphicsExtractor) {
        val now = System.currentTimeMillis()
        val lines = hudLines.toList().ifEmpty { listOf(HudLine(NametagFormatting.parse("&eChat Alert"), Long.MAX_VALUE)) }
        draw(g, lines, now)
    }

    /** Left edge of a [lineWidth] wide line in a [width] wide panel with [pad] padding, per HUD Alignment. */
    private fun alignX(lineWidth: Float, width: Float, pad: Float) = when (hudAlign.value) {
        "Left" -> pad
        "Right" -> width - pad - lineWidth
        else -> (width - lineWidth) / 2f
    }

    /** Each message keeps its & colours and fades out at the end. */
    private fun draw(g: GuiGraphicsExtractor, lines: List<HudLine>, now: Long) {
        val alphas = lines.map { ((it.until - now).toFloat() / FADE_MS).coerceIn(0.05f, 1f) }
        if (hudMcFont.value) drawVanilla(g, lines, alphas) else drawCard(g, lines, alphas)
    }

    /** NanoVG card like the other HUD panels, in the CedClient font. */
    private fun drawCard(g: GuiGraphicsExtractor, lines: List<HudLine>, alphas: List<Float>) {
        val s = panelScale
        val size = 11f * s
        val lineH = 14f * s
        val pad = 7f * s
        val widths = lines.map { HudCard.width(it.text, size) }
        val width = widths.max() + pad * 2
        val height = lines.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()

        HudCard.draw(g, panelX, panelY) {
            if (hudBackground.value) HudCard.card(width, height, s, alphas.max())
            lines.forEachIndexed { i, line ->
                HudCard.componentText(line.text, alignX(widths[i], width, pad), pad + i * lineH, size, alphas[i])
            }
        }
    }

    /** Plain Minecraft text, with a chat-style backdrop when HUD Background is on. */
    private fun drawVanilla(g: GuiGraphicsExtractor, lines: List<HudLine>, alphas: List<Float>) {
        val font = Minecraft.getInstance().font
        val lineH = font.lineHeight + 1
        val pad = 4
        val widths = lines.map { font.width(it.text) }
        val width = widths.max() + pad * 2
        val height = lines.size * lineH + pad * 2 - 1
        lastWidth = width
        lastHeight = height

        g.pose().pushMatrix()
        g.pose().translate(panelX.toFloat(), panelY.toFloat())
        g.pose().scale(panelScale, panelScale)
        if (hudBackground.value) g.fill(0, 0, width, height, HudCard.withAlpha(0x80000000.toInt(), alphas.max()))
        lines.forEachIndexed { i, line ->
            val x = alignX(widths[i].toFloat(), width.toFloat(), pad.toFloat()).toInt()
            g.text(font, line.text, x, pad + i * lineH, HudCard.withAlpha(-1, alphas[i]), hudShadow.value)
        }
        g.pose().popMatrix()
    }
}

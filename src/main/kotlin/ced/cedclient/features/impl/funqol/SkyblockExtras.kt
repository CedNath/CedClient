package ced.cedclient.features.impl.funqol

import ced.cedclient.utils.Keys
import ced.cedclient.config.CedPaths
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.utils.Color
import ced.cedclient.utils.Debug
import ced.cedclient.utils.PingTracker
import ced.cedclient.utils.loreLines
import ced.cedclient.utils.skyblockData
import ced.cedclient.utils.skyblockId
import ced.cedclient.utils.skyblockUuid
import ced.cedclient.utils.cleanName
import ced.cedclient.utils.isRecombobulated
import ced.cedclient.utils.reforge
import ced.cedclient.utils.hotPotatoBooks
import ced.cedclient.utils.stars
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/** Title + sound shared by the alert modules below. */
internal object Alerts {
    private val mc get() = Minecraft.getInstance()

    fun title(tag: String, title: String, subtitle: String, color: Int, sound: Boolean, pitch: Float) {
        if (mc.player == null) return
        mc.gui.hud.setTimes(5, 40, 10)
        mc.gui.hud.setTitle(Component.literal(title).withColor(color))
        mc.gui.hud.setSubtitle(Component.literal(subtitle))
        Debug.line(tag, "alert: $title $subtitle")
        if (sound) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, 1f))
    }
}

/** Shared HUD plumbing for the small "label  value" panels in this file: position file, scale, card. */
abstract class RowHud(name: String, description: String, private val key: String, private val defX: Int, private val defY: Int) :
    Module(name, Category.Skyblock, description), HudElement {

    protected val mc get() = Minecraft.getInstance()

    /** Rows to draw, or null to hide the panel. Left text, right text, colour. */
    protected abstract fun rows(): List<Triple<String, String, Int>>?
    protected abstract fun preview(): List<Triple<String, String, Int>>

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || mc.gui.hud.isHidden) return
        draw(g, rows() ?: return)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) = draw(g, rows() ?: preview())

    private val outline = BooleanSetting("Text Outline", true, "A black outline around the text so it reads on any background.")
    init { addSettings(outline) }

    private fun text(t: String, x: Float, y: Float, size: Float, color: Int, s: Float) {
        if (outline.value) {
            val o = 0.9f * s
            for ((dx, dy) in listOf(-o to 0f, o to 0f, 0f to -o, 0f to o, -o to -o, o to o, -o to o, o to -o)) HudCard.text(t, x + dx, y + dy, size, 0xFF000000.toInt())
        }
        HudCard.text(t, x, y, size, color)
    }

    private fun draw(g: GuiGraphicsExtractor, lines: List<Triple<String, String, Int>>) {
        val s = panelScale
        val size = 10f * s
        val lineH = 12f * s
        val pad = 6f * s
        val gap = 10f * s
        val leftW = lines.maxOf { HudCard.width(it.first, size) }
        val rightW = lines.maxOf { HudCard.width(it.second, size) }
        val width = HudCard.steady(label, leftW + gap + rightW + pad * 2, s)
        val height = lines.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            HudCard.card(width, height, s)
            lines.forEachIndexed { i, (name, value, color) ->
                val y = pad + i * lineH
                text(name, pad, y, size, color, s)
                text(value, width - pad - HudCard.width(value, size), y, size, color, s)
            }
        }
    }

    override val label: String get() = name
    override var panelX = defX
    override var panelY = defY
    override var panelScale = 1f
    override var lastWidth = 0
        protected set
    override var lastHeight = 0
        protected set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile by lazy { CedPaths.hud(key) }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = defX; panelY = defY; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }

    protected fun loadPosition() {
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
    }
}



/** Ping, FPS, TPS and the clock in one small panel. */
object PingFpsHud : RowHud("Ping / FPS", "HUD with your ping, FPS, server TPS and the time.", "ping_fps", 3, 340) {
    private val showPing = BooleanSetting("Ping", true, "Round trip to the server.")
    private val showFps = BooleanSetting("FPS", true)
    private val showTps = BooleanSetting("TPS", true, "Server ticks per second (20 is perfect).")
    private val showTime = BooleanSetting("Time", true, "Your computer's clock.")
    private val use24Hour = BooleanSetting("24-Hour Format", false).withDependency { showTime.value }
    private val showSeconds = BooleanSetting("Show Seconds", false).withDependency { showTime.value }
    private val okColor = ColorSetting("Good Color", Color(85, 255, 85), "Ping under 100 ms, FPS over 60.")
    private val badColor = ColorSetting("Bad Color", Color(255, 85, 85), "Ping over 200 ms, FPS under 30.")
    private val midColor = ColorSetting("Mid Color", Color(255, 255, 85), "In between.")

    private fun ping(): Int {
        val live = PingTracker.latest()
        if (live > 0) return live
        return runCatching { mc.currentServer?.ping?.toInt() ?: -1 }.getOrDefault(-1)
    }

    private fun pingColor(p: Int) = (if (p < 100) okColor else if (p > 200) badColor else midColor).value.rgba
    private fun fpsColor(f: Int) = (if (f > 60) okColor else if (f < 30) badColor else midColor).value.rgba

    override fun rows(): List<Triple<String, String, Int>>? {
        val out = mutableListOf<Triple<String, String, Int>>()
        if (showPing.value) ping().takeIf { it > 0 }?.let { out += Triple("Ping", "$it ms", pingColor(it)) }
        if (showFps.value) mc.fps.let { out += Triple("FPS", it.toString(), fpsColor(it)) }
        if (showTps.value) ced.cedclient.utils.TpsTracker.currentTps().let { out += Triple("TPS", "%.1f".format(java.util.Locale.ROOT, it), tpsColor(it)) }
        if (showTime.value) out += Triple("Time", clock(), 0xFFFFFFFF.toInt())
        return out.ifEmpty { null }
    }

    private fun tpsColor(t: Double) = (if (t >= 19.0) okColor else if (t < 15.0) badColor else midColor).value.rgba

    private fun clock(): String {
        val pattern = (if (use24Hour.value) "HH:mm" else "h:mm") + (if (showSeconds.value) ":ss" else "") + (if (use24Hour.value) "" else " a")
        return java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH))
    }

    override fun preview() = listOf(
        Triple("Ping", "42 ms", okColor.value.rgba), Triple("FPS", "144", okColor.value.rgba),
        Triple("TPS", "20.0", okColor.value.rgba), Triple("Time", "9:41 PM", 0xFFFFFFFF.toInt()),
    )

    init {
        addSettings(showPing, showFps, showTps, showTime, use24Hour, showSeconds, okColor, midColor, badColor)
        loadPosition()
    }
}


















/** Extra tooltip lines read off the item's SkyBlock data: item id, UUID, dungeon quality. */
object ItemInfoTooltip : Module("Item Info Tooltip", Category.Visuals, "Adds the SkyBlock item id, UUID and dungeon quality to tooltips.") {
    private val showId = BooleanSetting("Item ID", false, "The SkyBlock item id, in the tooltip.")
    private val showUuid = BooleanSetting("UUID", false)
    private val showQuality = BooleanSetting("Dungeon Quality", true, "Quality out of 50 and the floor it dropped on.")
    private val shiftOnly = BooleanSetting("Only With Shift", false, "Only show the extra lines while holding Shift.")

    /** A quiet line in the theme: the HUD accent, darkened. */
    private fun dim(text: String) = Component.literal(text).withColor(ced.cedclient.features.impl.misc.Theme.hudBorderRgba and 0xFFFFFF)
    private val showReforge = BooleanSetting("Reforge", false)
    private val showPotato = BooleanSetting("Hot Potato Books", false)
    private val showRecomb = BooleanSetting("Recombobulated", false)
    private val showStars = BooleanSetting("Dungeon Stars", false)

    init {
        addSettings(showId, showUuid, showQuality, showReforge, showPotato, showRecomb, showStars, shiftOnly)
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!isEnabled) return@register
            val data = stack.skyblockData ?: return@register
            val window = Minecraft.getInstance().window
            if (shiftOnly.value && !(Keys.isDown(Keys.LEFT_SHIFT) || Keys.isDown(Keys.RIGHT_SHIFT))) return@register
            if (showQuality.value) {
                val quality = data.getIntOr("baseStatBoostPercentage", -1)
                if (quality >= 0) {
                    val floor = data.getIntOr("item_tier", -1)
                    val accent = ced.cedclient.features.impl.misc.Theme.hudAccentRgba and 0xFFFFFF
                    val tier = if (quality >= 50) 0xFFAA00 else if (quality >= 40) 0x55FF55 else if (quality >= 25) 0xFFFF55 else 0xFF5555
                    val q = Component.literal("Quality: ").withColor(accent)
                        .append(Component.literal("$quality").withColor(tier)).append(Component.literal("/50").withColor(accent))
                    if (floor >= 0) q.append(dim(" (Floor $floor)"))
                    // Right at the top: under the name, and under the "Item Protected" line when there is one.
                    val at = lines.indexOfFirst { it.string.startsWith("Item Protected") }.let { if (it >= 0) it + 1 else minOf(1, lines.size) }
                    lines.add(at, q)
                }
            }
            if (showReforge.value) stack.reforge?.let { lines.add(dim("Reforge: ${it.replaceFirstChar { c -> c.uppercase() }}")) }
            if (showPotato.value) stack.hotPotatoBooks.takeIf { it > 0 }?.let { lines.add(dim("Hot Potato Books: $it")) }
            if (showStars.value) stack.stars.takeIf { it > 0 }?.let { lines.add(dim("Stars: $it")) }
            if (showRecomb.value && stack.isRecombobulated) lines.add(dim("Recombobulated"))
            if (showId.value) stack.skyblockId?.let { lines.add(dim("ID: $it")) }
            if (showUuid.value) stack.skyblockUuid?.let { lines.add(dim("UUID: $it")) }
        }
    }
}



/**
 * Pet Display: the pet you have out. Read from the tab list's pet line ("[Lvl 100] Ender Dragon", coloured by rarity there,
 * so the name takes that colour); the "You summoned / despawned your ..." chat lines fill in when the tab has no pet line.
 * shortcut: the tab-list pet widget has to be on (/tab widgets) for the level; without it only the name shows.
 */
object PetDisplay : RowHud("Pet Display", "HUD with the pet you have out: name in its rarity colour and level.", "pet_display", 3, 300) {
    private val showLevel = BooleanSetting("Show Level", true, "The pet's level, on the right of its name.")
    private val rarityColor = BooleanSetting("Rarity Color", true, "Colour the name by the pet's rarity (from the tab list). Off: the Color below.")
    private val color = ColorSetting("Color", Color(255, 170, 0), "Text colour when Rarity Color is off or unknown.")
    private val hideWhenNone = BooleanSetting("Hide When No Pet", true, "Hide the panel while no pet is out.")

    private val TAB = Regex("""^\[Lvl (\d+)] (.+?)(?: ✦)?$""")
    private val SUMMON = Regex("""You summoned your (.+?)!""")
    private val DESPAWN = Regex("""You despawned your (.+?)!""")
    private var chatPet: String? = null
    private var tabSeen = 0L

    private class Found(val name: String, val level: String, val rgb: Int?)
    private var found: Found? = null
    private var scanVersion = -1

    private fun scan() {
        val v = ced.cedclient.utils.TabListCache.version
        if (v == scanVersion) return
        scanVersion = v
        found = null
        for (e in ced.cedclient.utils.TabListCache.entries) {
            val m = TAB.find(e.stripped.trim()) ?: continue
            // The "[Lvl 100]" part is grey; the rarity colour is on the segment holding the name, so take the last coloured one.
            var rgb: Int? = null
            e.info.tabListDisplayName?.visit({ style, text ->
                if (text.isNotBlank() && text.trim() != "✦" && !text.startsWith("[")) style.color?.let { rgb = it.value }
                java.util.Optional.empty<Unit>()
            }, net.minecraft.network.chat.Style.EMPTY)
            found = Found(m.groupValues[2], m.groupValues[1], rgb)
            tabSeen = System.currentTimeMillis()
            break
        }
    }

    override fun rows(): List<Triple<String, String, Int>>? {
        scan()
        val f = found
        val name = f?.name ?: chatPet
        if (name == null) return if (hideWhenNone.value) null else listOf(Triple("No pet", "", color.value.rgba))
        val c = if (rarityColor.value && f?.rgb != null) 0xFF000000.toInt() or f.rgb else color.value.rgba
        // One row: the name (in its rarity color) and, on the right, the level.
        return listOf(Triple(name, if (showLevel.value && f != null) "Lvl ${f.level}" else "", c))
    }

    override fun preview() = listOf(Triple("Ender Dragon", "Lvl 100", 0xFFFFAA00.toInt()))

    init {
        addSettings(showLevel, rarityColor, color, hideWhenNone)
        loadPosition()
        on<ChatMessageEvent> {
            if (!isEnabled) return@on
            val text = it.unformattedText
            SUMMON.find(text)?.let { m -> chatPet = m.groupValues[1]; Debug.line("Pet Display", "summoned $chatPet") }
            DESPAWN.find(text)?.let { chatPet = null; found = null; Debug.line("Pet Display", "despawned") }
        }
    }
}

package ced.cedclient.features.impl.misc

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.Debug
import com.google.gson.Gson
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Optional

/*
 * Stat Bars and Skill Display: SkyBlock's action bar ("1,234/1,500❤   300❈ Defense   500/500✎ Mana", "+12.3 Mining (45.67%)")
 * read into movable HUD panels, optionally cut out of the action bar. Parsing is in ActionBarStats, which the Hud mixin
 * (HudActionBarMixin) feeds every action bar message. Nothing runs unless one of the two modules is on.
 * Not testable outside Hypixel: the patterns follow the action bar as it looks in game and skip anything that doesn't match.
 */

object ActionBarStats {
    class Stat(val cur: Double, val max: Double)

    var health: Stat? = null; private set
    var mana: Stat? = null; private set
    var vitality: Stat? = null; private set
    var overflow: Double? = null; private set
    var defence: Double? = null; private set
    var statsAt = 0L; private set
    private var lastUnreadLog = 0L

    var skill: String? = null; private set
    var skillGain = 0.0; private set
    var skillPercent = -1.0; private set
    var skillAt = 0L; private set

    private const val NUM = "[\\d,]+(?:\\.\\d+)?"
    private val numRe = Regex(NUM)
    private val healthRe = Regex("$NUM/$NUM\\s*❤|❤\\s*$NUM/$NUM")
    private val manaRe = Regex("$NUM/$NUM\\s*✎|✎\\s*$NUM/$NUM")
    private val defenceRe = Regex("$NUM\\s*❈|❈\\s*$NUM")
    private val pairRe = Regex("$NUM/$NUM")
    private val singleRe = Regex("(?<![\\d,./])$NUM(?![\\d,./])")
    private val skillRe = Regex("\\+($NUM)\\s+([A-Za-z]+)\\s+\\(([^)]*)\\)")

    private val LEGACY = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    )

    private fun nearestLegacy(rgb: Int): Int = LEGACY.minBy {
        val dr = (it shr 16 and 255) - (rgb shr 16 and 255)
        val dg = (it shr 8 and 255) - (rgb shr 8 and 255)
        val db = (it and 255) - (rgb and 255)
        dr * dr + dg * dg + db * db
    }

    private fun nums(s: String) = numRe.findAll(s).map { it.value.replace(",", "").toDouble() }.toList()

    /** The range of a match plus the spaces after it, so cutting it out doesn't leave a gap. */
    private fun withTrailing(text: String, range: IntRange): IntRange {
        var end = range.last + 1
        // The newer action bar draws each stat's icon as a private-use font glyph right after the number.
        if (end < text.length && text[end].code in 0xE000..0xF8FF) end++
        while (end < text.length && text[end] == ' ') end++
        return range.first until end
    }

    /** Mixin: every action bar message. Returns the message to show (stats cut out where a module asks for it). */
    @JvmStatic
    fun process(message: Component): Component {
        val bars = StatBars.isEnabled
        val skills = SkillDisplay.isEnabled
        if (!bars && !skills) return message
        // Hypixel sends the bar as plain text with legacy "§6" colour codes inline, so the string carries the codes. Work on the
        // text without them (text), keeping where each character was (origIdx) and its colour, and map cuts back at the end.
        val rawText = message.string
        val styleColors = IntArray(rawText.length) { -1 }
        run {
            var at = 0
            message.visit(FormattedText.StyledContentConsumer<Unit> { style, str ->
                // The bar may also use real RGB styles: snap to the nearest of the 16 legacy colours.
                val c = style.color?.value?.let { nearestLegacy(it) } ?: -1
                for (i in str.indices) if (at + i < styleColors.size) styleColors[at + i] = c
                at += str.length
                Optional.empty()
            }, Style.EMPTY)
        }
        val plain = StringBuilder()
        val origList = ArrayList<Int>()
        val colorList = ArrayList<Int>()
        run {
            var inline = -1
            var i = 0
            while (i < rawText.length) {
                if (rawText[i] == '§' && i + 1 < rawText.length) {
                    val code = rawText[i + 1].lowercaseChar()
                    val idx = "0123456789abcdef".indexOf(code)
                    if (idx >= 0) inline = LEGACY[idx] else if (code == 'r') inline = -1
                    i += 2
                    continue
                }
                plain.append(rawText[i]); origList += i; colorList += if (inline != -1) inline else styleColors[i]
                i++
            }
        }
        val text = plain.toString()
        val origIdx = origList.toIntArray()
        val colors = colorList.toIntArray()
        val cut = ArrayList<IntRange>()
        val now = System.currentTimeMillis()

        fun colored(pair: Boolean, vararg rgb: Int): MatchResult? =
            (if (pair) pairRe else singleRe).findAll(text).firstOrNull { colors.getOrElse(it.range.first) { -1 } in rgb }

        if (bars) {
            (healthRe.find(text) ?: colored(true, 0xFFAA00, 0xFF5555))?.let { m ->
                nums(m.value).takeIf { it.size == 2 }?.let { health = Stat(it[0], it[1]); statsAt = now }
                if (StatBars.hideHealth.value) cut += withTrailing(text, m.range)
            }
            (manaRe.find(text) ?: colored(true, 0x55FFFF))?.let { m ->
                nums(m.value).takeIf { it.size == 2 }?.let { mana = Stat(it[0], it[1]); statsAt = now }
                if (StatBars.hideMana.value) cut += withTrailing(text, m.range)
            }
            colored(true, 0xAA0000)?.let { m ->
                nums(m.value).takeIf { it.size == 2 }?.let { vitality = Stat(it[0], it[1]); statsAt = now }
                if (StatBars.hideVitality.value) cut += withTrailing(text, m.range)
            }
            colored(false, 0x00AAAA)?.let { m ->
                nums(m.value).singleOrNull()?.let { overflow = it; statsAt = now }
                if (StatBars.hideOverflow.value) cut += withTrailing(text, m.range)
            }
            (defenceRe.find(text) ?: colored(false, 0x55FF55))?.let { m ->
                nums(m.value).singleOrNull()?.let { defence = it; statsAt = now }
                if (StatBars.hideDefence.value) {
                    // "300❈ Defense": take the word with it.
                    val word = Regex("^\\s*Defense\\s*").find(text.substring(m.range.last + 1))
                    cut += if (word != null) m.range.first until (m.range.last + 1 + word.value.length) else withTrailing(text, m.range)
                }
            }
        }
        if (skills) {
            skillRe.find(text)?.let { m ->
                val inside = m.groupValues[3]
                val values = nums(inside)
                val percent = when {
                    inside.trim().endsWith("%") && values.size == 1 -> values[0]
                    values.size == 2 && values[1] > 0 -> values[0] / values[1] * 100
                    else -> -1.0
                }
                skill = m.groupValues[2]
                skillGain = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                skillPercent = percent
                skillAt = now
                Debug.line("Skill Display", "${m.groupValues[2]} +$skillGain, $percent%")
                if (SkillDisplay.hideFromBar.value) cut += withTrailing(text, m.range)
            }
        }
        // Only when nothing was recognised, and not more than once in a while: the bar arrives several times a second.
        if (bars && statsAt != now && now - lastUnreadLog > 30_000) {
            lastUnreadLog = now
            Debug.line("Stat Bars", "could not read the action bar: $text")
        }
        if (cut.isEmpty()) return message
        // Back to offsets in message.string, taking each cut stat's own colour code with it.
        val origCuts = cut.filter { it.first < origIdx.size }.map { r ->
            var start = origIdx[r.first]
            while (start >= 2 && rawText[start - 2] == '§') start -= 2
            start..origIdx[minOf(r.last, origIdx.size - 1)]
        }
        return remove(message, origCuts)
    }

    /** [message] without the characters in [ranges] (offsets into message.string), styles of the rest kept. */
    private fun remove(message: Component, ranges: List<IntRange>): Component {
        val out = Component.empty()
        var pos = 0
        message.visit(FormattedText.StyledContentConsumer<Unit> { style, s ->
            val start = pos
            pos += s.length
            val kept = StringBuilder()
            for (i in s.indices) if (ranges.none { (start + i) in it }) kept.append(s[i])
            if (kept.isNotEmpty()) out.append(Component.literal(kept.toString()).withStyle(style))
            Optional.empty()
        }, Style.EMPTY)
        return out
    }
}

private data class PanelPos(val x: Int, val y: Int, val scale: Float = 1f)

/** A movable HUD panel in the shared HUD editor. [draw] gets preview = true in the editor, where sample data is shown. */
abstract class StatPanel(
    name: String, description: String, fileKey: String, private val defX: Int, private val defY: Int
) : Module(name, Category.HUD, description), HudElement {
    override val label: String = name
    override var panelX: Int = defX
    override var panelY: Int = defY
    override var panelScale: Float = 1f
    override var lastWidth: Int = 80
        protected set
    override var lastHeight: Int = 12
        protected set

    private val gson = Gson()
    private val saveFile by lazy { CedPaths.hud(fileKey) }

    protected abstract fun draw(g: GuiGraphicsExtractor, preview: Boolean)

    private fun drawScaled(g: GuiGraphicsExtractor, preview: Boolean) {
        g.pose().pushMatrix()
        g.pose().translate(panelX.toFloat(), panelY.toFloat())
        g.pose().scale(panelScale, panelScale)
        draw(g, preview)
        g.pose().popMatrix()
    }

    override fun renderInternal(g: GuiGraphicsExtractor) = drawScaled(g, true)

    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.5f, 3f) }
    override fun resetPosition() { panelX = defX; panelY = defY; panelScale = 1f }

    override fun save() {
        runCatching {
            saveFile.parentFile?.mkdirs()
            saveFile.writeText(gson.toJson(PanelPos(panelX, panelY, panelScale)))
        }.onFailure { println("[$label] Failed to save position: ${it.message}") }
    }

    init {
        runCatching {
            if (saveFile.exists()) gson.fromJson(saveFile.readText(), PanelPos::class.java)?.let {
                panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.5f, 3f)
            }
        }.onFailure { println("[$label] Failed to load position: ${it.message}") }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("cedclient", fileKey)) { g, _ ->
            if (isEnabled && Minecraft.getInstance().gui.screen() !is AbstractContainerScreen<*>) drawScaled(g, false)
        }
    }
}

// ------------------------------------------------------------------------------------------------ Stat Bars

private const val STAT_STALE_MS = 6000L

/** A movable HUD panel that belongs to the Stat Bars module (it has no module switch of its own). */
abstract class StatHudPanel(
    override val label: String, fileKey: String, private val defY: Int, private val defX: Int = 10
) : HudElement {
    override var panelX: Int = defX
    override var panelY: Int = defY
    override var panelScale: Float = 1f
    override var lastWidth: Int = 80
        protected set
    override var lastHeight: Int = 12
        protected set

    private val gson = Gson()
    private val saveFile by lazy { CedPaths.hud(fileKey) }

    protected abstract fun draw(g: GuiGraphicsExtractor, preview: Boolean)

    protected fun fresh() = System.currentTimeMillis() - ActionBarStats.statsAt < STAT_STALE_MS

    private fun drawScaled(g: GuiGraphicsExtractor, preview: Boolean) {
        g.pose().pushMatrix()
        g.pose().translate(panelX.toFloat(), panelY.toFloat())
        g.pose().scale(panelScale, panelScale)
        draw(g, preview)
        g.pose().popMatrix()
    }

    override fun renderInternal(g: GuiGraphicsExtractor) = drawScaled(g, true)

    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.5f, 3f) }
    override fun resetPosition() { panelX = defX; panelY = defY; panelScale = 1f }

    override fun save() {
        runCatching {
            saveFile.parentFile?.mkdirs()
            saveFile.writeText(gson.toJson(PanelPos(panelX, panelY, panelScale)))
        }.onFailure { println("[$label] Failed to save position: ${it.message}") }
    }

    init {
        runCatching {
            if (saveFile.exists()) gson.fromJson(saveFile.readText(), PanelPos::class.java)?.let {
                panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.5f, 3f)
            }
        }.onFailure { println("[$label] Failed to load position: ${it.message}") }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("cedclient", fileKey)) { g, _ ->
            if (hudActive && Minecraft.getInstance().gui.screen() !is AbstractContainerScreen<*>) drawScaled(g, false)
        }
    }
}

/** Health / mana / vitality: a bar with "cur / max" in it. */
abstract class BarStatPanel(
    label: String, fileKey: String, defY: Int,
    private val color: Int, private val sample: ActionBarStats.Stat
) : StatHudPanel(label, fileKey, defY) {
    protected abstract fun current(): ActionBarStats.Stat?

    override fun draw(g: GuiGraphicsExtractor, preview: Boolean) {
        val fresh = fresh()
        val stat = if (preview && !fresh) sample else current().takeIf { fresh }
        val w = StatBars.barWidth.value.toInt()
        lastWidth = w
        lastHeight = 10
        if (stat == null) return
        val font = Minecraft.getInstance().font
        g.fill(0, 0, w, 10, 0x99101018.toInt())
        val filled = if (stat.max > 0) (w * (stat.cur / stat.max).coerceIn(0.0, 1.0)).toInt() else 0
        g.fill(0, 0, filled, 10, color)
        val text = "%,d / %,d".format(stat.cur.toLong(), stat.max.toLong())
        g.text(font, text, (w - font.width(text)) / 2, 1, -1)
    }
}

/** Overflow mana / defence: one coloured line. */
abstract class TextStatPanel(
    label: String, fileKey: String, defY: Int,
    private val prefix: String, private val color: Int, private val sample: Double
) : StatHudPanel(label, fileKey, defY) {
    protected abstract fun current(): Double?
    protected open fun visible(v: Double) = true

    override fun draw(g: GuiGraphicsExtractor, preview: Boolean) {
        val fresh = fresh()
        val v = if (preview && !fresh) sample else current().takeIf { fresh }
        lastHeight = 12
        if (v == null || !visible(v)) { lastWidth = 80; return }
        val font = Minecraft.getInstance().font
        val text = "$prefix %,d".format(v.toLong())
        g.text(font, text, 0, 1, color)
        lastWidth = font.width(text)
    }
}

object HealthPanel : BarStatPanel("Health Bar", "stat_health", 60, 0xFFE05050.toInt(), ActionBarStats.Stat(1200.0, 1500.0)) {
    override val hudActive get() = StatBars.isEnabled && StatBars.showHealth.value
    override fun current() = ActionBarStats.health
}

object ManaPanel : BarStatPanel("Mana Bar", "stat_mana", 76, 0xFF4F7BFF.toInt(), ActionBarStats.Stat(340.0, 500.0)) {
    override val hudActive get() = StatBars.isEnabled && StatBars.showMana.value
    override fun current() = ActionBarStats.mana
}

object VitalityPanel : BarStatPanel("Vitality Bar", "stat_vitality", 92, 0xFFB02020.toInt(), ActionBarStats.Stat(100.0, 120.0)) {
    override val hudActive get() = StatBars.isEnabled && StatBars.showVitality.value
    override fun current() = ActionBarStats.vitality
}

object OverflowPanel : TextStatPanel("Overflow Mana", "stat_overflow", 108, "Overflow", 0xFF2AB5B5.toInt(), 200.0) {
    override val hudActive get() = StatBars.isEnabled && StatBars.showOverflow.value
    override fun current() = ActionBarStats.overflow
    override fun visible(v: Double) = v > 0
}

object DefencePanel : TextStatPanel("Defence", "stat_defence", 122, "Defence", 0xFF55E05A.toInt(), 300.0) {
    override val hudActive get() = StatBars.isEnabled && StatBars.showDefence.value
    override fun current() = ActionBarStats.defence
}

/** Health, mana, vitality, overflow mana and defence from the action bar: one module, each stat its own HUD panel. */
object StatBars : Module(
    "Stat Bars", Category.HUD,
    "Health, mana, vitality, overflow mana and defence from the SkyBlock action bar, each as its own movable HUD panel."
) {
    val showHealth = BooleanSetting("Show Health", true)
    val showMana = BooleanSetting("Show Mana", true)
    val showVitality = BooleanSetting("Show Vitality", true)
    val showOverflow = BooleanSetting("Show Overflow Mana", true)
    val showDefence = BooleanSetting("Show Defence", true)
    val barWidth = NumberSetting("Bar Width", 90.0, 40.0, 200.0, 5.0)
    val hideHealth = BooleanSetting("Hide Health From Action Bar", false, "Cuts the health out of the action bar.")
    val hideMana = BooleanSetting("Hide Mana From Action Bar", false, "Cuts the mana out of the action bar.")
    val hideVitality = BooleanSetting("Hide Vitality From Action Bar", false, "Cuts the vitality out of the action bar.")
    val hideOverflow = BooleanSetting("Hide Overflow Mana From Action Bar", false, "Cuts the overflow mana out of the action bar.")
    val hideDefence = BooleanSetting("Hide Defence From Action Bar", false, "Cuts the defence out of the action bar.")

    /** Touching the panels here registers their HUD layers at startup. */
    val panels: List<HudElement> = listOf(HealthPanel, ManaPanel, VitalityPanel, OverflowPanel, DefencePanel)

    init { addSettings(showHealth, showMana, showVitality, showOverflow, showDefence, barWidth, hideHealth, hideMana, hideVitality, hideOverflow, hideDefence) }
}

// ------------------------------------------------------------------------------------------------ Skill Display

object SkillDisplay : StatPanel(
    "Skill Display", "SkyBlock skill XP from the action bar as a movable HUD element (icon, name, percent).", "skill_display", 10, 130
) {
    private val showIcon = BooleanSetting("Show Icon", true)
    private val showPercent = BooleanSetting("Show Percent", true)
    private val fadeSeconds = NumberSetting("Fade Time (s)", 4.0, 1.0, 15.0, 0.5)
    val hideFromBar = BooleanSetting("Hide From Action Bar", false, "Cuts the skill XP out of the action bar.")

    private val icons by lazy {
        mapOf(
            "Farming" to Items.GOLDEN_HOE, "Mining" to Items.STONE_PICKAXE, "Combat" to Items.STONE_SWORD,
            "Foraging" to Items.JUNGLE_SAPLING, "Fishing" to Items.FISHING_ROD, "Enchanting" to Items.ENCHANTING_TABLE,
            "Alchemy" to Items.BREWING_STAND, "Carpentry" to Items.CRAFTING_TABLE, "Taming" to Items.BONE,
        ).mapValues { ItemStack(it.value) }
    }
    private val fallback by lazy { ItemStack(Items.EXPERIENCE_BOTTLE) }

    init { addSettings(showIcon, showPercent, fadeSeconds, hideFromBar) }

    override fun draw(g: GuiGraphicsExtractor, preview: Boolean) {
        val age = System.currentTimeMillis() - ActionBarStats.skillAt
        val fadeMs = (fadeSeconds.value * 1000).toLong()
        val skill = ActionBarStats.skill
        val live = skill != null && age < fadeMs
        if (!live && !preview) return

        val name = if (live) skill!! else "Mining"
        val gain = if (live) ActionBarStats.skillGain else 12.3
        val percent = if (live) ActionBarStats.skillPercent else 45.67
        val alpha = if (live) (((fadeMs - age) / 600f).coerceIn(0.05f, 1f) * 255).toInt() else 255

        val font = Minecraft.getInstance().font
        var x = 0
        if (showIcon.value) {
            g.item(icons[name] ?: fallback, 0, 0)
            x = 20
        }
        val text = buildString {
            append("+").append(if (gain % 1.0 == 0.0) "%,d".format(gain.toLong()) else "%,.1f".format(gain)).append(' ').append(name)
            if (showPercent.value && percent >= 0) append(' ').append("%.2f".format(percent)).append('%')
        }
        g.text(font, text, x, 4, (alpha shl 24) or 0x55FFFF)
        lastWidth = x + font.width(text)
        lastHeight = 16
    }
}

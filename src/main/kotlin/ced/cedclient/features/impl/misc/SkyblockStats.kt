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
    var defence: Double? = null; private set
    var statsAt = 0L; private set

    var skill: String? = null; private set
    var skillGain = 0.0; private set
    var skillPercent = -1.0; private set
    var skillAt = 0L; private set

    private const val NUM = "[\\d,]+(?:\\.\\d+)?"
    private val numRe = Regex(NUM)
    private val healthRe = Regex("$NUM/$NUM\\s*❤|❤\\s*$NUM/$NUM")
    private val manaRe = Regex("$NUM/$NUM\\s*✎|✎\\s*$NUM/$NUM")
    private val defenceRe = Regex("$NUM\\s*❈|❈\\s*$NUM")
    private val skillRe = Regex("\\+($NUM)\\s+([A-Za-z]+)\\s+\\(([^)]*)\\)")

    private fun nums(s: String) = numRe.findAll(s).map { it.value.replace(",", "").toDouble() }.toList()

    /** The range of a match plus the spaces after it, so cutting it out doesn't leave a gap. */
    private fun withTrailing(text: String, range: IntRange): IntRange {
        var end = range.last + 1
        while (end < text.length && text[end] == ' ') end++
        return range.first until end
    }

    /** Mixin: every action bar message. Returns the message to show (stats cut out where a module asks for it). */
    @JvmStatic
    fun process(message: Component): Component {
        val bars = StatBars.isEnabled
        val skills = SkillDisplay.isEnabled
        if (!bars && !skills) return message
        val text = message.string
        val cut = ArrayList<IntRange>()
        val now = System.currentTimeMillis()

        if (bars) {
            healthRe.find(text)?.let { m ->
                nums(m.value).takeIf { it.size == 2 }?.let { health = Stat(it[0], it[1]); statsAt = now }
                if (StatBars.hideHealth.value) cut += withTrailing(text, m.range)
            }
            manaRe.find(text)?.let { m ->
                nums(m.value).takeIf { it.size == 2 }?.let { mana = Stat(it[0], it[1]); statsAt = now }
                if (StatBars.hideMana.value) cut += withTrailing(text, m.range)
            }
            defenceRe.find(text)?.let { m ->
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
        return if (cut.isEmpty()) message else remove(message, cut)
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

object StatBars : StatPanel(
    "Stat Bars", "Health, mana and defence from the SkyBlock action bar as a movable HUD panel.", "stat_bars", 10, 60
) {
    private val showHealth = BooleanSetting("Show Health", true)
    private val showMana = BooleanSetting("Show Mana", true)
    private val showDefence = BooleanSetting("Show Defence", true)
    private val barWidth = NumberSetting("Bar Width", 90.0, 40.0, 200.0, 5.0)
    val hideHealth = BooleanSetting("Hide Health From Action Bar", false, "Cuts the health out of the action bar.")
    val hideMana = BooleanSetting("Hide Mana From Action Bar", false, "Cuts the mana out of the action bar.")
    val hideDefence = BooleanSetting("Hide Defence From Action Bar", false, "Cuts the defence out of the action bar.")

    private const val STALE_MS = 6000L
    private const val HEALTH = 0xFFE05050.toInt()
    private const val MANA = 0xFF4F7BFF.toInt()
    private const val DEFENCE = 0xFF55E05A.toInt()
    private const val TRACK = 0x99101018.toInt()

    init { addSettings(showHealth, showMana, showDefence, barWidth, hideHealth, hideMana, hideDefence) }

    private fun fmt(v: Double) = "%,d".format(v.toLong())

    override fun draw(g: GuiGraphicsExtractor, preview: Boolean) {
        val fresh = System.currentTimeMillis() - ActionBarStats.statsAt < STALE_MS
        val sample = preview && !fresh
        val health = if (sample) ActionBarStats.Stat(1200.0, 1500.0) else ActionBarStats.health.takeIf { fresh }
        val mana = if (sample) ActionBarStats.Stat(340.0, 500.0) else ActionBarStats.mana.takeIf { fresh }
        val defence = if (sample) 300.0 else ActionBarStats.defence.takeIf { fresh }

        val font = Minecraft.getInstance().font
        val w = barWidth.value.toInt()
        var y = 0
        fun bar(stat: ActionBarStats.Stat, color: Int) {
            g.fill(0, y, w, y + 10, TRACK)
            val filled = if (stat.max > 0) (w * (stat.cur / stat.max).coerceIn(0.0, 1.0)).toInt() else 0
            g.fill(0, y, filled, y + 10, color)
            val text = "${fmt(stat.cur)} / ${fmt(stat.max)}"
            g.text(font, text, (w - font.width(text)) / 2, y + 1, -1)
            y += 12
        }
        if (showHealth.value && health != null) bar(health, HEALTH)
        if (showMana.value && mana != null) bar(mana, MANA)
        if (showDefence.value && defence != null) {
            g.text(font, "Defence ${fmt(defence)}", 0, y + 1, DEFENCE)
            y += 12
        }
        lastWidth = w
        lastHeight = maxOf(y, 12)
    }
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

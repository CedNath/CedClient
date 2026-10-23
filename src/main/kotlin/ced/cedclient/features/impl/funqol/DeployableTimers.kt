package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.events.core.TickEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.Color
import ced.cedclient.utils.getSkullTexture
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import java.io.File
import java.util.Base64

/**
 * Deployable Timers: every flare, power orb and totem near you with its time left, read off the floating name
 * above it ("Radiant 24s"). Rows are sorted by time left; green = you are inside its range.
 *
 * Orbs / umberella / totem: read off their floating name. Flares have no text: found by their head texture, timed
 * 180 s from the moment first seen (so one already flying when you arrive is early). Detection follows RFU's.
 * shortcut: Warning Flare's texture isn't known (not shown); add its hash to [FLARES]. Totem name is a guess.
 */
object DeployableTimers : Module(
    "Deployable Timers",
    Category.Skyblock,
    "HUD with the time left on every flare, power orb and totem near you."
), HudElement {
    private val orbs = BooleanSetting("Power Orbs", true, "Radiant, Mana Flux, Overflux, Plasmaflux.")
    private val flaresOn = BooleanSetting("Flares", true, "SOS and Alert Flare.")
    private val umberella = BooleanSetting("Umberella", true)
    private val totem = BooleanSetting("Totem Of Corruption", true)
    private val onlyInRange = BooleanSetting("Only In Range", false, "Hide deployables you are too far from to benefit.")
    private val sortBy = DropdownSetting("Sort By", listOf("Time Left", "Distance", "Type"), "Time Left", "Row order.")
    private val maxRows = NumberSetting("Max Rows", 6.0, 1.0, 12.0, 1.0, "Most rows shown at once.")
    private val showDistance = BooleanSetting("Show Distance", false, "Blocks away, after the name.")
    private val timeFormat = DropdownSetting("Time Format", listOf("1:30", "90s"), "1:30")
    private val background = BooleanSetting("Background", true, "The card behind the rows.")
    private val inRangeColor = ColorSetting("In Range Color", Color(85, 255, 85), "Rows for deployables you are inside the range of.")
    private val outRangeColor = ColorSetting("Out Of Range Color", Color(187, 187, 187), "Rows for deployables out of your reach.")
    private val lowColor = ColorSetting("Low Time Color", Color(255, 85, 85), "Rows about to run out.")
    private val lowSeconds = NumberSetting("Low Time", 10.0, 0.0, 60.0, 1.0, "Seconds left at which a row uses the Low Time Color (0 = never).")

    /** Name as shown above it -> effect radius in blocks. */
    private val KINDS = linkedMapOf(
        "Plasmaflux" to 20.0, "Overflux" to 18.0, "Mana Flux" to 18.0, "Radiant" to 18.0,
        "Umberella" to 30.0, "Totem of Corruption" to 16.0,
    )

    /** Flares have no floating text: their armor stand wears a head with this texture hash (Mojang texture URL tail). */
    private val FLARES = mapOf(
        "c0062cc98ebda72a6a4b89783adcef2815b483a01d73ea87b3df76072a89d13b" to "SOS Flare",
        "9d2bf9864720d87fd06b84efa80b795c48ed539b16523c3b1f1990b40c003f6b" to "Alert Flare",
    )
    private const val FLARE_RANGE = 40.0
    private const val FLARE_SECONDS = 180

    /** Flare armor stand id -> when it was first seen (ms); the timer counts [FLARE_SECONDS] from there. */
    private val flareSeen = HashMap<Int, Long>()
    private val CODES = Regex("§.")
    private val TIME = Regex("""(?:(\d+)m\s*)?(\d+)s|\b(\d+):(\d{2})\b""")

    private class Row(val name: String, val seconds: Int, val distance: Double, val range: Double) {
        val inRange get() = distance <= range
    }

    private val mc get() = Minecraft.getInstance()
    private var rows: List<Row> = emptyList()
    private var ticks = 0

    private fun seconds(text: String): Int? {
        val m = TIME.find(text) ?: return null
        return if (m.groupValues[3].isNotEmpty()) m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
        else (m.groupValues[1].toIntOrNull() ?: 0) * 60 + m.groupValues[2].toInt()
    }

    private fun kindOf(text: String) = KINDS.keys.firstOrNull { text.contains(it, ignoreCase = true) }

    private fun scan() {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) { rows = emptyList(); flareSeen.clear(); return }
        val now = System.currentTimeMillis()
        val flares = ArrayList<Row>()
        val seen = HashSet<Int>()
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand) continue
            val texture = entity.getItemBySlot(EquipmentSlot.HEAD).getSkullTexture() ?: continue
            val json = runCatching { String(Base64.getDecoder().decode(texture)) }.getOrNull() ?: continue
            val kind = FLARES.entries.firstOrNull { json.contains(it.key) }?.value ?: continue
            seen += entity.id
            val left = FLARE_SECONDS - ((now - flareSeen.getOrPut(entity.id) { now }) / 1000).toInt()
            if (left > 0) flares += Row(kind, left, entity.distanceTo(player).toDouble(), FLARE_RANGE)
        }
        flareSeen.keys.retainAll(seen)
        val named = level.entitiesForRendering().filter { it.hasCustomName() }
            .map { it to it.customName!!.string.replace(CODES, "").trim() }
        val found = ArrayList<Row>()
        for ((entity, text) in named) {
            val kind = kindOf(text) ?: continue
            // The time is on the same stand, else on one right next to it.
            val left = seconds(text)
                ?: named.firstOrNull { (o, t) -> o !== entity && o.distanceToSqr(entity) < 4.0 && kindOf(t) == null && seconds(t) != null }?.second?.let(::seconds)
                ?: continue
            found += Row(kind, left, entity.distanceTo(player).toDouble(), KINDS.getValue(kind))
        }
        rows = (found + flares).distinctBy { it.name to it.seconds }.sortedBy { it.seconds }
    }

    private fun fmt(s: Int) = if (timeFormat.value == "1:30" && s >= 60) "%d:%02d".format(s / 60, s % 60) else "${s}s"

    private val ORBS = setOf("Plasmaflux", "Overflux", "Mana Flux", "Radiant")

    private fun wanted(row: Row) = when {
        row.name in ORBS -> orbs.value
        row.name.contains("Flare") -> flaresOn.value
        row.name == "Umberella" -> umberella.value
        else -> totem.value
    }

    private fun colorOf(row: Row) = when {
        lowSeconds.value > 0 && row.seconds <= lowSeconds.value -> lowColor.value.rgba
        row.inRange -> inRangeColor.value.rgba
        else -> outRangeColor.value.rgba
    }

    private fun lines(list: List<Row>) = list.map {
        Triple(it.name + if (showDistance.value) " ${it.distance.toInt()}m" else "", fmt(it.seconds), colorOf(it))
    }

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || mc.options.hideGui) return
        var shown = rows.filter { wanted(it) && (!onlyInRange.value || it.inRange) }
        shown = when (sortBy.value) {
            "Distance" -> shown.sortedBy { it.distance }
            "Type" -> shown.sortedWith(compareBy({ it.name }, { it.seconds }))
            else -> shown
        }.take(maxRows.value.toInt())
        if (shown.isEmpty()) return
        draw(g, lines(shown))
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val sample = listOf(Row("Radiant", 24, 4.0, 18.0), Row("SOS Flare", 190, 60.0, 40.0), Row("Overflux", 8, 9.0, 18.0))
        draw(g, lines(rows.ifEmpty { sample }))
    }

    private fun draw(g: GuiGraphicsExtractor, lines: List<Triple<String, String, Int>>) {
        val s = panelScale
        val size = 10f * s
        val lineH = 12f * s
        val pad = 6f * s
        val gap = 10f * s
        val leftW = lines.maxOf { HudCard.width(it.first, size) }
        val rightW = lines.maxOf { HudCard.width(it.second, size) }
        val width = leftW + gap + rightW + pad * 2
        val height = lines.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            if (background.value) HudCard.card(width, height, s)
            lines.forEachIndexed { i, (name, time, color) ->
                val y = pad + i * lineH
                HudCard.text(name, pad, y, size, color)
                HudCard.text(time, width - pad - HudCard.width(time, size), y, size, color)
            }
        }
    }

    override val label = "Deployable Timers"
    private const val DEFAULT_X = 3
    private const val DEFAULT_Y = 200
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile: File by lazy { CedPaths.file("deployable_timers_hud.json") }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }

    init {
        addSettings(orbs, flaresOn, umberella, totem, onlyInRange, sortBy, maxRows, showDistance, timeFormat, background, inRangeColor, outRangeColor, lowColor, lowSeconds)
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
        on<TickEvent.End> { if (isEnabled && ++ticks % 5 == 0) scan() }
    }
}

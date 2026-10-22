package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.SubtitleEvent
import ced.cedclient.events.TitleEvent
import ced.cedclient.events.core.TickEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Color
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import java.io.File

/**
 * Sun Gecko (Rift, Oruo's Time Chamber): run timer with your best time per modifier set, a HUD with the
 * active modifiers, the Gecko's HP and what it last used, a box on the Gecko, and titles for Hazard Zone and
 * Spirit Beam. A run starts at "Let us train together" and ends at "SUN GECKO DOWN!" or a failed session.
 */
object SunGecko : Module(
    "Sun Gecko",
    Category.Skyblock,
    "Sun Gecko run timer + PB per modifier set, boss HUD, Gecko box, Hazard Zone / Spirit Beam alerts.",
    defaultEnabled = true
), HudElement {
    private val hud = BooleanSetting("HUD", true, "Timer, PB, modifiers, Gecko HP, last ability.")
    private val box = BooleanSetting("Box Sun Gecko", true)
    private val boxColor = ColorSetting("Box Color", Color(255, 200, 40)).withDependency { box.value }
    private val nukeAlert = BooleanSetting("Hazard Zone Alert", true, "Title + sound when the Gecko uses Hazard Zone.")
    private val hazardHud = BooleanSetting("Hazard Zone In HUD", false, "Hides Hypixel's Hazard Zone / get out titles and shows it as a HUD line instead.")
    private val beamAlert = BooleanSetting("Spirit Beam Alert", true, "Title when the Gecko uses Spirit Beam (it eats your time).")
    private val pbMessage = BooleanSetting("PB In Chat", true, "Run time and PB in chat after each kill.")

    // HUD lines, keyed by the row label they hide (the header always shows).
    private val hudLines = linkedMapOf(
        "Time" to BooleanSetting("Run Timer", true), "Hazard Zone" to BooleanSetting("Hazard Zone", true), "HP" to BooleanSetting("Gecko HP", true),
        "Last" to BooleanSetting("Last Ability", true), "Time lost" to BooleanSetting("Time Lost", true),
        "Orbs" to BooleanSetting("Orbs", true), "Last run" to BooleanSetting("Last Run", true),
        "Modifiers" to BooleanSetting("Modifiers", false), "PB" to BooleanSetting("PB", true))
    private val hudButton = ActionSetting("HUD Lines", "Pick what the Sun Gecko HUD shows.") {
        (Minecraft.getInstance().screen as? ClickGUI)?.openPopup(
            SettingsPopup("Sun Gecko HUD", listOf(listOf(SettingsPopup.Section("Lines", hudLines.values.toList())))))
    }

    init {
        addSettings(hud, hudButton, box, boxColor, nukeAlert, hazardHud, beamAlert, pbMessage)
        // Hazard Zone only comes as a title ("Hazard Zone", subtitle "get out"), never in chat.
        on<TitleEvent> {
            if (!isEnabled || runStart == 0L || !it.unformattedText.contains("Hazard Zone", ignoreCase = true)) return@on
            if (System.currentTimeMillis() - hazardAt > 5_000) ability("Hazard Zone") // title can repeat
            if (hazardHud.value) it.cancel()
        }
        on<SubtitleEvent> {
            if (isEnabled && hazardHud.value && runStart != 0L && it.unformattedText.contains("get out", ignoreCase = true)) it.cancel()
        }
        addSettings(*hudLines.values.toTypedArray())
        hudLines.values.forEach { it.inPopup = true }
        on<ChatMessageEvent> { onChat(it.unformattedText.trim()) }
        on<TickEvent.End> { tick() }
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            val gecko = gecko ?: return@AfterSolidFeatures
            if (!isEnabled || !box.value || runStart == 0L) return@AfterSolidFeatures
            val cam = context.levelState().cameraRenderState.pos
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
            val b = gecko.boundingBox.move(gecko.getPosition(pt).subtract(gecko.position())).inflate(0.1).move(-cam.x, -cam.y, -cam.z)
            val c = boxColor.value
            val buffer = context.bufferSource().getBuffer(CustomRenderType.LINES_DEPTH)
            LineBox.draw(context.poseStack(), buffer, b, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
            context.bufferSource().endBatch()
        })
    }

    private val mc get() = Minecraft.getInstance()

    // ---------------------------------------------------------------- run

    private var runStart = 0L
    private var revivalAt = 0L
    private var modifiers: List<String> = emptyList()
    private var readingModifiers = false
    private val pendingModifiers = mutableListOf<String>()
    private var lastAbility: String? = null
    private var lastAbilityAt = 0L
    private var timeLost = 0
    private var orbs = 0
    private var lastResult: String? = null

    private val ABILITY = Regex("""^Sun Gecko used (.+)!$""")
    private val TIME_LOST = Regex("""^Sun Gecko removed (\d+)s from you""")
    private val BAR = Regex("""^▬{5,}""")

    private fun onChat(line: String) {
        if (!isEnabled) return
        when {
            line == "ACTIVE MODIFIERS!" -> { readingModifiers = true; pendingModifiers.clear() }
            readingModifiers && BAR.containsMatchIn(line) -> {
                readingModifiers = false
                modifiers = pendingModifiers.joinToString(", ").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
            readingModifiers -> if (line.isNotBlank()) pendingModifiers += line
            line.startsWith("[NPC] Sun Gecko: Let us train together") -> startRun()
            runStart == 0L -> return
            line == "REVIVAL! Defeat the Sun Gecko once more!" -> revivalAt = System.currentTimeMillis()
            line == "SUN GECKO DOWN!" -> endRun(true)
            line.contains("You failed your training session with Sun Gecko") -> endRun(false)
            line.startsWith("ORB! Picked up") -> orbs++
            else -> {
                TIME_LOST.find(line)?.let { timeLost += it.groupValues[1].toInt() }
                ABILITY.matchEntire(line)?.let { m -> ability(m.groupValues[1]) }
            }
        }
    }

    /** SlayerCheats: a run is on and the Gecko used Hazard Zone in the last 10 s. */
    // Own timestamp: a Spirit Beam right after must not end the Hazard Zone window.
    private var hazardAt = 0L
    internal fun nukeRecent() = runStart != 0L && System.currentTimeMillis() - hazardAt < 10_000
    private var beamAt = 0L
    /** SlayerCheats: Spirit Beam in the last 10 s (it repaints the floor under you, hiding the Hazard Zone red). */
    internal fun beamRecent() = runStart != 0L && System.currentTimeMillis() - beamAt < 10_000

    private fun startRun() {
        runStart = System.currentTimeMillis()
        revivalAt = 0L; lastAbility = null; timeLost = 0; orbs = 0; lastResult = null
    }

    private fun ability(name: String) {
        lastAbility = name
        lastAbilityAt = System.currentTimeMillis()
        if (name == "Spirit Beam") beamAt = lastAbilityAt
        if (name == "Hazard Zone") hazardAt = lastAbilityAt
        when {
            name == "Hazard Zone" && nukeAlert.value && !hazardHud.value -> alert("§c§lHAZARD ZONE", 0.6f)
            name == "Spirit Beam" && beamAlert.value -> alert("§d§lSPIRIT BEAM", 1.4f)
        }
    }

    private fun alert(text: String, pitch: Float) {
        mc.gui.setTimes(2, 30, 8)
        mc.gui.setTitle(Component.literal(text))
        mc.gui.setSubtitle(Component.empty())
        mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, 1f))
    }

    private fun endRun(won: Boolean) {
        val time = System.currentTimeMillis() - runStart
        runStart = 0L
        if (!won) { lastResult = "Failed after ${format(time)}"; return }
        val key = modifiers.sorted().joinToString(", ").ifEmpty { "No modifiers" }
        val pb = bests[key]
        val newPb = pb == null || time < pb
        if (newPb) { bests[key] = time; saveBests() }
        lastResult = format(time) + if (newPb) "  NEW PB" else "  (PB ${format(pb!!)})"
        if (pbMessage.value) mc.player?.sendSystemMessage(ChatPrefix.msgFor("Sun Gecko", 
            "§eSun Gecko §f${format(time)}" + if (newPb) " §a§lNEW PB" + (pb?.let { " §7(was ${format(it)})" } ?: "") else " §7PB ${format(pb!!)}"))
    }

    private fun format(ms: Long): String {
        val s = ms / 1000.0
        return if (s >= 60) "%d:%05.2f".format((s / 60).toInt(), s % 60) else "%.2fs".format(s)
    }

    // ---------------------------------------------------------------- PBs (sun_gecko_pbs.json, by modifier set)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val pbFile: File by lazy { CedPaths.file("sun_gecko_pbs.json") }
    private val bests: MutableMap<String, Long> by lazy {
        runCatching { gson.fromJson<MutableMap<String, Long>>(pbFile.readText(), object : TypeToken<MutableMap<String, Long>>() {}.type) }
            .getOrNull() ?: mutableMapOf()
    }
    private fun saveBests() = runCatching { pbFile.parentFile?.mkdirs(); pbFile.writeText(gson.toJson(bests)) }

    // ---------------------------------------------------------------- the Gecko

    private var gecko: Entity? = null
    private var geckoHp: String? = null
    private val HP = Regex("""([\d.,]+[kM]?)/([\d.,]+[kM]?)❤|([\d.,]+[kM]?)❤""")

    private fun tick() {
        if (!isEnabled || runStart == 0L) { gecko = null; geckoHp = null; return }
        val player = mc.player ?: return
        val tag = mc.level?.entitiesForRendering()?.filterIsInstance<ArmorStand>()
            ?.firstOrNull { it.hasCustomName() && it.customName!!.string.contains("Sun Gecko") && it.distanceToSqr(player) < 60.0 * 60.0 }
        geckoHp = tag?.customName?.string?.let { HP.find(it)?.value }
        gecko = tag?.let { t ->
            mc.level?.entitiesForRendering()?.filter { it is LivingEntity && it !is ArmorStand && it !== player && it.distanceToSqr(t) < 9.0 }
                ?.minByOrNull { it.distanceToSqr(t) }
        }
    }

    // ---------------------------------------------------------------- HUD

    private fun rows(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Pair<String, String>>()
        out += "Sun Gecko" to ""
        if (runStart != 0L) {
            out += "Time" to format(now - runStart) + (if (revivalAt != 0L) "  (revival ${format(now - revivalAt)})" else "")
            // shortcut: no hit timer, just time since the title; add a countdown once the delay is known.
            if (hazardHud.value && nukeRecent()) out += "Hazard Zone" to "GET OUT  ${(now - hazardAt) / 1000}s ago"
            geckoHp?.let { out += "HP" to it }
            lastAbility?.let { out += "Last" to "$it  ${(now - lastAbilityAt) / 1000}s ago" }
            if (timeLost > 0) out += "Time lost" to "${timeLost}s"
            if (orbs > 0) out += "Orbs" to "$orbs"
        } else lastResult?.let { out += "Last run" to it }
        if (modifiers.isNotEmpty()) out += "Modifiers" to modifiers.joinToString(", ")
        bests[modifiers.sorted().joinToString(", ").ifEmpty { "No modifiers" }]?.let { out += "PB" to format(it) }
        return out
    }

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!isEnabled || !hud.value || mc.options.hideGui) return
        val area = ced.cedclient.state.Sidebar.area ?: return
        if (!area.contains("Time Chamber", ignoreCase = true) && !area.contains("Continuum", ignoreCase = true)) return
        // Only around a run: during it, and while its result is still worth seeing.
        if (runStart == 0L && lastResult == null && !readingModifiers) return
        draw(g, rows())
    }

    override fun renderInternal(g: GuiGraphicsExtractor) = draw(g, if (runStart != 0L) rows() else listOf(
        "Sun Gecko" to "", "Time" to "1:12.40", "HP" to "1.2M/3M❤", "Last" to "Spirit Beam  3s ago",
        "Modifiers" to "Combo Manic, Time Sliced, Buffantics", "PB" to "1:31.05"))

    private fun draw(g: GuiGraphicsExtractor, all: List<Pair<String, String>>) {
        val rows = all.filterIndexed { i, r -> i == 0 || hudLines[r.first]?.value != false }
        val s = panelScale
        val size = 11f * s
        val lineH = 13f * s
        val pad = 7f * s
        val gap = 10f * s
        val leftW = rows.filter { it.second.isNotEmpty() }.maxOfOrNull { HudCard.width(it.first, size) } ?: 0f
        val width = rows.maxOf { if (it.second.isEmpty()) HudCard.width(it.first, size) else leftW + gap + HudCard.width(it.second, size) } + pad * 2
        val height = rows.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            HudCard.card(width, height, s)
            rows.forEachIndexed { i, (left, right) ->
                val y = pad + i * lineH
                HudCard.text(left, pad, y, size, if (right.isEmpty()) 0xFFFFAA00.toInt() else 0xFFBBBBBB.toInt())
                if (right.isNotEmpty()) HudCard.text(right, pad + leftW + gap, y, size, if (left == "PB") 0xFF55FF55.toInt() else -1)
            }
        }
    }

    override val label = "Sun Gecko"
    private const val DEFAULT_X = 579
    private const val DEFAULT_Y = 35
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val hudFile: File by lazy { CedPaths.file("sun_gecko_hud.json") }

    init {
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
    }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }
}

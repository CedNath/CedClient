//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.farmhelper.FarmConfig
import ced.cedclient.farmhelper.FarmHelperCore
import ced.cedclient.farmhelper.failsafe.FailsafeManager
import ced.cedclient.farmhelper.failsafe.impl.LowerAvgBpsFailsafe
import ced.cedclient.farmhelper.handler.GameStateHandler
import ced.cedclient.farmhelper.handler.MacroHandler
import ced.cedclient.farmhelper.util.PlayerUtils
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.config.CedPaths
import ced.cedclient.utils.ChatPrefix
import com.google.gson.Gson
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.DeltaTracker
import ced.cedclient.farmhelper.util.KeyBindUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Block
import kotlin.math.abs
import org.lwjgl.glfw.GLFW
import java.io.File

/**
 * Crop farming macro: settings, toggle key, rewarp / spawn points and the status HUD for the FarmHelper port in
 * ced.cedclient.farmhelper (ported from JellyLab's FarmHelper, CC BY-NC-SA 4.0 -- see that package's NOTICE.md).
 * Friends-only (DevAccess), cheat build only.
 */
object FarmHelper : Module(
    "FarmHelper",
    Category.Cheats,
    "Crop farming macro (ported from JellyLab's FarmHelper, CC BY-NC-SA 4.0). Toggle Key in the Garden starts / stops it."
), HudElement {

    private val mc get() = Minecraft.getInstance()

    val toggleKey = KeybindSetting("Toggle Key", GLFW.GLFW_KEY_UNKNOWN, "Starts / stops the macro (Garden only)")
    private val statusHud = BooleanSetting("Status HUD", true, "Macro state, crop, run time and failsafe warnings")

    // Timings
    private val rowDelay = NumberSetting("Row Change Delay", 400.0, 0.0, 2000.0, 10.0, "ms before switching to the next row")
    private val rowDelayRandom = NumberSetting("Row Delay Randomness", 200.0, 0.0, 1000.0, 10.0, "Up to this many ms added at random")
    private val rotationTime = NumberSetting("Rotation Time", 500.0, 100.0, 2000.0, 10.0, "ms for a turn")
    private val rotationRandom = NumberSetting("Rotation Randomness", 300.0, 0.0, 1000.0, 10.0, "Up to this many ms added at random")
    private val rewarpDelay = NumberSetting("Rewarp Delay", 400.0, 0.0, 2000.0, 10.0, "ms standing on a rewarp point before /warp garden")
    private val rewarpRandom = NumberSetting("Rewarp Randomness", 350.0, 0.0, 1000.0, 10.0, "Up to this many ms added at random")

    // Behaviour
    val alwaysHoldW = BooleanSetting("Always Hold W", false, "Vertical farms: keep walking forward while strafing")
    val rotateAfterDrop = BooleanSetting("Rotate After Drop", false, "Turn 180 after dropping to the next layer")
    val rotateAfterWarped = BooleanSetting("Rotate After Warp", false, "Turn 180 after /warp garden")
    val dontFixAfterWarping = BooleanSetting("Don't Fix After Warp", false, "Skip the small rotation fix after a warp")
    val holdClickOnRowChange = BooleanSetting("Hold Click On Row Change", true, "Keep breaking while switching rows")
    val autoSwitchCrop = BooleanSetting("Auto Switch Crop", true, "Follow the crop you're looking at")

    // Failsafes
    val failsafeDelay = NumberSetting("Failsafe Stop Delay", 2000.0, 0.0, 5000.0, 100.0, "ms between a detected check and stopping")
    val rotationSensitivity = NumberSetting("Rotation Check Sensitivity", 7.0, 1.0, 30.0, 0.5, "Degrees the server may turn you before it counts")
    val teleportDistance = NumberSetting("Teleport Check Distance", 4.0, 1.0, 20.0, 0.5, "Blocks the server may move you before it counts")
    val knockbackThreshold = NumberSetting("Knockback Threshold", 0.5, 0.1, 3.0, 0.05, "Upward push (blocks / tick) that counts as a knockback check")
    val bpsCheck = BooleanSetting("Low BPS Check", true, "Stop when you break crops much slower than normal")
    val minBps = NumberSetting("Min BPS", 10.0, 1.0, 40.0, 0.5, "Blocks broken per second below which the check fires")
    val failsafeSound = BooleanSetting("Failsafe Sound", true, "Loud alert when a failsafe fires")
    val pauseOnGuest = BooleanSetting("Pause On Guest", true, "Pause when someone visits your garden")

    // Crop Config: per crop, the yaw / pitch the macro looks at and the keys it holds on each lane (LaneMacro).
    private val LANE_KEYS = listOf("A", "D", "W", "S", "W+A", "W+D", "S+A", "S+D")
    private class CropConfig(val crop: FarmConfig.CropEnum, yaw: Double, pitch: Double, lane1: String = "A", lane2: String = "D") {
        val yaw = NumberSetting("${crop.label} Yaw", yaw, -180.0, 180.0, 0.01)
        val pitch = NumberSetting("${crop.label} Pitch", pitch, -90.0, 90.0, 0.01)
        val lane1 = DropdownSetting("${crop.label} Lane 1 Keys", LANE_KEYS, lane1, "Held on the first lane, until you stop moving")
        val lane2 = DropdownSetting("${crop.label} Lane 2 Keys", LANE_KEYS, lane2, "Held on the next lane, until you stop moving; then back to Lane 1")
        val settings: List<RenderableSetting<*>> get() = listOf(yaw, pitch, lane1, lane2)
    }
    private val cropConfigs = listOf(
        CropConfig(FarmConfig.CropEnum.WHEAT, 0.0, 3.0), CropConfig(FarmConfig.CropEnum.CARROT, 0.0, 3.0),
        CropConfig(FarmConfig.CropEnum.POTATO, 0.0, 3.0), CropConfig(FarmConfig.CropEnum.NETHER_WART, 0.0, 0.25),
        CropConfig(FarmConfig.CropEnum.SUGAR_CANE, 45.0, 0.0), CropConfig(FarmConfig.CropEnum.MELON, 0.0, 28.0),
        CropConfig(FarmConfig.CropEnum.PUMPKIN, 0.0, 28.0), CropConfig(FarmConfig.CropEnum.CACTUS, 0.0, 0.25),
        CropConfig(FarmConfig.CropEnum.COCOA_BEANS, 0.0, -70.0, "W", "S"), CropConfig(FarmConfig.CropEnum.MUSHROOM, 45.0, 0.0),
        CropConfig(FarmConfig.CropEnum.SUNFLOWER, 0.0, 3.0), CropConfig(FarmConfig.CropEnum.MOONFLOWER, 0.0, 3.0),
        CropConfig(FarmConfig.CropEnum.ROSE, 0.0, 3.0),
    )
    private val cropPicker = DropdownSetting("Crop", cropConfigs.map { it.crop.label })
    private val cropConfigSettings = listOf<RenderableSetting<*>>(cropPicker) + cropConfigs.flatMap { it.settings }
    private val useCurrentAngles = ActionSetting("Use My Current Angles") {
        val player = mc.player ?: return@ActionSetting
        val c = cropConfigs.first { it.crop.label == cropPicker.value }
        c.yaw.value = Mth.wrapDegrees(player.yRot.toDouble())
        c.pitch.value = player.xRot.toDouble()
    }
    private val cropConfigButton = ActionSetting("Crop Config", "Yaw / pitch and lane keys per crop.") {
        runCatching { PlayerUtils.getFarmingCrop() }.getOrNull()?.let { c -> configOf(c)?.let { cropPicker.value = it.crop.label } }
        (mc.screen as? ClickGUI)?.openPopup(SettingsPopup("Crop Config", listOf(listOf(
            SettingsPopup.Section("Crop", listOf(cropPicker)),
            SettingsPopup.Section("Settings (click a number to type it)", cropConfigSettings.drop(1)),
        )), listOf(useCurrentAngles)))
    }

    // Learn Angles: while you farm by hand, your steady yaw / pitch per crop goes into Crop Config.
    val learnAngles = BooleanSetting("Learn Angles", true, "While you farm by hand (macro off), saves the yaw / pitch you hold for each crop into Crop Config")
    private const val LEARN_SAMPLES = 60 // crop breaks, ~3 s of normal farming
    private class Samples(val yaw: ArrayDeque<Double> = ArrayDeque(), val pitch: ArrayDeque<Double> = ArrayDeque())
    private val samples = HashMap<FarmConfig.CropEnum, Samples>()

    /** MultiPlayerGameModeMixin: you broke [block] yourself. */
    fun learnFrom(block: Block) {
        if (!isEnabled || !learnAngles.value || MacroHandler.getInstance().isMacroToggled) return
        val player = mc.player ?: return
        val c = configOf(PlayerUtils.cropOf(block)) ?: return
        val s = samples.getOrPut(c.crop) { Samples() }
        s.yaw.addLast(player.yRot.toDouble())
        s.pitch.addLast(player.xRot.toDouble())
        if (s.yaw.size > LEARN_SAMPLES) { s.yaw.removeFirst(); s.pitch.removeFirst() }
        if (s.yaw.size < LEARN_SAMPLES) return
        // Yaw counts in all 4 directions (like customYaw), so a farm walked both ways still reads as one angle.
        val yaw = steadyAngle(s.yaw, 90.0) ?: return
        val pitch = steadyAngle(s.pitch, null) ?: return
        s.yaw.clear(); s.pitch.clear()
        val sameYaw = abs(((yaw - c.yaw.value) % 90 + 135) % 90 - 45) < 0.05
        if (sameYaw && abs(pitch - c.pitch.value) < 0.05) return
        c.yaw.value = Mth.wrapDegrees(yaw)
        c.pitch.value = pitch
        msg("§aLearned ${c.crop.label} angles §7(yaw %.2f, pitch %.2f) §8- Crop Config".format(c.yaw.value, c.pitch.value))
    }

    /** Median of [values] (wrapped by [period] when given), or null when they spread over 1.5 degrees: not a steady angle. */
    internal fun steadyAngle(values: Collection<Double>, period: Double?): Double? {
        val ref = values.last()
        val d = values.map { if (period == null) it - ref else ((it - ref) % period + period * 1.5) % period - period / 2 }.sorted()
        if (d[d.size * 9 / 10] - d[d.size / 10] > 1.5) return null
        return ref + d[d.size / 2]
    }

    private fun configOf(crop: FarmConfig.CropEnum?): CropConfig? {
        val c = if (crop == FarmConfig.CropEnum.MUSHROOM_ROTATE) FarmConfig.CropEnum.MUSHROOM else crop
        return cropConfigs.firstOrNull { it.crop == c }
    }

    /** The macro's [base] yaw turned to your crop yaw, in whichever of the 4 directions [base] faces. */
    fun customYaw(crop: FarmConfig.CropEnum?, base: Float): Float {
        val c = configOf(crop) ?: return base
        return (base + ((c.yaw.value - base) % 90 + 135) % 90 - 45).toFloat()
    }

    fun customPitch(crop: FarmConfig.CropEnum?, base: Float): Float = configOf(crop)?.pitch?.value?.toFloat() ?: base

    /** Holds the current crop's keys for [lane] (1 or 2) plus attack. Unknown crop: A / D. */
    fun holdLane(lane: Int) {
        val c = configOf(MacroHandler.getInstance().crop)
        val combo = (if (lane == 1) c?.lane1?.value else c?.lane2?.value)?.takeIf { it in LANE_KEYS } ?: if (lane == 1) "A" else "D"
        val o = mc.options
        val keys = combo.split("+").map { when (it) { "W" -> o.keyUp; "A" -> o.keyLeft; "S" -> o.keyDown; else -> o.keyRight } }
        KeyBindUtils.holdThese(*(keys + o.keyAttack).toTypedArray())
    }

    private val rewarpsText = TextSetting("Rewarps", "", maxLength = 4000).also { it.hide() }
    private val spawnText = TextSetting("Spawn", "", maxLength = 80).also { it.hide() }

    private val timingSettings = listOf<RenderableSetting<*>>(rowDelay, rowDelayRandom, rotationTime, rotationRandom, rewarpDelay, rewarpRandom)
    private val behaviourSettings = listOf<RenderableSetting<*>>(alwaysHoldW, rotateAfterDrop, rotateAfterWarped, dontFixAfterWarping, holdClickOnRowChange, autoSwitchCrop)
    private val failsafeSettings = listOf<RenderableSetting<*>>(failsafeDelay, rotationSensitivity, teleportDistance, knockbackThreshold, bpsCheck, minBps, failsafeSound, pauseOnGuest)

    private val options = ActionSetting("Options", "Timings, behaviour and failsafes.") {
        (mc.screen as? ClickGUI)?.openPopup(SettingsPopup("FarmHelper", listOf(
            listOf(SettingsPopup.Section("Timings", timingSettings), SettingsPopup.Section("Behaviour", behaviourSettings)),
            listOf(SettingsPopup.Section("Failsafes", failsafeSettings)),
        )))
    }

    private var keyWasDown = false

    init {
        cropConfigs.forEach { c -> c.settings.forEach { it.withDependency { cropPicker.value == c.crop.label } } }
        val inPopup = timingSettings + behaviourSettings + failsafeSettings + cropConfigSettings
        inPopup.forEach { it.inPopup = true }
        addSettings(toggleKey, statusHud, options, cropConfigButton, learnAngles, *inPopup.toTypedArray(), rewarpsText, spawnText)
        // Self-check: 44.9 / 45.1 / -315 (= 45) read as one steady yaw; a 30 degree spread does not.
        check(steadyAngle(listOf(44.9, 45.1, -315.0, 45.0), 90.0)!!.let { abs(it - 45.0) < 0.11 })
        check(steadyAngle(listOf(0.0, 10.0, 20.0, 30.0), null) == null)
        loadHud()

        ClientTickEvents.START_CLIENT_TICK.register { FarmHelperCore.onTick() }
        on<ChatMessageEvent> { FarmHelperCore.onChat(it.unformattedText.replace(Regex("§."), "").trim()) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> FarmHelperCore.onDisconnect() }

        ClientTickEvents.END_CLIENT_TICK.register {
            val key = toggleKey.value
            val down = isEnabled && key > 0 && mc.screen == null && GLFW.glfwGetKey(mc.window.handle(), key) == GLFW.GLFW_PRESS
            if (down && !keyWasDown) MacroHandler.getInstance().toggleMacro()
            keyWasDown = down
            // Held macro keys aren't "input" to vanilla, so it would drop to the AFK FPS limit after a minute.
            if (MacroHandler.getInstance().isMacroToggled) mc.framerateLimitTracker.onInputReceived()
            if (!isEnabled && MacroHandler.getInstance().isMacroToggled) MacroHandler.getInstance().disableMacro()
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(ClientCommands.literal("farmhelper")
                .executes { MacroHandler.getInstance().toggleMacro(); 1 }
                .then(ClientCommands.literal("addrewarp").executes {
                    val pos = mc.player?.blockPosition() ?: return@executes 0
                    addRewarp(pos)
                    msg("§aRewarp point added at §f${pos.x}, ${pos.y}, ${pos.z} §7(${rewarps().size} total)")
                    1
                })
                .then(ClientCommands.literal("clearrewarps").executes {
                    rewarpsText.value = ""
                    saveConfig()
                    msg("§eAll rewarp points removed")
                    1
                })
                .then(ClientCommands.literal("info").executes {
                    msg("§7Rewarps: §f${rewarps().joinToString(" | ") { "${it.x}, ${it.y}, ${it.z}" }.ifEmpty { "none" }}")
                    msg("§7Spawn: §f${spawn()?.let { "${it.x}, ${it.y}, ${it.z}" } ?: "not set (do /setspawn in the Garden)"}")
                    1
                }))
        }
    }

    @JvmStatic fun msg(text: String) {
        mc.player?.sendSystemMessage(ChatPrefix.msgFor("FarmHelper", "§6[FarmHelper] §r$text"))
    }

    // ---- values for the port

    @JvmStatic fun randomRowDelay(): Long = (rowDelay.value + Math.random() * rowDelayRandom.value).toLong()
    @JvmStatic fun randomRotationTime(): Long = (rotationTime.value + Math.random() * rotationRandom.value).toLong()
    @JvmStatic fun randomRewarpDelay(): Long = (rewarpDelay.value + Math.random() * rewarpRandom.value).toLong()

    @JvmStatic fun rewarps(): List<BlockPos> = rewarpsText.value.split(';').mapNotNull { parsePos(it) }

    @JvmStatic fun addRewarp(pos: BlockPos) {
        val list = rewarps().filter { it != pos } + pos
        rewarpsText.value = list.joinToString(";") { "${it.x},${it.y},${it.z}" }
        saveConfig()
    }

    @JvmStatic fun removeRewarp(pos: BlockPos) {
        rewarpsText.value = rewarps().filter { it != pos }.joinToString(";") { "${it.x},${it.y},${it.z}" }
        saveConfig()
    }

    @JvmStatic fun spawn(): BlockPos? = parsePos(spawnText.value)

    @JvmStatic fun setSpawn(pos: BlockPos) {
        spawnText.value = "${pos.x},${pos.y},${pos.z}"
        saveConfig()
    }

    private fun parsePos(s: String): BlockPos? {
        val p = s.split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (p.size == 3) BlockPos(p[0], p[1], p[2]) else null
    }

    private fun saveConfig() {
        runCatching { ced.cedclient.config.ConfigManager.saveModulesOnly() }
    }

    // ---- HUD

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || mc.options.hideGui) return
        drawFailsafeWarning(g)
        if (!statusHud.value) return
        if (!MacroHandler.getInstance().isMacroToggled && !GameStateHandler.getInstance().inGarden()) return
        draw(g, rows())
    }

    private fun rows(): List<Pair<String, String>> {
        val mh = MacroHandler.getInstance()
        val state = when {
            !mh.isMacroToggled -> "§cOff"
            mh.isCurrentMacroPaused -> "§ePaused"
            else -> "§aOn"
        }
        val out = mutableListOf("FarmHelper" to "", "Macro" to state)
        if (mh.isMacroToggled) {
            out += "Crop" to mh.crop.label
            mh.currentMacro.ifPresent { out += "State" to it.currentState.name }
            out += "Time" to formatTime(mh.macroingTimer.elapsedTime)
            val bps = LowerAvgBpsFailsafe.getInstance().bps
            out += "BPS" to (if (bps < minBps.value) "§c" else "§a") + "%.1f".format(bps)
        }
        out += "Rewarps" to "${rewarps().size}" + if (spawn() == null) "  (no spawn)" else ""
        return out
    }

    private fun drawFailsafeWarning(g: GuiGraphicsExtractor) {
        val fm = FailsafeManager.getInstance()
        val lines = when {
            fm.chooseEmergencyDelay.isScheduled -> listOf("§dFailsafe in ${formatTime(fm.chooseEmergencyDelay.remainingTime)}")
            fm.triggeredFailsafe.isPresent -> listOf(
                "§6" + fm.triggeredFailsafe.get().type.name.replace('_', ' '),
                "§c§lPOSSIBLE STAFF CHECK - MACRO STOPPED",
                "§cTake over and react like a player would.",
            )
            else -> return
        }
        val font = mc.font
        var y = 20
        for (line in lines) {
            g.text(font, line, (g.guiWidth() - font.width(line)) / 2, y, -1, true)
            y += 11
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) = draw(g, listOf(
        "FarmHelper" to "", "Macro" to "§aOn", "Crop" to "Wheat", "State" to "LEFT", "Time" to "12:34", "BPS" to "§a19.6", "Rewarps" to "2"))

    private fun draw(g: GuiGraphicsExtractor, rows: List<Pair<String, String>>) {
        val s = panelScale
        val size = 11f * s
        val lineH = 13f * s
        val pad = 7f * s
        val gap = 10f * s
        val clean = rows.map { (l, r) -> l to r.replace(Regex("§."), "") }
        val leftW = clean.filter { it.second.isNotEmpty() }.maxOfOrNull { HudCard.width(it.first, size) } ?: 0f
        val width = clean.maxOf { if (it.second.isEmpty()) HudCard.width(it.first, size) else leftW + gap + HudCard.width(it.second, size) } + pad * 2
        val height = rows.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            HudCard.card(width, height, s)
            rows.forEachIndexed { i, (left, right) ->
                val y = pad + i * lineH
                HudCard.text(left, pad, y, size, if (right.isEmpty()) 0xFFFFAA00.toInt() else 0xFFBBBBBB.toInt())
                if (right.isNotEmpty()) HudCard.text(right.replace(Regex("§."), ""), pad + leftW + gap, y, size, colorOf(right))
            }
        }
    }

    private fun colorOf(value: String): Int = when {
        value.startsWith("§a") -> 0xFF55FF55.toInt()
        value.startsWith("§c") -> 0xFFFF5555.toInt()
        value.startsWith("§e") -> 0xFFFFFF55.toInt()
        else -> -1
    }

    override val label = "FarmHelper"
    private const val DEFAULT_X = 4
    private const val DEFAULT_Y = 255
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val hudFile: File by lazy { CedPaths.file("farmhelper_hud.json") }
    private val gson = Gson()

    private fun loadHud() {
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
//#endif

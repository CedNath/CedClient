package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.farmhelper.FarmHelperCore
import ced.cedclient.farmhelper.failsafe.FailsafeManager
import ced.cedclient.farmhelper.handler.GameStateHandler
import ced.cedclient.farmhelper.handler.MacroHandler
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
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
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
    /** Same order as the original MacroEnum (MacroHandler maps the index). */
    private val TYPES = listOf(
        "Vertical (wheat / carrot / potato / wart)", "Melon / Pumpkin", "Melon / Pumpkin (melonkingde)",
        "Melon / Pumpkin (default plot)", "Sugar Cane", "Cactus", "Cactus (SunTzu)", "Cocoa Beans",
        "Cocoa Beans (trapdoors)", "Cocoa Beans (left / right)", "Mushroom", "Mushroom (rotate)", "Mushroom (SDS)",
        "Circular",
    )

    private val mc get() = Minecraft.getInstance()

    val farmType = DropdownSetting("Farm Type", TYPES, description = "Layout of your farm")
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
        val inPopup = timingSettings + behaviourSettings + failsafeSettings
        inPopup.forEach { it.inPopup = true }
        addSettings(farmType, toggleKey, statusHud, options, *inPopup.toTypedArray(), rewarpsText, spawnText)
        loadHud()

        ClientTickEvents.START_CLIENT_TICK.register { FarmHelperCore.onTick() }
        on<ChatMessageEvent> { FarmHelperCore.onChat(it.unformattedText.replace(Regex("§."), "").trim()) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> FarmHelperCore.onDisconnect() }

        ClientTickEvents.END_CLIENT_TICK.register {
            val key = toggleKey.value
            val down = isEnabled && key > 0 && mc.screen == null && GLFW.glfwGetKey(mc.window.handle(), key) == GLFW.GLFW_PRESS
            if (down && !keyWasDown) MacroHandler.getInstance().toggleMacro()
            keyWasDown = down
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
        mc.player?.sendSystemMessage(ChatPrefix.msg("§6[FarmHelper] §r$text"))
    }

    // ---- values for the port

    @JvmStatic fun macroIndex(): Int = TYPES.indexOf(farmType.value).coerceAtLeast(0)
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
        val out = mutableListOf("FarmHelper" to "", "Macro" to state, "Farm" to farmType.value)
        if (mh.isMacroToggled) {
            out += "Crop" to mh.crop.label
            mh.currentMacro.ifPresent { out += "State" to it.currentState.name }
            out += "Time" to formatTime(mh.macroingTimer.elapsedTime)
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
        "FarmHelper" to "", "Macro" to "§aOn", "Farm" to TYPES[0], "Crop" to "Wheat", "State" to "LEFT", "Time" to "12:34", "Rewarps" to "2"))

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
    private const val DEFAULT_X = 6
    private const val DEFAULT_Y = 220
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

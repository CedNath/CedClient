package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.data.ClickType
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.ItemClickEvent
import ced.cedclient.events.core.TickEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.impl.render.ItemAbility
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.utils.Color
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import java.io.File
import kotlin.math.ceil

/**
 * Ragnarock Axe, phase by phase (the same timeline SkyHanni uses): a click starts the 3 s cast, a cast that goes
 * through is the 10 s strength buff, then the rest of the 20 s cooldown. A hit during the cast cancels it and leaves
 * 17 s. The action bar ("CASTING IN n", "CASTING", "CANCELLED") and chat ("cancelled due to being hit", "on
 * cooldown for n seconds") keep the timeline in step with the server.
 * Shows a HUD, titles for cast / fail / ready (each its own option), and the countdown on the axe's slot when
 * Item Cooldowns is on.
 */
object RagnarockTimer : Module(
    "Ragnarock Timer",
    Category.Skyblock,
    "Ragnarock Axe: 3 s cast, 10 s buff, then the cooldown. HUD plus titles when it casts, fails or is ready."
), HudElement {
    private val hud = BooleanSetting("HUD", true, "The phase and time left.")
    private val showReady = BooleanSetting("Show When Ready", false, "Keep the HUD up while the axe is ready.")
    private val decimals = BooleanSetting("Decimals", true, "Tenths of a second instead of whole seconds.")
    private val slotTimer = BooleanSetting("Item Slot Timer", true, "Countdown on the axe's slot (needs Item Cooldowns on).")
    private val castTitle = BooleanSetting("Cast Title", true, "Title when the cast goes through and the buff starts.")
    private val failTitle = BooleanSetting("Fail Title", true, "Title when the cast is cancelled by a hit or fails.")
    private val readyTitle = BooleanSetting("Ready Title", true, "Title when the axe is ready again.")
    private val sound = BooleanSetting("Sound", true, "A sound with each title.")
    private val castColor = ColorSetting("Cast Color", Color(255, 255, 255), "HUD colour while casting.")
    private val buffColor = ColorSetting("Active Color", Color(170, 85, 255), "HUD colour during the 10 s buff.")
    private val cooldownColor = ColorSetting("Cooldown Color", Color(255, 85, 85), "HUD colour during the cooldown.")
    private val readyColor = ColorSetting("Ready Color", Color(85, 255, 85), "HUD colour when ready.")

    private enum class Phase(val label: String) { READY("Ready"), CAST("Casting"), BUFF("Active"), COOLDOWN("Cooldown") }

    private val mc get() = Minecraft.getInstance()
    private val axe get() = ItemAbility.RAGNAROCK_AXE

    private var startedAt = 0L
    private var castEnd = 0L
    private var buffEnd = 0L
    /** When the axe is usable again; 0 = it is now. */
    private var readyAt = 0L
    /** The cast was cancelled (or the server says it's on cooldown): straight to the cooldown. */
    private var cancelled = false
    private var buffSynced = false
    private var lastFailAt = 0L
    private var lastLevel: ClientLevel? = null

    private val CODES = Regex("§.")
    private val ON_COOLDOWN = Regex("""on cooldown for (\d+(?:\.\d+)?) (?:more )?seconds?""", RegexOption.IGNORE_CASE)
    private val NO_CAST = listOf("You do not have enough mana", "You need at least", "You cannot use abilities", "You cannot do that in this room", "temporarily disabled")

    private fun phase(now: Long) = when {
        readyAt == 0L || now >= readyAt -> Phase.READY
        cancelled -> Phase.COOLDOWN
        now < castEnd -> Phase.CAST
        now < buffEnd -> Phase.BUFF
        else -> Phase.COOLDOWN
    }

    private fun holdingAxe() = mc.player?.mainHandItem?.hoverName?.string?.contains("Ragnarock") == true

    private fun reset() {
        startedAt = 0L; castEnd = 0L; buffEnd = 0L; readyAt = 0L; cancelled = false; buffSynced = false
        axe.clearCooldown()
    }

    /** The slot timer counts down to the axe being ready again. */
    private fun sync() {
        if (!slotTimer.value || readyAt == 0L) axe.clearCooldown()
        else axe.startCooldown(((readyAt - System.currentTimeMillis()).coerceAtLeast(0)) / 1000.0)
    }

    private fun startCast(now: Long) {
        startedAt = now
        castEnd = now + 3_000
        buffEnd = castEnd + 10_000
        readyAt = now + 20_000
        cancelled = false
        buffSynced = false
        sync()
    }

    /** The server confirms the cast went through: the buff starts now, 17 s of it plus cooldown left. */
    private fun castWent(now: Long) {
        if (buffSynced || cancelled) return
        if (readyAt == 0L) startedAt = now
        buffSynced = true
        castEnd = now
        buffEnd = now + 10_000
        readyAt = now + 17_000
        sync()
        notify(castTitle.value, "Ragnarock Cast!", 0xAA55FF, 1.2f)
    }

    private fun failed(title: String, cooldownMs: Long?) {
        val now = System.currentTimeMillis()
        if (now - lastFailAt < 1500) return
        lastFailAt = now
        if (cooldownMs != null) {
            cancelled = true
            castEnd = now; buffEnd = now
            readyAt = now + cooldownMs
            sync()
        } else reset()
        notify(failTitle.value, title, 0xFF5555, 0.6f)
    }

    /** Mixin: action bar text. */
    fun onActionBar(raw: String) {
        if (!isEnabled) return
        val text = raw.replace(CODES, "")
        val now = System.currentTimeMillis()
        when {
            text.contains("CASTING IN") -> if (phase(now) == Phase.READY && holdingAxe()) startCast(now)
            text.contains("CASTING") -> castWent(now)
            text.contains("CANCELLED") -> failed("Ragnarock Cancelled!", 17_000)
        }
    }

    private fun onChat(text: String) {
        if (!isEnabled) return
        val now = System.currentTimeMillis()
        if (text.contains("Ragnarock was cancelled due to being hit")) { failed("Ragnarock Cancelled!", 17_000); return }
        // The click did nothing (no mana, wrong room): no cooldown at all.
        if (phase(now) == Phase.CAST && now - startedAt < 800 && NO_CAST.any { text.contains(it) }) { failed("Ragnarock Failed!", null); return }
        if (!holdingAxe()) return
        ON_COOLDOWN.find(text)?.let { m ->
            val ms = (m.groupValues[1].toDouble() * 1000).toLong()
            cancelled = true
            castEnd = now; buffEnd = now
            readyAt = now + ms
            sync()
        }
    }

    private fun notify(enabled: Boolean, title: String, color: Int, pitch: Float) {
        if (!enabled || mc.player == null) return
        mc.gui.setTimes(5, 30, 10)
        mc.gui.setTitle(Component.literal(title).withColor(color))
        mc.gui.setSubtitle(Component.empty())
        if (sound.value) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, 1f))
    }

    private fun tick() {
        if (mc.level !== lastLevel) { lastLevel = mc.level; reset() }
        if (!isEnabled) return
        val now = System.currentTimeMillis()
        if (readyAt != 0L && now >= readyAt) {
            reset()
            notify(readyTitle.value, "Ragnarock Ready!", 0x55FF55, 1.4f)
        }
    }

    // ---------------------------------------------------------------- HUD

    private fun secs(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000.0
        return if (decimals.value) "%.1fs".format(java.util.Locale.ROOT, s) else "${ceil(s).toInt()}s"
    }

    private fun rows(): List<Triple<String, String, Int>>? {
        val now = System.currentTimeMillis()
        val p = phase(now)
        if (p == Phase.READY) return if (showReady.value) listOf(Triple("Ragnarock", "Ready", readyColor.value.rgba)) else null
        val left = when (p) { Phase.CAST -> castEnd - now; Phase.BUFF -> buffEnd - now; else -> readyAt - now }
        val color = when (p) { Phase.CAST -> castColor.value.rgba; Phase.BUFF -> buffColor.value.rgba; else -> cooldownColor.value.rgba }
        val out = mutableListOf(Triple(p.label, secs(left), color))
        if (p != Phase.COOLDOWN) out += Triple("Ready in", secs(readyAt - now), cooldownColor.value.rgba)
        return out
    }

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || !hud.value || mc.options.hideGui) return
        draw(g, rows() ?: return)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        draw(g, rows() ?: listOf(Triple("Active", secs(6_400), buffColor.value.rgba), Triple("Ready in", secs(13_400), cooldownColor.value.rgba)))
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
            HudCard.card(width, height, s)
            lines.forEachIndexed { i, (name, time, color) ->
                val y = pad + i * lineH
                HudCard.text(name, pad, y, size, color)
                HudCard.text(time, width - pad - HudCard.width(time, size), y, size, color)
            }
        }
    }

    override val label = "Ragnarock Timer"
    private const val DEFAULT_X = 3
    private const val DEFAULT_Y = 260
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile: File by lazy { CedPaths.file("ragnarock_timer_hud.json") }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }

    init {
        addSettings(hud, showReady, decimals, slotTimer, castTitle, failTitle, readyTitle, sound, castColor, buffColor, cooldownColor, readyColor)
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
        on<ChatMessageEvent> { onChat(it.unformattedText) }
        on<ItemClickEvent> { event ->
            val now = System.currentTimeMillis()
            if (isEnabled && event.clickType == ClickType.RIGHT_CLICK && event.itemInHand?.hoverName?.string?.contains("Ragnarock") == true && phase(now) == Phase.READY) startCast(now)
        }
        on<TickEvent.End> { tick() }
    }
}

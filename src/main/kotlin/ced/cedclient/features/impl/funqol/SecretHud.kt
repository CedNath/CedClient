package ced.cedclient.features.impl.funqol

import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.state.DungeonScanner
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import ced.cedclient.utils.safely
import com.google.gson.Gson
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.io.File

/** Secrets found in the dungeon room you stand in, read from the action bar's "x/y Secrets" (whoever found them). */
object SecretHud : Module(
    "Secret HUD",
    Category.Skyblock,
    "Shows how many secrets are done in the room you are in.",
    defaultEnabled = true
), HudElement {
    private val format = DropdownSetting("Format", listOf("Secrets: 3/7", "3/7 Secrets", "3/7", "3 / 7 (4 left)"), "Secrets: 3/7", "How the count is written.")
    private val showRoom = BooleanSetting("Show Room Name", true, "Room name above the count.")
    private val hideDone = BooleanSetting("Hide When Done", false, "Hide the HUD once every secret in the room is found.")
    private val textColor = ColorSetting("Text Color", Color(255, 255, 85), "Color while secrets are left.")
    private val doneColor = ColorSetting("Done Color", Color(85, 255, 85), "Color once the room is done.")
    private val background = NumberSetting("Background", 0.0, 0.0, 1.0, 0.1, "Opacity of the card behind the text (0 = none).")
    private val textSize = NumberSetting("Text Size", 10.0, 6.0, 24.0, 1.0, "Size of the text (you can also scale the whole HUD in the HUD editor).")

    init { addSettings(format, showRoom, hideDone, textSize, textColor, doneColor, background) }

    private val CODES = Regex("§.")
    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")
    private const val START = -185
    private val mc get() = Minecraft.getInstance()

    private var found = 0
    private var total = 0
    private var seenAt = 0L

    init {
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay || !isEnabled) return@register
            val m = SECRETS.find(message.string.replace(CODES, "")) ?: return@register
            found = m.groupValues[1].toInt()
            total = m.groupValues[2].toInt()
            seenAt = System.currentTimeMillis()
        }
    }

    /** The action bar only carries the count while you are in a room with secrets; it counts as gone 3 s after the last update. */
    private fun live() = total > 0 && System.currentTimeMillis() - seenAt < 3000

    private fun roomName(): String? {
        val p = mc.player ?: return null
        return DungeonScanner.room(Math.floorDiv(p.x.toInt() - START + 16, 32), Math.floorDiv(p.z.toInt() - START + 16, 32))?.name
    }

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || mc.gui.hud.isHidden() || !DungeonState.active || DungeonState.inBoss || !live()) return
        if (hideDone.value && found >= total) return
        safely("Secret HUD") { renderInternal(g) }
    }

    private fun text(f: Int, t: Int) = when (format.value) {
        "3/7 Secrets" -> "$f/$t Secrets"
        "3/7" -> "$f/$t"
        "3 / 7 (4 left)" -> "$f / $t (${(t - f).coerceAtLeast(0)} left)"
        else -> "Secrets: $f/$t"
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val real = live()
        val f = if (real) found else 3
        val t = if (real) total else 7
        val name = if (real) roomName() else "Example Room"
        val lines = ArrayList<String>()
        if (showRoom.value && name != null) lines += name
        lines += text(f, t)
        val s = panelScale
        val size = textSize.value.toFloat() * s
        val lineH = size * 1.2f
        val pad = 6f * s
        val width = HudCard.steady("Secret HUD", lines.maxOf { HudCard.width(it, size) } + pad * 2, s)
        val height = lines.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        val done = t > 0 && f >= t
        val color = (if (done) doneColor.value else textColor.value).rgba or (0xFF shl 24)
        HudCard.draw(g, panelX, panelY, behindLeap = true) {
            if (background.value > 0.0) HudCard.card(width, height, s, background.value.toFloat())
            lines.forEachIndexed { i, line ->
                val isCount = i == lines.lastIndex
                HudCard.text(line, pad, pad + i * lineH, size, if (isCount) color else 0xFFBBBBBB.toInt())
            }
        }
    }

    override val label = "Secret HUD"
    private const val DEFAULT_X = 621
    private const val DEFAULT_Y = 375
    private const val DEFAULT_SCALE = 1.3f
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = DEFAULT_SCALE
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile by lazy { ced.cedclient.config.CedPaths.hud("secret") }

    init {
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
    }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = DEFAULT_SCALE }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }
}

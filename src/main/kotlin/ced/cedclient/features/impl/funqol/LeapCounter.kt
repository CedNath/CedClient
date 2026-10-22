package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.state.DungeonState
import ced.cedclient.state.DungeonTeam
import ced.cedclient.utils.Color
import com.google.gson.Gson
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.io.File

/**
 * F7 phase 3: how many teammates have leaped to you at your spot (SS, EE2, EE3, Core, Relic).
 *
 * Teammates are read from their player entities each tick. One counts when it shows up inside the spot's box either
 * from far away (a jump of 6+ blocks in a tick, i.e. a teleport) or from nowhere (just loaded in), once per spot.
 * Plain walking or falling through the box never counts, unlike a bare "is inside the box" check.
 */
object LeapCounter : Module(
    "Leap Counter",
    Category.Skyblock,
    "F7 phase 3: shows how many teammates have leaped to you at SS / EE2 / EE3 / Core / Relic."
), HudElement {
    private val wholeSection = BooleanSetting("Count Whole Section", true, "Any teammate in the same section / phase as you counts as leaped (like NoammAddons), even if they walked there. Off: only a teammate who teleports into your spot's box counts.")
    private val alertComplete = BooleanSetting("Alert Complete", true, "Title and sound once everyone has leaped to you.")
    private val completeText = TextSetting("Complete Text", "&aEveryone Leaped!", "Title shown when everyone has leaped (& = color code).", 40)

    private val alertSound = BooleanSetting("Alert Sound", true, "Ding when everyone has leaped.")
    private val format = TextSetting("Text", "{count}/{max} Players Leaped", "HUD text. {count} = leaped, {max} = needed, {spot} = SS / EE2 / EE3 / Core / Relic.", 40)
    private val countColor = ColorSetting("Count Color", Color(170, 0, 0), "Count while two or more players are still missing.")
    private val almostColor = ColorSetting("Almost Color", Color(85, 85, 255), "Count while one player is still missing.")
    private val doneColor = ColorSetting("Done Color", Color(85, 255, 85), "Count once everyone has leaped.")
    private val textColor = ColorSetting("Text Color", Color(85, 85, 255), "Color of the rest of the text.")
    private val background = BooleanSetting("Background", true, "Draw the HUD card behind the text.")
    private val hideWhenDone = BooleanSetting("Hide When Done", true, "Hide the HUD a second after everyone has leaped (off: it stays while you are in the spot).")

    private val newRelic = BooleanSetting("ADG Leap Spots", false, "New ADG dungeon roles: the relic spots allow 3 leaps (not 4), and a second relic spot is counted in a big box around the relic area.")
    private val relicSize = NumberSetting("Relic Box Size", 5.0, 4.0, 40.0, 1.0, "ADG Leap Spots: how far (blocks, each side in x / z) the relic box reaches around the relic spot.").withDependency { newRelic.value }

    private class Spot(val name: String, var box: AABB, var cap: Int, val enabled: () -> Boolean = { true }, val inSection: (Vec3) -> Boolean) {
        val leaped = HashSet<String>()
        var doneAt = 0L
    }

    // Boxes from NoammAddons' LeapCounter; the section checks use DungeonState's own P3 sections / phase.
    /** Box around the relic spot (67.5, 6, 103.9) [r] blocks out in x / z. */
    private fun relicBox(r: Double) = AABB(67.5 - r, 3.0, 103.9 - r, 67.5 + r, 12.0, 103.9 + r)

    private val spots = listOf(
        Spot("SS", AABB(106.0, 119.0, 92.0, 109.0, 121.0, 96.0), 3) { DungeonState.f7P3SectionAt(it.x, it.y, it.z) == 1 },
        Spot("EE2", AABB(57.0, 108.0, 130.0, 59.0, 110.0, 132.0), 4) { DungeonState.f7P3SectionAt(it.x, it.y, it.z) == 2 },
        Spot("EE2", AABB(57.0, 132.0, 138.0, 62.0, 133.0, 140.0), 4) { DungeonState.f7P3SectionAt(it.x, it.y, it.z) == 2 },
        Spot("EE3", AABB(1.0, 108.0, 101.0, 3.0, 110.0, 107.0), 3) { DungeonState.f7P3SectionAt(it.x, it.y, it.z) == 3 },
        Spot("Core", AABB(51.0, 114.0, 54.0, 58.0, 117.0, 49.0), 4) { DungeonState.f7P3SectionAt(it.x, it.y, it.z) == 4 },
        Spot("Core", AABB(51.0, 114.0, 60.0, 58.0, 117.0, 55.0), 4) { DungeonState.f7P3SectionAt(it.x, it.y, it.z) == 4 },
        Spot("Relic", AABB(46.5, 3.0, 68.5, 62.5, 8.0, 84.5), 4) { DungeonState.f7PhaseAt(it.y) == 5 },
        Spot("Relic", relicBox(5.0), 3, { newRelic.value }) { DungeonState.f7PhaseAt(it.y) == 5 },
    )

    private var current: Spot? = null
    private var level: ClientLevel? = null
    private val last = HashMap<String, Vec3>()

    private val mc get() = Minecraft.getInstance()
    private fun maxCount(s: Spot) = minOf(DungeonTeam.members().size, s.cap)

    init {
        addSettings(wholeSection, newRelic, relicSize, alertComplete, completeText, alertSound, format, countColor, almostColor, doneColor, textColor, background, hideWhenDone)

        ClientTickEvents.END_CLIENT_TICK.register {
            val world = mc.level
            val player = mc.player
            if (world !== level) { level = world; spots.forEach { it.leaped.clear(); it.doneAt = 0L }; current = null; last.clear() }
            if (world == null || player == null || !isEnabled || !DungeonState.inBoss || DungeonState.floor?.endsWith("7") != true) { current = null; return@register }
            val now = System.currentTimeMillis()
            // A finished spot stays up for a second so the final count is seen.
            spots.last().box = relicBox(relicSize.value)
            // ADG roles: the original relic box stays but only 3 leap there.
            spots[spots.size - 2].cap = if (newRelic.value) 3 else 4
            val spot = spots.firstOrNull { it.enabled() && (it.doneAt == 0L || !hideWhenDone.value || now - it.doneAt < 1000) && it.box.contains(player.position()) }
            current = spot
            val names = DungeonTeam.members().filter { !it.dead }.map { it.name.lowercase() }.toSet()
            for (other in world.players()) {
                if (other === player) continue
                val key = other.gameProfile.name.lowercase()
                if (key !in names) continue
                val pos = other.position()
                val before = last.put(key, pos)
                if (spot == null || spot.doneAt != 0L || key in spot.leaped || !spot.inSection(pos)) continue
                if (!wholeSection.value) {
                    if (!spot.box.contains(pos)) continue
                    if (before != null && before.distanceToSqr(pos) < 36.0) continue
                }
                spot.leaped += key
                if (spot.leaped.size >= maxCount(spot)) {
                    spot.doneAt = now
                    if (alertComplete.value) {
                        mc.gui.setTitle(Component.literal(completeText.value.replace('&', '§')))
                        mc.gui.setTimes(5, 40, 10)
                        if (alertSound.value) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1f))
                    }
                }
            }
        }
    }

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || mc.options.hideGui || current == null) return
        renderInternal(g)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        val spot = current
        val count = spot?.leaped?.size ?: 2
        val max = spot?.let { maxCount(it) } ?: 4
        val color = when {
            max - count <= 0 -> doneColor.value.rgba
            max - count == 1 -> almostColor.value.rgba
            else -> countColor.value.rgba
        }
        val values = mapOf("{count}" to "$count", "{max}" to "$max", "{spot}" to (spot?.name ?: "SS"))
        val s = panelScale
        val size = 10f * s
        val pad = 6f * s
        // Text pieces: the {count} token in the count color, everything else in the text color.
        val parts = Regex("""\{count}|\{max}|\{spot}""").let { re ->
            val out = ArrayList<Pair<String, Int>>()
            var at = 0
            for (m in re.findAll(format.value)) {
                if (m.range.first > at) out += format.value.substring(at, m.range.first) to textColor.value.rgba
                out += values.getValue(m.value) to (if (m.value == "{count}") color else textColor.value.rgba)
                at = m.range.last + 1
            }
            if (at < format.value.length) out += format.value.substring(at) to textColor.value.rgba
            out
        }
        val textW = parts.sumOf { HudCard.width(it.first, size).toDouble() }.toFloat()
        val width = textW + pad * 2
        val height = size + pad * 2
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            if (background.value) HudCard.card(width, height, s)
            var x = pad
            for ((str, c) in parts) { HudCard.text(str, x, pad, size, c); x += HudCard.width(str, size) }
        }
    }

    override val label = "Leap Counter"
    private const val DEFAULT_X = 450
    private const val DEFAULT_Y = 300
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile: File by lazy { CedPaths.file("leap_counter_hud.json") }

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

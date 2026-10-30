package ced.cedclient.features.impl.misc

import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Where CedClient's own titles ("Warp ready", "Section done!", ...) show: a movable HUD panel (HUD editor) instead of the
 * vanilla title in the middle of the screen (a server's own titles are untouched). Features call setTitle / setSubtitle /
 * setTimes exactly like Gui's, in any order. Every module that sends titles gets its own "Show Title" toggle; the call is
 * traced back to the module that made it, and dropped when that module's toggle is off.
 */
object ClientTitles : StatHudPanel("Titles", "client_titles", 150, 330) {
    private val USERS = setOf(
        "BeachBallHelper", "BloodCamp", "CoralotHelper", "CritterSafari", "F7Titles", "MaskTimers", "I4Helper", "LividSolver",
        "WarpCooldown", "RoomAlerts", "TrevorHelper", "LeapCounter", "NecronDrop", "SbKickTimer", "PangolinCatcher",
        "RagnarockTimer", "ScoreCalculator", "SecretHighlight", "SunGecko", "VampireSlayer"
    )
    private val toggles = HashMap<String, BooleanSetting>()

    /** ModuleManager.register: gives a module that sends titles its "Show Title" toggle. */
    @JvmStatic
    fun attach(module: Module) {
        if (module.javaClass.simpleName !in USERS) return
        val toggle = BooleanSetting("Show Title", true, "Show this module's titles in the Titles HUD (move it in the HUD editor).")
        toggles[module.javaClass.name] = toggle
        module.addSettings(toggle)
    }

    /** False when the module whose code is calling has its Show Title off. */
    private fun allowed(): Boolean {
        if (toggles.isEmpty()) return true
        val frames = StackWalker.getInstance().walk { s -> s.map { it.className }.toList() }
        for (c in frames) for ((name, toggle) in toggles) if (c == name || c.startsWith("$name$")) return toggle.value
        return true
    }

    private var title: Component? = null
    private var subtitle: Component? = null
    private var startedAt = 0L
    private var fadeIn = 5
    private var stay = 40
    private var fadeOut = 10

    @JvmStatic fun setTimes(fadeInTicks: Int, stayTicks: Int, fadeOutTicks: Int) { if (!allowed()) return; fadeIn = fadeInTicks; stay = stayTicks; fadeOut = fadeOutTicks }

    @JvmStatic fun setTitle(text: Component) {
        if (!allowed()) return
        title = text
        startedAt = System.currentTimeMillis()
    }

    @JvmStatic fun setSubtitle(text: Component) { if (!allowed()) return; subtitle = text.takeIf { it.string.isNotEmpty() } }

    override fun draw(g: GuiGraphicsExtractor, preview: Boolean) {
        val font = Minecraft.getInstance().font
        val t: Component
        val sub: Component?
        var alpha = 1f
        if (preview) {
            t = Component.literal("Title"); sub = Component.literal("subtitle")
        } else {
            val shown = title ?: return
            val ms = (System.currentTimeMillis() - startedAt)
            val total = (fadeIn + stay + fadeOut) * 50L
            if (ms > total) { title = null; subtitle = null; return }
            val inMs = fadeIn * 50L; val outStart = (fadeIn + stay) * 50L
            alpha = when {
                ms < inMs && inMs > 0 -> ms.toFloat() / inMs
                ms > outStart -> 1f - (ms - outStart).toFloat() / (fadeOut * 50L).coerceAtLeast(1L)
                else -> 1f
            }.coerceIn(0f, 1f)
            t = shown; sub = subtitle
        }
        if (alpha <= 0.02f) return
        val a = ((alpha * 255).toInt() shl 24) or 0xFFFFFF
        val big = 3f; val small = 1.5f
        val tw = font.width(t) * big
        val sw = sub?.let { font.width(it) * small } ?: 0f
        val w = maxOf(tw, sw)
        g.pose().pushMatrix()
        g.pose().translate((w - tw) / 2f, 0f)
        g.pose().scale(big, big)
        g.text(font, t, 0, 0, a)
        g.pose().popMatrix()
        if (sub != null) {
            g.pose().pushMatrix()
            g.pose().translate((w - sw) / 2f, 9f * big + 4f)
            g.pose().scale(small, small)
            g.text(font, sub, 0, 0, a)
            g.pose().popMatrix()
        }
        lastWidth = w.toInt().coerceAtLeast(20)
        lastHeight = (9f * big + (if (sub != null) 4f + 9f * small else 0f)).toInt()
    }
}

package ced.cedclient.achievements

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.clickGuiScale
import ced.cedclient.utils.ui.mouseX
import ced.cedclient.utils.ui.mouseY
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * The trophy list (/cc achievements): every achievement with its progress bar, a tab per group, and for the unlocked ones
 * with a reward an Equip / Unequip button. Drawn with NanoVG in the ClickGUI style, like the update screen.
 */
class AchievementsScreen(private val parent: Screen? = null) : Screen(Component.literal("Achievements")) {

    private class Hit(val x: Float, val y: Float, val w: Float, val h: Float, val action: () -> Unit)

    private val tabs = listOf("All") + Achievements.groups
    private var tab = "All"
    private var scroll = 0f
    private val hits = ArrayList<Hit>()

    private var px = 0f; private var py = 0f; private var pw = 0f; private var ph = 0f

    private val font get() = NVGRenderer.defaultFont

    private fun layout() {
        val sw = NVGRenderer.canvasWidth / clickGuiScale
        val sh = NVGRenderer.canvasHeight / clickGuiScale
        pw = minOf(760f, sw - 60f)
        ph = minOf(860f, sh - 60f)
        px = (sw - pw) / 2f
        py = (sh - ph) / 2f
    }

    private fun shown() = Achievements.all.filter { tab == "All" || it.group == tab }
        .sortedWith(compareBy({ !Achievements.isUnlocked(it) }, { Achievements.all.indexOf(it) }))

    override fun extractRenderState(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        super.extractRenderState(g, mx, my, delta)
        NVGSpecialRenderer.draw(g, 0, 0, g.guiWidth(), g.guiHeight(), renderContent = {
            NVGRenderer.scale(clickGuiScale, clickGuiScale)
            layout()
            hits.clear()
            val mxs = mouseX / clickGuiScale
            val mys = mouseY / clickGuiScale
            val accent = ClickGUI.clickGUIColor.rgba

            NVGRenderer.dropShadow(px, py, pw, ph, 10f, 3f, 5f)
            NVGRenderer.rect(px, py, pw, ph, Colors.gray26.rgba, 5f)
            NVGRenderer.hollowRect(px, py, pw, ph, 1.5f, accent, 5f)

            NVGRenderer.text("Achievements", px + PAD, py + 12f, 24f, Colors.WHITE.rgba, font)
            val summary = "${Achievements.unlockedCount} / ${Achievements.all.size} unlocked"
            NVGRenderer.text(summary, px + pw - PAD - NVGRenderer.textWidth(summary, 14f, font), py + 20f, 14f, Colors.MINECRAFT_GRAY.rgba, font)

            // Tabs
            var tx = px + PAD
            for (t in tabs) {
                val w = NVGRenderer.textWidth(t, 15f, font) + 22f
                val hovered = mxs in tx..(tx + w) && mys in (py + 48f)..(py + 48f + 26f)
                NVGRenderer.rect(tx, py + 48f, w, 26f, (if (t == tab) accent else if (hovered) Colors.gray38.rgba else Colors.gray38.rgba), 4f)
                NVGRenderer.text(t, tx + 11f, py + 53f, 15f, if (t == tab) Colors.WHITE.rgba else Colors.MINECRAFT_GRAY.rgba, font)
                val captured = t
                hits += Hit(tx, py + 48f, w, 26f) { tab = captured; scroll = 0f }
                tx += w + 6f
            }

            // List
            val top = py + 86f
            val bottom = py + ph - PAD
            val view = bottom - top
            val items = shown()
            val contentH = items.size * (CARD_H + 8f)
            scroll = scroll.coerceIn((view - contentH).coerceAtMost(0f), 0f)
            NVGRenderer.pushScissor(px + PAD, top, pw - 2 * PAD, view)
            var y = top + scroll
            for (a in items) {
                if (y + CARD_H > top && y < bottom) card(a, px + PAD, y, pw - 2 * PAD, mxs, mys, accent, top, bottom)
                y += CARD_H + 8f
            }
            NVGRenderer.popScissor()
            if (contentH > view) {
                val thumbH = (view * view / contentH).coerceAtLeast(20f)
                val pct = (scroll / (view - contentH)).coerceIn(0f, 1f)
                NVGRenderer.rect(px + pw - PAD + 2f, top + (view - thumbH) * pct, 4f, thumbH, accent, 2f)
            }
        })
    }

    private fun card(a: Achievement, x: Float, y: Float, w: Float, mxs: Float, mys: Float, accent: Int, top: Float, bottom: Float) {
        val done = Achievements.isUnlocked(a)
        NVGRenderer.rect(x, y, w, CARD_H, Colors.gray38.rgba, 4f)
        if (done) NVGRenderer.rect(x, y, 4f, CARD_H, accent, 2f)
        val titleColor = if (done) Colors.WHITE.rgba else Colors.MINECRAFT_GRAY.rgba
        NVGRenderer.text((if (done) "✔ " else "") + a.name, x + 14f, y + 8f, 17f, titleColor, font)
        NVGRenderer.text(a.description, x + 14f, y + 30f, 13f, Colors.MINECRAFT_GRAY.rgba, font)

        val have = Achievements.counter(a.counter)
        val shown = have.coerceAtMost(a.target)
        val frac = (shown.toFloat() / a.target).coerceIn(0f, 1f)
        val barW = w - 28f - (if (a.reward != null) 150f else 0f)
        NVGRenderer.rect(x + 14f, y + 54f, barW, 6f, Colors.gray26.rgba, 3f)
        if (frac > 0f) NVGRenderer.rect(x + 14f, y + 54f, barW * frac, 6f, if (done) accent else Colors.MINECRAFT_GRAY.rgba, 3f)
        val progress = progressText(a, shown)
        NVGRenderer.text(progress, x + 14f, y + 64f, 12f, Colors.MINECRAFT_GRAY.rgba, font)

        val r = a.reward ?: return
        val bx = x + w - 140f
        val by = y + 18f
        val owned = done
        val equipped = Achievements.equipped(r.slot) == r.id
        NVGRenderer.text("Reward: ${r.label}", bx - 4f, by - 2f, 12f, if (owned) Colors.MINECRAFT_GOLD.rgba else Colors.MINECRAFT_GRAY.rgba, font)
        val label = when { !owned -> "Locked"; equipped -> "Unequip" else -> "Equip" }
        val hovered = owned && mxs in bx..(bx + 130f) && mys in (by + 16f)..(by + 16f + 26f) && mys in top..bottom
        NVGRenderer.rect(bx, by + 16f, 130f, 26f, if (hovered) accent else Colors.gray26.rgba, 4f)
        val lw = NVGRenderer.textWidth(label, 14f, font)
        NVGRenderer.text(label, bx + 65f - lw / 2f, by + 21f, 14f, if (owned) Colors.WHITE.rgba else Colors.MINECRAFT_GRAY.rgba, font)
        if (owned) hits += Hit(bx, by + 16f, 130f, 26f) { Achievements.toggleEquip(r) }
    }

    private fun progressText(a: Achievement, shown: Long): String = when (a.counter) {
        "playtime_s" -> "${shown / 3600}h ${shown % 3600 / 60}m / ${a.target / 3600}h"
        else -> "$shown / ${a.target}"
    }

    override fun mouseClicked(event: MouseButtonEvent, focused: Boolean): Boolean {
        if (event.button() != 0) return super.mouseClicked(event, focused)
        val x = mouseX / clickGuiScale
        val y = mouseY / clickGuiScale
        for (h in hits.reversed()) if (x in h.x..(h.x + h.w) && y in h.y..(h.y + h.h)) { h.action(); return true }
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll += (scrollY * 40).toFloat()
        return true
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        Achievements.flush()
        minecraft.gui.setScreen(parent)
    }

    private companion object {
        const val PAD = 20f
        const val CARD_H = 78f
    }
}

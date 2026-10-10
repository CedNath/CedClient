package ced.cedclient.features.impl.render

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.utils.Color
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.state.level.CameraRenderState
import org.joml.Matrix4f
import org.joml.Vector4f

/**
 * Entity ESP labels drawn flat on the HUD (Meteor-style) instead of as vanilla in-world nametags: a constant,
 * readable size at any distance, anti-aliased NanoVG text on a rounded translucent card, a health bar, and
 * near labels drawn over far ones.
 *
 * World pass ([capture], from EntityESPRenderer): projects each entity's head position with this frame's camera
 * matrices into normalised screen coordinates. HUD pass ([render]): draws the cards at those positions.
 */
object EntityESPLabels {

    private class Label(
        val nx: Float, val ny: Float, val depth: Float,
        val name: String, val nameColor: Int,
        val health: Float, val maxHealth: Float, val distance: Double,
    )

    @Volatile
    private var labels: List<Label> = emptyList()

    private val viewProj = Matrix4f()
    private val clip = Vector4f()

    /** World render pass: project every scanned entity. */
    fun capture(camera: CameraRenderState) {
        if (!EntityESP.isEnabled || !EntityESP.labelsEnabled) {
            labels = emptyList()
            return
        }
        val partial = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)
        viewProj.set(camera.projectionMatrix).mul(camera.viewRotationMatrix)
        val cam = camera.pos
        val out = ArrayList<Label>(EntityESP.scannedEntities.size)
        for (scanned in EntityESP.scannedEntities) {
            val e = scanned.entity
            if (e.isRemoved) continue
            val p = e.getPosition(partial)
            clip.set((p.x - cam.x).toFloat(), (p.y + e.bbHeight + 0.35 - cam.y).toFloat(), (p.z - cam.z).toFloat(), 1f)
            viewProj.transform(clip)
            if (clip.w <= 0.05f) continue // behind the camera
            val nx = clip.x / clip.w
            val ny = clip.y / clip.w
            if (nx < -1.2f || nx > 1.2f || ny < -1.2f || ny > 1.2f) continue // well off screen
            out += Label(
                nx * 0.5f + 0.5f, 0.5f - ny * 0.5f, clip.w,
                scanned.name, nameColor(scanned.category),
                e.health, e.maxHealth, scanned.distance,
            )
        }
        out.sortByDescending { it.depth } // far first, so near cards end up on top
        labels = out
    }

    /** HUD pass. */
    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        val list = labels
        if (list.isEmpty() || !EntityESP.isEnabled || !EntityESP.labelsEnabled) return
        val guiW = g.guiWidth().toFloat()
        val guiH = g.guiHeight().toFloat()
        NVGSpecialRenderer.draw(g, 0, 0, g.guiWidth(), g.guiHeight(), renderContent = {
            // Work in GUI units so labels follow the GUI Scale like the rest of the HUD.
            val ratio = NVGRenderer.canvasWidth / guiW
            NVGRenderer.scale(ratio, ratio)
            for (l in list) drawLabel(l, l.nx * guiW, l.ny * guiH)
        })
    }

    private fun drawLabel(l: Label, cx: Float, bottomY: Float) {
        val font = NVGRenderer.defaultFont
        val s = EntityESP.labelScale
        val size = 8f * s
        val padX = 4f * s
        val padY = 2.5f * s
        val gap = 4f * s
        val showBar = EntityESP.labelHealthBar && l.maxHealth > 0f
        val barH = if (showBar) 1.5f * s else 0f

        val pct = if (l.maxHealth > 0f) (l.health / l.maxHealth).coerceIn(0f, 1f) else 0f
        val hpText = compact(l.health)
        val distText = "${l.distance.toInt()}m"

        val nameW = NVGRenderer.textWidth(l.name, size, font)
        val hpW = NVGRenderer.textWidth(hpText, size, font)
        val distW = NVGRenderer.textWidth(distText, size, font)
        val w = padX * 2 + nameW + gap + hpW + gap + distW
        val h = padY * 2 + size + barH

        val x = cx - w / 2f
        val y = bottomY - h

        NVGRenderer.rect(x, y, w, h, BG, 2.5f * s)
        var tx = x + padX
        val ty = y + padY
        NVGRenderer.text(l.name, tx, ty, size, l.nameColor, font)
        tx += nameW + gap
        NVGRenderer.text(hpText, tx, ty, size, healthColor(pct), font)
        tx += hpW + gap
        NVGRenderer.text(distText, tx, ty, size, DIST, font)

        if (showBar) {
            val by = y + h - barH
            NVGRenderer.rect(x, by, w, barH, BAR_BG)
            NVGRenderer.rect(x, by, w * pct, barH, healthColor(pct))
        }
    }

    /** 950 -> "950", 12_400 -> "12.4k", 3_200_000 -> "3.2M". */
    private fun compact(v: Float): String = when {
        v >= 1_000_000f -> "%.1fM".format(v / 1_000_000f)
        v >= 10_000f -> "%.0fk".format(v / 1_000f)
        v >= 1_000f -> "%.1fk".format(v / 1_000f)
        else -> "%.0f".format(v)
    }

    private fun nameColor(category: MobCategoryType): Int = when (category) {
        MobCategoryType.HOSTILE -> HOSTILE
        MobCategoryType.PASSIVE -> PASSIVE
        MobCategoryType.PLAYER -> PLAYER
    }

    /** Green -> yellow -> red. */
    private fun healthColor(pct: Float): Int = when {
        pct >= 0.66f -> HP_HIGH
        pct >= 0.33f -> HP_MID
        else -> HP_LOW
    }

    private val BG = Color(0, 0, 0, 0.55f).rgba
    private val BAR_BG = Color(255, 255, 255, 0.12f).rgba
    private val DIST = Color(190, 190, 190).rgba
    private val HOSTILE = Color(255, 110, 110).rgba
    private val PASSIVE = Color(120, 230, 120).rgba
    private val PLAYER = Color(110, 180, 255).rgba
    private val HP_HIGH = Color(90, 220, 90).rgba
    private val HP_MID = Color(240, 200, 60).rgba
    private val HP_LOW = Color(240, 80, 80).rgba
}

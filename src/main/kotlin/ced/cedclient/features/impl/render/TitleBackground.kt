package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.render.nvg.Gradient
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Replaces the spinning panorama on the title screen with a drawn animation, picked with the Style setting.
 * TitleScreenBackgroundMixin calls [draw] instead of the panorama.
 */
object TitleBackground : Module(
    "Title Background", Category.Visuals,
    "Animated CedClient background on the title screen instead of the panorama.",
    defaultEnabled = true
) {
    private val style = DropdownSetting(
        "Style", listOf("Aurora", "Starfield", "Synthwave", "Neon Rain", "Ocean"), "Aurora",
        "Which animation to draw behind the title screen."
    )

    private val start = System.nanoTime()

    init { addSettings(style) }

    fun isActive(): Boolean = isEnabled

    private fun argb(a: Float, r: Int, g: Int, b: Int): Int =
        ((a * 255).toInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b

    private fun rnd(i: Int, k: Int): Float {
        val v = sin(i * 12.9898 + k * 78.233) * 43758.5453
        return (v - floor(v)).toFloat()
    }

    fun draw(g: GuiGraphicsExtractor, w: Int, h: Int) {
        NVGSpecialRenderer.draw(g, 0, 0, w, h, renderContent = {
            val ratio = NVGRenderer.canvasWidth / g.guiWidth()
            NVGRenderer.scale(ratio, ratio)
            val t = (System.nanoTime() - start) / 1_000_000_000f
            when (style.value) {
                "Starfield" -> starfield(t, w.toFloat(), h.toFloat())
                "Synthwave" -> synthwave(t, w.toFloat(), h.toFloat())
                "Neon Rain" -> neonRain(t, w.toFloat(), h.toFloat())
                "Ocean" -> ocean(t, w.toFloat(), h.toFloat())
                else -> aurora(t, w.toFloat(), h.toFloat())
            }
        })
    }

    // ───────────────────────── Aurora ─────────────────────────

    // blob / ribbon colours: purple, teal, blue, pink
    private val palette = listOf(intArrayOf(140, 70, 255), intArrayOf(40, 210, 190), intArrayOf(60, 110, 255), intArrayOf(230, 80, 190))

    private fun aurora(t: Float, wf: Float, hf: Float) {
        NVGRenderer.gradientRect(0f, 0f, wf, hf, 0xFF070B1E.toInt(), 0xFF2B1457.toInt(), Gradient.TopToBottom, 0f)

        // soft glow blobs, faked with stacked translucent circles
        for ((i, c) in palette.withIndex()) {
            val cx = wf * (0.5f + 0.38f * sin(t * 0.11f + i * 1.7f))
            val cy = hf * (0.5f + 0.28f * cos(t * 0.09f + i * 2.3f))
            val r = hf * (0.45f + 0.08f * sin(t * 0.2f + i))
            for (k in 0 until 8) NVGRenderer.circle(cx, cy, r * (1f - k / 8f), argb(0.045f, c[0], c[1], c[2]))
        }

        // aurora ribbons
        val step = 16
        val points = wf.toInt() / step + 2
        for (band in 0 until 3) {
            val c = palette[band]
            val baseY = hf * (0.3f + 0.17f * band)
            val xy = FloatArray(points * 2)
            for (p in 0 until points) {
                val x = p * step.toFloat()
                xy[p * 2] = x
                xy[p * 2 + 1] = baseY + 38f * sin(x * 0.006f + t * (0.35f + 0.1f * band) + band * 2f) +
                    16f * sin(x * 0.017f - t * 0.5f + band)
            }
            NVGRenderer.strokePath(xy, 34f, argb(0.05f, c[0], c[1], c[2]))
            NVGRenderer.strokePath(xy, 16f, argb(0.10f, c[0], c[1], c[2]))
            NVGRenderer.strokePath(xy, 4f, argb(0.30f, c[0], c[1], c[2]))
        }

        // rising sparkles
        for (i in 0 until 70) {
            val speed = 0.015f + rnd(i, 1) * 0.035f
            val life = (t * speed + rnd(i, 2)) % 1f
            val x = wf * rnd(i, 3) + 14f * sin(t * 0.6f + i)
            val y = hf * (1.05f - life * 1.1f)
            val twinkle = 0.5f + 0.5f * sin(t * (1.5f + rnd(i, 4) * 2f) + i)
            val fade = minOf(life, 1f - life) * 4f
            NVGRenderer.circle(x, y, 0.8f + rnd(i, 5) * 1.6f, argb(0.75f * twinkle * fade.coerceIn(0f, 1f), 255, 255, 255))
        }
    }

    // ───────────────────────── Starfield (warp flight) ─────────────────────────

    private fun starfield(t: Float, wf: Float, hf: Float) {
        NVGRenderer.gradientRect(0f, 0f, wf, hf, 0xFF02030A.toInt(), 0xFF0A0F2A.toInt(), Gradient.TopToBottom, 0f)
        val cx = wf / 2f
        val cy = hf / 2f
        for (k in 0 until 6) NVGRenderer.circle(cx, cy, hf * (0.55f - k * 0.08f), argb(0.03f, 90, 70, 200))

        val reach = maxOf(wf, hf)
        for (i in 0 until 220) {
            val life = (t * (0.06f + rnd(i, 1) * 0.1f) + rnd(i, 2)) % 1f
            val angle = rnd(i, 3) * 6.2831855f
            val d = life * life * reach * 0.75f // accelerates outwards
            val len = 2f + life * 26f
            val x2 = cx + cos(angle) * d
            val y2 = cy + sin(angle) * d
            val x1 = cx + cos(angle) * (d - len).coerceAtLeast(0f)
            val y1 = cy + sin(angle) * (d - len).coerceAtLeast(0f)
            val a = (life * 1.6f).coerceIn(0f, 1f)
            NVGRenderer.line(x1, y1, x2, y2, 0.6f + life * 1.6f, argb(a, 200 + (rnd(i, 4) * 55).toInt(), 210, 255))
        }
    }

    // ───────────────────────── Synthwave ─────────────────────────

    private fun synthwave(t: Float, wf: Float, hf: Float) {
        val horizon = hf * 0.58f
        NVGRenderer.gradientRect(0f, 0f, wf, horizon, 0xFF12052E.toInt(), 0xFFB0236E.toInt(), Gradient.TopToBottom, 0f)
        NVGRenderer.rect(0f, horizon, wf, hf - horizon, 0xFF0B0620.toInt())

        // sun with the classic stripes cut out of its lower half
        val sunR = hf * 0.2f
        val sunX = wf / 2f
        val sunY = horizon - sunR * 0.55f
        for (k in 0 until 6) NVGRenderer.circle(sunX, sunY, sunR * (1.5f - k * 0.08f), argb(0.04f, 255, 90, 160))
        NVGRenderer.circle(sunX, sunY, sunR, 0xFFFFC447.toInt())
        NVGRenderer.circle(sunX, sunY - sunR * 0.0f, sunR * 0.98f, argb(0.35f, 255, 60, 140))
        for (s in 0 until 6) {
            val y = sunY + sunR * (0.05f + s * 0.16f)
            val thick = 1.5f + s * 1.6f
            NVGRenderer.rect(sunX - sunR, y, sunR * 2f, thick, if (s % 2 == 0) 0xFF3A0B45.toInt() else 0xFF24092F.toInt())
        }
        NVGRenderer.rect(0f, horizon, wf, hf - horizon, 0xFF0B0620.toInt())

        // moving perspective grid
        val neon = argb(0.85f, 255, 60, 200)
        val glow = argb(0.18f, 255, 60, 200)
        for (i in 0 until 14) {
            val f = ((i + t * 0.35f) % 14f) / 14f
            val y = horizon + (hf - horizon) * f * f
            NVGRenderer.line(0f, y, wf, y, 3.5f * f + 0.6f, glow)
            NVGRenderer.line(0f, y, wf, y, 1.2f * f + 0.4f, neon)
        }
        for (i in -14..14) {
            val bottomX = wf / 2f + i * wf * 0.1f
            NVGRenderer.line(wf / 2f + i * wf * 0.012f, horizon, bottomX, hf, 1.2f, argb(0.7f, 255, 60, 200))
        }
        NVGRenderer.gradientRect(0f, horizon - 2f, wf, 8f, argb(0.5f, 255, 120, 220), argb(0f, 255, 120, 220), Gradient.TopToBottom, 0f)
    }

    // ───────────────────────── Neon Rain ─────────────────────────

    private fun neonRain(t: Float, wf: Float, hf: Float) {
        NVGRenderer.gradientRect(0f, 0f, wf, hf, 0xFF03060F.toInt(), 0xFF07162A.toInt(), Gradient.TopToBottom, 0f)
        val cols = (wf / 14f).toInt()
        for (i in 0 until cols) {
            val speed = 0.12f + rnd(i, 1) * 0.28f
            val trail = hf * (0.18f + rnd(i, 2) * 0.35f)
            val head = ((t * speed + rnd(i, 3)) % 1.4f) * (hf + trail) - trail * 0.2f
            val x = i * 14f + 7f
            val teal = rnd(i, 4) > 0.35f
            val (r, gC, b) = if (teal) Triple(40, 235, 200) else Triple(120, 110, 255)
            NVGRenderer.gradientRect(x - 1f, head - trail, 2f, trail, argb(0f, r, gC, b), argb(0.55f, r, gC, b), Gradient.TopToBottom, 1f)
            NVGRenderer.circle(x, head, 2.2f, argb(0.95f, 235, 255, 250))
            NVGRenderer.circle(x, head, 6f, argb(0.12f, r, gC, b))
        }
    }

    // ───────────────────────── Ocean ─────────────────────────

    private fun ocean(t: Float, wf: Float, hf: Float) {
        NVGRenderer.gradientRect(0f, 0f, wf, hf, 0xFF0E5C8A.toInt(), 0xFF032A4A.toInt(), Gradient.TopToBottom, 0f)

        // light shafts from above
        for (i in 0 until 5) {
            val x = wf * (0.1f + i * 0.2f) + 40f * sin(t * 0.15f + i)
            NVGRenderer.polygon(
                floatArrayOf(x - 20f, 0f, x + 20f, 0f, x + 120f + 30f * sin(t * 0.2f + i), hf, x - 60f, hf),
                argb(0.035f, 200, 240, 255)
            )
        }

        // layered waves, far to near
        val step = 18
        val points = wf.toInt() / step + 2
        val layers = listOf(
            Triple(0.45f, argb(0.30f, 40, 150, 210), 0.5f),
            Triple(0.58f, argb(0.45f, 20, 110, 175), 0.7f),
            Triple(0.72f, argb(0.65f, 10, 70, 130), 0.9f),
            Triple(0.86f, argb(0.85f, 4, 40, 90), 1.1f),
        )
        for ((li, layer) in layers.withIndex()) {
            val xy = FloatArray((points + 2) * 2)
            for (p in 0 until points) {
                val x = p * step.toFloat()
                xy[p * 2] = x
                xy[p * 2 + 1] = hf * layer.first + 22f * sin(x * 0.008f + t * layer.third + li * 1.9f) +
                    9f * sin(x * 0.021f - t * layer.third * 1.4f)
            }
            xy[points * 2] = wf + step; xy[points * 2 + 1] = hf
            xy[points * 2 + 2] = 0f; xy[points * 2 + 3] = hf
            NVGRenderer.polygon(xy, layer.second)
        }

        // bubbles
        for (i in 0 until 40) {
            val life = (t * (0.03f + rnd(i, 1) * 0.05f) + rnd(i, 2)) % 1f
            val x = wf * rnd(i, 3) + 10f * sin(t * 0.8f + i)
            val y = hf * (1.05f - life * 1.1f)
            val r = 1.5f + rnd(i, 4) * 3.5f
            NVGRenderer.circle(x, y, r, argb(0.10f, 200, 235, 255))
            NVGRenderer.circle(x - r * 0.3f, y - r * 0.3f, r * 0.35f, argb(0.45f, 255, 255, 255))
        }
    }
}

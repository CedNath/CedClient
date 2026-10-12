package ced.cedclient.utils.ui

import net.minecraft.client.Minecraft

inline val mouseX: Float
    get() = Minecraft.getInstance().mouseHandler.xpos().toFloat()

inline val mouseY: Float
    get() = Minecraft.getInstance().mouseHandler.ypos().toFloat()

/**
 * GUI scale the ClickGUI is rendered at: 1.15 on a window 2560 px wide or more, shrinking with narrower windows so
 * the same panel layout fits (1080p gets ~0.86). Every isAreaHovered(scaled=true) call site (and ClickGUI's own
 * extractRenderState) is already written against this, so it's the one place the scale is decided.
 */
val clickGuiScale: Float
    get() {
        val width = Minecraft.getInstance().window.width
        return if (width <= 0) 1.15f else 1.15f * minOf(1f, width / 2560f)
    }

fun isAreaHovered(x: Float, y: Float, w: Float, h: Float, scaled: Boolean = false): Boolean =
    if (scaled) mouseX / clickGuiScale in x..(x + w) && mouseY / clickGuiScale in y..(y + h)
    else mouseX in x..(x + w) && mouseY in y..(y + h)

fun isAreaHovered(x: Float, y: Float, w: Float, scaled: Boolean = false): Boolean =
    if (scaled) mouseX / clickGuiScale in x..(x + w) && mouseY / clickGuiScale >= y
    else mouseX in x..(x + w) && mouseY >= y
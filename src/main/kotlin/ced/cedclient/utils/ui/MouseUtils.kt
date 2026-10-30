package ced.cedclient.utils.ui

import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.ui.clickgui.ClickGUI
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
        val mc = Minecraft.getInstance()
        // The classic layout is a row of panels, so it runs smaller (Odin-sized) than the compact window.
        val inGui = mc.gui.screen() is ClickGUI
        val base = when {
            inGui && !ClickGUI.compact -> 0.86f
            inGui -> 1.15f * (Theme.menuSize.value.toFloat() / 100f) // Theme > Menu Size
            else -> 1.15f
        }
        val width = mc.window.width
        return if (width <= 0) base else base * minOf(1f, width / 2560f)
    }

fun isAreaHovered(x: Float, y: Float, w: Float, h: Float, scaled: Boolean = false): Boolean =
    if (scaled) mouseX / clickGuiScale in x..(x + w) && mouseY / clickGuiScale in y..(y + h)
    else mouseX in x..(x + w) && mouseY in y..(y + h)

fun isAreaHovered(x: Float, y: Float, w: Float, scaled: Boolean = false): Boolean =
    if (scaled) mouseX / clickGuiScale in x..(x + w) && mouseY / clickGuiScale >= y
    else mouseX in x..(x + w) && mouseY >= y
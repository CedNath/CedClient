package ced.cedclient.utils.ui

import net.minecraft.client.Minecraft

inline val mouseX: Float
    get() = Minecraft.getInstance().mouseHandler.xpos().toFloat()

inline val mouseY: Float
    get() = Minecraft.getInstance().mouseHandler.ypos().toFloat()

/**
 * GUI scale the ClickGUI is rendered at. Fixed at 1.15f -- there's no
 * in-GUI "GUI Scale" module/slider anymore, this is just the flat default
 * everything renders at. Every isAreaHovered(scaled=true) call site (and
 * ClickGUI's own extractRenderState) is already written against this var,
 * so changing this one number is all it takes to change the scale.
 */
var clickGuiScale: Float = 1.15f

fun isAreaHovered(x: Float, y: Float, w: Float, h: Float, scaled: Boolean = false): Boolean =
    if (scaled) mouseX / clickGuiScale in x..(x + w) && mouseY / clickGuiScale in y..(y + h)
    else mouseX in x..(x + w) && mouseY in y..(y + h)

fun isAreaHovered(x: Float, y: Float, w: Float, scaled: Boolean = false): Boolean =
    if (scaled) mouseX / clickGuiScale in x..(x + w) && mouseY / clickGuiScale >= y
    else mouseX in x..(x + w) && mouseY >= y
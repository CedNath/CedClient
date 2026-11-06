package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.render.MasterHudEditScreen
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Color.Companion.brighter
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import net.minecraft.client.Minecraft
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * The floating search box at the bottom of the ClickGUI. Panel.kt filters
 * its module buttons against [currentSearch] every draw/click/key call.
 *
 * Ported from Odin's clickgui/SearchBar.kt — same 350x40 box, same
 * "Search here..." placeholder, same 16-char cap.
 */
object SearchBar {

    var currentSearch = ""
        private set(value) {
            if (value == field || value.length > 16) return
            field = value
        }

    // Measured when drawn, not when typed: the measure depends on the current NanoVG scale and on the chosen font.
    private val placeHolderWidth get() = NVGRenderer.textWidth("Search here...", 20f, NVGRenderer.defaultFont)
    private val searchWidth get() = NVGRenderer.textWidth(currentSearch, 20f, NVGRenderer.defaultFont)

    private val textInputHandler = TextInputHandler(
        textProvider = { currentSearch },
        textSetter = { currentSearch = it }
    )

    private const val HUD_BTN_W = 56f

    /** Width of the whole bar: the search box, a gap, then the HUD editor button. */
    const val TOTAL_WIDTH = 350f + 6f + HUD_BTN_W

    private var barX = 0f
    private var barY = 0f

    fun draw(x: Float, y: Float, mouseX: Float, mouseY: Float) {
        barX = x
        barY = y
        val bx = x + 356f
        val hovered = mouseX in bx..(bx + HUD_BTN_W) && mouseY in y..(y + 40f)
        NVGRenderer.rect(bx, y, HUD_BTN_W, 40f, (if (hovered) Colors.gray38.brighter(1.3f) else Colors.gray38).rgba, 9f)
        NVGRenderer.hollowRect(bx, y, HUD_BTN_W, 40f, 3f, Colors.WHITE.withAlpha(if (hovered) 1f else 0.75f).rgba, 9f)
        val label = "HUD"
        NVGRenderer.text(label, bx + HUD_BTN_W / 2f - NVGRenderer.textWidth(label, 16f, NVGRenderer.defaultFont) / 2f, y + 12f, 16f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        NVGRenderer.dropShadow(x, y, 350f, 40f, 10f, 0.75f, 9f)
        NVGRenderer.rect(x, y, 350f, 40f, Colors.gray38.rgba, 9f)
        NVGRenderer.hollowRect(x, y, 350f, 40f, 3f, ClickGUI.clickGUIColor.rgba, 9f)

        val textY = y + 10f

        if (currentSearch.isEmpty()) NVGRenderer.text(
            "Search here...",
            x + 175f - placeHolderWidth / 2,
            textY,
            20f,
            Colors.WHITE.rgba,
            NVGRenderer.defaultFont
        )
        textInputHandler.x =
            (x + 175f - searchWidth / 2 - if (currentSearch.isEmpty()) placeHolderWidth / 2 + 2f else 0f).coerceAtLeast(
                x
            )
        textInputHandler.y = textY - 1
        textInputHandler.width = 250f
        textInputHandler.height = 22f
        textInputHandler.draw(mouseX, mouseY)
    }

    fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        val bx = barX + 356f
        if (click.button() == 0 && mouseX in bx..(bx + HUD_BTN_W) && mouseY in barY..(barY + 40f)) {
            // Deferred out of the click event: switching screens inside it gets undone (same reason /cc hud waits a tick).
            ced.cedclient.utils.Debug.line("HUD Button", "clicked, opening the HUD editor next tick")
            val mc = Minecraft.getInstance()
            mc.execute {
                try {
                    mc.gui.setScreen(MasterHudEditScreen())
                    ced.cedclient.utils.Debug.line("HUD Button", "setScreen done, screen is now ${mc.gui.screen()?.javaClass?.simpleName}")
                } catch (t: Throwable) {
                    ced.cedclient.utils.Debug.line("HUD Button", "failed: $t")
                }
            }
            return true
        }
        return textInputHandler.mouseClicked(mouseX, mouseY, click)
    }

    /** Starts typing into the box without a click. */
    fun focus() = textInputHandler.focus()

    fun mouseReleased() {
        textInputHandler.mouseReleased()
    }

    fun keyPressed(input: KeyEvent): Boolean {
        return textInputHandler.keyPressed(input)
    }

    fun keyTyped(input: CharacterEvent): Boolean {
        return textInputHandler.keyTyped(input)
    }
}
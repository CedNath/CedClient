package ced.cedclient.features.impl.render

import ced.cedclient.render.cosmetic.CosmeticStore
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import ced.cedclient.utils.ui.clickGuiScale
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.sign
import ced.cedclient.utils.ui.mouseX as cedMouseX
import ced.cedclient.utils.ui.mouseY as cedMouseY

/**
 * Quick preset switcher for Cosmetic Blocks: lists the presets of the island you are on, click (or press 1-9) to
 * switch. Also creates presets and exports / imports the whole Cosmetic Blocks config.
 * Opened with the "Preset Switcher Key" or /cc cosmetic presets.
 */
class CosmeticPresetScreen : Screen(Component.literal("Cosmetic Presets")) {

    private var presets: List<String> = emptyList()
    private var newName = ""
    private var status = ""
    private var scroll = 0f

    private val input = TextInputHandler(textProvider = { newName }, textSetter = { newName = it.take(32) })

    // layout (canvas space, set every frame)
    private var x = 0f
    private var y = 0f
    private val listY get() = y + 40f
    private val listHeight get() = VISIBLE_ROWS.coerceAtMost(presets.size.coerceAtLeast(1)) * ROW_HEIGHT
    private val inputY get() = listY + listHeight + 10f
    private val buttonsY get() = inputY + INPUT_HEIGHT + 8f
    private val height get() = buttonsY + BUTTON_HEIGHT + 30f - y

    private val buttons: List<Pair<String, () -> Unit>> = listOf(
        "Export File" to { status = CosmeticBlocks.exportConfigToFile(null) },
        "Copy All" to { status = CosmeticBlocks.copyConfig() },
        "Paste (Merge)" to { status = CosmeticBlocks.pasteConfig(replace = false); refresh() },
    )

    private fun refresh() {
        presets = CosmeticStore.listPresets()
    }

    override fun init() {
        refresh()
        super.init()
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        NVGSpecialRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight(), renderContent = {
            val mx = cedMouseX / clickGuiScale
            val my = cedMouseY / clickGuiScale
            NVGRenderer.scale(clickGuiScale, clickGuiScale)
            val cw = NVGRenderer.canvasWidth / clickGuiScale
            val ch = NVGRenderer.canvasHeight / clickGuiScale

            NVGRenderer.rect(0f, 0f, cw, ch, Colors.BLACK.withAlpha(0.35f).rgba)
            x = (cw - WIDTH) / 2f
            y = (ch - height) / 2f
            draw(mx, my)
        })
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    private fun draw(mx: Float, my: Float) {
        val font = NVGRenderer.defaultFont
        NVGRenderer.dropShadow(x, y, WIDTH, height, 10f, 3f, 5f)
        NVGRenderer.rect(x, y, WIDTH, height, Colors.gray26.rgba, 5f)
        NVGRenderer.hollowRect(x, y, WIDTH, height, 1.5f, ClickGUI.clickGUIColor.rgba, 5f)

        val title = "Presets - ${CosmeticStore.islandKey}"
        NVGRenderer.text(title, x + WIDTH / 2f - NVGRenderer.textWidth(title, 18f, font) / 2f, y + 10f, 18f, Colors.WHITE.rgba, font)

        // Preset rows
        val active = CosmeticStore.activePresetName
        val contentHeight = presets.size * ROW_HEIGHT
        scroll = scroll.coerceIn((listHeight - contentHeight).coerceAtMost(0f), 0f)
        NVGRenderer.pushScissor(x, listY, WIDTH, listHeight)
        presets.forEachIndexed { i, name ->
            val rowY = listY + scroll + i * ROW_HEIGHT
            if (rowY + ROW_HEIGHT < listY || rowY > listY + listHeight) return@forEachIndexed
            val isActive = name == active
            val hovered = my in listY..(listY + listHeight) && hovered(x + 8f, rowY, WIDTH - 16f, ROW_HEIGHT - 2f, mx, my)
            val bg = when {
                isActive -> ClickGUI.clickGUIColor.rgba
                hovered -> Colors.gray38.rgba
                else -> null
            }
            bg?.let { NVGRenderer.rect(x + 8f, rowY, WIDTH - 16f, ROW_HEIGHT - 2f, it, 4f) }
            val num = if (i < 9) "${i + 1}" else ""
            NVGRenderer.text(num, x + 16f, rowY + 4f, 15f, Colors.MINECRAFT_GRAY.rgba, font)
            NVGRenderer.text(NVGRenderer.truncate(name, WIDTH - 130f, 15f, font), x + 36f, rowY + 4f, 15f, Colors.WHITE.rgba, font)
            val count = "${CosmeticStore.presetSize(name)} blocks"
            NVGRenderer.text(count, x + WIDTH - 16f - NVGRenderer.textWidth(count, 13f, font), rowY + 5f, 13f, Colors.MINECRAFT_GRAY.rgba, font)
        }
        NVGRenderer.popScissor()

        // New preset: name field + button
        val fieldW = WIDTH - 24f - NEW_BUTTON_W - 6f
        NVGRenderer.rect(x + 12f, inputY, fieldW, INPUT_HEIGHT, Colors.gray38.rgba, 4f)
        if (newName.isEmpty()) NVGRenderer.text("New preset name...", x + 18f, inputY + 5f, 15f, Colors.MINECRAFT_GRAY.rgba, font)
        input.x = x + 12f
        input.y = inputY + 1f
        input.width = fieldW
        input.height = INPUT_HEIGHT - 2f
        input.draw(mx, my)
        button("New", x + WIDTH - 12f - NEW_BUTTON_W, inputY, NEW_BUTTON_W, INPUT_HEIGHT, mx, my)

        // Config buttons
        val bw = buttonWidth()
        buttons.forEachIndexed { i, (label, _) ->
            button(label, x + 12f + i * (bw + GAP), buttonsY, bw, BUTTON_HEIGHT, mx, my)
        }

        val hint = status.ifEmpty { "Click or press 1-9 to switch - Esc to close" }
        NVGRenderer.text(NVGRenderer.truncate(hint, WIDTH - 24f, 13f, font), x + 12f, buttonsY + BUTTON_HEIGHT + 8f, 13f, Colors.MINECRAFT_GRAY.rgba, font)
    }

    private fun button(label: String, bx: Float, by: Float, bw: Float, bh: Float, mx: Float, my: Float) {
        val font = NVGRenderer.defaultFont
        NVGRenderer.rect(bx, by, bw, bh, (if (hovered(bx, by, bw, bh, mx, my)) ClickGUI.clickGUIColor else Colors.gray38).rgba, 4f)
        val text = NVGRenderer.truncate(label, bw - 8f, 14f, font)
        NVGRenderer.text(text, bx + bw / 2f - NVGRenderer.textWidth(text, 14f, font) / 2f, by + (bh - 14f) / 2f, 14f, Colors.WHITE.rgba, font)
    }

    private fun buttonWidth() = (WIDTH - 24f - GAP * (buttons.size - 1)) / buttons.size

    private fun hovered(ax: Float, ay: Float, aw: Float, ah: Float, mx: Float, my: Float) = mx in ax..(ax + aw) && my in ay..(ay + ah)

    private fun switchTo(index: Int) {
        val name = presets.getOrNull(index) ?: return
        if (CosmeticStore.usePreset(name)) status = "Now using '$name'"
    }

    private fun createPreset() {
        val name = CosmeticStore.sanitize(newName)
        if (name.isEmpty()) return
        status = if (CosmeticStore.createPreset(name)) {
            CosmeticStore.usePreset(name)
            newName = ""
            refresh()
            "Created and switched to '$name'"
        } else "Preset '$name' already exists"
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mx = cedMouseX / clickGuiScale
        val my = cedMouseY / clickGuiScale
        if (input.mouseClicked(mx, my, mouseButtonEvent)) return true
        if (mouseButtonEvent.button() != 0) return true

        if (my in listY..(listY + listHeight) && mx in x..(x + WIDTH)) {
            switchTo(((my - listY - scroll) / ROW_HEIGHT).toInt())
            return true
        }
        if (hovered(x + WIDTH - 12f - NEW_BUTTON_W, inputY, NEW_BUTTON_W, INPUT_HEIGHT, mx, my)) {
            createPreset()
            return true
        }
        val bw = buttonWidth()
        buttons.forEachIndexed { i, (_, action) ->
            if (hovered(x + 12f + i * (bw + GAP), buttonsY, bw, BUTTON_HEIGHT, mx, my)) {
                action()
                return true
            }
        }
        if (!hovered(x, y, WIDTH, height, mx, my)) onClose()
        return true
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        input.mouseReleased()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        scroll += (verticalAmount.sign * 16).toFloat()
        return true
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (keyEvent.key == GLFW.GLFW_KEY_ENTER && newName.isNotBlank()) {
            createPreset()
            return true
        }
        if (input.keyPressed(keyEvent)) return true
        return super.keyPressed(keyEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (input.keyTyped(characterEvent)) return true
        val c = characterEvent.codepointAsString()
        if (c.length == 1 && c[0] in '1'..'9') {
            switchTo(c[0] - '1')
            return true
        }
        return super.charTyped(characterEvent)
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private const val WIDTH = 340f
        private const val ROW_HEIGHT = 24f
        private const val VISIBLE_ROWS = 10
        private const val INPUT_HEIGHT = 24f
        private const val BUTTON_HEIGHT = 24f
        private const val NEW_BUTTON_W = 60f
        private const val GAP = 6f
    }
}

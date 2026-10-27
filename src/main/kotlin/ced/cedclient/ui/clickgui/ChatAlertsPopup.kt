package ced.cedclient.ui.clickgui

import ced.cedclient.features.impl.misc.ChatAlert
import ced.cedclient.features.impl.misc.ChatPing
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.utils.Colors
import ced.cedclient.utils.ui.TextInputHandler
import net.minecraft.client.Minecraft
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

/**
 * Opened by Chat Features' "Alerts". Lists every [ChatAlert] with an on/off box; clicking a row opens that
 * alert's settings (a SettingsPopup with a Back button), the field on top adds a new alert from its trigger words.
 * Same layout and scrolling as ChatFilterPopup.
 */
class ChatAlertsPopup : Popup("Chat Alerts", WIDTH, HEIGHT) {

    private var scrollOffset = 0f
    private var newEntry = ""

    private var draggingScrollbar = false
    private var dragStartMouseY = 0f
    private var dragStartScrollOffset = 0f

    private val input = TextInputHandler(
        textProvider = { newEntry },
        textSetter = { newEntry = it.take(128) }
    )

    init { input.focus() }

    private val inputY get() = y + 34f
    private val inputX get() = x + 12f
    private val addButtonWidth = 56f
    private val inputWidth = width - 24f - addButtonWidth - 8f
    private val addButtonX get() = inputX + inputWidth + 8f

    private val listY get() = y + 68f
    private val listHeight get() = height - 78f

    private val scrollbarWidth = 5f
    private val scrollbarX get() = x + width - scrollbarWidth - 4f

    private fun submitNewEntry() {
        val trimmed = newEntry.trim()
        if (trimmed.isEmpty()) return
        newEntry = ""
        openEditor(ChatPing.addAlert(trimmed))
    }

    private fun openEditor(alert: ChatAlert) {
        val gui = Minecraft.getInstance().gui.screen() as? ClickGUI ?: return
        val back = ActionSetting("Back") {
            ChatPing.saveAlerts()
            gui.openPopup(ChatAlertsPopup())
        }
        gui.openPopup(
            SettingsPopup(
                "Alert: ${alert.label}",
                listOf(
                    listOf(
                        SettingsPopup.Section("Trigger", listOf(alert.triggers, alert.regex, alert.exclude, alert.ignoreOwn, alert.cooldown)),
                        SettingsPopup.Section("Chat Line", listOf(alert.highlight, alert.color, alert.replaceLine, alert.copyLine)),
                    ),
                    listOf(
                        SettingsPopup.Section("Sound", listOf(alert.sound, alert.customSound, alert.volume, alert.pitch)),
                        SettingsPopup.Section("HUD", listOf(alert.hudMessage, alert.hudSeconds)),
                    ),
                ),
                listOf(back, alert.test),
                height = 700f,
            )
        )
    }

    override fun drawContent(mouseX: Float, mouseY: Float) {
        NVGRenderer.rect(inputX, inputY, inputWidth, INPUT_HEIGHT, Colors.INPUT_BG.rgba, 4f)
        NVGRenderer.hollowRect(inputX, inputY, inputWidth, INPUT_HEIGHT, 1f, Colors.INPUT_BORDER.rgba, 4f)
        input.x = inputX
        input.y = inputY + 1f
        input.width = inputWidth
        input.height = INPUT_HEIGHT - 2f
        input.draw(mouseX, mouseY)

        val addHovered = isAreaHovered(addButtonX, inputY, addButtonWidth, INPUT_HEIGHT, mouseX, mouseY)
        NVGRenderer.rect(addButtonX, inputY, addButtonWidth, INPUT_HEIGHT, (if (addHovered) ClickGUI.clickGUIColor else Colors.gray38).rgba, 4f)
        val addWidth = NVGRenderer.textWidth("Add", 16f, NVGRenderer.defaultFont)
        NVGRenderer.text("Add", addButtonX + addButtonWidth / 2f - addWidth / 2f, inputY + 5f, 16f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        val list = ChatPing.alerts
        val contentHeight = list.size * ROW_HEIGHT
        val needsScrollbar = contentHeight > listHeight

        if (draggingScrollbar) {
            val maxScroll = (-(contentHeight - listHeight)).coerceAtMost(0f)
            val thumbHeight = (listHeight * listHeight / contentHeight).coerceAtLeast(MIN_THUMB_HEIGHT)
            val maxThumbTravel = listHeight - thumbHeight
            if (maxThumbTravel > 0f) {
                scrollOffset = (dragStartScrollOffset + (mouseY - dragStartMouseY) * (maxScroll / maxThumbTravel)).coerceIn(maxScroll, 0f)
            }
        }

        NVGRenderer.pushScissor(x, listY, width, listHeight)
        var rowY = listY + scrollOffset
        for (alert in list) {
            drawRow(alert, rowY, mouseX, mouseY)
            rowY += ROW_HEIGHT
        }
        if (list.isEmpty()) {
            val hint = "Type trigger words above and press Add"
            val hintWidth = NVGRenderer.textWidth(hint, 15f, NVGRenderer.defaultFont)
            NVGRenderer.text(hint, x + width / 2f - hintWidth / 2f, listY + 8f, 15f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
        }
        NVGRenderer.popScissor()

        if (needsScrollbar) drawScrollbar(contentHeight, mouseX, mouseY)
    }

    private fun drawScrollbar(contentHeight: Float, mouseX: Float, mouseY: Float) {
        NVGRenderer.rect(scrollbarX, listY, scrollbarWidth, listHeight, Colors.gray26.rgba, scrollbarWidth / 2f)
        val thumbHeight = (listHeight * listHeight / contentHeight).coerceAtLeast(MIN_THUMB_HEIGHT)
        val maxScroll = -(contentHeight - listHeight)
        val scrollPercent = if (maxScroll < 0f) (scrollOffset / maxScroll).coerceIn(0f, 1f) else 0f
        val thumbY = listY + (listHeight - thumbHeight) * scrollPercent
        val hovered = isAreaHovered(scrollbarX, thumbY, scrollbarWidth, thumbHeight, mouseX, mouseY)
        val color = if (draggingScrollbar || hovered) ClickGUI.clickGUIColor else Colors.gray38
        NVGRenderer.rect(scrollbarX, thumbY, scrollbarWidth, thumbHeight, color.rgba, scrollbarWidth / 2f)
    }

    private fun drawRow(alert: ChatAlert, rowY: Float, mouseX: Float, mouseY: Float) {
        if (rowY + ROW_HEIGHT < listY || rowY > listY + listHeight) return
        val rowWidth = width - 24f

        val hovered = isAreaHovered(x + 12f, rowY, rowWidth, ROW_HEIGHT - 2f, mouseX, mouseY)
        if (hovered) NVGRenderer.rect(x + 12f, rowY, rowWidth, ROW_HEIGHT - 2f, Colors.gray38.rgba, 3f)

        val boxSize = 13f
        val boxColor = if (alert.enabled) ClickGUI.clickGUIColor else Colors.gray38
        NVGRenderer.rect(x + 16f, rowY + (ROW_HEIGHT - 2f) / 2f - boxSize / 2f, boxSize, boxSize, boxColor.rgba, 3f)

        val labelMaxWidth = rowWidth - boxSize - 16f - REMOVE_SIZE - 14f
        val label = NVGRenderer.truncate(alert.label, labelMaxWidth, 15f, NVGRenderer.defaultFont)
        NVGRenderer.text(label, x + 16f + boxSize + 8f, rowY + (ROW_HEIGHT - 2f) / 2f - 7f, 15f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        val removeX = x + width - 12f - REMOVE_SIZE
        val removeY = rowY + (ROW_HEIGHT - 2f) / 2f - REMOVE_SIZE / 2f
        val removeHovered = isAreaHovered(removeX, removeY, REMOVE_SIZE, REMOVE_SIZE, mouseX, mouseY)
        val rWidth = NVGRenderer.textWidth("x", 12f, NVGRenderer.defaultFont)
        NVGRenderer.text("x", removeX + REMOVE_SIZE / 2f - rWidth / 2f, removeY - 1f, 12f,
            (if (removeHovered) Colors.MINECRAFT_RED else Colors.MINECRAFT_GRAY).rgba, NVGRenderer.defaultFont)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (click.button() != 0) return false

        if (isAreaHovered(addButtonX, inputY, addButtonWidth, INPUT_HEIGHT, mouseX, mouseY)) {
            submitNewEntry()
            return true
        }
        if (input.mouseClicked(mouseX, mouseY, click)) return true

        val list = ChatPing.alerts
        val contentHeight = list.size * ROW_HEIGHT
        if (contentHeight > listHeight && isAreaHovered(scrollbarX, listY, scrollbarWidth, listHeight, mouseX, mouseY)) {
            draggingScrollbar = true
            dragStartMouseY = mouseY
            dragStartScrollOffset = scrollOffset
            return true
        }

        if (mouseY in listY..(listY + listHeight)) {
            val index = ((mouseY - listY - scrollOffset) / ROW_HEIGHT).toInt()
            val alert = list.getOrNull(index) ?: return true
            val rowTop = listY + scrollOffset + index * ROW_HEIGHT
            val removeX = x + width - 12f - REMOVE_SIZE
            val removeY = rowTop + (ROW_HEIGHT - 2f) / 2f - REMOVE_SIZE / 2f
            when {
                isAreaHovered(removeX, removeY, REMOVE_SIZE, REMOVE_SIZE, mouseX, mouseY) -> ChatPing.removeAlert(alert)
                // The on/off box (with a little slack around it) toggles; the rest of the row opens the editor.
                mouseX < x + 16f + 13f + 4f -> { alert.enabled = !alert.enabled; ChatPing.saveAlerts() }
                else -> openEditor(alert)
            }
            return true
        }
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        input.mouseReleased()
        draggingScrollbar = false
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Int): Boolean {
        if (mouseY !in listY..(listY + listHeight)) return false
        val contentHeight = ChatPing.alerts.size * ROW_HEIGHT
        scrollOffset = (scrollOffset + amount).coerceIn((-contentHeight + listHeight).coerceAtMost(0f), 0f)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key == GLFW.GLFW_KEY_ESCAPE) return false
        val wasEnter = input.key == GLFW.GLFW_KEY_ENTER
        val handled = this.input.keyPressed(input)
        if (wasEnter) submitNewEntry()
        return handled || wasEnter
    }

    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    companion object {
        private const val INPUT_HEIGHT = 24f
        private const val ROW_HEIGHT = 26f
        private const val MIN_THUMB_HEIGHT = 20f
        private const val REMOVE_SIZE = 14f
    }
}

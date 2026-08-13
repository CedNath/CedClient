package ced.cedclient.ui.inventory.editor

import ced.cedclient.ui.inventory.InvButton
import ced.cedclient.ui.inventory.InventoryButtonManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.resources.Identifier
import net.minecraft.network.chat.Component


class EditButtonPopup(private val target: InvButton) : Popup {

    var x: Int = 0
    var y: Int = 0
    val width: Int = 240
    val height: Int = 180

    private val mc = Minecraft.getInstance()
    private val font: Font = mc.font

    private val nameField = EditBox(font, 0, 0, 170, 14, Component.empty())
    private val commandField = EditBox(font, 0, 0, 170, 14, Component.empty())
    private val widthField = EditBox(font, 0, 0, 80, 14, Component.empty())
    private val heightField = EditBox(font, 0, 0, 80, 14, Component.empty())
    private val iconField = EditBox(font, 0, 0, 170, 14, Component.empty())

    // simple style toggle: false = dark, true = light
    private var lightStyle = false

    // dragging
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private val DARK_BG = 0xFF2A2A2A.toInt()
    private val DARK_HOVER = 0xFFF0F0F0.toInt()
    private val LIGHT_BG = 0xFFF0F0F0.toInt()
    private val LIGHT_HOVER = 0xFF3A3A3A.toInt()
    private val BORDER = 0xFF000000.toInt()

    init {
        nameField.value = target.name
        commandField.value = target.command
        widthField.value = target.width.toString()
        heightField.value = target.height.toString()
        iconField.value = target.icon?.toString() ?: ""

        // guess initial style from current bg color
        lightStyle = target.bgColor == LIGHT_BG
    }

    private fun focusOnly(field: EditBox) {
        val fields = listOf(
            nameField, commandField, widthField, heightField, iconField
        )
        fields.forEach { it.setFocused(it == field) }
    }

    override fun render(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        g.fill(x, y, x + width, y + height, 0xEE1A1A1A.toInt())
        g.fill(x, y, x + width, y + 16, 0xFF3A8FFF.toInt())
        g.text(font, "Edit Inventory Button", x + 10, y + 6, 0xFFFFFFFF.toInt())

        g.text(font, "Name", x + 10, y + 24, 0xFFAAAAAA.toInt())
        g.text(font, "Command", x + 10, y + 44, 0xFFAAAAAA.toInt())
        g.text(font, "Size (W / H)", x + 10, y + 64, 0xFFAAAAAA.toInt())
        g.text(font, "Icon (RL path)", x + 10, y + 99, 0xFFAAAAAA.toInt())

        val styleText = if (lightStyle) "Style: Light" else "Style: Dark"
        val styleX = x + 10
        val styleY = y + 124
        val styleHover = mouseX in styleX..(styleX + 90) && mouseY in styleY..(styleY + 12)
        val styleColor = if (styleHover) 0x88A8FFFF.toInt() else 0x55A8FFFF.toInt()
        g.text(font, styleText, styleX, styleY, styleColor)

        nameField.x = x + 10; nameField.y = y + 30
        nameField.extractRenderState(g, mouseX, mouseY, delta)

        commandField.x = x + 10; commandField.y = y + 50
        commandField.extractRenderState(g, mouseX, mouseY, delta)

        widthField.x = x + 10; widthField.y = y + 74
        widthField.extractRenderState(g, mouseX, mouseY, delta)

        heightField.x = x + 100; heightField.y = y + 74
        heightField.extractRenderState(g, mouseX, mouseY, delta)

        iconField.x = x + 10; iconField.y = y + 109
        iconField.extractRenderState(g, mouseX, mouseY, delta)

        drawButtons(g, mouseX, mouseY)
    }

    private fun drawButtons(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        drawButton(g, "Save", x + 10, y + 145, mouseX, mouseY)
        drawButton(g, "Delete", x + 70, y + 145, mouseX, mouseY)
        drawButton(g, "Cancel", x + 130, y + 145, mouseX, mouseY)
    }

    private fun drawButton(g: GuiGraphicsExtractor, text: String, bx: Int, by: Int, mx: Int, my: Int) {
        val bw = 52
        val bh = 16
        val hovered = mx in bx..(bx + bw) && my in by..(by + bh)
        val bg = if (hovered) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt()

        g.fill(bx, by, bx + bw, by + bh, bg)
        g.text(font, text, bx + 6, by + 4, 0xFFFFFFFF.toInt())
    }

    override fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {

        if (isInside(mx, my)) {

            // drag start on top bar
            if (my in y..(y + 16)) {
                dragging = true
                dragOffsetX = mx - x
                dragOffsetY = my - y
                return true
            }

            // style toggle
            val styleX = x + 10
            val styleY = y + 124
            if (mx in styleX..(styleX + 90) && my in styleY..(styleY + 12)) {
                lightStyle = !lightStyle
                return true
            }

            // SAVE
            if (mx in (x + 10)..(x + 62) && my in (y + 145)..(y + 161)) {
                save()
                InventoryButtonManager.save()
                InventoryButtonEditorOverlay.closePopup()
                return true
            }

            // DELETE
            if (mx in (x + 70)..(x + 122) && my in (y + 145)..(y + 161)) {
                InventoryButtonManager.removeButton(target)
                InventoryButtonEditorOverlay.closePopup()
                return true
            }

            // CANCEL
            if (mx in (x + 130)..(x + 182) && my in (y + 145)..(y + 161)) {
                InventoryButtonEditorOverlay.closePopup()
                return true
            }

            // text fields
            val fields = listOf(nameField, commandField, widthField, heightField, iconField)
            for (f in fields) {
                if (f.mouseClicked(mx, my, button)) {
                    focusOnly(f)
                    return true
                }
            }

            // consume any other inside click
            return true
        }

        // outside → let overlay decide (likely close)
        return false
    }

    override fun mouseDragged(mx: Int, my: Int, button: Int, dx: Double, dy: Double): Boolean {
        if (dragging) {
            x = mx - dragOffsetX
            y = my - dragOffsetY
            return true
        }
        return false
    }


    override fun mouseReleased(mx: Int, my: Int, button: Int): Boolean {
        dragging = false
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val fields = listOf(
            nameField, commandField, widthField, heightField, iconField
        )

        if (fields.any { it.isFocused }) {
            fields.forEach { it.keyPressed(event) }
            return true
        }

        return false
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val fields = listOf(
            nameField, commandField, widthField, heightField, iconField
        )

        if (fields.any { it.isFocused }) {
            fields.forEach { it.charTyped(event) }
            return true
        }

        return false
    }

    override fun isInside(mx: Int, my: Int): Boolean {
        return mx in x..(x + width) && my in y..(y + height)
    }

    fun save() {
        target.name = nameField.value
        target.command = commandField.value
        target.width = widthField.value.toIntOrNull() ?: target.width
        target.height = heightField.value.toIntOrNull() ?: target.height

        val iconPath = iconField.value
        var icon: Identifier? = null
        var iconIsItem = false

        if (iconPath.isNotBlank()) {
            val id = Identifier.tryParse(iconPath)

            if (id != null) {
                val item = try {
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id)
                } catch (e: Exception) {
                    null
                }

                if (item != null && !item.defaultInstance.isEmpty) {
                    icon = id
                    iconIsItem = true
                } else {
                    icon = id
                    iconIsItem = false
                }
            }
        }

        target.icon = icon
        target.iconIsItem = iconIsItem

        val bgColor = if (lightStyle) LIGHT_BG else DARK_BG
        val hoverColor = if (lightStyle) LIGHT_HOVER else DARK_HOVER

        target.bgColor = bgColor
        target.bgHoverColor = hoverColor
        target.borderColor = BORDER
    }

}
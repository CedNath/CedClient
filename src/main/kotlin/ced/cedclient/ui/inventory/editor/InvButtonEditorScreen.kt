package ced.cedclient.ui.inventory.editor

import ced.cedclient.features.impl.misc.InventoryButtons
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.inventory.InvButton
import ced.cedclient.ui.inventory.InventoryButtonManager
import ced.cedclient.ui.inventory.InventoryButtonRenderer
import ced.cedclient.ui.nvg.NVGRenderer
import ced.cedclient.ui.nvg.NVGSpecialRenderer
import ced.cedclient.utils.Color.Companion.withAlpha
import ced.cedclient.utils.Colors
import ced.cedclient.utils.Debug
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import java.util.Base64
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * NEU-style inventory button editor. Replaces the old overlay + popup based
 * editor (InventoryButtonEditorOverlay's editor-mode branch, AddButtonPopup,
 * EditButtonPopup, EditorPanel) with a single self-contained Screen, the same
 * way NotEnoughUpdates' own button editor works: buttons live directly on a
 * mock inventory background and are dragged/selected in place, and a docked
 * side panel (not a popup) handles naming, commands, and icon search for
 * whichever button is currently selected.
 *
 * Opened directly via `Minecraft.getInstance().setScreen(InvButtonEditorScreen())`
 * — no need to first open the real InventoryScreen underneath it.
 */
class InvButtonEditorScreen : Screen(Component.literal("Inventory Button Editor")) {

    private val mc = Minecraft.getInstance()
    private val font: Font = mc.font

    // ───────────────────────── Inventory mock background ─────────────────────────
    private val xSize = 176
    private val ySize = 166
    private var guiLeft = 0
    private var guiTop = 0

    private val invBackground: Identifier = Identifier.parse("minecraft:textures/gui/container/inventory.png")

    // ───────────────────────── Selection / dragging ─────────────────────────
    private var selected: InvButton? = null
    private var dragging: InvButton? = null
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private val gridSize = 8

    // ───────────────────────── Side panel ─────────────────────────
    private val panelWidth = 150
    private var panelX = 0
    private var panelY = 0
    private val panelHeight = 256

    private lateinit var nameField: EditBox
    private lateinit var commandField: EditBox
    private lateinit var searchField: EditBox

    // ───────────────────────── Icon search ─────────────────────────
    private data class IconEntry(val id: Identifier, val item: Item)

    private var allIcons: List<IconEntry> = emptyList()
    private var searchResults: List<IconEntry> = emptyList()
    private val iconCols = 6
    private val iconSlot = 20
    private var iconScrollRow = 0

    // ───────────────────────── Bottom-left panel ─────────────────────────
    private var statusText = ""
    private var statusUntil = 0L

    override fun init() {
        guiLeft = (this.width - xSize) / 2
        guiTop = (this.height - ySize) / 2

        panelX = guiLeft + xSize + 14
        panelY = guiTop - 20
        if (panelY < 6) panelY = 6

        nameField = EditBox(font, 0, 0, panelWidth - 16, 14, Component.empty())
        commandField = EditBox(font, 0, 0, panelWidth - 16, 14, Component.empty())
        searchField = EditBox(font, 0, 0, panelWidth - 16, 14, Component.empty())

        nameField.x = panelX + 8; nameField.y = panelY + 30
        commandField.x = panelX + 8; commandField.y = panelY + 70
        searchField.x = panelX + 8; searchField.y = panelY + 110

        buildIconIndex()
        applySearch()

        InventoryButtonManager.ensureLoaded()
        selected = null
    }

    private fun buildIconIndex() {
        val list = mutableListOf<IconEntry>()
        for (item in BuiltInRegistries.ITEM) {
            if (item === Items.AIR) continue
            val id = BuiltInRegistries.ITEM.getKey(item) ?: continue
            list += IconEntry(id, item)
        }
        allIcons = list.sortedBy { it.id.toString() }
    }

    private fun applySearch() {
        val query = searchField.value.trim().lowercase()
        searchResults = if (query.isEmpty()) {
            allIcons
        } else {
            allIcons.filter {
                it.id.path.lowercase().contains(query) ||
                        it.id.path.replace('_', ' ').lowercase().contains(query)
            }
        }
        iconScrollRow = 0
    }

    // ───────────────────────── RENDER ─────────────────────────

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        context.fill(0, 0, this.width, this.height, 0x90101010.toInt())

        context.blit(
            RenderPipelines.GUI_TEXTURED,
            invBackground,
            guiLeft, guiTop,
            0f, 0f,
            xSize, ySize,
            256, 256
        )

        // Buttons, drawn at their real saved positions relative to the mock
        // container. This one call replaces the old per-button vanilla loop
        // AND the separate hard-line drawOutline() call -- InventoryButtonRenderer
        // now handles the selection highlight itself (as a rounded, accent-
        // colored border) since it already needs to know which button is
        // selected to draw it on top correctly. See its doc comment for why
        // it takes the whole list instead of one button at a time.
        InventoryButtonRenderer.draw(context, InventoryButtonManager.buttons, guiLeft, guiTop, mouseX, mouseY, selected)

        // Side panel + bottom bar chrome (backgrounds, headers, labels, button
        // rects) all go through NVG in a single batch here, registered before
        // the vanilla widgets below (EditBox fields, icon-grid item icons)
        // that need to draw on top of it -- the same ordering ClickGUI itself
        // relies on, registering its NVG batch ahead of the vanilla widgets
        // `super.extractRenderState()` draws afterward.
        NVGSpecialRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight(), renderContent = {
            // Every coordinate in this screen (guiLeft/guiTop, panelX/panelY, the
            // bottom-bar x0/y, etc.) is computed from this.width/this.height --
            // i.e. GuiGraphicsExtractor.guiWidth()/guiHeight() space. But NVG
            // itself draws in canvasWidth/canvasHeight space (see the doc comment
            // on NVGRenderer.canvasWidth), which only coincides with guiWidth()/
            // guiHeight() when the GUI Scale setting happens to make them equal.
            // Scaling the whole batch by canvasWidth/guiWidth() up front converts
            // every GUI-space coordinate below into the right canvas-space pixel
            // without having to touch each individual draw call -- same fix
            // ClickGUI already relies on via its own scale ratio.
            val guiScaleRatio = NVGRenderer.canvasWidth / context.guiWidth()
            NVGRenderer.scale(guiScaleRatio, guiScaleRatio)

            if (selected == null) {
                NVGRenderer.text(
                    "Left-click a button to select and drag it",
                    (guiLeft - 10).toFloat(), (guiTop - 200).toFloat(), 8f, 0xFFAAAAAA.toInt(), NVGRenderer.defaultFont
                )
                NVGRenderer.text(
                    "Right-click empty space to add a new one",
                    (guiLeft - 10).toFloat(), (guiTop - 210).toFloat(), 8f, 0xFFAAAAAA.toInt(), NVGRenderer.defaultFont
                )
            }

            renderSidePanelChrome(mouseX, mouseY)
            renderBottomBarChrome(mouseX, mouseY)
        })

        renderSidePanelWidgets(context, mouseX, mouseY, deltaTicks)
    }

    private fun renderSidePanelChrome(mouseX: Int, mouseY: Int) {
        val btn = selected

        NVGRenderer.dropShadow(panelX.toFloat(), panelY.toFloat(), panelWidth.toFloat(), panelHeight.toFloat(), 10f, 2f, 5f)
        // Same near-opaque near-black as before (0xEE1A1A1A), just through
        // Colors.gray26 + rounded corners instead of a flat vanilla fill.
        NVGRenderer.rect(panelX.toFloat(), panelY.toFloat(), panelWidth.toFloat(), panelHeight.toFloat(), Colors.gray26.withAlpha(0.933f).rgba, 5f)
        // Header used a one-off blue (0xFF3A8FFF) before; swapped for the
        // ClickGUI accent purple so the editor reads as part of the same GUI.
        NVGRenderer.drawHalfRoundedRect(panelX.toFloat(), panelY.toFloat(), panelWidth.toFloat(), 16f, ClickGUI.clickGUIColor.rgba, 5f, true)

        if (btn == null) {
            NVGRenderer.text("No button selected", panelX + 8f, panelY + 5f, 8f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
            NVGRenderer.text("Select or create a button", panelX + 8f, panelY + 30f, 8f, 0xFFAAAAAA.toInt(), NVGRenderer.defaultFont)
            NVGRenderer.text("to edit it here.", panelX + 8f, panelY + 42f, 8f, 0xFFAAAAAA.toInt(), NVGRenderer.defaultFont)
            return
        }

        NVGRenderer.text("Edit Button", panelX + 8f, panelY + 5f, 8f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        // Close (deselect) button, top-right corner of the header
        val closeX = panelX + panelWidth - 14
        val closeY = panelY + 3
        val closeHover = mouseX in closeX..(closeX + 10) && mouseY in closeY..(closeY + 10)
        NVGRenderer.text("x", closeX + 2f, closeY.toFloat(), 8f, if (closeHover) 0xFFFF5555.toInt() else Colors.WHITE.rgba, NVGRenderer.defaultFont)

        NVGRenderer.text("Name", panelX + 8f, panelY + 18f, 8f, 0xFFAAAAAA.toInt(), NVGRenderer.defaultFont)
        NVGRenderer.text("Command", panelX + 8f, panelY + 58f, 8f, 0xFFAAAAAA.toInt(), NVGRenderer.defaultFont)
        NVGRenderer.text("Icon", panelX + 8f, panelY + 98f, 8f, 0xFFAAAAAA.toInt(), NVGRenderer.defaultFont)

        // Icon grid cell backgrounds -- the item icons drawn into them are
        // vanilla-only (g.item) and are drawn on top afterward, in
        // renderSidePanelWidgets().
        val gridX = panelX + 8
        val gridY = panelY + 138
        val gridRowsVisible = 4
        val startIndex = iconScrollRow * iconCols

        for (row in 0 until gridRowsVisible) {
            for (col in 0 until iconCols) {
                val index = startIndex + row * iconCols + col
                if (index >= searchResults.size) continue
                val entry = searchResults[index]

                val ix = gridX + col * iconSlot
                val iy = gridY + row * iconSlot
                val hovered = mouseX in ix..(ix + 18) && mouseY in iy..(iy + 18)
                val isCurrentIcon = btn.iconIsItem && btn.icon == entry.id

                val bg = when {
                    isCurrentIcon -> ClickGUI.clickGUIColor.rgba
                    hovered -> 0xFF3A3A3A.toInt()
                    else -> 0xFF2A2A2A.toInt()
                }
                NVGRenderer.rect(ix.toFloat(), iy.toFloat(), 18f, 18f, bg, 3f)
            }
        }

        val maxScrollRow = max(0, ceil(searchResults.size.toDouble() / iconCols).toInt() - gridRowsVisible)
        if (maxScrollRow > 0) {
            NVGRenderer.text(
                "scroll for more (${searchResults.size} items)",
                gridX.toFloat(), (gridY + gridRowsVisible * iconSlot + 4).toFloat(), 7f, 0xFF666666.toInt(), NVGRenderer.defaultFont
            )
        }

        // Delete button
        val delX = panelX + 8
        val delY = panelY + panelHeight - 22
        val delW = panelWidth - 16
        val delHover = mouseX in delX..(delX + delW) && mouseY in delY..(delY + 16)
        NVGRenderer.rect(delX.toFloat(), delY.toFloat(), delW.toFloat(), 16f, if (delHover) 0xFF803030.toInt() else 0xFF602020.toInt(), 3f)
        NVGRenderer.text("Delete Button", delX + 8f, delY + 4f, 8f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
    }

    /** EditBox fields + icon-grid item icons: the vanilla widgets that draw on top of renderSidePanelChrome(). */
    private fun renderSidePanelWidgets(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (selected == null) return

        nameField.extractRenderState(g, mouseX, mouseY, delta)
        commandField.extractRenderState(g, mouseX, mouseY, delta)
        searchField.extractRenderState(g, mouseX, mouseY, delta)

        val gridX = panelX + 8
        val gridY = panelY + 138
        val gridRowsVisible = 4
        val startIndex = iconScrollRow * iconCols

        for (row in 0 until gridRowsVisible) {
            for (col in 0 until iconCols) {
                val index = startIndex + row * iconCols + col
                if (index >= searchResults.size) continue
                val entry = searchResults[index]

                val ix = gridX + col * iconSlot
                val iy = gridY + row * iconSlot
                g.item(ItemStack(entry.item), ix + 1, iy + 1)
            }
        }
    }

    private fun renderBottomBarChrome(mouseX: Int, mouseY: Int) {
        val x0 = 10
        var y = this.height - 78

        val snapText = "Grid Snap (S): " + if (InventoryButtons.snapEnabled) "ON" else "OFF"
        NVGRenderer.text(snapText, x0.toFloat(), y.toFloat(), 8f, if (InventoryButtons.snapEnabled) 0xFF55FF55.toInt() else 0xFFAAAAAA.toInt(), NVGRenderer.defaultFont)
        y += 16

        val copyHover = mouseX in x0..(x0 + 150) && mouseY in y..(y + 16)
        NVGRenderer.rect(x0.toFloat(), y.toFloat(), 150f, 16f, if (copyHover) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt(), 3f)
        NVGRenderer.text("Save preset to Clipboard", x0 + 6f, y + 4f, 8f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        y += 20

        val pasteHover = mouseX in x0..(x0 + 150) && mouseY in y..(y + 16)
        NVGRenderer.rect(x0.toFloat(), y.toFloat(), 150f, 16f, if (pasteHover) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt(), 3f)
        NVGRenderer.text("Load preset from Clipboard", x0 + 6f, y + 4f, 8f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        y += 20

        if (System.currentTimeMillis() < statusUntil) {
            NVGRenderer.text(statusText, x0.toFloat(), y.toFloat(), 8f, 0xFF55FF55.toInt(), NVGRenderer.defaultFont)
        }
    }

    // ───────────────────────── INPUT: MOUSE ─────────────────────────

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mx = mouseButtonEvent.x().toInt()
        val my = mouseButtonEvent.y().toInt()
        val button = mouseButtonEvent.button()

        // Side panel takes priority
        if (mx in panelX..(panelX + panelWidth) && my in panelY..(panelY + panelHeight)) {
            return handlePanelClick(mx, my, mouseButtonEvent, bl)
        }

        // Bottom-left preset bar
        if (handleBottomBarClick(mx, my)) return true

        // Existing buttons — clicking (either button) on top of one always selects
        // and grabs it, same as NEU. Only empty space distinguishes left vs right click.
        val hit = InventoryButtonManager.getButtonAt(mx, my, guiLeft, guiTop)
        if (hit != null) {
            selectButton(hit)
            dragging = hit
            dragOffsetX = mx - (guiLeft + hit.xOff)
            dragOffsetY = my - (guiTop + hit.yOff)
            return true
        }

        // Right click empty space → create + select + start dragging a new button
        if (button == 1) {
            val newBtn = InvButton(
                name = "",
                xOff = mx - guiLeft - InventoryButtons.defaultWidth / 2,
                yOff = my - guiTop - InventoryButtons.defaultHeight / 2,
                width = InventoryButtons.defaultWidth,
                height = InventoryButtons.defaultHeight,
                command = ""
            )
            InventoryButtonManager.addButton(newBtn)
            selectButton(newBtn)
            dragging = newBtn
            dragOffsetX = InventoryButtons.defaultWidth / 2
            dragOffsetY = InventoryButtons.defaultHeight / 2
            return true
        }

        // Clicked empty space → deselect
        if (button == 0) {
            selected = null
        }

        return super.mouseClicked(mouseButtonEvent, bl)
    }

    private fun selectButton(btn: InvButton) {
        selected = btn
        nameField.value = btn.name
        commandField.value = btn.command
        searchField.value = ""
        applySearch()
    }

    private fun handlePanelClick(mx: Int, my: Int, event: MouseButtonEvent, bl: Boolean): Boolean {
        val btn = selected ?: return true

        // Close (deselect)
        val closeX = panelX + panelWidth - 14
        val closeY = panelY + 3
        if (mx in closeX..(closeX + 10) && my in closeY..(closeY + 10)) {
            selected = null
            return true
        }

        if (nameField.mouseClicked(event, bl)) {
            nameField.setFocused(true); commandField.setFocused(false); searchField.setFocused(false)
            return true
        }
        if (commandField.mouseClicked(event, bl)) {
            nameField.setFocused(false); commandField.setFocused(true); searchField.setFocused(false)
            return true
        }
        if (searchField.mouseClicked(event, bl)) {
            nameField.setFocused(false); commandField.setFocused(false); searchField.setFocused(true)
            return true
        }

        // Icon grid
        val gridX = panelX + 8
        val gridY = panelY + 138
        val gridRowsVisible = 4
        if (my in gridY..(gridY + gridRowsVisible * iconSlot)) {
            val col = (mx - gridX) / iconSlot
            val row = (my - gridY) / iconSlot
            if (col in 0 until iconCols && row in 0 until gridRowsVisible) {
                val index = iconScrollRow * iconCols + row * iconCols + col
                searchResults.getOrNull(index)?.let { entry ->
                    btn.icon = entry.id
                    btn.iconIsItem = true
                    InventoryButtonManager.save()
                }
            }
            return true
        }

        // Delete
        val delX = panelX + 8
        val delY = panelY + panelHeight - 22
        val delW = panelWidth - 16
        if (mx in delX..(delX + delW) && my in delY..(delY + 16)) {
            InventoryButtonManager.removeButton(btn)
            selected = null
            return true
        }

        return true
    }

    private fun handleBottomBarClick(mx: Int, my: Int): Boolean {
        val x0 = 10
        var y = this.height - 78

        if (mx in x0..(x0 + 150) && my in y..(y + 16)) {
            InventoryButtons.snapEnabled = !InventoryButtons.snapEnabled
            return true
        }
        y += 16

        if (mx in x0..(x0 + 150) && my in y..(y + 16)) {
            exportToClipboard()
            return true
        }
        y += 20

        if (mx in x0..(x0 + 150) && my in y..(y + 16)) {
            importFromClipboard()
            return true
        }

        return false
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        dragging?.let {
            if (InventoryButtons.snapEnabled) {
                it.xOff = snap(it.xOff)
                it.yOff = snap(it.yOff)
            }
            InventoryButtonManager.save()
            dragging = null
            return true
        }
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun mouseDragged(mouseButtonEvent: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val btn = dragging ?: return super.mouseDragged(mouseButtonEvent, dragX, dragY)

        val mx = mouseButtonEvent.x().toInt()
        val my = mouseButtonEvent.y().toInt()

        btn.xOff = mx - dragOffsetX - guiLeft
        btn.yOff = my - dragOffsetY - guiTop

        if (InventoryButtons.snapEnabled) {
            btn.xOff = snap(btn.xOff)
            btn.yOff = snap(btn.yOff)
        }

        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (mouseX.toInt() in panelX..(panelX + panelWidth)) {
            val maxScrollRow = max(0, ceil(searchResults.size.toDouble() / iconCols).toInt() - 4)
            iconScrollRow = (iconScrollRow - verticalAmount.roundToInt()).coerceIn(0, maxScrollRow)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    private fun snap(v: Int): Int = (v / gridSize.toFloat()).roundToInt() * gridSize

    // ───────────────────────── INPUT: KEYBOARD ─────────────────────────

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        val fields = listOf(nameField, commandField, searchField)

        if (fields.any { it.isFocused }) {
            val handled = fields.any { it.keyPressed(keyEvent) }
            selected?.let {
                it.name = nameField.value
                it.command = commandField.value
            }
            if (searchField.isFocused) applySearch()
            InventoryButtonManager.save()
            if (handled) return true
        }

        when (keyEvent.key) {
            GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
                selected?.let {
                    InventoryButtonManager.removeButton(it)
                    selected = null
                    return true
                }
            }
            GLFW.GLFW_KEY_S -> {
                InventoryButtons.snapEnabled = !InventoryButtons.snapEnabled
                return true
            }
        }

        return super.keyPressed(keyEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        val fields = listOf(nameField, commandField, searchField)

        if (fields.any { it.isFocused }) {
            val handled = fields.any { it.charTyped(characterEvent) }
            selected?.let {
                it.name = nameField.value
                it.command = commandField.value
            }
            if (searchField.isFocused) applySearch()
            InventoryButtonManager.save()
            if (handled) return true
        }

        return super.charTyped(characterEvent)
    }

    // ───────────────────────── EXPORT / IMPORT ─────────────────────────

    private fun exportToClipboard() {
        try {
            val gson = Gson()
            val jsonList = InventoryButtonManager.buttons.map { btn ->
                mapOf(
                    "name" to btn.name,
                    "xOff" to btn.xOff,
                    "yOff" to btn.yOff,
                    "width" to btn.width,
                    "height" to btn.height,
                    "command" to btn.command,
                    "icon" to btn.icon?.toString(),
                    "iconIsItem" to btn.iconIsItem,
                    "showTextOnHover" to btn.showTextOnHover,
                    "hideBlankName" to btn.hideBlankName
                )
            }
            val encoded = Base64.getEncoder().encodeToString(gson.toJson(jsonList).toByteArray(Charsets.UTF_8))
            mc.keyboardHandler.clipboard = encoded
            setStatus("Copied to clipboard!")
        } catch (e: Exception) {
            Debug.log("InvButtonEditorScreen: export failed")
            e.printStackTrace()
            setStatus("Export failed!")
        }
    }

    private fun importFromClipboard() {
        try {
            val clipboard = mc.keyboardHandler.clipboard?.trim()
            if (clipboard.isNullOrEmpty()) {
                setStatus("Clipboard is empty!")
                return
            }

            val decoded = try {
                String(Base64.getDecoder().decode(clipboard), Charsets.UTF_8)
            } catch (e: Exception) {
                clipboard
            }

            val root = JsonParser.parseString(decoded)
            val array: JsonArray = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject && root.asJsonObject.has("buttons") -> root.asJsonObject.getAsJsonArray("buttons")
                else -> throw IllegalArgumentException("No buttons array found")
            }

            val loaded = mutableListOf<InvButton>()
            for (elem in array) {
                val obj: JsonObject = elem.asJsonObject
                val iconStr = obj.get("icon")?.takeUnless { it.isJsonNull }?.asString
                val icon = iconStr?.let { runCatching { Identifier.parse(it) }.getOrNull() }

                loaded += InvButton(
                    name = obj.get("name")?.asString ?: "",
                    xOff = obj.get("xOff")?.asInt ?: 0,
                    yOff = obj.get("yOff")?.asInt ?: 0,
                    width = obj.get("width")?.asInt ?: InventoryButtons.defaultWidth,
                    height = obj.get("height")?.asInt ?: InventoryButtons.defaultHeight,
                    command = obj.get("command")?.asString ?: "",
                    icon = icon,
                    iconIsItem = obj.get("iconIsItem")?.asBoolean ?: (icon != null),
                    showTextOnHover = obj.get("showTextOnHover")?.asBoolean ?: true,
                    hideBlankName = obj.get("hideBlankName")?.asBoolean ?: true
                )
            }

            if (loaded.isEmpty()) throw IllegalArgumentException("No valid buttons parsed")

            InventoryButtonManager.buttons.clear()
            InventoryButtonManager.buttons.addAll(loaded)
            InventoryButtonManager.save()
            selected = null
            setStatus("Imported ${loaded.size} buttons!")
        } catch (e: Exception) {
            Debug.log("InvButtonEditorScreen: import failed")
            e.printStackTrace()
            setStatus("Invalid clipboard data!")
        }
    }

    private fun setStatus(text: String) {
        statusText = text
        statusUntil = System.currentTimeMillis() + 3000
    }

    // ───────────────────────── LIFECYCLE ─────────────────────────

    override fun onClose() {
        InventoryButtonManager.save()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false
}
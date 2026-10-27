package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.Debug
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.io.File
import kotlin.math.roundToInt

/**
 * Your armor (and optionally the held item and offhand) as a HUD: icons in a row or column, in the order you pick,
 * with durability as a bar, a number or a percentage under / next to each piece.
 */
object ArmorHud : Module(
    "Armor HUD",
    Category.HUD,
    "Your armor on screen: layout, order, size, durability display, held item and offhand."
), HudElement {
    private val layout = DropdownSetting("Layout", listOf("Vertical", "Horizontal"), "Vertical", "Stack the pieces down or side by side.")
    private val order = DropdownSetting("Order", listOf("Helmet First", "Boots First"), "Helmet First", "Which piece comes first.")
    private val durability = DropdownSetting("Durability", listOf("Off", "Bar", "Number", "Percent"), "Percent", "How wear shows (only on pieces that wear down).")
    private val showHeld = BooleanSetting("Show Held Item", false, "The item in your main hand, before the armor.")
    private val showOffhand = BooleanSetting("Show Offhand", false, "The offhand item, after the armor.")
    private val hideEmpty = BooleanSetting("Hide Empty Slots", true, "Skip pieces you aren't wearing instead of leaving a gap.")
    private val background = BooleanSetting("Background", false, "A rounded card behind the icons.")
    private val spacing = NumberSetting("Spacing", 2.0, 0.0, 12.0, 1.0, "Gap between pieces.")

    private val mc get() = Minecraft.getInstance()

    private class Piece(val stack: ItemStack)

    private fun pieces(): List<Piece> {
        val p = mc.player ?: return emptyList()
        val armor = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
            .let { if (order.value == "Boots First") it.reversed() else it }
            .map { p.getItemBySlot(it) }
        val all = (if (showHeld.value) listOf(p.mainHandItem) else emptyList()) + armor +
            (if (showOffhand.value) listOf(p.offhandItem) else emptyList())
        return all.filter { !hideEmpty.value || !it.isEmpty }.map { Piece(it) }
    }

    private val sample: List<Piece> by lazy {
        listOf(Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS).mapIndexed { i, item ->
            Piece(ItemStack(item).also { it.damageValue = it.maxDamage * (i + 1) / 6 })
        }
    }

    private var lastCount = -1

    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        if (!isEnabled || mc.gui.hud.isHidden || mc.player == null) return
        val list = pieces()
        if (list.size != lastCount) { lastCount = list.size; Debug.line("ArmorHud", "showing ${list.size} pieces") }
        draw(g, list)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) = draw(g, pieces().ifEmpty { sample })

    private fun label(stack: ItemStack): String? {
        if (!stack.isDamageableItem) return null
        val left = stack.maxDamage - stack.damageValue
        return when (durability.value) {
            "Number" -> left.toString()
            "Percent" -> "${(left * 100f / stack.maxDamage).roundToInt()}%"
            else -> null
        }
    }

    private fun labelColor(stack: ItemStack): Int {
        val f = (1f - stack.damageValue / stack.maxDamage.toFloat()).coerceIn(0f, 1f)
        val r = ((1f - f) * 2f).coerceIn(0f, 1f)
        val gr = (f * 2f).coerceIn(0f, 1f)
        return 0xFF000000.toInt() or ((r * 255).toInt() shl 16) or ((gr * 255).toInt() shl 8) or 0x40
    }

    private fun draw(g: GuiGraphicsExtractor, list: List<Piece>) {
        if (list.isEmpty()) return
        val font = mc.font
        val horizontal = layout.value == "Horizontal"
        val gap = spacing.value.toInt()
        val labels = list.map { label(it.stack) }
        val labelH = if (horizontal && labels.any { it != null }) font.lineHeight + 1 else 0
        val cell = 16
        // Vertical: the label sits right of the icon; Horizontal: under it.
        val labelW = if (horizontal) 0 else (labels.maxOfOrNull { it?.let(font::width) ?: 0 } ?: 0).let { if (it > 0) it + 3 else 0 }
        val itemW = if (horizontal) maxOf(cell, labels.maxOfOrNull { it?.let(font::width) ?: 0 } ?: 0) else cell + labelW
        val itemH = cell + labelH
        val pad = if (background.value) 4 else 0
        val w = pad * 2 + if (horizontal) list.size * itemW + (list.size - 1) * gap else itemW
        val h = pad * 2 + if (horizontal) itemH else list.size * itemH + (list.size - 1) * gap
        lastWidth = w
        lastHeight = h
        val s = panelScale
        if (background.value) {
            HudCard.draw(g, panelX, panelY) { HudCard.card(w * s, h * s, s) }
            g.nextStratum()
        }
        g.pose().pushMatrix()
        g.pose().translate(panelX.toFloat(), panelY.toFloat())
        g.pose().scale(s, s)
        list.forEachIndexed { i, piece ->
            val x = pad + if (horizontal) i * (itemW + gap) + (itemW - cell) / 2 else 0
            val y = pad + if (horizontal) 0 else i * (itemH + gap)
            g.item(piece.stack, x, y)
            if (durability.value == "Bar") g.itemDecorations(font, piece.stack, x, y)
            val text = labels[i] ?: return@forEachIndexed
            val color = labelColor(piece.stack)
            if (horizontal) g.text(font, text, pad + i * (itemW + gap) + (itemW - font.width(text)) / 2, y + cell + 1, color)
            else g.text(font, text, x + cell + 3, y + (cell - font.lineHeight) / 2 + 1, color)
        }
        g.pose().popMatrix()
    }

    override val label = "Armor HUD"
    private const val DEFAULT_X = 3
    private const val DEFAULT_Y = 120
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = 1f
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = Gson()
    private val hudFile by lazy { CedPaths.hud("armor") }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = 1f }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 4f) }

    init {
        addSettings(layout, order, durability, showHeld, showOffhand, hideEmpty, background, spacing)
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 4f)
        }
    }
}

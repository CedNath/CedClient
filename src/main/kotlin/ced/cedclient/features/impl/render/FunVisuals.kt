package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.TextSetting
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth

/** China Hat: a rainbow cone on your own head. Drawn by ChinaHatLayer (added in PlayerRendererMixin); only you see it. */
object ChinaHat : Module(
    "China Hat", Category.Cosmetic,
    "A rotating rainbow cone hat on your own head. Only you see it."
) {
    private val style = DropdownSetting("Style", listOf("Rainbow", "Straw"), "Rainbow", "Straw: a woven conical straw hat with a ribbon, like the classic Asian farmer's hat.")
    private val radius = NumberSetting("Radius", 0.6, 0.3, 1.0, 0.05)
    private val height = NumberSetting("Height", 0.3, 0.1, 0.6, 0.05)
    private val speed = NumberSetting("Rainbow Speed", 0.3, 0.0, 2.0, 0.05)

    init { addSettings(style, radius, height, speed) }

    @JvmStatic fun straw(): Boolean = style.value == "Straw"
    @JvmStatic fun radius(): Float = radius.value.toFloat()
    @JvmStatic fun height(): Float = height.value.toFloat()
    @JvmStatic fun speed(): Float = speed.value.toFloat()
}

/** ADHD: a label that bounces around the screen and changes colour on every wall hit, like the DVD logo. */
object ADHD : Module(
    "ADHD", Category.Misc,
    "Bounces a label around your screen like the DVD logo."
) {
    private val text = TextSetting("Text", "CedClient", "What bounces around.", maxLength = 24)
    private val speed = NumberSetting("Speed", 60.0, 10.0, 300.0, 5.0)
    private val scale = NumberSetting("Size", 1.5, 0.5, 4.0, 0.25)

    private var x = 20f
    private var y = 20f
    private var vx = 1f
    private var vy = 1f
    private var hue = 0f
    private var lastNanos = 0L

    init {
        addSettings(text, speed, scale)
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("cedclient", "adhd")) { g, _ ->
            if (!isEnabled) { lastNanos = 0L; return@addLast }
            val font = Minecraft.getInstance().font
            val s = scale.value.toFloat()
            val label = text.value.ifBlank { "CedClient" }
            val w = (font.width(label) + 8) * s
            val h = 14 * s
            val maxX = (g.guiWidth() - w).coerceAtLeast(0f)
            val maxY = (g.guiHeight() - h).coerceAtLeast(0f)

            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
            lastNanos = now
            x += vx * speed.value.toFloat() * dt
            y += vy * speed.value.toFloat() * dt
            if (x <= 0f) { x = 0f; vx = 1f; hue += 0.17f } else if (x >= maxX) { x = maxX; vx = -1f; hue += 0.17f }
            if (y <= 0f) { y = 0f; vy = 1f; hue += 0.17f } else if (y >= maxY) { y = maxY; vy = -1f; hue += 0.17f }
            hue %= 1f

            g.pose().pushMatrix()
            g.pose().translate(x, y)
            g.pose().scale(s, s)
            g.fill(0, 0, font.width(label) + 8, 14, 0xFF000000.toInt() or (Mth.hsvToRgb(hue, 0.8f, 1f) and 0xFFFFFF))
            g.text(font, label, 4, 3, -1)
            g.pose().popMatrix()
        }
    }
}

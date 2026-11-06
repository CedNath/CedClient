package ced.cedclient.features.impl.misc

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.BooleanSetting
import com.google.gson.Gson
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * Where CedClient's own alerts ("Warp ready", "Section done!", ...) show: every module that sends them has its own small HUD
 * card (movable in the HUD editor, named "<Module> Alert") instead of a big title in the middle of the screen (a server's own
 * titles are untouched). Features call setTitle / setSubtitle / setTimes exactly like Gui's, in any order. The call is traced
 * back to the module that made it, and goes to that module's card; it is dropped when the module's "Show Title" toggle is off.
 */
object ClientTitles {
    private val USERS = setOf(
        "BeachBallHelper", "BloodCamp", "CoralotHelper", "CritterSafari", "F7Titles", "MaskTimers", "I4Helper", "LividSolver",
        "WarpCooldown", "RoomAlerts", "TrevorHelper", "NecronDrop", "SbKickTimer", "PangolinCatcher",
        "RagnarockTimer", "KeyAlerts", "SecretHighlight", "SunGecko", "VampireSlayer"
    )

    /** What each card shows in the HUD editor until a real alert has been seen: title, subtitle (§ codes give the colour). */
    private val SAMPLES = mapOf(
        "BeachBallHelper" to ("§aBall #3: 5 bounces" to "§7Avg §e4.2§7 bounces per ball"),
        "BloodCamp" to ("§cKill Mobs" to ""),
        "CoralotHelper" to ("§dCoralot spotted" to ""),
        "CritterSafari" to ("§aCritter found" to "§7Macaw nearby"),
        "F7Titles" to ("§bStorm" to "§7phase 2"),
        "MaskTimers" to ("§aBonzo's Mask ready" to ""),
        "I4Helper" to ("§aCompleted Device!" to "§77/9 hit"),
        "LividSolver" to ("§bIce Spray Livid!" to ""),
        "WarpCooldown" to ("§aWarp ready" to "§7you can start a run"),
        "RoomAlerts" to ("§aSecrets Done" to "§75/5"),
        "TrevorHelper" to ("§aTrevor is ready" to "§7new animal"),
        "NecronDrop" to ("§aAbove Platform" to ""),
        "SbKickTimer" to ("§eKick timer" to "§7ready in 5s"),
        "PangolinCatcher" to ("§aPangolin!" to ""),
        "RagnarockTimer" to ("§aRagnarock ready" to ""),
        "KeyAlerts" to ("§cBlood Key" to "§7CedNath"),
        "SecretHighlight" to ("§cSecret locked" to ""),
        "SunGecko" to ("§eSun Gecko spawned" to ""),
        "VampireSlayer" to ("§cMania!" to "§7boss is low"),
    )

    private val CODE = Regex("§.")
    private val CODE_COLORS = mapOf(
        '0' to 0x000000, '1' to 0x0000AA, '2' to 0x00AA00, '3' to 0x00AAAA, '4' to 0xAA0000, '5' to 0xAA00AA, '6' to 0xFFAA00, '7' to 0xAAAAAA,
        '8' to 0x555555, '9' to 0x5555FF, 'a' to 0x55FF55, 'b' to 0x55FFFF, 'c' to 0xFF5555, 'd' to 0xFF55FF, 'e' to 0xFFFF55, 'f' to 0xFFFFFF
    )

    /** The text without § codes, and its colour: the first style colour, else the first § colour code, else white. */
    private fun plain(c: Component): Pair<String, Int> {
        var rgb: Int? = null
        c.visit({ style, text ->
            if (rgb == null && text.isNotBlank()) style.color?.let { rgb = it.value }
            java.util.Optional.empty<Unit>()
        }, net.minecraft.network.chat.Style.EMPTY)
        val s = c.string
        if (rgb == null) rgb = Regex("§([0-9a-f])").find(s)?.let { CODE_COLORS[it.groupValues[1][0]] }
        return s.replace(CODE, "").trim() to (0xFF000000.toInt() or (rgb ?: 0xFFFFFF))
    }

    /** One module's alert card. [module] is null for the catch-all card (a caller that is no module above). */
    class Panel(private val module: Module?, private val toggle: BooleanSetting?, fileKey: String, override val label: String, private val sampleMain: String, private val sampleSub: String, private val defX: Int, private val defY: Int) : HudElement {
        override var panelX = defX
        override var panelY = defY
        override var panelScale = 1f
        override var lastWidth = 90
            private set
        override var lastHeight = 22
            private set
        private var used = false
        override val hudActive: Boolean get() = if (module != null) module.isEnabled && toggle?.value != false else used

        private var title: Component? = null
        private var subtitle: Component? = null
        private var startedAt = 0L
        private var fadeIn = 5
        private var stay = 40
        private var fadeOut = 10

        fun setTimes(fadeInTicks: Int, stayTicks: Int, fadeOutTicks: Int) { fadeIn = fadeInTicks; stay = stayTicks; fadeOut = fadeOutTicks }
        /** The last real alert, so the HUD editor shows what this card really displays once it has shown one. */
        private var lastMain: Pair<String, Int>? = null
        private var lastSub: Pair<String, Int>? = null

        fun setTitle(text: Component) { used = true; title = text; startedAt = System.currentTimeMillis(); lastMain = plain(text); lastSub = null }
        fun setSubtitle(text: Component) { subtitle = text.takeIf { it.string.isNotEmpty() }; lastSub = subtitle?.let(::plain) }

        private data class Pos(val x: Int, val y: Int, val scale: Float)
        private val gson = Gson()
        private val file by lazy { CedPaths.hud("title_$fileKey") }

        override fun save() { runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(Pos(panelX, panelY, panelScale))) } }
        override fun resetPosition() { panelX = defX; panelY = defY; panelScale = 1f }
        override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }

        override fun renderInternal(g: GuiGraphicsExtractor) {
            val main = lastMain ?: plain(Component.literal(sampleMain))
            val sub = if (lastMain != null) lastSub else sampleSub.takeIf { it.isNotEmpty() }?.let { plain(Component.literal(it)) }
            draw(g, null, main, sub)
        }

        fun render(g: GuiGraphicsExtractor) {
            if (!hudActive || Minecraft.getInstance().gui.screen() is AbstractContainerScreen<*>) return
            val shown = title ?: return
            if (System.currentTimeMillis() - startedAt > (fadeIn + stay + fadeOut) * 50L) { title = null; subtitle = null; return }
            draw(g, null, plain(shown), subtitle?.let(::plain))
        }

        /** A card with the alert text; [head] (the editor sample) is drawn small above it. */
        private fun draw(g: GuiGraphicsExtractor, head: String?, main: Pair<String, Int>, sub: Pair<String, Int>?) {
            val s = panelScale
            val size = 10f * s
            val pad = 6f * s
            val rows = listOfNotNull(head?.let { Triple(it, size * 0.8f, 0xFF9A9AA8.toInt()) }, Triple(main.first, size, main.second), sub?.let { Triple(it.first, size * 0.85f, it.second) })
            val textW = rows.maxOf { HudCard.width(it.first, it.second) }
            val width = HudCard.steady(label, textW + pad * 2, s)
            val height = pad * 2 + rows.sumOf { it.second.toDouble() }.toFloat() + (rows.size - 1) * 3f * s
            lastWidth = (width / s).toInt()
            lastHeight = (height / s).toInt()
            HudCard.draw(g, panelX, panelY) {
                HudCard.card(width, height, s) // the card is the HUD editor's BG toggle (off until turned on)
                var y = pad
                for ((text, sz, color) in rows) { HudCard.text(text, (width - HudCard.width(text, sz)) / 2f, y, sz, color); y += sz + 3f * s }
            }
        }

        init {
            HudCard.offByDefault(label)
            runCatching { gson.fromJson(file.readText(), Pos::class.java) }.getOrNull()?.let { panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f) }
            HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("cedclient", "title_$fileKey")) { g, _ -> render(g) }
        }
    }

    private class Entry(val panel: Panel, val toggle: BooleanSetting)
    private val byClass = LinkedHashMap<String, Entry>()
    private val other = Panel(null, null, "other", "Other Alerts", "§aExample alert", "§7with a subtitle", 330, 20)

    /** Every alert card, for the HUD editor and "Reset Everything". */
    val panels: List<HudElement> get() = byClass.values.map { it.panel } + other

    /** ModuleManager.register: gives a module that sends titles its "Show Title" toggle and its own HUD card. */
    @JvmStatic
    fun attach(module: Module) {
        if (module.javaClass.simpleName !in USERS) return
        val toggle = BooleanSetting("Show Title", true, "Show this module's alerts in its own HUD card (move it in the HUD editor) instead of a title in the middle of the screen.")
        module.addSettings(toggle)
        // Two columns, staggered, so the cards do not start on top of each other.
        val i = byClass.size
        val panel = Panel(module, toggle, module.javaClass.simpleName.lowercase(), "${module.name} Alert", SAMPLES[module.javaClass.simpleName]?.first ?: "§a${module.name}", SAMPLES[module.javaClass.simpleName]?.second ?: "§7Example alert", 330 + (i / 10) * 170, 40 + (i % 10) * 34)
        byClass[module.javaClass.name] = Entry(panel, toggle)
    }

    /** The card of the module whose code is calling; null when that module's Show Title is off. */
    private fun target(): Panel? {
        val frames = StackWalker.getInstance().walk { s -> s.map { it.className }.toList() }
        for (c in frames) for ((name, e) in byClass) if (c == name || c.startsWith("$name$")) return e.panel.takeIf { e.toggle.value }
        return other
    }

    @JvmStatic fun setTimes(fadeInTicks: Int, stayTicks: Int, fadeOutTicks: Int) { target()?.setTimes(fadeInTicks, stayTicks, fadeOutTicks) }
    @JvmStatic fun setTitle(text: Component) { target()?.setTitle(text) }
    @JvmStatic fun setSubtitle(text: Component) { target()?.setSubtitle(text) }
}

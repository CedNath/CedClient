package ced.cedclient.features.impl.render

import ced.cedclient.features.impl.funqol.CritterSafari
import ced.cedclient.features.impl.funqol.DungeonSplits
import ced.cedclient.features.impl.misc.ChatPing
import ced.cedclient.features.impl.misc.DailyReset
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Anything that can be dragged/resized from MasterHudEditScreen. Implemented
 * by EntityESPHud and TimeHud; anything new that wants to show up in the
 * shared editor just implements this and gets added to
 * MasterHudEditScreen.elements.
 */
interface HudElement {
    /** Shown above the panel in the editor so multiple panels aren't ambiguous. */
    val label: String

    var panelX: Int
    var panelY: Int
    var panelScale: Float

    /** Logical (unscaled) size from the last render — used for hit-testing. */
    val lastWidth: Int
    val lastHeight: Int

    /** Draws the panel content only, ignoring the module's own enabled/disabled — used for the live editor preview. */
    fun renderInternal(g: GuiGraphicsExtractor)

    fun adjustScale(delta: Float)
    fun save()

    /** Back to the default spot and size (Client Settings' "Reset Everything"). */
    fun resetPosition()

    /**
     * Pulls the panel back inside a [screenW] x [screenH] GUI. Defaults are placed for a 1280x720 GUI, so on a
     * smaller screen the right / bottom ones end up hugging that edge instead of off-screen.
     */
    fun keepOnScreen(screenW: Int, screenH: Int) {
        if (lastWidth <= 0 || lastHeight <= 0) return
        panelX = panelX.coerceIn(0, maxOf(0, screenW - (lastWidth * panelScale).toInt()))
        panelY = panelY.coerceIn(0, maxOf(0, screenH - (lastHeight * panelScale).toInt()))
    }

    companion object {
        // Positions are GUI pixels, so a layout made on a 1080p GUI put right / bottom panels mid-screen on 2K. The
        // layout from the HUD editor is kept with the GUI size it was made at ("home"); any other size gets positions
        // worked out from it (each panel keeps its nearest edge), so switching back and forth never drifts.
        private val layoutFile = ced.cedclient.config.CedPaths.file("hud-layout.json")
        private val home = HashMap<String, IntArray>()
        private var homeW = -1
        private var homeH = -1
        private var curW = -1
        private var curH = -1

        fun followScreenSize(screenW: Int, screenH: Int) {
            if (screenW == curW && screenH == curH) return
            curW = screenW
            curH = screenH
            if (homeW < 0) loadHome()
            all.forEach { e ->
                val p = home[e.label] ?: return@forEach
                e.panelX = reanchor(p[0], (e.lastWidth * e.panelScale).toInt(), homeW, screenW)
                e.panelY = reanchor(p[1], (e.lastHeight * e.panelScale).toInt(), homeH, screenH)
            }
        }

        /** The HUD editor (or a reset) saved: what's on screen now is the layout to keep, at this GUI size. */
        fun layoutEdited() {
            if (curW < 0) return
            homeW = curW
            homeH = curH
            home.clear()
            all.forEach { home[it.label] = intArrayOf(it.panelX, it.panelY) }
            val json = com.google.gson.JsonObject().apply {
                addProperty("w", homeW)
                addProperty("h", homeH)
                add("panels", com.google.gson.JsonObject().also { panels ->
                    home.forEach { (label, p) -> panels.add(label, com.google.gson.JsonArray().apply { add(p[0]); add(p[1]) }) }
                })
            }
            runCatching { layoutFile.writeText(json.toString()) }
        }

        private fun loadHome() {
            val json = runCatching { com.google.gson.JsonParser.parseString(layoutFile.readText()).asJsonObject }.getOrNull()
            if (json == null) {
                // First run: the positions the panels loaded with are the layout, at the current size.
                layoutEdited()
                return
            }
            homeW = json["w"].asInt
            homeH = json["h"].asInt
            json["panels"].asJsonObject.entrySet().forEach { (label, p) ->
                home[label] = intArrayOf(p.asJsonArray[0].asInt, p.asJsonArray[1].asInt)
            }
        }

        /** New position along one axis: keep the gap to the nearest edge, or the offset from the centre. */
        fun reanchor(pos: Int, size: Int, oldScreen: Int, newScreen: Int): Int {
            val center = pos + size / 2f
            return when {
                center < oldScreen / 3f -> pos
                center > oldScreen * 2 / 3f -> newScreen - (oldScreen - pos)
                else -> pos + (newScreen - oldScreen) / 2
            }
        }

        init {
            // left stays, right keeps its right gap, centre stays centred
            check(reanchor(10, 50, 480, 640) == 10)
            check(reanchor(400, 50, 480, 640) == 560)
            check(reanchor(215, 50, 480, 640) == 295)
        }

        /** Every HUD panel, for MasterHudEditScreen and "Reset Everything". */
//#if CHEAT
        val all: List<HudElement> get() = listOf(TimeHud, ChatChannelHud, DailyReset, DungeonSplits, ChatPing, ActionBarHud.Notices, ActionBarHud.ItemName, CritterSafari.ProgressHud, CritterSafari.MissingHud, CritterSafari.TradesHud, ced.cedclient.features.impl.funqol.VampireSlayer, ced.cedclient.features.impl.funqol.SunGecko, ced.cedclient.features.impl.funqol.DungeonMap) + (if (ced.cedclient.utils.DevAccess.isFriend) listOf(EntityESPHud, ced.cedclient.features.impl.funqol.FarmHelper) else emptyList())
//#else
//$        val all: List<HudElement> get() = listOf(TimeHud, ChatChannelHud, DailyReset, DungeonSplits, ChatPing, ActionBarHud.Notices, ActionBarHud.ItemName, CritterSafari.ProgressHud, CritterSafari.MissingHud, CritterSafari.TradesHud, ced.cedclient.features.impl.funqol.VampireSlayer, ced.cedclient.features.impl.funqol.SunGecko, ced.cedclient.features.impl.funqol.DungeonMap)
//#endif
    }
}

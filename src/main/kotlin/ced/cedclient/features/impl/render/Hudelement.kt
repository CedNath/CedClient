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
    /** False while the panel's owner is off: it is then left out of the HUD editor. */
    val hudActive: Boolean get() = true

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
        private val layoutFile = ced.cedclient.config.CedPaths.hud("layout")
        private val home = HashMap<String, IntArray>()
        private var homeW = -1
        private var homeH = -1
        private var curW = -1
        private var curH = -1

        /**
         * Every frame, before the panels draw: puts each panel where the saved layout says, pinned to its nearest edge
         * (left / right / top / bottom keep their gap, a middle panel keeps its centre). Worked out from the layout each
         * time, never from last frame, so a panel that grows (another font, a longer number, a different GUI size) grows
         * away from its edge and goes back to the same spot when it shrinks. keepOnScreen only clamps what is drawn.
         * While the HUD editor is open the panels' live positions are what is being edited, so nothing is moved.
         */
        fun followScreenSize(screenW: Int, screenH: Int) {
            curW = screenW
            curH = screenH
            if (homeW < 0) loadHome()
            val screen = net.minecraft.client.Minecraft.getInstance().gui.screen()
            if (screen is MasterHudEditScreen || screen is HudEditScreen) return
            all.forEach { e ->
                val p = home[e.label] ?: return@forEach
                val w = (e.lastWidth * e.panelScale).toInt()
                val h = (e.lastHeight * e.panelScale).toInt()
                // Layouts saved before sizes were kept: the first size seen stands in for it.
                if (p[2] <= 0 && w > 0) p[2] = w
                if (p[3] <= 0 && h > 0) p[3] = h
                e.panelX = reanchor(p[0], p[2], w, homeW, screenW)
                e.panelY = reanchor(p[1], p[3], h, homeH, screenH)
            }
        }

        /** The HUD editor (or a reset) saved: what's on screen now is the layout to keep, at this GUI size. */
        fun layoutEdited() {
            if (curW < 0) return
            homeW = curW
            homeH = curH
            home.clear()
            all.forEach { home[it.label] = intArrayOf(it.panelX, it.panelY, (it.lastWidth * it.panelScale).toInt(), (it.lastHeight * it.panelScale).toInt()) }
            val json = com.google.gson.JsonObject().apply {
                addProperty("w", homeW)
                addProperty("h", homeH)
                add("panels", com.google.gson.JsonObject().also { panels ->
                    home.forEach { (label, p) -> panels.add(label, com.google.gson.JsonArray().apply { p.forEach { add(it) } }) }
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
                val a = p.asJsonArray
                // [x, y] in older layout files, [x, y, width, height] now.
                home[label] = intArrayOf(a[0].asInt, a[1].asInt, if (a.size() > 2) a[2].asInt else 0, if (a.size() > 3) a[3].asInt else 0)
            }
        }

        /**
         * Position along one axis of a panel saved at [pos] with size [savedSize] on an [oldScreen]-wide GUI, now [size]
         * big on a [newScreen]-wide GUI: the near edge keeps its gap (far edge for a right / bottom panel), a middle panel
         * keeps its centre offset. [savedSize] 0 = unknown, treated as [size].
         */
        fun reanchor(pos: Int, savedSize: Int, size: Int, oldScreen: Int, newScreen: Int): Int {
            val s = if (savedSize > 0) savedSize else size
            val center = pos + s / 2f
            return when {
                center < oldScreen / 3f -> pos
                center > oldScreen * 2 / 3f -> newScreen - (oldScreen - pos - s) - size
                else -> Math.round(center + (newScreen - oldScreen) / 2f - size / 2f)
            }
        }

        init {
            // left stays, right keeps its right gap, centre stays centred
            check(reanchor(10, 50, 50, 480, 640) == 10)
            check(reanchor(400, 50, 50, 480, 640) == 560)
            check(reanchor(215, 50, 50, 480, 640) == 295)
            // same screen, panel grew 50 -> 80 (wider font): left edge, right edge and centre each stay put
            check(reanchor(10, 50, 80, 480, 480) == 10)
            check(reanchor(400, 50, 80, 480, 480) == 370)
            check(reanchor(215, 50, 80, 480, 480) == 200)
            // and shrinking back returns to the saved spot
            check(reanchor(400, 50, 50, 480, 480) == 400)
        }

        /** Every HUD panel, for MasterHudEditScreen and "Reset Everything". */
//#if CHEAT
        val all: List<HudElement> get() = listOf(TimeHud, EventCountdown, ArmorHud, ChatChannelHud, DailyReset, DungeonSplits, DungeonSplits.CurrentSplit, ChatPing, ActionBarHud.Notices, ActionBarHud.ItemName, CritterSafari.ProgressHud, CritterSafari.MissingHud, CritterSafari.TradesHud, ced.cedclient.features.impl.funqol.RunProfit, F4Features, ced.cedclient.features.impl.funqol.DeployableTimers, ced.cedclient.features.impl.funqol.RagnarockTimer, ced.cedclient.features.impl.funqol.VampireSlayer, ced.cedclient.features.impl.funqol.SunGecko, ced.cedclient.features.impl.funqol.DungeonMap, ced.cedclient.features.impl.funqol.ScoreCalculator, ced.cedclient.features.impl.funqol.RoomTimes, ced.cedclient.features.impl.funqol.SecretHud, ced.cedclient.features.impl.misc.Spotify, ced.cedclient.features.impl.funqol.LeapCounter, ced.cedclient.features.impl.funqol.PingFpsHud, ced.cedclient.features.impl.funqol.SbKickTimer, ced.cedclient.features.impl.funqol.PetDisplay, ced.cedclient.features.impl.misc.HealthPanel, ced.cedclient.features.impl.misc.ManaPanel, ced.cedclient.features.impl.misc.VitalityPanel, ced.cedclient.features.impl.misc.OverflowPanel, ced.cedclient.features.impl.misc.DefencePanel, ced.cedclient.features.impl.misc.SkillDisplay, ced.cedclient.features.impl.funqol.MaskTimers, ced.cedclient.features.impl.funqol.BlessingDisplay, ced.cedclient.features.impl.funqol.WarpCooldown, ced.cedclient.features.impl.funqol.MaxorPanel, ced.cedclient.features.impl.funqol.GoldorPanel, ced.cedclient.features.impl.funqol.StormPyPanel, ced.cedclient.features.impl.funqol.StormPadPanel, ced.cedclient.features.impl.funqol.SpringBoots, ced.cedclient.features.impl.funqol.WitherDragons) + (if (ced.cedclient.utils.DevAccess.isFriend) listOf(EntityESPHud, ced.cedclient.features.impl.funqol.FarmHelper) else emptyList()) + ced.cedclient.features.impl.misc.ClientTitles.panels + ced.cedclient.features.impl.funqol.BlazeSlayerMacro.StatusHud
//#else
//$        val all: List<HudElement> get() = listOf(TimeHud, EventCountdown, ArmorHud, ChatChannelHud, DailyReset, DungeonSplits, DungeonSplits.CurrentSplit, ChatPing, ActionBarHud.Notices, ActionBarHud.ItemName, CritterSafari.ProgressHud, CritterSafari.MissingHud, CritterSafari.TradesHud, ced.cedclient.features.impl.funqol.RunProfit, ced.cedclient.features.impl.funqol.DeployableTimers, ced.cedclient.features.impl.funqol.RagnarockTimer, ced.cedclient.features.impl.funqol.VampireSlayer, ced.cedclient.features.impl.funqol.SunGecko, ced.cedclient.features.impl.funqol.DungeonMap, ced.cedclient.features.impl.funqol.ScoreCalculator, ced.cedclient.features.impl.funqol.RoomTimes, ced.cedclient.features.impl.funqol.SecretHud, ced.cedclient.features.impl.misc.Spotify, ced.cedclient.features.impl.funqol.LeapCounter, ced.cedclient.features.impl.funqol.PingFpsHud, ced.cedclient.features.impl.funqol.SbKickTimer, ced.cedclient.features.impl.funqol.PetDisplay, ced.cedclient.features.impl.misc.HealthPanel, ced.cedclient.features.impl.misc.ManaPanel, ced.cedclient.features.impl.misc.VitalityPanel, ced.cedclient.features.impl.misc.OverflowPanel, ced.cedclient.features.impl.misc.DefencePanel, ced.cedclient.features.impl.misc.SkillDisplay, ced.cedclient.features.impl.funqol.MaskTimers, ced.cedclient.features.impl.funqol.BlessingDisplay, ced.cedclient.features.impl.funqol.WarpCooldown, ced.cedclient.features.impl.funqol.MaxorPanel, ced.cedclient.features.impl.funqol.GoldorPanel, ced.cedclient.features.impl.funqol.StormPyPanel, ced.cedclient.features.impl.funqol.StormPadPanel) + ced.cedclient.features.impl.misc.ClientTitles.panels
//#endif
    }
}

//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.render.bufferSource
import ced.cedclient.render.draw
import ced.cedclient.render.polyline
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.Box3D
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.render.LineBox
import ced.cedclient.render.WorldText
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonScanner
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.phys.AABB

/**
 * The Blaze puzzle: the blazes have to be killed from lowest to highest health (the other way round in "Lower Blaze"). The
 * health comes from the name tag above each blaze, so the blazes are sorted by it and the first few are boxed with a line
 * between neighbours. Unique: boxes are numbered by kill order, a "Blaze Done!" party message goes out when the last one dies,
 * and the number of blazes left is shown above the first one.
 */
object BlazeSolver : Module(
    "Blaze Solver",
    Category.Dungeons,
    "Blaze puzzle: boxes the next blazes to kill, in order, with a line between them."
) {
    private val count = NumberSetting("Blazes To Highlight", 3.0, 1.0, 10.0, 1.0, "How many of the next blazes get a box.")
    private val first = ColorSetting("First Blaze", Color(0, 255, 0), "The blaze to kill now.")
    private val second = ColorSetting("Second Blaze", Color(255, 255, 0), "The next one.")
    private val other = ColorSetting("Other Blazes", Color(255, 0, 0), "The rest of the highlighted ones.")
    private val line = ColorSetting("Line Color", Color(255, 255, 255), "Line between blazes.")
    private val done = BooleanSetting("Blaze Done Message", true, "A party message when the puzzle is finished.")

    private val HP = Regex("""^\[Lv\d+].+Blaze [\d,]+/([\d,]+)❤$""")
    private const val START = -185

    private val blazes = ArrayList<Blaze>()
    private var reversed = false
    private var inRoom = false
    private var lastCount = 10
    private var world: Any? = null

    private fun roomName(): String? {
        val p = Minecraft.getInstance().player ?: return null
        val i = Math.floorDiv(p.x.toInt() - START + 16, 32)
        val j = Math.floorDiv(p.z.toInt() - START + 16, 32)
        return DungeonScanner.room(i, j)?.name
    }

    init {
        addSettings(count, first, second, other, line, done)
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.level !== world) { world = mc.level; blazes.clear(); inRoom = false; lastCount = 10 }
            val level = mc.level ?: return@register
            if (!isEnabled || !DungeonState.inDungeon) { blazes.clear(); return@register }
            val name = roomName()
            inRoom = name?.contains("Blaze") == true
            if (!inRoom) { blazes.clear(); return@register }
            reversed = name.equals("Lower Blaze", ignoreCase = true)
            val health = HashMap<Int, Int>()
            val found = ArrayList<Blaze>()
            for (stand in level.entitiesForRendering().filterIsInstance<ArmorStand>()) {
                val label = stand.customName?.string ?: continue
                val max = HP.find(label)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: continue
                val blaze = level.getEntitiesOfClass(Blaze::class.java, stand.boundingBox.expandTowards(0.0, -2.0, 0.0)).firstOrNull() ?: continue
                if (blaze in found) continue
                health[blaze.id] = max
                found += blaze
            }
            found.sortBy { health[it.id] }
            if (reversed) found.reverse()
            blazes.clear(); blazes.addAll(found)
            if (found.isEmpty() && lastCount == 1 && done.value) mc.player?.connection?.sendCommand("pc Blaze Done!")
            lastCount = found.size
        }
        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!isEnabled || !inRoom || blazes.isEmpty()) return@CollectSubmits
            val cam = context.gameRenderer().mainCamera().position()
            val n = count.value.toInt().coerceAtMost(blazes.size)
            val boxes = ArrayList<Pair<AABB, Color>>()
            for (i in 0 until n) boxes += Box3D.smooth(blazes[i]).inflate(0.05) to (if (i == 0) first.value else if (i == 1) second.value else other.value)
            Box3D.draw(context, boxes)
            val bufferSource = context.bufferSource()
            val buf = bufferSource.getBuffer(CustomRenderType.LINES_ESP)
            val c = line.value
            for (i in 1 until n) {
                val a = blazes[i - 1].position().add(0.0, blazes[i - 1].bbHeight / 2.0, 0.0).subtract(cam)
                val b = blazes[i].position().add(0.0, blazes[i].bbHeight / 2.0, 0.0).subtract(cam)
                LineBox.polyline(context.poseStack(), buf, listOf(a, b), c.redFloat, c.greenFloat, c.blueFloat, 1f)
            }
            bufferSource.endBatch()
            for (i in 0 until n) WorldText.draw(context, blazes[i].position().add(0.0, blazes[i].bbHeight + 0.6, 0.0), Component.literal(if (i == 0) "#1  (${blazes.size} left)" else "#${i + 1}"))
        })
    }
}
//#endif

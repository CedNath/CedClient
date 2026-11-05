//#if CHEAT
package ced.cedclient.pathing

import ced.cedclient.config.CedPaths
import ced.cedclient.utils.skyblockId
import com.google.gson.Gson
import com.google.gson.JsonObject
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.io.File

/**
 * Records how you walk, to learn from it: `/cedclient path record start [name]`, walk, `/cedclient path record stop`.
 * One JSON line per tick in config/cedclient/pathrecords/<name>.jsonl: position, look, keys held, whether you are on
 * the ground / sneaking / sprinting, the held item, and the blocks at your feet and under them (with slab / stair
 * state), so a walk can be compared with what the planner would have done. A jump of more than 3 blocks between two
 * ticks is flagged as a teleport.
 */
object PathRecorder {
    private val mc = Minecraft.getInstance()
    private val gson = Gson()
    private val lines = ArrayList<String>()
    private var name = ""
    private var tick = 0
    private var last: Vec3? = null
    private const val MAX_TICKS = 36000 // 30 minutes

    val active: Boolean get() = name.isNotEmpty()
    val samples: Int get() = tick

    init { ClientTickEvents.END_CLIENT_TICK.register { sample() } }

    fun start(label: String?) {
        name = label?.takeIf { it.isNotBlank() }?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: "walk-${System.currentTimeMillis() / 1000}"
        lines.clear(); tick = 0; last = null
        val p = mc.player
        line(JsonObject().apply {
            addProperty("type", "header")
            addProperty("name", name)
            if (p != null) { addProperty("x", p.x); addProperty("y", p.y); addProperty("z", p.z) }
        })
    }

    /** A note in the recording, e.g. "reached the stairs". */
    fun mark(text: String) {
        if (active) line(JsonObject().apply { addProperty("type", "mark"); addProperty("t", tick); addProperty("text", text) })
    }

    /** Ends the recording and writes it; the file, or null if nothing was recording. */
    fun stop(): File? {
        if (!active) return null
        val file = CedPaths.dir.resolve("pathrecords").also { it.mkdirs() }.resolve("$name.jsonl")
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        name = ""; lines.clear()
        return file
    }

    private fun line(o: JsonObject) { lines += gson.toJson(o) }

    private fun round(v: Double) = Math.round(v * 1000.0) / 1000.0

    private fun sample() {
        if (!active) return
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (tick >= MAX_TICKS) { mark("auto-stopped at the time limit"); stop(); return }
        val o = mc.options
        val feet = BlockPos.containing(p.x, p.y + 0.01, p.z)
        val pos = p.position()
        val prev = last
        line(JsonObject().apply {
            addProperty("t", tick)
            addProperty("x", round(p.x)); addProperty("y", round(p.y)); addProperty("z", round(p.z))
            addProperty("yaw", round(p.yRot.toDouble())); addProperty("pitch", round(p.xRot.toDouble()))
            addProperty("ground", p.onGround()); addProperty("sneak", p.isCrouching); addProperty("sprint", p.isSprinting)
            addProperty("keys", (if (o.keyUp.isDown) "W" else "") + (if (o.keyLeft.isDown) "A" else "") + (if (o.keyDown.isDown) "S" else "") +
                (if (o.keyRight.isDown) "D" else "") + (if (o.keyJump.isDown) "J" else "") + (if (o.keyUse.isDown) "U" else ""))
            addProperty("item", p.mainHandItem.skyblockId)
            addProperty("feet", level.getBlockState(feet).toString().removePrefix("Block{minecraft:"))
            addProperty("below", level.getBlockState(feet.below()).toString().removePrefix("Block{minecraft:"))
            if (prev != null && pos.distanceTo(prev) > 3.0) addProperty("teleport", true)
        })
        last = pos
        tick++
    }
}
//#endif

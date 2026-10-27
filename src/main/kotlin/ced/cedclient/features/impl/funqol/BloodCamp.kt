//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.impl.render.Box3D
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.render.LineBox
import ced.cedclient.render.WorldText
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import ced.cedclient.utils.getSkullTexture
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/**
 * The blood room: the blood mobs fly in on straight lines from where they spawn. While the Watcher is near, each mob's flight
 * line gives its landing spot (16.1 blocks for the first wave, 11.9 after), a box is drawn there with a countdown, and the
 * "Move" HUD counts the ticks until the mobs can be killed after the blood door opens.
 * Unique: a box turns orange for the last second, the label also shows how many mobs are tracked, and the kill title and move
 * counter can be turned off separately.
 */
object BloodCamp : RowHud("Blood Camp", "Blood room: where the blood mobs land, with a countdown, and a move timer.", "blood_camp", 3, 100) {
    private val helper = BooleanSetting("Blood Camp Helper", true, "Landing box, line and countdown for each blood mob.")
    private val decimals = NumberSetting("Decimal Places", 1.0, 0.0, 2.0, 1.0, "Decimals of the countdown.").withDependency { helper.value }
    private val timerColor = ColorSetting("Timer Color", Color(255, 255, 255), "Countdown text.")
    private val boxColor = ColorSetting("Box Color", Color(255, 0, 255), "Landing box.")
    private val soonColor = ColorSetting("Last Second Color", Color(255, 170, 0), "The box in the last second.")
    private val lineColor = ColorSetting("Line Color", Color(0, 255, 255), "Line from the mob to its landing spot.")
    private val moveTimer = BooleanSetting("Move Timer", true, "A counter until the blood mobs are ready to be killed.")
    private val killTitle = BooleanSetting("Kill Title", false, "A title when the blood mobs are ready (not always exact).")

    private class Mob(var start: Vec3, val startedTick: Long, val first: Boolean) { var end: Vec3? = null }

    private val mobs = HashMap<ArmorStand, Mob>()
    private var watcher: Zombie? = null
    private var firstSpawns = true
    private var tick = 0L
    private var moveEnd = 0L
    private var killAt = 0L
    private var world: Any? = null

    private val skulls: Pair<Set<String>, Set<String>> by lazy {
        runCatching {
            val text = BloodCamp::class.java.getResourceAsStream("/assets/cedclient/blood_camp_skulls.json")!!.bufferedReader().readText()
            val o = com.google.gson.JsonParser.parseString(text).asJsonObject
            fun set(n: String) = o.getAsJsonArray(n).map { it.asString }.toSet()
            set("watcher") to set("mobs")
        }.getOrDefault(emptySet<String>() to emptySet())
    }

    private val MOVE_TICKS = intArrayOf(480, 600, 500, 500, 500, 470, 470, 420)

    private fun num(v: Double, places: Int) = if (places > 0) "%.${places}f".format(java.util.Locale.ROOT, v) else ceil(v).toInt().toString()

    override fun rows(): List<Triple<String, String, Int>>? {
        if (!isEnabled || !moveTimer.value || moveEnd <= tick) return null
        return listOf(Triple("Move", num((moveEnd - tick) / 20.0, 2), 0xFFFF5555.toInt()))
    }

    override fun preview() = listOf(Triple("Move", "21.00", 0xFFFF5555.toInt()))

    init {
        addSettings(helper, decimals, timerColor, boxColor, soonColor, lineColor, moveTimer, killTitle)
        loadPosition()

        on<ChatMessageEvent> {
            if (!isEnabled || DungeonState.inBoss) return@on
            when (it.unformattedText.trim()) {
                "[BOSS] The Watcher: Let's see how you can handle this." -> firstSpawns = false
                "The BLOOD DOOR has been opened!" -> {
                    val floor = DungeonState.floor?.drop(1)?.toIntOrNull() ?: 0
                    val ticks = MOVE_TICKS.getOrElse(floor) { 480 }
                    if (moveTimer.value) moveEnd = tick + ticks
                    if (killTitle.value) killAt = tick + ticks
                }
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            tick++
            if (mc.level !== world) { world = mc.level; mobs.clear(); watcher = null; firstSpawns = true; moveEnd = 0; killAt = 0 }
            if (killAt != 0L && tick >= killAt) { killAt = 0; Alerts.title("Blood Camp", "Kill Mobs", "", 0xFF5555, true, 1.3f) }
            val level = mc.level ?: return@register
            if (!isEnabled || !helper.value || !DungeonState.inDungeon || DungeonState.inBoss) return@register
            val (watcherTex, mobTex) = skulls
            val w = watcher?.takeIf { !it.isRemoved } ?: level.entitiesForRendering().filterIsInstance<Zombie>()
                .firstOrNull { it.getItemBySlot(EquipmentSlot.HEAD).getSkullTexture() in watcherTex }.also { watcher = it }
            if (w == null) return@register
            mobs.keys.removeIf { it.isRemoved }
            for (stand in level.entitiesForRendering().filterIsInstance<ArmorStand>()) {
                if (stand.distanceToSqr(w) > 400) continue
                if (stand.getItemBySlot(EquipmentSlot.HEAD).getSkullTexture() !in mobTex) continue
                val pos = stand.position()
                val mob = mobs.getOrPut(stand) { Mob(pos, tick, firstSpawns) }
                val flight = pos.subtract(mob.start)
                if (flight.lengthSqr() < 1.0E-4) continue
                mob.end = mob.start.add(flight.normalize().scale(if (mob.first) 16.1 else 11.9))
            }
        }

        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!isEnabled || !helper.value || DungeonState.inBoss || mobs.isEmpty()) return@AfterSolidFeatures
            val camera = context.gameRenderer().mainCamera
            val cam = camera.position()
            val boxes = ArrayList<Pair<AABB, Color>>()
            val labels = ArrayList<Pair<Vec3, String>>()
            val lines = ArrayList<List<Vec3>>()
            for ((stand, mob) in mobs) {
                val end = mob.end ?: continue
                val left = ((if (mob.first) 40 else 0) + 38 - (tick - mob.startedTick)) / 20.0
                boxes += AABB(end.x - 0.5, end.y - 0.5, end.z - 0.5, end.x + 0.5, end.y + 0.5, end.z + 0.5) to (if (left < 1.0) soonColor.value else boxColor.value)
                labels += end.add(0.0, 2.0, 0.0) to (num(left.coerceAtLeast(0.0), decimals.value.toInt()) + "s")
                lines += listOf(stand.position().add(0.0, 2.0, 0.0).subtract(cam), end.add(0.0, 2.0, 0.0).subtract(cam))
            }
            Box3D.draw(context, boxes)
            val bufferSource = context.bufferSource()
            val buf = bufferSource.getBuffer(CustomRenderType.LINES_ESP)
            val lc = lineColor.value
            for (l in lines) LineBox.polyline(context.poseStack(), buf, l, lc.redFloat, lc.greenFloat, lc.blueFloat, 1f)
            bufferSource.endBatch()
            val tc = timerColor.value.rgba and 0xFFFFFF
            for ((at, text) in labels) WorldText.draw(context, at, Component.literal("$text (${mobs.size})").withColor(tc))
        })
    }
}
//#endif

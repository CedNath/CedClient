//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.farmhelper.util.KeyBindUtils
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.utils.Debug
import ced.cedclient.utils.HumanLook
import ced.cedclient.utils.getWornSkullTexture
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Year of the Seal beach ball: marks where the ball will come down with a bounce counter, walks you under it (crouched,
 * standing up to run only when it is far), and once the combo hits [missAt] walks away so the ball is dropped on purpose.
 * Keeps balls used / average bounces per ball for the session and pops it up when a ball ends.
 *
 * The prediction follows SkyHanni's beach ball helper: one long position history per ball that restarts its arc whenever
 * the ball passes close to you (that height is the bounce height), three small curve fits averaged together, a new
 * prediction only every few ticks, and predictions that don't end near the bounce height thrown away.
 *
 * The ball is an armor stand wearing a player head; only the one that appears next to you right after your throw
 * message is followed, so other players' balls are left alone.
 */
object BeachBallHelper : Module(
    "Beach Ball Helper",
    Category.Cheats,
    "Year of the Seal: shows where the beach ball comes down with a bounce counter, walks you under it and drops it at the combo you set"
) {
    private val mc = Minecraft.getInstance()

    private val autoWalk = BooleanSetting("Auto Walk", true, "Walk under the landing spot, crouched (you stand up to run when it is far).")
    private val followBall = BooleanSetting("Walk To Ball", true, "Walk to where the ball is right now (smooth, like SkyHanni's marker). Off: walk to the predicted landing spot.")
    private val steer = BooleanSetting("Steer To Start", true, "Stands a little off-centre so the ball bounces back toward where it was thrown (stand left of the spot and it goes right), keeping it away from walls and edges.")
    private val steerStrength = NumberSetting("Steer Strength", 0.07, 0.0, 1.0, 0.01, "Blocks of off-centre per block the spot is from the start.").withDependency { steer.value }
    private val steerMax = NumberSetting("Steer Max Offset (blocks)", 0.3, 0.1, 1.5, 0.05, "Never stand further than this from the centre; much more and the hit stops counting.").withDependency { steer.value }
    private val farDistance = NumberSetting("Run Distance (blocks)", 6.0, 2.0, 30.0, 0.5, "Further than this you stand up and run; otherwise you stay crouched.")
    private val missAt = NumberSetting("Miss At Combo", 40.0, 1.0, 200.0, 1.0, "Walk away from the landing spot once this many bounces are reached.")
    private val awayDistance = NumberSetting("Walk Away (blocks)", 3.0, 1.0, 10.0, 0.5)
    private val detectRange = NumberSetting("Detect Range (blocks)", 40.0, 5.0, 100.0, 1.0)
    private val showMarker = BooleanSetting("Landing Marker", true)
    private val showPath = BooleanSetting("Path Line", true, "The ball's arc since it last passed you (dark) and the predicted arc (bright).")
    private val popup = BooleanSetting("Popup When Ball Ends", true)
    private val throwNext = BooleanSetting("Throw Next Ball", false, "Right-click a Bouncy Beach Ball from the hotbar after one ends.")
    private val texture = TextSetting("Ball Texture", BALL_TEXTURE, "Head texture of the ball (base64 value or a part of it). Empty: the built-in one.", 400)
    private val lookSmoothMs = NumberSetting("Look Smoothness ms", 250.0, 50.0, 600.0, 10.0)
    private val logHeads = BooleanSetting("Log Head Textures", false, "Prints nearby heads' textures to the Debug Panel.")

    /** The Bouncy Beach Ball head's skin hash (the end of its texture url), from a Debug Panel log. */
    private const val BALL_TEXTURE = "2adf9d71367cd6e505fb48caaa5acdcdff2a09f66c488daf04d045ee0bf528e1"
    private const val ARM_TIMEOUT_MS = 15_000L
    private const val SPAWN_WINDOW_MS = 5_000L
    private const val OWN_SPAWN_RANGE = 10f
    private const val BALL_ITEM = "Bouncy Beach Ball"
    private const val THROW_DELAY_TICKS = 30
    private val FORMAT_CODE = Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE)
    private val SERVER_COUNT = Regex("""in the air for (\d+) bounces?""")
    // The action bar reads "2,578/2,203❤  Bounces: 3  2,465/2,465✎ ...".
    private val BAR_COUNT = Regex("""(?i)bounces:?\s*(\d+)""")

    private const val IDLE_END_TICKS = 60
    private const val CONTACT_RANGE = 2.1
    private const val PREDICT_EVERY = 4
    private const val BOUNCE_MIN_MOVE = 0.3
    private const val BOUNCE_MIN_GAP_MS = 800L
    private const val MAX_PATH_TICKS = 300

    // Walking: players on Hypixel often have a lot of speed, so you coast and overshoot unless this is allowed for.
    private const val COAST_TICKS = 4.0
    // Smooth over exact: the server counts a hit a good way off centre, so only start moving past START_OFFSET, stop
    // inside STOP_OFFSET, and keep going in between (long, steady moves instead of constant small corrections).
    private const val START_OFFSET = 0.35
    private const val STOP_OFFSET = 0.12

    private var ball: Entity? = null
    private val data = ArrayList<Vec3>()
    private var startIndex = 0
    private var minY = 0.0
    private var predictedPath: List<Vec3> = emptyList()
    private var sincePredict = 0
    private var rising = true
    private var lastDirPos = Vec3.ZERO
    private var lastBounceMs = 0L
    private var combo = 0
    private var idleTicks = 0
    private var tickCount = 0

    /** Where the ball is expected to come down (x, z matter), eased so it doesn't step. */
    private var target: Vec3? = null

    /** Where the ball was when you threw it: the steering aims the ball back here. */
    private var home: Vec3? = null

    /** Ball head entity id -> (when first seen, distance from you then). */
    private val seen = HashMap<Int, Pair<Long, Float>>()
    private val decoded = HashMap<String, String>()

    private var barSeen = false
    private var lastBar = ""
    private var fwdDir = 0
    private var rightDir = 0
    private var lastOff = 0.0
    private var lastDy = 0.0
    private var armed = false
    private var armedAt = 0L
    private var throwIn = -1
    private var balls = 0
    private var totalBounces = 0

    private var controlling = false
    private var look: HumanLook? = null
    private var lastFrameNanos = 0L

    init {
        listOf(lookSmoothMs, awayDistance, logHeads).forEach { it.advanced = true }
        addSettings(
            autoWalk, followBall, steer, steerStrength, steerMax, farDistance, missAt, awayDistance, detectRange, showMarker, showPath, popup, throwNext,
            texture, lookSmoothMs, logHeads
        )

        ClientTickEvents.END_CLIENT_TICK.register { if (isEnabled) tick() }

        // Only runs for a ball you threw: the ability prints this when it's used.
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!isEnabled) return@register
            val text = message.string.replace(FORMAT_CODE, "")
            if (overlay) return@register actionBar(text)
            SERVER_COUNT.find(text)?.let { Debug.line("BeachBall", "Server counted ${it.groupValues[1]} bounces, we counted $combo") }
            // The full throw line, so whatever the game says when a ball ends doesn't arm it again.
            if (text.contains("BOUNCE BONANZA", ignoreCase = true) && text.contains("Keep the Bouncy Beach Ball in the air", ignoreCase = true)) {
                armed = true
                armedAt = System.currentTimeMillis()
                Debug.line("BeachBall", "Ball thrown, armed")
            }
        }

        // Camera: only the pitch moves (straight down), every rendered frame.
        LevelRenderEvents.START_MAIN.register {
            if (!isEnabled || ball == null || !autoWalk.value || mc.screen != null) return@register
            val player = mc.player ?: return@register
            val now = System.nanoTime()
            if (lastFrameNanos == 0L) { lastFrameNanos = now; return@register }
            var dt = (now - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = now
            if (dt <= 0f || dt > 0.1f) dt = 1f / 60f
            val hl = look ?: HumanLook((lookSmoothMs.value / 1000.0).toFloat(), 900f, 0f).also { look = it }
            val (_, newPitch) = hl.step(player.yRot, player.xRot, player.yRot, 90f, dt)
            player.xRot = newPitch
        }

        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!isEnabled || ball == null) return@AfterSolidFeatures
            val player = mc.player ?: return@AfterSolidFeatures
            val poseStack = context.poseStack()
            val cam = context.gameRenderer().mainCamera.position()
            val bufferSource = context.bufferSource()

            if (showPath.value) {
                val buf = bufferSource.getBuffer(CustomRenderType.LINES_ESP)
                drawPath(poseStack, buf, data.subList(startIndex, data.size).toList(), cam, 0.1f, 0.5f, 0.1f)
                drawPath(poseStack, buf, predictedPath, cam, 0.3f, 1f, 0.3f)
                bufferSource.endBatch()
            }
            // Like SkyHanni: the marker rides the ball's own smooth movement (not the stepping prediction), at your height.
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
            val land = ball?.getPosition(pt)
            if (!showMarker.value || land == null) return@AfterSolidFeatures

            // Like SkyHanni: green right under it, orange close, red otherwise.
            val me = player.getPosition(pt)
            val off = hypot(land.x - me.x, land.z - me.z)
            val (r, g, b) = when {
                off < 0.3 -> Triple(0.2f, 1f, 0.2f)
                off < 0.9 -> Triple(1f, 0.65f, 0.1f)
                else -> Triple(1f, 0.2f, 0.2f)
            }
            val y = me.y
            val box = AABB(land.x - 0.3, y, land.z - 0.3, land.x + 0.3, y + 0.05, land.z + 0.3).move(-cam.x, -cam.y, -cam.z)
            drawBox(poseStack, bufferSource.getBuffer(CustomRenderType.LINES_ESP), box, r, g, b, 1f)

            poseStack.pushPose()
            poseStack.translate((land.x - cam.x).toFloat(), (y + 0.7 - cam.y).toFloat(), (land.z - cam.z).toFloat())
            context.submitNodeCollector().submitNameTag(
                poseStack, Vec3.ZERO, 0,
                Component.literal("Bounces: $combo").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                true, 0xF000F0, 4096.0, context.levelState().cameraRenderState
            )
            poseStack.popPose()
            bufferSource.endBatch()
        })
    }

    override fun onDisable() {
        throwIn = -1
        release()
        reset()
    }

    private fun tick() {
        val player = mc.player ?: return release()
        tickCount++
        scanBalls(player)

        if (throwIn > 0) throwIn--
        else if (throwIn == 0) {
            throwIn = -1
            if (ball == null && mc.screen == null) throwBall(player)
        }

        if (logHeads.value && tickCount % 40 == 0) logNearbyHeads()

        var b = ball
        if (b != null && (!b.isAlive || player.distanceTo(b) > detectRange.value)) {
            endBall(if (!b.isAlive) "ball gone" else "out of range")
            b = null
        }
        if (armed && System.currentTimeMillis() - armedAt > ARM_TIMEOUT_MS) {
            armed = false
            Debug.line("BeachBall", "No ball found after the throw, disarmed")
        }
        if (b == null && armed) {
            b = findBall()?.also { startBall(it) }
            if (b == null && tickCount % 20 == 0) {
                val now = System.currentTimeMillis()
                Debug.line("BeachBall", "Armed, no ball yet: " +
                    (seen.entries.joinToString { "#${it.key} ${"%.1f".format(it.value.second)}m ${it.value.first - armedAt}ms" }.ifEmpty { "no ball heads in view" }))
            }
        }
        if (b == null) return release()

        val pos = b.position()
        newData(player, pos)
        lastOff = hypot(pos.x - player.x, pos.z - player.z)
        lastDy = pos.y - minY
        if (idleTicks > IDLE_END_TICKS) return endBall("sat still")

        val spot = if (followBall.value) pos else predictedPath.lastOrNull() ?: pos
        target = if (steer.value) steered(spot) else spot
        walk(player)
    }

    /** The ball is pushed away from the side you hit it on: stand opposite the start so it drifts back there. */
    private fun steered(spot: Vec3): Vec3 {
        val home = home ?: return spot
        val dx = home.x - spot.x
        val dz = home.z - spot.z
        val dist = hypot(dx, dz)
        if (dist < 0.01) return spot
        val mag = minOf(steerMax.value, dist * steerStrength.value)
        return Vec3(spot.x - dx / dist * mag, spot.y, spot.z - dz / dist * mag)
    }

    /** The server shows the bounce count in the action bar: that is the real count, so it replaces our own while it shows. */
    private fun actionBar(text: String) {
        if (ball == null || !text.contains("ounce", ignoreCase = true)) return
        val m = BAR_COUNT.find(text)
        val n = m?.groupValues?.get(1)?.toIntOrNull()
        if (text != lastBar) {
            lastBar = text
            Debug.line("BeachBall", "Action bar: \"$text\" -> ${n ?: "no count found"} (we counted $combo)")
        }
        if (n == null) return
        barSeen = true
        combo = n
    }

    // --- Prediction (after SkyHanni's BeachBallCatchHelper) ---

    private fun newData(player: LocalPlayer, new: Vec3) {
        updateDirection(new)
        idleTicks = if (data.isNotEmpty() && abs(new.y - data.last().y) < 0.005) idleTicks + 1 else 0
        data.add(new)
        if (new.distanceTo(player.position()) < CONTACT_RANGE) {
            startIndex = data.lastIndex
            minY = new.y
        }
        predictedPath = predictedPath.drop(1)
        sincePredict++
        // Only re-predict every few ticks so the path (and you) don't flicker.
        if (sincePredict < PREDICT_EVERY) return
        predictedPath = predict()
        sincePredict = 0
    }

    /** A bounce is the ball turning upward after a real move, at most once per [BOUNCE_MIN_GAP_MS]. */
    private fun updateDirection(new: Vec3) {
        if (lastDirPos.distanceTo(new) < BOUNCE_MIN_MOVE) return
        if (System.currentTimeMillis() - lastBounceMs < BOUNCE_MIN_GAP_MS) return
        val isRising = new.y - lastDirPos.y > 0
        if (isRising && !rising) {
            if (!barSeen) combo++ // once the action bar gives the count, that one is used instead
            lastBounceMs = System.currentTimeMillis()
            // How far from your head the ball turned: a small number is a clean hit, a big one a lucky or near miss.
            val p = mc.player
            Debug.line("BeachBall", "Bounce #$combo" + (p?.let {
                ", ball ${"%.2f".format(hypot(new.x - it.x, new.z - it.z))}m off you, you moving ${"%.2f".format(it.deltaMovement.horizontalDistance())}/tick" +
                    (target?.let { t -> ", spot was ${"%.2f".format(hypot(t.x - new.x, t.z - new.z))}m from the ball" } ?: "")
            } ?: ""))
        }
        rising = isRising
        lastDirPos = new
    }

    /** One way of fitting the arc: three sample times for a parabola through y, and how x / z speed is read. */
    private class Model(
        val minSamples: Int,
        val weight: Int,
        val t1: (start: Int, cur: Int) -> Int,
        val t2: (start: Int, cur: Int) -> Int,
        val t3: (start: Int, cur: Int) -> Int,
        val smooth: Boolean = false,
        val avgSpeed: Boolean = false
    )

    private val models = listOf(
        Model(3, 1, { _, c -> c }, { _, c -> c - 1 }, { _, c -> c - 2 }),
        Model(7, 2, { _, c -> c - 1 }, { _, c -> c - 3 }, { _, c -> c - 5 }, smooth = true, avgSpeed = true),
        Model(5, 1, { _, c -> c - 1 }, { s, c -> (c - s) / 2 + s }, { s, _ -> s + 1 }, smooth = true)
    )

    private fun predict(): List<Vec3> {
        val cur = data.lastIndex
        val present = cur - startIndex
        val fits = models.filter { it.minSamples <= present }
            .mapNotNull { m -> fit(m, cur)?.let { m to it } }
            .filter { (_, path) -> path.last().y in (minY - 1)..(minY + 1) }
        if (fits.isEmpty()) return listOf(data.last())

        val total = fits.sumOf { it.first.weight }
        val ax = fits.sumOf { it.second.last().x * it.first.weight } / total
        val az = fits.sumOf { it.second.last().z * it.first.weight } / total
        return fits.minBy { (_, path) ->
            val l = path.last()
            (l.x - ax) * (l.x - ax) + (l.z - az) * (l.z - az)
        }.second
    }

    /** The path [m] expects from now until the ball is back down at the bounce height. */
    private fun fit(m: Model, cur: Int): List<Vec3>? {
        fun y(t: Int) = if (m.smooth) (data[t - 1].y + data[t].y + data[t + 1].y) / 3 else data[t].y
        val t1 = m.t1(startIndex, cur)
        val t2 = m.t2(startIndex, cur)
        val t3 = m.t3(startIndex, cur)
        val y1 = y(t1); val y2 = y(t2); val y3 = y(t3)
        val a = ((y3 - y1) * (t2 - t1) + (y2 - y1) * (t1 - t3)) /
            ((t3 * t3 - t1 * t1).toDouble() * (t2 - t1) + (t2 * t2 - t1 * t1).toDouble() * (t1 - t3))
        val b = ((y2 - y1) - a * (t2 * t2 - t1 * t1)) / (t2 - t1)
        val c = y1 - b * t1 - a * t1 * t1
        if (!a.isFinite() || !b.isFinite() || !c.isFinite()) return null

        fun speed(axis: (Vec3) -> Double): Double =
            if (m.avgSpeed) (0..2).map { axis(data[cur - it]) - axis(data[cur - it - 1]) }.average()
            else axis(data[cur]) - axis(data[cur - 1])
        val dx = speed { it.x }
        val dz = speed { it.z }

        val path = ArrayList<Vec3>()
        var p = data[cur]
        path += p
        for (t in cur + 1..cur + MAX_PATH_TICKS) {
            val py = a * t * t + b * t + c
            p = Vec3(p.x + dx, py, p.z + dz)
            path += p
            if (py <= minY) break
        }
        return path
    }

    // --- Finding your ball ---

    private fun hasTexture(e: ArmorStand, wanted: String): Boolean {
        val raw = e.getWornSkullTexture() ?: return false
        if (raw.contains(wanted)) return true
        val json = decoded.getOrPut(raw) { runCatching { String(java.util.Base64.getMimeDecoder().decode(raw)) }.getOrDefault("") }
        return json.contains(wanted)
    }

    /** Remembers when and how far from you each ball head first showed up, so your own throw can be told from others'. */
    private fun scanBalls(player: Entity) {
        val level = mc.level ?: return
        val wanted = texture.value.ifBlank { BALL_TEXTURE }
        val now = System.currentTimeMillis()
        val alive = HashSet<Int>()
        for (e in level.entitiesForRendering()) {
            if (e !is ArmorStand || !e.isAlive || !hasTexture(e, wanted)) continue
            alive += e.id
            if (e.id !in seen) {
                seen[e.id] = now to player.distanceTo(e)
                Debug.line("BeachBall", "Ball head #${e.id} first seen ${"%.1f".format(player.distanceTo(e))}m away, armed=$armed")
            }
        }
        seen.keys.retainAll(alive)
    }

    /** Your ball: a ball head that appeared right next to you around the moment the throw message came. */
    private fun findBall(): Entity? {
        val level = mc.level ?: return null
        return level.entitiesForRendering()
            .filter { e ->
                val s = seen[e.id]
                e is ArmorStand && e.isAlive && s != null &&
                    abs(s.first - armedAt) <= SPAWN_WINDOW_MS && s.second <= OWN_SPAWN_RANGE
            }
            .minByOrNull { seen.getValue(it.id).second }
    }

    private fun isBallCandidate(e: Entity) =
        e.type == EntityType.SLIME || (e is ArmorStand && !e.getItemBySlot(EquipmentSlot.HEAD).isEmpty)

    private fun startBall(b: Entity) {
        ball = b
        armed = false
        barSeen = false
        lastBar = ""
        data.clear()
        startIndex = 0
        minY = 0.0
        predictedPath = emptyList()
        sincePredict = 0
        rising = true
        lastDirPos = b.position()
        home = b.position()
        lastBounceMs = 0L
        combo = 0
        idleTicks = 0
        target = null
        Debug.line("BeachBall", "Ball #${b.id} tracked")
    }

    private fun endBall(reason: String) {
        if (ball == null) return
        balls++
        totalBounces += combo
        val avg = totalBounces.toDouble() / balls
        Debug.line("BeachBall", "Ball ended ($reason): $combo bounces, $balls balls, avg ${"%.1f".format(avg)}; " +
            "last seen ${"%.2f".format(lastOff)}m off you, ${"%.2f".format(lastDy)}m above bounce height")
        if (popup.value) {
            mc.gui.setTitle(Component.literal("§aBall #$balls: $combo bounces"))
            mc.gui.setSubtitle(Component.literal("§7Avg §e${"%.1f".format(avg)}§7 bounces per ball"))
            mc.gui.setTimes(5, 50, 10)
        }
        release()
        reset()
        if (throwNext.value) throwIn = THROW_DELAY_TICKS
    }

    private fun reset() {
        armed = false
        ball = null
        data.clear()
        predictedPath = emptyList()
        target = null
        home = null
        combo = 0
        look = null
        lastFrameNanos = 0L
    }

    private fun throwBall(player: LocalPlayer) {
        val slot = (0..8).firstOrNull { player.inventory.getItem(it).hoverName.string.contains(BALL_ITEM, ignoreCase = true) }
        if (slot == null) return Debug.line("BeachBall", "No $BALL_ITEM in hotbar, not throwing")
        player.inventory.setSelectedSlot(slot)
        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        Debug.line("BeachBall", "Threw a new ball from slot ${slot + 1}")
    }

    // --- Walking ---

    /**
     * WASD relative to where you look (yaw stays put, the camera only tilts down), crouched unless the spot is far.
     * Speed buffs make you slide, so it aims where you'd end up if you let go now.
     */
    private fun walk(player: LocalPlayer) {
        val land = target
        if (!autoWalk.value || land == null || mc.screen != null) return release()
        val dist = hypot(land.x - player.x, land.z - player.z)
        val away = combo >= missAt.value
        if (away && dist >= awayDistance.value) return release()

        val vel = player.deltaMovement
        val dx = land.x - (player.x + vel.x * COAST_TICKS)
        val dz = land.z - (player.z + vel.z * COAST_TICKS)
        val sign = if (away) -1.0 else 1.0
        val yaw = Math.toRadians(player.yRot.toDouble())
        val fwd = sign * (-sin(yaw) * dx + cos(yaw) * dz)
        val right = sign * (-cos(yaw) * dx - sin(yaw) * dz)
        val opts = mc.options
        val far = dist > farDistance.value && !away
        val keys = ArrayList<KeyMapping>(3)
        if (away) { fwdDir = if (fwd > 0) 1 else -1; rightDir = if (right > 0) 1 else -1 }
        else { fwdDir = latch(fwd, fwdDir); rightDir = latch(right, rightDir) }
        if (fwdDir > 0) keys += opts.keyUp else if (fwdDir < 0) keys += opts.keyDown
        if (rightDir > 0) keys += opts.keyRight else if (rightDir < 0) keys += opts.keyLeft
        // Crouch stays held the whole time (letting go of every key would stand you up and sneak again each tick).
        if (!far) keys += opts.keyShift
        else if (opts.keyUp in keys) keys += opts.keySprint
        if (keys.isEmpty()) return release()
        hold(*keys.toTypedArray())
    }

    /** -1 / 0 / 1 for one axis: starts past [START_OFFSET], stops inside [STOP_OFFSET], else keeps what it was doing. */
    private fun latch(error: Double, current: Int): Int = when {
        error > START_OFFSET -> 1
        error < -START_OFFSET -> -1
        abs(error) < STOP_OFFSET -> 0
        else -> current
    }

    private fun hold(vararg keys: KeyMapping) {
        controlling = true
        KeyBindUtils.holdThese(*keys)
    }

    private fun release() {
        if (!controlling) return
        controlling = false
        KeyBindUtils.stopMovement()
    }

    private fun logNearbyHeads() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        for (e in level.entitiesForRendering()) {
            if (!isBallCandidate(e) || player.distanceTo(e) > detectRange.value) continue
            val head = (e as? ArmorStand)?.getItemBySlot(EquipmentSlot.HEAD)
            Debug.line("BeachBall", "${e.type} #${e.id} ${"%.1f".format(player.distanceTo(e))}m y=${"%.2f".format(e.y)}" +
                    (head?.let { " head=${it.hoverName.string} tex=${(e as ArmorStand).getWornSkullTexture()}" } ?: ""))
        }
    }

    private fun drawPath(poseStack: PoseStack, buf: VertexConsumer, path: List<Vec3>, cam: Vec3, r: Float, g: Float, b: Float) {
        for (i in 1 until path.size) edge(poseStack, buf, path[i - 1].subtract(cam), path[i].subtract(cam), r, g, b, 1f)
    }

    private fun drawBox(poseStack: PoseStack, buf: VertexConsumer, b: AABB, r: Float, g: Float, bl: Float, a: Float) {
        val xs = doubleArrayOf(b.minX, b.maxX)
        val ys = doubleArrayOf(b.minY, b.maxY)
        val zs = doubleArrayOf(b.minZ, b.maxZ)
        for (y in ys) for (z in zs) edge(poseStack, buf, Vec3(xs[0], y, z), Vec3(xs[1], y, z), r, g, bl, a)
        for (x in xs) for (z in zs) edge(poseStack, buf, Vec3(x, ys[0], z), Vec3(x, ys[1], z), r, g, bl, a)
        for (x in xs) for (y in ys) edge(poseStack, buf, Vec3(x, y, zs[0]), Vec3(x, y, zs[1]), r, g, bl, a)
    }

    private fun edge(poseStack: PoseStack, buf: VertexConsumer, from: Vec3, to: Vec3, r: Float, g: Float, b: Float, a: Float) {
        val pose = poseStack.last().pose()
        val n = to.subtract(from).normalize()
        buf.addVertex(pose, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .setColor(r, g, b, a).setNormal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat()).setLineWidth(2.0f)
        buf.addVertex(pose, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
            .setColor(r, g, b, a).setNormal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat()).setLineWidth(2.0f)
    }
}
//#endif

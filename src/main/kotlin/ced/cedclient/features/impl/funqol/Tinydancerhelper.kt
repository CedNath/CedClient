package ced.cedclient.features.impl.funqol

import ced.cedclient.events.PlaySoundEvent
import ced.cedclient.events.SubtitleEvent
import ced.cedclient.events.core.TickEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.mixin.accessor.MinecraftAccessor
import ced.cedclient.utils.HumanLook
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Input
import kotlin.math.abs
import kotlin.math.floor

/**
 * Rift Dance Room helper (Tiny Dancer talisman), ported from appable0/DanceRoomSolver.
 *
 * Flow: stand on the green block in the Dance Room. When the "Move!" subtitle appears the
 * helper takes over movement, following the beat sounds the server plays:
 *   - walks the 4 sides of the floor (one segment per odd beat)
 *   - sneaks / unsneaks from beat 8, jumps from beat 24, punches from beat 64
 *   - stops on the "complete" pitch, a failure burp, leaving the floor, or moving your mouse
 *
 * Movement is applied through KeyboardInputMixin (see overrideInput()); the camera is
 * locked to yaw 90 / pitch 90 (facing west, looking straight down) so the directional keys
 * map onto world axes.
 */
object TinyDancerHelper : Module(
    "TinyDancerHelper",
    Category.Funqol,
    "Completes the Rift Dance Room (Tiny Dancer). Stand on the green block and wait for \"Move!\""
) {
    private val mc: Minecraft = Minecraft.getInstance()

    // --- UI settings ---
    private val jumpDelayMs = NumberSetting("Jump Delay ms", 500.0, 0.0, 1000.0, 10.0)
    private val punchDelayMs = NumberSetting("Punch Delay ms", 800.0, 0.0, 1500.0, 10.0)
    private val beatMessages = BooleanSetting("Beat Messages", true)
    private val debugLog = BooleanSetting("Debug Log", false)
    private val testBeatTicks = NumberSetting("Test Beat Ticks", 10.0, 4.0, 40.0, 1.0)
    private val lookSmoothnessMs = NumberSetting("Look Smoothness ms", 150.0, 50.0, 500.0, 10.0)

    // --- Dance Room data (taken from DanceRoomSolver) ---
    // Beat sounds are note-block bass hits at one of these pitches; one pitch = "complete".
    private val beatPitches = floatArrayOf(0.5238095f, 1.0476191f, 0.6984127f, 0.8888889f)
    private const val COMPLETE_PITCH = 0.74603176f
    private const val PITCH_TOLERANCE = 0.01f

    // Approximate centre of the dance floor + how far from it the helper may run.
    private const val FLOOR_X = -263.0
    private const val FLOOR_Z = -106.5
    private const val FLOOR_RADIUS = 10.0

    // Singleplayer test run (/cc dance): a fake beat every few ticks instead of server sounds.
    private const val TEST_COMPLETE_BEAT = 72

    private enum class Dir { FORWARD, BACKWARD, LEFT, RIGHT }

    /** One side of the square. [target] is the block coordinate to reach on that axis. */
    private data class Segment(val alongX: Boolean, val target: Int, val dir: Dir)

    // Start at (-264, -108), camera yaw 90 (west):  left = +Z, backward = +X, right = -Z, forward = -X
    private val segments = listOf(
        Segment(alongX = false, target = -105, dir = Dir.LEFT),
        Segment(alongX = true, target = -262, dir = Dir.BACKWARD),
        Segment(alongX = false, target = -107, dir = Dir.RIGHT),
        Segment(alongX = true, target = -264, dir = Dir.FORWARD),
    )

    // --- runtime state ---
    private var active = false
    private var beats = 0
    private var segIndex = 0
    private var moveDir: Dir? = null
    private var sneakHeld = false
    private var jumpTicksLeft = 0
    private var jumpHeld = false
    private val pendingJumps = mutableListOf<Long>()
    private val pendingPunches = mutableListOf<Long>()

    // Test mode: beats come from a tick counter, and the whole floor is shifted so the
    // square starts at the block you were standing on (see activate()).
    private var testMode = false
    private var testTickCounter = 0
    private var offX = 0.0
    private var offZ = 0.0

    // Smooth camera alignment (same HumanLook easing CoralotHelper uses) instead of snapping.
    private var aligning = false
    private var alignLook: HumanLook? = null
    private var alignLastNanos = 0L
    private var alignDeadline = 0L

    init {
        listOf(jumpDelayMs, punchDelayMs, debugLog, testBeatTicks, lookSmoothnessMs).forEach { it.advanced = true }
        addSettings(beatMessages, lookSmoothnessMs, jumpDelayMs, punchDelayMs, debugLog, testBeatTicks)

        // IMPORTANT: ClientPacketListenerMixin injects at the HEAD of the packet handlers, which
        // runs once on the network (netty) thread and then AGAIN on the main thread (vanilla
        // re-queues the packet). Without this guard every beat would count twice, and anything
        // that touches the GUI (like chat) from the netty thread throws "Rendersystem called
        // from wrong thread" and kicks you from the server. Only the main-thread pass is used.
        on<SubtitleEvent> { event ->
            if (!mc.isSameThread()) return@on
            if (!isEnabled || active) return@on
            if (event.unformattedText.trim() == "Move!" && nearDanceFloor()) activate()
        }

        on<PlaySoundEvent> { event ->
            if (!mc.isSameThread()) return@on
            handleSound(event.soundName, event.pitch, event.volume)
        }

        on<TickEvent.Start> { tick() }

        // Camera movement runs every rendered frame (not 20x/sec) so the turn is smooth.
        LevelRenderEvents.START_MAIN.register {
            if (!isEnabled || !active || !aligning) return@register
            val player = mc.player ?: return@register
            alignFrame(player)
        }
    }

    override fun onDisable() {
        deactivate()
    }

    // -------------------------------------------------------------------
    // Singleplayer testing (/cc dance, /cc dance stop)
    // -------------------------------------------------------------------
    /**
     * Starts a fake Dance Room run at your current position: no Hypixel needed. Beats are
     * generated by a tick counter and the floor is shifted to start on your block, so you
     * can watch the walk / sneak / jump / punch schedule anywhere with ~4x4 free space.
     * Returns an error message, or null if it started.
     */
    fun startTest(): String? {
        if (!isEnabled) return "§cTurn on TinyDancerHelper in the ClickGUI first."
        if (mc.player == null) return "§cYou're not in a world."
        if (active) return "§eAlready running. Use /cc dance stop to cancel."
        activate(test = true)
        return null
    }

    /** Stops any running solver (real or test). Returns a status message. */
    fun stopTest(): String {
        if (!active) return "§7Nothing is running."
        deactivate()
        return "§7Stopped."
    }

    // -------------------------------------------------------------------
    // Input override -- called from KeyboardInputMixin every client tick
    // -------------------------------------------------------------------
    /** Returns the key presses to force this tick, or null if the helper isn't driving. */
    @JvmStatic
    fun overrideInput(): Input? {
        if (!isEnabled || !active) return null
        val d = moveDir
        return Input(
            d == Dir.FORWARD,
            d == Dir.BACKWARD,
            d == Dir.LEFT,
            d == Dir.RIGHT,
            jumpHeld,
            sneakHeld,
            false
        )
    }

    // -------------------------------------------------------------------
    // Activation
    // -------------------------------------------------------------------
    private fun activate(test: Boolean = false) {
        val player = mc.player ?: return
        active = true
        testMode = test
        testTickCounter = 0
        if (test) {
            // Shift the real floor so its start block (-264, -108) lands on the block we're on.
            offX = floor(player.x) - (-264.0)
            offZ = floor(player.z) - (-108.0)
        } else {
            offX = 0.0
            offZ = 0.0
        }
        beats = 0
        segIndex = 0
        moveDir = null
        sneakHeld = false
        jumpHeld = false
        jumpTicksLeft = 0
        pendingJumps.clear()
        pendingPunches.clear()

        say(if (test) "§aTest run started (fake beats every ${testBeatTicks.value.toInt()} ticks)." else "§aDance Room solver enabled!")

        // Face west + look straight down so W/A/S/D line up with world axes -- but turn there
        // smoothly. Movement only starts once the camera is lined up (see finishAlign()).
        val yawOff = abs(Mth.wrapDegrees(player.yRot - 90f))
        val pitchOff = abs(player.xRot - 90f)
        if (yawOff < 1f && pitchOff < 1f) {
            finishAlign(player)
        } else {
            aligning = true
            alignLook = HumanLook(
                smoothTimeSeconds = (lookSmoothnessMs.value / 1000.0).toFloat(),
                maxDegreesPerSecond = 900f,
                jitterDegrees = 0f   // no jitter: we need to land exactly on yaw 90 / pitch 90
            )
            alignLastNanos = 0L
            alignDeadline = System.currentTimeMillis() + 3000L
        }
    }

    /** Runs every rendered frame while [aligning]; eases the camera toward yaw 90 / pitch 90. */
    private fun alignFrame(player: LocalPlayer) {
        val look = alignLook ?: return
        val now = System.nanoTime()
        if (alignLastNanos == 0L) {
            alignLastNanos = now   // first frame: just start the clock
            return
        }
        var dt = (now - alignLastNanos) / 1_000_000_000f
        alignLastNanos = now
        if (dt <= 0f || dt > 0.1f) dt = 1f / 60f   // lag spike / alt-tab guard, no giant jump

        val (newYaw, newPitch) = look.step(player.yRot, player.xRot, 90f, 90f, dt)
        player.yRot = newYaw
        player.xRot = newPitch

        if (abs(Mth.wrapDegrees(newYaw - 90f)) < 1f && abs(newPitch - 90f) < 1f) finishAlign(player)
    }

    /** Camera is lined up: lock it exactly and start (or catch up on) the dance. */
    private fun finishAlign(player: LocalPlayer) {
        player.yRot = 90f
        player.xRot = 90f
        player.yRotO = 90f
        player.xRotO = 90f
        aligning = false
        alignLook = null

        if (testMode) {
            // Test runs have no sounds: start the fake beat clock now.
            testTickCounter = 0
            doMove(0)
        } else if (beats > 0) {
            // Beat sounds kept arriving while we were turning: re-apply the latest beat's action.
            doMove(beats - 1)
        }
    }

    private fun deactivate() {
        active = false
        aligning = false
        alignLook = null
        testMode = false
        testTickCounter = 0
        offX = 0.0
        offZ = 0.0
        beats = 0
        segIndex = 0
        moveDir = null
        sneakHeld = false
        jumpHeld = false
        jumpTicksLeft = 0
        pendingJumps.clear()
        pendingPunches.clear()
    }

    // -------------------------------------------------------------------
    // Per-beat logic (identical schedule to DanceRoomSolver)
    // -------------------------------------------------------------------
    private fun doMove(beat: Int) {
        val status = mutableListOf<String>()
        val now = System.currentTimeMillis()

        if (beat == 0 || beat % 2 == 1) {
            status += "moving"
            moveDir = segments[segIndex].dir
        }

        if (beat >= 8) {
            if (beat % 4 == 0) {
                status += "sneaking"
                sneakHeld = true
            } else if (beat % 4 == 1) {
                status += "unsneaking"
                sneakHeld = false
            }
        }

        if (beat >= 24 && (beat % 8 == 0 || beat % 8 == 2)) {
            status += "jumping"
            pendingJumps += now + jumpDelayMs.value.toLong()
        }

        if (beat >= 64 && beat % 2 == 0) {
            status += "punching"
            pendingPunches += now + punchDelayMs.value.toLong()
        }

        if (beatMessages.value) {
            val suffix = if (status.isEmpty()) "" else ": ${status.joinToString(", ")}"
            say("§7Beat $beat$suffix.")
        }
    }

    // -------------------------------------------------------------------
    // Sounds
    // -------------------------------------------------------------------
    private fun handleSound(name: String, pitch: Float, volume: Float) {
        if (!isEnabled) return

        // Use "Debug Log" near the floor to verify the sound names/pitches this client
        // actually receives (they can differ from the 1.8 values the original used).
        if (debugLog.value && nearDanceFloor()) {
            println("[TinyDancer] sound=$name pitch=$pitch volume=$volume active=$active beats=$beats")
        }
        if (!active || testMode) return

        if (name.contains("burp") || name.contains("villager.no")) {
            say("§cFailed! Toggling off.")
            deactivate()
            return
        }

        val isBass = name.contains("note_block.bass")
        if (!isBass || volume < 0.99f) return

        if (beatPitches.any { abs(it - pitch) < PITCH_TOLERANCE }) {
            beats++
            // The first beat sound arrives right as "Move!" shows, so it is schedule beat 0.
            // The original 1.8 script had no such sound; counting from beat 1 (as I did first)
            // runs every action one beat too early -- that is what caused "You weren't sneaking!".
            if (aligning) return   // still turning: finishAlign() catches up on the latest beat
            doMove(beats - 1)
        } else if (abs(COMPLETE_PITCH - pitch) < PITCH_TOLERANCE) {
            say("§aCompleted! Toggling off.")
            deactivate()
        }
    }

    // -------------------------------------------------------------------
    // Tick (runs before the player's input is read this tick)
    // -------------------------------------------------------------------
    private fun tick() {
        if (!isEnabled || !active) return
        val player = mc.player
        if (player == null) {
            deactivate()
            return
        }

        if (!nearDanceFloor()) {
            say("§cCancelled because you left the dance floor.")
            deactivate()
            return
        }

        // Still easing the camera into place (per-frame, see alignFrame): nothing else to do yet.
        if (aligning) {
            if (System.currentTimeMillis() > alignDeadline) finishAlign(player)
            return
        }

        // Cancel if the camera was moved by hand.
        if (abs(Mth.wrapDegrees(player.yRot - 90f)) > 1f || abs(player.xRot - 90f) > 1f) {
            say("§cCancelled because you moved your mouse!")
            deactivate()
            return
        }

        // Test mode: fake beat clock instead of server sounds.
        if (testMode) {
            testTickCounter++
            if (testTickCounter >= testBeatTicks.value.toInt()) {
                testTickCounter = 0
                beats++
                if (beats >= TEST_COMPLETE_BEAT) {
                    say("§aTest completed! Toggling off.")
                    deactivate()
                    return
                }
                doMove(beats)
            }
        }

        // Stop walking once the current segment's target tile is reached.
        if (moveDir != null) {
            val seg = segments[segIndex]
            val low = if (seg.target >= 0) seg.target.toDouble() else seg.target - 1.0
            val high = if (seg.target >= 0) seg.target + 1.0 else seg.target.toDouble()
            val pos = if (seg.alongX) player.x - offX else player.z - offZ
            if (pos in low..high) {
                moveDir = null
                segIndex = (segIndex + 1) % segments.size
            }
        }

        val now = System.currentTimeMillis()

        // Jumps: hold the key for 2 ticks (~100ms, like the original).
        if (pendingJumps.removeAll { it <= now }) jumpTicksLeft = 2
        jumpHeld = jumpTicksLeft > 0
        if (jumpTicksLeft > 0) jumpTicksLeft--

        // Punches: a real left-click (see punch()).
        if (pendingPunches.removeAll { it <= now }) punch()
    }

    /**
     * A real left-click, the same as DanceRoomSolver's clickMouse(): runs Minecraft's own
     * startAttack(), which handles whatever is under the crosshair and swings the arm.
     * If vanilla refuses the click (its miss-delay is still running) fall back to a plain swing
     * so a punch always goes out.
     */
    private fun punch() {
        val player = mc.player ?: return
        val clicked = try {
            (mc as MinecraftAccessor).cedStartAttack()
        } catch (t: Throwable) {
            false
        }
        if (!clicked) player.swing(InteractionHand.MAIN_HAND)
    }

    private fun nearDanceFloor(): Boolean {
        val p = mc.player ?: return false
        val dx = p.x - offX - FLOOR_X
        val dz = p.z - offZ - FLOOR_Z
        return dx * dx + dz * dz <= FLOOR_RADIUS * FLOOR_RADIUS
    }

    private fun say(message: String) {
        // Local-only chat line (never sent to the server), same approach as DailyReset.
        // Always hop to the main thread: touching chat/GUI from any other thread crashes the connection.
        mc.execute {
            mc.gui?.hud?.chat?.addClientSystemMessage(Component.literal("§b§l[CC] TinyDancer §7» $message"))
        }
    }
}
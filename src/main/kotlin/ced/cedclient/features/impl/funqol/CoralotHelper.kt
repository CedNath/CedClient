//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.impl.render.ActionBarHud
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.utils.HumanLook
import ced.cedclient.utils.isLookingAt
import ced.cedclient.utils.lookAngles
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.animal.axolotl.Axolotl
import net.minecraft.world.entity.decoration.ArmorStand
import kotlin.random.Random
import org.lwjgl.glfw.GLFW

private enum class CoralotState { IDLE, CATCH_DELAY, CONFIRMING }

/** Colour codes, compiled once. */
private val CODES = Regex("§.")

object CoralotHelper : Module(
    "CoralotHelper",

    Category.Cheats,
   description = "Auto-catches Axolotls with a net once the warning icon clears"
) {
    private val mc = Minecraft.getInstance()

    /** "The Sepialot didn't notice your presence thanks to your Stealth enchantment!" -- any creature name. */
    // No end anchor: chat-stacking mods can append " (x12)".
    private val STEALTH_MESSAGE = Regex("^The (.+?) didn't notice your presence thanks to your Stealth enchantment!")
    private const val STEALTH_RESOLVE_MS = 400L
    // Icons popped at 5.6-16.8m in the logs, Stealth msgs came with the nearest axolotl at 4.4-11.7m.
    private const val SILENT_RANGE = 10f
    private const val SILENT_MS = 3000L
    private val SCARED_MESSAGE = Regex("""^\w+ got scared!""")
    /** A Stealth message with no matching creature yet (e.g. still out of range) is dropped after this. */
    private const val STEALTH_EXPIRE_MS = 5000L
    /** When each still-unresolved Stealth message arrived. */
    private val stealthPending = ArrayList<Long>()
    /** Queued from a Stealth message: if one gets a warning icon after all, it was the wrong pick and the message is retried. */
    private val stealthQueued = HashSet<Axolotl>()
    /** Axolotls whose warning icon is showing this tick (each named armor stand counts for its nearest axolotl only). */
    private var withHolo: Set<Axolotl> = emptySet()

    // --- settings ---
    private val detectRange = NumberSetting("Detect Range (blocks)", 15.0, 1.0, 25.0, 0.5)
    private val smoothnessMs = NumberSetting("Look Smoothness ms", 180.0, 50.0, 500.0, 10.0)
    private val maxTurnSpeed = NumberSetting("Max Turn Speed (deg/s)", 900.0, 100.0, 3000.0, 50.0)
    private val aimJitter = NumberSetting("Aim Jitter (deg)", 0.6, 0.0, 3.0, 0.1)
    private val playSound = BooleanSetting("Play Sound", true)
    private val showTitle = BooleanSetting("Show Title", true)
    private val confirmTimeoutMs = NumberSetting("Confirm Timeout ms", 3000.0, 500.0, 10000.0, 250.0)
    private val catchDelayEnabled = BooleanSetting("Enable Catch Delay", true)
    private val catchDelayMinMs = NumberSetting("Catch Delay Min (ms)", 150.0, 0.0, 3000.0, 10.0)
    private val catchDelayMaxMs = NumberSetting("Catch Delay Max (ms)", 350.0, 0.0, 3000.0, 10.0)
    private val aimToleranceDeg = NumberSetting("Aim Tolerance (deg)", 2.0, 0.5, 15.0, 0.5)
    private val maxAimWaitMs = NumberSetting("Max Aim Wait (ms)", 1500.0, 200.0, 5000.0, 100.0)
    private val restorePreviousItem = BooleanSetting("Restore Previous Item", true)
    private val catchMultipleTargets = BooleanSetting("Catch Multiple", true)
    private val requireKeybind = BooleanSetting(
        "Require Keybind", true,
        "Off: catches on its own. On: keeps scanning, but only looks / catches while toggled on with the Catch Key."
    )
    private val catchKey = KeybindSetting("Catch Key", description = "Toggles catching on / off (with Require Keybind on).")
        .withDependency { requireKeybind.value }

    /** Toggled by [catchKey]; only matters with [requireKeybind] on. */
    private var armed = false
    private var keyWas = false
    /** Also read by TropicalFishHelper, which shares this key while both helpers are on. */
    val canCatch get() = !requireKeybind.value || armed

    /**
     * TropicalFishHelper waits while this is true: an axolotl is queued / mid-catch, or a fresh Stealth message is
     * still being matched. Axolotls still showing their warning icon don't count, so fish get netted meanwhile and
     * the fish helper drops its target the tick one becomes catchable.
     */
    fun hasWork(): Boolean {
        if (!isEnabled) return false
        val now = System.currentTimeMillis()
        return state != CoralotState.IDLE || readyQueue.isNotEmpty() || stealthPending.any { now - it < STEALTH_RESOLVE_MS }
    }

    /** /cc coralot debug: prints each detection / catch step to chat. */
    var debug = false
    /** Set while [dbg] prints, so our own lines never count as a Stealth or catch message. */
    private var printing = false

    fun dbg(msg: String) {
        if (!debug) return
        printing = true
        try { mc.player?.sendSystemMessage(Component.literal("§8[CH] §7$msg")) } finally { printing = false }
    }

    /** /cc coralot status: one line per axolotl in range plus the queue / Stealth state. */
    fun status(): List<String> {
        val player = mc.player ?: return listOf("Not in a world")
        val out = mutableListOf(
            "§fstate §e$state §farmed §e$armed §fcanCatch §e$canCatch §fqueue §e${readyQueue.size} " +
                    "§fstealthPending §e${stealthPending.size} §fstealthQueued §e${stealthQueued.size}"
        )
        mc.level?.entitiesForRendering()?.filterIsInstance<Axolotl>()
            ?.filter { it.isAlive && player.distanceTo(it) <= detectRange.value + 3.0 }
            ?.sortedBy { player.distanceTo(it) }
            ?.forEach { out += "§7#${it.id} §f${"%.1f".format(player.distanceTo(it))}m" + describe(player, it) }
        return out
    }

    private fun describe(player: LocalPlayer, axo: Axolotl) = buildString {
        if (player.distanceTo(axo) > detectRange.value) append(" §8out-of-range")
        if (axo in withHolo) append(" §cicon")
        if (hologramTracked[axo] == true) append(" §6tracked")
        if (axo in readyQueue) append(" §aqueued")
        if (axo in stealthQueued) append(" §bstealth")
        if (axo === target) append(" §dtarget")
    }

    // --- customizable via chat commands (see CedClientCommand) ---
    var netItemName: String = "Net"
    var catchKeywords: List<String> = listOf("caught", "coralot")
    var soundId: String = "minecraft:entity.player.levelup"
    var titleText: String = "&aAxolotl Caught!"

    private var state = CoralotState.IDLE
    private var target: Axolotl? = null
    private var humanLook: HumanLook? = null
    private var confirmDeadline = 0L
    private var catchDeadline = 0L
    private var aimHardDeadline = 0L

    // per-frame timing for smooth look updates (independent of the 20/s tick rate)
    private var lastFrameNanos = 0L

    // slot the player was on right before we swapped to the net, restored once this catch cycle ends
    private var previousSlot: Int? = null

    // tracks, per nearby axolotl, whether it currently has a warning hologram —
    // lets us detect the "warning just disappeared" transition for EVERY axolotl,
    // not just the one we're actively looking at.
    private val hologramTracked = HashMap<Axolotl, Boolean>()
    private val readyQueue = ArrayDeque<Axolotl>()

    init {
        listOf(
            smoothnessMs, maxTurnSpeed, aimJitter, confirmTimeoutMs,
            catchDelayMinMs, catchDelayMaxMs, aimToleranceDeg, maxAimWaitMs,
            restorePreviousItem
        ).forEach { it.advanced = true }

        addSettings(
            detectRange,
            smoothnessMs,
            maxTurnSpeed,
            aimJitter,
            playSound,
            showTitle,
            confirmTimeoutMs,
            catchDelayEnabled,
            catchDelayMinMs,
            catchDelayMaxMs,
            aimToleranceDeg,
            maxAimWaitMs,
            restorePreviousItem,
            catchMultipleTargets,
            requireKeybind,
            catchKey
        )
        // State machine / timers / hologram scanning: doesn't need frame-rate precision, tick rate is fine.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) return@register
            tick()
        }

        // Actual camera movement: needs to run every rendered frame, not every tick,
        // or fast turns visibly stutter (the angle only updates 20x/sec otherwise
        // regardless of your FPS).
        LevelRenderEvents.START_MAIN.register {
            if (!isEnabled) return@register
            if (state != CoralotState.CATCH_DELAY) return@register

            val player = mc.player ?: return@register
            val axo = target ?: return@register
            if (!axo.isAlive) return@register

            smoothLookAtFrame(player, axo)
        }

        ClientReceiveMessageEvents.GAME.register { message, _ ->
            if (!isEnabled || printing) return@register

            // Stealth enchant: the creature never shows its warning icon, so the icon-cleared trigger below never
            // fires -- this message (any mob name) means it can be caught right away. Anchored to the start of
            // the line so the same text pasted in party/guild chat doesn't trigger it.
            val plain = message.string.replace(CODES, "").trim()
            STEALTH_MESSAGE.find(plain)?.let {
                // Other critters (Timil, Blue Jay...) send it too; only the axolotl ones (Coralot, Sepialot, Goldolot) end in "lot".
                if (!it.groupValues[1].endsWith("lot", ignoreCase = true)) {
                    dbg("Stealth msg (${it.groupValues[1]}) ignored: not an axolotl")
                    return@register
                }
                // Resolved a moment later (see tick): Stealth procs per creature, so the ones that DID notice you
                // need a few ticks to get their warning icon -- only then can we tell which one is the stealthed one.
                stealthPending.add(System.currentTimeMillis())
                lastStealthMsgAt = System.currentTimeMillis()
                dbg("Stealth msg (${it.groupValues[1]}), pending ${stealthPending.size}, axolotls: ${axolotlDistances()}")
                return@register
            }

            if (state != CoralotState.CONFIRMING) return@register

            // Thrown while its icon was still up: it ran off, so stop waiting for a catch message.
            if (SCARED_MESSAGE.containsMatchIn(plain)) {
                dbg("#${target?.id} got scared")
                return@register reset()
            }

            val msg = message.string.lowercase()
            if (catchKeywords.any { msg.contains(it.lowercase()) }) {
                dbg("Confirmed #${target?.id}")
                onCatchConfirmed()
            }
        }
    }

    private fun tick() {
        val player = mc.player ?: return

        pollCatchKey()
        val level = mc.level ?: return
        val now = System.currentTimeMillis()
        // A few blocks past the range, so an icon of an axolotl just outside isn't pinned on one inside.
        val around = level.entitiesForRendering().filterIsInstance<Axolotl>()
            .filter { it.isAlive && player.distanceTo(it) <= detectRange.value + 3.0 }
        withHolo = hologramOwners(around)
        val nearby = around.filter { player.distanceTo(it) <= detectRange.value }
        // Scanning always runs, so with the key off the queue is still up to date the moment it's pressed.
        updateHologramTracking(player, nearby)
        if (debug) reportSilent(player, nearby, now)
        // Queued while not catching: drop ones that got their warning icon back or swam out of range.
        readyQueue.removeAll {
            val drop = !it.isAlive || player.distanceTo(it) > detectRange.value || it in withHolo
            // A Stealth pick that shows an icon now noticed you, so the stealthed one is another: retry right away.
            if (drop && stealthQueued.remove(it) && it.isAlive && it in withHolo) {
                stealthPending.add(now - STEALTH_RESOLVE_MS)
                dbg("Stealth pick #${it.id} got an icon, retrying")
            } else if (drop) dbg("Unqueued #${it.id}")
            drop
        }
        stealthQueued.retainAll { it in readyQueue }
        if (stealthPending.removeAll { now - it > STEALTH_EXPIRE_MS }) dbg("Stealth msg expired: no icon-free axolotl in range")
        // Waits for the creatures that DID notice you to get their icon, then keeps retrying until one fits or it expires.
        // One message can cover several axolotls, so one resolve may queue more than one; each queued uses up a message.
        val ready = stealthPending.filter { now - it >= STEALTH_RESOLVE_MS }.sorted()
        if (ready.isNotEmpty()) {
            val queued = queueStealthTargets(player, nearby)
            ready.take(queued).forEach { stealthPending.remove(it) }
        }

        if (state == CoralotState.CONFIRMING && System.currentTimeMillis() > confirmDeadline) {
            // never heard a confirmation message back — bail out safely rather than getting stuck
            dbg("No confirm for #${target?.id} in ${confirmTimeoutMs.value.toLong()}ms")
            reset()
        }

        // Debug: say once why a queued axolotl isn't being started.
        val waitReason = when {
            readyQueue.isEmpty() -> null
            !canCatch -> "catching is off (Catch Key)"
            state != CoralotState.IDLE -> "busy: $state on #${target?.id}"
            else -> null
        }
        if (waitReason != lastWaitReason) {
            waitReason?.let { dbg("Queue of ${readyQueue.size} waiting: $it") }
            lastWaitReason = waitReason
        }

        when (state) {
            CoralotState.IDLE -> {
                if (!canCatch) return
                val next = readyQueue.removeFirstOrNull() ?: return
                if (!next.isAlive) return

                target = next
                dbg("Target #${next.id} ${"%.1f".format(player.distanceTo(next))}m, ${readyQueue.size} left")
                humanLook = HumanLook(
                    smoothTimeSeconds = (smoothnessMs.value / 1000.0).toFloat(),
                    maxDegreesPerSecond = maxTurnSpeed.value.toFloat(),
                    jitterDegrees = aimJitter.value.toFloat()
                )
                lastFrameNanos = 0L
                beginCatchDelay()
            }

            CoralotState.CATCH_DELAY -> {
                val axo = target ?: return reset()
                if (!axo.isAlive) return reset()

                val now = System.currentTimeMillis()
                val delayElapsed = now >= catchDeadline
                val forcedByTimeout = now >= aimHardDeadline

                if (forcedByTimeout || (delayElapsed && isLookingAt(player, axo, aimToleranceDeg.value.toFloat()))) {
                    attemptCatch(player, axo)
                }
            }

            CoralotState.CONFIRMING -> { /* waiting on chat */ }
        }
    }

    /** Catch Key: toggles catching (only outside menus). Turning it off mid-aim lets go of the camera right away. */
    private var lastWaitReason: String? = null
    private var lastStealthMsgAt = 0L

    /** Debug: when each close axolotl was first seen with no icon and nothing queued for it; -1 once reported. */
    private val silentSince = HashMap<Axolotl, Long>()

    /**
     * Debug: an axolotl within [SILENT_RANGE] for [SILENT_MS] that never got an icon nor a Stealth pick -- likely one
     * where Hypixel sent no message (e.g. another player already near it). Reported once per axolotl.
     */
    private fun reportSilent(player: LocalPlayer, nearby: List<Axolotl>, now: Long) {
        val quiet = nearby.filter {
            player.distanceTo(it) <= SILENT_RANGE && it !in withHolo && hologramTracked[it] != true &&
                    it !in readyQueue && it !== target
        }.toSet()
        silentSince.keys.retainAll(quiet)
        for (axo in quiet) {
            val since = silentSince.getOrPut(axo) { now }
            if (since < 0 || now - since < SILENT_MS) continue
            val others = mc.level?.players()?.filter { it !== player && it.distanceTo(axo) <= 16f }
                ?.joinToString { "${it.name.string} ${"%.1f".format(it.distanceTo(axo))}m" }?.ifEmpty { "none" }
            dbg("Silent #${axo.id} at ${"%.1f".format(player.distanceTo(axo))}m: no icon, no Stealth msg for ${SILENT_MS / 1000}s (players near it: $others)")
            silentSince[axo] = -1
        }
    }

    /** Debug: every axolotl within detect range + 3, nearest first, e.g. "3.6m, 7.2m icon". */
    private fun axolotlDistances(): String {
        val player = mc.player ?: return "-"
        return mc.level?.entitiesForRendering()?.filterIsInstance<Axolotl>()
            ?.filter { it.isAlive && player.distanceTo(it) <= detectRange.value + 3.0 }
            ?.sortedBy { player.distanceTo(it) }
            ?.joinToString { "%.1fm".format(player.distanceTo(it)) + if (it in withHolo) " icon" else "" }
            ?.ifEmpty { "none" } ?: "-"
    }

    private fun pollCatchKey() {
        val code = catchKey.value
        val window = GLFW.glfwGetCurrentContext()
        val down = requireKeybind.value && code > 0 && window != 0L && mc.screen == null &&
                GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS
        if (down && !keyWas) {
            armed = !armed
            if (!armed && state == CoralotState.CATCH_DELAY) reset()
            ActionBarHud.notify(Component.literal(if (armed) "§aCoralot catching ON" else "§cCoralot catching OFF"))
        }
        keyWas = down
    }

    /**
     * Watches every nearby axolotl's warning hologram, not just the current target.
     * The moment any axolotl's hologram goes from present to absent, it's queued up
     * to be caught — this is what lets multiple axolotls that clear around the same
     * time all get caught in turn, instead of only the one we happened to be looking at.
     */
    private fun updateHologramTracking(player: LocalPlayer, nearby: List<Axolotl>) {
        // stop tracking anything that's no longer nearby/alive, so it doesn't
        // falsely trigger "ready" if it wanders back into range later
        hologramTracked.keys.retainAll { it in nearby }

        for (axo in nearby) {
            val holoPresent = axo in withHolo
            val hadHolo = hologramTracked[axo] ?: false

            if (holoPresent) {
                // Debug: the distance an icon pops up at is the notice range.
                if (!hadHolo) {
                    val since = System.currentTimeMillis() - lastStealthMsgAt
                    dbg("Icon on #${axo.id} at ${"%.1f".format(player.distanceTo(axo))}m" +
                            if (since <= STEALTH_EXPIRE_MS) ", ${since}ms after Stealth msg" else "")
                }
                hologramTracked[axo] = true
            } else if (hadHolo) {
                hologramTracked.remove(axo)
                if (axo !== target && axo !in readyQueue) {
                    val slotFree = target == null && readyQueue.isEmpty()
                    if (catchMultipleTargets.value || slotFree) {
                        readyQueue.addLast(axo)
                        dbg("Icon cleared #${axo.id}, queued")
                    }
                }
            }
        }
    }

    /**
     * Picks the creature the Stealth message was about: one in range that has NOT got a warning icon (the others
     * noticed you, so they show one and are on cooldown), isn't already being caught, preferring the closest.
     */
    /**
     * Queues the creatures a Stealth message was about: ones in range with NO warning icon (the rest noticed you and
     * are on cooldown). Every axolotl inside the notice range either shows an icon or was stealthed, and the farthest
     * one with an icon shows that range, so every icon-free one within it is queued. Beyond it only the nearest
     * icon-free one is taken. Returns how many were queued.
     */
    private fun queueStealthTargets(player: LocalPlayer, nearby: List<Axolotl>): Int {
        val busy = target != null || readyQueue.isNotEmpty()
        if (busy && !catchMultipleTargets.value) return 0
        val free = nearby.filter { it !== target && it !in readyQueue && it !in withHolo }
            .sortedBy { player.distanceTo(it) }
        if (free.isEmpty()) return 0
        val noticeRange = nearby.filter { it in withHolo }.maxOfOrNull { player.distanceTo(it) } ?: 0f
        dbg("Stealth resolve: icon-free at ${free.joinToString { "%.1f".format(player.distanceTo(it)) }}m, " +
                if (noticeRange > 0f) "with icon up to ${"%.1f".format(noticeRange)}m" else "none with an icon (range unknown, taking nearest)")
        val picks = free.filter { player.distanceTo(it) <= noticeRange }.ifEmpty { listOf(free.first()) }
            .let { if (catchMultipleTargets.value) it else it.take(1) }
        for (axo in picks) {
            readyQueue.addLast(axo)
            stealthQueued.add(axo)
            dbg("Stealth -> #${axo.id} ${"%.1f".format(player.distanceTo(axo))}m (notice range ${"%.1f".format(noticeRange)}m, ${free.size} icon-free)")
        }
        return picks.size
    }

    /**
     * Which axolotls show a warning icon. Each named armor stand counts only for the axolotl nearest to it
     * (horizontally), so in a packed group one creature's icon doesn't hide its stealthed / cleared neighbour.
     */
    private fun hologramOwners(axolotls: List<Axolotl>): Set<Axolotl> {
        val level = mc.level ?: return emptySet()
        val owners = HashSet<Axolotl>()
        for (axo in axolotls) {
            for (stand in level.getEntitiesOfClass(ArmorStand::class.java, axo.boundingBox.inflate(0.6, 2.0, 0.6)) { it.customName != null }) {
                axolotls.minByOrNull { (it.x - stand.x).let { dx -> dx * dx } + (it.z - stand.z).let { dz -> dz * dz } }
                    ?.let(owners::add)
            }
        }
        return owners
    }

    /** Runs every rendered frame while CATCH_DELAY, using real elapsed time. */
    private fun smoothLookAtFrame(player: LocalPlayer, axo: Axolotl) {
        val look = humanLook ?: return

        val now = System.nanoTime()
        if (lastFrameNanos == 0L) {
            // first frame after acquiring a target: skip movement, just start the clock
            lastFrameNanos = now
            return
        }

        var deltaTime = (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now

        // guard against huge deltas from lag spikes / alt-tab so we don't get a giant snap
        if (deltaTime <= 0f || deltaTime > 0.1f) deltaTime = 1f / 60f

        val (targetYaw, targetPitch) = lookAngles(player, axo)

        val (newYaw, newPitch) = look.step(player.yRot, player.xRot, targetYaw, targetPitch, deltaTime)
        player.yRot = newYaw
        player.xRot = newPitch
    }

    private fun beginCatchDelay() {
        if (!catchDelayEnabled.value) {
            catchDeadline = System.currentTimeMillis()
        } else {
            val lo = catchDelayMinMs.value
            val hi = catchDelayMaxMs.value
            val (min, max) = if (lo <= hi) lo to hi else hi to lo // guard against min>max from UI sliders

            val delay = if (max <= 0.0) 0L else Random.nextLong(min.toLong(), (max.toLong() + 1L).coerceAtLeast(min.toLong() + 1L))
            catchDeadline = System.currentTimeMillis() + delay
        }

        // aim must converge by (delay + extra buffer) or we throw anyway regardless of aim
        aimHardDeadline = catchDeadline + maxAimWaitMs.value.toLong()
        state = CoralotState.CATCH_DELAY
    }

    private fun attemptCatch(player: LocalPlayer, axo: Axolotl) {
        val slotBeforeSwitch = player.inventory.selectedSlot

        if (!switchToNet(player)) {
            dbg("No \"$netItemName\" in hotbar")
            reset()
            return
        }
        if (restorePreviousItem.value) {
            previousSlot = slotBeforeSwitch
        }

        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        player.swing(InteractionHand.MAIN_HAND)
        dbg("Threw at #${axo.id}, aimed=${isLookingAt(player, axo, aimToleranceDeg.value.toFloat())}")

        state = CoralotState.CONFIRMING
        confirmDeadline = System.currentTimeMillis() + confirmTimeoutMs.value.toLong()
    }

    private fun switchToNet(player: LocalPlayer): Boolean {
        for (slot in 0..8) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            if (stack.hoverName.string.contains(netItemName, ignoreCase = true)) {
                player.inventory.setSelectedSlot(slot)
                return true
            }
        }
        return false
    }

    private fun onCatchConfirmed() {
        if (playSound.value) {
            val id = Identifier.tryParse(soundId)
            val sound: SoundEvent? = id?.let { BuiltInRegistries.SOUND_EVENT.get(it).orElse(null)?.value() }
            if (sound != null) {
            ced.cedclient.utils.Debug.line("Coralot Helper", "alert sound $soundId")
                mc.soundManager.play(SimpleSoundInstance.forUI(sound, 1.0f))
            }
        }

        if (showTitle.value) {
            mc.gui.setTitle(Component.literal(titleText.replace('&', '\u00A7')))
            mc.gui.setTimes(5, 40, 10)
        }

        reset()
    }

    private fun reset() {
        previousSlot?.let { slot ->
            mc.player?.inventory?.setSelectedSlot(slot)
        }
        previousSlot = null

        state = CoralotState.IDLE
        target = null
        humanLook = null
        lastFrameNanos = 0L
    }
}
//#endif

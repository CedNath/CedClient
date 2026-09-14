package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.phys.AABB
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap

object FishingHelper : Module(
    "FishingHelper",
    Category.Funqol,
    "Fishing helper with optimized scanning and human-like timing"
) {
    private val mc: Minecraft = Minecraft.getInstance()

    // --- UI settings ---
    private val serverReel = BooleanSetting("Server Reel", true)
    private val autoRecast = BooleanSetting("Auto Recast", true)

    private val randomizeTiming = BooleanSetting("Randomize Timing", true)
    private val randomizeRangeMs = NumberSetting("Randomize Range ms", 100.0, 0.0, 500.0, 10.0)

    private val reelDelayMs = NumberSetting("Reel Delay ms", 150.0, 0.0, 400.0, 10.0)
    private val recastDelayMs = NumberSetting("Recast Delay ms", 250.0, 0.0, 20000.0, 10.0)
    private val cooldownMs = NumberSetting("Cooldown ms", 600.0, 0.0, 2000.0, 10.0)
    private val detectRadiusBlocks = NumberSetting("Detect Radius (blocks)", 2.0, 0.5, 8.0, 0.5)

    // tick throttling (to avoid FPS drops)
    private var mainScanCounter = 0

    // pending actions
    private data class PendingReel(val bobId: Int, val executeAt: Long)
    private data class PendingRecast(val executeAt: Long)

    @Volatile private var pendingReel: PendingReel? = null
    @Volatile private var pendingRecast: PendingRecast? = null

    private val lastReelAt = ConcurrentHashMap<Int, Long>()

    init {
        listOf(randomizeRangeMs, reelDelayMs, recastDelayMs, cooldownMs, detectRadiusBlocks)
            .forEach { it.advanced = true }

        addSettings(
            serverReel,
            autoRecast,
            randomizeTiming,
            randomizeRangeMs,
            reelDelayMs,
            recastDelayMs,
            cooldownMs,
            detectRadiusBlocks
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!isEnabled) return@register
            tick(client)
        }
    }

    // -------------------------
    // Main tick (bobber + reel)
    // -------------------------
    private fun tick(client: Minecraft) {
        val player = client.player as? LocalPlayer ?: return
        val world = client.level ?: return

        // always process timers (cheap)
        processPendingReel(player, world)
        processPendingRecast(player)

        // throttle heavy scans: only every 3 ticks
        mainScanCounter++
        if (mainScanCounter % 3 != 0) return

        val searchRadius = 24.0
        val searchBox = AABB(
            player.x - searchRadius, player.y - searchRadius, player.z - searchRadius,
            player.x + searchRadius, player.y + searchRadius, player.z + searchRadius
        )

        val bobbers = world.getEntitiesOfClass(FishingHook::class.java, searchBox) { true }
        if (bobbers.isEmpty()) return

        val now = System.currentTimeMillis()
        val detectRadius = detectRadiusBlocks.value

        for (bob in bobbers) {
            val owner = bob.owner
            if (owner !== player) continue

            val last = lastReelAt[bob.id] ?: 0L
            if (now - last < cooldownMs.value.toLong()) continue

            val area = AABB(
                bob.x - detectRadius, bob.y - detectRadius, bob.z - detectRadius,
                bob.x + detectRadius, bob.y + detectRadius, bob.z + detectRadius
            )

            val stands = world.getEntitiesOfClass(ArmorStand::class.java, area) { s ->
                val name = s.customName ?: return@getEntitiesOfClass false
                name.string == "!!!"
            }

            if (stands.isEmpty()) continue

            val currentPending = pendingReel
            if (currentPending != null && currentPending.bobId == bob.id) break

            val delay = applyRandomization(reelDelayMs.value.toLong())
            pendingReel = PendingReel(bob.id, now + delay)
            if (FishinghelperConfig.debugLogging) {
                println("[FishingHelper] Scheduled reel for bobber ${bob.id} in $delay ms")
            }
            break
        }
    }

    private fun processPendingReel(player: LocalPlayer, world: net.minecraft.world.level.Level) {
        val pr = pendingReel ?: return
        val now = System.currentTimeMillis()
        if (now < pr.executeAt) return

        val searchRadius = 24.0
        val searchBox = AABB(
            player.x - searchRadius, player.y - searchRadius, player.z - searchRadius,
            player.x + searchRadius, player.y + searchRadius, player.z + searchRadius
        )

        val bob = world.getEntitiesOfClass(FishingHook::class.java, searchBox) { true }
            .firstOrNull { it.id == pr.bobId }

        if (bob != null) {
            performReel(player)
            lastReelAt[bob.id] = now

            if (autoRecast.value) {
                val delay = applyRandomization(recastDelayMs.value.toLong())
                pendingRecast = PendingRecast(now + delay)
                if (FishinghelperConfig.debugLogging) {
                    println("[FishingHelper] Scheduled recast in $delay ms")
                }
            }
        }

        pendingReel = null
    }

    private fun processPendingRecast(player: LocalPlayer) {
        val prc = pendingRecast ?: return
        val now = System.currentTimeMillis()
        if (now < prc.executeAt) return

        performCast(player)
        pendingRecast = null
        if (FishinghelperConfig.debugLogging) {
            println("[FishingHelper] Performed recast")
        }
    }

    // -------------------------
    // Helpers
    // -------------------------
    private fun applyRandomization(baseMs: Long): Long {
        if (!randomizeTiming.value) return baseMs.coerceAtLeast(0L)
        val range = randomizeRangeMs.value.toLong().coerceAtLeast(0L)
        if (range == 0L) return baseMs.coerceAtLeast(0L)
        val offset = Random.nextLong(-range, range + 1)
        return (baseMs + offset).coerceAtLeast(0L)
    }

    private fun performReel(player: LocalPlayer) {
        player.swing(InteractionHand.MAIN_HAND)
        if (serverReel.value) mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
    }

    private fun performCast(player: LocalPlayer) {
        if (serverReel.value) {
            mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
            player.swing(InteractionHand.MAIN_HAND)
        } else {
            player.swing(InteractionHand.MAIN_HAND)
        }
    }

    // -------------------------
    // Runtime setters (optional)
    // -------------------------
    fun setServerReel(enabled: Boolean) { serverReel.value = enabled }
    fun setAutoRecast(enabled: Boolean) { autoRecast.value = enabled }
    fun setRandomizeTiming(enabled: Boolean) { randomizeTiming.value = enabled }
    fun setRandomizeRangeMs(ms: Double) { randomizeRangeMs.value = ms.coerceAtLeast(0.0) }
    fun setReelDelayMs(ms: Double) { reelDelayMs.value = ms.coerceAtLeast(0.0) }
    fun setRecastDelayMs(ms: Double) { recastDelayMs.value = ms.coerceAtLeast(0.0) }
    fun setCooldownMs(ms: Double) { cooldownMs.value = ms.coerceAtLeast(0.0) }
    fun setDetectRadiusBlocks(r: Double) { detectRadiusBlocks.value = r.coerceAtLeast(0.5) }
}

object FishinghelperConfig {
    var debugLogging: Boolean = false
}
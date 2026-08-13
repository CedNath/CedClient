package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Leashable
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap

object LassoHelper : Module(
    "LassoHelper",
    Category.Funqol,
    "Auto-reels lassoed mobs when the REEL prompt appears"
) {
    private val mc: Minecraft = Minecraft.getInstance()

    // --- match strings ---
    private val reelText = "REEL"          // text to look for near our leashed stand
    private val heldItemMatch = "Lasso"    // substring that identifies the lasso item

    // --- UI settings ---
    private val randomizeTiming = BooleanSetting("Randomize Timing", true)
    private val randomizeRangeMs = NumberSetting("Randomize Range ms", 100.0, 0.0, 5000.0, 10.0)

    private val reelDelayMs = NumberSetting("Reel Delay ms", 150.0, 0.0, 20000.0, 50.0)
    private val cooldownMs = NumberSetting("Cooldown ms", 600.0, 0.0, 20000.0, 50.0)

    // how far from the player to look for OUR leashed cobweb stand (leash max range is ~10 blocks)
    private val leashSearchRadiusBlocks = NumberSetting("Leash Search Radius (blocks)", 16.0, 4.0, 32.0, 1.0)
    // how far from that cobweb stand to look for the REEL text
    private val reelProximityBlocks = NumberSetting("Reel Proximity (blocks)", 3.0, 0.5, 6.0, 0.5)

    private val debugLog = BooleanSetting("Debug Log", false)

    // tick throttling (to avoid FPS drops)
    private var scanCounter = 0

    // pending action
    private data class PendingReel(val reelEntityId: Int, val executeAt: Long)

    @Volatile private var pendingReel: PendingReel? = null

    private val lastReelAt = ConcurrentHashMap<Int, Long>()

    init {
        listOf(randomizeRangeMs, reelDelayMs, cooldownMs, debugLog)
            .forEach { it.advanced = true }

        addSettings(
            randomizeTiming,
            randomizeRangeMs,
            reelDelayMs,
            cooldownMs,
            leashSearchRadiusBlocks,
            reelProximityBlocks,
            debugLog
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!isEnabled) return@register
            tick(client)
        }
    }

    private fun tick(client: Minecraft) {
        val player = client.player as? LocalPlayer ?: return
        val world = client.level ?: return

        // always process the pending timer (cheap)
        processPendingReel(player, world)

        // throttle heavy scans: only every 3 ticks
        scanCounter++
        if (scanCounter % 3 != 0) return

        if (!isHoldingLasso(player)) return

        val currentPending = pendingReel
        if (currentPending != null) return // already have a reel queued

        val now = System.currentTimeMillis()

        // Step 1: find whatever entity is leashed directly to US.
        // (This is the cobweb stand briefly, then the mob itself once it attaches.)
        val myLeashedEntity = findMyLeashedEntity(player, world) ?: run {
            if (debugLog.value) println("[LassoHelper] Nothing leashed to us right now")
            return
        }

        // Step 2: within a tight radius of that entity, look for the REEL text
        // (it lives on a separate nearby armor stand, not the leashed entity itself).
        val reelEntity = findReelEntityNear(myLeashedEntity, world) ?: return

        val last = lastReelAt[reelEntity.id] ?: 0L
        if (now - last < cooldownMs.value.toLong()) return

        val delay = applyRandomization(reelDelayMs.value.toLong())
        pendingReel = PendingReel(reelEntity.id, now + delay)

        if (debugLog.value) {
            println("[LassoHelper] Found REEL near our leashed entity (${myLeashedEntity.id}) -> entity ${reelEntity.id}, scheduled in $delay ms")
        }
    }

    /** Finds any entity (armor stand OR mob) currently leashed directly to the player. */
    private fun findMyLeashedEntity(player: LocalPlayer, world: Level): Entity? {
        val radius = leashSearchRadiusBlocks.value
        val area = AABB(
            player.x - radius, player.y - radius, player.z - radius,
            player.x + radius, player.y + radius, player.z + radius
        )
        return world.getEntities(player, area) { e ->
            (e as? Leashable)?.leashHolder === player
        }.firstOrNull()
    }

    private fun findReelEntityNear(anchor: Entity, world: Level): Entity? {
        val radius = reelProximityBlocks.value
        val area = AABB(
            anchor.x - radius, anchor.y - radius, anchor.z - radius,
            anchor.x + radius, anchor.y + radius, anchor.z + radius
        )
        return world.getEntities(anchor, area) { e ->
            val name = e.customName ?: return@getEntities false
            name.string.contains(reelText, ignoreCase = true)
        }.firstOrNull()
    }

    private fun processPendingReel(player: LocalPlayer, world: Level) {
        val pr = pendingReel ?: return
        val now = System.currentTimeMillis()
        if (now < pr.executeAt) return

        // re-validate: we still need to be holding the lasso and still have something
        // leashed to us with the REEL entity nearby, in case it resolved in the meantime.
        val myLeashedEntity = findMyLeashedEntity(player, world)
        val stillValid = myLeashedEntity != null &&
                isHoldingLasso(player) &&
                findReelEntityNear(myLeashedEntity, world)?.id == pr.reelEntityId

        if (stillValid) {
            performReel(player)
            lastReelAt[pr.reelEntityId] = now
            if (debugLog.value) {
                println("[LassoHelper] Performed reel for entity ${pr.reelEntityId}")
            }
        } else if (debugLog.value) {
            println("[LassoHelper] Skipped reel for entity ${pr.reelEntityId} (no longer valid)")
        }

        pendingReel = null
    }

    private fun isHoldingLasso(player: LocalPlayer): Boolean {
        val stack = player.mainHandItem
        if (stack.isEmpty) return false
        return stack.hoverName.string.contains(heldItemMatch, ignoreCase = true)
    }

    private fun performReel(player: LocalPlayer) {

        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
    }

    private fun applyRandomization(baseMs: Long): Long {
        if (!randomizeTiming.value) return baseMs.coerceAtLeast(0L)
        val range = randomizeRangeMs.value.toLong().coerceAtLeast(0L)
        if (range == 0L) return baseMs.coerceAtLeast(0L)
        val offset = Random.nextLong(-range, range + 1)
        return (baseMs + offset).coerceAtLeast(0L)
    }
}
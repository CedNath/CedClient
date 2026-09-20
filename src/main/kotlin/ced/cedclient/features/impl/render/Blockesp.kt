package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.ui.clickgui.BlockFilterPopup
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Color
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.sqrt

data class ScannedBlock(
    val pos: BlockPos,
    val name: String,
    val distance: Double
)

/**
 * Same shape as EntityESP, but for blocks instead of mobs -- Select Blocks
 * opens a BlockFilterPopup listing every block in the registry, and only
 * blocks matching the active filter get scanned/rendered.
 *
 * Unlike EntityESP, an empty "Only" list here means "scan nothing" rather
 * than "scan everything": entities loaded nearby are a small, cheap set to
 * walk every tick, but "every block in render distance" is not -- see
 * scan() below. So this is deliberately an opt-in, Only-list-driven module:
 * pick specific block(s) (ore, a decoration block, whatever you're looking
 * for) rather than expecting it to outline the whole world.
 *
 * PERFORMANCE NOTE (why Max Distance can go much higher than the old 48 cap
 * without freezing the game, and why results still take a moment to appear
 * at high values):
 *
 * This is still a brute-force "walk every block position in a cube around
 * the player" scan, unlike e.g. Meteor's BlockESP, which never rescans a
 * volume at all -- it processes each chunk once when it loads and only
 * touches individual positions again on an actual block-change packet, so
 * its render distance is effectively free. Rebuilding that (chunk-load +
 * block-update mixins, a persistent per-chunk cache) is a real architecture
 * change, not a tuning knob, so it's not what this does.
 *
 * What IS fixed here, both real bottlenecks in the old version:
 *  1. The scan now runs on its own background thread (scanExecutor below)
 *     instead of synchronously on the client tick thread, so a slow scan
 *     produces stale-for-a-moment results, not a frozen game. The
 *     `scanning` flag makes this self-throttling: if a scan is still
 *     running when the next interval hits, tick() just skips submitting
 *     another one rather than piling up a queue.
 *  2. Per-position filter matching used to compute a translated display
 *     name string and run two linear contains() scans against the name
 *     lists for EVERY non-air block hit (i.e. most of the volume). That's
 *     now resolved to a plain `Set<Block>` once per scan (registry-sized,
 *     a few thousand entries -- trivial) via resolveTargetBlocks(), so the
 *     hot per-position check is a single hash-set lookup.
 *
 * Even off-thread, a 256-radius sphere is on the order of tens of millions
 * of block reads per pass -- expect that pass to take real wall-clock time
 * (seconds, not milliseconds), which is what the self-throttling above is
 * for. If you want truly "unbounded, instant" distance the Meteor way,
 * that needs the chunk-cache rewrite mentioned above, not just this.
 */
object BlockESP : Module(
    "BlockESP",
    Category.Render,
    "Highlights selected block types within range"
) {
    private val mc = Minecraft.getInstance()

    // --- UI settings ---
    private val maxDistance = NumberSetting(
        "Max Distance", 32.0, 128.0, 256.0, 8.0,
        "Very high values (100+) are a real, brute-force volume scan -- see the class doc. " +
                "Results will lag behind by however long a pass actually takes, not freeze the game."
    )
    private val scanIntervalTicks = NumberSetting("Scan Interval ticks", 20.0, 5.0, 100.0, 5.0)
    private val debugLog = BooleanSetting("Debug Log", false)
    private val showBoxes = BooleanSetting("Show Boxes", true)
    private val boxColor = ColorSetting(
        "Box Color",
        Color(255, 210, 51), // same gold-ish as the old hardcoded ACCENT (1.0, 0.82, 0.2)
        "Color of the outline box drawn around scanned blocks."
    )
    private val showTracers = BooleanSetting("Show Tracers", false)
    private val tracerColor = ColorSetting(
        "Tracer Color",
        Color(255, 210, 51),
        "Color of the tracer line drawn to scanned blocks."
    )
    private val showLabels = BooleanSetting("Show Labels", true)

    private val openBlockFilterMenu = ActionSetting("Select Blocks") {
        val mc = Minecraft.getInstance()
        (mc.screen as? ClickGUI)?.openPopup(BlockFilterPopup())
    }

    @Volatile
    var scannedBlocks: List<ScannedBlock> = emptyList()
        private set
    val maxDistanceBlocks: Double
        get() = maxDistance.value

    private var tickCounter = 0
    private var lastLogAt = 0L
    private val logIntervalMs = 3000L

    val boxesEnabled: Boolean get() = showBoxes.value
    val boxColorValue: Color get() = boxColor.value
    val tracersEnabled: Boolean get() = showTracers.value
    val tracerColorValue: Color get() = tracerColor.value
    val labelsEnabled: Boolean get() = showLabels.value

    val blockedNames: MutableSet<String> = CopyOnWriteArraySet()
    val onlyNames: MutableSet<String> = CopyOnWriteArraySet()

    private data class NameFilters(val blocked: List<String>, val only: List<String>)
    private val filtersGson = Gson()
    private val filtersFile: File by lazy {
        File(Minecraft.getInstance().gameDirectory, "cedclient/block_esp_filters.json")
    }

    fun blockName(name: String) { blockedNames.add(name); saveFilters() }
    fun unblockName(name: String) { blockedNames.removeAll { it.equals(name, ignoreCase = true) }; saveFilters() }
    fun onlyName(name: String) { onlyNames.add(name); saveFilters() }
    fun unOnlyName(name: String) { onlyNames.removeAll { it.equals(name, ignoreCase = true) }; saveFilters() }
    fun clearOnly() { onlyNames.clear(); saveFilters() }
    fun clearBlocked() { blockedNames.clear(); saveFilters() }

    private fun loadFilters() {
        try {
            if (filtersFile.exists()) {
                val type = object : TypeToken<NameFilters>() {}.type
                val loaded: NameFilters? = filtersGson.fromJson(filtersFile.readText(), type)
                if (loaded != null) {
                    blockedNames.clear()
                    blockedNames.addAll(loaded.blocked)
                    onlyNames.clear()
                    onlyNames.addAll(loaded.only)
                }
            }
        } catch (e: Exception) {
            println("[BlockESP] Failed to load name filters: ${e.message}")
        }
    }

    private fun saveFilters() {
        try {
            filtersFile.parentFile?.mkdirs()
            filtersFile.writeText(filtersGson.toJson(NameFilters(blockedNames.toList(), onlyNames.toList())))
        } catch (e: Exception) {
            println("[BlockESP] Failed to save name filters: ${e.message}")
        }
    }

    init {
        loadFilters()

        listOf(maxDistance, scanIntervalTicks, debugLog)
            .forEach { it.advanced = true }

        addSettings(
            maxDistance, scanIntervalTicks,
            showBoxes, boxColor, showTracers, tracerColor, showLabels,
            debugLog, openBlockFilterMenu
        )

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) return@register

            tickCounter++
            val interval = scanIntervalTicks.value.toInt().coerceAtLeast(1)
            if (tickCounter % interval == 0) {
                // self-throttling: if the previous pass is still running
                // (likely at high Max Distance values), just skip this
                // interval instead of queueing scans up behind it.
                if (!scanning) {
                    scanning = true
                    scanExecutor.submit {
                        try {
                            scan()
                        } catch (e: Exception) {
                            // Reading live level/chunk data from a background
                            // thread is technically racy against chunk
                            // load/unload -- swallow and just skip this pass
                            // rather than crashing the game over a transient
                            // read.
                            if (debugLog.value) println("[BlockESP] scan failed: ${e.message}")
                        } finally {
                            scanning = false
                        }
                    }
                }
            }

            if (debugLog.value) {
                maybeLog()
            }
        }
    }

    @Volatile
    private var scanning = false

    private val scanExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CedClient-BlockESP-Scan").apply { isDaemon = true }
    }

    /**
     * Resolves the active Blocked/Only name filters (which are plain
     * substring-of-display-name patterns, same as the popup UI) down to a
     * concrete Set<Block> ONCE per scan, by walking the registry (a few
     * thousand entries) instead of the scanned volume (potentially tens of
     * millions of positions). The per-position hot loop in scan() then does
     * a single hash-set lookup instead of a translated-string contains()
     * check against every name pattern.
     */
    private fun resolveTargetBlocks(): Set<Block> {
        if (onlyNames.isEmpty()) return emptySet()
        val only = onlyNames.toList()
        val blocked = blockedNames.toList()

        val result = mutableSetOf<Block>()
        for (block in BuiltInRegistries.BLOCK) {
            val displayName = block.name.string
            if (blocked.any { displayName.contains(it, ignoreCase = true) }) continue
            if (only.none { displayName.contains(it, ignoreCase = true) }) continue
            result.add(block)
        }
        return result
    }

    private fun scan() {
        val player = mc.player ?: return
        val level = mc.level ?: return

        val targetBlocks = resolveTargetBlocks()
        if (targetBlocks.isEmpty()) {
            // Nothing selected -- see class doc for why this doesn't fall
            // back to "show every block" the way EntityESP's empty-only
            // does for entities.
            scannedBlocks = emptyList()
            return
        }

        val radius = maxDistance.value.toInt()
        val radiusSq = (radius * radius).toDouble()
        val center = player.blockPosition()
        val results = mutableListOf<ScannedBlock>()

        for (pos in BlockPos.betweenClosed(
            center.offset(-radius, -radius, -radius),
            center.offset(radius, radius, radius)
        )) {
            // Cheap integer distance check before touching the block state --
            // skips ~48% of positions (cube vs sphere) for free.
            if (center.distSqr(pos) > radiusSq) continue

            val block = level.getBlockState(pos).block
            if (block !in targetBlocks) continue

            val distance = sqrt(player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))
            if (distance > maxDistance.value) continue

            results.add(ScannedBlock(pos.immutable(), block.name.string, distance))
            if (results.size >= MAX_RESULTS) break
        }

        scannedBlocks = results.sortedBy { it.distance }
    }

    private fun maybeLog() {
        val now = System.currentTimeMillis()
        if (now - lastLogAt < logIntervalMs) return
        lastLogAt = now

        val list = scannedBlocks
        val sb = StringBuilder()
        sb.appendLine("[BlockESP] ${list.size} blocks matched")

        if (list.isEmpty()) {
            sb.appendLine("  (none in range)")
        } else {
            for (b in list) {
                sb.appendLine("  %-25s %5.1fm  (${b.pos.x}, ${b.pos.y}, ${b.pos.z})".format(b.name, b.distance))
            }
        }

        println(sb.toString().trimEnd())
    }

    private const val MAX_RESULTS = 200
}
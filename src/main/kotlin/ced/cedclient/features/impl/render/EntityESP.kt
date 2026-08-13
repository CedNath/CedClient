package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.ESPFilterPopup
import ced.cedclient.ui.clickgui.MobFilterPopup
import ced.cedclient.ui.clickgui.PlayerFilterPopup
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import java.io.File
import kotlin.math.sqrt

enum class MobCategoryType {
    HOSTILE, PASSIVE, PLAYER
}

data class ScannedEntity(
    val entity: LivingEntity,
    val name: String,
    val category: MobCategoryType,
    val distance: Double,


    )

object EntityESP : Module(
    "EntityESP",
    Category.Render,
    "Scans server-loaded mobs and shows their nametags"
) {
    private val mc = Minecraft.getInstance()

    // --- UI settings ---
    private val showHostile = BooleanSetting("Show Hostile", true)
    private val showPassive = BooleanSetting("Show Passive", true)
    private val showPlayers = BooleanSetting("Show Players", true)
    private val maxDistance = NumberSetting("Max Distance", 256.0, 8.0, 1024.0, 8.0)
    private val scanIntervalTicks = NumberSetting("Scan Interval ticks", 5.0, 1.0, 40.0, 1.0)
    private val debugLog = BooleanSetting("Debug Log", false)
    private val showBoxes = BooleanSetting("Show Boxes", true)
    private val showTracers = BooleanSetting("Show Tracers", true)
    private val showLabels = BooleanSetting("Show Labels", true)

    // separate from the module's own on/off — lets you keep boxes/tracers/labels
    // running in-world while hiding just the summary panel, or vice versa.
    private val showHudPanel = BooleanSetting("Show HUD Panel", true)

    private val openMobFilterMenu = ActionSetting("Select Mobs") {
        val mc = net.minecraft.client.Minecraft.getInstance()
        (mc.screen as? ClickGUI)?.openPopup(MobFilterPopup())
    }

    private val openPlayerFilterMenu = ActionSetting("Select Players") {
        val mc = net.minecraft.client.Minecraft.getInstance()
        (mc.screen as? ClickGUI)?.openPopup(PlayerFilterPopup())
    }

    private val openCustomFilterMenu = ActionSetting("Select Custom") {
        val mc = net.minecraft.client.Minecraft.getInstance()
        (mc.screen as? ClickGUI)?.openPopup(ESPFilterPopup())
    }
    @Volatile
    var scannedEntities: List<ScannedEntity> = emptyList()
        private set
    val maxDistanceBlocks: Double
        get() = maxDistance.value

    private var tickCounter = 0
    private var lastLogAt = 0L
    private val logIntervalMs = 3000L

    val boxesEnabled: Boolean get() = showBoxes.value
    val tracersEnabled: Boolean get() = showTracers.value
    val labelsEnabled: Boolean get() = showLabels.value
    val hudPanelEnabled: Boolean get() = showHudPanel.value

    val blockedNames = mutableSetOf<String>()
    val onlyNames = mutableSetOf<String>()

    private data class NameFilters(val blocked: List<String>, val only: List<String>)
    private val filtersGson = Gson()
    private val filtersFile: File by lazy {
        File(Minecraft.getInstance().gameDirectory, "cedclient/entity_esp_filters.json")
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
            println("[EntityESP] Failed to load name filters: ${e.message}")
        }
    }

    private fun saveFilters() {
        try {
            filtersFile.parentFile?.mkdirs()
            filtersFile.writeText(filtersGson.toJson(NameFilters(blockedNames.toList(), onlyNames.toList())))
        } catch (e: Exception) {
            println("[EntityESP] Failed to save name filters: ${e.message}")
        }
    }
    init {
        loadFilters()

        listOf(maxDistance, scanIntervalTicks, debugLog, openMobFilterMenu, openPlayerFilterMenu, openCustomFilterMenu)
            .forEach { it.advanced = true }

        addSettings(
            showHostile, showPassive, showPlayers, maxDistance, scanIntervalTicks,
            showBoxes, showTracers, showLabels, showHudPanel,
            debugLog, openMobFilterMenu, openPlayerFilterMenu, openCustomFilterMenu
        )

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) return@register

            tickCounter++
            val interval = scanIntervalTicks.value.toInt().coerceAtLeast(1)
            if (tickCounter % interval != 0) return@register

            scan()

            if (debugLog.value) {
                maybeLog()
            }
        }
    }

    private fun scan() {
        val player = mc.player ?: return
        val level = mc.level ?: return


        val results = mutableListOf<ScannedEntity>()

        for (entity in level.entitiesForRendering()) {
            if (entity !is LivingEntity) continue
            if (entity === player) continue
            if (!entity.isAlive) continue

            val category = categorize(entity)
            when (category) {
                MobCategoryType.HOSTILE -> if (!showHostile.value) continue
                MobCategoryType.PASSIVE -> if (!showPassive.value) continue
                MobCategoryType.PLAYER -> if (!showPlayers.value) continue
            }
            val displayName = entity.name.string
            if (onlyNames.isNotEmpty() && onlyNames.none { displayName.contains(it, ignoreCase = true) }) continue
            if (blockedNames.any { displayName.contains(it, ignoreCase = true) }) continue

            val distance = sqrt(player.distanceToSqr(entity))
            if (distance > maxDistance.value) continue

            results.add(ScannedEntity(entity, entity.name.string, category, distance))
        }

        scannedEntities = results.sortedBy { it.distance }
    }

    private fun categorize(entity: LivingEntity): MobCategoryType {
        if (entity is Player) return MobCategoryType.PLAYER
        return if (entity.type.category.isFriendly) MobCategoryType.PASSIVE else MobCategoryType.HOSTILE
    }

    private fun maybeLog() {
        val now = System.currentTimeMillis()
        if (now - lastLogAt < logIntervalMs) return
        lastLogAt = now

        val list = scannedEntities
        val hostileCount = list.count { it.category == MobCategoryType.HOSTILE }
        val passiveCount = list.count { it.category == MobCategoryType.PASSIVE }
        val playerCount = list.count { it.category == MobCategoryType.PLAYER }

        val sb = StringBuilder()
        sb.appendLine("[EntityESP] ${list.size} entities  (Hostile: $hostileCount  Passive: $passiveCount  Player: $playerCount)")

        if (list.isEmpty()) {
            sb.appendLine("  (none in range)")
        } else {
            for (e in list) {
                sb.appendLine("  [%-8s] %-25s %5.1fm".format(e.category.name, e.name, e.distance))
            }
        }

        println(sb.toString().trimEnd())
    }
}
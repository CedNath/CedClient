//#if CHEAT
package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
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
    val style: EspStyle = EspStyle.ALL,
)

/**
 * Blocked / Only name lists (substring match) with a per-Only-entry [EspStyle], saved to [filterFileName].
 * Shared by Player ESP, Mob ESP and Item ESP; the filter popups talk to it through these methods.
 */
abstract class EspFilterModule(
    name: String,
    description: String,
    private val filterFileName: String,
) : Module(name, Category.Cheats, description) {
    val blockedNames = mutableSetOf<String>()
    val onlyNames = mutableSetOf<String>()
    /** Per Only-entry boxes / tracers / labels, keyed by the entry as typed. */
    private val styles = HashMap<String, EspStyle>()

    // styles is nullable: filter files saved before it existed don't have it.
    private data class NameFilters(val blocked: List<String>, val only: List<String>, val styles: Map<String, EspStyle>? = null)
    private val filtersGson = Gson()
    private val filtersFile: File by lazy { CedPaths.file(filterFileName) }

    fun blockName(name: String) { blockedNames.add(name); saveFilters() }
    fun unblockName(name: String) { blockedNames.removeAll { it.equals(name, ignoreCase = true) }; saveFilters() }
    fun onlyName(name: String) { onlyNames.add(name); saveFilters() }
    fun unOnlyName(name: String) {
        onlyNames.removeAll { it.equals(name, ignoreCase = true) }
        styles.keys.removeAll { it.equals(name, ignoreCase = true) }
        saveFilters()
    }

    /** Style of an Only entry (created on first use), or null for names that aren't one. */
    fun styleOf(name: String): EspStyle? = if (name in onlyNames) styles.getOrPut(name) { EspStyle() } else null
    fun clearOnly() { onlyNames.clear(); saveFilters() }
    fun clearBlocked() { blockedNames.clear(); saveFilters() }

    protected fun loadFilters() {
        try {
            if (filtersFile.exists()) {
                val type = object : TypeToken<NameFilters>() {}.type
                val loaded: NameFilters? = filtersGson.fromJson(filtersFile.readText(), type)
                if (loaded != null) {
                    blockedNames.clear()
                    blockedNames.addAll(loaded.blocked)
                    onlyNames.clear()
                    onlyNames.addAll(loaded.only)
                    styles.clear()
                    loaded.styles?.let { styles.putAll(it) }
                }
            }
        } catch (e: Exception) {
            println("[$name] Failed to load name filters: ${e.message}")
        }
    }

    fun saveFilters() {
        try {
            filtersFile.parentFile?.mkdirs()
            filtersFile.writeText(filtersGson.toJson(NameFilters(blockedNames.toList(), onlyNames.toList(), styles)))
        } catch (e: Exception) {
            println("[$name] Failed to save name filters: ${e.message}")
        }
    }
}

/** What Player ESP and Mob ESP have in common: scan the loaded living entities that [accepts], filter, box / tracer / label. */
abstract class EntityEspModule(
    name: String,
    description: String,
    filterFileName: String,
) : EspFilterModule(name, description, filterFileName) {
    private val mc = Minecraft.getInstance()

    protected abstract fun accepts(entity: LivingEntity): Boolean

    private val maxDistance = NumberSetting("Max Distance", 256.0, 8.0, 1024.0, 8.0)
    private val debugLog = BooleanSetting("Debug Log", false)
    private val showBoxes = BooleanSetting("Show Boxes", true)
    private val showTracers = BooleanSetting("Show Tracers", true)
    private val showLabels = BooleanSetting("Show Labels", true)
    private val labelScaleSetting = NumberSetting("Label Scale", 1.0, 0.5, 2.5, 0.1, "Size of the name labels (they keep this size at any distance).")
    private val labelHealthBarSetting = BooleanSetting("Label Health Bar", true)

    // separate from the module's own on/off — lets you keep boxes/tracers/labels
    // running in-world while hiding just the summary panel, or vice versa.
    private val showHudPanel = BooleanSetting("Show HUD Panel", true)

    protected val openCustomFilterMenu = ActionSetting("Select Custom") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(ESPFilterPopup(this))
    }

    @Volatile
    var scannedEntities: List<ScannedEntity> = emptyList()
        private set
    val maxDistanceBlocks: Double
        get() = maxDistance.value

    private var lastLogAt = 0L
    private val logIntervalMs = 3000L

    val boxesEnabled: Boolean get() = showBoxes.value
    val tracersEnabled: Boolean get() = showTracers.value
    val labelsEnabled: Boolean get() = showLabels.value
    val labelScale: Float get() = labelScaleSetting.value.toFloat()
    val labelHealthBar: Boolean get() = labelHealthBarSetting.value
    val hudPanelEnabled: Boolean get() = showHudPanel.value

    init {
        loadFilters()

        listOf(maxDistance, debugLog).forEach { it.advanced = true }

        addSettings(
            maxDistance,
            showBoxes, showTracers, showLabels, labelScaleSetting, labelHealthBarSetting, showHudPanel,
            debugLog
        )

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) {
                if (scannedEntities.isNotEmpty()) scannedEntities = emptyList()
                return@register
            }

            scan()

            if (debugLog.value) maybeLog()
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
            if (!accepts(entity)) continue

            val displayName = entity.name.string
            val onlyMatch = onlyNames.firstOrNull { displayName.contains(it, ignoreCase = true) }
            if (onlyNames.isNotEmpty() && onlyMatch == null) continue
            if (blockedNames.any { displayName.contains(it, ignoreCase = true) }) continue

            val distance = sqrt(player.distanceToSqr(entity))
            if (distance > maxDistance.value) continue

            val style = onlyMatch?.let { styleOf(it) } ?: EspStyle.ALL
            if (!style.box && !style.tracer && !style.label) continue
            results.add(ScannedEntity(entity, displayName, categorize(entity), distance, style))
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
        val sb = StringBuilder()
        sb.appendLine("[$name] ${list.size} entities")

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

object PlayerESP : EntityEspModule(
    "Player ESP",
    "Other players through walls, with health",
    "player_esp_filters.json",
) {
    private val openPlayerFilterMenu = ActionSetting("Select Players") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(PlayerFilterPopup())
    }

    override fun accepts(entity: LivingEntity) = entity is Player

    init {
        addSettings(openPlayerFilterMenu, openCustomFilterMenu)
    }
}

object MobESP : EntityEspModule(
    "Mob ESP",
    "Mobs through walls, like slayer bosses",
    "mob_esp_filters.json",
) {
    private val showHostile = BooleanSetting("Show Hostile", true)
    private val showPassive = BooleanSetting("Show Passive", true)
    private val openMobFilterMenu = ActionSetting("Select Mobs") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(MobFilterPopup())
    }

    override fun accepts(entity: LivingEntity): Boolean {
        if (entity is Player) return false
        return if (entity.type.category.isFriendly) showPassive.value else showHostile.value
    }

    init {
        addSettings(showHostile, showPassive, openMobFilterMenu, openCustomFilterMenu)
    }
}

/**
 * Not a module: the Player ESP + Mob ESP pair as one thing for the HUD panel, labels, renderer and the
 * `/cedclient esp` name-filter commands.
 */
object EntityESP {
    val modules: List<EntityEspModule> get() = listOf(PlayerESP, MobESP)

    val isEnabled: Boolean get() = modules.any { it.isEnabled }
    private val hudModules: List<EntityEspModule> get() = modules.filter { it.isEnabled && it.hudPanelEnabled }

    val hudPanelEnabled: Boolean get() = hudModules.isNotEmpty()
    val scannedEntities: List<ScannedEntity> get() = hudModules.flatMap { it.scannedEntities }
    val maxDistanceBlocks: Double get() = hudModules.maxOfOrNull { it.maxDistanceBlocks } ?: 0.0

    val blockedNames: Set<String> get() = modules.flatMapTo(linkedSetOf()) { it.blockedNames }
    val onlyNames: Set<String> get() = modules.flatMapTo(linkedSetOf()) { it.onlyNames }

    fun blockName(name: String) = modules.forEach { it.blockName(name) }
    fun unblockName(name: String) = modules.forEach { it.unblockName(name) }
    fun onlyName(name: String) = modules.forEach { it.onlyName(name) }
    fun unOnlyName(name: String) = modules.forEach { it.unOnlyName(name) }
    fun clearOnly() = modules.forEach { it.clearOnly() }
    fun clearBlocked() = modules.forEach { it.clearBlocked() }
}
//#endif

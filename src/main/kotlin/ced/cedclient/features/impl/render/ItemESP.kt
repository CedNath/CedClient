//#if CHEAT
package ced.cedclient.features.impl.render

import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.ItemFilterPopup
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.item.ItemEntity
import kotlin.math.sqrt

data class ScannedItem(
    val entity: ItemEntity,
    val name: String,
    val distance: Double,
    val style: EspStyle = EspStyle.ALL,
)

/**
 * Boxes round dropped items, through walls. Select Items picks which ones (matched on the item's display name);
 * with an empty Only list every dropped item in range is drawn.
 */
object ItemESP : EspFilterModule(
    "Item ESP",
    "Boxes on dropped items you care about",
    "item_esp_filters.json",
) {
    private val mc = Minecraft.getInstance()

    private val maxDistance = NumberSetting("Max Distance", 64.0, 8.0, 256.0, 8.0)
    private val showBoxes = BooleanSetting("Show Boxes", true)
    private val showTracers = BooleanSetting("Show Tracers", false)
    private val debugLog = BooleanSetting("Debug Log", false)

    private val openItemFilterMenu = ActionSetting("Select Items") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(ItemFilterPopup())
    }

    @Volatile
    var scannedItems: List<ScannedItem> = emptyList()
        private set

    val boxesEnabled: Boolean get() = showBoxes.value
    val tracersEnabled: Boolean get() = showTracers.value
    /** Box / tracer colour for items without a colour of their own in the Only list. */
    val defaultColor = Triple(1.0f, 0.82f, 0.2f)

    private var lastLogAt = 0L

    init {
        loadFilters()
        debugLog.advanced = true
        addSettings(maxDistance, showBoxes, showTracers, openItemFilterMenu, debugLog)

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) {
                if (scannedItems.isNotEmpty()) scannedItems = emptyList()
                return@register
            }
            scan()
            if (debugLog.value) maybeLog()
        }
    }

    private fun scan() {
        val player = mc.player ?: return
        val level = mc.level ?: return

        val results = mutableListOf<ScannedItem>()
        for (entity in level.entitiesForRendering()) {
            if (entity !is ItemEntity || entity.isRemoved) continue

            val displayName = entity.item.hoverName.string
            val onlyMatch = onlyNames.firstOrNull { displayName.contains(it, ignoreCase = true) }
            if (onlyNames.isNotEmpty() && onlyMatch == null) continue
            if (blockedNames.any { displayName.contains(it, ignoreCase = true) }) continue

            val distance = sqrt(player.distanceToSqr(entity))
            if (distance > maxDistance.value) continue

            val style = onlyMatch?.let { styleOf(it) } ?: EspStyle.ALL
            if (!style.box && !style.tracer) continue
            results.add(ScannedItem(entity, displayName, distance, style))
        }
        scannedItems = results.sortedBy { it.distance }
    }

    private fun maybeLog() {
        val now = System.currentTimeMillis()
        if (now - lastLogAt < 3000L) return
        lastLogAt = now
        val list = scannedItems
        println("[Item ESP] ${list.size} items" + list.joinToString("") { "\n  %-25s %5.1fm".format(it.name, it.distance) })
    }
}
//#endif

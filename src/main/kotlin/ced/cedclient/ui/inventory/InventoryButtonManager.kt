package ced.cedclient.ui.inventory

import ced.cedclient.features.impl.misc.InventoryButtons
import ced.cedclient.utils.Debug
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

import java.io.File

object InventoryButtonManager {

    @JvmField
    val buttons = mutableListOf<InvButton>()

    private val gson = Gson()

    private val configFile: File by lazy {
        File(Minecraft.getInstance().gameDirectory, "cedclient/inventory_buttons.json")
    }

    private var loadedOnce = false

    init {
        Debug.log("InventoryButtonManager INIT")
    }

    // ───────────────────────── PUBLIC API ─────────────────────────

    fun ensureLoaded() {


        if (!loadedOnce) {
            load()
            loadedOnce = true
        }
    }

    fun save() {
        ensureLoaded()

        try {
            configFile.parentFile.mkdirs()

            // ⭐ Manual JSON map so we can serialize ResourceLocation safely
            val jsonList = buttons.map { btn ->
                mapOf(
                    "name" to btn.name,
                    "xOff" to btn.xOff,
                    "yOff" to btn.yOff,
                    "width" to btn.width,
                    "height" to btn.height,
                    "command" to btn.command,
                    "icon" to btn.icon?.toString(),
                    "iconIsItem" to btn.iconIsItem,
                    "showTextOnHover" to btn.showTextOnHover,
                    "hideBlankName" to btn.hideBlankName
                )
            }

            val json = gson.toJson(jsonList)

            Debug.log("JSON SAVE CONTENT = $json")

            configFile.writeText(json)
            Debug.log("InventoryButtonManager: Saved ${buttons.size} buttons")
        } catch (e: Exception) {
            Debug.log("InventoryButtonManager: Failed to save buttons")
            e.printStackTrace()
        }
    }


    fun load() {
        try {
            println("BUTTON JSON LOADED")
            Debug.log("LOAD() CALLED — loadedOnce=$loadedOnce")

            if (!configFile.exists()) {
                Debug.log("InventoryButtonManager: No config file, starting empty")
                return
            }

            val json = configFile.readText()
            Debug.log("JSON LOAD CONTENT = $json")

            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val rawList: List<Map<String, Any>> = gson.fromJson(json, type) ?: emptyList()

            buttons.clear()

            for (entry in rawList) {
                val iconStr = entry["icon"] as? String
                val icon = iconStr?.let {
                    runCatching { Identifier.parse(it) }.getOrNull()
                }

                val btn = InvButton(
                    name = entry["name"] as String,
                    xOff = (entry["xOff"] as Number).toInt(),
                    yOff = (entry["yOff"] as Number).toInt(),
                    width = (entry["width"] as Number).toInt(),
                    height = (entry["height"] as Number).toInt(),
                    command = entry["command"] as String,
                    icon = icon,
                    iconIsItem = entry["iconIsItem"] as? Boolean ?: false,
                    showTextOnHover = entry["showTextOnHover"] as? Boolean ?: true,
                    hideBlankName = entry["hideBlankName"] as? Boolean ?: true
                )

                buttons += btn
            }

            Debug.log("InventoryButtonManager: Loaded ${buttons.size} buttons")
        } catch (e: Exception) {
            Debug.log("InventoryButtonManager: Failed to load buttons")
            e.printStackTrace()
        }
    }

    // ───────────────────────── RUNTIME HELPERS ─────────────────────────

    fun getButtonAt(mouseX: Int, mouseY: Int, containerLeft: Int, containerTop: Int): InvButton? {
        ensureLoaded()

        for (btn in buttons.asReversed()) {
            val bx = containerLeft + btn.xOff
            val by = containerTop + btn.yOff

            if (mouseX in bx..(bx + btn.width) &&
                mouseY in by..(by + btn.height)
            ) {
                return btn
            }
        }
        return null
    }

    fun removeButton(button: InvButton) {
        ensureLoaded()
        buttons.remove(button)
        save()
    }

    fun addButton(button: InvButton) {
        ensureLoaded()
        buttons.add(button)
        save()
    }

    fun runButtonCommand(button: InvButton) {
        ensureLoaded()

        val mc = Minecraft.getInstance()

        // ⭐ Hard gate: module must be ON
        if (!InventoryButtons.enabled) return

        // ⭐ Hard gate: only in player inventory
        if (mc.screen !is net.minecraft.client.gui.screens.inventory.InventoryScreen) return

        val player = mc.player ?: return

        if (button.command.isNotBlank()) {
            player.connection?.sendCommand(button.command)
        }
    }

}

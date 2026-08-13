package ced.cedclient.ui.clickgui

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

data class PanelPos(val x: Double, val y: Double)

object PanelConfig {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile = File("config/panels.json")

    // Default positions, spaced using Panel's own width/gap constants so
    // they can never end up narrower than the panels actually are (this was
    // the root cause of the Render/Misc columns overlapping — they were
    // only ~10px apart here before, on a 260px-wide panel).
    private val columnStep = (Panel.WIDTH + Panel.GAP).toDouble()
    private val defaultMap: Map<String, PanelPos> = mapOf(
        "Funqol" to PanelPos(15.0, 15.0),
        "Render" to PanelPos(15.0 + columnStep, 15.0),
        "Misc" to PanelPos(15.0 + columnStep * 2, 15.0)
    )

    fun ensureDefaults() {
        if (!configFile.exists()) {
            write(defaultMap)
            return
        }

        val existing = read() ?: emptyMap()
        val merged = defaultMap + existing
        write(merged)
    }

    fun read(): Map<String, PanelPos>? {
        return try {
            val type = object : TypeToken<Map<String, PanelPos>>() {}.type
            gson.fromJson<Map<String, PanelPos>>(configFile.readText(), type)
        } catch (e: Exception) {
            null
        }
    }

    fun write(map: Map<String, PanelPos>) {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(gson.toJson(map))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Apply saved positions to the provided panels list.
     * Call this after you create your panels.
     */
    fun applyTo(panels: List<Panel>) {
        val map = read() ?: defaultMap
        for (panel in panels) {
            val name = panel.category.name
            val pos = map[name]
            if (pos != null) panel.setPosition(pos.x.toFloat(), pos.y.toFloat())
        }
    }

    /**
     * Save the current positions of the provided panels to disk.
     */
    fun saveAll(panels: List<Panel>) {
        val map = panels.associate { it.category.name to PanelPos(it.x.toDouble(), it.y.toDouble()) }
        write(map)
    }

    /**
     * Overwrite the panels config with the built-in defaults and persist to disk.
     * Then apply those defaults to the provided panels list if one is supplied.
     */
    fun resetToDefaults(panels: List<Panel>? = null) {
        // FIX: this used to write {"panels": {"Funqol": {"x":.., "y":..}, ...}}
        // — nested under a "panels" key, with each entry as a raw x/y map.
        // Everywhere else (read/write/applyTo/saveAll/ensureDefaults) expects
        // a FLAT {"Funqol": {"x":.., "y":..}, ...} map at the top level, same
        // shape as PanelPos. The mismatch meant a saved reset wouldn't be
        // read back correctly (read() would fail to parse it, silently
        // falling back to in-memory defaults) — it only "worked" for the
        // current session and self-healed on the next ensureDefaults() call.
        // Writing the same flat shape as everything else fixes that for good.
        write(defaultMap)
        panels?.let { applyTo(it) }
    }
}
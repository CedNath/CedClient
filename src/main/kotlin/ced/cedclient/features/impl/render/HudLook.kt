package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Per-HUD "Background" choice made in the HUD editor's toolbar, kept in hud-looks.json
 * ({"Ragnarock Timer": {"background": true}}). A HUD with no entry keeps its default look. Older files may also hold
 * "outline" entries from the removed Text Outline toggle; they are ignored and dropped on the next save.
 * Modules with their own Background setting are toggled through that setting instead (see HudCard).
 */
object HudLook {
    private val file by lazy { CedPaths.hud("looks") }
    private val background = HashMap<String, Boolean>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            JsonParser.parseString(file.readText()).asJsonObject.entrySet().forEach { (label, v) ->
                v.asJsonObject.get("background")?.let { background[label] = it.asBoolean }
            }
        }
    }

    /** The editor's choice for [label]'s card, null = never set (the HUD's default). */
    fun background(label: String?): Boolean? { load(); return background[label] }

    fun setBackground(label: String, on: Boolean) { load(); background[label] = on; save() }

    /** Forget every per-HUD choice (the one-time look reset); returns how many there were. */
    fun clear(): Int { load(); val n = background.size; background.clear(); save(); return n }

    private fun save() {
        val json = JsonObject()
        for ((label, on) in background) json.add(label, JsonObject().also { it.addProperty("background", on) })
        runCatching { file.parentFile?.mkdirs(); file.writeText(Gson().toJson(json)) }
    }
}

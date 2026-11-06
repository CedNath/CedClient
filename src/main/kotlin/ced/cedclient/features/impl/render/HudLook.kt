package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Per-HUD "Background" and "Text Outline" choices made in the HUD editor's toolbar, kept in hud-looks.json
 * ({"Ragnarock Timer": {"background": true, "outline": true}}). A HUD with no entry keeps its default look.
 * Modules with their own Background / Text Outline setting are toggled through that setting instead (see HudCard).
 */
object HudLook {
    private val file by lazy { CedPaths.hud("looks") }
    private val background = HashMap<String, Boolean>()
    private val outline = HashMap<String, Boolean>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            JsonParser.parseString(file.readText()).asJsonObject.entrySet().forEach { (label, v) ->
                val o = v.asJsonObject
                o.get("background")?.let { background[label] = it.asBoolean }
                o.get("outline")?.let { outline[label] = it.asBoolean }
            }
        }
    }

    /** The editor's choice for [label]'s card, null = never set (the HUD's default). */
    fun background(label: String?): Boolean? { load(); return background[label] }
    fun outline(label: String?): Boolean { load(); return outline[label] == true }

    fun setBackground(label: String, on: Boolean) { load(); background[label] = on; save() }
    fun setOutline(label: String, on: Boolean) { load(); outline[label] = on; save() }

    private fun save() {
        val json = JsonObject()
        for (label in background.keys + outline.keys) {
            json.add(label, JsonObject().also { o ->
                background[label]?.let { o.addProperty("background", it) }
                outline[label]?.let { o.addProperty("outline", it) }
            })
        }
        runCatching { file.parentFile?.mkdirs(); file.writeText(Gson().toJson(json)) }
    }
}

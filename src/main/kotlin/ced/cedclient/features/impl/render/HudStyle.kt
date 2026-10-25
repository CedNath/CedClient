package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import ced.cedclient.features.impl.misc.Theme
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Look of the HUD cards drawn through [HudCard]. One global choice (Theme > HUD Style) and an optional override per
 * HUD, set from the HUD editor's toolbar and kept in hud-styles.json ({"Ragnarock Timer": "Glass"}).
 */
object HudStyle {
    val names = listOf("Classic", "Glass", "Accent Top", "Rail", "Flat")

    private val file by lazy { CedPaths.hud("styles") }
    private val overrides = HashMap<String, String>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            JsonParser.parseString(file.readText()).asJsonObject.entrySet().forEach { (k, v) -> if (v.asString in names) overrides[k] = v.asString }
        }
    }

    /** The style [label]'s card uses: its override, else the global one. */
    fun of(label: String?): String {
        load()
        return overrides[label] ?: Theme.hudStyle.value.takeIf { it in names } ?: names.first()
    }

    /** True if [label] has its own style instead of following the global one. */
    fun hasOverride(label: String): Boolean { load(); return label in overrides }

    /** Next style for [label]: follow global -> each style in turn -> back to following global. */
    fun cycle(label: String) {
        load()
        val cur = overrides[label]
        val next = if (cur == null) names.first() else names.getOrNull(names.indexOf(cur) + 1)
        if (next == null) overrides.remove(label) else overrides[label] = next
        save()
    }

    private fun save() {
        val json = JsonObject().also { o -> overrides.forEach { (k, v) -> o.addProperty(k, v) } }
        runCatching { file.writeText(Gson().toJson(json)) }
    }
}

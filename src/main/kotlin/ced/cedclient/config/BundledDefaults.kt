package ced.cedclient.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The settings and HUD spots a fresh install starts with, made by scripts/make-defaults.mjs from a played-in config.
 * Two files: cedclient_defaults.json, plus cedclient_defaults_cheat.json (not in the Legit jar). Whatever the player
 * saved wins; these only fill what is not saved yet. On / off state and keybinds are never in them.
 */
object BundledDefaults {
    /** module name -> (setting name -> value) */
    val modules: JsonObject
    /** HUD key -> that HUD's json, same shape as hud/huds.json */
    val huds: JsonObject

    init {
        val m = JsonObject()
        val h = JsonObject()
        for (file in listOf("/cedclient_defaults.json", "/cedclient_defaults_cheat.json")) {
            val root = runCatching {
                BundledDefaults::class.java.getResourceAsStream(file)?.use { JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject }
            }.getOrNull() ?: continue
            root.getAsJsonObject("modules")?.let { merge(m, it) }
            root.getAsJsonObject("huds")?.let { merge(h, it) }
        }
        modules = m
        huds = h
    }

    /** Objects are merged key by key (the layout's panels come from both files), anything else is replaced. */
    private fun merge(into: JsonObject, from: JsonObject) {
        for ((key, value) in from.entrySet()) {
            val old = into.get(key)
            if (old != null && old.isJsonObject && value.isJsonObject) merge(old.asJsonObject, value.asJsonObject) else into.add(key, value.deepCopy())
        }
    }
}

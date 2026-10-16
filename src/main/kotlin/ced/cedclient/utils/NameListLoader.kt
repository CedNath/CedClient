package ced.cedclient.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mojang.authlib.minecraft.client.MinecraftClient
import java.io.InputStreamReader
import java.lang.reflect.Type
import java.util.Collections

object NameListLoader {
    private var creatureMessages: Map<String, String> = Collections.emptyMap()

    fun load() {
        try {
            val resource = MinecraftClient::class.java.classLoader.getResourceAsStream("sea_creatures.json")
            if (resource == null) {
                System.err.println("[FishingHelper] sea_creatures.json not found in resources.")
                return
            }
            InputStreamReader(resource).use { reader ->
                val type: Type = object : TypeToken<Map<String, String>>() {}.type
                creatureMessages = Gson().fromJson(reader, type) ?: Collections.emptyMap()
                println("[FishingHelper] Loaded ${creatureMessages.size} creatures.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            creatureMessages = Collections.emptyMap()
        }
    }

    fun getMessage(key: String): String? = creatureMessages[key]

    fun findMatchingCreatureKey(normalizedName: String?): String? {
        if (normalizedName.isNullOrBlank()) return null
        // iterate keys; keys should be normalized the same way as chat
        for (key in creatureMessages.keys) {
            if (normalizedName.contains(key.lowercase())) return key
        }
        return null
    }

    // Compiled once: this runs for every entity on every Entity ESP scan.
    private val CODES = Regex("§.")
    private val LEVEL = Regex("\\[Lv\\d+\\]        ")
    private val HP = Regex("\\d+[kKmM]?/\\d+[kKmM]?❤")
    private val SYMBOLS = Regex("[^\\p{L}\\p{N}\\p{Z}]")
    private val SPACES = Regex("\\s{2,}")

    private fun normalizeName(raw: String?): String {
        if (raw == null) return ""
        var s = raw.replace(CODES, "")                      // strip color codes
        s = s.replace(LEVEL, " ")                  // remove [Lv123]
        s = s.replace(HP, " ")      // remove hp tags
        s = s.replace(SYMBOLS, " ")         // keep letters/numbers/space
        s = s.replace(SPACES, " ").trim()                // collapse whitespace
        return s.lowercase()
    }

}

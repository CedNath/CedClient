package ced.cedclient.features.settings

import com.google.gson.Gson
import com.google.gson.JsonElement

/**
 * Implemented by settings that ConfigManager should persist. read()/write()
 * let each setting type control its own JSON shape (e.g. NumberSetting writes
 * a JsonPrimitive number, a future ColorSetting could write a hex string)
 * instead of ConfigManager needing a type-specific branch for every setting.
 */
internal interface Saving {
    /** Update this setting's value from saved JSON. */
    fun read(element: JsonElement, gson: Gson)

    /** Produce the JSON to persist for this setting. */
    fun write(gson: Gson): JsonElement
}
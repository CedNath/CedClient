package ced.cedclient.features.impl.misc

import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.features.settings.Saving
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.utils.Color
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.UUID

/**
 * One user-made chat alert (Chat Features > Alerts).
 * Its options are ordinary settings so the editor is a SettingsPopup and saving reuses each setting's read/write.
 */
class ChatAlert(val id: String = UUID.randomUUID().toString()) {
    var enabled = true

    val triggers = TextSetting("Trigger Words", "", "Comma-separated, whole words, any case.", maxLength = 128)
    val regex = BooleanSetting("Regex", false, "Trigger Words is one regex instead (matched anywhere in the line).")
    val exclude = TextSetting("Ignore If Contains", "", "Comma-separated: no alert when the line has any of these.", maxLength = 128)
    val ignoreOwn = BooleanSetting("Ignore Own Messages", true)

    val highlight = BooleanSetting("Highlight", true)
    val color = ColorSetting("Highlight Color", Color(255, 196, 0)).withDependency { highlight.value }
    val replaceLine = TextSetting("Replace Line With", "", "Empty keeps the line. & colour codes, {line} = the original line.", maxLength = 128)
    val copyLine = BooleanSetting("Copy Line To Clipboard", false)

    val sound = DropdownSetting("Sound", ChatPing.soundPresets.keys.toList() + "Custom", "Bell")
    val customSound = TextSetting("Custom Sound ID", "minecraft:entity.player.levelup", maxLength = 64)
        .withDependency { sound.value == "Custom" }
    val volume = NumberSetting("Volume", 1.0, 0.0, 1.0, 0.05).withDependency { sound.value != "None" }
    val pitch = NumberSetting("Pitch", 1.0, 0.5, 2.0, 0.05).withDependency { sound.value != "None" }

    val hudMessage = TextSetting("HUD Message", "", "Shown on the Chat Alerts HUD. & colour codes, {line} = the chat line. Empty = none.", maxLength = 128)
    val hudSeconds = NumberSetting("HUD Seconds", 3.0, 1.0, 10.0, 0.5).withDependency { hudMessage.value.isNotBlank() }
    val cooldown = NumberSetting("Cooldown (s)", 0.0, 0.0, 60.0, 0.5, "Minimum time between two alerts.")

    val test = ActionSetting("Test") { ChatPing.fire(this, "Someone: this is a test line") }

    private val saved: List<RenderableSetting<*>> = listOf(
        triggers, regex, exclude, ignoreOwn, highlight, color, replaceLine, copyLine,
        sound, customSound, volume, pitch, hudMessage, hudSeconds, cooldown,
    )

    var lastFired = 0L

    val label: String get() = triggers.value.ifBlank { "(no trigger words)" }

    // Rebuilt only when the trigger / exclude text changes.
    private var matchKey = ""
    private var matchRegex: Regex? = null
    private var excludeWords: List<String> = emptyList()

    /** Regex for the trigger, or null when there is nothing (valid) to match. */
    fun matcher(): Regex? {
        val key = "${regex.value}\u0000${triggers.value}"
        if (key != matchKey) {
            matchKey = key
            matchRegex = if (regex.value) {
                runCatching { Regex(triggers.value, RegexOption.IGNORE_CASE) }.getOrNull()?.takeIf { triggers.value.isNotBlank() }
            } else {
                ChatPing.wordRegex(triggers.value.split(','))
            }
        }
        return matchRegex
    }

    fun excluded(text: String): Boolean {
        val words = exclude.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (words != excludeWords) excludeWords = words
        return excludeWords.any { text.contains(it, ignoreCase = true) }
    }

    fun toJson(gson: Gson): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("enabled", enabled)
        val settings = JsonObject()
        for (s in saved) if (s is Saving) settings.add(s.name, s.write(gson))
        add("settings", settings)
    }

    companion object {
        fun fromJson(obj: JsonObject, gson: Gson): ChatAlert {
            val alert = ChatAlert(obj.get("id")?.asString ?: UUID.randomUUID().toString())
            alert.enabled = obj.get("enabled")?.asBoolean ?: true
            val settings = obj.getAsJsonObject("settings") ?: return alert
            for (s in alert.saved) {
                val element = settings.get(s.name) ?: continue
                if (s is Saving) runCatching { s.read(element, gson) }
            }
            return alert
        }
    }
}

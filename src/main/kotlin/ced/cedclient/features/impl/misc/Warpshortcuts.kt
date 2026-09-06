package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.client.Minecraft
import java.io.File

/**
 * One warp shortcut: typing "/<alias>" sends "/<target>" to the server
 * instead. `target` never includes a leading slash (Fabric's command
 * events don't use one either).
 */
data class WarpShortcut(
    val alias: String,
    val target: String,
    var enabled: Boolean = true,
    val custom: Boolean = false
)

/**
 * SkyHanni-style warp shortcuts -- "/dh" instead of "/warp dh", etc.
 *
 * IMPLEMENTATION NOTE: this rewrites the command via Fabric's
 * ClientSendMessageEvents.MODIFY_COMMAND hook, NOT a Brigadier client-command
 * registration (the ClientCommands.literal(...) approach CedClientCommand
 * uses). That's deliberate:
 *
 * 1. Brigadier client commands are only (re)built once per
 *    ClientCommandRegistrationCallback firing -- same tree CedClientCommand
 *    itself builds -- so a shortcut added mid-session wouldn't take effect
 *    until reconnecting.
 * 2. MODIFY_COMMAND fires on the raw string the player typed, before it's
 *    dispatched anywhere, so /cedclient warp add works immediately without
 *    a rejoin, and unmatched words are returned untouched -- no risk of
 *    shadowing a real Hypixel or client command that happens to share a
 *    name we don't recognize.
 *
 * Module enabled/disabled is the master switch -- rewrite() is a no-op
 * while off.
 *
 * ONE THING TO VERIFY ON YOUR END: ClientSendMessageEvents.ModifyCommand's
 * single abstract method is named `modifySendCommandMessage(String)` by
 * strong naming symmetry with the confirmed `modifySendChatMessage` (chat)
 * and `allowSendCommandMessage` (command-allow) methods elsewhere in that
 * same class -- I couldn't get a direct fetch of that exact interface's
 * javadoc page to 100% confirm the name, so if this doesn't compile, check
 * your fabric-message-api-v1 sources for the actual method name and swap
 * it in (the lambda body doesn't need to change either way).
 */
object WarpShortcuts : Module(
    "WarpShortcuts",
    Category.Misc,
    "Type /<alias> instead of /warp <alias>, e.g. /dh -> /warp dh",
    defaultEnabled = true
) {

    // Mirrors SkyHanni-REPO's constants/Warps.json warpCommands array (85
    // entries, checked Sep 2026) -- every one of these defaults to
    // "warp <alias>" verbatim, the same mapping SkyHanni uses.
    private val defaultAliases: List<String> = listOf(
        "arachne", "backwater", "base", "basecamp", "barn", "bayou", "camp",
        "carnival", "castle", "ch", "cn", "crimson", "crypt", "crypts",
        "crystals", "da", "deep", "deeper", "desert", "dh", "dhub", "dmines",
        "drag", "dragons", "dungeons", "dungeon_hub", "dwarves", "elizabeth",
        "end", "farming", "foraging", "forge", "galatea", "garden", "glacite",
        "gold", "gt", "hollows", "home", "howl", "howling_cave", "hub",
        "island", "isle", "jerry", "jungle", "kuudra", "loch", "mines",
        "mound", "murk", "murkwater", "museum", "nest", "nether", "nuc",
        "nucleus", "park", "rift", "sepulture", "skull", "smold",
        "smoldering", "smoldering_tomb", "spider", "spiders", "stonks",
        "taylor", "the_rift", "top", "tower", "trap", "trapper", "trees",
        "tunnel", "tunnels", "village", "void", "winter", "wiz", "wizard",
        "wizard_tower", "workshop"
    )

    private val defaultEntries: List<WarpShortcut> =
        defaultAliases.map { WarpShortcut(it, "warp $it", enabled = true, custom = false) }

    private val entries: MutableList<WarpShortcut> = mutableListOf()

    /** Snapshot for a future config popup / `/cedclient warp list`. */
    fun currentEntries(): List<WarpShortcut> = entries.sortedBy { it.alias }

    /**
     * Adds (or overwrites) a custom shortcut. `target` is the full command
     * sent to the server, no leading slash needed -- doesn't have to be a
     * /warp at all (e.g. addCustom("pa", "p accept") for a party-accept
     * shortcut).
     */
    fun addCustom(alias: String, target: String) {
        val a = alias.trim().lowercase()
        val t = target.trim().removePrefix("/")
        if (a.isEmpty() || t.isEmpty()) return
        entries.removeAll { it.alias == a } // overwrite, whether it was default or custom
        entries.add(WarpShortcut(a, t, enabled = true, custom = true))
        save()
    }

    /** Removes a custom shortcut. If it was shadowing a default alias, the default comes back. */
    fun removeCustom(alias: String) {
        val a = alias.trim().lowercase()
        val wasShadowingDefault = defaultAliases.contains(a) && entries.any { it.alias == a && it.custom }
        entries.removeAll { it.alias == a && it.custom }
        if (wasShadowingDefault && entries.none { it.alias == a }) {
            entries.add(defaultEntries.first { it.alias == a }.copy())
        }
        save()
    }

    fun setEnabled(alias: String, value: Boolean) {
        entries.firstOrNull { it.alias == alias.trim().lowercase() }?.enabled = value
        save()
    }

    /**
     * Called from ClientSendMessageEvents.MODIFY_COMMAND. `command` is the
     * raw string the player sent (no leading slash, may include args).
     * Returns it unchanged unless the first word matches an enabled alias
     * exactly -- so "/dh" rewrites but "/dhsomethingelse" doesn't.
     */
    fun rewrite(command: String): String {
        if (!isEnabled) return command
        val firstSpace = command.indexOf(' ')
        val word = (if (firstSpace == -1) command else command.substring(0, firstSpace)).lowercase()
        val shortcut = entries.firstOrNull { it.enabled && it.alias == word } ?: return command
        return shortcut.target
    }

    private fun registerHook() {
        ClientSendMessageEvents.MODIFY_COMMAND.register { command -> rewrite(command) }
    }

    // -------------------------
    // Persistence -- own file, same disabled-defaults + custom-list pattern
    // ChatFilter uses for chat_filters.json.
    // -------------------------
    private data class SavedState(val disabledDefaultAliases: List<String>, val custom: List<WarpShortcut>)

    private val gson = Gson()
    private val shortcutsFile: File by lazy {
        File(Minecraft.getInstance().gameDirectory, "cedclient/warp_shortcuts.json")
    }

    private fun save() {
        try {
            shortcutsFile.parentFile?.mkdirs()
            val disabledDefaults = entries.filter { !it.custom && !it.enabled }.map { it.alias }
            val custom = entries.filter { it.custom }
            shortcutsFile.writeText(gson.toJson(SavedState(disabledDefaults, custom)))
        } catch (e: Exception) {
            println("[WarpShortcuts] Failed to save shortcuts: ${e.message}")
        }
    }

    private fun load() {
        entries.clear()
        entries.addAll(defaultEntries.map { it.copy() })

        try {
            if (shortcutsFile.exists()) {
                val type = object : TypeToken<SavedState>() {}.type
                val loaded: SavedState? = gson.fromJson(shortcutsFile.readText(), type)
                if (loaded != null) {
                    for (alias in loaded.disabledDefaultAliases) {
                        entries.firstOrNull { it.alias == alias && !it.custom }?.enabled = false
                    }
                    entries.addAll(loaded.custom)
                }
            }
        } catch (e: Exception) {
            println("[WarpShortcuts] Failed to load shortcuts: ${e.message}")
        }
    }

    init {
        load()
        registerHook()
    }
}
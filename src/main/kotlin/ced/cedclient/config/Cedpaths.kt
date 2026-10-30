package ced.cedclient.config

import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * Single source of truth for where CedClient stores its files: <minecraft>/config/cedclient/.
 *
 * Older versions wrote to <minecraft>/cedclient/. The first time anything touches this object (it is
 * initialised before any config is read), that legacy folder is migrated into the config folder and then
 * deleted. When a file exists in both, the newer one wins.
 * If copying fails part-way, the legacy folder is left in place and retried next launch.
 */
object CedPaths {

    val dir: File = FabricLoader.getInstance().configDir.resolve("cedclient").toFile()

    init {
        dir.mkdirs()
        migrateLegacy()
    }

    /** Subfolder each known file lives in; anything not listed stays in the config folder itself. */
    private val folderOf: Map<String, String> = buildMap {
        listOf(
            "block_esp_filters.json", "entity_esp_filters.json", "chat_filters.json", "chat_alerts.json", "custom_party_commands.json",
            "inventory_buttons.json", "inventory_button_presets.json", "warp_shortcuts.json", "positional_messages.json"
        ).forEach { put(it, "filters") }
        listOf(
            "chat_history.json", "chat_channel_state.json", "daily_reset.json", "dungeon_splits.json", "room_times_pb.json",
            "sun_gecko_pbs.json", "critter_safari_map.json", "critter_runs.json", "run_profit.json", "friends.txt", "friends_synced.flag"
        ).forEach { put(it, "data") }
        listOf("hypixel_pack.zip", "hypixel_pack.key", "hypixel_pack.new.zip", "hypixel_pack.new.key").forEach { put(it, "cache") }
    }

    /** A file in the config folder. Known ones sit in a subfolder; one still in the old place is moved there on first use. */
    fun file(name: String): File {
        val sub = folderOf[name] ?: return File(dir, name)
        val target = File(dir, "$sub/$name")
        val old = File(dir, name)
        if (!target.exists() && old.isFile) {
            runCatching {
                target.parentFile.mkdirs()
                if (!old.renameTo(target)) { old.copyTo(target); old.delete() }
            }
        }
        return target
    }

    /** A HUD's saved position / scale (one entry in hud/huds.json, see [HudStore]). */
    fun hud(key: String): HudFile = HudFile(key)

    private fun migrateLegacy() {
        val legacy = FabricLoader.getInstance().gameDir.resolve("cedclient").toFile()
        if (!legacy.isDirectory) return

        try {
            legacy.walkTopDown().filter { it.isFile }.forEach { source ->
                val target = File(dir, source.relativeTo(legacy).path)
                // Newer wins: a few features used to write here by mistake (Entity / Block ESP filters, HUD positions,
                // inventory buttons), so the legacy copy can be the up-to-date one.
                if (!target.exists() || source.lastModified() > target.lastModified()) {
                    target.parentFile.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
            }

            if (legacy.deleteRecursively()) {
                println("[CedClient] Moved config from ${legacy.path} to ${dir.path} and removed the old folder")
            } else {
                println("[CedClient] Migrated config to ${dir.path} but could not fully delete ${legacy.path}")
            }
        } catch (t: Throwable) {
            // Leave the legacy folder untouched so nothing is lost; we'll try again next launch.
            println("[CedClient] Config migration failed, keeping old folder: ${t.message}")
        }
    }
}
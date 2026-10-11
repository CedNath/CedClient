package ced.cedclient.config

import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * Single source of truth for where CedClient stores its files: <minecraft>/config/cedclient/.
 *
 * Older versions wrote to <minecraft>/cedclient/. The first time anything touches this object (it is
 * initialised before any config is read), that legacy folder is migrated into the config folder and then
 * deleted. Files that already exist in the new location win, so a migration never overwrites anything.
 * If copying fails part-way, the legacy folder is left in place and retried next launch.
 */
object CedPaths {

    val dir: File = FabricLoader.getInstance().configDir.resolve("cedclient").toFile()

    init {
        dir.mkdirs()
        migrateLegacy()
    }

    fun file(name: String): File = File(dir, name)

    private fun migrateLegacy() {
        val legacy = FabricLoader.getInstance().gameDir.resolve("cedclient").toFile()
        if (!legacy.isDirectory) return

        try {
            legacy.walkTopDown().filter { it.isFile }.forEach { source ->
                val target = File(dir, source.relativeTo(legacy).path)
                if (!target.exists()) {
                    target.parentFile.mkdirs()
                    source.copyTo(target)
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
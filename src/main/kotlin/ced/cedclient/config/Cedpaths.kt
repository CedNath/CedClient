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

    fun file(name: String): File = File(dir, name)

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
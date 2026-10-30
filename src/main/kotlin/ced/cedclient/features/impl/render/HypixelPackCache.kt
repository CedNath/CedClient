package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import net.minecraft.SharedConstants
import net.minecraft.client.resources.server.PackReloadConfig
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.FilePackResources
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Optional
import java.util.UUID
import java.util.function.Consumer

/**
 * Vanilla Textures "Keep Hypixel Pack Loaded": Hypixel's SkyBlock pack is copied to config/cedclient the first time
 * it loads and from then on is part of the startup resource load. When Hypixel pushes the same pack again it is told
 * the pack loaded right away, so joining SkyBlock skips the download and the resource reload.
 * A different (updated) pack loads the vanilla way, replaces the copy and is used from the next start.
 *
 * Hooks: DownloadedPackSourceMixin ([loadPacks], [onReload]), ResourcePackPushMixin ([onPush]).
 */
object HypixelPackCache {
    private val pack = CedPaths.file("hypixel_pack.zip").toPath()
    private val key = CedPaths.file("hypixel_pack.key").toPath()
    // An update is written next to the copy and swapped in at the next start: the copy is open while it's loaded.
    private val newPack = CedPaths.file("hypixel_pack.new.zip").toPath()
    private val newKey = CedPaths.file("hypixel_pack.new.key").toPath()

    /** Key (hash, or URL without one) of the saved pack while it is loaded, else null. */
    private var loadedKey: String? = null
    /** Hypixel sent a different pack this session: leave ours out so the two don't stack. */
    private var replaced = false
    private var started = false
    private var pendingId: UUID? = null
    private var pendingKey = ""

    private fun enabled() = VanillaTextures.isEnabled && VanillaTextures.keepPack.value && !VanillaTextures.blockPack.value

    /** Every resource reload: the saved pack, on top like a server pack. */
    @JvmStatic
    fun loadPacks(output: Consumer<Pack>) {
        loadedKey = null
        if (!enabled() || replaced) return
        runCatching {
            if (!started && Files.isRegularFile(newPack) && Files.isRegularFile(newKey)) {
                Files.move(newPack, pack, StandardCopyOption.REPLACE_EXISTING)
                Files.move(newKey, key, StandardCopyOption.REPLACE_EXISTING)
            }
            started = true
            if (!Files.isRegularFile(pack) || !Files.isRegularFile(key)) return
            val info = PackLocationInfo("cedclient/hypixel_pack", Component.literal("Hypixel SkyBlock (saved)"), PackSource.SERVER, Optional.empty())
            val resources = FilePackResources.FileResourcesSupplier(pack)
            val version = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES)
            val metadata = Pack.readPackMetadata(info, resources, version, PackType.CLIENT_RESOURCES) ?: return
            output.accept(Pack(info, resources, metadata, PackSelectionConfig(true, Pack.Position.TOP, true)))
            loadedKey = Files.readString(key)
        }.onFailure { it.printStackTrace() }
    }

    /** Hypixel pushed its SkyBlock pack: true when the saved one is that pack and already loaded. */
    @JvmStatic
    fun onPush(id: UUID, url: String, hash: String): Boolean {
        if (!enabled()) return false
        val pushed = hash.ifEmpty { url }
        if (pushed == loadedKey) return true
        if (loadedKey != null) replaced = true
        pendingId = id
        pendingKey = pushed
        return false
    }

    /** Vanilla is about to load its server packs: save the pushed SkyBlock pack once it's downloaded. */
    @JvmStatic
    fun onReload(packs: List<PackReloadConfig.IdAndPath>) {
        val downloaded = packs.firstOrNull { it.id() == pendingId }?.path() ?: return
        pendingId = null
        val savedKey = pendingKey
        // Copied right here, not on another thread: by the time a thread got to it the download had already been moved
        // away by the reload ("NoSuchFileException"), so the pack was never kept and loaded from scratch every time.
        runCatching {
            Files.createDirectories(newPack.parent) // the cache folder does not exist on a fresh install ("NoSuchFileException a -> b")
            Files.copy(downloaded, newPack, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(newKey, savedKey)
            ced.cedclient.utils.Debug.line("Vanilla Textures", "saved the Hypixel pack (${Files.size(newPack) / 1024} KB), used from the next start")
        }.onFailure {
            ced.cedclient.utils.Debug.line("Vanilla Textures", "could not save the Hypixel pack: ${it.message}")
            it.printStackTrace()
        }
    }
}

package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.mixin.accessor.LanguageAccessor
import ced.cedclient.mixin.accessor.LanguageManagerAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.resources.language.ClientLanguage
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentContents
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.KeybindContents
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import java.util.IdentityHashMap
import java.util.Optional

/**
 * A server can put a translation / keybind key on a sign or an anvil rename and read back what the client turns it into:
 * if the text comes back translated, a mod that adds that key is installed. This answers every such key as if only
 * vanilla and the server's own resource pack were loaded (the key itself comes back untranslated), so installed mods
 * stay private. Ported from NoammAddons; mods that add no language keys were never exposed.
 */
object ModHider : Module(
    "Mod Hider",
    Category.Misc,
    "Stops servers from detecting your mods through translation keys in signs and anvil renames.",
    defaultEnabled = false
) {
    private val mc get() = Minecraft.getInstance()
    private val serverLanguages = IdentityHashMap<ClientPacketListener?, Language?>()
    private val vanilla by lazy { LanguageAccessor.`ced$loadDefault`() }

    /** Used by the sign / anvil mixins in place of Component.getString(). */
    @JvmStatic
    fun getString(component: Component): String {
        if (!isEnabled || component !is MutableComponent) return component.string
        val sb = StringBuilder()
        visit(component.contents)?.let { sb.append(it) }
        for (sibling in component.siblings) sb.append(getString(sibling))
        return sb.toString()
    }

    private fun visit(contents: ComponentContents): String? = when {
        contents is KeybindContents && !canTranslate(contents.name) -> contents.name
        contents is TranslatableContents && !canTranslate(contents.key) -> contents.fallback ?: contents.key
        else -> contents.visit(Optional<String>::of).orElse(null)
    }

    private fun canTranslate(key: String): Boolean {
        if (mc.currentServer?.resourcePackStatus == ServerData.ServerPackStatus.ENABLED) {
            val connection = mc.connection
            if (!serverLanguages.containsKey(connection)) {
                serverLanguages.clear()
                serverLanguages[connection] = serverLanguage()
            }
            return serverLanguages[connection]!!.has(key)
        }
        return vanilla.has(key)
    }

    /** Vanilla's own pack plus the packs the server (or world / feature flags) added, nothing from mods or the user. */
    private fun serverLanguage(): Language {
        val all = mc.resourceManager.listPacks().toList()
        val packs = mutableListOf(all.first())
        for (i in 1 until all.size) {
            val source = all[i].location().source()
            if (source == PackSource.FEATURE || source == PackSource.WORLD || source == PackSource.SERVER) packs.add(all[i])
        }
        val manager = MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs)
        val selected = mc.languageManager.selected
        val languages = LanguageManagerAccessor.`ced$extractLanguages`(manager.listPacks())
        val codes = mutableListOf("en_us")
        var bidirectional = LanguageManagerAccessor.`ced$defaultLanguage`().bidirectional()
        val info = if (selected != "en_us") languages[selected] else null
        if (info != null) {
            codes.add(selected)
            bidirectional = info.bidirectional()
        }
        return ClientLanguage.loadFrom(manager, codes, bidirectional)
    }
}

package ced.cedclient.utils

import ced.cedclient.features.impl.render.CustomNametag
import ced.cedclient.features.impl.render.HardcodedCosmetics
import ced.cedclient.sync.CosmeticsSync
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player

/**
 * Single source of truth for "does this player currently have a custom
 * nametag override, and if so what's the raw (unparsed) text". Used by
 * PlayerRendererMixin, which overwrites the render state's own nametag
 * Component with this (parsed) whenever it's non-null, so vanilla renders
 * our text through its normal in-world nametag pipeline.
 *
 * Same priority order as CustomNametagText: a synced cosmetic tag wins over
 * your own CustomNametag text.
 */
object NametagOverride {

    /**
     * [originalNameTagText] is the plain string vanilla already computed
     * for this entity's nametag (team prefix/suffix included) by the time
     * PlayerRendererMixin's TAIL injection runs. It's used as a fallback
     * lookup key for CosmeticsSync: Hypixel SkyBlock fakes the entity's
     * GameProfile name (same trick confirmed for tab-list rows in
     * CustomNametagText), so `player.gameProfile.name` stops matching
     * real IGNs there. The vanilla-rendered text still contains the real
     * IGN as literal text though, so if the direct name lookup misses, we
     * scan known synced IGNs for a whole-word match against that text.
     *
     * CustomNametag's own-tag branch doesn't need any of this -- it
     * identifies "is this me" by reference equality on the local Player
     * object, not by name, so it's unaffected by name spoofing either way.
     */
    fun activeTagFor(player: Player, originalNameTagText: String? = null): String? {
        if (HardcodedCosmetics.isEnabled) {
            CosmeticsSync.getOverride(player.gameProfile.name)?.tag?.let { return it }

            if (originalNameTagText != null) {
                for ((ign, tag) in CosmeticsSync.allTags()) {
                    val regex = Regex("\\b${Regex.escape(ign)}\\b", RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(originalNameTagText)) {
                        return tag
                    }
                }
            }
        }

        val ownTag = CustomNametag.tagText.value
        if (CustomNametag.isEnabled && ownTag.isNotBlank() && player === Minecraft.getInstance().player) {
            return ownTag
        }

        return null
    }
}
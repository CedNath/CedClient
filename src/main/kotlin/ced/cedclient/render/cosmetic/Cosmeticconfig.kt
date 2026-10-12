package ced.cedclient.render.cosmetic

import ced.cedclient.utils.ChatPrefix
import ced.cedclient.state.DungeonState
import ced.cedclient.state.IslandState
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Plain, thread-safe mirror of the Cosmetic Blocks module settings. The render hooks run on chunk-compile worker
 * threads and inside mixins, so they read these fields instead of touching the module / Setting objects directly.
 * [ced.cedclient.features.impl.render.CosmeticBlocks] refreshes them every tick.
 */
object CosmeticConfig {
    @JvmField @Volatile var enabled = false
    @JvmField @Volatile var keepSize = false
    @JvmField @Volatile var lavaAsWater = false
    @JvmField @Volatile var customWater = false
    @JvmField @Volatile var waterRgb = 0x3F76E4
    @JvmField @Volatile var onlyInDungeons = false
    @JvmField @Volatile var roomScanning = true
    @JvmField @Volatile var remapsEnabled = true
    @JvmField @Volatile var remapKeepShape = false
    @JvmField @Volatile var fillLimit = 2000
    /** World Edit: largest region (blocks) set / replace / remove / replacenear may touch. */
    @JvmField @Volatile var maxRegion = 250_000

    fun inDungeon(): Boolean = DungeonState.inDungeon || IslandState.island == IslandState.Island.CATACOMBS

    /** False when "Only in Dungeons" is on and we are somewhere else. */
    @JvmStatic
    fun gateOpen(): Boolean = !onlyInDungeons || inDungeon()
}

/** Chat output shared by every cosmetic tool. */
object CosmeticChat {
    fun say(text: String) {
        Minecraft.getInstance().player?.sendSystemMessage(ChatPrefix.msg("§f$text"))
    }
}

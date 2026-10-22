package ced.cedclient.features.impl.funqol

import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.state.DungeonScanner
import ced.cedclient.state.DungeonState
import ced.cedclient.ui.clickgui.SettingsPopup
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.floor

/** Left-clicking a dungeon secret (chest, lever, skull) uses it instead of starting to mine it. */
object LeftClickSecrets {
    val on = BooleanSetting("Left Click Secrets", false, "Lets left-clicking dungeon secrets interact with them instead of mining.")
    val isEnabled get() = Secrets.isEnabled && on.value
    private val waterBoard = BooleanSetting("Water Board", false, "Also turns left-clicked levers into clicks in the Water Board room (off: you can still hit them to mine).")

    val popupColumns get() = listOf(listOf(SettingsPopup.Section("Levers", listOf(waterBoard))))

    /** Called from Minecraft.startAttack (every left click, before mining starts). True = click handled, cancel the attack. */
    @JvmStatic
    fun handleAttack(): Boolean {
        if (!isEnabled || !DungeonState.inDungeon || DungeonState.inBoss) return false
        val mc = Minecraft.getInstance()
        val hit = mc.hitResult as? BlockHitResult ?: return false
        if (hit.type != HitResult.Type.BLOCK) return false
        val pos = hit.blockPos
        val level = mc.level ?: return false
        val block = level.getBlockState(pos).block
        val secret = block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.LEVER ||
            block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD
        if (!secret) return false
        if (block == Blocks.LEVER && !waterBoard.value && inWaterBoard(pos)) return false
        val gm = mc.gameMode ?: return false
        val lp = mc.player ?: return false
        gm.useItemOn(lp, InteractionHand.MAIN_HAND, hit)
        lp.swing(InteractionHand.MAIN_HAND)
        return true
    }

    private fun inWaterBoard(pos: BlockPos): Boolean {
        val i = floor((pos.x + 185) / 32.0).toInt()
        val j = floor((pos.z + 185) / 32.0).toInt()
        if (i !in 0..5 || j !in 0..5) return false
        return DungeonScanner.room(i, j)?.name == "Water Board"
    }
}

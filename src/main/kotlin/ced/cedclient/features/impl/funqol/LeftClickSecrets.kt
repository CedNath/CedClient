package ced.cedclient.features.impl.funqol

import ced.cedclient.utils.swingMainHand
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.state.DungeonScanner
import ced.cedclient.state.DungeonState
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.floor

/** Left-clicking a dungeon secret (chest, lever, skull) uses it instead of starting to mine it. */
object LeftClickSecrets : Module(
    "Left Click Secrets",
    Category.Skyblock,
    "Lets left-clicking dungeon secrets interact with them instead of mining."
) {
    private val waterBoard = BooleanSetting("Water Board", false, "Also turns left-clicked levers into clicks in the Water Board room (off: you can still hit them to mine).")

    init { addSettings(waterBoard) }

    private var lastHeld: BlockPos? = null

    /** Called from Minecraft.startAttack (every left click, before mining starts). True = click handled, cancel the attack. */
    @JvmStatic
    fun handleAttack(): Boolean {
        val hit = secretHit() ?: return false
        click(hit)
        lastHeld = hit.blockPos
        return true
    }

    /** Called from Minecraft.continueAttack while left click stays held: a new secret under the crosshair is used once, none of them is mined. */
    @JvmStatic
    fun handleHold(): Boolean {
        val hit = secretHit()
        if (hit == null) { lastHeld = null; return false }
        if (hit.blockPos != lastHeld) { click(hit); lastHeld = hit.blockPos }
        return true
    }

    private fun click(hit: BlockHitResult) {
        val mc = Minecraft.getInstance()
        val gm = mc.gameMode ?: return
        val lp = mc.player ?: return
        gm.useItemOn(lp, InteractionHand.MAIN_HAND, hit)
        lp.swingMainHand(attack = true)
    }

    /** The secret under the crosshair, or null when the click should act normally. */
    private fun secretHit(): BlockHitResult? {
        if (!isEnabled || !DungeonState.inDungeon || DungeonState.inBoss) return null
        val mc = Minecraft.getInstance()
        val hit = mc.hitResult as? BlockHitResult ?: return null
        if (hit.type != HitResult.Type.BLOCK) return null
        val pos = hit.blockPos
        val level = mc.level ?: return null
        val block = level.getBlockState(pos).block
        if (!SecretSignals.isSecretBlock(level, pos)) return null
        if (block == Blocks.LEVER && !waterBoard.value && inWaterBoard(pos)) return null
        return hit
    }

    private fun inWaterBoard(pos: BlockPos): Boolean {
        val i = floor((pos.x + 185) / 32.0).toInt()
        val j = floor((pos.z + 185) / 32.0).toInt()
        if (i !in 0..5 || j !in 0..5) return false
        return DungeonScanner.room(i, j)?.name == "Water Board"
    }
}

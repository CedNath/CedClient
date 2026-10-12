package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.state.DungeonState
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.EntityCollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Secret Hitboxes (NoammAddons-style): bigger hitboxes on levers, buttons, skulls and mushrooms so secrets are
 * easier to click. Only the outline / click shape changes; collision stays vanilla.
 *
 * F7 / M7 second device (the 5x4 lights grid): the 14 levers that are never used keep their normal lever
 * hitbox, and with Hide Unused Device Levers they aren't rendered at all, so only the 6 real ones are left.
 *
 * Hooks: LeverBlockMixin / ButtonBlockMixin / SkullBlockMixin / MushroomBlockMixin (getShape),
 * SkullCollisionMixin (keeps skull collision vanilla), and the cosmetic render hooks for the hidden levers.
 */
object SecretHitboxes : Module(
    "Secret Hitboxes",
    Category.Cheats,
    "Bigger lever / button / skull / mushroom hitboxes. Hides the unused levers on the F7 second device."
) {

    private val lever = BooleanSetting("Lever", true, "Full-block lever hitbox.")
    private val leverSize = NumberSetting("Lever Hitbox Size", 1.0, 0.5, 1.0, 0.01)
    private val button = BooleanSetting("Button", true, "Bigger button hitbox.")
    private val buttonSize = NumberSetting("Button Hitbox Size", 1.0, 0.375, 1.0, 0.01)
    private val skull = BooleanSetting("Skulls", false, "Full-block hitbox on player-head skulls (wither essence / redstone key) during clear.")
    private val mushroom = BooleanSetting("Mushroom", false, "Full-block mushroom hitbox.")
    private val hideDeviceLevers = BooleanSetting(
        "Hide Unused Device Levers", true,
        "F7 / M7 second device: don't render the 14 levers you never use (they keep a normal lever hitbox)."
    )
    private val onlyDungeons = BooleanSetting("Only In Dungeons", true, "Off: also works outside dungeons (e.g. a copied map).")

    /** The 14 levers on the lights device that are never part of the solution (same list as NoammAddons). */
    private val UNUSED_DEVICE_LEVERS: Set<Long> = listOf(
        BlockPos(61, 136, 142), BlockPos(60, 136, 142), BlockPos(59, 136, 142),
        BlockPos(62, 135, 142), BlockPos(61, 135, 142), BlockPos(59, 135, 142), BlockPos(58, 135, 142),
        BlockPos(62, 134, 142), BlockPos(61, 134, 142), BlockPos(59, 134, 142), BlockPos(58, 134, 142),
        BlockPos(61, 133, 142), BlockPos(60, 133, 142), BlockPos(59, 133, 142),
    ).map { it.asLong() }.toHashSet()

    // ---- state updated each tick, read from shape / render calls (any thread) ----
    @Volatile private var active = false
    @Volatile private var onF7 = false
    @Volatile private var inClear = false
    @Volatile private var hiding = false

    /** Set by SkullCollisionMixin while a skull's collision shape is being looked up (keep that vanilla). */
    @JvmField
    val SKULL_COLLISION: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    private var lastSidebarCheck = 0L
    private var sidebarF7 = false

    private val mc get() = Minecraft.getInstance()

    init {
        addSettings(lever, leverSize, button, buttonSize, skull, mushroom, hideDeviceLevers, onlyDungeons)
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    private fun tick() {
        val inDungeon = DungeonState.inDungeon
        active = isEnabled && mc.level != null && (inDungeon || !onlyDungeons.value)

        // F7 / M7: the run's floor, or the sidebar's "The Catacombs (F7)" on a copied map (checked once a second).
        val now = System.currentTimeMillis()
        if (now - lastSidebarCheck > 1000) {
            lastSidebarCheck = now
            sidebarF7 = DungeonState.sidebarFloor()?.drop(1) == "7"
        }
        onF7 = DungeonState.floor?.drop(1) == "7" || sidebarF7
        inClear = !DungeonState.inBoss

        val nowHiding = active && onF7 && hideDeviceLevers.value
        if (nowHiding != hiding) {
            hiding = nowHiding
            remeshDevice()
        }
    }

    /** Rebuild the chunk section holding the lights device so hiding / un-hiding shows up immediately. */
    private fun remeshDevice() {
        if (mc.level == null) return
        try {
            mc.levelExtractor.setSectionDirty(60 shr 4, 134 shr 4, 142 shr 4)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Called from the mixins
    // ------------------------------------------------------------------

    /**
     * Only enlarge for a player's outline / click lookup. Face culling (Sodium, MoreCulling) asks with an empty
     * context, and a full-block button there culled the face of the block it sits on, leaving a see-through hole.
     */
    @JvmStatic
    fun isEntityQuery(context: CollisionContext): Boolean = context is EntityCollisionContext && context.entity != null

    private fun isUnusedDeviceLever(pos: BlockPos) = onF7 && UNUSED_DEVICE_LEVERS.contains(pos.asLong())

    /** Render hook: draw these positions as air. */
    @JvmStatic
    fun isHidden(x: Int, y: Int, z: Int): Boolean = hiding && UNUSED_DEVICE_LEVERS.contains(BlockPos.asLong(x, y, z))

    @JvmStatic
    fun leverShape(state: BlockState, pos: BlockPos): VoxelShape? {
        if (!active || !lever.value || isUnusedDeviceLever(pos)) return null
        return attached(leverShapes(leverSize.value), state)
    }

    @JvmStatic
    fun buttonShape(state: BlockState): VoxelShape? {
        if (!active || !button.value) return null
        val powered = state.getValue(ButtonBlock.POWERED)
        return attached(buttonShapes(buttonSize.value, powered), state)
    }

    @JvmStatic
    fun skullShape(state: BlockState): VoxelShape? {
        if (!skullActive()) return null
        if ((state.block as? SkullBlock)?.type != SkullBlock.Types.PLAYER) return null
        return Shapes.block()
    }

    @JvmStatic
    fun skullActive(): Boolean = active && skull.value && inClear

    @JvmStatic
    fun mushroomShape(): VoxelShape? = if (active && mushroom.value) Shapes.block() else null

    // ---- shapes (cached per size, like vanilla's makeShapes) ----

    private fun attached(shapes: Map<AttachFace, Map<Direction, VoxelShape>>, state: BlockState): VoxelShape? =
        shapes[state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)]
            ?.get(state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING))

    private var leverCacheSize = -1.0
    private var leverCache: Map<AttachFace, Map<Direction, VoxelShape>> = emptyMap()

    @Synchronized
    private fun leverShapes(size: Double): Map<AttachFace, Map<Direction, VoxelShape>> {
        if (size != leverCacheSize) {
            val s = size * 16.0
            leverCache = Shapes.rotateAttachFace(Block.boxZ(s, s, 16.0 - s, 16.0))
            leverCacheSize = size
        }
        return leverCache
    }

    private var buttonCacheSize = -1.0
    private var buttonCache: Map<AttachFace, Map<Direction, VoxelShape>> = emptyMap()
    private var buttonCachePressed: Map<AttachFace, Map<Direction, VoxelShape>> = emptyMap()

    @Synchronized
    private fun buttonShapes(size: Double, powered: Boolean): Map<AttachFace, Map<Direction, VoxelShape>> {
        if (size != buttonCacheSize) {
            val s = size * 16.0
            buttonCache = Shapes.rotateAttachFace(Block.boxZ(s, 14.0, 16.0))
            buttonCachePressed = Shapes.rotateAttachFace(Block.boxZ(s, 15.0, 16.0))
            buttonCacheSize = size
        }
        return if (powered) buttonCachePressed else buttonCache
    }
}

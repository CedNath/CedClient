package ced.cedclient.features.impl.funqol

import ced.cedclient.events.PlaySoundEvent
import ced.cedclient.events.core.on
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.safely
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB

/**
 * Secret pickups as Hypixel reports them (same signals Odin uses): a secret item entity that is taken or removed within
 * 6 blocks of you, and the 0.1-volume bat sound. Neither needs the item to land in your inventory. Feeds Secret Clicked and Room Times.
 */
object SecretSignals {
    private val DROPS = listOf(
        "health potion viii splash potion", "healing potion 8 splash potion", "healing potion viii splash potion", "healing viii splash potion",
        "healing 8 splash potion", "decoy", "inflatable jerry", "spirit leap", "trap", "training weights", "defuse kit", "dungeon chest key",
        "treasure talisman", "revive stone", "architect's first draft", "secret dye", "candycomb",
    )
    private const val RANGE_SQ = 36.0
    private val mc get() = Minecraft.getInstance()

    private var nearItems = HashMap<Int, AABB>()
    private var nearBats = HashMap<Int, AABB>()
    private val done = HashSet<Int>()

    private fun wanted() = (RoomTimes.isEnabled || SecretHighlight.isEnabled) && DungeonState.active && !DungeonState.inBoss
    private fun isDrop(e: ItemEntity) = e.item.hoverName.string.lowercase().let { n -> DROPS.any { it in n } }

    /** Call once so the listeners below exist. */
    fun ensure() = Unit

    // Skin owners of the dungeon's wither essence / redstone key heads (same ids Odin checks); other heads are decoration.
    private val SECRET_HEADS = setOf(
        java.util.UUID.fromString("e0f3e929-869e-3dca-9504-54c666ee6f23"),
        java.util.UUID.fromString("fed95410-aba1-39df-9b95-1d4f361eb66e"),
    )

    /** A chest, lever or secret head (wither essence / redstone key) at [pos]: something clicking counts as a secret. */
    fun isSecretBlock(level: net.minecraft.world.level.Level, pos: net.minecraft.core.BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (state.`is`(net.minecraft.world.level.block.Blocks.CHEST) || state.`is`(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST) ||
            state.`is`(net.minecraft.world.level.block.Blocks.LEVER)) return true
        if (!state.`is`(net.minecraft.world.level.block.Blocks.PLAYER_HEAD) && !state.`is`(net.minecraft.world.level.block.Blocks.PLAYER_WALL_HEAD)) return false
        val head = level.getBlockEntity(pos) as? net.minecraft.world.level.block.entity.SkullBlockEntity
        return head?.ownerProfile?.partialProfile()?.id in SECRET_HEADS
    }

    init {
        ClientTickEvents.END_CLIENT_TICK.register { safely("Secret Signals") {
            val level = mc.level
            val player = mc.player
            if (level == null || player == null || !wanted()) { nearItems.clear(); nearBats.clear(); done.clear(); return@register }
            val items = HashMap<Int, AABB>()
            for (e in level.getEntitiesOfClass(ItemEntity::class.java, player.boundingBox.inflate(10.0)) { isDrop(it) }) items[e.id] = e.boundingBox
            for ((id, box) in nearItems) if (id !in items && level.getEntity(id) == null) itemGone(id, box)
            nearItems = items
            done.retainAll(items.keys)
            val bats = HashMap<Int, AABB>()
            for (b in level.getEntitiesOfClass(Bat::class.java, player.boundingBox.inflate(16.0)) { true }) bats[b.id] = b.boundingBox
            nearBats = bats
        } }
        on<PlaySoundEvent> {
            if (!wanted() || (it.soundName != "minecraft:entity.bat.death" && it.soundName != "minecraft:entity.bat.hurt") || it.volume != 0.1f) return@on
            val eye = mc.player?.eyePosition ?: return@on
            val box = nearBats.values.minByOrNull { b -> b.center.distanceToSqr(eye) }
            RoomTimes.onOwnSecret("bat sound")
            SecretHighlight.onBatSecret(box)
        }
    }

    /** The take-item packet: item entity [itemId] is about to vanish into somebody. */
    @JvmStatic
    fun onTake(itemId: Int) {
        if (!wanted()) return
        val e = mc.level?.getEntity(itemId) as? ItemEntity ?: return
        if (isDrop(e)) itemGone(itemId, e.boundingBox)
    }

    private fun itemGone(id: Int, box: AABB) {
        val eye = mc.player?.eyePosition ?: return
        if (box.center.distanceToSqr(eye) > RANGE_SQ || !done.add(id)) return
        RoomTimes.onOwnSecret("item pickup")
        SecretHighlight.onItemSecret(box)
    }
}

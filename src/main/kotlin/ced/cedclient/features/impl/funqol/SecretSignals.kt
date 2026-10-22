package ced.cedclient.features.impl.funqol

import ced.cedclient.events.PlaySoundEvent
import ced.cedclient.events.core.on
import ced.cedclient.state.DungeonState
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

    init {
        ClientTickEvents.END_CLIENT_TICK.register {
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
        }
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

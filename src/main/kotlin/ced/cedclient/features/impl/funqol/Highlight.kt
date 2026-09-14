package ced.cedclient.features.impl.funqol

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

object Highlight {

    /**
     * Returns a custom team color for the given entity.
     * If null is returned, vanilla team color is used.
     *
     * This is a minimal stub you can later hook into your
     * dungeon teammate / class detection logic.
     */
    @JvmStatic
    fun getTeammateColor(entity: Entity): Int? {
        // Example: simple highlight for all other players (not you)
        if (entity is Player) {
            // TODO: replace this with real dungeon teammate logic later
            return 0x00FFAA // some noticeable teal-ish color
        }

        // No override → use vanilla color
        return null
    }
}

package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.funqol.Highlight
import ced.cedclient.utils.Debug
import net.minecraft.world.entity.Entity

/**
 * No Glow: stops the outline glow vanilla puts on entities the server flagged as glowing (boss and mob highlights,
 * the glowing effect). The client's own outlines (EntityGlow, e.g. F4 Features, and the teammate colours) are kept.
 * Hook: MinecraftMixin.shouldEntityAppearGlowing.
 */
object NoGlow : Module(
    "No Glow",
    Category.Render,
    "Removes the outline glow from entities (the glowing effect the server puts on mobs and players).",
    defaultEnabled = false
) {
    private var logged = false

    /** Called per entity from MinecraftMixin; true = this entity must not glow. */
    @JvmStatic
    fun blocks(entity: Entity): Boolean {
        if (!isEnabled) return false
        if (Highlight.getTeammateColor(entity) != null) return false
        if (!logged) { logged = true; Debug.line(name, "blocked a glow on ${entity.type.descriptionId}") }
        return true
    }
}

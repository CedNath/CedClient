package ced.cedclient.render

import net.minecraft.world.entity.Entity

/**
 * Vanilla outline glow on any entity, in any colour. A module registers a provider that returns an RGB colour for the
 * entities it wants outlined (null for the rest); EntityMixin / MinecraftMixin read it so the entity glows through walls
 * without the server having to set the glowing flag.
 */
object EntityGlow {
    private val providers = ArrayList<(Entity) -> Int?>()

    fun register(provider: (Entity) -> Int?) { providers += provider }

    @JvmStatic
    fun colorOf(entity: Entity): Int? {
        for (p in providers) p(entity)?.let { return it }
        return null
    }
}

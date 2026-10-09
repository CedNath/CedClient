package ced.cedclient.render.cosmetic

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/** Block-state property helpers (Kotlin, so it compiles with the rest of the mod -- no separate Java file needed). */
object CosmeticProps {

    // Comparable<Any> satisfies Property's "T : Comparable<T>" bound (Comparable is contravariant).
    @Suppress("UNCHECKED_CAST")
    private fun raw(p: Property<*>): Property<Comparable<Any>> = p as Property<Comparable<Any>>

    /** Sets [p] to the value with the given serialized name, or returns null if the name isn't valid. */
    fun setByName(state: BlockState, p: Property<*>, valueName: String): BlockState? {
        val prop = raw(p)
        val value = prop.getValue(valueName)
        return if (value.isPresent) state.setValue(prop, value.get()) else null
    }

    /** "minecraft:oak_stairs[facing=east,half=bottom,...]" (just the id when the block has no properties). */
    fun serialize(state: BlockState): String {
        val id = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        val props = state.block.stateDefinition.properties
        if (props.isEmpty()) return id
        return props.joinToString(",", "$id[", "]") { p ->
            val prop = raw(p)
            "${prop.name}=${prop.getName(state.getValue(prop))}"
        }
    }
}
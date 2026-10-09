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

    /** The serialized name of [p]'s current value on [state] ("east", "true", "3", ...). */
    fun valueName(state: BlockState, p: Property<*>): String {
        val prop = raw(p)
        return prop.getName(state.getValue(prop))
    }

    /** Every serialized value [p] can take ("north", "south", ...). */
    fun possibleValueNames(p: Property<*>): List<String> {
        val prop = raw(p)
        return prop.possibleValues.map { prop.getName(it) }
    }

    /** Copies every property [from] has onto [to] where [to] has the same property (stairs facing/half, slab type...). */
    fun copyMatching(from: BlockState, to: BlockState): BlockState {
        var result = to
        for (p in from.block.stateDefinition.properties) {
            if (!result.hasProperty(p)) continue
            val prop = raw(p)
            result = result.setValue(prop, from.getValue(prop))
        }
        return result
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

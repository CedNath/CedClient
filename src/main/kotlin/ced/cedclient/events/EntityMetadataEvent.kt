package cedclient.events

import ced.cedclient.events.core.CancellableEvent
import net.minecraft.network.protocol.Packet
import net.minecraft.world.entity.Entity


class EntityMetadataEvent(
    val entity: Entity,
    val packet: Packet<*>
) : CancellableEvent()

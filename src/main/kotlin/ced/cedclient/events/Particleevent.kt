package ced.cedclient.events

import ced.cedclient.events.core.Event
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket

/**
 * Fired on the main thread for every particle packet the server sends
 * (see ParticlePacketMixin). Read-only tap, same style as ChatMessageEvent --
 * vanilla still spawns the particles as normal.
 */
class ParticleEvent(
    val packet: ClientboundLevelParticlesPacket
) : Event()
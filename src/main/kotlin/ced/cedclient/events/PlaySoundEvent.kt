package ced.cedclient.events

import ced.cedclient.events.core.Event

/**
 * Fired for every sound packet the client receives from the server
 * (ClientboundSoundPacket). Hypixel signals most item-ability activations
 * this way -- each ability plays a distinctive sound the instant it fires --
 * which is what lets ItemCooldowns start a countdown on the exact tick the
 * ability activates, instead of waiting on (and parsing) a chat message.
 *
 * [soundName] is the sound event's resource location as a string (e.g.
 * "minecraft:entity.zombie.ambient"), NOT a legacy 1.8-style name -- do not
 * copy sound signatures from other (older) mods without re-verifying them
 * against what actually gets logged here.
 *
 * Not cancellable: this is a read-only tap, same rationale as ChatMessageEvent
 * -- it's a place for features to react to sounds, not a second system for
 * muting/replacing them.
 */
class PlaySoundEvent(
    val soundName: String,
    val pitch: Float,
    val volume: Float,
) : Event()
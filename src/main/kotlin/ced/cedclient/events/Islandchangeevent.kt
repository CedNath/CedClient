package ced.cedclient.events

import ced.cedclient.events.core.Event
import ced.cedclient.state.IslandState

/**
 * Fired whenever [IslandState.island] changes -- including transitions into
 * or out of `null` (leaving/entering SkyBlock, or a tab list read that
 * couldn't be matched to a known island). Mirrors the read-only "tap" style
 * of [ChatMessageEvent] -- this isn't cancellable, it's just a notification
 * for features that want to react without polling IslandState themselves.
 */
class IslandChangeEvent(
    val island: IslandState.Island?,
    val previousIsland: IslandState.Island?
) : Event()
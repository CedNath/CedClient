package ced.cedclient.events

import ced.cedclient.events.core.Event

/**
 * Fired when the sidebar's area line changes (" ⏣ Dwarven Village" -> "Dwarven Village"), including to / from
 * null (no area line: lobby, loading, other servers). See [ced.cedclient.state.Sidebar].
 */
class AreaChangeEvent(val area: String?, val previousArea: String?) : Event()

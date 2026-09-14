package ced.cedclient.events.core

sealed class TickEvent : Event() {
    object Start : TickEvent()
    object End : TickEvent()
}

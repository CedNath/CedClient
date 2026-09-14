package ced.cedclient.events.core

open class Event {
    open fun post(): Boolean = EventBus.post(this)
    open fun postAndCatch(): Boolean = EventBus.post(this)
}

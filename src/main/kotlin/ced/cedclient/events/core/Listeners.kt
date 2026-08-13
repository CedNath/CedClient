package ced.cedclient.events.core

import kotlin.reflect.KClass

inline fun <reified T : Event> on(noinline handler: (T) -> Unit) {
    EventBus.subscribe(T::class, handler)
}

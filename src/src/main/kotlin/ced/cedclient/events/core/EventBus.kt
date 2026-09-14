package ced.cedclient.events.core

import kotlin.reflect.KClass

object EventBus {

    private val listeners = mutableMapOf<KClass<*>, MutableList<(Event) -> Unit>>()

    fun <T : Event> subscribe(type: KClass<T>, handler: (T) -> Unit) {
        listeners.computeIfAbsent(type) { mutableListOf() }
            .add { event -> handler(event as T) }
    }

    fun post(event: Event): Boolean {
        listeners[event::class]?.forEach { handler ->
            handler(event)
        }

        return if (event is CancellableEvent) event.isCancelled else false
    }
}

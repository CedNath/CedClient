package ced.cedclient.events.core

open class CancellableEvent : Event() {
    var isCancelled = false
        private set

    fun cancel() {
        isCancelled = true
    }

    override fun postAndCatch(): Boolean {
        EventBus.post(this)
        return isCancelled
    }
}

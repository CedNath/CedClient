package ced.cedclient.utils

object Debug {
    var enabled = false
    var tickCounter = 0

    fun log(msg: String, interval: Int = 50) {
        tickCounter++
        if (tickCounter % interval == 0) {
            if (enabled) println(msg)
        }
    }

}

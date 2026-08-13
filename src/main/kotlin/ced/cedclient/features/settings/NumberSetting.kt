package ced.cedclient.features.settings

class NumberSetting(
    name: String,
    value: Double,
    val min: Double,
    val max: Double,
    val increment: Double = 0.1
) : Setting<Double>(name, value) {

    fun increment() {
        set((value + increment).coerceAtMost(max))
    }

    fun decrement() {
        set((value - increment).coerceAtLeast(min))
    }

    override fun set(newValue: Double) {
        super.set(newValue.coerceIn(min, max))
    }
}

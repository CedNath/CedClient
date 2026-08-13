// LorenzVec.kt
package ced.cedclient.utils

data class LorenzVec(val x: Double, val y: Double, val z: Double) {
    constructor() : this(0.0, 0.0, 0.0)

    fun up(dy: Double) = LorenzVec(x, y + dy, z)
    fun distanceTo(other: LorenzVec): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    override fun toString(): String = "LorenzVec(x=$x, y=$y, z=$z)"
}

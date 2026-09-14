package ced.cedclient.utils

val Int.red: Int get() = (this shr 16) and 255
val Int.green: Int get() = (this shr 8) and 255
val Int.blue: Int get() = this and 255
val Int.alpha: Int get() = (this shr 24) and 255

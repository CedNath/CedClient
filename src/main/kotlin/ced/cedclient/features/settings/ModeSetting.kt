package ced.cedclient.features.settings

class ModeSetting(
    name: String,
    value: String,
    val modes: Array<String>
) : Setting<String>(name, value)

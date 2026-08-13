package ced.cedclient.features

object ModuleManager {

    val modules = mutableListOf<Module>()

    fun register(module: Module) {
        modules += module
    }

    fun getModule(name: String): Module? {
        return modules.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

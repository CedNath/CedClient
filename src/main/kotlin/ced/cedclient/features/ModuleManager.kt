package ced.cedclient.features

object ModuleManager {

    val modules = mutableListOf<Module>()

    /**
     * Modules grouped by category, e.g. for Panel.kt (Phase 4) to pull the
     * button list for its own category without every Panel re-filtering the
     * full module list itself.
     */
    val modulesByCategory: Map<Category, List<Module>>
        get() = modules.groupBy { it.category }

    fun register(module: Module) {
        modules += module
    }

    fun getModule(name: String): Module? {
        return modules.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
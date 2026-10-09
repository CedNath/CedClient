package ced.cedclient.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.tree.CommandNode
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals

/**
 * Fixes tab-complete for client commands whose name the server also has (on Hypixel e.g. /hub, /garden from
 * WarpShortcuts, or any of ours that collides with a server command).
 *
 * Fabric merges our client commands into the server's command tree (that tree is what chat suggestions read), but
 * when a root name already exists there, Brigadier's addChild() merges only that node and Fabric then attaches the
 * subcommands to the throw-away copy -- so the command still runs, but nothing below it autocompletes.
 * Called after every server command tree (ClientCommandTreeMixin): copies any missing nodes back in.
 */
object ClientCommandTreeRepair {
    private val reported = HashSet<String>()

    @JvmStatic
    fun repair(serverRoot: CommandNode<*>) {
        val clientRoot = ClientCommandInternals.getActiveDispatcher()?.root ?: return
        try {
            for (child in clientRoot.children) {
                val existing = serverRoot.getChild(child.name)
                val lostChildren = existing != null && child.children.any { existing.getChild(it.name) == null }
                if (lostChildren && reported.add(child.name)) {
                    println("[CedClient] /${child.name} also exists on the server -- restoring its subcommand suggestions")
                }
            }
            @Suppress("UNCHECKED_CAST")
            copyMissing(clientRoot as CommandNode<Any>, serverRoot as CommandNode<Any>)
        } catch (t: Throwable) {
            println("[CedClient] Could not repair command suggestions: ${t.message}")
        }
    }

    /** Recursively adds every node of [from] that [into] lacks (by name). Existing nodes are left as they are. */
    private fun copyMissing(from: CommandNode<Any>, into: CommandNode<Any>) {
        for (child in from.children) {
            var target = into.getChild(child.name)
            if (target == null) {
                val builder = child.createBuilder().requires { true }.redirect(null)
                // Never executed (Fabric runs client commands before the server path), but a node without a
                // command makes the chat box flag the input as incomplete.
                if (builder.command != null) builder.executes(Command { 0 })
                target = builder.build()
                into.addChild(target)
            }
            if (child.children.isNotEmpty()) copyMissing(child, target!!)
        }
    }
}

package ced.cedclient.commands

import ced.cedclient.features.impl.funqol.CoralotHelper
import ced.cedclient.features.impl.render.EntityESP
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Debug
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.chat.Component

object CedClientCommand {

    @Volatile
    private var pendingOpenGui = false

    fun register() {
        // One persistent listener, registered once — checks the flag every tick
        // and opens the GUI on the tick after the command actually ran, avoiding
        // the chat screen's own close logic from wiping it out immediately.
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (pendingOpenGui) {
                pendingOpenGui = false
                client.gui.setScreen(ClickGUI())
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // build the full tree fresh for each root name, then register both
            dispatcher.register(buildCommandTree("cedclient"))
            dispatcher.register(buildCommandTree("cc"))
        }
    }

    private fun buildCommandTree(rootName: String): LiteralArgumentBuilder<FabricClientCommandSource> {
        val openGuiExecutes =
            { ctx: com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ->
                pendingOpenGui = true
                1
            }

        return ClientCommands.literal(rootName)
            .executes(openGuiExecutes)   // bare /cedclient or /cc opens GUI

            .then(
                ClientCommands.literal("debug")
                    .executes {
                        Debug.enabled = !Debug.enabled
                        val state = if (Debug.enabled) "enabled" else "disabled"

                        it.source.sendFeedback(
                            Component.literal("CedClient debug mode $state")
                        )
                        1
                    }
            )

            .then(
                ClientCommands.literal("esp")
                    .then(
                        ClientCommands.literal("block")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "name")
                                        EntityESP.blockName(name)
                                        ctx.source.sendFeedback(Component.literal("Blocked: $name"))
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("unblock")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "name")
                                        EntityESP.unblockName(name)
                                        ctx.source.sendFeedback(Component.literal("Unblocked: $name"))
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("only")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "name")
                                        EntityESP.onlyName(name)
                                        ctx.source.sendFeedback(Component.literal("Only showing (added): $name"))
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("unonly")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "name")
                                        EntityESP.unOnlyName(name)
                                        ctx.source.sendFeedback(Component.literal("Removed from only-list: $name"))
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("clearonly")
                            .executes { ctx ->
                                EntityESP.clearOnly()
                                ctx.source.sendFeedback(Component.literal("Cleared only-list (showing all again)"))
                                1
                            }
                    )
                    .then(
                        ClientCommands.literal("clearblocked")
                            .executes { ctx ->
                                EntityESP.clearBlocked()
                                ctx.source.sendFeedback(Component.literal("Cleared blocked list"))
                                1
                            }
                    )
                    .then(
                        ClientCommands.literal("list")
                            .executes { ctx ->
                                val blocked = if (EntityESP.blockedNames.isEmpty()) "(none)" else EntityESP.blockedNames.joinToString(", ")
                                val only = if (EntityESP.onlyNames.isEmpty()) "(none)" else EntityESP.onlyNames.joinToString(", ")

                                ctx.source.sendFeedback(Component.literal("Blocked: $blocked"))
                                ctx.source.sendFeedback(Component.literal("Only: $only"))
                                1
                            }
                    )
            )

            .then(
                ClientCommands.literal("coralot")
                    .then(
                        ClientCommands.literal("netname")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        CoralotHelper.netItemName = StringArgumentType.getString(ctx, "name")
                                        ctx.source.sendFeedback(Component.literal("Net item name set"))
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("keywords")
                            .then(
                                ClientCommands.argument("words", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val words = StringArgumentType.getString(ctx, "words")
                                            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                        CoralotHelper.catchKeywords = words
                                        ctx.source.sendFeedback(Component.literal("Catch keywords set: $words"))
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("sound")
                            .then(
                                ClientCommands.argument("id", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        CoralotHelper.soundId = StringArgumentType.getString(ctx, "id")
                                        ctx.source.sendFeedback(Component.literal("Sound set"))
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("title")
                            .then(
                                ClientCommands.argument("text", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        CoralotHelper.titleText = StringArgumentType.getString(ctx, "text")
                                        ctx.source.sendFeedback(Component.literal("Title set"))
                                        1
                                    }
                            )
                    )
            )
    }
}
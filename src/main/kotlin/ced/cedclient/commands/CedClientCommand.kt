package ced.cedclient.commands

import ced.cedclient.features.impl.funqol.CoralotHelper
import ced.cedclient.features.impl.render.CustomNametag
import ced.cedclient.features.impl.render.EntityESP
import ced.cedclient.features.impl.render.MasterHudEditScreen
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Debug
import ced.cedclient.utils.NametagFormatting
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

    @Volatile
    private var pendingOpenHudEdit = false

    fun register() {
        // One persistent listener, registered once — checks the flags every
        // tick and opens the relevant screen on the tick after the command
        // actually ran, avoiding the chat screen's own close logic from
        // wiping it out immediately.
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (pendingOpenGui) {
                pendingOpenGui = false
                client.setScreen(ClickGUI())
            }
            if (pendingOpenHudEdit) {
                pendingOpenHudEdit = false
                client.setScreen(MasterHudEditScreen())
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
                ClientCommands.literal("hud")
                    .executes {
                        pendingOpenHudEdit = true
                        1
                    }
            )

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
                // Chat-based alternative to the (small) Tag Text box in the
                // GUI -- mainly so &#hex/<gradient:...>/<rainbow> strings
                // aren't painful to type. Bare "/cedclient nametag" clears it
                // and turns the module off; "/cedclient nametag <text>" sets
                // the text and turns the module on.
                ClientCommands.literal("nametag")
                    .executes { ctx ->
                        CustomNametag.tagText.value = ""
                        CustomNametag.setEnabled(false)
                        ctx.source.sendFeedback(Component.literal("Custom nametag cleared"))
                        1
                    }
                    .then(
                        ClientCommands.argument("text", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val text = StringArgumentType.getString(ctx, "text")
                                CustomNametag.tagText.value = text
                                CustomNametag.setEnabled(true)
                                ctx.source.sendFeedback(
                                    Component.literal("Custom nametag set to: ").append(NametagFormatting.parse(text))
                                )
                                1
                            }
                    )
            )




    }
}
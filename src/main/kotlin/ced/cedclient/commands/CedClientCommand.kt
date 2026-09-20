package ced.cedclient.commands

import ced.cedclient.features.impl.funqol.CoralotHelper
import ced.cedclient.features.impl.misc.DailyReset
import ced.cedclient.features.impl.render.nametag.CustomNametag
import ced.cedclient.features.impl.render.EntityESP
import ced.cedclient.features.impl.render.MasterHudEditScreen
import ced.cedclient.features.impl.misc.WarpShortcuts
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.utils.Debug
import ced.cedclient.features.impl.render.nametag.NametagFormatting
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
                client.gui.setScreen(ClickGUI())
            }
            if (pendingOpenHudEdit) {
                pendingOpenHudEdit = false
                client.gui.setScreen(MasterHudEditScreen())
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
                                val blocked =
                                    if (EntityESP.blockedNames.isEmpty()) "(none)" else EntityESP.blockedNames.joinToString(
                                        ", "
                                    )
                                val only =
                                    if (EntityESP.onlyNames.isEmpty()) "(none)" else EntityESP.onlyNames.joinToString(", ")

                                ctx.source.sendFeedback(Component.literal("Blocked: $blocked"))
                                ctx.source.sendFeedback(Component.literal("Only: $only"))
                                1
                            }
                    )
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

            .then(
                // Auto-tracked + manual checklist of daily-reset tasks -- see
                // DailyReset.kt. Bare "/cc daily" (and "/cc daily list") print
                // everything still outstanding today.
                ClientCommands.literal("daily")
                    .executes { ctx ->
                        val remaining = DailyReset.remaining()
                        ctx.source.sendFeedback(Component.literal("[CC] Daily tasks remaining:"))
                        if (remaining.isEmpty()) {
                            ctx.source.sendFeedback(Component.literal("  All done for today!"))
                        } else {
                            for (name in remaining) {
                                ctx.source.sendFeedback(Component.literal("  - $name"))
                            }
                        }
                        1
                    }
                    .then(
                        ClientCommands.literal("list")
                            .executes { ctx ->
                                ctx.source.sendFeedback(Component.literal("[CC] Daily tasks:"))
                                for ((name, completed) in DailyReset.allTasks()) {
                                    val mark = if (completed) "\u00a7a[done]" else "\u00a77[ ]"
                                    ctx.source.sendFeedback(Component.literal("  $mark \u00a7f$name"))
                                }
                                1
                            }
                    )
                    .then(
                        // Lists ONLY the auto-tracked definitions (not the
                        // manual list) with their cooldown label and whether
                        // a trigger has fired today -- handy for checking
                        // which ones still need a Regex added.
                        ClientCommands.literal("entries")
                            .executes { ctx ->
                                ctx.source.sendFeedback(Component.literal("[CC] Auto-tracked dailies:"))
                                for ((name, cooldown, completed) in DailyReset.autoEntries()) {
                                    val mark = if (completed) "\u00a7a[done]" else "\u00a77[ ]"
                                    ctx.source.sendFeedback(Component.literal("  $mark \u00a7f$name \u00a78($cooldown)"))
                                }
                                1
                            }
                    )
                    .then(
                        ClientCommands.literal("add")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "name")
                                        if (DailyReset.add(name)) {
                                            ctx.source.sendFeedback(Component.literal("[CC] Added daily: $name"))
                                        } else {
                                            ctx.source.sendFeedback(Component.literal("[CC] Already tracking: $name"))
                                        }
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("remove")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "name")
                                        if (DailyReset.remove(name)) {
                                            ctx.source.sendFeedback(Component.literal("[CC] Removed daily: $name"))
                                        } else {
                                            ctx.source.sendFeedback(Component.literal("[CC] No daily named: $name"))
                                        }
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("done")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "name")
                                        if (DailyReset.setCompleted(name, true)) {
                                            ctx.source.sendFeedback(Component.literal("[CC] Marked done: $name"))
                                        } else {
                                            ctx.source.sendFeedback(Component.literal("[CC] No daily named: $name"))
                                        }
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("undo")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "name")
                                        if (DailyReset.setCompleted(name, false)) {
                                            ctx.source.sendFeedback(Component.literal("[CC] Marked not done: $name"))
                                        } else {
                                            ctx.source.sendFeedback(Component.literal("[CC] No daily named: $name"))
                                        }
                                        1
                                    }
                            )
                    )
                    .then(
                        // Enable/disable ONE auto-tracked daily by name --
                        // the manual list has no toggle since it's already
                        // add/remove-able. This is separate from `done`:
                        // "done" clears at the next reset, "toggle off" hides
                        // it until you toggle it back on.
                        ClientCommands.literal("toggle")
                            .then(
                                ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "name")
                                        val currentlyOn = DailyReset.autoEntries()
                                            .any { (n, _, _) -> n.equals(name, ignoreCase = true) }
                                        if (!currentlyOn) {
                                            ctx.source.sendFeedback(Component.literal("[CC] No auto-tracked daily named: $name"))
                                        } else {
                                            // autoEntries() doesn't expose current enabled state directly
                                            // (it only lists enabled ones), so just flip based on presence.
                                            val nowEnabled = !DailyReset.autoEntries().any { (n, _, _) -> n.equals(name, ignoreCase = true) }
                                            DailyReset.setEntryEnabled(name, nowEnabled)
                                            ctx.source.sendFeedback(
                                                Component.literal("[CC] $name -> ${if (nowEnabled) "enabled" else "disabled"}")
                                            )
                                        }
                                        1
                                    }
                            )
                    )
            )

            .then(
                // Management for WarpShortcuts (the "/dh" -> "/warp dh"
                // module). The shortcuts themselves take effect the moment
                // they're typed -- no reconnect needed -- since they're
                // rewritten via ClientSendMessageEvents.MODIFY_COMMAND
                // rather than registered as Brigadier client commands.
                ClientCommands.literal("warp")
                    .then(
                        ClientCommands.literal("list")
                            .executes { ctx ->
                                val enabled = WarpShortcuts.currentEntries()
                                    .filter { it.enabled }
                                    .joinToString(", ") { it.alias }
                                ctx.source.sendFeedback(Component.literal("Enabled warp shortcuts: $enabled"))
                                1
                            }
                    )
                    .then(
                        ClientCommands.literal("add")
                            .then(
                                ClientCommands.argument("alias", StringArgumentType.word())
                                    .then(
                                        ClientCommands.argument("target", StringArgumentType.greedyString())
                                            .executes { ctx ->
                                                val alias = StringArgumentType.getString(ctx, "alias")
                                                val target = StringArgumentType.getString(ctx, "target")
                                                WarpShortcuts.addCustom(alias, target)
                                                ctx.source.sendFeedback(
                                                    Component.literal("Added shortcut: /$alias -> /$target")
                                                )
                                                1
                                            }
                                    )
                            )
                    )
                    .then(
                        ClientCommands.literal("remove")
                            .then(
                                ClientCommands.argument("alias", StringArgumentType.word())
                                    .executes { ctx ->
                                        val alias = StringArgumentType.getString(ctx, "alias")
                                        WarpShortcuts.removeCustom(alias)
                                        ctx.source.sendFeedback(Component.literal("Removed shortcut: /$alias"))
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("enable")
                            .then(
                                ClientCommands.argument("alias", StringArgumentType.word())
                                    .executes { ctx ->
                                        val alias = StringArgumentType.getString(ctx, "alias")
                                        WarpShortcuts.setEnabled(alias, true)
                                        ctx.source.sendFeedback(Component.literal("Enabled: /$alias"))
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("disable")
                            .then(
                                ClientCommands.argument("alias", StringArgumentType.word())
                                    .executes { ctx ->
                                        val alias = StringArgumentType.getString(ctx, "alias")
                                        WarpShortcuts.setEnabled(alias, false)
                                        ctx.source.sendFeedback(Component.literal("Disabled: /$alias"))
                                        1
                                    }
                            )
                    )
            )
    }
}
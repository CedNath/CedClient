package ced.cedclient.features.impl.loot

import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component

/**
 * Registers /cedloot (print summary) and /cedloot clear (reset LootTracker).
 * Call LootSummaryCommand.register() once from your client mod initializer.
 */
object LootSummaryCommand {

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("cedloot")
                    .executes { ctx -> showSummary(ctx) }
                    .then(
                        literal("clear").executes { ctx ->
                            LootTracker.reset()
                            ctx.source.sendFeedback(Component.literal("\u00a7aLoot tracker cleared."))
                            1
                        }
                    )
            )
        }
    }

    private fun showSummary(ctx: CommandContext<FabricClientCommandSource>): Int {
        LootTracker.buildSummaryLines().forEach {
            ctx.source.sendFeedback(Component.literal("\u00a7d$it"))
        }
        return 1
    }
}
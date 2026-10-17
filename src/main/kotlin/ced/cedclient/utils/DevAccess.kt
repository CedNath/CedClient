package ced.cedclient.utils

import ced.cedclient.state.IslandState
import ced.cedclient.state.Sidebar
import ced.cedclient.features.impl.funqol.AutoExperiments
import ced.cedclient.features.impl.funqol.SlayerCheats
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * Who gets what, by Minecraft account UUID (checked against the logged-in account at startup).
 * [isDev]: the developer only (dev commands). [isFriend]: dev + friends (the fishing helpers).
 */
object DevAccess {
    private val DEV = setOf(UUID.fromString("21f2e2c1-21b7-49e1-99d4-4c1eeb5ce539")) // CedNath
    private val FRIENDS = DEV + setOf(
        UUID.fromString("4e9324f4-782d-4708-a349-4818e2d6ea77"), // abhu
        UUID.fromString("b719162a-bd30-467d-8034-e480d440b007"), // cleeve
    )

    private val self: UUID? get() = Minecraft.getInstance().user?.profileId

    val isDev get() = self in DEV
    val isFriend get() = self in FRIENDS

    /** "/cc dev ...": only registered for the dev. Quick looks at what the mod reads from the game. */
    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> = ClientCommands.literal("dev")
        .requires { isDev }
        .then(ClientCommands.literal("sidebar").executes { ctx ->
            ctx.source.sendFeedback(Component.literal("§7Sidebar (${Sidebar.title}), area: ${Sidebar.area}"))
            Sidebar.lines.forEachIndexed { i, l -> ctx.source.sendFeedback(Component.literal("§8$i §f$l")) }
            1
        })
        .then(ClientCommands.literal("island").executes { ctx ->
            ctx.source.sendFeedback(Component.literal("§7Island: ${IslandState.island}"))
            1
        })
        .then(ClientCommands.literal("entities").executes { ctx ->
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return@executes 0
            mc.level?.entitiesForRendering()?.filter { it !== player && it.distanceToSqr(player) < 15.0 * 15.0 }
                ?.sortedBy { it.distanceToSqr(player) }?.take(30)?.forEach { e ->
                    val name = if (e.hasCustomName()) e.customName!!.string else e.type.description.string
                    ctx.source.sendFeedback(Component.literal("§8%.1fm §7%s §f%s".format(Math.sqrt(e.distanceToSqr(player)), e.type.description.string, name)))
                }
            1
        })
        .then(ClientCommands.literal("held").executes { ctx ->
            val stack = Minecraft.getInstance().player?.mainHandItem ?: return@executes 0
            ctx.source.sendFeedback(Component.literal("§7${stack.item} §f${stack.hoverName.string}"))
            ctx.source.sendFeedback(Component.literal("§7${stack.get(DataComponents.CUSTOM_DATA)}"))
            ctx.source.sendFeedback(Component.literal("§7model: ${stack.get(DataComponents.ITEM_MODEL)}"))
            1
        })
        // World time and which flower Farm Helper picks now (to check the 13000/23000 night cutoff against Hypixel).
        .then(ClientCommands.literal("time").executes { ctx ->
            val level = Minecraft.getInstance().level ?: return@executes 0
            val t = Math.floorMod(level.overworldClockTime, 24000L)
            val flower = ced.cedclient.farmhelper.util.PlayerUtils.cropOf(net.minecraft.world.level.block.Blocks.SUNFLOWER)
            ctx.source.sendFeedback(Component.literal("§7Time: §f$t §7(total ${level.overworldClockTime}), bright: ${level.isBrightOutside}, flower: §f$flower"))
            1
        })
        // Bug reporter (utils/debug/Diag): recent issues, a test report, send now, set a local webhook.
        .then(ClientCommands.literal("diag")
            .executes { ctx ->
                val list = ced.cedclient.utils.debug.Diag.recent(10)
                ctx.source.sendFeedback(Component.literal("§7Diag: ${list.size} recent issue(s), webhook ${if (ced.cedclient.utils.debug.Diag.webhookSet()) "§aset" else "§cnot set"}"))
                list.forEach { ctx.source.sendFeedback(Component.literal("§8x${it.count} §7${it.kind} §f${it.source}§7: ${it.message.take(100)}")) }
                1
            }
            .then(ClientCommands.literal("test").executes { ctx ->
                ced.cedclient.utils.debug.Diag.error("diag test", "Test report from /cc dev diag test", RuntimeException("test"))
                ctx.source.sendFeedback(Component.literal("§7Test issue recorded; §f/cc dev diag send §7to send it now"))
                1
            })
            .then(ClientCommands.literal("send").executes { ctx ->
                ced.cedclient.utils.debug.Diag.flushAsync()
                ctx.source.sendFeedback(Component.literal("§7Sending pending issues"))
                1
            })
            .then(ClientCommands.literal("webhook")
                .then(ClientCommands.argument("url", StringArgumentType.greedyString()).executes { ctx ->
                    ced.cedclient.utils.debug.Diag.setProp("webhook", StringArgumentType.getString(ctx, "url"))
                    ctx.source.sendFeedback(Component.literal("§7Webhook saved to config/cedclient-diag.properties"))
                    1
                })))
        // Auto Experiments debug lines in chat + log (until restart).
        .then(ClientCommands.literal("expdebug").executes { ctx ->
            AutoExperiments.debug = !AutoExperiments.debug
            ctx.source.sendFeedback(Component.literal("§7Auto Experiments debug: ${if (AutoExperiments.debug) "§aon" else "§coff"}"))
            1
        })
        // Slayer Helper Mania Walk: min blocks between the picked green tile and the boss (until restart).
        .then(ClientCommands.literal("maniadist")
            .executes { ctx ->
                ctx.source.sendFeedback(Component.literal("§7Mania min distance: §f${SlayerCheats.maniaMinDist} §7blocks"))
                1
            }
            .then(ClientCommands.argument("blocks", DoubleArgumentType.doubleArg(0.0, 10.0)).executes { ctx ->
                SlayerCheats.maniaMinDist = DoubleArgumentType.getDouble(ctx, "blocks")
                ctx.source.sendFeedback(Component.literal("§7Mania min distance set to §f${SlayerCheats.maniaMinDist} §7blocks"))
                1
            }))
}

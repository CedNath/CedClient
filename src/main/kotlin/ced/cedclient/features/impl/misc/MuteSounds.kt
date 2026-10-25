package ced.cedclient.features.impl.misc

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Debug
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style

/**
 * Silences noisy sounds by ID. The list takes full IDs or parts ("entity.bat" mutes every bat sound). Add to it from
 * the settings, "Mute Last Sound", "/cc mute <id>", or the [mute] button on the lines "Log Played Sounds" prints.
 * Hooked from SoundEngineMixin, so it covers server packets and client-side sounds alike.
 */
object MuteSounds : Module(
    "Mute Sounds",
    Category.Misc,
    "Silences sounds you pick by ID, with a one-click mute for what you just heard."
) {
    private val list = TextSetting("Muted Sounds", "", "Comma-separated sound IDs or parts, e.g. entity.bat.ambient, block.note_block", maxLength = 2000)
    private val log = BooleanSetting("Log Played Sounds", false, "Prints each sound in chat the first time it plays, with a [mute] button.")
    private val muteLast = ActionSetting("Mute Last Sound", "Adds the last sound you heard to the list.") { muteLastHeard() }
    private val clearAll = ActionSetting("Unmute All", "Empties the list.") { setEntries(emptyList()) }

    private val mc get() = Minecraft.getInstance()
    private val recent = ArrayDeque<String>()
    private val announced = HashSet<String>()
    private val loggedMutes = HashSet<String>()

    private var parsedFrom = ""
    private var parsed = emptyList<String>()

    private fun entries(): List<String> {
        if (list.value != parsedFrom) {
            parsedFrom = list.value
            parsed = parsedFrom.split(',').map { it.trim().lowercase().removePrefix("minecraft:") }.filter { it.isNotEmpty() }
        }
        return parsed
    }

    private fun setEntries(e: List<String>) {
        list.value = e.joinToString(", ")
        ConfigManager.saveModulesOnly()
    }

    private fun short(id: String) = id.removePrefix("minecraft:")

    /** True if this sound should not start at all. */
    @JvmStatic
    fun shouldCancel(instance: SoundInstance): Boolean {
        if (!isEnabled) return false
        val id = short(instance.identifier.toString())
        val hit = entries().any { id.contains(it) }
        if (hit) {
            if (loggedMutes.add(id)) Debug.line("MuteSounds", "muted $id")
            return true
        }
        if (recent.lastOrNull() != id) {
            recent.remove(id)
            recent.addLast(id)
            if (recent.size > 30) recent.removeFirst()
        }
        if (log.value && announced.add(id)) mc.player?.sendSystemMessage(announce(id))
        return false
    }

    private fun announce(id: String): Component = ChatPrefix.msg("§7sound §f$id ").append(
        Component.literal("§c[mute]").withStyle(Style.EMPTY.withClickEvent(ClickEvent.RunCommand("/cc mute $id")))
    )

    private fun muteLastHeard() {
        val id = recent.lastOrNull()
        say(if (id == null) "§7No sound heard yet." else mute(id))
    }

    private fun mute(raw: String): String {
        val id = short(raw.trim().lowercase())
        if (id.isEmpty()) return "§7Nothing to mute."
        val now = entries()
        if (id in now) return "§7Already muted: §f$id"
        setEntries(now + id)
        Debug.line("MuteSounds", "added $id to the list")
        return "§aMuted §f$id §7(/cc unmute $id to undo)"
    }

    private fun unmute(raw: String): String {
        val id = short(raw.trim().lowercase())
        val now = entries()
        if (id !in now) return "§7Not in the list: §f$id"
        setEntries(now - id)
        Debug.line("MuteSounds", "removed $id from the list")
        return "§aUnmuted §f$id"
    }

    private fun say(text: String) = mc.player?.sendSystemMessage(ChatPrefix.msg(text))

    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("mute")
            .executes { ctx ->
                ctx.source.sendFeedback(ChatPrefix.msg("§7Muted: §f" + (entries().joinToString(", ").ifEmpty { "nothing" }) + " §7(/cc mute <id>, /cc mute last, /cc unmute <id>)"))
                1
            }
            .then(ClientCommands.literal("last").executes { muteLastHeard(); 1 })
            .then(ClientCommands.argument("sound", StringArgumentType.greedyString()).executes { ctx ->
                ctx.source.sendFeedback(ChatPrefix.msg(mute(StringArgumentType.getString(ctx, "sound"))))
                1
            })

    fun unmuteCommandTree(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("unmute")
            .then(ClientCommands.argument("sound", StringArgumentType.greedyString()).executes { ctx ->
                ctx.source.sendFeedback(ChatPrefix.msg(unmute(StringArgumentType.getString(ctx, "sound"))))
                1
            })

    init {
        addSettings(list, log, muteLast, clearAll)
    }
}

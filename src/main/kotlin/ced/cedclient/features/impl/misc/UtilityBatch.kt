package ced.cedclient.features.impl.misc

import ced.cedclient.accessor.ChatHistoryAccess
import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.funqol.hovered
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.mixin.accessor.GuiAccessor
import ced.cedclient.mixin.accessor.PlayerTabOverlayAccessor
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Debug
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtOps
import net.minecraft.network.chat.Component
import net.minecraft.util.ProblemReporter
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.scores.DisplaySlot
import ced.cedclient.utils.Keys
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * Small utility modules: Scoreboard Toggle, Tab List Toggle, Hotbar Keybinds, Copy Item Data, Copy Entity Data, Dumpers.
 * Keys are KeybindSettings polled while no screen is open (same as Zoom, SDL scancodes), so they are rebindable in the ClickGUI.
 */

private fun mcNow(): Minecraft = Minecraft.getInstance()

private fun keyDown(code: Int): Boolean {
    return mcNow().gui.screen() == null && Keys.isDown(code)
}

/** True once per key press (the tick it goes down). */
private class KeyEdge {
    private var was = false
    fun pressed(code: Int): Boolean {
        val down = keyDown(code)
        val edge = down && !was
        was = down
        return edge
    }
}

private fun say(module: String, text: String) {
    mcNow().player?.sendSystemMessage(ChatPrefix.msgFor(module, text))
}

private fun copy(module: String, text: String, what: String) {
    mcNow().keyboardHandler.clipboard = text
    Debug.line(module, "copied $what, ${text.length} chars")
    say(module, "§aCopied §f$what §7(${text.length} characters)")
}

// ------------------------------------------------------------------------------------------------ Scoreboard Toggle

object ScoreboardToggle : Module(
    "Scoreboard Toggle", Category.Misc,
    "Hides the sidebar scoreboard. A key shows it again (or shows it while you hold the key)."
) {
    private val key = KeybindSetting("Toggle Key", Keys.UNKNOWN, "Shows / hides the scoreboard.")
    private val mode = DropdownSetting("Mode", listOf("Toggle", "Hold to Show"), "Toggle")
    private val edge = KeyEdge()
    private var hidden = true

    init {
        addSettings(key, mode)
        // Wraps whatever draws the scoreboard (vanilla or Compact Scoreboard). Registered after Compact Scoreboard in
        // CedClient.registerModules so this wrapper is the outer one.
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD) { inner ->
            HudElement { graphics, tick -> if (!isHidden()) inner.extractRenderState(graphics, tick) }
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            if (edge.pressed(key.value) && mode.value == "Toggle") {
                hidden = !hidden
                Debug.line(name, if (hidden) "scoreboard hidden" else "scoreboard shown")
            }
        }
    }

    override fun onEnable() { hidden = true }

    private fun isHidden(): Boolean {
        if (!isEnabled) return false
        return if (mode.value == "Hold to Show") !keyDown(key.value) else hidden
    }
}

// ------------------------------------------------------------------------------------------------ Tab List Toggle

object TabListToggle : Module(
    "Tab List Toggle", Category.Misc,
    "Press the player list key once to keep the tab list open, press again to close it."
) {
    private var latched = false
    private var wasDown = false

    override fun onDisable() { latched = false; wasDown = false }

    /** Mixin (Gui.extractTabList): the player list key's state in, whether the tab list shows out. Runs once per frame. */
    @JvmStatic
    fun shown(vanillaDown: Boolean): Boolean {
        if (!isEnabled) return vanillaDown
        if (vanillaDown && !wasDown) {
            latched = !latched
            Debug.line(name, if (latched) "tab list latched open" else "tab list closed")
        }
        wasDown = vanillaDown
        return latched
    }
}

// ------------------------------------------------------------------------------------------------ Hotbar Keybinds

object HotbarKeybinds : Module(
    "Hotbar Keybinds", Category.General,
    "Extra keys for the hotbar slots, on top of the number keys. Keyboard keys only."
) {
    private val keys = (1..9).map { KeybindSetting("Slot $it", Keys.UNKNOWN, "Selects hotbar slot $it.") }
    private val edges = List(9) { KeyEdge() }

    init {
        addSettings(*keys.toTypedArray())
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Always poll, so a key held while the module was off doesn't count as a fresh press.
            val pressed = edges.indices.filter { edges[it].pressed(keys[it].value) }
            val player = client.player
            if (!isEnabled || player == null) return@register
            pressed.lastOrNull()?.let {
                player.inventory.selectedSlot = it
                Debug.line(name, "selected slot ${it + 1}")
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Copy Item Data

object CopyItemData : Module(
    "Copy Item Data", Category.Dev,
    "Hover an item in a menu (or hold it) and press the key to copy its full data to the clipboard."
) {
    private val key = KeybindSetting("Copy Key", Keys.UNKNOWN, "Copies the hovered item, or the held item when no menu is open.")
    private val edge = KeyEdge()

    init {
        addSettings(key)
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenKeyboardEvents.allowKeyPress(screen).register { s, event ->
                if (!isEnabled || key.value <= 0 || event.key != key.value) return@register true
                val stack = hovered(s)?.item?.takeUnless { it.isEmpty } ?: return@register true
                copyStack(stack)
                false
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (edge.pressed(key.value) && isEnabled) {
                val held = client.player?.mainHandItem?.takeUnless { it.isEmpty } ?: return@register
                copyStack(held)
            }
        }
    }

    private fun copyStack(stack: ItemStack) {
        val level = mcNow().level ?: return
        val ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE)
        val tag = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null)
        if (tag == null) { say(name, "§cCouldn't read that item."); return }
        copy(name, tag.toString(), stack.hoverName.string)
    }
}

// ------------------------------------------------------------------------------------------------ Copy Entity Data

object CopyEntityData : Module(
    "Copy Entity Data", Category.Dev,
    "Look at an entity and press the key to copy the data the client has for it to the clipboard."
) {
    private val key = KeybindSetting("Copy Key", Keys.UNKNOWN, "Copies the entity under your crosshair.")
    private val edge = KeyEdge()

    init {
        addSettings(key)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!edge.pressed(key.value) || !isEnabled) return@register
            val entity = client.crosshairPickEntity
            if (entity == null) { say(name, "§cNot looking at an entity."); return@register }
            val text = runCatching {
                val out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess())
                entity.saveWithoutId(out)
                buildString {
                    appendLine("type: ${BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)}")
                    appendLine("name: ${entity.name.string}")
                    appendLine("uuid: ${entity.uuid}")
                    appendLine("id: ${entity.id}")
                    appendLine("pos: ${entity.x}, ${entity.y}, ${entity.z}")
                    append(out.buildResult())
                }
            }.getOrNull()
            if (text == null) { say(name, "§cCouldn't read that entity."); return@register }
            copy(name, text, entity.name.string)
        }
    }
}

// ------------------------------------------------------------------------------------------------ Dumpers

object Dumpers : Module(
    "Dumpers", Category.Dev,
    "Keys that save the scoreboard, tab list, action bar or chat to a file in config/cedclient/dumps and copy it."
) {
    private val scoreboardKey = KeybindSetting("Scoreboard Key", Keys.UNKNOWN)
    private val tabKey = KeybindSetting("Tab List Key", Keys.UNKNOWN)
    private val actionBarKey = KeybindSetting("Action Bar Key", Keys.UNKNOWN)
    private val chatKey = KeybindSetting("Chat Key", Keys.UNKNOWN)
    private val edges = List(4) { KeyEdge() }
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    init {
        addSettings(scoreboardKey, tabKey, actionBarKey, chatKey)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val pressed = listOf(scoreboardKey, tabKey, actionBarKey, chatKey).mapIndexed { i, k -> edges[i].pressed(k.value) }
            if (!isEnabled || client.player == null) return@register
            if (pressed[0]) dump("scoreboard", scoreboard())
            if (pressed[1]) dump("tab list", tabList())
            if (pressed[2]) dump("action bar", actionBar())
            if (pressed[3]) dump("chat", chat())
        }
    }

    private fun dump(what: String, text: String?) {
        if (text == null) { say(name, "§cNothing to dump for the $what."); return }
        val file = File(CedPaths.dir, "dumps/${what.replace(' ', '-')}-${LocalDateTime.now().format(stamp)}.txt")
        val saved = runCatching { file.parentFile.mkdirs(); file.writeText(text) }.isSuccess
        mcNow().keyboardHandler.clipboard = text
        Debug.line(name, "$what dumped, ${text.length} chars, file saved=$saved")
        say(name, if (saved) "§aDumped the $what §7to ${file.name} and the clipboard" else "§eCopied the $what §7(couldn't write the file)")
    }

    private fun scoreboard(): String? {
        val sb = mcNow().level?.scoreboard ?: return null
        val objective = sb.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
        val lines = sb.listPlayerScores(objective).sortedByDescending { it.value() }.map { entry ->
            val team = sb.getPlayersTeam(entry.owner())
            (if (team != null) Component.empty().append(team.playerPrefix).append(entry.ownerName()).append(team.playerSuffix)
            else entry.ownerName()).string
        }
        return (listOf(objective.displayName.string) + lines).joinToString("\n")
    }

    private fun tabList(): String? {
        val overlay = mcNow().gui.hud.tabList
        val acc = overlay as PlayerTabOverlayAccessor
        val names = acc.`ced$playerInfos`().map { overlay.getNameForDisplay(it).string }
        if (names.isEmpty()) return null
        return buildList {
            acc.`ced$getHeader`()?.string?.let { add("[header] $it") }
            addAll(names)
            acc.`ced$getFooter`()?.string?.let { add("[footer] $it") }
        }.joinToString("\n")
    }

    private fun actionBar(): String? = (mcNow().gui.hud as GuiAccessor).`ced$getOverlayMessage`()?.string

    private fun chat(): String? {
        // allMessages is newest first; the file reads oldest first.
        val lines = (mcNow().gui.hud.chat as ChatHistoryAccess).`cedclient$allMessages`().asReversed().map { it.content().string }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}

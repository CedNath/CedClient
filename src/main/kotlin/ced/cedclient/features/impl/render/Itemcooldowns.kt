package ced.cedclient.features.impl.render

import ced.cedclient.data.ClickType
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.utils.Color
import ced.cedclient.utils.dungeons.DungeonState
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import kotlin.math.ceil

// NOTE -- PlaySoundEvent and ItemClickEvent both exist and are posted:
// ClientPacketListenerMixin posts PlaySoundEvent on ClientboundSoundPacket,
// MultiPlayerGameModeMixin posts ItemClickEvent(RIGHT_CLICK) from useItem(),
// and MinecraftMixin posts ItemClickEvent(LEFT_CLICK) from startAttack(). The
// sound-based path (SoundSignature) is still dead code regardless -- every
// entry's soundSignature is null (see the class doc below) -- so activation
// currently relies entirely on ItemClickEvent, with chat as a fallback/resync.
import ced.cedclient.events.PlaySoundEvent
import ced.cedclient.events.ItemClickEvent

/**
 * A specific (soundName, pitch, volume) fingerprint Hypixel plays the instant
 * an ability activates. This is what lets cooldowns start on the exact tick
 * the ability fires, instead of waiting on a chat message.
 *
 * pitch/volume are optional -- some sounds are distinctive by name alone,
 * others need pitch/volume to disambiguate from unrelated vanilla sounds
 * that share the same name (see SkyHanni's onPlaySound for real examples of
 * this, e.g. multiple abilities all using "random.eat" at different pitches).
 */
data class SoundSignature(
    val soundName: String,
    val pitch: Float? = null,
    val volume: Float? = null,
    val tolerance: Float = 0.01f,
) {
    fun matches(eventSoundName: String, eventPitch: Float, eventVolume: Float): Boolean {
        if (eventSoundName != soundName) return false
        if (pitch != null && kotlin.math.abs(eventPitch - pitch) > tolerance) return false
        if (volume != null && kotlin.math.abs(eventVolume - volume) > tolerance) return false
        return true
    }
}


enum class ItemAbility(
    val cooldownSeconds: Int,
    vararg val itemNameFragments: String,
    val clickType: ClickType = ClickType.RIGHT_CLICK,
    val ignoreMageCooldownReduction: Boolean = false,
    val soundSignature: SoundSignature? = null,
) {
    ALERT_FLARE(20, "Warning Flare", soundSignature = SoundSignature("minecraft:entity.firework_rocket.launch", 1.0f, 3.0f)), // confirmed same sound as SOS_FLARE
    // Activation plays an ascending ghast.scream sweep (0.49 -> 0.89); ~4s
    // later (this ability's own cooldown length) a second, shorter burst
    // replays just the high end of that sweep as an "ability over" cue.
    // Matching only the very first/lowest pitch avoids the end-burst
    // entirely -- and even if it didn't, onMatchedSound()'s 400ms ping
    // window would reject a sound that far after the click regardless.
    ATOMSPLIT_KATANA(4, "Atomsplit Katana", "Vorpal Katana", "Voidedge Katana", ignoreMageCooldownReduction = true, soundSignature = SoundSignature("minecraft:entity.ghast.scream", 0.4920635f, 0.15f)),
    ECHO(3, "Ancestral Spade"),
    EMBER_ROD(30, "Ember Rod"),
    ENDER_BOW(5, "Ender Bow"),
    END_STONE_SWORD(5, "End Stone Sword"),
    ENRAGER(20, "Enrager"),
    // Ambient freeze-ray loop -- keeps firing for the whole duration the
    // beam is active, but the first instance lands within the click's
    // 400ms ping window (onMatchedSound's own check), which is all that's
    // needed to confirm the cast.
    FIRE_FREEZE_STAFF(10, "Fire Freeze Staff", soundSignature = SoundSignature("minecraft:entity.elder_guardian.ambient", 2.0f, 0.2f)),
    FIRE_FURY_STAFF(20, "Fire Fury Staff"),
    FIRE_VEIL(5, "Fire Veil Wand"),
    GIANTS_SWORD(30, "Giant's Sword"),
    GOLEM_SWORD(3, "Golem Sword", soundSignature = SoundSignature("minecraft:entity.generic.explode", 4.047619f, 0.2f)),

    // Right-click plays a 3-pitch burst of the same sound (1.587/1.746/1.889) --
    // only need to match one of the three, the other two just won't hit
    // anything and are harmlessly ignored.
    GYROKINETIC_WAND(10, "Gyrokinetic Wand", soundSignature = SoundSignature("minecraft:entity.zombie.infect", 1.5873016f, 1.0f)),
    // Left-click's spin-up plays dozens of enderman.teleport sounds sweeping
    // pitch upward, but the actual activation moment is marked by one
    // distinct outlier line: pitch=0.0, volume=8.0. Confirmed against a real
    // capture where this exact line landed in the same instant Hypixel's
    // "still on cooldown" chat warning fired for this ability.
    // Confirmed by Nath as the genuine activation sound for this ability.
    // Caution: this exact sound (entity.enderman.teleport, pitch=0.0,
    // volume=8.0) has also been observed alongside unrelated failure chats
    // (Livid Dagger + "still on cooldown", Voodoo Doll + "No target
    // found!"), so it may be a shared UI chime Hypixel reuses rather than
    // something unique to this wand. If spam-clicking this wand while it's
    // already on cooldown is ever seen to keep pushing the countdown back
    // out further than expected, this is the first signature to suspect.
    GYROKINETIC_WAND_LEFT(30, "Gyrokinetic Wand", clickType = ClickType.LEFT_CLICK, soundSignature = SoundSignature("minecraft:entity.enderman.teleport", 0.0f, 8.0f)),
    HOLY_ICE(4, "Holy Ice"),
    // Teleport+explosion plays a paired generic.explode + zombie_villager.cure
    // -- using the cure sound since generic.explode at pitch=1.0/volume=1.0
    // is too generic on its own (other abilities reuse that exact sound at
    // different pitches, see STAFF_OF_THE_VOLCANO/GOLEM_SWORD above).
    HYPERION(5, "Hyperion", "Scylla", "Valkyrie", "Astraea", ignoreMageCooldownReduction = true, soundSignature = SoundSignature("minecraft:entity.zombie_villager.cure", 0.6984127f, 1.0f)),
    ICE_SPRAY_WAND(5, "Ice Spray Wand", soundSignature = SoundSignature("minecraft:entity.ender_dragon.growl", 1.0f, 1.0f)),
    // Confirmed no distinct activation sound -- click-only, no signature to add.
    INFINILEAP(2, "Infinileap", ignoreMageCooldownReduction = true),
    INK_WAND(30, "Ink Wand"),
    LIVID_DAGGER(5, "Livid Dagger"),
    PIGMAN_SWORD(5, "Pigman Sword"),
    // Charging plays 3x block.lever.click, but the actual throw/release is
    // this wolf.death -- confirmed by an Odin "Gained strength" line landing
    // right after it in the capture.
    RAGNAROCK_AXE(20, "Ragnarock", soundSignature = SoundSignature("minecraft:entity.wolf.death", 1.4920635f, 1.0f)),
    ROGUE_SWORD(30, "Rogue Sword", ignoreMageCooldownReduction = true, soundSignature = SoundSignature("minecraft:block.lava.pop", 2.0f, 0.4f)),
    ROYAL_PIGEON(5, "Royal Pigeon"),
    SHADOW_FURY(15, "Shadow Fury"),
    SOS_FLARE(10, "SOS Flare", soundSignature = SoundSignature("minecraft:entity.firework_rocket.launch", 1.0f, 3.0f)),
    SOUL_ESOWARD(20, "Soul Esoward"),
    STAFF_OF_THE_VOLCANO(30, "Staff of the Volcano", soundSignature = SoundSignature("minecraft:entity.generic.explode", 0.4920635f, 0.5f)),
    STARLIGHT_WAND(2, "Starlight Wand"),
    SWORD_OF_BAD_HEALTH(5, "Sword of Bad Health", ignoreMageCooldownReduction = true, soundSignature = SoundSignature("minecraft:entity.generic.eat", 1.0f, 1.0f)),
    TACTICAL_INSERTION(20, "Tactical Insertion", soundSignature = SoundSignature("minecraft:item.flintandsteel.use", 0.74603176f, 1.0f)),
    TALBOTS_THEODOLITE(10, "Talbot's Theodolite"),
    TOTEM_OF_CORRUPTION(20, "Totem of Corruption"),
    VOODOO_DOLL(5, "Voodoo Doll"),
    // "Wilted Voodoo Doll" also contains the substring "Voodoo Doll", so it'd
    // match VOODOO_DOLL too -- see the specificity rule in abilitiesFor() below,
    // which picks whichever matched fragment is longer/more specific.
    VOODOO_DOLL_WILTED(3, "Wilted Voodoo Doll"),
    WAND_OF_ATONEMENT(6, "Wand of Atonement", ignoreMageCooldownReduction = true, soundSignature = SoundSignature("minecraft:block.lava.pop", 0.7619048f, 0.15f)),
    WAND_OF_HEALING(5, "Wand of Healing", soundSignature = SoundSignature("minecraft:block.lava.pop", 0.7619048f, 0.15f)), // confirmed same sound as WAND_OF_ATONEMENT
    WAND_OF_MENDING(5, "Wand of Mending", soundSignature = SoundSignature("minecraft:block.lava.pop", 0.7619048f, 0.15f)), // confirmed same sound as WAND_OF_ATONEMENT
    WAND_OF_RESTORATION(6, "Wand of Restoration", soundSignature = SoundSignature("minecraft:block.lava.pop", 0.7619048f, 0.15f)), // confirmed same sound as WAND_OF_ATONEMENT
    WAND_OF_STRENGTH(10, "Wand of Strength"), // strength buff, not a healing wand -- not covered by the above grouping
    WEIRDER_TUBA(30, "Weirder Tuba", ignoreMageCooldownReduction = true, soundSignature = SoundSignature("minecraft:entity.wolf.death", 1.5079365f, 0.5f)),
    WEIRD_TUBA(20, "Weird Tuba", ignoreMageCooldownReduction = true),
    WITHER_CLOAK(10, "Wither Cloak");


    var cooldownEndMillis: Long = 0L
        private set


    var lastItemClick: Long = 0L


    fun startCooldown(seconds: Double) {
        cooldownEndMillis = System.currentTimeMillis() + (seconds * 1000).toLong()
    }


    fun startCooldown() =
        startCooldown(cooldownSeconds * DungeonState.mageCooldownMultiplier(ignoreMageCooldownReduction))

    fun isOnCooldown(): Boolean = cooldownEndMillis > System.currentTimeMillis()


    fun clearCooldown() {
        cooldownEndMillis = 0L
    }

    fun secondsLeft(): Int? {
        val remainingMs = cooldownEndMillis - System.currentTimeMillis()
        if (remainingMs <= 0) return null
        return ceil(remainingMs / 1000.0).toInt()
    }

    fun onMatchedSound() {
        val ping = System.currentTimeMillis() - lastItemClick
        if (ping in 0..400) startCooldown()
    }
}

object ItemCooldowns : Module(
    "Item Cooldowns",
    Category.Render,
    "Shows a countdown on items whose ability is on cooldown, inspired by SkyHanni.",
    defaultEnabled = false
) {

    private val showOnHotbar = BooleanSetting("Show On Hotbar", true)
    private val showInInventory = BooleanSetting("Show In Inventory", true)
    private val showReadyIndicator = BooleanSetting(
        "Show Ready Indicator",
        true,
        "Shows a green \"R\" on ability items that are off cooldown, not just the countdown while on cooldown."
    )
    private val readyColor = ColorSetting(
        "Ready Color",
        Color(85, 255, 85), // same green as before (0xFF55FF55)
        "Color of the \"R\" shown on ability items that are off cooldown."
    )
    private val cooldownColor = ColorSetting(
        "Cooldown Color",
        Color(110, 50, 200), // same purple as ClickGUI.clickGUIColor, just a separate customizable copy now
        "Color of the countdown number shown on ability items that are on cooldown."
    )
    private val logUnmatched = BooleanSetting(
        "Log Unmatched Cooldown Messages",
        false,
        "Prints any chat message containing \"cooldown\" to the console -- use this to find the exact " +
                "wording this server sends if cooldowns aren't being detected."
    )
    private val logAllSounds = BooleanSetting(
        "Log All Sounds",
        false,
        "Prints every sound the client plays to the console, as (name, pitch, volume). Trigger an " +
                "ability once with this on to find the exact SoundSignature to add to ItemAbility."
    )
    private val logAbilityCasts = BooleanSetting(
        "Log Ability Casts",
        false,
        "Prints one debug line per ability click to console, listing the item, which ItemAbility it " +
                "matched, and every sound that played in the following second -- use this to capture real " +
                "SoundSignature data: turn it on, use every ability item once each, then grab the lines " +
                "from the log."
    )

    // Anchors only on the "cooldown for X (more) seconds" core, so it survives
    // most variation in the prefix wording ("This item is...", "This ability
    // is...", etc.).
    private val genericCooldownRegex =
        Regex("""cooldown for (?<seconds>[\d.]+) (?:more )?seconds?""", RegexOption.IGNORE_CASE)

    // Dungeon "Ultimate" enchant cooldown -- confirmed wording from Chatfilter.kt.
    private val ultimateCooldownRegex =
        Regex("""Your Ultimate is currently on cooldown for (?<seconds>[\d.]+) more seconds\.""")

    // Hypixel's "your click did NOT actually cast anything" lines -- exact
    // wording lifted from ChatFilter's own defaultPatterns, since those are
    // already confirmed-correct against the live server. Until real
    // SoundSignature data exists (see the class doc above), handleItemClick()
    // has no positive way to confirm an ability actually fired -- it starts
    // the cooldown optimistically on every click. This is the negative
    // signal that walks that optimistic start back: if one of these shows up
    // right after a click, the click didn't do anything, so the cooldown it
    // started gets cancelled. Once SoundSignature entries are filled in,
    // this becomes a secondary safety net rather than the only check.
    private val abilityFailureRegex = listOf(
        Regex("""This item's ability is temporarily disabled!"""),
        Regex("""You cannot use abilities in this room!"""),
        Regex("""You cannot do that in this room!"""),
        Regex("""You do not have enough mana to do this!"""),
        Regex("""You need at least .+ mana to activate this!""")
    )

    // How long after a click a failure message still counts as "caused by
    // that click" -- same style of ping-window correlation onMatchedSound()
    // already uses for sound confirmation (400ms), just slightly wider since
    // chat can lag a little more than a sound packet.
    private const val CLICK_FAILURE_WINDOW_MS = 500L

    // -------------------------
    // Sound capture debug tool ("Log Ability Casts") -- correlates a click
    // with every sound that plays in the following second, then prints one
    // consolidated line to console. Gated entirely behind logAbilityCasts;
    // when off, none of this runs (handleSound's existing hot path is
    // untouched).
    // -------------------------

    // Generous on purpose -- some abilities play a second "impact"/"land"
    // sound noticeably after the initial cast sound (Hyperion's teleport +
    // explosion, for instance), and it's better to capture one extra
    // unrelated sound than to cut a real one off.
    private const val CAPTURE_WINDOW_MS = 1000L

    private data class CapturedSound(val soundName: String, val pitch: Float, val volume: Float, val offsetMs: Long)

    private data class PendingCapture(
        val itemName: String,
        val ability: ItemAbility,
        val clickType: ClickType,
        val clickTimeMs: Long,
        val wasOnCooldownAtClick: Boolean,
        val sounds: MutableList<CapturedSound> = mutableListOf()
    )

    private var pendingCapture: PendingCapture? = null

    private fun startCaptureIfNeeded(stack: ItemStack, clickType: ClickType) {
        if (!logAbilityCasts.value) return
        // Only capture for items that already resolve to a known ItemAbility
        // -- there's no point logging every random right-click on a block or
        // vanilla item, and having the resolved enum in the output is the
        // whole point (it's what tells me which ItemAbility entry to fill in).
        val ability = abilitiesFor(stack).firstOrNull { it.clickType == clickType } ?: return
        pendingCapture = PendingCapture(
            itemName = cleanName(stack) ?: ability.name,
            ability = ability,
            clickType = clickType,
            clickTimeMs = System.currentTimeMillis(),
            wasOnCooldownAtClick = ability.isOnCooldown()
        )
    }

    private fun flushCaptureIfDue() {
        val capture = pendingCapture ?: return
        if (System.currentTimeMillis() - capture.clickTimeMs < CAPTURE_WINDOW_MS) return

        val soundsText = if (capture.sounds.isEmpty()) {
            "none"
        } else {
            capture.sounds.joinToString(" | ") { s ->
                "${s.soundName} p=${"%.4f".format(s.pitch)} v=${"%.4f".format(s.volume)} +${s.offsetMs}ms"
            }
        }

        println(
            "[ItemCooldowns DEBUG] item=\"${capture.itemName}\" ability=${capture.ability.name} " +
                    "click=${capture.clickType} onCooldownAtClick=${capture.wasOnCooldownAtClick} sounds=[$soundsText]"
        )

        pendingCapture = null
    }

    // NOTE: ItemCooldowns is itself a Kotlin `object`, and objects can't hold a
    // nested `companion object` (that's a class-only construct) -- not that it
    // matters anymore, since both colors are now ColorSettings (above) rather
    // than fixed constants, so there's nothing left to put in one.

    private val armorSlotsTopToBottom = listOf(
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET
    )

    // Mirrors DungeonState's own lastLevel tracking (same proven technique --
    // reference-compare the ClientLevel instance each tick). Any world swap
    // (leaving a dungeon, re-entering one, changing servers/instances, etc.)
    // means whatever cooldowns we were tracking client-side no longer apply,
    // so every ability resets to "ready" the instant the level changes.
    private var lastLevel: ClientLevel? = null

    init {
        on<ChatMessageEvent> { event -> handleChatMessage(event.unformattedText) }

        // TODO: verify PlaySoundEvent / ItemClickEvent exist with these field
        // names -- see the CAVEAT at the top of the file.
        on<PlaySoundEvent> { event -> handleSound(event.soundName, event.pitch, event.volume) }
        on<ItemClickEvent> { event -> event.itemInHand?.let { handleItemClick(it, event.clickType) } }

        ClientTickEvents.END_CLIENT_TICK.register {
            val currentLevel = Minecraft.getInstance().level
            if (currentLevel !== lastLevel) {
                lastLevel = currentLevel
                for (ability in ItemAbility.entries) {
                    ability.clearCooldown()
                }
            }

            // flushCaptureIfDue() only actually prints once CAPTURE_WINDOW_MS
            // has passed since the click, so it needs to be polled somewhere
            // -- tick is the natural place, same as the level-swap check above.
            flushCaptureIfDue()
        }

        addSettings(showOnHotbar, showInInventory, showReadyIndicator, readyColor, cooldownColor, logUnmatched, logAllSounds)
    }

    private fun handleChatMessage(text: String) {
        if (logUnmatched.value && text.contains("cooldown", ignoreCase = true)) {
            println("[ItemCooldowns] cooldown message: \"$text\"")
        }

        // Check failure signals BEFORE the cooldown regexes -- a message
        // like "You do not have enough mana to do this!" never mentions
        // "cooldown" at all, so there's no overlap risk, but it needs to run
        // first regardless since it's cancelling a cooldown the click may
        // have just optimistically started, not reporting a real one.
        if (abilityFailureRegex.any { it.containsMatchIn(text) }) {
            val now = System.currentTimeMillis()
            for (ability in ItemAbility.entries) {
                if (now - ability.lastItemClick in 0..CLICK_FAILURE_WINDOW_MS) {
                    ability.clearCooldown()
                }
            }
            return
        }

        val match = ultimateCooldownRegex.find(text) ?: genericCooldownRegex.find(text) ?: return
        val seconds = match.groups["seconds"]?.value?.toDoubleOrNull() ?: return

        // NOTE: unlike handleItemClick, this can't tell which click actually
        // triggered the warning -- so for a dual-ability item like the
        // Gyrokinetic Wand, both its abilities would get resynced to the same
        // `seconds` value here, even though only one of them actually just
        // fired. Rare edge case (this path only runs on the "still on
        // cooldown" chat warning), not worth the complexity to fix yet.
        val heldItem = Minecraft.getInstance().player?.mainHandItem ?: return
        for (ability in abilitiesFor(heldItem)) {
            ability.startCooldown(seconds)
        }
    }

    private fun handleSound(soundName: String, pitch: Float, volume: Float) {
        if (logAllSounds.value) {
            // Held-item name included so sounds can be correlated back to an
            // item by eye, without depending on ItemClickEvent/abilitiesFor
            // matching anything -- useful precisely when that click-based
            // correlation isn't firing (or the item isn't in the enum yet).
            val heldItemName = Minecraft.getInstance().player?.mainHandItem?.let { cleanName(it) } ?: "empty hand"
            println("[ItemCooldowns] item=\"$heldItemName\" sound=\"$soundName\" pitch=$pitch volume=$volume")
        }

        // Feed the pending capture (if any) regardless of soundSignature --
        // capturing every sound in the window, not just ones that already
        // match a filled-in signature, is the whole point: signatures aren't
        // filled in yet, so this is how real ones get found.
        pendingCapture?.let { capture ->
            val offset = System.currentTimeMillis() - capture.clickTimeMs
            if (offset in 0..CAPTURE_WINDOW_MS) {
                capture.sounds.add(CapturedSound(soundName, pitch, volume, offset))
            }
        }

        for (ability in ItemAbility.entries) {
            val sig = ability.soundSignature ?: continue
            if (sig.matches(soundName, pitch, volume)) {
                ability.onMatchedSound()
            }
        }
    }

    private fun handleItemClick(stack: ItemStack, clickType: ClickType) {
        val now = System.currentTimeMillis()

        // Must run before the cooldown-starting loop below, not after -- it
        // reads ability.isOnCooldown() itself (wasOnCooldownAtClick) and
        // needs that to reflect the state at the moment of the click, not
        // after this click has already (optimistically) started a cooldown.
        startCaptureIfNeeded(stack, clickType)

        // Only start cooldowns for abilities matching the click that was
        // actually observed -- abilitiesFor(stack) can return one ability per
        // click type (e.g. Gyrokinetic Wand has both a left- and right-click
        // ability), and without this filter a single right-click would
        // incorrectly start the left-click ability's cooldown too, just
        // because it's on the same item.
        for (ability in abilitiesFor(stack).filter { it.clickType == clickType }) {
            ability.lastItemClick = now

            // Sound signatures aren't filled in yet (every ItemAbility.soundSignature
            // is still null -- see the class doc comment), so onMatchedSound() never
            // fires and this was the only other place a cooldown could start. Start
            // it directly off the click instead, using the ability's declared base
            // duration. Guarded so spam-clicking an already-cooling-down item doesn't
            // keep resetting the timer back to full length; the chat fallback will
            // still correct the exact remaining time if Hypixel sends the "still on
            // cooldown for X seconds" warning for that re-click.
            if (!ability.isOnCooldown()) {
                ability.startCooldown()
            }
        }
    }

    private fun abilitiesFor(stack: ItemStack): List<ItemAbility> {
        if (stack.isEmpty) return emptyList()
        val name = cleanName(stack) ?: return emptyList()

        // Resolved separately per click type: almost every item only has a
        // right-click ability, but a couple (Gyrokinetic Wand) have a
        // genuinely different ability on each click, so both need to be able
        // to match and render at once. Within a given click type, plain
        // substring matching can still produce more than one candidate --
        // e.g. "Wilted Voodoo Doll" contains VOODOO_DOLL's "Voodoo Doll" AND
        // VOODOO_DOLL_WILTED's more specific "Wilted Voodoo Doll" -- so within
        // each click type, only the ability whose matched fragment is longest
        // (most specific) is kept.
        return ClickType.entries.mapNotNull { clickType ->
            ItemAbility.entries
                .filter { it.clickType == clickType }
                .mapNotNull { ability ->
                    ability.itemNameFragments
                        .filter { name.contains(it) }
                        .maxByOrNull { it.length }
                        ?.let { fragment -> ability to fragment.length }
                }
                .maxByOrNull { (_, length) -> length }
                ?.first
        }
    }

    private fun cleanName(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        return stack.hoverName.string.trim().takeIf { it.isNotEmpty() }
    }

    // --- Hotbar overlay: registered via HudElementRegistry in CedClient.kt ---

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!isEnabled || !showOnHotbar.value) return

        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val font = mc.font

        val screenWidth = mc.window.guiScaledWidth
        val screenHeight = mc.window.guiScaledHeight
        val hotbarLeft = screenWidth / 2 - 91
        val hotbarTop = screenHeight - 22

        for (slot in 0..8) {
            val x = hotbarLeft + slot * 20 + 3
            val y = hotbarTop + 3
            drawSlotIfOnCooldown(g, font, player.inventory.getItem(slot), x, y)
        }
    }

    // --- Inventory-screen overlay: called from AbstractContainerScreenMixin ---

    fun renderInInventory(g: GuiGraphicsExtractor, leftPos: Int, topPos: Int) {
        if (!isEnabled || !showInInventory.value) return

        val player = Minecraft.getInstance().player ?: return
        val font = Minecraft.getInstance().font

        // Hotbar row (bottom of the inventory screen).
        for (i in 0..8) {
            drawSlotIfOnCooldown(g, font, player.inventory.getItem(i), leftPos + 8 + i * 18, topPos + 142)
        }

        // Main inventory, 3 rows x 9 columns, slots 9-35.
        for (row in 0..2) {
            for (col in 0..8) {
                val index = 9 + row * 9 + col
                val x = leftPos + 8 + col * 18
                val y = topPos + 84 + row * 18
                drawSlotIfOnCooldown(g, font, player.inventory.getItem(index), x, y)
            }
        }

        // Armor, top to bottom: head, chest, legs, feet.
        for ((i, slot) in armorSlotsTopToBottom.withIndex()) {
            drawSlotIfOnCooldown(g, font, player.getItemBySlot(slot), leftPos + 8, topPos + 8 + i * 18)
        }

        // Offhand.
        drawSlotIfOnCooldown(g, font, player.getItemBySlot(EquipmentSlot.OFFHAND), leftPos + 77, topPos + 62)
    }

    private fun drawSlotIfOnCooldown(g: GuiGraphicsExtractor, font: Font, stack: ItemStack?, x: Int, y: Int) {
        if (stack == null || stack.isEmpty) return

        // abilitiesFor can return up to two abilities for the same item (one
        // per click type -- e.g. Gyrokinetic Wand's left- and right-click
        // abilities), each drawn in its own corner by drawStatusText below.
        for (ability in abilitiesFor(stack)) {
            val seconds = ability.secondsLeft()
            if (seconds != null) {
                drawStatusText(g, font, x, y, ability.clickType, seconds.toString(), cooldownColor.value.rgba)
            } else if (showReadyIndicator.value) {
                drawStatusText(g, font, x, y, ability.clickType, "R", readyColor.value.rgba)
            }
        }
    }

    private fun drawStatusText(
        g: GuiGraphicsExtractor,
        font: Font,
        slotX: Int,
        slotY: Int,
        clickType: ClickType,
        text: String,
        color: Int,
    ) {
        val textWidth = font.width(text)
        val anchorX: Int
        val anchorY: Int
        val drawX: Int
        val drawY: Int
        when (clickType) {
            ClickType.RIGHT_CLICK -> {
                anchorX = slotX + 16
                anchorY = slotY + 16
                drawX = -textWidth
                drawY = -font.lineHeight
            }
            ClickType.LEFT_CLICK -> {
                anchorX = slotX
                anchorY = slotY
                drawX = 0
                drawY = 0
            }
        }

        g.pose().pushMatrix()
        g.pose().translate(anchorX.toFloat(), anchorY.toFloat())
        g.pose().scale(0.8f, 0.8f)
        g.text(font, text, drawX, drawY, color)
        g.pose().popMatrix()
    }
}
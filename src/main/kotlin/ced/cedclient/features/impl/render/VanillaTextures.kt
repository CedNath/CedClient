package ced.cedclient.features.impl.render

import ced.cedclient.accessor.VanillaTextureCache
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.utils.skyblockData
import ced.cedclient.utils.skyblockId
import com.google.common.collect.ImmutableMultimap
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Base64
import java.util.UUID
import kotlin.concurrent.thread

/**
 * SkyBlock items with their pre-update vanilla look: Hypixel's own item models
 * ("hypixel_skyblock:" namespace) are swapped for the item's old vanilla item, heads get their old skin back, and
 * Hypixel's tooltip frames / colour shader are turned off. Old looks come from a bundled NEU repo snapshot
 * (assets/cedclient/vanilla_looks.json, scripts/vanilla_looks.mjs); items newer than it keep Hypixel's texture
 * (their plain base item when the pack is blocked).
 *
 * Hooks: ItemModelResolverMixin / PlayerHeadRendererMixin (items), TooltipStyleMixin + TooltipSpriteMixin (tooltips, drawn by [drawTooltip]),
 * ShaderPreprocessorMixin (colours), ResourcePackPushMixin (Block Hypixel Pack), DownloadedPackSourceMixin ([HypixelPackCache]).
 */
object VanillaTextures : Module(
    "Vanilla Textures",
    Category.Render,
    "SkyBlock items, tooltips and colours look like before Hypixel's texture pack.",
    defaultEnabled = false
) {
    private val retexture = BooleanSetting("Old Item Textures", true, "Items and heads look like before the texture update.")
    private val keep = TextSetting("Keep Custom Textures", "", "SkyBlock item IDs that keep Hypixel's texture, comma-separated, e.g. HYPERION.", maxLength = 16384)
    private val chooser = ActionSetting("Choose Textures", "Search every SkyBlock item and pick which ones keep Hypixel's texture.") {
        (Minecraft.getInstance().gui.screen() as? ced.cedclient.ui.clickgui.ClickGUI)?.openPopup(ced.cedclient.ui.clickgui.TextureListPopup())
    }
    private val tooltips = BooleanSetting("Vanilla Tooltips", true)
    private val colors = BooleanSetting("Vanilla Colors", true, "Turns off Hypixel's colour shader. Applies after Reload Resources (F3+T).")
    val blockPack = BooleanSetting("Block Hypixel Pack", false, "Tells Hypixel the pack loaded without loading it. Also removes Hypixel's custom icons. Applies on next join.")
    val keepPack = BooleanSetting("Keep Hypixel Pack Loaded", true, "Saves Hypixel's SkyBlock pack and loads it at startup, so joining SkyBlock doesn't reload resources. It then stays loaded on other servers too. Applies on restart.")
    private val reload = ActionSetting("Reload Resources") { Minecraft.getInstance().reloadResourcePacks() }

    init {
        addSettings(retexture, chooser, keep, tooltips, colors, blockPack, keepPack, reload)
        keep.inPopup = true // edited through Choose Textures; still saved like any setting
    }

    override fun onEnable() = invalidate()
    override fun onDisable() = invalidate()

    /** Set from ResourcePackPushMixin: the server sent Hypixel's SkyBlock pack (so its tooltip sprites are loaded). */
    @JvmField
    var skyblockPack = false

    @JvmStatic fun tooltipsActive() = isEnabled && tooltips.value
    @JvmStatic fun colorsActive() = isEnabled && colors.value

    private const val TOOLTIP_BG = 0xF0100010.toInt()
    private const val TOOLTIP_BORDER_TOP = 0x505000FF
    private const val TOOLTIP_BORDER_BOTTOM = 0x5028007F

    /** The classic tooltip box (dark background, purple gradient border) around a [width] x [height] tooltip at [x], [y]. */
    @JvmStatic
    fun drawTooltip(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        val left = x - 3
        val top = y - 3
        val right = x + width + 3
        val bottom = y + height + 3
        g.fill(left, top - 1, right, top, TOOLTIP_BG)
        g.fill(left, bottom, right, bottom + 1, TOOLTIP_BG)
        g.fill(left, top, right, bottom, TOOLTIP_BG)
        g.fill(left - 1, top, left, bottom, TOOLTIP_BG)
        g.fill(right, top, right + 1, bottom, TOOLTIP_BG)
        g.fillGradient(left, top + 1, left + 1, bottom - 1, TOOLTIP_BORDER_TOP, TOOLTIP_BORDER_BOTTOM)
        g.fillGradient(right - 1, top + 1, right, bottom - 1, TOOLTIP_BORDER_TOP, TOOLTIP_BORDER_BOTTOM)
        g.fill(left, top, right, top + 1, TOOLTIP_BORDER_TOP)
        g.fill(left, bottom - 1, right, bottom, TOOLTIP_BORDER_BOTTOM)
    }

    // ---- items ----

    private class OldLook(val model: Identifier, val texture: String?) {
        val profile: ResolvableProfile? by lazy { texture?.let(::profileFor) }
    }

    @Volatile private var looks: Map<String, OldLook> = emptyMap()
    private var loadStarted = false

    /** Bumped when settings change, so every ItemStack re-resolves its cached look. */
    private var generation = 1
    private var keepKey = ""
    private var keepIds: Set<String> = emptySet()
    private val ARROW: Identifier = Identifier.withDefaultNamespace("arrow")

    private fun invalidate() { generation++ }

    /** ItemModelResolverMixin: the item model to use instead of Hypixel's. */
    @JvmStatic
    fun itemModel(stack: ItemStack, original: Identifier?): Identifier? {
        if (original == null || original.namespace != "hypixel_skyblock" || !isEnabled || !retexture.value || stack.isEmpty) return original
        if (!loadStarted) startLoading()
        if (keep.value != keepKey) {
            keepKey = keep.value
            keepIds = keep.value.split(',').map { it.trim().uppercase().replace(':', '-') }.filter { it.isNotEmpty() }.toSet()
            invalidate()
        }
        val cache = stack as VanillaTextureCache
        if (cache.`cedclient$vtGeneration`() == generation) return cache.`cedclient$vtModel`() ?: original

        val tag = stack.skyblockData
        val id = stack.skyblockId?.replace(':', '-')
        val look = id?.let { looks[it] }
        val model = when {
            tag?.contains("quiver_arrow") == true -> ARROW
            id != null && id in keepIds -> original
            look != null -> look.model
            blockPack.value -> BuiltInRegistries.ITEM.getKey(stack.item)
            else -> original
        }
        cache.`cedclient$vtModel`(model)
        cache.`cedclient$vtProfile`(if (model == original) null else look?.profile)
        // Don't remember a fallback while the repo is still loading: it may know the item a moment later.
        if (look != null || looks.isNotEmpty()) cache.`cedclient$vtGeneration`(generation)
        return model
    }

    /** PlayerHeadRendererMixin: the old head skin for a head that came without one. */
    @JvmStatic
    fun headProfile(stack: ItemStack, original: ResolvableProfile?): ResolvableProfile? {
        if (original != null || !isEnabled || !retexture.value) return original
        return (stack as VanillaTextureCache).`cedclient$vtProfile`() ?: original
    }

    private fun profileFor(texture: String): ResolvableProfile {
        val uuid = UUID.nameUUIDFromBytes("cedclient\$vt\$$texture".toByteArray())
        val props = PropertyMap(ImmutableMultimap.of("textures", Property("textures", texture)))
        return ResolvableProfile.createResolved(GameProfile(uuid, "CedClient", props))
    }

    // ---- bundled NEU repo snapshot ----

    /** The base64 "textures" property value for a skin hash on textures.minecraft.net. */
    private fun skinValue(hash: String): String = Base64.getEncoder().encodeToString(
        """{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/$hash"}}}""".toByteArray()
    )

    // ---- Choose Textures popup ----

    /** Every SkyBlock item ID Vanilla Textures knows (bundled snapshot + the live NEU repo), sorted by name. */
    @Volatile private var itemNames: Map<String, String>? = null
    private var namesLoading = false

    fun items(): List<Pair<String, String>> {
        if (!loadStarted) startLoading()
        if (itemNames == null && !namesLoading) {
            namesLoading = true
            thread(name = "CedClient VanillaTextures names", isDaemon = true) {
                val names = HashMap<String, String>()
                val dir = net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("notenoughupdates/repo/items")
                runCatching {
                    java.nio.file.Files.list(dir).use { files ->
                        files.forEach { file ->
                            runCatching {
                                val o = JsonParser.parseString(java.nio.file.Files.readString(file)).asJsonObject
                                val id = o["internalname"]?.asString?.replace(':', '-') ?: return@runCatching
                                names[id] = (o["displayname"]?.asString ?: id).replace(Regex("§."), "")
                            }
                        }
                    }
                }
                itemNames = names
            }
        }
        val names = itemNames.orEmpty()
        // Built once per (snapshot, names) pair: the popup asks every frame.
        val key = looks.size to names.size
        sortedItems?.takeIf { sortedKey == key }?.let { return it }
        sortedKey = key
        return (looks.keys + names.keys).map { it to (names[it] ?: it.lowercase().split('_').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }) }
            .sortedBy { it.second.lowercase() }.also { sortedItems = it }
    }
    private var sortedItems: List<Pair<String, String>>? = null
    private var sortedKey = 0 to 0

    fun isKept(id: String): Boolean {
        if (keep.value != keptKey) { keptKey = keep.value; kept = keep.value.split(',').map { it.trim().uppercase() }.toSet() }
        return id.uppercase() in kept
    }
    private var keptKey: String? = null
    private var kept: Set<String> = emptySet()

    /** Flips [id] between Hypixel's texture (kept) and the old look. */
    fun toggleKeep(id: String) {
        val ids = keep.value.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toMutableSet()
        if (!ids.remove(id.uppercase())) ids += id.uppercase()
        keep.value = ids.sorted().joinToString(",")
        ced.cedclient.config.ConfigManager.saveModulesOnly()
    }

    private fun startLoading() {
        loadStarted = true
        thread(name = "CedClient VanillaTextures", isDaemon = true) {
            val out = HashMap<String, OldLook>()
            // Format: "legacy_item:damage[:skin hash]", or "=modern_item:0[:skin hash]".
            VanillaTextures::class.java.getResourceAsStream("/assets/cedclient/vanilla_looks.json")?.reader()?.use { reader ->
                for ((id, value) in JsonParser.parseReader(reader).asJsonObject.entrySet()) {
                    val parts = value.asString.split(':')
                    val model = (if (parts[0].startsWith('=')) Identifier.withDefaultNamespace(parts[0].substring(1)).takeIf(BuiltInRegistries.ITEM::containsKey)
                        else modernId(parts[0], parts[1].toInt())) ?: continue
                    out[id] = OldLook(model, parts.getOrNull(2)?.takeIf { model.path == "player_head" }?.let(::skinValue))
                }
            }
            looks = out
            Minecraft.getInstance().execute { invalidate() }
        }
    }

    private val COLORS = listOf("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")
    private val WOODS = listOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")

    /** 1.8 item id + damage (what the NEU repo stores) to today's item, or null if there is none. */
    private fun modernId(legacy: String, damage: Int): Identifier? {
        val name = legacy.removePrefix("minecraft:")
        val modern = when (name) {
            "skull" -> listOf("skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head", "creeper_head", "dragon_head").getOrNull(damage)
            "dye" -> listOf("ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli", "purple_dye", "cyan_dye", "light_gray_dye",
                "gray_dye", "pink_dye", "lime_dye", "yellow_dye", "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal").getOrNull(damage)
            "wool", "carpet", "stained_glass", "stained_glass_pane", "banner" ->
                COLORS.getOrNull(if (name == "banner") 15 - damage else damage)?.let { "${it}_" + if (name == "wool") "wool" else name }
            "stained_hardened_clay" -> COLORS.getOrNull(damage)?.let { "${it}_terracotta" }
            "fish" -> listOf("cod", "salmon", "tropical_fish", "pufferfish").getOrNull(damage)
            "cooked_fish" -> listOf("cooked_cod", "cooked_salmon").getOrNull(damage)
            "coal" -> if (damage == 1) "charcoal" else "coal"
            "golden_apple" -> if (damage == 1) "enchanted_golden_apple" else "golden_apple"
            "red_flower" -> listOf("poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy").getOrNull(damage)
            "yellow_flower" -> "dandelion"
            "double_plant" -> listOf("sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony").getOrNull(damage)
            "tallgrass" -> if (damage == 2) "fern" else "short_grass"
            "log" -> WOODS.getOrNull(damage)?.let { "${it}_log" }
            "log2" -> WOODS.getOrNull(damage + 4)?.let { "${it}_log" }
            "leaves" -> WOODS.getOrNull(damage % 4)?.let { "${it}_leaves" }
            "leaves2" -> WOODS.getOrNull(damage % 4 + 4)?.let { "${it}_leaves" }
            "planks" -> WOODS.getOrNull(damage)?.let { "${it}_planks" }
            "sapling" -> WOODS.getOrNull(damage)?.let { "${it}_sapling" }
            "wooden_slab" -> WOODS.getOrNull(damage)?.let { "${it}_slab" }
            "stone" -> listOf("stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite").getOrNull(damage)
            "sandstone" -> listOf("sandstone", "chiseled_sandstone", "cut_sandstone").getOrNull(damage)
            "red_sandstone" -> listOf("red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone").getOrNull(damage)
            "prismarine" -> listOf("prismarine", "prismarine_bricks", "dark_prismarine").getOrNull(damage)
            "quartz_block" -> listOf("quartz_block", "chiseled_quartz_block", "quartz_pillar").getOrNull(damage)
            "stonebrick" -> listOf("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks").getOrNull(damage)
            "dirt" -> listOf("dirt", "coarse_dirt", "podzol").getOrNull(damage)
            "sponge" -> if (damage == 1) "wet_sponge" else "sponge"
            "sand" -> if (damage == 1) "red_sand" else "sand"
            "cobblestone_wall" -> if (damage == 1) "mossy_cobblestone_wall" else "cobblestone_wall"
            "anvil" -> listOf("anvil", "chipped_anvil", "damaged_anvil").getOrNull(damage)
            "spawn_egg", "monster_egg" -> null
            else -> RENAMES[name] ?: name
        } ?: return null
        val id = Identifier.withDefaultNamespace(modern)
        return if (BuiltInRegistries.ITEM.containsKey(id)) id else null
    }

    private val RENAMES = mapOf(
        "waterlily" to "lily_pad", "melon_block" to "melon", "melon" to "melon_slice", "speckled_melon" to "glistering_melon_slice",
        "reeds" to "sugar_cane", "noteblock" to "note_block", "trapdoor" to "oak_trapdoor", "fireworks" to "firework_rocket",
        "firework_charge" to "firework_star", "slime" to "slime_block", "fence" to "oak_fence", "fence_gate" to "oak_fence_gate",
        "wooden_door" to "oak_door", "boat" to "oak_boat", "mob_spawner" to "spawner", "web" to "cobweb", "lit_pumpkin" to "jack_o_lantern",
        "golden_rail" to "powered_rail", "brick_block" to "bricks", "netherbrick" to "nether_brick", "nether_brick" to "nether_bricks",
        "quartz_ore" to "nether_quartz_ore", "end_bricks" to "end_stone_bricks", "hardened_clay" to "terracotta", "grass" to "grass_block",
        "deadbush" to "dead_bush", "magma" to "magma_block", "chorus_fruit_popped" to "popped_chorus_fruit", "bed" to "red_bed",
        "wooden_button" to "oak_button", "wooden_pressure_plate" to "oak_pressure_plate", "stone_slab" to "smooth_stone_slab",
        "silver_shulker_box" to "light_gray_shulker_box", "snow_layer" to "snow", "red_mushroom_block" to "red_mushroom_block",
        "record_13" to "music_disc_13", "record_cat" to "music_disc_cat", "record_blocks" to "music_disc_blocks",
        "record_chirp" to "music_disc_chirp", "record_far" to "music_disc_far", "record_mall" to "music_disc_mall",
        "record_mellohi" to "music_disc_mellohi", "record_stal" to "music_disc_stal", "record_strad" to "music_disc_strad",
        "record_ward" to "music_disc_ward", "record_11" to "music_disc_11", "record_wait" to "music_disc_wait",
    )
}

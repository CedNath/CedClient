package ced.cedclient.features.impl.misc

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.ui.search.SearchOverlayScreen
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack
import ced.cedclient.features.impl.render.VanillaTextures
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.SignBlockEntity
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Colour codes, compiled once. */
private val CODES = Regex("§.")

/**
 * Skyblocker-style search for the Bazaar / Auction House: replaces Hypixel's "Enter query" sign with a
 * search box that suggests item names (with icons) and remembers recent searches.
 *
 * No repo of our own: item names come from the NEU repo SkyHanni downloads to
 * config/notenoughupdates/repo (NEU and Firmament use the same folder). items/ gives the names,
 * itemsOverlay/<dataVersion>/ gives modern item stacks for the icons. Without that folder the
 * overlay still works, just with history only.
 *
 * Suggestions are filtered per menu: Bazaar to Hypixel's bazaar product list, Auction House to the
 * items with a lowest BIN (hysky.de, the list Skyblocker uses). If a list can't be fetched, that menu
 * suggests every item instead.
 */
object SearchOverlay : Module(
    "SearchOverlay",
    Category.Skyblock,
    "Bazaar / Auction House search box with item suggestions and history (needs SkyHanni's item repo)"
) {
    private val mc = Minecraft.getInstance()

    private val historySize = NumberSetting("History Size", 5.0, 0.0, 15.0, 1.0)
    // Newline-separated, newest first, one per menu since they hold different items. Hidden: only written by the overlay.
    private val bazaarHistory = TextSetting("Bazaar History", "", maxLength = 1000).also { it.hide() }
    private val auctionHistory = TextSetting("Auction History", "", maxLength = 1000).also { it.hide() }

    private const val BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar"
    private const val LOWEST_BIN_URL = "https://hysky.de/api/auctions/lowestbins"
    private val PET_TIERS = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

    enum class Location { BAZAAR, AUCTION }

    class Entry(val name: String, val display: String, val id: String, val legacyItem: String, val vanilla: Boolean = false, val skull: String? = null)

    @Volatile
    private var bazaarItems: List<Entry> = emptyList()
    @Volatile
    private var auctionItems: List<Entry> = emptyList()
    @Volatile
    private var overlayFiles: Map<String, Path> = emptyMap()
    private val icons = HashMap<String, ItemStack>()

    /** Every non-vanilla item of the NEU repo, by name (the invbutton editor offers them as icons). */
    @Volatile var allItems: List<Entry> = emptyList()
        private set
    @Volatile private var allById: Map<String, Entry> = emptyMap()

    /** The icon stack of SkyBlock item [id], null while the repo is not loaded or the item is unknown. */
    fun stackFor(id: String): ItemStack? = allById[id]?.let { icon(it) }

    private val repoDir: Path get() = FabricLoader.getInstance().configDir.resolve("notenoughupdates/repo")

    // The repo is rewritten at startup (SkyHanni / Firmament download it): a load that raced that holds a partial item list.
    private val repoStamp: Long get() = runCatching {
        Files.getLastModifiedTime(repoDir.resolveSibling("currentCommit.json")).toMillis() + Files.getLastModifiedTime(repoDir.resolve("items")).toMillis()
    }.getOrDefault(0L)
    @Volatile private var loadedStamp = -1L
    @Volatile private var loading = false

    private fun startLoad() {
        if (loading) return
        loading = true
        Thread({ try { loadedStamp = repoStamp; load() } finally { loading = false } }, "CedClient-SearchOverlay").apply { isDaemon = true }.start()
    }

    init {
        addSettings(historySize, bazaarHistory, auctionHistory)
        startLoad()
    }

    /** Called from LocalPlayerMixin when a sign editor opens. True = we replaced it with the overlay. */
    @JvmStatic
    fun tryOpen(sign: SignBlockEntity, front: Boolean): Boolean {
        if (!isEnabled) return false
        if (repoStamp != loadedStamp) startLoad()
        if (!sign.getText(front).getMessage(3, false).string.equals("enter query", ignoreCase = true)) return false
        val title = mc.gui.screen()?.title?.string?.lowercase() ?: return false
        val location = when {
            "bazaar" in title -> Location.BAZAAR
            "auction" in title -> Location.AUCTION
            else -> return false
        }
        mc.gui.setScreen(SearchOverlayScreen(sign, front, location))
        return true
    }

    fun hasItems(location: Location): Boolean = items(location).isNotEmpty()

    private fun items(location: Location) = if (location == Location.BAZAAR) bazaarItems else auctionItems

    private fun historySetting(location: Location) = if (location == Location.BAZAAR) bazaarHistory else auctionHistory

    fun history(location: Location): List<String> = historySetting(location).value.split('\n').filter { it.isNotBlank() }

    fun remember(query: String, location: Location) {
        historySetting(location).value = (listOf(query) + history(location).filterNot { it.equals(query, ignoreCase = true) })
            .take(historySize.value.toInt()).joinToString("\n")
        ConfigManager.saveModulesOnly()
    }

    /** Prefix matches first, then word-start matches, then anywhere; shorter names first within each group. */
    fun suggest(query: String, location: Location, limit: Int): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return items(location).asSequence()
            .mapNotNull { e ->
                val n = e.name.lowercase()
                val rank = when {
                    n.startsWith(q) -> 0
                    " $q" in n -> 1
                    q in n -> 2
                    else -> return@mapNotNull null
                }
                rank to e
            }
            .sortedWith(compareBy({ it.first }, { it.second.name.length }))
            .take(limit).map { it.second }.toList()
    }

    fun icon(e: Entry): ItemStack = icons.getOrPut(e.id) { overlayStack(e.id) ?: skullStack(e) ?: legacyStack(e.legacyItem) }

    /** Custom heads have no modern item file (their old id is "skull", which would end up as paper): build the head from the repo's skin texture. */
    private fun skullStack(e: Entry): ItemStack? = e.skull?.let { tex ->
        ItemStack(Items.PLAYER_HEAD).also { it.set(DataComponents.PROFILE, VanillaTextures.profileFor(tex)) }
    }

    private fun overlayStack(id: String): ItemStack? {
        val path = overlayFiles[id] ?: return null
        val registries = mc.level?.registryAccess() ?: return null
        return runCatching {
            val tag = TagParser.parseCompoundFully(Files.readString(path))
            tag.remove("source")
            ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag).result().orElse(null)
                // Hypixel's own models only exist with their resource pack; fall back to the vanilla look.
                ?.also { stack ->
                    // remove() would leave the stack with no model at all, so point it at the vanilla one.
                    if (stack.get(DataComponents.ITEM_MODEL)?.namespace != "minecraft") {
                        stack.set(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(stack.item))
                    }
                }
        }.getOrNull()
    }

    private fun legacyStack(itemId: String): ItemStack {
        val item = Identifier.tryParse(itemId)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: Items.PAPER
        return ItemStack(item)
    }

    // ---------------------------------------------------------------- loading (background thread)

    private fun load() {
        val itemsDir = repoDir.resolve("items")
        if (!Files.isDirectory(itemsDir)) return
        runCatching { overlayFiles = indexOverlays() }

        val byId = HashMap<String, Entry>()
        Files.list(itemsDir).use { files ->
            files.forEach { f -> runCatching { neuEntry(JsonParser.parseString(Files.readString(f)).asJsonObject) }.getOrNull()?.let { byId[it.id] = it } }
        }

        allById = byId
        allItems = byId.values.filter { !it.vanilla }.sortedBy { it.name }

        val bazaarIds = fetchIds(BAZAAR_URL) { it.getAsJsonObject("products").keySet() }
        val auctionIds = fetchIds(LOWEST_BIN_URL) { it.keySet() }

        // Shards from Hunting aren't in the NEU repo yet: SHARD_SALMON -> "Salmon Shard", prismarine shard icon.
        bazaarItems = dedupe(bazaarIds?.map { api ->
            neuIdCandidates(api).firstNotNullOfOrNull(byId::get)
                ?: if (api.startsWith("SHARD_")) titleCase(api.removePrefix("SHARD_")).let { Entry("$it Shard", "§f$it Shard", api, "minecraft:prismarine_shard") } else null
        } ?: byId.values)
        // Plain vanilla blocks and items (NEU marks them "vanilla") just clutter AH suggestions.
        auctionItems = dedupe((auctionIds?.map { api -> neuIdCandidates(api).firstNotNullOfOrNull(byId::get) } ?: byId.values).filter { it?.vanilla == false })
    }

    private val SKULL_VALUE = Regex("""Value:"([^"]+)"""")

    private fun neuEntry(o: JsonObject): Entry? {
        val id = o["internalname"].asString
        val legacy = o["itemid"]?.asString ?: ""
        val display = if (legacy == "minecraft:enchanted_book" && ';' in id) {
            // Books are all called "Enchanted Book"; ULTIMATE_WISE;5 -> "Ultimate Wise".
            "§9" + titleCase(id.substringBefore(';'))
        } else {
            o["displayname"].asString.replace("[Lvl {LVL}] ", "").trim()
        }
        val name = display.replace(CODES, "").trim()
        val vanilla = o["vanilla"]?.asBoolean == true
        // Custom heads: the skin texture sits in the item's nbttag (SkullOwner.Properties.textures[0].Value).
        val skull = o["nbttag"]?.takeIf { it.isJsonPrimitive }?.asString?.let { SKULL_VALUE.find(it)?.groupValues?.get(1) }
        return if (name.isEmpty()) null else Entry(name, display, id, legacy, vanilla, skull)
    }

    private fun dedupe(entries: Iterable<Entry?>): List<Entry> {
        val seen = HashSet<String>()
        return entries.filterNotNull().filter { seen.add(it.name.lowercase()) }
    }

    /** Hypixel / hysky item IDs -> possible NEU repo IDs, most likely first. */
    fun neuIdCandidates(api: String): List<String> {
        val out = mutableListOf(api, api.replace(':', '-'))
        Regex("""^ENCHANTMENT_(.+)_(\d+)$""").matchEntire(api)?.let { out += "${it.groupValues[1]};${it.groupValues[2]}" }
        Regex("""^LVL_\d+_([A-Z]+)_(.+)$""").matchEntire(api)?.let {
            val tier = PET_TIERS.indexOf(it.groupValues[1])
            if (tier >= 0) out += "${it.groupValues[2]};$tier"
        }
        // Runes and other leveled items: ANTLERS_RUNE_3 -> ANTLERS_RUNE;3
        Regex("""^(.+)_(\d+)$""").matchEntire(api)?.let { out += "${it.groupValues[1]};${it.groupValues[2]}" }
        return out
    }

    private fun titleCase(id: String) = id.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

    private fun fetchIds(url: String, keys: (JsonObject) -> Set<String>): Set<String>? = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build()
        val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
        keys(JsonParser.parseString(body).asJsonObject).takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Latest overlay file per item whose data version is not newer than the running game. */
    private fun indexOverlays(): Map<String, Path> {
        val current = SharedConstants.getCurrentVersion().dataVersion().version()
        val map = HashMap<String, Path>()
        val versions = Files.list(repoDir.resolve("itemsOverlay")).use { dirs ->
            dirs.toList().mapNotNull { d -> d.fileName.toString().toIntOrNull()?.let { it to d } }
        }
        for ((_, dir) in versions.filter { it.first <= current }.sortedBy { it.first }) {
            Files.list(dir).use { files -> files.forEach { map[it.fileName.toString().removeSuffix(".snbt")] = it } }
        }
        return map
    }
}

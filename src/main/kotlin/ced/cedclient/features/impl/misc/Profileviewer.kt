package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.network.ApiResult
import ced.cedclient.network.HypixelApiClient
import ced.cedclient.utils.Debug
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Phase 1 of the Profile Viewer: fetch pipeline only, no custom UI yet.
 * `/cedclient pv [username]` resolves a username -> UUID (Mojang) -> raw
 * Hypixel /player + /skyblock/profiles payloads, caches the raw JSON here,
 * and reports progress/results as chat feedback so the pipeline can be
 * verified end-to-end before any screen is built on top of it.
 *
 * The Hypixel API key is stored as a module setting (persisted via the
 * existing Saving/ConfigManager path) rather than a separate file -- same
 * as every other per-feature bit of config in this mod.
 */
object ProfileViewer : Module(
    name = "Profile Viewer",
    category = Category.CedClient,
    description = "Fetches Hypixel SkyBlock profile data for /cedclient pv",
    defaultEnabled = true
) {

    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    val apiKeySetting = registerSetting(
        TextSetting(
            name = "Hypixel API Key",
            default = "",
            description = "Get a Personal API Key at developer.hypixel.net (Create App)",
            maxLength = 48
        )
    )

    // -------------------------
    // Result cache -- read by the (future) PV screen. Public so a screen in
    // a later phase can just read these directly rather than re-fetching.
    // -------------------------

    @Volatile
    var loading: Boolean = false
        private set

    @Volatile
    var lastUsername: String? = null
        private set

    @Volatile
    var lastUuid: String? = null
        private set

    @Volatile
    var lastPlayerJson: JsonObject? = null
        private set

    @Volatile
    var lastSkyblockJson: JsonObject? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Kicks off the full fetch chain for `username` (or, if null, the
     * currently logged-in player) and reports progress/results through
     * `source`. Safe to call from a command's `executes` block -- all the
     * actual network work happens off-thread, hopping back to the main
     * thread only to touch Minecraft state or send feedback.
     */
    fun fetch(username: String?, source: FabricClientCommandSource) {
        val apiKey = apiKeySetting.value
        if (apiKey.isBlank()) {
            source.sendFeedback(
                Component.literal("[CC] No Hypixel API key set. /api new is dead -- go to developer.hypixel.net, sign in, Create App, choose 'Personal API Key' (needs approval), then paste it in the ClickGUI (CedClient > Profile Viewer).")
            )
            return
        }

        val resolvedName = username ?: Minecraft.getInstance().player?.gameProfile?.name
        if (resolvedName.isNullOrBlank()) {
            source.sendFeedback(Component.literal("[CC] Couldn't determine a username (not logged in, and none was given)."))
            return
        }

        loading = true
        lastError = null
        source.sendFeedback(Component.literal("[CC] Looking up $resolvedName..."))

        HypixelApiClient.resolveUuid(resolvedName).thenAccept { uuidResult ->
            when (uuidResult) {
                is ApiResult.Failure -> fail(source, uuidResult.message)
                is ApiResult.Success -> {
                    val uuid = uuidResult.data
                    lastUsername = resolvedName
                    lastUuid = uuid

                    val playerFuture = HypixelApiClient.fetchPlayer(uuid, apiKey)
                    val profilesFuture = HypixelApiClient.fetchSkyblockProfiles(uuid, apiKey)

                    playerFuture.thenCombine(profilesFuture) { playerResult, profilesResult ->
                        Minecraft.getInstance().execute {
                            loading = false

                            val playerJson = (playerResult as? ApiResult.Success)?.data
                            val skyblockJson = (profilesResult as? ApiResult.Success)?.data
                            lastPlayerJson = playerJson
                            lastSkyblockJson = skyblockJson

                            val playerFailMsg = (playerResult as? ApiResult.Failure)?.message
                            val profilesFailMsg = (profilesResult as? ApiResult.Failure)?.message

                            if (playerFailMsg != null) {
                                source.sendFeedback(Component.literal("[CC] /player failed: $playerFailMsg"))
                            }
                            if (profilesFailMsg != null) {
                                source.sendFeedback(Component.literal("[CC] /skyblock/profiles failed: $profilesFailMsg"))
                            }
                            if (playerFailMsg == null && profilesFailMsg == null) {
                                val profileCount = skyblockJson
                                    ?.getAsJsonArray("profiles")
                                    ?.size() ?: 0
                                source.sendFeedback(
                                    Component.literal("[CC] Got data for $resolvedName: $profileCount SkyBlock profile(s). Raw JSON cached in ProfileViewer.lastPlayerJson/lastSkyblockJson.")
                                )
                                if (Debug.enabled) {
                                    println("[ProfileViewer] player: ${prettyGson.toJson(playerJson)}")
                                    println("[ProfileViewer] skyblock/profiles: ${prettyGson.toJson(skyblockJson)}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fail(source: FabricClientCommandSource, message: String) {
        Minecraft.getInstance().execute {
            loading = false
            lastError = message
            source.sendFeedback(Component.literal("[CC] $message"))
        }
    }
}
package ced.cedclient.features.impl.funqol

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Bazaar prices of the critter shards (SHARD_<NAME>), refreshed every 10 minutes while asked for.
 * Instant sell = what selling to buy orders pays now; sell offer = the cheapest sell offer (what you'd list at).
 */
internal object CritterPrices {
    private class Price(val instantSell: Double, val sellOffer: Double)

    @Volatile private var prices: Map<String, Price> = emptyMap()
    @Volatile private var fetching = false
    private var fetchedAt = 0L
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    val loaded get() = prices.isNotEmpty()

    private fun id(name: String) = "SHARD_" + name.uppercase().replace(' ', '_')

    /** Coins for [shards] (species name -> amount), after the 1.25% bazaar tax when [tax]. */
    fun value(shards: Map<String, Int>, sellOffer: Boolean, tax: Boolean): Long {
        refresh()
        val total = shards.entries.sumOf { (name, n) ->
            val p = prices[id(name)] ?: return@sumOf 0.0
            n * if (sellOffer) p.sellOffer else p.instantSell
        }
        return (if (tax) total * 0.9875 else total).toLong()
    }

    private fun refresh() {
        if (fetching || System.currentTimeMillis() - fetchedAt < 600_000) return
        fetching = true
        fetchedAt = System.currentTimeMillis()
        val request = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/skyblock/bazaar")).timeout(Duration.ofSeconds(15)).build()
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
            fetching = false
            if (error != null || response.statusCode() != 200) {
                fetchedAt -= 540_000 // try again in a minute
                return@whenComplete
            }
            runCatching {
                val products = JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("products")
                prices = products.entrySet().filter { it.key.startsWith("SHARD_") }.associate { (key, value) ->
                    val q = value.asJsonObject.getAsJsonObject("quick_status")
                    key to Price(q["sellPrice"].asDouble, q["buyPrice"].asDouble)
                }
            }
        }
    }

    fun format(coins: Long): String = when {
        coins >= 1_000_000_000 -> "%.2fB".format(coins / 1e9)
        coins >= 1_000_000 -> "%.2fM".format(coins / 1e6)
        coins >= 10_000 -> "%.0fk".format(coins / 1e3)
        else -> "%,d".format(coins)
    }
}

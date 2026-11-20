package app.pricewatch.data.store

import app.pricewatch.data.model.Asset
import app.pricewatch.data.model.AssetGroup
import app.pricewatch.data.model.AssetType
import app.pricewatch.testing.Assets
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class StoresTest {

  @get:Rule val folder = TemporaryFolder()

  private fun TestScope.watchlist(file: File = File(folder.root, "watchlist.json")) =
    WatchlistStore(
      JsonStore(file, ListSerializer(Asset.serializer()), emptyList(), backgroundScope, StandardTestDispatcher(testScheduler))
    )

  private fun TestScope.settings(file: File = File(folder.root, "settings.json")) =
    SettingsStore(JsonStore(file, Settings.serializer(), Settings(), backgroundScope, StandardTestDispatcher(testScheduler)))

  @Test
  fun `the watchlist survives a restart, in order, in sections and with custom names`() = runTest {
    val first = watchlist()
    first.add(Assets.apple)
    first.add(Assets.bitcoin)
    first.add(Assets.stock("IBM"))
    first.add(Assets.euro)
    first.move(Assets.stock("IBM").id, -1)
    first.setGroup(Assets.euro.id, AssetGroup.INDICES)
    first.rename(Assets.bitcoin.id, "  Savings  ")
    runCurrent() // lets the background write finish

    val reopened = watchlist().assets.value

    assertEquals(listOf("BTC-USD", "IBM", "AAPL", "EUR/USD"), reopened.map { it.symbol })
    assertEquals("Savings", reopened.first().label)
    assertEquals("Savings", reopened.first().displayName)
    assertEquals(Assets.euro.copy(group = AssetGroup.INDICES), reopened.last())
  }

  @Test
  fun `new entries go to the end of their own section`() = runTest {
    val store = watchlist()

    store.add(Assets.apple)
    store.add(Assets.euro)
    store.add(Assets.bitcoin)
    store.add(Assets.stock("IBM"))
    store.add(Assets.ether.copy(group = AssetGroup.OTHER))

    assertEquals(listOf("BTC-USD", "AAPL", "IBM", "EUR/USD", "ETH-USD"), store.assets.value.map { it.symbol })
  }

  @Test
  fun `changing an entry's section puts it at the end of the new one`() = runTest {
    val store = watchlist()
    listOf(Assets.apple, Assets.stock("IBM"), Assets.stock("SPY").copy(group = AssetGroup.INDICES), Assets.bitcoin)
      .forEach { store.add(it) }

    store.setGroup(Assets.apple.id, AssetGroup.INDICES)
    assertEquals(listOf("BTC-USD", "IBM", "SPY", "AAPL"), store.assets.value.map { it.symbol })
    assertEquals(AssetGroup.INDICES, store.assets.value.last().group)

    // Choosing the section it is already in changes nothing.
    store.setGroup(Assets.bitcoin.id, AssetGroup.CRYPTO)
    store.setGroup("not on the list", AssetGroup.STOCKS)
    assertEquals(listOf("BTC-USD", "IBM", "SPY", "AAPL"), store.assets.value.map { it.symbol })
  }

  @Test
  fun `a watchlist saved before sections existed gets suggested sections and keeps its order within them`() =
    runTest {
      val file =
        File(folder.root, "watchlist.json").apply {
          writeText(
            """
            [
              {"provider":"TWELVE_DATA","symbol":"SPY","name":"SPDR S&P 500 ETF Trust","type":"ETF","exchange":"NYSE"},
              {"provider":"COINBASE","symbol":"ETH-USD","name":"Ethereum","type":"CRYPTO","exchange":"Coinbase"},
              {"provider":"TWELVE_DATA","symbol":"AAPL","name":"Apple Inc.","type":"STOCK","exchange":"NASDAQ"},
              {"provider":"COINBASE","symbol":"BTC-USD","name":"Bitcoin","type":"CRYPTO","exchange":"Coinbase"}
            ]
            """
          )
        }

      val assets = watchlist(file).assets.value

      assertEquals(listOf("ETH-USD", "BTC-USD", "AAPL", "SPY"), assets.map { it.symbol })
      assertEquals(
        listOf(AssetGroup.CRYPTO, AssetGroup.CRYPTO, AssetGroup.STOCKS, AssetGroup.INDICES),
        assets.map { it.group },
      )
      runCurrent() // lets the background write finish
      assertTrue("the sorted list is saved with its sections", file.readText().contains("\"group\": \"INDICES\""))
    }

  @Test
  fun `the section is saved even when it is the suggested one`() = runTest {
    val file = File(folder.root, "watchlist.json")
    watchlist(file).add(Assets.apple)
    runCurrent() // lets the background write finish

    assertTrue(file.readText().contains("\"group\": \"STOCKS\""))
  }

  @Test
  fun `adding the same asset twice keeps one entry`() = runTest {
    val store = watchlist()

    store.add(Assets.apple)
    store.add(Assets.apple.copy(name = "Apple again"))

    assertEquals(listOf(Assets.apple), store.assets.value)
  }

  @Test
  fun `a removed asset can be put back where it was`() = runTest {
    val store = watchlist()
    val (a, b, c) = listOf(Assets.stock("A"), Assets.stock("B"), Assets.stock("C"))
    listOf(Assets.bitcoin, a, b, c, Assets.euro).forEach { store.add(it) }

    store.remove(b.id)
    assertEquals(listOf(Assets.bitcoin, a, c, Assets.euro), store.assets.value)

    store.add(b, index = 2)
    assertEquals(listOf(Assets.bitcoin, a, b, c, Assets.euro), store.assets.value)
  }

  @Test
  fun `moving stays within the section and stops at its ends`() = runTest {
    val store = watchlist()
    val (a, b, c) = listOf(Assets.stock("A"), Assets.stock("B"), Assets.stock("C"))
    listOf(Assets.bitcoin, a, b, c, Assets.euro).forEach { store.add(it) }
    fun symbols() = store.assets.value.map { it.symbol }

    // The first and last stock do not leave Stocks for the neighbouring sections.
    store.move(a.id, -1)
    store.move(c.id, 1)
    assertEquals(listOf("BTC-USD", "A", "B", "C", "EUR/USD"), symbols())

    store.move(a.id, 1)
    assertEquals(listOf("BTC-USD", "B", "A", "C", "EUR/USD"), symbols())

    store.move(c.id, -2)
    assertEquals(listOf("BTC-USD", "C", "B", "A", "EUR/USD"), symbols())

    // Alone in its section, an entry cannot move at all.
    store.move(Assets.bitcoin.id, 1)
    store.move("not on the list", 1)
    assertEquals(listOf("BTC-USD", "C", "B", "A", "EUR/USD"), symbols())
  }

  @Test
  fun `each type suggests the section it belongs in`() {
    assertEquals(AssetGroup.STOCKS, AssetType.STOCK.suggestedGroup)
    assertEquals(AssetGroup.INDICES, AssetType.ETF.suggestedGroup)
    assertEquals(AssetGroup.INDICES, AssetType.INDEX.suggestedGroup)
    assertEquals(AssetGroup.INDICES, AssetType.FUND.suggestedGroup)
    assertEquals(AssetGroup.CRYPTO, AssetType.CRYPTO.suggestedGroup)
    assertEquals(AssetGroup.OTHER, AssetType.FOREX.suggestedGroup)
    assertEquals(AssetGroup.OTHER, AssetType.COMMODITY.suggestedGroup)
  }

  @Test
  fun `an empty name restores the provider's name`() = runTest {
    val store = watchlist()
    store.add(Assets.apple)

    store.rename(Assets.apple.id, "My fruit")
    assertEquals("My fruit", store.assets.value.single().displayName)

    store.rename(Assets.apple.id, "   ")
    assertNull(store.assets.value.single().label)
    assertEquals("Apple Inc.", store.assets.value.single().displayName)
  }

  @Test
  fun `settings survive a restart`() = runTest {
    val first = settings()
    assertEquals(Settings.DEFAULT_AUTO_REFRESH_SECONDS, first.settings.value.autoRefreshSeconds)
    first.setTwelveDataApiKey("  abc123 ")
    first.setAutoRefreshSeconds(900)
    runCurrent() // lets the background write finish

    assertEquals(Settings(twelveDataApiKey = "abc123", autoRefreshSeconds = 900), settings().settings.value)
  }

  @Test
  fun `a damaged file falls back to defaults and is repaired by the next change`() = runTest {
    val file = File(folder.root, "watchlist.json").apply { writeText("{ this is not a watchlist") }

    val store = watchlist(file)
    assertTrue(store.assets.value.isEmpty())

    store.add(Assets.apple)
    runCurrent() // lets the background write finish
    assertEquals(listOf(Assets.apple), watchlist(file).assets.value)
    assertFalse("no temporary file is left behind", File(folder.root, "watchlist.json.tmp").exists())
  }

  @Test
  fun `a file written by a newer version with extra fields still loads`() = runTest {
    val file =
      File(folder.root, "settings.json").apply {
        writeText("""{"twelveDataApiKey":"k","autoRefreshSeconds":60,"somethingNew":true}""")
      }

    assertEquals(Settings("k", 60), settings(file).settings.value)
  }
}

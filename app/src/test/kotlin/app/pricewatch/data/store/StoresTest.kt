package app.pricewatch.data.store

import app.pricewatch.data.model.Asset
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
  fun `the watchlist survives a restart, in order and with custom names`() = runTest {
    val first = watchlist()
    first.add(Assets.apple)
    first.add(Assets.bitcoin)
    first.add(Assets.euro)
    first.move(Assets.euro.id, -2)
    first.rename(Assets.bitcoin.id, "  Savings  ")
    runCurrent() // lets the background write finish

    val reopened = watchlist().assets.value

    assertEquals(listOf("EUR/USD", "AAPL", "BTC-USD"), reopened.map { it.symbol })
    assertEquals("Savings", reopened.last().label)
    assertEquals("Savings", reopened.last().displayName)
    assertEquals(Assets.euro, reopened.first())
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
    listOf(Assets.apple, Assets.bitcoin, Assets.euro).forEach { store.add(it) }

    store.remove(Assets.bitcoin.id)
    assertEquals(listOf(Assets.apple, Assets.euro), store.assets.value)

    store.add(Assets.bitcoin, index = 1)
    assertEquals(listOf(Assets.apple, Assets.bitcoin, Assets.euro), store.assets.value)
  }

  @Test
  fun `moving stops at the ends of the list`() = runTest {
    val store = watchlist()
    listOf(Assets.apple, Assets.bitcoin, Assets.euro).forEach { store.add(it) }

    store.move(Assets.apple.id, -1)
    store.move(Assets.euro.id, 1)
    assertEquals(listOf(Assets.apple, Assets.bitcoin, Assets.euro), store.assets.value)

    store.move(Assets.apple.id, 1)
    assertEquals(listOf(Assets.bitcoin, Assets.apple, Assets.euro), store.assets.value)

    store.move("not on the list", 1)
    assertEquals(listOf(Assets.bitcoin, Assets.apple, Assets.euro), store.assets.value)
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

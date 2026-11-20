package app.pricewatch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pricewatch.data.model.AssetGroup
import app.pricewatch.data.source.HttpResponse
import app.pricewatch.testing.FakeHttp
import app.pricewatch.testing.Request
import app.pricewatch.testing.fixture
import app.pricewatch.testing.ok
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The app with the network replaced by responses recorded from the real services. */
class TestApplication : PriceWatchApplication() {
  val http = FakeHttp(::recordedBackend)

  override val container: AppContainer by lazy {
    AppContainer(storageDir = Files.createTempDirectory("pricewatch-test").toFile(), http = http)
  }
}

private fun recordedBackend(request: Request): HttpResponse =
  when {
    request.url.startsWith("https://api.exchange.coinbase.com/") ->
      when {
        request.path == "products" -> ok(fixture("cb_products.json"))
        request.path == "currencies" -> ok(fixture("cb_currencies.json"))
        request.path.endsWith("/stats") -> ok(fixture("cb_stats.json"))
        request.path.endsWith("/candles") -> ok(fixture("cb_candles.json"))
        else -> HttpResponse(404, fixture("cb_bad.json"))
      }
    request.query["apikey"] != "valid-key" -> HttpResponse(401, fixture("td_badkey.json"))
    request.path == "symbol_search" -> ok(fixture("td_search_aapl.json"))
    request.path == "quote" -> ok(fixture("td_quote_aapl.json"))
    request.path == "time_series" ->
      ok(fixture(if (request.query["interval"] == "5min") "td_ts_aapl_5min.json" else "td_ts_aapl.json"))
    request.path == "api_usage" ->
      ok("""{"current_usage":1,"plan_limit":8,"daily_usage":12,"plan_daily_limit":800,"plan_category":"basic"}""")
    else -> HttpResponse(404, """{"code":404,"message":"not found","status":"error"}""")
  }

/**
 * Drives the real screens, navigation, view models and storage end to end on the JVM. Only the
 * HTTP layer is replaced.
 */
@RunWith(AndroidJUnit4::class)
// Keep Robolectric's default screen size. With a larger one set through `qualifiers`, a text field
// inside a dialog (the rename dialog) never lets Compose report idle and the test times out.
@Config(application = TestApplication::class)
class AppFlowTest {

  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val app: TestApplication
    get() = compose.activity.application as TestApplication

  /** View models wait in real time (typing pause, rate limit); let that time pass. */
  private fun waitFor(description: String, condition: () -> Boolean) {
    repeat(100) {
      compose.waitForIdle()
      if (condition()) return
      ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    }
    fail("Timed out waiting for $description")
  }

  private fun waitForText(text: String) =
    waitFor("text \"$text\"") { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

  private fun waitForDescription(text: String) =
    waitFor("\"$text\"") {
      compose.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

  private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

  private fun pressBack() {
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()
  }

  /** Picks a watchlist section in the menu that adding a search result opens. */
  private fun chooseSection(label: String) {
    // The search screen has a "Crypto" heading too; only the menu item can be clicked.
    waitFor("the section menu") { compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
    compose.onNode(hasText(label) and hasClickAction()).performClick()
  }

  private fun addEtherFromSearch() {
    compose.onNodeWithText("Add an asset").performClick()
    // Before typing, Coinbase's suggestions are listed.
    waitForText("BTC-USD")

    compose.onNode(hasSetTextAction()).performTextInput("eth")
    waitForText("ETH-USD")
    compose.onNodeWithContentDescription("Add ETH-USD to watchlist").performClick()
    // Its type suggests the Crypto section.
    compose.onNodeWithText("Add ETH-USD to").assertIsDisplayed()
    compose.onNodeWithText("Suggested").assertIsDisplayed()
    chooseSection("Crypto")
    waitForDescription("Remove ETH-USD from watchlist")
    pressBack()
  }

  @Test
  fun `first run - add a crypto pair and see its price`() {
    compose.onNodeWithText("Nothing on your watchlist yet").assertIsDisplayed()

    addEtherFromSearch()

    waitForText("68,161.53")
    compose.onNodeWithText("Crypto").assertIsDisplayed()
    compose.onNodeWithText("ETH-USD").assertIsDisplayed()
    compose.onNodeWithText("Ether").assertIsDisplayed()
    compose.onNodeWithText("▼ 2,012.66 (2.87%)").assertIsDisplayed()
    assertEquals(listOf("ETH-USD"), app.container.watchlistStore.assets.value.map { it.symbol })
    assertEquals(AssetGroup.CRYPTO, app.container.watchlistStore.assets.value.single().group)
  }

  @Test
  fun `without an api key a search for a stock points to settings`() {
    compose.onNodeWithText("Add an asset").performClick()
    waitForText("BTC-USD")

    compose.onNode(hasSetTextAction()).performTextInput("aapl")

    waitForText("No crypto pair matches.")
    compose.onNodeWithText("Searching stocks, ETFs and forex needs a free Twelve Data API key.").assertIsDisplayed()
    assertTrue("no stock search goes out without a key", app.http.requests.none { it.path == "symbol_search" })
    compose.onNodeWithText("Add the key in Settings").performClick()
    waitForText("Get a free key")
  }

  @Test
  fun `detail shows statistics and a chart that follows the selected range`() {
    addEtherFromSearch()
    waitForText("68,161.53")

    compose.onNodeWithText("ETH-USD").performClick()

    waitForText("Price 24 hours ago")
    compose.onNodeWithText("▼ 2,012.66 (2.87%) in 24 hours").assertIsDisplayed()
    compose.onNodeWithText("70,174.19").performScrollTo().assertIsDisplayed()
    waitForDescription("Price chart, 1D")

    compose.onNodeWithText("1Y").performScrollTo().performClick()
    waitForDescription("Price chart, 1Y")
    compose.onNodeWithText("1Y").assertIsSelected()
    val candleRequests = app.http.requests.filter { it.path.endsWith("/candles") }
    assertEquals(listOf("300", "86400", "86400"), candleRequests.map { it.query["granularity"] })

    // Scrubbing shows the price and date of the touched point instead of the range's change.
    assertTrue(exists("▼ 22,659.66 (24.95%) past year"))
    compose.onNode(hasContentDescription("Price chart, 1Y", substring = true)).performScrollTo().performTouchInput { swipeRight() }
    waitFor("the range change to return") { exists("▼ 22,659.66 (24.95%) past year") }

    pressBack()
    compose.onNodeWithText("Watchlist").assertIsDisplayed()
  }

  @Test
  fun `edit mode renames, moves to another section, removes and restores an entry`() {
    addEtherFromSearch()
    waitForText("68,161.53")

    compose.onNodeWithContentDescription("Edit watchlist").performClick()
    compose.onNodeWithText("Edit watchlist").assertIsDisplayed()
    // A single entry cannot move anywhere.
    compose.onNodeWithContentDescription("Move ETH-USD up").assertIsNotEnabled()
    compose.onNodeWithContentDescription("Move ETH-USD down").assertIsNotEnabled()

    compose.onNodeWithContentDescription("Edit ETH-USD").performClick()
    compose.onNode(hasSetTextAction()).performTextReplacement("Long-term stash")
    // On the test's small screen the dialog scrolls, and "Other" is its last choice.
    compose.onNodeWithText("Other").performScrollTo().performClick().assertIsSelected()
    compose.onNodeWithText("Save").performClick()
    waitForText("Long-term stash")
    val edited = app.container.watchlistStore.assets.value.single()
    assertEquals("Long-term stash", edited.label)
    assertEquals(AssetGroup.OTHER, edited.group)
    compose.onNodeWithText("Other").assertIsDisplayed()
    assertTrue("the empty Crypto section is gone", !exists("Crypto"))

    compose.onNodeWithContentDescription("Remove ETH-USD").performClick()
    waitForText("Removed ETH-USD")
    assertTrue(app.container.watchlistStore.assets.value.isEmpty())

    compose.onNodeWithText("Undo").performClick()
    waitForText("Long-term stash")
    assertEquals(listOf("ETH-USD"), app.container.watchlistStore.assets.value.map { it.symbol })
  }

  @Test
  fun `back leaves edit mode before it leaves the app`() {
    addEtherFromSearch()
    compose.onNodeWithContentDescription("Edit watchlist").performClick()
    compose.onNodeWithText("Edit watchlist").assertIsDisplayed()

    pressBack()

    compose.onNodeWithText("Watchlist").assertIsDisplayed()
    assertTrue(!compose.activity.isFinishing)
  }

  @Test
  fun `with an api key stocks can be found, added and priced`() {
    compose.onNodeWithText("Open Settings").performClick()
    compose.onNode(hasSetTextAction()).performTextInput("valid-key")
    compose.onNodeWithText("Check key").performClick()
    waitForText("The key works. 1 of 8 requests used this minute, 12 of 800 today.")
    assertEquals("valid-key", app.container.settingsStore.settings.value.twelveDataApiKey)
    pressBack()

    compose.onNodeWithText("Add an asset").performClick()
    compose.onNode(hasSetTextAction()).performTextInput("AAPL")
    // The stock search goes out once typing has paused.
    waitForText("NASDAQ · Stock · USD")
    // Apple is listed on several exchanges the free plan covers; its home listing comes first.
    compose.onAllNodesWithContentDescription("Add AAPL to watchlist").onFirst().performClick()
    chooseSection("Stocks")
    waitForDescription("Remove AAPL from watchlist")
    pressBack()

    waitForText("356.27")
    compose.onNodeWithText("Stocks").assertIsDisplayed()
    compose.onNodeWithText("Apple Inc.").assertIsDisplayed()
    compose.onNodeWithText("▲ 1.39 (0.39%)").assertIsDisplayed()

    compose.onNodeWithText("AAPL").performClick()
    waitForText("Previous close")
    compose.onNodeWithText("▲ 1.39 (0.39%) since previous close").assertIsDisplayed()
    waitForDescription("Price chart, 1D")
    assertTrue(exists("As of Nov 13, 8:59 PM · Market closed") || exists("As of Nov 13, 8:59 PM · Market closed"))
  }

  @Test
  fun `a wrong api key is explained in settings and in search`() {
    compose.onNodeWithText("Open Settings").performClick()
    compose.onNode(hasSetTextAction()).performTextInput("wrong-key")
    compose.onNodeWithText("Check key").performClick()
    waitForText("Twelve Data does not accept this key. Check that it was copied completely.")
    pressBack()

    compose.onNodeWithText("Add an asset").performClick()
    compose.onNode(hasSetTextAction()).performTextInput("AAPL")
    waitForText("API key rejected. Could not search Stocks, ETFs, forex.")
  }
}

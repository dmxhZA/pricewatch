# PriceWatch

A small Android app for checking the prices of a personal list of stocks, ETFs, currency pairs and
crypto once or a few times a day. It has a watchlist, a search screen for adding assets, a detail
screen with a price chart (1D, 1W, 1M, 3M, 1Y, 5Y) and a settings screen. There is no server and
no account: the watchlist, settings and last known prices are JSON files in the app's private
storage on the phone.

The watchlist is split into sections: Crypto, Stocks, Indices & ETFs, and Other (forex,
commodities; shown only when used). When you add an asset you pick its section; the one that fits
the provider's instrument type ("Common Stock", "ETF", ...) is marked as suggested. Edit mode moves
an entry to another section.

## Where prices come from

| Assets | Source | Setup |
| --- | --- | --- |
| Crypto pairs (BTC-USD, ETH-EUR, ...) | Coinbase Exchange public market data | None |
| US-listed stocks and ETFs, currency pairs | Twelve Data, free "Basic" plan | A free API key, entered in Settings |

The Twelve Data free plan allows 8 requests a minute and 800 a day, and one quote costs one
request. The app stays inside the per-minute limit by itself: a watchlist with more than six
stocks is priced in batches a minute apart, and the screen says so while it waits. Get a key at
<https://twelvedata.com/pricing> (email address only), then paste it under Settings and press
"Check key".

## Install

Copy `app/build/outputs/apk/release/app-release.apk` to the phone and open it (Android asks once
to allow installing from that source), or with the phone connected over USB:

    adb install -r app/build/outputs/apk/release/app-release.apk

Requires Android 8.0 or newer.

## Build and test

Needs JDK 17 or newer and the Android SDK (platform 36); set `JAVA_HOME` and `ANDROID_HOME`.

    ./gradlew assembleRelease      # builds the APK
    ./gradlew testDebugUnitTest    # runs the tests on this machine, no phone needed

Every build is signed with the key in `signing/`, so a rebuilt APK installs over the previous one
and keeps the watchlist. See `signing/README.md`.

The tests replace only the network, with responses recorded from the two real services
(`app/src/test/resources/fixtures`). `AppFlowTest` drives the real screens end to end.

## Code layout

    app/src/main/kotlin/app/pricewatch/
      data/source/   Twelve Data and Coinbase clients, request rate limiter
      data/store/    watchlist, settings and quote cache as JSON files
      data/          MarketRepository: what to refresh and when, chart cache
      ui/            watchlist, search, detail (chart), settings screens

# OANDA Integration Verification Checklist

`OandaBrokerAdapter` was written against the public v20 API docs with no way to test it against
a real account in the sandbox it was built in. This is the checklist to close that gap. Budget
about 20 minutes — most of it is reading real JSON next to `OandaApi.kt`.

## 0. Prerequisites

1. Create a free OANDA practice account: https://www.oanda.com/demo-account/tpa/personal_finance
2. Generate a personal access token: account settings → "Manage API Access" → generate token.
   This is the only secret involved — never commit it, never hardcode it. See §5 below for where
   it actually belongs in this app.
3. Find your account ID — shown on the practice account dashboard, format `xxx-xxx-xxxxxxx-xxx`.
4. Export both as environment variables for the checks below:
   ```
   export OANDA_API_KEY="your-token-here"
   export OANDA_ACCOUNT_ID="001-001-xxxxxxx-001"
   ```

## 1. Run the opt-in integration tests

`OandaLiveIntegrationTest.kt` (added alongside this checklist) hits the real practice API only
when the two environment variables above are set — it skips itself cleanly otherwise, so it's
safe to leave in the test suite permanently.

```
./gradlew test --tests "*.OandaLiveIntegrationTest" -Dorg.gradle.jvmargs="-DOANDA_API_KEY=$OANDA_API_KEY"
```

(Exact invocation depends on how your Gradle setup passes env vars through to the test JVM —
some setups need `environment("OANDA_API_KEY", ...)` added to the `test { }` block in
`build.gradle.kts`; add that if the variables aren't reaching the test process.)

Each test prints what it fetched. Read the printed output, not just pass/fail — a test can pass
structurally while still surfacing a value worth double-checking by eye (see §3).

## 2. Manual curl comparison (do this even if the tests pass)

For each endpoint, run the curl command and diff the real JSON keys against the matching data
class in `OandaApi.kt`. A field this app doesn't already model just gets ignored (harmless); a
field OANDA *doesn't* send that this app expects to parse will throw or null out silently
(need to know either way).

```bash
BASE=https://api-fxpractice.oanda.com
AUTH="Authorization: Bearer $OANDA_API_KEY"

# Account summary -> compare against OandaAccount
curl -s -H "$AUTH" "$BASE/v3/accounts/$OANDA_ACCOUNT_ID/summary" | python3 -m json.tool

# Pricing -> compare against OandaPrice / OandaPriceLevel
curl -s -H "$AUTH" "$BASE/v3/accounts/$OANDA_ACCOUNT_ID/pricing?instruments=EUR_USD" | python3 -m json.tool

# Candles -> compare against OandaCandle / OandaOhlc
curl -s -H "$AUTH" "$BASE/v3/instruments/EUR_USD/candles?granularity=M15&count=5&price=M" | python3 -m json.tool

# Open trades -> compare against OandaTrade (only meaningful once you have an open practice trade)
curl -s -H "$AUTH" "$BASE/v3/accounts/$OANDA_ACCOUNT_ID/openTrades" | python3 -m json.tool
```

## 3. The two specific assumptions flagged in the adapter's file header

These are called out because if either is wrong, the adapter fails in a way that's easy to miss
rather than a crash:

- **Bid/ask ordering.** `latestTick()` reads `bids[0]` and `asks[0]` as the best (top-of-book)
  prices. Look at the pricing JSON from §2 — if there's more than one entry in `bids`/`asks`,
  confirm index 0 is actually the best price (highest bid / lowest ask), not the worst. If it's
  reversed, fix the `.firstOrNull()` calls in `OandaBrokerAdapter.latestTick` to `.lastOrNull()`
  (or add explicit sorting).
- **Attached stop-loss/take-profit field names on an open trade.** Place a manual practice trade
  in the OANDA web/mobile app with a stop-loss and take-profit attached, then run the openTrades
  curl in §2 and look for how those two prices actually appear on the trade object (OANDA's docs
  suggest `stopLossOrder`/`takeProfitOrder` sub-objects, each with a `price` field, but this
  wasn't verified). `OandaBrokerAdapter.openPositions()` currently hardcodes `stopLoss = 0.0` and
  `takeProfit = 0.0` for exactly this reason — update `OandaTrade` in `OandaApi.kt` with the real
  shape and wire those two fields through once confirmed.

## 4. Order placement (only once §2 and §3 are clean)

Placing a real order — even in a practice account — is worth doing deliberately, not as a
drive-by check:

1. Use `PaperTradingEngine` with this adapter (`mode = DEMO` under `PRACTICE`) rather than
   calling `submitOrder` directly, so the full risk pipeline is exercised too.
2. Place ONE small trade, then immediately check `openPositions()` reflects it and the OANDA app
   shows the same trade.
3. Close it through `closePosition()` and confirm the app shows it closed.
4. Deliberately trigger a rejection (e.g., a stop-loss on the wrong side of the entry price) and
   confirm `submitOrder` returns `OrderResult.Rejected` with a real reason string, not `Unknown`.

## 5. Wiring the credential in the app (not part of verification, but don't skip this)

The API key goes in `SecurePrefs` (the same Keystore-backed store everything else's credentials
live in) — add a property there following the existing `searchApiKey` pattern, never a
constructor default, a `BuildConfig` field, or anything checked into source control.

## Sign-off

- [ ] §1 opt-in tests pass and printed output looks sane
- [ ] §2 account summary fields match `OandaAccount`
- [ ] §2 pricing fields match `OandaPrice`/`OandaPriceLevel`
- [ ] §2 candle fields match `OandaCandle`/`OandaOhlc`
- [ ] §3 bid/ask ordering confirmed (or fixed)
- [ ] §3 stop-loss/take-profit field names confirmed (or fixed) — only skip if you don't need
      `openPositions()` to report real SL/TP levels yet
- [ ] §4 a real practice trade was placed, monitored, and closed through the adapter
- [ ] §5 API key lives in `SecurePrefs`, not in source

Only once every box above is checked should this adapter be trusted with anything beyond further
testing — and even then, only in `PRACTICE`/DEMO mode. `LIVE` mode still has no execution engine
built for it at all (see the comment block at the bottom of `OandaBrokerAdapter.kt`).

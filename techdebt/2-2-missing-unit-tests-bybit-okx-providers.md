# Missing unit tests for BybitTradeStreamProvider and OkxTradeStreamProvider

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Small |
| Location | `services/whale-service/src/main/java/com/cryptoradar/whale/provider/bybit/BybitTradeStreamProvider.java`, `services/whale-service/src/main/java/com/cryptoradar/whale/provider/okx/OkxTradeStreamProvider.java` |
| Found during | QA coverage check — recent commit batch |
| Date | 2026-04-03 |

## Issue

Both `BybitTradeStreamProvider` and `OkxTradeStreamProvider` were changed in the recent commit batch. Neither has a corresponding test class. The core logic that needs coverage is the `parseTradeMessage()` method in each, which:

- Parses JSON from the exchange WebSocket feed
- Applies the whale threshold filter
- Maps exchange-specific field names to `WhaleTransaction`
- Maps OKX instrument IDs (e.g. `BTC-USDT`) to canonical symbols (e.g. `BTCUSDT`)

`BinanceTradeStreamProvider` (unchanged) also lacks a test, but those two are the actively modified ones.

`AbstractExchangeStreamProvider` has no test either — the `processTransaction()` threshold guard and the message-buffering `Listener` are untested.

## Risks

- Silent regressions if exchange WebSocket message format changes — the `parseTradeMessage` implementations silently `return null` on any parse error, so broken parsing produces zero whale events rather than a visible failure.
- The OKX `SYMBOL_MAP` could silently drop symbols if keys are mis-keyed; there is no test to assert correct mapping.
- The `getThreshold()` guard in `processTransaction()` is exercised only at runtime; a refactor could break the filter unnoticed.

## Suggested Solutions

1. **Unit-test `parseTradeMessage()` directly** — instantiate the provider with a mocked `ObjectMapper` and `WhaleFlowService`, feed it raw JSON strings matching the real exchange format, and assert the returned `WhaleTransaction` fields (symbol, side, value, source). Use `@ExtendWith(MockitoExtension.class)`.

2. **Test the threshold filter** — pass a trade below threshold and assert `null` is returned; pass one above and assert a valid `WhaleTransaction`.

3. **Test OKX symbol mapping** — assert each entry in `SYMBOL_MAP` maps to the correct canonical symbol and that an unknown `instId` returns `null`.

Example test skeleton:
```java
@ExtendWith(MockitoExtension.class)
@DisplayName("OkxTradeStreamProvider")
class OkxTradeStreamProviderTest {

    private OkxTradeStreamProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OkxTradeStreamProvider();
        // inject threshold via reflection or a test subclass
    }

    @Test
    @DisplayName("parseTradeMessage returns null for non-trade messages")
    void parseTradeMessage_nonTrade_returnsNull() { ... }

    @Test
    @DisplayName("parseTradeMessage maps BTC-USDT to BTCUSDT")
    void parseTradeMessage_btcUsdt_mapsSymbolCorrectly() { ... }
}
```

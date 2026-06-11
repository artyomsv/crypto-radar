# Turtle + Donchian — Plan 2: Live Single-Unit Execution

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take the `donchian`/`turtle-s1`/`turtle-s2` breakout signals (built in Plan 1) all the way to **live single-unit Bybit execution** with native Turtle exits — no pyramiding yet.

**Architecture:** The breakout strategies already reach `trade-execution-service` over the existing `crypto:signals` Redis path (Plan 1 detectors emit `TradeSetup`s like every other detector). This plan makes the execution side treat them correctly: exempt them from the alignment-floor gate (their alignment is a fixed mechanical 60, not a confluence score), keep them out of the intraday `StagnationMonitor`/`TrailMirror` (they're multi-day trades), enforce mutual exclusion among the breakout family per symbol+direction, and close them on a live reverse-Donchian breach via a new `DonchianExitMonitor`. The Bybit-attached 2N stop (already placed by `OrderPlacer` from the signal's stop) remains the catastrophic backstop.

**Tech Stack:** Java 21, Quarkus 3.17, CDI, `@Scheduled`, Panache, JUnit 5, `shared-trade-core` `DonchianMath` (already a dependency of this service).

**Scope:** Plan 2 of 3. **Plan 3 = `PyramidingEngine`** (add units at 0.5N, `executed_trade_units` child table, heat/unit caps, `DonchianMath.impliedN`) is written + built separately, after this plan is validated live. This plan deliberately does NOT add pyramiding, the units table, or sizing caps.

**Spec:** `docs/superpowers/specs/2026-06-11-turtle-donchian-strategy-design.md` §4.4 (exit handling, mutual exclusion, exit policy), §4.5 (flags, marker). §4.4 PyramidingEngine is Plan 3.

**Branch:** continue on `feat/turtle-donchian-strategy` (Plan 1 is already there).

**Key derivations / decisions (locked):**
- **No signal-schema change.** Turtle stop = `entry ∓ 2N`, so N is recoverable as `|entry − stop|/2` when Plan 3 needs it. Plan 2's Donchian exit recomputes channels live and needs no N.
- **Long-horizon strategy set** = `{donchian, turtle-s1, turtle-s2}`, config-driven via `execution.long-horizon-strategies` (CSV). One `StrategyExitPolicy` bean is the single source of truth, injected everywhere a per-strategy decision is made.
- **Exit lookbacks:** `donchian`/`turtle-s1` exit on the reverse **10-day** channel; `turtle-s2` exits on the reverse **20-day** channel.
- **Mutual exclusion** is scoped to the breakout family: a new breakout placement is blocked iff another OPEN breakout-family trade already holds that symbol+direction. Existing strategies (`dimension`, `trend-continuation`, `liquidity-sweep`) are unaffected.
- **Guardrails retained:** `SymbolPerformanceGate`, `GuardrailPolicy` (kill-switch, max-concurrent, daily-halt, same-strategy dedup), and `DailyPnlCalculator` still apply to breakout trades — only the alignment-floor is exempted.

---

## File Structure

**trade-execution-service** (`services/trade-execution-service/src/main/java/com/cryptoradar/execution/`):
- Create `lifecycle/StrategyExitPolicy.java` — config-driven long-horizon strategy set; `isLongHorizon(strategy)`.
- Create `lifecycle/DonchianExitDecision.java` — pure exit-decision + per-strategy exit lookback.
- Create `lifecycle/DonchianExitMonitor.java` — `@Scheduled`; live reverse-channel close.
- Create `intake/MutualExclusionGuard.java` — blocks duplicate breakout-family symbol+direction.
- Modify `model/ExitReason.java` — add `DONCHIAN_EXIT`.
- Modify `client/MarketDataClient.java` — add `getDailyCandles(symbol, limit)` + a `DailyBar` carrier.
- Modify `repository/ExecutedTradeRepository.java` — add `findOpenBySymbolAndDirection`.
- Modify `lifecycle/StagnationMonitor.java` — skip long-horizon strategies.
- Modify `lifecycle/TrailMirror.java` — skip long-horizon strategies.
- Modify `intake/SignalSubscriber.java` — alignment-floor exemption + mutual-exclusion gate.
- Modify `model/ExecutionEventType.java` — add `SIGNAL_BLOCKED_MUTUAL_EXCLUSION`.
- Modify `src/main/resources/application.properties` — new config keys.
- Modify `db/init/execution-init.sql` — `DONCHIAN_EXIT` in exit_reason CHECK; (verify execution_events type constraint).

**Tests:**
- `DonchianExitDecisionTest`, `StrategyExitPolicyTest`, `MarketDataClientDailyBarsTest` (parse), `MutualExclusionGuardTest`.

**deployment marker:** `db/init/signal-init.sql` (markers table lives in signal-service schema) — `v8-turtle-donchian-live`.

---

## Phase A — Foundations (no behaviour change)

### Task 1: Add `DONCHIAN_EXIT` to `ExitReason` + DB constraint

**Files:**
- Modify: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/model/ExitReason.java`
- Modify: `db/init/execution-init.sql`

- [ ] **Step 1: Add the enum value**

Append `DONCHIAN_EXIT` to `ExitReason` (after `STAGNATION`), with a comment:
```java
    STAGNATION,       // Stagnation monitor triggered
    DONCHIAN_EXIT     // Reverse Donchian-channel breach (Turtle/Donchian native exit)
}
```

- [ ] **Step 2: Extend the DB CHECK constraint**

In `db/init/execution-init.sql`, find `executed_trades_exit_reason_ck` and add `'DONCHIAN_EXIT'` to the `IN (...)` list:
```sql
    CONSTRAINT executed_trades_exit_reason_ck
        CHECK (exit_reason IS NULL OR exit_reason IN
            ('TARGET', 'INITIAL_STOP', 'TRAIL_STOP', 'EXPIRED', 'FLIP_CLOSE', 'MANUAL', 'KILL', 'STAGNATION', 'DONCHIAN_EXIT'))
```
Note: `db/init` only runs on a fresh DB. For the running DB, the matching live migration is applied in Task 12 (deploy step). Record it now in the init file so fresh environments are correct.

- [ ] **Step 3: Compile-check**

Run: `cd services/trade-execution-service && mvnd -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/model/ExitReason.java db/init/execution-init.sql
git commit -m "feat(execution): add DONCHIAN_EXIT exit reason"
```

---

### Task 2: `ExecutedTradeRepository.findOpenBySymbolAndDirection`

**Files:**
- Modify: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/repository/ExecutedTradeRepository.java`

The existing `findOpenBySymbolAndDirectionAndStrategy` requires a strategy. Mutual exclusion needs the strategy-agnostic version. Match the existing method's status-set + query style (it uses an OPEN-status `in` set — reuse the same constant/list the sibling method uses).

- [ ] **Step 1: Add the method**

```java
    /**
     * Any open trade for this account+symbol+direction regardless of strategy.
     * Backs the breakout-family mutual-exclusion guard. Uses the same
     * open-status set as {@link #findOpenBySymbolAndDirectionAndStrategy}.
     */
    public Optional<ExecutedTrade> findOpenBySymbolAndDirection(
            Long accountId, String symbol, String direction) {
        return find("exchangeAccountId = ?1 and symbol = ?2 and direction = ?3 and status in ?4",
                accountId, symbol, direction, OPEN_STATUSES)
                .firstResultOptional();
    }
```
If the sibling method inlines the status list rather than referencing a constant named `OPEN_STATUSES`, mirror exactly whatever it uses (e.g. `List.of(TradeStatus.PENDING_PLACE, TradeStatus.OPEN, TradeStatus.CLOSING)`).

- [ ] **Step 2: Compile-check**

Run: `cd services/trade-execution-service && mvnd -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/repository/ExecutedTradeRepository.java
git commit -m "feat(execution): add findOpenBySymbolAndDirection for mutual exclusion"
```

---

### Task 3: `MarketDataClient.getDailyCandles` + `DailyBar`

**Files:**
- Modify: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/MarketDataClient.java`
- Test: `services/trade-execution-service/src/test/java/com/cryptoradar/execution/client/MarketDataClientDailyBarsTest.java`

Mirrors signal-service's `CandleClient`: `GET {market-data.url}/api/market/candles/{symbol}?interval=1d&limit=N`, response is a DESC-by-time JSON array of `{time,open,high,low,close,volume}`. We reverse to oldest-first and expose a tiny `DailyBar` carrier. Parsing is extracted to a package-private pure method for testing.

- [ ] **Step 1: Write the failing test**

```java
package com.cryptoradar.execution.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataClientDailyBarsTest {

    private final MarketDataClient client = new MarketDataClient();

    @Test
    void parseDailyBars_reversesToOldestFirst_andMapsOHLC() {
        // upstream returns newest-first
        String json = """
            [
              {"time":"2026-06-10T00:00:00Z","open":2,"high":12,"low":1,"close":10,"volume":5},
              {"time":"2026-06-09T00:00:00Z","open":3,"high":9,"low":2,"close":8,"volume":7}
            ]
            """;
        List<MarketDataClient.DailyBar> bars = client.parseDailyBars(json);
        assertEquals(2, bars.size());
        // oldest-first: 2026-06-09 first
        assertEquals(9.0, bars.get(0).high());
        assertEquals(2.0, bars.get(0).low());
        assertEquals(12.0, bars.get(1).high());
        assertEquals(10.0, bars.get(1).close());
    }

    @Test
    void parseDailyBars_emptyOrMalformed_returnsEmpty() {
        assertTrue(client.parseDailyBars("[]").isEmpty());
        assertTrue(client.parseDailyBars("not json").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/trade-execution-service && mvnd -q test -Dtest=MarketDataClientDailyBarsTest`
Expected: FAIL — `DailyBar` / `parseDailyBars` / no-arg constructor not present.

- [ ] **Step 3: Implement**

`MarketDataClient` currently has a constructor with injected fields. Add a no-arg constructor for the parse unit test (the existing constructor stays for CDI), the `DailyBar` record, the `getDailyCandles` method, and the package-private `parseDailyBars`. Add imports as needed (`com.fasterxml.jackson.databind.*`, `java.net.http.*`, `java.util.*`, `java.time.*`).

```java
    /** Compact daily OHLC bar for Donchian channel computation. */
    public record DailyBar(java.time.Instant time, double open, double high,
                           double low, double close) {}

    /**
     * Fetch the most recent {@code limit} daily candles for the symbol, oldest
     * first. Empty on any failure (fail-open: the caller skips this tick).
     * Mirrors signal-service CandleClient's endpoint + ordering.
     */
    public List<DailyBar> getDailyCandles(String symbol, int limit) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    baseUrl + "/api/market/candles/" + symbol + "?interval=1d&limit=" + limit))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warnf("market-data 1d HTTP %d for %s", resp.statusCode(), symbol);
                return List.of();
            }
            return parseDailyBars(resp.body());
        } catch (IOException | InterruptedException | RuntimeException e) {
            LOG.warnf(e, "market-data 1d fetch failed for %s", symbol);
            return List.of();
        }
    }

    /** Parse the DESC-by-time JSON array into oldest-first DailyBars. Pure; package-private for testing. */
    List<DailyBar> parseDailyBars(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isArray()) return List.of();
            List<DailyBar> bars = new ArrayList<>(root.size());
            for (JsonNode e : root) {
                JsonNode t = e.path("time");
                if (t.isMissingNode() || t.isNull()) continue;
                bars.add(new DailyBar(
                        java.time.Instant.parse(t.asText()),
                        e.path("open").asDouble(), e.path("high").asDouble(),
                        e.path("low").asDouble(), e.path("close").asDouble()));
            }
            java.util.Collections.reverse(bars); // upstream DESC -> oldest-first
            return bars;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }
```
Add a no-arg constructor that initialises only what `parseDailyBars` needs (a fresh `ObjectMapper`), leaving the existing CDI constructor intact:
```java
    /** Test-only: parse helpers don't need HTTP/config. */
    MarketDataClient() {
        this.mapper = new ObjectMapper();
        this.http = null;
        this.baseUrl = null;
    }
```
(If `mapper`/`http`/`baseUrl` are `final`, this is fine — all are assigned. If the existing fields aren't named exactly `mapper`/`http`/`baseUrl`, use the real names from the file.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/trade-execution-service && mvnd -q test -Dtest=MarketDataClientDailyBarsTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/MarketDataClient.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/client/MarketDataClientDailyBarsTest.java
git commit -m "feat(execution): add daily-candle fetch to MarketDataClient"
```

---

## Phase B — Keep intraday exits off long-horizon trades

### Task 4: `StrategyExitPolicy` bean

**Files:**
- Create: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/StrategyExitPolicy.java`
- Test: `services/trade-execution-service/src/test/java/com/cryptoradar/execution/lifecycle/StrategyExitPolicyTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.cryptoradar.execution.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrategyExitPolicyTest {

    private StrategyExitPolicy policy(String csv) {
        StrategyExitPolicy p = new StrategyExitPolicy();
        p.longHorizonCsv = csv;
        return p;
    }

    @Test
    void recognisesConfiguredLongHorizonStrategies() {
        StrategyExitPolicy p = policy("donchian,turtle-s1,turtle-s2");
        assertTrue(p.isLongHorizon("donchian"));
        assertTrue(p.isLongHorizon("turtle-s1"));
        assertTrue(p.isLongHorizon("turtle-s2"));
    }

    @Test
    void otherStrategiesAreNotLongHorizon() {
        StrategyExitPolicy p = policy("donchian,turtle-s1,turtle-s2");
        assertFalse(p.isLongHorizon("trend-continuation"));
        assertFalse(p.isLongHorizon("dimension"));
        assertFalse(p.isLongHorizon(null));
    }

    @Test
    void toleratesWhitespaceAndBlankEntries() {
        StrategyExitPolicy p = policy(" donchian , , turtle-s1 ");
        assertTrue(p.isLongHorizon("donchian"));
        assertTrue(p.isLongHorizon("turtle-s1"));
        assertFalse(p.isLongHorizon("turtle-s2"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/trade-execution-service && mvnd -q test -Dtest=StrategyExitPolicyTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

```java
package com.cryptoradar.execution.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for which strategies are "long-horizon" (multi-day
 * Turtle/Donchian breakouts). These are kept out of the intraday
 * StagnationMonitor + TrailMirror, exempted from the alignment-floor gate, and
 * recognised by the DonchianExitMonitor + mutual-exclusion guard.
 */
@ApplicationScoped
public class StrategyExitPolicy {

    @ConfigProperty(name = "execution.long-horizon-strategies",
            defaultValue = "donchian,turtle-s1,turtle-s2")
    String longHorizonCsv;

    public boolean isLongHorizon(String strategy) {
        if (strategy == null) return false;
        return parse().contains(strategy);
    }

    private Set<String> parse() {
        return Arrays.stream(longHorizonCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/trade-execution-service && mvnd -q test -Dtest=StrategyExitPolicyTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/StrategyExitPolicy.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/lifecycle/StrategyExitPolicyTest.java
git commit -m "feat(execution): add StrategyExitPolicy (long-horizon strategy set)"
```

---

### Task 5: Skip long-horizon trades in `StagnationMonitor` and `TrailMirror`

**Files:**
- Modify: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/StagnationMonitor.java`
- Modify: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/TrailMirror.java`

Both already iterate `tradeRepo.findOpenForAccount(...)`. Inject `StrategyExitPolicy` and `continue` past long-horizon trades so the 45-min stagnation exit and the R-trail never touch multi-day Turtle positions (their only exits are the Bybit 2N stop + `DonchianExitMonitor`).

- [ ] **Step 1: StagnationMonitor — inject + guard**

Add `StrategyExitPolicy exitPolicy` to the constructor (follow the existing constructor-injection list). In `sweepForAccount`, inside the `for (ExecutedTrade trade : open)` loop, add as the FIRST statement:
```java
            if (exitPolicy.isLongHorizon(trade.getStrategy())) continue;
```

- [ ] **Step 2: TrailMirror — inject + guard**

Add `StrategyExitPolicy exitPolicy` to the constructor. In `processAccount`, inside the `for (ExecutedTrade trade : ...)` loop, add as the FIRST statement (before `processTrade`):
```java
            if (exitPolicy.isLongHorizon(trade.getStrategy())) continue;
```

- [ ] **Step 3: Compile + run the touched monitors' existing tests (if any) + full module compile**

Run: `cd services/trade-execution-service && mvnd -q test-compile && mvnd -q compile`
Expected: BUILD SUCCESS. (If `StagnationMonitorTest`/`TrailMirrorTest` exist, run them: `mvnd -q test -Dtest=StagnationMonitorTest,TrailMirrorTest` — they must still pass; the guard only adds a skip for long-horizon strategies which those tests don't use.)

- [ ] **Step 4: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/StagnationMonitor.java \
        services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/TrailMirror.java
git commit -m "feat(execution): exclude long-horizon strategies from stagnation + trail"
```

---

## Phase C — Intake: alignment-floor exemption + mutual exclusion

### Task 6: Add `SIGNAL_BLOCKED_MUTUAL_EXCLUSION` event type

**Files:**
- Modify: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/model/ExecutionEventType.java`
- Modify (if a CHECK constraint exists): `db/init/execution-init.sql`

- [ ] **Step 1: Add the enum value**

Add `SIGNAL_BLOCKED_MUTUAL_EXCLUSION` alongside the other `SIGNAL_BLOCKED_*` values in `ExecutionEventType`.

- [ ] **Step 2: Check for a DB constraint**

Grep `execution-init.sql` for `event_type` and `SIGNAL_BLOCKED_ALIGNMENT_FLOOR`. If the `execution_events` table has a CHECK constraint enumerating event types, add `'SIGNAL_BLOCKED_MUTUAL_EXCLUSION'` to it. If event_type is a free VARCHAR with no CHECK, no DB change is needed — note that in the commit.

- [ ] **Step 3: Compile-check**

Run: `cd services/trade-execution-service && mvnd -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/model/ExecutionEventType.java db/init/execution-init.sql
git commit -m "feat(execution): add SIGNAL_BLOCKED_MUTUAL_EXCLUSION event type"
```

---

### Task 7: `MutualExclusionGuard`

**Files:**
- Create: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/MutualExclusionGuard.java`
- Test: `services/trade-execution-service/src/test/java/com/cryptoradar/execution/intake/MutualExclusionGuardTest.java`

Pure decision over an injected repo + `StrategyExitPolicy`, so it's testable with hand-built fakes. It blocks iff an OPEN trade for the same symbol+direction exists whose strategy is long-horizon (breakout-family). Fail-open: any query exception → not blocked (a stuck read must not stop legitimate trades).

- [ ] **Step 1: Write the failing test**

```java
package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.lifecycle.StrategyExitPolicy;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MutualExclusionGuardTest {

    private final StrategyExitPolicy exitPolicy = new StrategyExitPolicy() {
        @Override public boolean isLongHorizon(String s) {
            return s != null && s.startsWith("turtle") || "donchian".equals(s);
        }
    };

    private MutualExclusionGuard guard(ExecutedTradeRepository repo) {
        return new MutualExclusionGuard(repo, exitPolicy);
    }

    private ExecutedTrade tradeWithStrategy(String strategy) {
        ExecutedTrade t = new ExecutedTrade();
        t.setStrategy(strategy);
        return t;
    }

    @Test
    void blocksWhenAnOpenBreakoutTradeHoldsSymbolDirection() {
        ExecutedTradeRepository repo = mock(ExecutedTradeRepository.class);
        when(repo.findOpenBySymbolAndDirection(1L, "BTCUSDT", "LONG"))
                .thenReturn(Optional.of(tradeWithStrategy("turtle-s1")));
        assertTrue(guard(repo).isBlocked(1L, "BTCUSDT", "LONG"));
    }

    @Test
    void allowsWhenExistingOpenTradeIsNotBreakoutFamily() {
        ExecutedTradeRepository repo = mock(ExecutedTradeRepository.class);
        when(repo.findOpenBySymbolAndDirection(1L, "BTCUSDT", "LONG"))
                .thenReturn(Optional.of(tradeWithStrategy("trend-continuation")));
        assertFalse(guard(repo).isBlocked(1L, "BTCUSDT", "LONG"));
    }

    @Test
    void allowsWhenNoOpenTrade() {
        ExecutedTradeRepository repo = mock(ExecutedTradeRepository.class);
        when(repo.findOpenBySymbolAndDirection(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        assertFalse(guard(repo).isBlocked(1L, "ETHUSDT", "SHORT"));
    }

    @Test
    void failOpenOnQueryError() {
        ExecutedTradeRepository repo = mock(ExecutedTradeRepository.class);
        when(repo.findOpenBySymbolAndDirection(anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        assertFalse(guard(repo).isBlocked(1L, "ETHUSDT", "SHORT"));
    }
}
```
(If Mockito is not already a test dependency of this service, add `quarkus-junit5-mockito` to `pom.xml` test scope as a sub-step — check first; the service has 111 tests so a mock framework is likely present.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/trade-execution-service && mvnd -q test -Dtest=MutualExclusionGuardTest`
Expected: FAIL — `MutualExclusionGuard` does not exist.

- [ ] **Step 3: Implement**

```java
package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.lifecycle.StrategyExitPolicy;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Blocks a breakout-family placement when another OPEN breakout-family trade
 * already holds the same symbol+direction (first-to-fire wins). Existing
 * non-breakout strategies are unaffected. Fail-open: a query error never
 * blocks a trade.
 */
@ApplicationScoped
public class MutualExclusionGuard {

    private static final Logger LOG = Logger.getLogger(MutualExclusionGuard.class);

    private final ExecutedTradeRepository tradeRepo;
    private final StrategyExitPolicy exitPolicy;

    public MutualExclusionGuard(ExecutedTradeRepository tradeRepo, StrategyExitPolicy exitPolicy) {
        this.tradeRepo = tradeRepo;
        this.exitPolicy = exitPolicy;
    }

    public boolean isBlocked(Long accountId, String symbol, String direction) {
        try {
            return tradeRepo.findOpenBySymbolAndDirection(accountId, symbol, direction)
                    .map(t -> exitPolicy.isLongHorizon(t.getStrategy()))
                    .orElse(false);
        } catch (RuntimeException e) {
            LOG.warnf(e, "mutual-exclusion query failed for %s %s — failing open", symbol, direction);
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/trade-execution-service && mvnd -q test -Dtest=MutualExclusionGuardTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/MutualExclusionGuard.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/intake/MutualExclusionGuardTest.java
git commit -m "feat(execution): add breakout-family MutualExclusionGuard"
```

---

### Task 8: Wire intake — exempt alignment floor + apply mutual exclusion

**Files:**
- Modify: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/SignalSubscriber.java`

Two surgical changes in the signal-handling path. Inject `StrategyExitPolicy exitPolicy` and `MutualExclusionGuard mutualExclusion` (add to the constructor / CDI field list following the existing pattern).

- [ ] **Step 1: Exempt long-horizon strategies from the alignment floor**

In `isBelowAlignmentFloor(...)`, add at the very top (the breakout strategies carry a fixed mechanical alignment of 60, which is not comparable to the confluence-derived floor; their safety comes from the other gates + the 2N stop):
```java
        String strategy = signalNode.path("strategy").asText("dimension");
        if (exitPolicy.isLongHorizon(strategy)) {
            return false; // mechanical breakout strategies bypass the confluence alignment floor
        }
```
(Use the actual `signalNode` parameter name from the method signature.)

- [ ] **Step 2: Apply mutual exclusion just before placement**

Locate the placement dispatch (`orderPlacer.place(account, new OrderPlacer.PlacementRequest(...))`, ~line 330). Immediately BEFORE it, add:
```java
        if (exitPolicy.isLongHorizon(candidate.strategy())
                && mutualExclusion.isBlocked(account.getId(), symbol, direction)) {
            recordBlockedEvent(account, candidate.signalId(), symbol, direction,
                    ExecutionEventType.SIGNAL_BLOCKED_MUTUAL_EXCLUSION,
                    "breakout-family symbol+direction already held");
            return;
        }
```
Match the EXACT signature of the existing `recordBlockedEvent(...)`/`recordBlocked(...)` helper used by the other gates (copy the argument shape from the alignment-floor or symbol-perf block site). Use the same `symbol`/`direction`/`candidate` locals already in scope at that point.

- [ ] **Step 3: Compile + run existing SignalSubscriber tests**

Run: `cd services/trade-execution-service && mvnd -q test -Dtest=SignalSubscriber*`
Expected: existing intake tests still pass (the new gate only affects long-horizon strategies, which existing tests don't exercise). If there are no SignalSubscriber tests, run `mvnd -q compile` and proceed.

- [ ] **Step 4: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/SignalSubscriber.java
git commit -m "feat(execution): exempt breakout strategies from alignment floor + mutual-exclusion gate"
```

---

## Phase D — Native Donchian exit

### Task 9: `DonchianExitDecision` (pure)

**Files:**
- Create: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/DonchianExitDecision.java`
- Test: `services/trade-execution-service/src/test/java/com/cryptoradar/execution/lifecycle/DonchianExitDecisionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.cryptoradar.execution.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DonchianExitDecisionTest {

    @Test
    void exitLookback_is10ForDonchianAndS1_20ForS2() {
        assertEquals(10, DonchianExitDecision.exitLookback("donchian"));
        assertEquals(10, DonchianExitDecision.exitLookback("turtle-s1"));
        assertEquals(20, DonchianExitDecision.exitLookback("turtle-s2"));
    }

    @Test
    void exitLookback_unknownStrategy_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> DonchianExitDecision.exitLookback("dimension"));
    }

    @Test
    void longExitsWhenPriceAtOrBelowReverseLow() {
        assertTrue(DonchianExitDecision.shouldExit(true, 99.0, 100.0, 120.0));   // price <= low
        assertTrue(DonchianExitDecision.shouldExit(true, 100.0, 100.0, 120.0));  // equal -> exit
        assertFalse(DonchianExitDecision.shouldExit(true, 101.0, 100.0, 120.0)); // above low -> hold
    }

    @Test
    void shortExitsWhenPriceAtOrAboveReverseHigh() {
        assertTrue(DonchianExitDecision.shouldExit(false, 121.0, 100.0, 120.0));  // price >= high
        assertTrue(DonchianExitDecision.shouldExit(false, 120.0, 100.0, 120.0));  // equal -> exit
        assertFalse(DonchianExitDecision.shouldExit(false, 119.0, 100.0, 120.0)); // below high -> hold
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/trade-execution-service && mvnd -q test -Dtest=DonchianExitDecisionTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

```java
package com.cryptoradar.execution.lifecycle;

/**
 * Pure decision logic for the native Turtle/Donchian exit. A LONG exits when
 * price breaches the reverse (low) channel; a SHORT exits when price breaches
 * the reverse (high) channel. Exit lookback is per strategy: donchian/turtle-s1
 * use the 10-day reverse channel, turtle-s2 uses the 20-day reverse channel.
 */
public final class DonchianExitDecision {

    private DonchianExitDecision() {}

    private static final int EXIT_LOOKBACK_FAST = 10; // donchian, turtle-s1
    private static final int EXIT_LOOKBACK_SLOW = 20; // turtle-s2

    public static int exitLookback(String strategy) {
        return switch (strategy) {
            case "donchian", "turtle-s1" -> EXIT_LOOKBACK_FAST;
            case "turtle-s2" -> EXIT_LOOKBACK_SLOW;
            default -> throw new IllegalArgumentException(
                    "no Donchian exit lookback for strategy: " + strategy);
        };
    }

    /** LONG exits at/below reverseLow; SHORT exits at/above reverseHigh. */
    public static boolean shouldExit(boolean isLong, double price,
                                     double reverseLow, double reverseHigh) {
        return isLong ? price <= reverseLow : price >= reverseHigh;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/trade-execution-service && mvnd -q test -Dtest=DonchianExitDecisionTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/DonchianExitDecision.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/lifecycle/DonchianExitDecisionTest.java
git commit -m "feat(execution): add pure DonchianExitDecision"
```

---

### Task 10: `DonchianExitMonitor` (`@Scheduled`)

**Files:**
- Create: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/DonchianExitMonitor.java`

Thin orchestration over the already-tested pure pieces (`DonchianExitDecision`, `DonchianMath` from shared-core, `MarketDataClient.getDailyCandles`). It mirrors `StagnationMonitor`'s structure: `@Scheduled`, iterate accounts → open long-horizon trades → decide → `orderPlacer.close(..., DONCHIAN_EXIT)`. Fail-open per trade. No dedicated unit test (the decision + channel math are tested; this is wiring) — verified in the live smoke (Task 12).

- [ ] **Step 1: Implement**

```java
package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.core.DonchianMath;
import com.cryptoradar.execution.client.MarketDataClient;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExitReason;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;

/**
 * Closes open Turtle/Donchian positions when price breaches the live reverse
 * Donchian channel (10-day for donchian/turtle-s1, 20-day for turtle-s2). The
 * Bybit-attached 2N stop remains the catastrophic backstop; this monitor is the
 * primary, trend-following exit. Recomputes channels from fresh daily candles
 * each tick (the A+C "live exit" half of the design). Fail-open per trade.
 */
@ApplicationScoped
public class DonchianExitMonitor {

    private static final Logger LOG = Logger.getLogger(DonchianExitMonitor.class);
    /** Fetch enough daily bars to cover the 20-day exit + 1 excluded forming bar, with slack. */
    private static final int CANDLE_FETCH = 30;

    @ConfigProperty(name = "execution.donchian-exit.enabled", defaultValue = "true")
    boolean enabled;

    private final ExchangeAccountRepository accountRepo;
    private final ExecutedTradeRepository tradeRepo;
    private final StrategyExitPolicy exitPolicy;
    private final MarketDataClient marketData;
    private final OrderPlacer orderPlacer;

    public DonchianExitMonitor(ExchangeAccountRepository accountRepo, ExecutedTradeRepository tradeRepo,
                               StrategyExitPolicy exitPolicy, MarketDataClient marketData,
                               OrderPlacer orderPlacer) {
        this.accountRepo = accountRepo;
        this.tradeRepo = tradeRepo;
        this.exitPolicy = exitPolicy;
        this.marketData = marketData;
        this.orderPlacer = orderPlacer;
    }

    @Scheduled(every = "${execution.donchian-exit.interval:60s}", delayed = "50s")
    @Transactional
    public void sweep() {
        if (!enabled) return;
        for (ExchangeAccount account : accountRepo.listAll()) {
            for (ExecutedTrade trade : tradeRepo.findOpenForAccount(account.getId())) {
                if (!exitPolicy.isLongHorizon(trade.getStrategy())) continue;
                try {
                    evaluate(account, trade);
                } catch (RuntimeException e) {
                    LOG.warnf(e, "donchian-exit check failed for trade %d — skipping", trade.getId());
                }
            }
        }
    }

    private void evaluate(ExchangeAccount account, ExecutedTrade trade) {
        int lookback = DonchianExitDecision.exitLookback(trade.getStrategy());
        List<MarketDataClient.DailyBar> bars = marketData.getDailyCandles(trade.getSymbol(), CANDLE_FETCH);
        if (bars.size() < lookback + 1) return; // not enough history; backstop stop still active

        int endExclusive = bars.size() - 1; // exclude today's forming bar
        double[] highs = new double[bars.size()];
        double[] lows = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            highs[i] = bars.get(i).high();
            lows[i] = bars.get(i).low();
        }
        double reverseLow = DonchianMath.channelLow(lows, endExclusive, lookback);
        double reverseHigh = DonchianMath.channelHigh(highs, endExclusive, lookback);

        BigDecimal priceBd = marketData.getLastPrice(trade.getSymbol());
        if (priceBd == null) return;
        boolean isLong = "LONG".equals(trade.getDirection());

        if (DonchianExitDecision.shouldExit(isLong, priceBd.doubleValue(), reverseLow, reverseHigh)) {
            LOG.infof("DONCHIAN_EXIT account=%d trade=%d %s %s price=%s reverseLow=%.6f reverseHigh=%.6f",
                    account.getId(), trade.getId(), trade.getSymbol(), trade.getDirection(),
                    priceBd.toPlainString(), reverseLow, reverseHigh);
            orderPlacer.close(account, trade, ExitReason.DONCHIAN_EXIT);
        }
    }
}
```
(Confirm `ExchangeAccountRepository` is the real account-repo type name used by `StagnationMonitor` — copy whatever it injects. If it injects `accountRepo.listAll()` from a differently-named class, use that.)

- [ ] **Step 2: Compile**

Run: `cd services/trade-execution-service && mvnd -q compile`
Expected: BUILD SUCCESS (resolves `DonchianMath` from shared-trade-core, `DailyBar`, `DonchianExitDecision`).

- [ ] **Step 3: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/DonchianExitMonitor.java
git commit -m "feat(execution): add DonchianExitMonitor (live reverse-channel close)"
```

---

## Phase E — Config, marker, verification

### Task 11: Config keys + `v8` deployment marker + full test run

**Files:**
- Modify: `services/trade-execution-service/src/main/resources/application.properties`
- Modify: `db/init/signal-init.sql`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add config keys**

Append to `services/trade-execution-service/src/main/resources/application.properties`:
```properties
# Turtle / Donchian live execution (Plan 2). Long-horizon strategies are kept
# out of intraday stagnation/trail exits and closed by the Donchian monitor.
execution.long-horizon-strategies=donchian,turtle-s1,turtle-s2
execution.donchian-exit.enabled=true
execution.donchian-exit.interval=60s
```

- [ ] **Step 2: Record the v8 deployment marker**

In `db/init/signal-init.sql`, find the `INSERT INTO deployment_markers` block (where v1..v7 are seeded) and add a row, matching the existing style:
```sql
INSERT INTO deployment_markers (deployed_at, version, description) VALUES
  ('2026-06-11 00:00:00+00', 'v8-turtle-donchian-live',
   'Daily Donchian/Turtle breakout strategies live single-unit on Bybit: donchian/turtle-s1/turtle-s2 entries, 2N stop, native reverse-Donchian exit (10d/20d), mutual exclusion among breakout family, exempt from intraday stagnation/trail + alignment floor. Pyramiding = Plan 3.')
ON CONFLICT (deployed_at) DO NOTHING;
```
(Use the exact timestamp/INSERT idiom already present; if markers are inserted one-per-statement, follow that form.)

- [ ] **Step 3: Run the FULL execution-service suite**

Run: `cd services/trade-execution-service && mvnd -q test`
Expected: PASS — the pre-existing 111 tests plus the new ones (≈13: MarketDataClientDailyBars 2, StrategyExitPolicy 3, MutualExclusionGuard 4, DonchianExitDecision 4). Zero failures.

- [ ] **Step 4: Document in CLAUDE.md**

Add a bullet under the `## Execution gates` section (or near `StagnationMonitor`) summarising the new long-horizon handling:
```markdown
- **Turtle/Donchian live execution (v8, Plan 2)** — the `donchian`/`turtle-s1`/`turtle-s2` breakout strategies trade live single-unit. `StrategyExitPolicy` (config `execution.long-horizon-strategies`) marks them long-horizon: exempt from the alignment floor, excluded from `StagnationMonitor` + `TrailMirror`, mutually exclusive per symbol+direction (`MutualExclusionGuard`), and closed by `DonchianExitMonitor` on a live reverse-Donchian breach (10d for donchian/s1, 20d for s2) via `ExitReason.DONCHIAN_EXIT`. The Bybit 2N stop is the catastrophic backstop. Pyramiding is Plan 3 (not yet built).
```

- [ ] **Step 5: Commit**

```bash
git add services/trade-execution-service/src/main/resources/application.properties db/init/signal-init.sql CLAUDE.md
git commit -m "feat(execution): wire Turtle/Donchian live config + v8 marker"
```

---

### Task 12: Live deploy + smoke (controller-run)

**Files:** none (operational).

This task brings the stack up and applies the two live DB migrations (the `db/init` edits only affect fresh databases). Run by the controller, not a subagent, because it touches the live trading stack.

- [ ] **Step 1: Apply the live DB migrations** (the running DB predates the init-file edits)

```bash
docker exec projectr-x-timescaledb-1 psql -U cryptoradar -d marketdata -c "ALTER TABLE executed_trades DROP CONSTRAINT IF EXISTS executed_trades_exit_reason_ck; ALTER TABLE executed_trades ADD CONSTRAINT executed_trades_exit_reason_ck CHECK (exit_reason IS NULL OR exit_reason IN ('TARGET','INITIAL_STOP','TRAIL_STOP','EXPIRED','FLIP_CLOSE','MANUAL','KILL','STAGNATION','DONCHIAN_EXIT'));"
docker exec projectr-x-timescaledb-1 psql -U cryptoradar -d marketdata -c "INSERT INTO deployment_markers (deployed_at, version, description) VALUES ('2026-06-11 00:00:00+00','v8-turtle-donchian-live','...') ON CONFLICT (deployed_at) DO NOTHING;"
```
(If `execution_events.event_type` has a CHECK constraint, apply the matching `ALTER` for `SIGNAL_BLOCKED_MUTUAL_EXCLUSION` too.)

- [ ] **Step 2: Build + restart the execution service**

```bash
docker compose build trade-execution-service && docker compose up -d --no-deps trade-execution-service
```

- [ ] **Step 3: Smoke (no fabricated data)**

- Confirm clean startup: `docker compose logs trade-execution-service --since=2m | Select-String "started in|DonchianExitMonitor|ERROR"`.
- Confirm the monitors are scheduled and the service is healthy.
- A real `donchian`/`turtle-s1`/`turtle-s2` order only appears when a real daily breakout fires upstream — do NOT insert a synthetic signal. Verify the path is wired by watching `execution_events` for `SIGNAL_BLOCKED_*` / `ORDER_PLACED` rows tagged with a breakout `strategy` as they occur naturally:
```bash
docker exec projectr-x-timescaledb-1 psql -U cryptoradar -d marketdata -c "SELECT event_type, count(*) FROM execution_events WHERE created_at > now() - interval '1 day' GROUP BY event_type;"
```
- ⚠️ This is the first point real capital can be deployed by these strategies. Confirm the account's kill-switch + `max_daily_loss_percent` + `risk_percent` are at intended values before leaving it running.

---

## Verification checklist (whole plan)

1. `cd services/trade-execution-service && mvnd -q test` → all pass (111 pre-existing + ≈13 new), zero failures.
2. `cd shared-trade-core && mvnd -q test` → still green (unchanged).
3. Long-horizon trades are skipped by `StagnationMonitor` + `TrailMirror` (guard is first statement in each loop).
4. Breakout signals are NOT blocked by the alignment floor; ARE still subject to symbol-perf, guardrails, daily-halt.
5. A second breakout placement on a symbol+direction already held by a breakout trade is blocked with `SIGNAL_BLOCKED_MUTUAL_EXCLUSION`.
6. `DonchianExitMonitor` closes an open breakout trade when live price breaches the reverse channel; fail-open when candles/price unavailable.
7. No fabricated data: smoke waits for real breakouts; no synthetic signals or rows inserted.

## Out of scope (Plan 3 — pyramiding)

- `DonchianMath.impliedN(entry, stop, stopMultiple)` and N recovery from the trade.
- `executed_trade_units` child table + entity/repository.
- `PyramidingEngine` (add a unit at +0.5N, ratchet all-unit stops, heat/unit caps).
- Sizing config: `turtle.unit-risk-percent`, `turtle.max-units-per-market`, `turtle.max-heat-percent`, `turtle.global-max-units`, `turtle.max-units-per-direction`.
- A `PyramidUnitPlacer` (adds to an existing Bybit position rather than opening a new parent trade) + parent-qty aggregation.

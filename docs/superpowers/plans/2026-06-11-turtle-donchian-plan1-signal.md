# Turtle + Donchian — Plan 1: Shared Math + Signal-Service Entry

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add daily Donchian/Turtle breakout *entry detection* that fires `donchian`, `turtle-s1`, and `turtle-s2` setups into `signal_outcomes` for paper measurement — no live orders yet.

**Architecture:** Pure breakout/volatility math lands in `shared-trade-core` (`DonchianMath`, tested in isolation). A new `DonchianChannelService` in signal-service fetches 60×1d candles per symbol, computes a `DonchianSnapshot` (six channel levels + N + the System-1 loser-flag), and the existing `TradeSetupEngine` injects it into `MarketContext`. Three new pure detectors read the snapshot and emit `TradeSetup`s. Detectors are auto-registered via CDI — no engine wiring beyond the context field.

**Tech Stack:** Java 21, Quarkus 3.17, CDI, Panache, JUnit 5, `shared-trade-core` pure JAR (installed to local `.m2`).

**Scope note:** This is Plan 1 of 2. Plan 2 (trade-execution-service: mutual-exclusion guard, per-strategy exit policy, `DonchianExitMonitor`, `PyramidingEngine`, live flags + deployment marker) depends on the types defined here and is written separately, after this plan is built.

**Spec:** `docs/superpowers/specs/2026-06-11-turtle-donchian-strategy-design.md` (§4.1, §4.2; §4.3 caps are Plan 2).

---

## File Structure

**shared-trade-core** (`shared-trade-core/src/main/java/com/cryptoradar/core/`):
- Create `DonchianMath.java` — pure channel/N/stop/add-trigger/breakout math.
- Test `shared-trade-core/src/test/java/com/cryptoradar/core/DonchianMathTest.java`.

**signal-service** (`services/signal-service/src/main/java/com/cryptoradar/signal/`):
- Create `model/DonchianSnapshot.java` — immutable per-symbol channel record.
- Create `service/DonchianChannelService.java` — fetch 1d candles, build + cache snapshots.
- Create `detector/DonchianBreakoutDetector.java` — `donchian` (20/10, no filter).
- Create `detector/TurtleSystem1Detector.java` — `turtle-s1` (20/10 + loser filter).
- Create `detector/TurtleSystem2Detector.java` — `turtle-s2` (55/20).
- Modify `model/MarketContext.java` — add `DonchianSnapshot donchian` component + `empty()`.
- Modify `service/TradeSetupEngine.java` — inject `DonchianChannelService`, populate the new field.
- Modify `repository/SignalOutcomeRepository.java` — add `findLastClosedByStrategy`.
- Modify `src/test/java/com/cryptoradar/signal/detector/LiquiditySweepDetectorTest.java` — fix the `MarketContext` helper for the new component.
- Create tests `detector/DonchianBreakoutDetectorTest.java`, `detector/TurtleSystem1DetectorTest.java`, `detector/TurtleSystem2DetectorTest.java`, `service/DonchianChannelServiceTest.java`.

---

## Task 1: `DonchianMath` — channels + breakout direction

**Files:**
- Create: `shared-trade-core/src/main/java/com/cryptoradar/core/DonchianMath.java`
- Test: `shared-trade-core/src/test/java/com/cryptoradar/core/DonchianMathTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.cryptoradar.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DonchianMathTest {

    // highs/lows oldest-first; index 5 (the 6th bar) is "today" and is excluded
    private static final double[] HIGHS = {10, 11, 12, 11, 13, 99};
    private static final double[] LOWS  = { 9,  8,  7,  9,  6,  1};

    @Test
    void channelHigh_excludesCurrentBar_takesPriorLookback() {
        // endExclusive=5 excludes the 99 bar; max of first five highs = 13
        assertEquals(13.0, DonchianMath.channelHigh(HIGHS, 5, 5));
    }

    @Test
    void channelLow_excludesCurrentBar_takesPriorLookback() {
        // endExclusive=5 excludes the 1 bar; min of first five lows = 6
        assertEquals(6.0, DonchianMath.channelLow(LOWS, 5, 5));
    }

    @Test
    void channelHigh_shorterLookback_usesOnlyMostRecentCompletedBars() {
        // last 2 completed highs before index 5 are {11,13} -> 13
        assertEquals(13.0, DonchianMath.channelHigh(HIGHS, 5, 2));
    }

    @Test
    void breakoutDirection_longWhenAboveHigh() {
        assertEquals(DonchianMath.Breakout.LONG,
                DonchianMath.breakoutDirection(13.5, 13.0, 6.0));
    }

    @Test
    void breakoutDirection_shortWhenBelowLow() {
        assertEquals(DonchianMath.Breakout.SHORT,
                DonchianMath.breakoutDirection(5.5, 13.0, 6.0));
    }

    @Test
    void breakoutDirection_noneWhenInsideChannel() {
        assertEquals(DonchianMath.Breakout.NONE,
                DonchianMath.breakoutDirection(10.0, 13.0, 6.0));
    }

    @Test
    void channelHigh_throwsWhenNotEnoughHistory() {
        assertThrows(IllegalArgumentException.class,
                () -> DonchianMath.channelHigh(HIGHS, 3, 5));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd shared-trade-core && mvn -q test -Dtest=DonchianMathTest`
Expected: FAIL — `DonchianMath` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.cryptoradar.core;

/**
 * Pure Donchian-channel + Turtle volatility math. No I/O, no state — mirrors
 * {@link RUnitMath}. All array methods take an oldest-first series and an
 * {@code endExclusive} index so callers can exclude the current forming bar
 * (a breakout is "price exceeds the PRIOR n completed bars", not including today).
 */
public final class DonchianMath {

    private DonchianMath() {}

    /** Breakout classification of a live price against a channel. */
    public enum Breakout { LONG, SHORT, NONE }

    /** Highest high over {@code [endExclusive-lookback, endExclusive)}. */
    public static double channelHigh(double[] highs, int endExclusive, int lookback) {
        requireWindow(highs.length, endExclusive, lookback, "channelHigh");
        double max = Double.NEGATIVE_INFINITY;
        for (int i = endExclusive - lookback; i < endExclusive; i++) {
            if (highs[i] > max) max = highs[i];
        }
        return max;
    }

    /** Lowest low over {@code [endExclusive-lookback, endExclusive)}. */
    public static double channelLow(double[] lows, int endExclusive, int lookback) {
        requireWindow(lows.length, endExclusive, lookback, "channelLow");
        double min = Double.POSITIVE_INFINITY;
        for (int i = endExclusive - lookback; i < endExclusive; i++) {
            if (lows[i] < min) min = lows[i];
        }
        return min;
    }

    /** LONG if price breaks above the high channel, SHORT below the low, else NONE. */
    public static Breakout breakoutDirection(double price, double channelHigh, double channelLow) {
        if (price > channelHigh) return Breakout.LONG;
        if (price < channelLow) return Breakout.SHORT;
        return Breakout.NONE;
    }

    private static void requireWindow(int length, int endExclusive, int lookback, String who) {
        if (lookback <= 0 || endExclusive > length || endExclusive - lookback < 0) {
            throw new IllegalArgumentException(who + ": need " + lookback
                    + " bars before index " + endExclusive + " in a series of " + length
                    + " — not enough history");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd shared-trade-core && mvn -q test -Dtest=DonchianMathTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add shared-trade-core/src/main/java/com/cryptoradar/core/DonchianMath.java \
        shared-trade-core/src/test/java/com/cryptoradar/core/DonchianMathTest.java
git commit -m "feat(core): add DonchianMath channels + breakout direction"
```

---

## Task 2: `DonchianMath.computeN` — Wilder-smoothed 20-day ATR

**Files:**
- Modify: `shared-trade-core/src/main/java/com/cryptoradar/core/DonchianMath.java`
- Test: `shared-trade-core/src/test/java/com/cryptoradar/core/DonchianMathTest.java`

- [ ] **Step 1: Write the failing test**

Add to `DonchianMathTest`:

```java
@Test
void computeN_constantOnePointRange_equalsOne() {
    // Every bar has high-low = 1 and no gaps, so TR is 1 throughout -> N = 1.
    int n = 25;
    double[] highs = new double[n];
    double[] lows = new double[n];
    double[] closes = new double[n];
    for (int i = 0; i < n; i++) {
        highs[i] = 100.5;
        lows[i] = 99.5;
        closes[i] = 100.0;
    }
    assertEquals(1.0, DonchianMath.computeN(highs, lows, closes, 20), 1e-9);
}

@Test
void computeN_throwsWhenSeriesShorterThanPeriodPlusOne() {
    double[] a = {1, 2, 3};
    assertThrows(IllegalArgumentException.class,
            () -> DonchianMath.computeN(a, a, a, 20));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd shared-trade-core && mvn -q test -Dtest=DonchianMathTest`
Expected: FAIL — `computeN` not defined.

- [ ] **Step 3: Add the implementation**

Insert into `DonchianMath` (before `requireWindow`):

```java
/**
 * N = Wilder-smoothed ATR over {@code period} days, the original Turtle
 * volatility unit. True range needs the prior close, so the series must be
 * at least {@code period + 1} long. Seeds with the simple average of the
 * first {@code period} true ranges, then applies Wilder smoothing
 * {@code N = ((period-1)·prevN + TR) / period} for the remainder.
 */
public static double computeN(double[] highs, double[] lows, double[] closes, int period) {
    int length = highs.length;
    if (period <= 0 || length < period + 1) {
        throw new IllegalArgumentException("computeN: need at least " + (period + 1)
                + " bars for period " + period + ", got " + length);
    }
    double seedSum = 0.0;
    for (int i = 1; i <= period; i++) {
        seedSum += trueRange(highs[i], lows[i], closes[i - 1]);
    }
    double n = seedSum / period;
    for (int i = period + 1; i < length; i++) {
        double tr = trueRange(highs[i], lows[i], closes[i - 1]);
        n = ((period - 1) * n + tr) / period;
    }
    return n;
}

private static double trueRange(double high, double low, double prevClose) {
    double a = high - low;
    double b = Math.abs(high - prevClose);
    double c = Math.abs(low - prevClose);
    return Math.max(a, Math.max(b, c));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd shared-trade-core && mvn -q test -Dtest=DonchianMathTest`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add shared-trade-core/src/main/java/com/cryptoradar/core/DonchianMath.java \
        shared-trade-core/src/test/java/com/cryptoradar/core/DonchianMathTest.java
git commit -m "feat(core): add Wilder-smoothed N (20-day ATR) to DonchianMath"
```

---

## Task 3: `DonchianMath` — 2N stop + 0.5N add-trigger

**Files:**
- Modify: `shared-trade-core/src/main/java/com/cryptoradar/core/DonchianMath.java`
- Test: `shared-trade-core/src/test/java/com/cryptoradar/core/DonchianMathTest.java`

- [ ] **Step 1: Write the failing test**

Add to `DonchianMathTest`:

```java
@Test
void unitStop_longSubtractsTwoN_shortAddsTwoN() {
    assertEquals(96.0, DonchianMath.unitStop(100.0, 2.0, true, 2.0), 1e-9);
    assertEquals(104.0, DonchianMath.unitStop(100.0, 2.0, false, 2.0), 1e-9);
}

@Test
void addTrigger_longAddsHalfN_shortSubtractsHalfN() {
    assertEquals(101.0, DonchianMath.addTrigger(100.0, 2.0, true, 0.5), 1e-9);
    assertEquals(99.0, DonchianMath.addTrigger(100.0, 2.0, false, 0.5), 1e-9);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd shared-trade-core && mvn -q test -Dtest=DonchianMathTest`
Expected: FAIL — `unitStop` / `addTrigger` not defined.

- [ ] **Step 3: Add the implementation**

Insert into `DonchianMath` (after `breakoutDirection`):

```java
/** Protective stop: {@code entry - mult·N} for LONG, {@code entry + mult·N} for SHORT. */
public static double unitStop(double entry, double n, boolean isLong, double stopMultiple) {
    return isLong ? entry - stopMultiple * n : entry + stopMultiple * n;
}

/** Next pyramid level: {@code lastEntry + frac·N} for LONG, {@code lastEntry - frac·N} for SHORT. */
public static double addTrigger(double lastUnitEntry, double n, boolean isLong, double stepFraction) {
    return isLong ? lastUnitEntry + stepFraction * n : lastUnitEntry - stepFraction * n;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd shared-trade-core && mvn -q test -Dtest=DonchianMathTest`
Expected: PASS (11 tests).

- [ ] **Step 5: Install shared-trade-core to local `.m2` (signal-service depends on it) and commit**

```bash
cd shared-trade-core && mvn -q install -DskipTests=false
cd .. && git add shared-trade-core/src/main/java/com/cryptoradar/core/DonchianMath.java \
        shared-trade-core/src/test/java/com/cryptoradar/core/DonchianMathTest.java
git commit -m "feat(core): add 2N stop + 0.5N add-trigger to DonchianMath"
```

Expected: `BUILD SUCCESS`; `com.cryptoradar:shared-trade-core` installed so the signal-service build resolves `DonchianMath`.

---

## Task 4: `DonchianSnapshot` record

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/model/DonchianSnapshot.java`

No dedicated test (a pure immutable carrier; exercised by Task 6's service test and the detector tests).

- [ ] **Step 1: Create the record**

```java
package com.cryptoradar.signal.model;

import java.time.Instant;

/**
 * Per-symbol daily Donchian channel snapshot consumed by the breakout
 * detectors. Carries all six channel levels needed across the three
 * strategies plus the Turtle volatility unit N and the System-1 loser-filter
 * flag. Computed by {@code DonchianChannelService}; injected into
 * {@link MarketContext} so detectors stay pure.
 *
 * @param high20 highest high of the prior 20 completed daily bars
 * @param low20  lowest low of the prior 20 completed daily bars
 * @param high10 highest high of the prior 10 completed daily bars (S1/donchian short exit)
 * @param low10  lowest low of the prior 10 completed daily bars (S1/donchian long exit)
 * @param high55 highest high of the prior 55 completed daily bars (S2 long entry)
 * @param low55  lowest low of the prior 55 completed daily bars (S2 short entry)
 * @param n      Wilder-smoothed 20-day ATR (Turtle N)
 * @param lastS1BreakoutWasWinner true when the last closed {@code turtle-s1}
 *               outcome for this symbol had realized R &gt; 0; the System-1
 *               entry filter skips a new breakout when this is true
 * @param computedAt when this snapshot was built
 */
public record DonchianSnapshot(
        double high20, double low20,
        double high10, double low10,
        double high55, double low55,
        double n,
        boolean lastS1BreakoutWasWinner,
        Instant computedAt) {
}
```

- [ ] **Step 2: Compile-check**

Run: `cd services/signal-service && mvnd -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/model/DonchianSnapshot.java
git commit -m "feat(signal): add DonchianSnapshot model"
```

---

## Task 5: `SignalOutcomeRepository.findLastClosedByStrategy`

**Files:**
- Modify: `services/signal-service/src/main/java/com/cryptoradar/signal/repository/SignalOutcomeRepository.java`

Exercised via the service test in Task 6 (repo mocked); no DB integration test added — consistent with the existing repo, which ships no unit tests (tracked tech debt).

- [ ] **Step 1: Add the query method**

Insert after `findOpenByStrategy` (around line 43):

```java
    /**
     * The most recently closed outcome for a symbol+strategy, newest first.
     * Backs the Turtle System-1 loser-filter: the new 20-day breakout is
     * skipped when the last closed {@code turtle-s1} trade was a winner.
     * Empty when no closed outcome exists yet (so the first breakout is taken).
     */
    public Optional<SignalOutcome> findLastClosedByStrategy(String symbol, String strategy) {
        return find("symbol = ?1 and strategy = ?2 and status <> ?3 order by closedAt desc",
                symbol, strategy, OutcomeStatus.PENDING)
                .firstResultOptional();
    }
```

- [ ] **Step 2: Compile-check**

Run: `cd services/signal-service && mvnd -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/repository/SignalOutcomeRepository.java
git commit -m "feat(signal): add findLastClosedByStrategy for S1 loser-filter"
```

---

## Task 6: `DonchianChannelService` — build + cache snapshots

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/service/DonchianChannelService.java`
- Test: `services/signal-service/src/test/java/com/cryptoradar/signal/service/DonchianChannelServiceTest.java`

The pure `buildSnapshot(bars, lastWinner)` is the unit under test; `snapshotFor` adds the I/O + cache around it.

- [ ] **Step 1: Write the failing test**

```java
package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.DonchianSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DonchianChannelServiceTest {

    private final DonchianChannelService service = new DonchianChannelService(null, null);

    /** 60 oldest-first daily bars; the LAST bar is "today" and must be excluded. */
    private List<CandleBar> bars(double todayHigh, double todayLow) {
        List<CandleBar> bars = new ArrayList<>();
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 59; i++) {
            // completed history: highs 100..158, lows 90..148
            double high = 100 + i;
            double low = 90 + i;
            bars.add(new CandleBar(t.plusSeconds(i * 86400L), low + 1, high, low, high - 1));
        }
        // today's forming bar — extreme values that must NOT enter the channel
        bars.add(new CandleBar(t.plusSeconds(59 * 86400L), 1, todayHigh, todayLow, 1));
        return bars;
    }

    @Test
    void buildSnapshot_excludesTodayBar_fromChannels() {
        DonchianSnapshot snap = service.buildSnapshot(bars(9999, -1), true);
        // last 20 completed highs are 139..158 -> high20 = 158
        assertEquals(158.0, snap.high20());
        // last 55 completed highs end at 158 -> high55 = 158
        assertEquals(158.0, snap.high55());
        // last 10 completed lows are 139..148 -> low10 = 139
        assertEquals(139.0, snap.low10());
        assertTrue(snap.n() > 0);
        assertTrue(snap.lastS1BreakoutWasWinner());
    }

    @Test
    void buildSnapshot_throwsWhenInsufficientHistory() {
        List<CandleBar> tooFew = bars(200, 80).subList(0, 40); // < 56 bars
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSnapshot(tooFew, false));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/signal-service && mvnd -q test -Dtest=DonchianChannelServiceTest`
Expected: FAIL — `DonchianChannelService` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.cryptoradar.signal.service;

import com.cryptoradar.core.DonchianMath;
import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.repository.SignalOutcomeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches per-symbol daily Donchian snapshots. Mirrors
 * {@code MarketRegimeService}'s 1d-candle approach but is lazy + per-symbol:
 * {@link #snapshotFor(String)} recomputes when the cached value is older than
 * {@link #TTL_MILLIS}. Fail-safe — returns {@link Optional#empty()} when
 * history is short or the fetch fails, so detectors simply no-op for that
 * symbol rather than throwing.
 */
@ApplicationScoped
public class DonchianChannelService {

    private static final Logger LOG = Logger.getLogger(DonchianChannelService.class);

    private static final String INTERVAL_1D = "1d";
    private static final int FETCH_LIMIT = 60;
    private static final int N_PERIOD = 20;
    private static final int LOOKBACK_55 = 55;
    private static final int LOOKBACK_20 = 20;
    private static final int LOOKBACK_10 = 10;
    /** 56 bars = 55 completed + 1 excluded "today" bar. */
    private static final int MIN_BARS = LOOKBACK_55 + 1;
    private static final long TTL_MILLIS = 3_600_000L; // 1h — daily candles change once a day
    private static final String S1_STRATEGY = "turtle-s1";

    private final CandleClient candleClient;
    private final SignalOutcomeRepository outcomeRepo;
    private final ConcurrentHashMap<String, DonchianSnapshot> cache = new ConcurrentHashMap<>();

    public DonchianChannelService(CandleClient candleClient, SignalOutcomeRepository outcomeRepo) {
        this.candleClient = candleClient;
        this.outcomeRepo = outcomeRepo;
    }

    /** Cached snapshot for the symbol, recomputed when stale. Empty on failure. */
    public Optional<DonchianSnapshot> snapshotFor(String symbol) {
        DonchianSnapshot cached = cache.get(symbol);
        if (cached != null && !isStale(cached)) {
            return Optional.of(cached);
        }
        try {
            List<CandleBar> bars = candleClient.fetchRecent(symbol, INTERVAL_1D, FETCH_LIMIT);
            if (bars == null || bars.size() < MIN_BARS) {
                LOG.warnf("Donchian: insufficient 1d history for %s (got %d, need %d)",
                        symbol, bars == null ? 0 : bars.size(), MIN_BARS);
                return Optional.empty();
            }
            DonchianSnapshot snap = buildSnapshot(bars, lastS1Winner(symbol));
            cache.put(symbol, snap);
            return Optional.of(snap);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Donchian: snapshot build failed for %s — skipping", symbol);
            return Optional.empty();
        }
    }

    private boolean isStale(DonchianSnapshot snap) {
        return Instant.now().toEpochMilli() - snap.computedAt().toEpochMilli() > TTL_MILLIS;
    }

    private boolean lastS1Winner(String symbol) {
        return outcomeRepo.findLastClosedByStrategy(symbol, S1_STRATEGY)
                .map(SignalOutcome::getRealizedRMultiple)
                .map(r -> r != null && r > 0)
                .orElse(false);
    }

    /**
     * Pure snapshot builder. {@code bars} is oldest-first; the LAST bar is the
     * current/forming day and is excluded from all channels (a breakout is
     * "price exceeds the prior n COMPLETED bars"). Package-private for testing.
     */
    DonchianSnapshot buildSnapshot(List<CandleBar> bars, boolean lastS1Winner) {
        int size = bars.size();
        if (size < MIN_BARS) {
            throw new IllegalArgumentException("Donchian needs >= " + MIN_BARS
                    + " daily bars, got " + size);
        }
        double[] highs = new double[size];
        double[] lows = new double[size];
        double[] closes = new double[size];
        for (int i = 0; i < size; i++) {
            highs[i] = bars.get(i).high();
            lows[i] = bars.get(i).low();
            closes[i] = bars.get(i).close();
        }
        int endExclusive = size - 1; // exclude today's forming bar
        return new DonchianSnapshot(
                DonchianMath.channelHigh(highs, endExclusive, LOOKBACK_20),
                DonchianMath.channelLow(lows, endExclusive, LOOKBACK_20),
                DonchianMath.channelHigh(highs, endExclusive, LOOKBACK_10),
                DonchianMath.channelLow(lows, endExclusive, LOOKBACK_10),
                DonchianMath.channelHigh(highs, endExclusive, LOOKBACK_55),
                DonchianMath.channelLow(lows, endExclusive, LOOKBACK_55),
                DonchianMath.computeN(highs, lows, closes, N_PERIOD),
                lastS1Winner,
                Instant.now());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/signal-service && mvnd -q test -Dtest=DonchianChannelServiceTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/service/DonchianChannelService.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/service/DonchianChannelServiceTest.java
git commit -m "feat(signal): add DonchianChannelService (1d snapshot + cache)"
```

---

## Task 7: Wire `DonchianSnapshot` into `MarketContext` + `TradeSetupEngine`

**Files:**
- Modify: `services/signal-service/src/main/java/com/cryptoradar/signal/model/MarketContext.java`
- Modify: `services/signal-service/src/main/java/com/cryptoradar/signal/service/TradeSetupEngine.java`
- Modify: `services/signal-service/src/test/java/com/cryptoradar/signal/detector/LiquiditySweepDetectorTest.java`

This adds a 9th record component. Every `new MarketContext(...)` site must update: `MarketContext.empty()`, `TradeSetupEngine.buildContext`, and the `LiquiditySweepDetectorTest` helper.

- [ ] **Step 1: Add the component to `MarketContext`**

In `MarketContext.java`, add the field to the record header (after `recent4hBars`) and a doc line:

```java
        Map<String, Double> dimensionScores,
        List<CandleBar> recent4hBars,
        DonchianSnapshot donchian
) {
```
Add to the Javadoc param block:
```java
 * @param donchian          daily Donchian channel snapshot, or {@code null}
 *                          when unavailable; breakout detectors no-op on null
```
Update `empty(...)` to pass `null` for the new field:
```java
    public static MarketContext empty(String symbol) {
        return new MarketContext(symbol, null,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), null);
    }
```

- [ ] **Step 2: Inject the service and populate the field in `TradeSetupEngine`**

In `TradeSetupEngine.java`:

Add the field + constructor param:
```java
    private final DataAggregator dataAggregator;
    private final DonchianChannelService donchianService;
    private final Instance<TradeSetupDetector> detectors;

    public TradeSetupEngine(
            DataAggregator dataAggregator,
            DonchianChannelService donchianService,
            @Any Instance<TradeSetupDetector> detectors) {
        this.dataAggregator = dataAggregator;
        this.donchianService = donchianService;
        this.detectors = detectors;
    }
```

In `buildContext`, fetch the snapshot and pass it as the new last arg:
```java
    private MarketContext buildContext(String symbol, SymbolRawData data, List<DimensionScore> dimensions) {
        Double currentPrice = ContextValues.readDouble(data.priceData(), PRICE_FIELD);
        Map<String, Double> dimensionScores = toDimensionScores(dimensions);
        List<CandleBar> bars = loadRecentBars(symbol);
        DonchianSnapshot donchian = donchianService.snapshotFor(symbol).orElse(null);

        return new MarketContext(
                symbol,
                currentPrice,
                nullSafe(data.analytics()),
                nullSafe(data.whaleData()),
                nullSafe(data.derivativesData()),
                nullSafe(data.macroData()),
                dimensionScores,
                bars,
                donchian);
    }
```
Add the import: `import com.cryptoradar.signal.model.DonchianSnapshot;`

- [ ] **Step 3: Fix the existing test helper**

In `LiquiditySweepDetectorTest.java` (~line 243), add the trailing `null` arg to the `new MarketContext(...)` call:
```java
        return new MarketContext(
                "TESTUSDT", currentPrice, analytics, Map.of(), Map.of(), Map.of(),
                dimensionScores, bars, null);
```

- [ ] **Step 4: Run the existing detector + engine tests to verify nothing broke**

Run: `cd services/signal-service && mvnd -q test -Dtest=LiquiditySweepDetectorTest,DonchianChannelServiceTest`
Expected: PASS (no regression from the new component).

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/model/MarketContext.java \
        services/signal-service/src/main/java/com/cryptoradar/signal/service/TradeSetupEngine.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/detector/LiquiditySweepDetectorTest.java
git commit -m "feat(signal): carry DonchianSnapshot through MarketContext"
```

---

## Task 8: `DonchianBreakoutDetector` (strategy `donchian`)

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/detector/DonchianBreakoutDetector.java`
- Test: `services/signal-service/src/test/java/com/cryptoradar/signal/detector/DonchianBreakoutDetectorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.cryptoradar.signal.detector;

import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DonchianBreakoutDetectorTest {

    private final DonchianBreakoutDetector detector = new DonchianBreakoutDetector();

    // @ConfigProperty injection does not run for plain-new beans, so the flag
    // defaults to false. Set it explicitly or every "fires" case no-ops.
    @BeforeEach
    void enableDetector() {
        detector.enabled = true;
    }

    private MarketContext ctx(double price, DonchianSnapshot snap) {
        return new MarketContext("BTCUSDT", price,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), snap);
    }

    private DonchianSnapshot snap() {
        // high20=110, low20=90, N=2
        return new DonchianSnapshot(110, 90, 108, 92, 120, 80, 2.0, false, Instant.now());
    }

    @Test
    void firesLongWhenPriceBreaksHigh20() {
        Optional<TradeSetup> r = detector.detect(ctx(110.5, snap()));
        assertTrue(r.isPresent());
        TradeSetup s = r.get();
        assertEquals("donchian", s.strategy());
        assertEquals("LONG", s.direction());
        assertEquals(110.5 - 2 * 2.0, s.stopPrice(), 1e-9); // entry - 2N
    }

    @Test
    void firesShortWhenPriceBreaksLow20() {
        Optional<TradeSetup> r = detector.detect(ctx(89.5, snap()));
        assertTrue(r.isPresent());
        assertEquals("SHORT", r.get().direction());
        assertEquals(89.5 + 2 * 2.0, r.get().stopPrice(), 1e-9); // entry + 2N
    }

    @Test
    void silentInsideChannel() {
        assertTrue(detector.detect(ctx(100.0, snap())).isEmpty());
    }

    @Test
    void silentWhenNoSnapshot() {
        assertTrue(detector.detect(ctx(100.0, null)).isEmpty());
    }

    @Test
    void silentWhenNoPrice() {
        MarketContext c = new MarketContext("BTCUSDT", null,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), snap());
        assertTrue(detector.detect(c).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/signal-service && mvnd -q test -Dtest=DonchianBreakoutDetectorTest`
Expected: FAIL — `DonchianBreakoutDetector` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.cryptoradar.signal.detector;

import com.cryptoradar.core.DonchianMath;
import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Textbook Donchian channel breakout: enter on a 20-day high/low break, exit
 * (managed downstream) on the reverse 10-day channel. No entry filter, single
 * unit, no pyramiding — the simplest of the three breakout strategies and the
 * comparison baseline against the full Turtle variants.
 */
@ApplicationScoped
public class DonchianBreakoutDetector implements TradeSetupDetector {

    static final String NAME = "donchian";
    private static final double STOP_MULTIPLE = 2.0;   // 2N catastrophic stop
    private static final double TARGET_N_MULTIPLE = 20.0; // nominal backstop only
    private static final int MECHANICAL_ALIGNMENT = 60;

    @ConfigProperty(name = "turtle.donchian.enabled", defaultValue = "true")
    boolean enabled;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<TradeSetup> detect(MarketContext context) {
        if (!enabled) return Optional.empty();
        DonchianSnapshot snap = context.donchian();
        Double price = context.currentPrice();
        if (snap == null || price == null) return Optional.empty();

        DonchianMath.Breakout dir = DonchianMath.breakoutDirection(price, snap.high20(), snap.low20());
        if (dir == DonchianMath.Breakout.NONE) return Optional.empty();

        return Optional.of(BreakoutSetups.build(NAME, context.symbol(), price, snap.n(),
                dir == DonchianMath.Breakout.LONG, STOP_MULTIPLE, TARGET_N_MULTIPLE,
                MECHANICAL_ALIGNMENT,
                List.of(String.format("Donchian 20-day %s breakout (high20=%.4f low20=%.4f N=%.4f)",
                        dir, snap.high20(), snap.low20(), snap.n()),
                        "Operative exit = reverse 10-day Donchian monitor; TP is a 20N backstop"),
                Instant.now()));
    }
}
```

This references a shared builder `BreakoutSetups.build(...)` — created next, before the test will compile.

- [ ] **Step 4: Create the shared setup builder**

Create `services/signal-service/src/main/java/com/cryptoradar/signal/detector/BreakoutSetups.java`:

```java
package com.cryptoradar.signal.detector;

import com.cryptoradar.core.DonchianMath;
import com.cryptoradar.signal.model.TradeSetup;

import java.time.Instant;
import java.util.List;

/**
 * Shared construction of the breakout strategies' {@link TradeSetup}. All three
 * detectors size the stop at 2N, carry a distant {@code targetNMultiple}·N
 * nominal TP (the operative exit is the downstream Donchian monitor), and use a
 * fixed mechanical alignment (these are rule-based, not confluence-scored).
 */
final class BreakoutSetups {

    private BreakoutSetups() {}

    static TradeSetup build(String strategy, String symbol, double entry, double n,
                            boolean isLong, double stopMultiple, double targetNMultiple,
                            int alignment, List<String> reasons, Instant firedAt) {
        double stop = DonchianMath.unitStop(entry, n, isLong, stopMultiple);
        double target = isLong ? entry + targetNMultiple * n : entry - targetNMultiple * n;
        double rr = stopMultiple == 0 ? 0 : targetNMultiple / stopMultiple;
        String direction = isLong ? "LONG" : "SHORT";
        String signalType = isLong ? "BUY" : "SELL";
        return new TradeSetup(strategy, symbol, direction, signalType,
                entry, stop, target, rr, alignment, reasons, firedAt);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd services/signal-service && mvnd -q test -Dtest=DonchianBreakoutDetectorTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/detector/DonchianBreakoutDetector.java \
        services/signal-service/src/main/java/com/cryptoradar/signal/detector/BreakoutSetups.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/detector/DonchianBreakoutDetectorTest.java
git commit -m "feat(signal): add DonchianBreakoutDetector (donchian strategy)"
```

---

## Task 9: `TurtleSystem1Detector` (strategy `turtle-s1`, loser-filter)

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/detector/TurtleSystem1Detector.java`
- Test: `services/signal-service/src/test/java/com/cryptoradar/signal/detector/TurtleSystem1DetectorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.cryptoradar.signal.detector;

import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TurtleSystem1DetectorTest {

    private final TurtleSystem1Detector detector = new TurtleSystem1Detector();

    @BeforeEach
    void enableDetector() {
        detector.enabled = true;
    }

    private MarketContext ctx(double price, DonchianSnapshot snap) {
        return new MarketContext("ETHUSDT", price,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), snap);
    }

    private DonchianSnapshot snap(boolean lastWinner) {
        return new DonchianSnapshot(110, 90, 108, 92, 120, 80, 2.0, lastWinner, Instant.now());
    }

    @Test
    void firesLongOnHigh20Break_whenLastBreakoutWasLoser() {
        Optional<TradeSetup> r = detector.detect(ctx(110.5, snap(false)));
        assertTrue(r.isPresent());
        assertEquals("turtle-s1", r.get().strategy());
        assertEquals("LONG", r.get().direction());
    }

    @Test
    void skipsBreakout_whenLastBreakoutWasWinner() {
        // loser-filter: a winning prior S1 breakout suppresses the next entry
        assertTrue(detector.detect(ctx(110.5, snap(true))).isEmpty());
    }

    @Test
    void silentInsideChannel() {
        assertTrue(detector.detect(ctx(100.0, snap(false))).isEmpty());
    }

    @Test
    void silentWhenNoSnapshot() {
        assertTrue(detector.detect(ctx(110.5, null)).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/signal-service && mvnd -q test -Dtest=TurtleSystem1DetectorTest`
Expected: FAIL — `TurtleSystem1Detector` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.cryptoradar.signal.detector;

import com.cryptoradar.core.DonchianMath;
import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turtle System 1: 20-day breakout entry with the original loser-filter — a
 * new breakout is skipped when the last closed {@code turtle-s1} trade for the
 * symbol was a winner (forces participation in the breakouts after a failed
 * one). Reverse exit is the 10-day channel (managed downstream); stop is 2N;
 * pyramiding-eligible on the execution side (Plan 2).
 */
@ApplicationScoped
public class TurtleSystem1Detector implements TradeSetupDetector {

    static final String NAME = "turtle-s1";
    private static final double STOP_MULTIPLE = 2.0;
    private static final double TARGET_N_MULTIPLE = 20.0;
    private static final int MECHANICAL_ALIGNMENT = 60;

    @ConfigProperty(name = "turtle.s1.enabled", defaultValue = "true")
    boolean enabled;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<TradeSetup> detect(MarketContext context) {
        if (!enabled) return Optional.empty();
        DonchianSnapshot snap = context.donchian();
        Double price = context.currentPrice();
        if (snap == null || price == null) return Optional.empty();

        DonchianMath.Breakout dir = DonchianMath.breakoutDirection(price, snap.high20(), snap.low20());
        if (dir == DonchianMath.Breakout.NONE) return Optional.empty();
        // Loser-filter: suppress the entry when the prior S1 breakout won.
        if (snap.lastS1BreakoutWasWinner()) return Optional.empty();

        return Optional.of(BreakoutSetups.build(NAME, context.symbol(), price, snap.n(),
                dir == DonchianMath.Breakout.LONG, STOP_MULTIPLE, TARGET_N_MULTIPLE,
                MECHANICAL_ALIGNMENT,
                List.of(String.format("Turtle S1 20-day %s breakout (high20=%.4f low20=%.4f N=%.4f)",
                        dir, snap.high20(), snap.low20(), snap.n()),
                        "Loser-filter passed (last S1 breakout was not a winner)",
                        "Operative exit = reverse 10-day Donchian monitor; TP is a 20N backstop"),
                Instant.now()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/signal-service && mvnd -q test -Dtest=TurtleSystem1DetectorTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/detector/TurtleSystem1Detector.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/detector/TurtleSystem1DetectorTest.java
git commit -m "feat(signal): add TurtleSystem1Detector with loser-filter"
```

---

## Task 10: `TurtleSystem2Detector` (strategy `turtle-s2`, 55-day)

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/detector/TurtleSystem2Detector.java`
- Test: `services/signal-service/src/test/java/com/cryptoradar/signal/detector/TurtleSystem2DetectorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.cryptoradar.signal.detector;

import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TurtleSystem2DetectorTest {

    private final TurtleSystem2Detector detector = new TurtleSystem2Detector();

    @BeforeEach
    void enableDetector() {
        detector.enabled = true;
    }

    private MarketContext ctx(double price, DonchianSnapshot snap) {
        return new MarketContext("SOLUSDT", price,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Collections.emptyList(), snap);
    }

    // high55=120, low55=80; the 20-day channel (110/90) must NOT trigger S2
    private DonchianSnapshot snap() {
        return new DonchianSnapshot(110, 90, 108, 92, 120, 80, 2.0, true, Instant.now());
    }

    @Test
    void firesLongOnHigh55Break() {
        Optional<TradeSetup> r = detector.detect(ctx(120.5, snap()));
        assertTrue(r.isPresent());
        assertEquals("turtle-s2", r.get().strategy());
        assertEquals("LONG", r.get().direction());
    }

    @Test
    void ignoresLoserFilter_firesEvenWhenLastWinnerTrue() {
        // S2 has no loser-filter; snap().lastS1BreakoutWasWinner()==true must not block
        assertTrue(detector.detect(ctx(120.5, snap())).isPresent());
    }

    @Test
    void doesNotFireOn20DayBreakOnly() {
        // price breaks high20 (110) but not high55 (120) -> S2 silent
        assertTrue(detector.detect(ctx(111.0, snap())).isEmpty());
    }

    @Test
    void firesShortOnLow55Break() {
        assertEquals("SHORT", detector.detect(ctx(79.5, snap())).get().direction());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/signal-service && mvnd -q test -Dtest=TurtleSystem2DetectorTest`
Expected: FAIL — `TurtleSystem2Detector` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.cryptoradar.signal.detector;

import com.cryptoradar.core.DonchianMath;
import com.cryptoradar.signal.model.DonchianSnapshot;
import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turtle System 2: the slower 55-day breakout entry with no loser-filter
 * (always taken). Reverse exit is the 20-day channel (managed downstream);
 * stop is 2N; pyramiding-eligible on the execution side (Plan 2).
 */
@ApplicationScoped
public class TurtleSystem2Detector implements TradeSetupDetector {

    static final String NAME = "turtle-s2";
    private static final double STOP_MULTIPLE = 2.0;
    private static final double TARGET_N_MULTIPLE = 20.0;
    private static final int MECHANICAL_ALIGNMENT = 60;

    @ConfigProperty(name = "turtle.s2.enabled", defaultValue = "true")
    boolean enabled;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<TradeSetup> detect(MarketContext context) {
        if (!enabled) return Optional.empty();
        DonchianSnapshot snap = context.donchian();
        Double price = context.currentPrice();
        if (snap == null || price == null) return Optional.empty();

        DonchianMath.Breakout dir = DonchianMath.breakoutDirection(price, snap.high55(), snap.low55());
        if (dir == DonchianMath.Breakout.NONE) return Optional.empty();

        return Optional.of(BreakoutSetups.build(NAME, context.symbol(), price, snap.n(),
                dir == DonchianMath.Breakout.LONG, STOP_MULTIPLE, TARGET_N_MULTIPLE,
                MECHANICAL_ALIGNMENT,
                List.of(String.format("Turtle S2 55-day %s breakout (high55=%.4f low55=%.4f N=%.4f)",
                        dir, snap.high55(), snap.low55(), snap.n()),
                        "Operative exit = reverse 20-day Donchian monitor; TP is a 20N backstop"),
                Instant.now()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/signal-service && mvnd -q test -Dtest=TurtleSystem2DetectorTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/detector/TurtleSystem2Detector.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/detector/TurtleSystem2DetectorTest.java
git commit -m "feat(signal): add TurtleSystem2Detector (55-day breakout)"
```

---

## Task 11: Full module test run + config defaults + docs

**Files:**
- Modify: `services/signal-service/src/main/resources/application.properties`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add explicit enable-flag defaults (documentation of the knobs)**

Append to `services/signal-service/src/main/resources/application.properties`:

```properties
# Turtle / Donchian breakout detectors (Plan 1). Each detector also defaults
# true via @ConfigProperty; these lines make the knobs discoverable + flippable.
turtle.donchian.enabled=true
turtle.s1.enabled=true
turtle.s2.enabled=true
```

- [ ] **Step 2: Run the FULL signal-service test suite (no regressions)**

Run: `cd services/signal-service && mvnd -q test`
Expected: PASS — the pre-existing 55 tests plus the new detector/service tests (15 new). No failures.

- [ ] **Step 3: Run the shared-core suite**

Run: `cd shared-trade-core && mvn -q test`
Expected: PASS — 38 pre-existing + 11 new `DonchianMath` tests.

- [ ] **Step 4: Note the new strategies in CLAUDE.md**

Under the `detector/` package bullet in `CLAUDE.md`, extend the detector list:

```markdown
- **`detector/` package** — pluggable `TradeSetupDetector` interface. Current: `LiquiditySweepDetector`, `TrendContinuationDetector`, `DonchianBreakoutDetector` (`donchian`, 20/10), `TurtleSystem1Detector` (`turtle-s1`, 20/10 + loser-filter), `TurtleSystem2Detector` (`turtle-s2`, 55/20). The three breakout detectors read a daily `DonchianSnapshot` injected into `MarketContext` by `DonchianChannelService` (60×1d candles, cached 1h); breakout/N math lives in `shared-trade-core` `DonchianMath`. Live execution + pyramiding is Plan 2.
```

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/resources/application.properties CLAUDE.md
git commit -m "feat(signal): wire Turtle/Donchian flags + document strategies"
```

- [ ] **Step 6: Build + smoke the running service (real data, no fabrication)**

```bash
docker compose build signal-service && docker compose up -d --no-deps signal-service
docker compose logs signal-service --since=3m | Select-String -Pattern "Donchian|turtle|ERROR"
```
Expected: service starts clean; `DonchianChannelService` warnings only for symbols with <56 daily bars (none expected — 1d backfilled 2500d). Over the next cycles, real `donchian`/`turtle-s1`/`turtle-s2` rows begin appearing in `signal_outcomes` **only** when a real daily breakout occurs — no synthetic rows. Verify with:
```bash
docker exec projectr-x-timescaledb-1 psql -U cryptoradar -d marketdata \
  -c "SELECT strategy, count(*) FROM signal_outcomes WHERE strategy LIKE 'turtle%' OR strategy='donchian' GROUP BY strategy;"
```
Expected: zero or more real rows; empty result is acceptable (a breakout simply hasn't fired yet — patience over fabrication).

---

## Verification checklist (whole plan)

1. `cd shared-trade-core && mvn -q test` → `DonchianMath` 11 tests pass.
2. `cd services/signal-service && mvnd -q test` → all detector/service tests pass, zero regressions.
3. `docker compose logs signal-service --since=5m | Select-String "ERROR"` → none from the new code.
4. New strategies (`donchian`, `turtle-s1`, `turtle-s2`) appear in `signal_outcomes` **only** on real daily breakouts; `DonchianChannelService` returns empty (detectors no-op) rather than throwing when history is short.
5. No fabricated rows: the smoke step never inserts test data — it waits for a real breakout.

## Out of scope (Plan 2)

- trade-execution-service: `MutualExclusionGuard`, `StrategyExitPolicy` (skip stagnation/trail), `DonchianExitMonitor`, `PyramidingEngine`, `executed_trade_units` table, `ExitReason.DONCHIAN_EXIT`.
- §4.3 risk caps (`turtle.unit-risk-percent`, heat cap, unit caps) — consumed by execution sizing.
- `v8-turtle-donchian` deployment marker (recorded when execution goes live).
- Frontend surfacing of the new strategies.

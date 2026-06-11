# Turtle + Donchian Breakout Strategy — Design Spec

**Date:** 2026-06-11
**Status:** Approved design, pre-implementation
**Author:** brainstorming session (Artjoms + Claude)

## 1. Purpose

Add trend-following breakout strategies to projectr-x, modelled on the 1983
Richard Dennis / William Eckhardt "Turtle" system. Two strategy families ship:

- **Donchian** — the textbook channel-breakout baseline (20-day entry / 10-day
  reverse exit), single unit, no entry filter, no pyramiding.
- **Full Turtle** — System 1 (20/10 with loser-filter) + System 2 (55/20),
  volatility-sized units, pyramiding up to 4 units, portfolio caps.

Both run **live on Bybit** from launch, with conservative real-money caps and
independent kill-switches. Both are measured independently in `signal_outcomes`.

## 2. Decisions locked during brainstorming

| Decision | Choice | Rationale |
|---|---|---|
| Timeframe | **Daily (1d)** | Canonical Turtle; best-evidenced. 1d candles already backfilled 2500 days for all pairs. |
| Rollout | **Live execution from day one** | User choice. Mitigated by conservative caps + kill-switches + existing guardrails. |
| Exit style | **Native Turtle exits** | 2N hard stop on Bybit + reverse-Donchian monitor; intraday exits disabled for these strategies. |
| Pyramiding | **Full, with hard heat cap** | Add 1 unit per 0.5N up to 4 units; total open risk per market capped. |
| Coexistence | **Both live, per-symbol mutual exclusion** | First-to-fire holds the symbol+direction; Turtle beats Donchian in the same cycle. |
| Computation placement | **A+C blend** | Pure math in `shared-trade-core`; signal-service owns entries, execution recomputes channels live for exits/pyramiding. |

## 3. Architecture overview

```
                         shared-trade-core
                         ┌─────────────────┐
                         │  DonchianMath   │  (pure: channel, N, 2N stop,
                         │  (tested once)  │   0.5N add-trigger, breakout dir)
                         └────────┬────────┘
              ┌───────────────────┴───────────────────┐
              │ calls                                  │ calls
   signal-service (ENTRY)                   trade-execution-service (EXIT + PYRAMID)
   ┌────────────────────────┐               ┌──────────────────────────────────┐
   │ DonchianChannelService │               │ MutualExclusionGuard (intake)    │
   │  60×1d → DonchianSnapshot│             │ StrategyExitPolicy (skip stagnation/trail)│
   │ MarketContext.donchian()│              │ DonchianExitMonitor (@Scheduled, live channel)│
   │ 3 detectors:           │  signal →     │ PyramidingEngine (@Scheduled)    │
   │  donchian / s1 / s2    │  (carries N)  │ executed_trade_units (child table)│
   └────────────────────────┘               └──────────────────────────────────┘
              │                                          │
              └──────── both read 1d candles ────────────┘
                       (each its own fetch; same math)
```

The "A+C blend": one math implementation (C — shared-core), but each service
fetches its own daily candles so the execution-side exit trails the channel
**live** rather than from a stale entry snapshot (A's weakness). The signal
still carries the entry-time `N` so unit sizing and pyramid spacing stay
anchored to the value the entry was based on.

## 4. Component design

### 4.1 `shared-trade-core` — `DonchianMath`

Pure, stateless, package `com.cryptoradar.core`. No I/O. Mirrors the existing
`RUnitMath` / `TrailCalculator` style.

```java
public final class DonchianMath {
    private DonchianMath() {}

    /** Highest high over the prior `lookback` COMPLETED bars (excludes index end). */
    public static double channelHigh(double[] highs, int endExclusive, int lookback);
    /** Lowest low over the prior `lookback` completed bars. */
    public static double channelLow(double[] lows, int endExclusive, int lookback);

    /** N = Wilder-smoothed 20-day ATR: N = (19*prevN + TR)/20. */
    public static double computeN(double[] highs, double[] lows, double[] closes, int period);

    /** entry ∓ 2N (LONG: entry-2N; SHORT: entry+2N). */
    public static double unitStop(double entry, double n, boolean isLong, double stopMultiple);

    /** Next pyramid level: lastUnitEntry ± stepFraction·N (default 0.5N). */
    public static double addTrigger(double lastUnitEntry, double n, boolean isLong, double stepFraction);

    /** LONG if price>channelHigh, SHORT if price<channelLow, else null. */
    public static Direction breakoutDirection(double price, double channelHigh, double channelLow);
}
```

Key correctness rules:
- Channel uses **prior completed bars only** — the current forming daily bar is
  excluded so a breakout means "today exceeds the prior 20 days."
- `computeN` seeds with a simple average of the first `period` TRs, then applies
  Wilder smoothing for the remainder (the original Turtle N).
- All methods validate array length ≥ required lookback and throw
  `IllegalArgumentException` with an actionable message otherwise.

### 4.2 `signal-service` — entry path

**`DonchianSnapshot`** (immutable record, new). Carries **all six** channel
levels needed across the three strategies' entries and exits:
```java
public record DonchianSnapshot(
    double high20, double low20,   // 20-day entry channel (donchian + S1 long/short)
    double high10, double low10,   // 10-day reverse-exit channel (donchian + S1)
    double high55, double low55,   // 55-day entry channel (S2 long/short)
    double n, boolean lastS1BreakoutWasWinner, Instant computedAt) {}
```
(S2's 20-day reverse exit reuses `high20`/`low20`.)

**`DonchianChannelService`** (`@ApplicationScoped`, `@Scheduled`):
- Mirrors `MarketRegimeService`: per symbol, fetch last 60 × `1d` candles via the
  market-data client/query pattern already used there.
- Computes channel levels + `N` via `DonchianMath`.
- Resolves `lastS1BreakoutWasWinner` from the last **closed** `turtle-s1`
  `signal_outcomes` row for the symbol (`realized_r_multiple > 0`) via
  `SignalOutcomeRepository`. Default `false` when no history (so the first
  breakout is always taken).
- Caches `Map<String, DonchianSnapshot>`, refreshes hourly, primes on
  `StartupEvent`. Fail-safe: a missing snapshot → detectors no-op for that symbol.

**`MarketContext`** gains `DonchianSnapshot donchian()`; the context builder
populates it from the service before detectors run. Detectors stay **pure**.

**Detectors** (each `implements TradeSetupDetector`, independent outcome rows):

| Strategy `name()` | Entry trigger | Reverse-exit lookback | Loser filter | Pyramid-eligible |
|---|---|---|---|---|
| `donchian` | price breaks `high20` (LONG) / `low20` (SHORT) | 10-day | no | no |
| `turtle-s1` | price breaks `high20` / `low20` | 10-day | yes | yes |
| `turtle-s2` | price breaks `high55` / `low55` | 20-day (`high20`/`low20`) | no | yes |

Each detector builds its own `TradeSetup`:
- `stop = DonchianMath.unitStop(entry, n, isLong, 2.0)`.
- `target = entry ± 20·N` — a **nominal catastrophic backstop only**, present so
  the existing RR-floor passes. `reasons()` records "operative exit = reverse
  Donchian monitor; TP is a 20N backstop."
- `alignment` = fixed mechanical constant (e.g. 60) — these are rule-based, not
  confluence-scored. Detector-originated signals already bypass the execution
  alignment-floor gate.
- `TrailConfig`: not used for live exits (execution disables the trail-mirror for
  these strategies), but a value is still required by the `TradeSetup`
  constructor — pass `TrailConfig.DEFAULT` and rely on `StrategyExitPolicy` to
  ignore it.
- `reasons()` lists: trigger level crossed, N value, 2N stop, and (S1) the
  loser-filter verdict.

S1 loser-filter logic: skip the breakout when `lastS1BreakoutWasWinner == true`
(the contrarian filter); a 55-day breakout failsafe is **not** modelled in v1 —
S2 covers the 55-day breakout as its own measured strategy. Documented deviation.

### 4.3 Sizing & risk caps

Unit quantity reuses `RUnitMath.computeQty(equity, unitRiskPercent, entry,
stop2N, lotStep)` — the 2N stop distance is the risk denominator, so no new
sizing math.

New config (signal-side carries intent; execution enforces):

| Property | Default | Notes |
|---|---|---|
| `turtle.unit-risk-percent` | `1.0` | Risk to the 2N stop per unit. Faithful Turtle = 2.0; defaulted lower for the small live account. ⚠️ real money |
| `turtle.stop-multiple` | `2.0` | N multiples for the catastrophic stop. |
| `turtle.pyramid-step-fraction` | `0.5` | Add a unit every 0.5N favourable. |
| `turtle.max-units-per-market` | `4` | Hard per-market unit cap. |
| `turtle.max-heat-percent` | `6.0` | Hard ceiling on summed open risk per market; blocks further adds. ⚠️ |
| `turtle.global-max-units` | `12` | Across all markets. |
| `turtle.max-units-per-direction` | `8` | Long or short aggregate. |

**Correlation simplification (documented):** the original tiered
correlated/uncorrelated unit caps assume a diversified futures portfolio. Crypto
is overwhelmingly BTC-correlated, so all pairs are treated as **correlated** —
one global unit cap + total-heat cap replaces the loose-correlation tiers.

### 4.4 `trade-execution-service` — exits & position management

**`MutualExclusionGuard`** (intake, before `OrderPlacer.place`): skip the
placement if any OPEN trade in `{donchian, turtle-s1, turtle-s2}` already holds
the same symbol+direction. First-to-fire wins; when `donchian` and `turtle-s1`
fire in the same evaluation cycle, Turtle is offered first. Fail-open on query
error (consistent with existing gates).

**`StrategyExitPolicy`**: a config-driven set of "long-horizon" strategy names.
`StagnationMonitor` and `TrailMirror` each early-return for trades whose strategy
is in the set — so the 45-min stagnation exit and the R-trail never touch these
multi-day positions. The 2N stop on Bybit + the Donchian monitor are the only
exits.

**`DonchianExitMonitor`** (`@Scheduled`): for each open Turtle/Donchian trade,
recompute the **live** reverse channel (execution service fetches its own 60×1d
candles, calls shared-core `DonchianMath`) and `OrderPlacer.close(trade,
ExitReason.DONCHIAN_EXIT)` when price crosses it:
- `donchian` / `turtle-s1` long → close on `low10`; short → close on `high10`.
- `turtle-s2` long → close on `low20`; short → close on `high20`.

New `ExitReason.DONCHIAN_EXIT` enum value. Fail-open: a failed candle fetch skips
the tick (the Bybit 2N stop still protects the position).

**`PyramidingEngine`** (`@Scheduled`) ⚠️ highest-risk component:
- For each open `turtle-s1`/`turtle-s2` trade under `max-units-per-market` and
  with summed open risk under `max-heat-percent`:
  - When live price has advanced ≥ `pyramid-step-fraction · N` beyond the last
    unit's entry, place +1 unit (`OrderPlacer`, same symbol+direction).
  - **Ratchet every unit's stop** to `newUnitEntry ∓ 2N` (Bybit stop amend), so
    aggregate open risk stays roughly constant as the position grows.
  - Re-check `global-max-units` and `max-units-per-direction` before each add.
- N is read from the trade row (entry-time value carried on the signal), so add
  spacing is anchored to the entry's volatility.

**Schema — `executed_trade_units` child table** (clean per-unit accounting):
```sql
CREATE TABLE executed_trade_units (
    id              BIGSERIAL PRIMARY KEY,
    executed_trade_id BIGINT NOT NULL REFERENCES executed_trades(id),
    unit_index      SMALLINT NOT NULL,         -- 1..4
    entry_price     NUMERIC NOT NULL,
    qty             NUMERIC NOT NULL,
    stop_price      NUMERIC NOT NULL,          -- current ratcheted stop
    n_value         NUMERIC NOT NULL,          -- N at entry of this unit
    opened_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (executed_trade_id, unit_index)
);
```
Plus `executed_trades.strategy_kind` marker (or reuse `strategy`) so monitors
filter cheaply. The parent `executed_trades` row remains the position; units are
its rungs.

### 4.5 Config, flags, marker

- `turtle.enabled` master kill-switch + per-strategy enables
  (`turtle.donchian.enabled`, `turtle.s1.enabled`, `turtle.s2.enabled`) and
  `turtle.execution.enabled`. Defaults **on** (live-from-day-one) but every
  sub-behavior is independently flaggable for instant rollback, matching the
  Tier-4 / StagnationMonitor pattern.
- `deployment_markers` row: `v8-turtle-donchian` with a description of the launch,
  so `/metrics` slices cleanly before/after.

## 5. Data flow (one trade, full lifecycle)

1. `DonchianChannelService` refresh → `DonchianSnapshot{high20, …, n}` cached.
2. Context builder injects snapshot into `MarketContext`.
3. `TurtleSystem1Detector.detect` sees price > `high20`, loser-filter passes →
   `TradeSetup(entry, 2N stop, 20N backstop TP, n, reasons)`.
4. `OutcomeTracker` persists a `turtle-s1` `signal_outcomes` row (paper truth).
5. Signal dispatched to execution carrying `n`. `MutualExclusionGuard` confirms no
   open turtle/donchian position in symbol+direction. `OrderPlacer.place` sizes 1
   unit via `RUnitMath` against the 2N stop; writes `executed_trades` +
   `executed_trade_units[1]`.
6. `StrategyExitPolicy` keeps `StagnationMonitor`/`TrailMirror` off this trade.
7. `PyramidingEngine` adds units 2–4 at each +0.5N, ratcheting stops, until caps.
8. `DonchianExitMonitor` closes the whole position when price hits the live
   10-day low; or the Bybit 2N stop fires first as backstop.
9. `OutcomeEvaluator` (signal side) independently records the paper outcome;
   reconciler records the live PnL on `executed_trades`.

## 6. Test plan

- **shared-core (`DonchianMath`)**: channel excludes current bar; Wilder N matches
  a hand-computed reference; 2N stop sides; 0.5N add-trigger; breakout direction;
  input-validation throws.
- **signal-service detectors**: fires on breakout; silent inside channel; S1
  loser-filter skips after a win and takes after a loss / no history; S2 uses
  55/20; correct 2N stop side per direction; built via a `MarketContext` builder
  with an injected `DonchianSnapshot`.
- **execution**:
  - `PyramidingEngine`: adds at exactly 0.5N; caps at 4 units; heat cap blocks the
    next add; stop ratchet sets all units to the newest 2N; global/direction caps.
  - `DonchianExitMonitor`: closes on the correct reverse level per strategy;
    ignores non-Turtle strategies; fail-open on fetch error.
  - `MutualExclusionGuard`: blocks the second entry; allows different
    symbol/direction; Turtle-precedence in same cycle.
  - `StrategyExitPolicy`: stagnation + trail skip for long-horizon strategies,
    unaffected for others.

Success check per phase: the named tests pass under `mvnd test` in each module,
plus a live smoke (phase 5) showing a real `turtle-*` row in `executed_trades`
with `unit_count ≥ 1` and a coherent 2N stop on Bybit.

## 7. Build order (each phase independently shippable)

1. `shared-trade-core` `DonchianMath` + tests. (`mvn install` to `.m2`.)
2. signal-service: `DonchianSnapshot`, `DonchianChannelService`, `MarketContext`
   wiring, 3 detectors + tests. → **tracking live in `signal_outcomes`.**
3. execution: `MutualExclusionGuard`, `StrategyExitPolicy` skips,
   `DonchianExitMonitor`, `executed_trade_units` schema + tests. → **live
   single-unit trading.**
4. execution: `PyramidingEngine` + caps + tests. → **full Turtle.**
5. Config flags, `v8-turtle-donchian` marker, live verification at minimal
   `unit-risk-percent`.

## 8. Assumptions baked in (confirmed)

- System-1 loser-filter **included**, sourced from last closed `turtle-s1`
  realized R.
- Universe = the existing **13 pairs**.
- All pairs treated as **correlated** (single global cap, not loose-correlation
  tiers).
- `unit-risk-percent` default **1.0%** (below faithful 2.0%) for the small live
  account.
- Three-strategy split (§4.2), §4.3 risk-cap defaults, and §4.4 child-table
  accounting — all approved.

## 9. Risks & mitigations ⚠️

| Risk | Mitigation |
|---|---|
| Unvalidated strategy risks real capital from day one | Conservative `unit-risk-percent`, hard heat cap, existing daily-loss halt + `SymbolPerformanceGate` + `GuardrailPolicy` still apply; per-behavior kill-switches. |
| Pyramiding gap blows past intended heat | Heat cap re-checked before every add; stops ratchet so aggregate risk stays bounded; Bybit-side 2N stop is the hard floor. |
| Daily Turtle = few signals, long holds | Expected and intended; tracking + live measured over weeks against the `v8` marker. |
| Crypto correlation defeats diversification | All-correlated treatment + global unit cap prevents stacking 13 correlated longs. |
| Stale exit channel | Execution recomputes the channel live each tick (A+C blend), not from entry snapshot. |

## 10. Out of scope (deferred)

- 55-day breakout failsafe inside S1 (S2 covers 55-day separately).
- Frontend visualisation of Donchian channels / units (TradeLedger already shows
  `strategy`; a channel overlay is a later iteration).
- Backtest harness for the strategies (live + paper tracking first).
- Original Turtle's "add on N/2 then take partial" exotic variants.

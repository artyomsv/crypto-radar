# Triple-Barrier Labeling

> The canonical way to label a financial event for supervised learning: profit target up, stop down, time-barrier across. Maps directly onto our OutcomeEvaluator.

## Definition

The triple-barrier method (TBM) was introduced by Marcos Lopez de Prado in *Advances in Financial Machine Learning* (Wiley, 2018), Chapter 3. Given an entry event at time `t0` and an entry price `P0`, you draw three barriers around the subsequent path:

1. **Upper (profit-take) barrier** — a horizontal line at `P0 × (1 + u)` where `u` is set as a multiple of recent realized volatility (or, equivalently, of ATR). Hits this first → label `+1` (long-side win, short-side loss).
2. **Lower (stop-loss) barrier** — a horizontal line at `P0 × (1 − d)` for downside multiplier `d`, again volatility-scaled. Hits this first → label `−1` (long-side loss, short-side win).
3. **Vertical (time) barrier** — a vertical line at `t0 + H` where `H` is a maximum holding horizon in bars. Reached without either price barrier being hit → label is `0` (or, in Lopez de Prado's stricter variant, the sign of the realized return at `t0 + H`).

Two things make TBM materially better than "label by next-N-bar return":

- **It is path-dependent.** A return that ends flat after touching the upper barrier and reversing is a *win* under TBM (you would have taken profit), but `0` under naive labeling. That matches how a real trader operates.
- **It is volatility-scaled.** The barriers move with regime — wider in high-vol environments, tighter in chop — so a single labeled dataset is comparable across time and across symbols. This is the whole reason Lopez de Prado does it this way.

The label is later refined with **meta-labeling**: a separate model decides *whether* to take a trade given the primary model's direction call. TBM provides the labels for both stages.

## When it works

- **Supervised classifiers trained on event-driven entries.** If your "events" are detector fires (liquidity sweep, trend continuation, etc.), TBM gives you a labeled outcome for every event without arbitrary bar-counting.
- **Cross-symbol learning.** Because barriers are vol-scaled, a labeled dataset from BTC, ETH, and SOL can be concatenated without one symbol dominating by virtue of its raw volatility.
- **Strategies with explicit profit-take / stop-loss.** Any real trading system already has these levels; TBM just makes the label consistent with how the trade actually plays out.

## When it fails

- **No clean entry events.** TBM needs a `t0`. If you're trying to label every bar (e.g. for next-bar return prediction), TBM degenerates and you should use a different framework.
- **Vol estimator looks ahead.** If `u` and `d` are set using volatility that includes `t0` onwards, you've leaked the future. Vol must be estimated strictly on `[t0 − N, t0 − 1]`.
- **Holding horizon poorly chosen.** Too short, and almost every label is `0` (time-out). Too long, and you've effectively dropped the stop barrier — every trade either hits target or runs for hours. Lopez de Prado recommends choosing `H` so that the time-barrier hit rate is roughly 30–40%.
- **Single-bar barriers.** If your bars are coarse (1 day) and the barriers are close, intrabar paths can hit both barriers in the same bar and you have to pick one — usually conservative (stop wins). Use finer bars where possible.

## What we do today (in projectr-x)

The `signal_outcomes` lifecycle in `OutcomeEvaluator.java` is a triple-barrier labeler in everything but name:

| TBM concept | projectr-x implementation |
|---|---|
| Entry event `t0` | `signal_outcomes.fired_at` — set by `OutcomeTracker` on a signal transition or detector fire |
| Upper barrier (long) / lower (short) | `signal_outcomes.target_price` — set by `SignalEngine.populateTradeLevels` or `LiquiditySweepDetector.buildSetup`, enforcing `MIN_RR = 2.0` |
| Lower barrier (long) / upper (short) | `signal_outcomes.stop_price` — enforces `MIN_RISK_PCT = 1.5%` (post-v4) |
| Time barrier | Implicit in the stagnation rule: a trade with no movement after 45×1m bars closes with `final_exit_reason = 'STAGNATION'` |
| Label `+1` | `final_exit_reason ∈ {TARGET, TRAIL}` with `realized_r_multiple > 0` |
| Label `−1` | `final_exit_reason ∈ {INITIAL_STOP, TRAIL}` with `realized_r_multiple < 0` |
| Label `0` | `final_exit_reason = 'STAGNATION'` |

The **vol scaling** also matches: `MIN_RISK_PCT` is a hard floor, but actual stops in `LiquiditySweepDetector` are placed via `STOP_BUFFER_ATR = 0.5` — half an ATR beyond the swept level. ATR is itself a rolling realized-vol estimate. So our "upper" and "lower" barriers move with regime, exactly as Lopez de Prado prescribes.

The new **ATR-scaled stagnation rule** (deployment marker `v4-data-driven-vectors`, 2026-04-24, then further refined in late May 2026) is a direct upgrade of the vertical-barrier idea. The original v4 rule used percent thresholds (`MFE < 0.2%`, `MAE > −0.3%`), which was sensible in calm markets but stayed silent through perfectly stagnant high-vol windows where 0.2% is a tick. The refined rule reads: **close as STAGNATION when, over 45 consecutive 1m bars, MFE < 0.25 × ATR(14) and MAE > −0.4 × ATR(14)**. The barriers now widen and narrow with volatility — a tight chop hits stagnation quickly, a wide-ATR coil gets the full 45 minutes before being cut.

Code references:
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java` — walks 1m bars, detects target / trail / stop / stagnation. The whole file is the triple-barrier walker.
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeTracker.java` — sets `fired_at` (the `t0`) and persists target/stop levels.
- `db/init/signal-init.sql` — `signal_outcomes` table is the labeled-event store.
- `shared-trade-core/src/main/java/com/cryptoradar/core/TrailCalculator.java` — modifies the upper/lower barrier dynamically (the trailing stop is a moving lower-barrier-for-longs).

## Implementation sketch (gaps that remain)

What's still missing relative to a textbook TBM:

1. **Explicit `H` (max bars) field.** We have stagnation but no hard time barrier. Adding `max_holding_bars` to `TradeSetup` and forcing a close at that horizon would give us a clean three-way label distribution `{+1, −1, 0}`. ~half a day of work.
2. **Vol-scaled `u` and `d` everywhere, not just LS detector.** `SignalEngine.populateTradeLevels` still uses % distances; should use ATR multiples to match the LS detector. ~1 day.
3. **Side-of-trade pre-filter.** A first-stage model that decides whether a TBM-labeled event is worth taking at all. This is the meta-labeling step that would slot in front of `trade-execution-service`'s alignment floor — see `02-ml-for-trading.md`.

## Sources

1. [Lopez de Prado, M. *Advances in Financial Machine Learning*, Chapter 3 "Labeling"](https://www.wiley.com/en-us/Advances+in+Financial+Machine+Learning-p-9781119482086) — the original presentation of the triple-barrier method and meta-labeling pattern.
2. [Murtazin, A. "Labeling Stock Prices for ML with Triple Barrier Methods"](https://ayratmurtazin.beehiiv.com/p/labeling-stock-prices-for-ml-with-triple-barrier-methods) — clean practitioner walkthrough with code, useful for cross-checking your implementation.
3. [Santos, W. "Algorithmic trading: triple barrier labelling"](https://williamsantos.me/posts/2022/triple-barrier-labelling-algorithm/) — Python implementation that makes the path-dependent walking explicit.
4. [Hudson & Thames "A Lab for Machine Learning in Finance" (mlfinlab project)](https://hudsonthames.org/a-laboratory-for-machine-learning-in-finance/) — open-source library implementing TBM with vol scaling per the textbook; useful reference implementation.
5. [Lopez de Prado *AFML* preprint Chapter 1 (Wiley TOC PDF)](https://toc.library.ethz.ch/objects/pdf03/e01_978-1-119-48208-6_01.pdf) — table of contents and chapter framing.

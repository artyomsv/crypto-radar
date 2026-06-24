package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.ClosedPnlV5;
import com.cryptoradar.execution.client.bybit.dto.PositionV5;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.model.ExitReason;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.notify.ExecutionEventService;
import com.cryptoradar.execution.observability.SlippageLogger;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Startup + periodic drift check between local DB and Bybit position state.
 * Detects orphans (Bybit has a position we don't know about) and closed-externally
 * cases (local OPEN but Bybit has none — fetch closed-pnl to populate realized
 * P&L, fees, and exit reason).
 */
@ApplicationScoped
public class OrderReconciler {

    private static final Logger LOG = Logger.getLogger(OrderReconciler.class);

    private final ExecutedTradeRepository tradeRepo;
    private final ExchangeAccountRepository accountRepo;
    private final ExecutionEventService events;
    private final BybitV5RestClient bybit;

    public OrderReconciler(ExecutedTradeRepository tradeRepo, ExchangeAccountRepository accountRepo,
                           ExecutionEventService events, BybitV5RestClient bybit) {
        this.tradeRepo = tradeRepo;
        this.accountRepo = accountRepo;
        this.events = events;
        this.bybit = bybit;
    }

    void onStartup(@Observes StartupEvent ev) {
        try {
            reconcile();
        } catch (RuntimeException e) {
            LOG.warnf(e, "startup reconcile failed");
        }
    }

    @Scheduled(every = "${execution.reconcile.interval:60s}", delay = 45, delayUnit = TimeUnit.SECONDS)
    @Transactional
    public void reconcile() {
        accountRepo.listAll().forEach(this::reconcileAccount);
    }

    @Transactional
    public void reconcileAccount(ExchangeAccount account) {
        BybitResponse<BybitV5RestClient.ListResult<PositionV5>> resp;
        try {
            resp = bybit.getPositionList(account.getEnvironment(),
                    account.getApiKeyEncrypted(), account.getApiSecretEncrypted());
        } catch (RuntimeException e) {
            LOG.warnf(e, "positionList fetch failed for account %d", account.getId());
            return;
        }
        if (!resp.isOk() || resp.result() == null) return;

        List<PositionV5> remote = resp.result().list();
        List<ExecutedTrade> local = tradeRepo.findOpenForAccount(account.getId());

        Set<String> remoteOpen = new HashSet<>();
        for (PositionV5 pos : remote) {
            if (!hasOpenSize(pos)) continue;
            remoteOpen.add(pos.symbol() + "|" + pos.side());
        }

        // Shared across the externally-closed loop and the incomplete-close sweep
        // below so no closed-pnl entry is attributed to two trades in one cycle.
        Set<String> consumedCloseIds = new HashSet<>();

        // Find local rows that are no longer on Bybit — closed externally.
        for (ExecutedTrade trade : local) {
            String side = "LONG".equals(trade.getDirection()) ? "Buy" : "Sell";
            String key = trade.getSymbol() + "|" + side;
            if (!remoteOpen.contains(key)) {
                closeFromReconcile(account, trade, consumedCloseIds);
            } else {
                trade.setLastSyncAt(Instant.now());
            }
        }

        // Find remote positions that aren't tracked locally — orphans.
        for (PositionV5 pos : remote) {
            if (!hasOpenSize(pos)) continue;
            String direction = "Buy".equals(pos.side()) ? "LONG" : "SHORT";
            boolean tracked = local.stream().anyMatch(t ->
                    t.getSymbol().equals(pos.symbol()) && t.getDirection().equals(direction));
            if (!tracked) {
                createOrphan(account, pos, direction);
            }
        }

        // Self-heal CLOSED rows left with NULL pnl/exit/entry by a WS-close that
        // could not fetch the closed-pnl payload (e.g. a Bybit outage). The
        // OPEN-row loop above never revisits CLOSED rows, so without this sweep
        // such rows stay permanently incomplete until a manual admin backfill.
        // Reached only after the position fetch above succeeded, so it is
        // naturally skipped while Bybit is unreachable and retried next cycle.
        sweepIncompleteCloses(account, INCOMPLETE_SWEEP_LIMIT, consumedCloseIds);
    }

    /**
     * Per-cycle cap on how many incomplete CLOSED rows the periodic reconcile
     * sweep repairs, bounding the closed-pnl fetches added to one reconcile
     * pass. A backlog drains across cycles; steady state is zero.
     */
    private static final int INCOMPLETE_SWEEP_LIMIT = 25;

    /**
     * Close a trade by querying Bybit's closed-pnl history for the symbol and
     * populating realized PnL, fees, exit price, exit reason, and (if missing)
     * entry price + R-multiple. Idempotent: callers may invoke this on a row
     * already CLOSED to backfill missing fields.
     *
     * <p>Package-private so {@link com.cryptoradar.execution.client.bybit.BybitV5WsClient}
     * can reuse the same logic when its position-update stream sees size→0,
     * keeping the close path single-sourced. Returns {@code true} when at
     * least one field was filled, {@code false} otherwise (no Bybit match,
     * fetch error, or row was already complete).
     */
    public boolean closeFromReconcile(ExchangeAccount account, ExecutedTrade trade) {
        return closeFromReconcile(account, trade, new HashSet<>());
    }

    /**
     * As {@link #closeFromReconcile(ExchangeAccount, ExecutedTrade)}, but threads
     * a {@code consumedCloseIds} set of closed-pnl orderIds already claimed earlier
     * in the same reconcile pass. Two same-symbol trades whose match windows
     * overlap would otherwise both be attributed the first in-window close; the
     * set ensures each claims a distinct entry.
     */
    public boolean closeFromReconcile(ExchangeAccount account, ExecutedTrade trade,
                                      Set<String> consumedCloseIds) {
        BybitResponse<BybitV5RestClient.ListResult<ClosedPnlV5>> pnlResp;
        try {
            pnlResp = bybit.getClosedPnl(account.getEnvironment(),
                    account.getApiKeyEncrypted(), account.getApiSecretEncrypted(),
                    trade.getSymbol(), 50);
        } catch (RuntimeException e) {
            LOG.warnf(e, "closedPnl fetch failed for trade %d", trade.getId());
            return false;
        }
        if (!pnlResp.isOk() || pnlResp.result() == null || pnlResp.result().list().isEmpty()) {
            return false;
        }

        ClosedPnlV5 match = pickMatchingClose(pnlResp.result().list(), trade, consumedCloseIds);
        if (match == null) return false;
        if (match.orderId() != null) consumedCloseIds.add(match.orderId());

        boolean filled = false;

        if (trade.getStatus() != TradeStatus.CLOSED) {
            trade.setStatus(TradeStatus.CLOSED);
            trade.setClosedAt(Instant.now());
            filled = true;
        }
        if (trade.getRealizedPnlUsdt() == null) {
            trade.setRealizedPnlUsdt(safeBd(match.closedPnl()));
            filled = true;
        }
        if (trade.getFeesUsdt() == null) {
            // Bybit may omit openFee/closeFee on liquidation or partial-fill edge
            // cases — coalesce nulls to zero so the addition doesn't NPE.
            BigDecimal openFee = safeBd(match.openFee());
            BigDecimal closeFee = safeBd(match.closeFee());
            trade.setFeesUsdt(
                    (openFee == null ? BigDecimal.ZERO : openFee)
                            .add(closeFee == null ? BigDecimal.ZERO : closeFee));
            filled = true;
        }
        // Prefer avgExitPrice over orderPrice — orderPrice is the trigger price
        // of a single fill while avgExitPrice is the position-level average.
        if (trade.getExitPrice() == null) {
            BigDecimal exit = safeBd(match.avgExitPrice());
            if (exit == null) exit = safeBd(match.orderPrice());
            trade.setExitPrice(exit);
            if (exit != null) filled = true;
        }
        // Backfill entry price from the position-level avgEntryPrice when WS
        // missed the execution event. Reconciler is the only path that can
        // recover this if the WS stream dropped before first fill landed.
        if (trade.getEntryPrice() == null) {
            BigDecimal entry = safeBd(match.avgEntryPrice());
            if (entry != null) {
                trade.setEntryPrice(entry);
                filled = true;
            }
        }
        if (trade.getExitReason() == null) {
            trade.setExitReason(inferExitReason(trade, match));
            filled = true;
        }
        // Slippage observability: log actual avgExitPrice vs the intended level
        // for whichever exit reason the inference produced. Pure observation,
        // no behavior change.
        logExitSlippage(trade, match);
        if (trade.getRealizedRMultiple() == null) {
            BigDecimal r = computeRMultiple(trade);
            if (r != null) {
                trade.setRealizedRMultiple(r);
                filled = true;
            }
        }

        if (filled) {
            ExecutionEvent ev = new ExecutionEvent();
            ev.setExchangeAccountId(account.getId());
            ev.setExecutedTradeId(trade.getId());
            ev.setEventType(ExecutionEventType.RECONCILE_CLOSED_EXTERNALLY);
            ev.setSignalId(trade.getSignalId());
            ev.setMetadata(Map.of(
                    "closedPnl", String.valueOf(match.closedPnl()),
                    "orderPrice", String.valueOf(match.orderPrice()),
                    "avgEntryPrice", String.valueOf(match.avgEntryPrice()),
                    "avgExitPrice", String.valueOf(match.avgExitPrice()),
                    "inferredExitReason", String.valueOf(trade.getExitReason())));
            events.record(ev);
        }
        return filled;
    }

    /**
     * Pick the closed-pnl entry that most likely corresponds to this trade.
     * Filters by close-side direction AND a time window around the trade's
     * open/close window — without the time bound, repeat trades on the same
     * symbol would steal each other's payload (caused mis-backfills with
     * stop_price on the wrong side of entry_price).
     *
     * <p>The acceptable window is from {@code openedAt - 1h} (catch fills
     * just before the row was persisted) through {@code closedAt + 24h}, or
     * "now" if the trade is still open at call time.
     *
     * <p>Among in-window candidates it returns the one whose {@code createdTime}
     * sits nearest the trade's close time, and skips any orderId already in
     * {@code consumedCloseIds}. Without both, two same-symbol trades with
     * overlapping windows would be attributed the same first-in-window close
     * (the BTC 255/257 duplicate).
     */
    private static ClosedPnlV5 pickMatchingClose(List<ClosedPnlV5> closes, ExecutedTrade trade,
                                                 Set<String> consumedCloseIds) {
        if (closes.isEmpty()) return null;
        String openSide = "LONG".equals(trade.getDirection()) ? "Buy" : "Sell";
        String closeSide = "Buy".equals(openSide) ? "Sell" : "Buy";

        Instant lowerBound = trade.getOpenedAt() == null ? null
                : trade.getOpenedAt().minusSeconds(MATCH_WINDOW_BEFORE_OPEN_SEC);
        Instant upperBound = trade.getClosedAt() != null
                ? trade.getClosedAt().plusSeconds(MATCH_WINDOW_AFTER_CLOSE_SEC)
                : Instant.now().plusSeconds(MATCH_WINDOW_AFTER_CLOSE_SEC);
        // The close should sit nearest the trade's recorded close (≈ when Bybit
        // closed it), or "now" for a just-detected external close.
        Instant reference = trade.getClosedAt() != null ? trade.getClosedAt() : Instant.now();

        ClosedPnlV5 best = null;
        long bestDist = Long.MAX_VALUE;
        for (ClosedPnlV5 c : closes) {
            if (!closeSide.equals(c.side())) continue;
            if (c.orderId() != null && consumedCloseIds.contains(c.orderId())) continue;
            Instant created = parseEpochMillis(c.createdTime());
            if (lowerBound != null && created != null && created.isBefore(lowerBound)) continue;
            if (created != null && created.isAfter(upperBound)) continue;
            // Entries without a parseable time sort last but stay selectable.
            long dist = created == null ? Long.MAX_VALUE - 1
                    : Math.abs(created.toEpochMilli() - reference.toEpochMilli());
            if (dist < bestDist) {
                best = c;
                bestDist = dist;
            }
        }
        // No row matched within the window — refuse to guess. The caller
        // treats null as "no match", which is safer than attributing another
        // trade's PnL to this row.
        return best;
    }

    private static Instant parseEpochMillis(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Instant.ofEpochMilli(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final long MATCH_WINDOW_BEFORE_OPEN_SEC = 3_600;          //  1h
    private static final long MATCH_WINDOW_AFTER_CLOSE_SEC = 24 * 3_600;     // 24h

    /**
     * Infer an {@link ExitReason} when the trade was closed externally on
     * Bybit (no in-process exit signal). Strategy:
     * <ol>
     *   <li>If we have entry+stop+target+exit price: compare exit price to
     *       the stop and target levels (with 0.1% tolerance for slippage).
     *       Trail-active flag distinguishes INITIAL_STOP vs TRAIL_STOP.</li>
     *   <li>Otherwise fall back to PnL sign + trail-active flag.</li>
     * </ol>
     * Defaults to {@code MANUAL} when neither path produces an answer —
     * better to mark "unknown" than to silently mislabel as TARGET, which
     * was the bug that misclassified 26 stop hits as wins.
     */
    static ExitReason inferExitReason(ExecutedTrade trade, ClosedPnlV5 match) {
        boolean trailActive = trade.getTrailTriggeredAt() != null;
        BigDecimal exit = safeBd(match.avgExitPrice());
        if (exit == null) exit = safeBd(match.orderPrice());
        BigDecimal target = trade.getTargetPrice();
        BigDecimal stop = trade.getStopPrice();

        BigDecimal closedPnl = safeBd(match.closedPnl());
        if (exit != null && target != null && stop != null && exit.signum() > 0) {
            BigDecimal tol = exit.multiply(SLIPPAGE_TOLERANCE);
            boolean isLong = "LONG".equals(trade.getDirection());
            if (isLong) {
                if (exit.compareTo(target.subtract(tol)) >= 0) return ExitReason.TARGET;
                if (exit.compareTo(stop.add(tol)) <= 0) {
                    return trailActive ? ExitReason.TRAIL_STOP : ExitReason.INITIAL_STOP;
                }
            } else {
                if (exit.compareTo(target.add(tol)) <= 0) return ExitReason.TARGET;
                if (exit.compareTo(stop.subtract(tol)) >= 0) {
                    return trailActive ? ExitReason.TRAIL_STOP : ExitReason.INITIAL_STOP;
                }
            }
            // Exit between the two levels — slippage from a stop or target
            // hit is the dominant cause in volatile crypto markets (price
            // gaps through the level and the market order fills somewhere
            // back inside). Use PnL sign + trail-active to disambiguate.
            // Reserve MANUAL only for the genuinely ambiguous case where
            // PnL is missing too.
            if (trailActive) return ExitReason.TRAIL_STOP;
            if (closedPnl != null) {
                return closedPnl.signum() > 0 ? ExitReason.TARGET : ExitReason.INITIAL_STOP;
            }
            return ExitReason.MANUAL;
        }

        if (closedPnl != null) {
            if (closedPnl.signum() > 0) return trailActive ? ExitReason.TRAIL_STOP : ExitReason.TARGET;
            return trailActive ? ExitReason.TRAIL_STOP : ExitReason.INITIAL_STOP;
        }
        return ExitReason.MANUAL;
    }

    /**
     * Compute R-multiple from entry/stop/exit. Rejects nonsense risk geometry
     * to guard against backfilled rows whose stored {@code stopPrice} ended up
     * on the wrong side of {@code entryPrice} (e.g., LONG with stop above
     * entry). Such rows would otherwise produce extreme values like -245R
     * that pollute aggregate metrics.
     */
    private static BigDecimal computeRMultiple(ExecutedTrade trade) {
        if (trade.getEntryPrice() == null || trade.getStopPrice() == null
                || trade.getExitPrice() == null) return null;

        BigDecimal entry = trade.getEntryPrice();
        BigDecimal stop = trade.getStopPrice();
        boolean isLong = "LONG".equals(trade.getDirection());

        // Stop must be on the protective side of entry: below for LONG, above
        // for SHORT. Otherwise the row's risk geometry is corrupt.
        if (isLong && stop.compareTo(entry) >= 0) return null;
        if (!isLong && stop.compareTo(entry) <= 0) return null;

        BigDecimal riskDist = entry.subtract(stop).abs();
        if (riskDist.signum() <= 0) return null;
        // Guard against vanishingly small risk distances (< 0.1% of entry)
        // which produce unreliable R values from minor stop-placement noise.
        BigDecimal minRisk = entry.abs().multiply(MIN_RISK_FRACTION_FOR_R);
        if (riskDist.compareTo(minRisk) < 0) return null;

        BigDecimal pnlDist = isLong
                ? trade.getExitPrice().subtract(entry)
                : entry.subtract(trade.getExitPrice());
        return pnlDist.divide(riskDist, 4, RoundingMode.HALF_UP);
    }

    private static final BigDecimal MIN_RISK_FRACTION_FOR_R = new BigDecimal("0.001");

    private static final BigDecimal SLIPPAGE_TOLERANCE = new BigDecimal("0.001");

    /**
     * Re-classify exit_reason on rows where the recorded reason contradicts
     * the PnL sign — i.e., the legacy default-to-TARGET bug. Uses local data
     * only (entry/stop/target/exit price + pnl + trail flag); no Bybit call.
     * Returns the number of rows whose exit_reason was changed.
     */
    @Transactional
    public int repairMislabeledExitReasons(Long accountId, int limit) {
        if (accountRepo.findById(accountId) == null) return 0;
        List<ExecutedTrade> rows = tradeRepo.findMislabeledExits(accountId, limit);
        int changed = 0;
        for (ExecutedTrade trade : rows) {
            ExitReason inferred = inferFromLocalData(trade);
            if (inferred != null && inferred != trade.getExitReason()) {
                ExitReason previous = trade.getExitReason();
                trade.setExitReason(inferred);
                changed++;
                ExecutionEvent ev = new ExecutionEvent();
                ev.setExchangeAccountId(accountId);
                ev.setExecutedTradeId(trade.getId());
                ev.setEventType(ExecutionEventType.RECONCILE_CLOSED_EXTERNALLY);
                ev.setSignalId(trade.getSignalId());
                ev.setMetadata(Map.of(
                        "repair", "exit_reason_reclassified",
                        "previous", String.valueOf(previous),
                        "inferred", String.valueOf(inferred)));
                events.record(ev);
            }
        }
        if (changed > 0) {
            LOG.infof("repairMislabeledExitReasons account=%d changed=%d/%d",
                    accountId, changed, rows.size());
        }
        return changed;
    }

    /**
     * Local-only reclassifier that mirrors {@link #inferExitReason}'s logic
     * but reads from the trade row instead of a {@link ClosedPnlV5} response.
     * Returns null when the row lacks enough data to decide.
     */
    private static ExitReason inferFromLocalData(ExecutedTrade trade) {
        boolean trailActive = trade.getTrailTriggeredAt() != null;
        BigDecimal exit = trade.getExitPrice();
        BigDecimal target = trade.getTargetPrice();
        BigDecimal stop = trade.getStopPrice();

        BigDecimal pnl = trade.getRealizedPnlUsdt();
        if (exit != null && target != null && stop != null && exit.signum() > 0) {
            BigDecimal tol = exit.multiply(SLIPPAGE_TOLERANCE);
            boolean isLong = "LONG".equals(trade.getDirection());
            if (isLong) {
                if (exit.compareTo(target.subtract(tol)) >= 0) return ExitReason.TARGET;
                if (exit.compareTo(stop.add(tol)) <= 0) {
                    return trailActive ? ExitReason.TRAIL_STOP : ExitReason.INITIAL_STOP;
                }
            } else {
                if (exit.compareTo(target.add(tol)) <= 0) return ExitReason.TARGET;
                if (exit.compareTo(stop.subtract(tol)) >= 0) {
                    return trailActive ? ExitReason.TRAIL_STOP : ExitReason.INITIAL_STOP;
                }
            }
            // Between levels with no trail → resolve via PnL sign (likely
            // stop or target with slippage exceeding the tolerance band).
            if (trailActive) return ExitReason.TRAIL_STOP;
            if (pnl != null) {
                return pnl.signum() > 0 ? ExitReason.TARGET : ExitReason.INITIAL_STOP;
            }
            return null;
        }

        if (pnl != null) {
            if (pnl.signum() > 0) return trailActive ? ExitReason.TRAIL_STOP : ExitReason.TARGET;
            return trailActive ? ExitReason.TRAIL_STOP : ExitReason.INITIAL_STOP;
        }
        return null;
    }

    /**
     * Re-fetch Bybit closed-pnl for every CLOSED row on the account that has
     * NULL pnl/exit_reason/entry_price. One-shot repair for trades that were
     * marked closed by the WS path before the close-payload was available
     * (the WS race that caused the "—" rows in the trade ledger).
     *
     * <p>Bybit's closed-pnl endpoint only goes back ~30 days; older rows
     * cannot be recovered. Returns the count of trades whose data was
     * meaningfully updated.
     */
    @Transactional
    public int backfillIncompleteCloses(Long accountId, int limit) {
        ExchangeAccount account = accountRepo.findById(accountId);
        if (account == null) return 0;
        return sweepIncompleteCloses(account, limit, new HashSet<>());
    }

    /**
     * Re-fetch Bybit closed-pnl for CLOSED rows on the account that still have
     * NULL pnl/exit_reason/entry_price and fill what is recoverable. Shared by
     * the admin endpoint ({@link #backfillIncompleteCloses}) and the periodic
     * reconcile loop so a WS-close that landed during a Bybit outage self-heals
     * on the next cycle instead of needing a manual admin call. Returns the
     * count of trades whose data was meaningfully updated.
     */
    private int sweepIncompleteCloses(ExchangeAccount account, int limit, Set<String> consumedCloseIds) {
        List<ExecutedTrade> incomplete = tradeRepo.findIncompleteCloses(account.getId(), limit);
        int recovered = 0;
        for (ExecutedTrade trade : incomplete) {
            if (closeFromReconcile(account, trade, consumedCloseIds)) recovered++;
        }
        if (recovered > 0) {
            LOG.infof("incomplete-close sweep account=%d recovered=%d/%d",
                    account.getId(), recovered, incomplete.size());
        }
        return recovered;
    }

    private void createOrphan(ExchangeAccount account, PositionV5 pos, String direction) {
        ExecutedTrade orphan = new ExecutedTrade();
        orphan.setExchangeAccountId(account.getId());
        orphan.setSymbol(pos.symbol());
        orphan.setDirection(direction);
        orphan.setStatus(TradeStatus.OPEN);
        orphan.setEntryPrice(safeBd(pos.avgPrice()));
        orphan.setQty(safeBd(pos.size()));
        orphan.setStopPrice(safeBd(pos.stopLoss()));
        orphan.setTargetPrice(safeBd(pos.takeProfit()));
        orphan.setDynamicStopPrice(safeBd(pos.stopLoss()));
        orphan.setLeverage(Integer.parseInt(pos.leverage() == null ? "1" : pos.leverage()));
        tradeRepo.persist(orphan);

        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(account.getId());
        ev.setEventType(ExecutionEventType.RECONCILE_ORPHAN_DETECTED);
        ev.setExecutedTradeId(orphan.getId());
        ev.setMetadata(Map.of("symbol", pos.symbol(), "side", pos.side()));
        events.record(ev);
    }

    /**
     * Picks the intended exit level the reconciler thinks was hit (target /
     * trail stop / initial stop) and logs the basis-point gap between that
     * level and Bybit's avgExitPrice. Trail trades use {@code dynamicStopPrice}
     * since the ratcheted stop is the level the engine actually defended,
     * not the original {@code stopPrice}.
     */
    private static void logExitSlippage(ExecutedTrade trade, ClosedPnlV5 match) {
        ExitReason reason = trade.getExitReason();
        if (reason == null) return;
        BigDecimal intended = switch (reason) {
            case TARGET -> trade.getTargetPrice();
            case TRAIL_STOP -> trade.getDynamicStopPrice() != null
                    ? trade.getDynamicStopPrice() : trade.getStopPrice();
            case INITIAL_STOP -> trade.getStopPrice();
            default -> null;
        };
        if (intended == null) return;
        BigDecimal actual = safeBd(match.avgExitPrice());
        if (actual == null) actual = safeBd(match.orderPrice());
        if (actual == null) return;
        SlippageLogger.logExit(trade, reason, intended, actual);
    }

    private static boolean hasOpenSize(PositionV5 pos) {
        if (pos.size() == null || "0".equals(pos.size())) return false;
        try {
            return new BigDecimal(pos.size()).signum() != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static BigDecimal safeBd(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

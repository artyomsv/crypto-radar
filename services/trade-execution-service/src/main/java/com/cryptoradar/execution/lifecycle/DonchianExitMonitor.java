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
import jakarta.inject.Inject;
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
 * each tick. Fail-open per trade.
 */
@ApplicationScoped
public class DonchianExitMonitor {

    private static final Logger LOG = Logger.getLogger(DonchianExitMonitor.class);

    /** Fetch enough daily bars to cover the 20-day exit + 1 excluded forming bar, with slack. */
    private static final int CANDLE_FETCH = 30;

    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject StrategyExitPolicy exitPolicy;
    @Inject MarketDataClient marketData;
    @Inject OrderPlacer orderPlacer;

    @ConfigProperty(name = "execution.donchian-exit.enabled", defaultValue = "true")
    boolean enabled;

    // NOTE: the daily-candle + last-price HTTP fetches below run inside this
    // @Transactional sweep, holding the DB connection for the fetch loop's
    // duration. Safe at Plan 2's expected handful of long-horizon trades;
    // tracked for a two-phase (fetch-then-write) refactor before Plan 3
    // pyramiding raises per-symbol trade counts. See
    // techdebt/trade-execution-service/3-3-http-calls-inside-scheduled-transaction.md
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

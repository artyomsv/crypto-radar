package com.cryptoradar.derivatives.service;

import com.cryptoradar.derivatives.model.LiquidationLevel;
import com.cryptoradar.derivatives.model.LiquidationMap;
import com.cryptoradar.derivatives.model.SymbolDerivatives;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Estimates liquidation levels based on open interest, current price,
 * long/short ratio, and common leverage distributions.
 *
 * Methodology:
 * 1. Distribute open interest across common leverage tiers (2x-100x)
 *    weighted by typical retail/institutional usage patterns
 * 2. For each leverage tier, calculate the liquidation price:
 *    - Long liq = currentPrice * (1 - 1/leverage)
 *    - Short liq = currentPrice * (1 + 1/leverage)
 * 3. Weight by long/short ratio from actual market data
 * 4. Result: estimated USD at risk of liquidation at each price level
 */
@ApplicationScoped
public class LiquidationMapService {

    private static final Logger LOG = Logger.getLogger(LiquidationMapService.class);

    // Leverage tiers and their estimated share of total open interest
    // Based on typical Binance Futures leverage distribution research
    private static final int[] LEVERAGE_TIERS = {2, 3, 5, 10, 15, 20, 25, 50, 75, 100};
    private static final double[] LEVERAGE_WEIGHTS = {
            0.05,  // 2x  — conservative/institutional
            0.08,  // 3x  — low leverage
            0.15,  // 5x  — moderate
            0.25,  // 10x — most popular tier
            0.12,  // 15x — common
            0.12,  // 20x — popular
            0.10,  // 25x — aggressive
            0.07,  // 50x — high risk
            0.03,  // 75x — extreme
            0.03   // 100x — maximum, degen
    };

    @Inject
    DerivativesService derivativesService;

    // Cache: pre-computed every 15 minutes by scheduler
    private final java.util.concurrent.ConcurrentHashMap<String, LiquidationMap> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Get cached map or compute on-demand if not cached yet or if cached is empty */
    public LiquidationMap getLiquidationMap(String symbol) {
        LiquidationMap cached = cache.get(symbol);
        if (cached != null && cached.getLongLiquidationLevels() != null
                && !cached.getLongLiquidationLevels().isEmpty()) {
            return cached;
        }
        return computeAndCache(symbol);
    }

    /** Compute and store in cache — called by scheduler every 15 min */
    public LiquidationMap computeAndCache(String symbol) {
        LiquidationMap map = computeLiquidationMap(symbol);
        cache.put(symbol, map);
        return map;
    }

    /**
     * Compute estimated liquidation map for a symbol.
     */
    private LiquidationMap computeLiquidationMap(String symbol) {
        SymbolDerivatives data = derivativesService.getSymbolDerivatives(symbol);
        if (data == null || data.getOpenInterestUsd() <= 0) {
            return emptyMap(symbol);
        }

        double currentPrice = getCurrentPrice(symbol);
        if (currentPrice <= 0) {
            return emptyMap(symbol);
        }

        double oiUsd = data.getOpenInterestUsd();
        double longPct = data.getLongPct();
        double shortPct = data.getShortPct();

        // Split OI into long and short positions
        double longOiUsd = oiUsd * longPct;
        double shortOiUsd = oiUsd * shortPct;

        List<LiquidationLevel> longLevels = new ArrayList<>();
        List<LiquidationLevel> shortLevels = new ArrayList<>();

        double totalLongLiq = 0;
        double totalShortLiq = 0;
        double maxLongLiqPrice = 0;
        double maxLongLiqValue = 0;
        double maxShortLiqPrice = 0;
        double maxShortLiqValue = 0;

        for (int i = 0; i < LEVERAGE_TIERS.length; i++) {
            int leverage = LEVERAGE_TIERS[i];
            double weight = LEVERAGE_WEIGHTS[i];

            // Long liquidation: price drops by (1/leverage) from current
            double longLiqPrice = currentPrice * (1.0 - 1.0 / leverage);
            double longLiqValue = longOiUsd * weight;

            if (longLiqPrice > 0) {
                longLevels.add(new LiquidationLevel(
                        Math.round(longLiqPrice * 100.0) / 100.0,
                        longLiqValue,
                        0,
                        leverage
                ));
                totalLongLiq += longLiqValue;
                if (longLiqValue > maxLongLiqValue) {
                    maxLongLiqValue = longLiqValue;
                    maxLongLiqPrice = longLiqPrice;
                }
            }

            // Short liquidation: price rises by (1/leverage) from current
            double shortLiqPrice = currentPrice * (1.0 + 1.0 / leverage);
            double shortLiqValue = shortOiUsd * weight;

            shortLevels.add(new LiquidationLevel(
                    Math.round(shortLiqPrice * 100.0) / 100.0,
                    0,
                    shortLiqValue,
                    leverage
            ));
            totalShortLiq += shortLiqValue;
            if (shortLiqValue > maxShortLiqValue) {
                maxShortLiqValue = shortLiqValue;
                maxShortLiqPrice = shortLiqPrice;
            }
        }

        // Sort: longs by price descending (closest to current first), shorts ascending
        longLevels.sort(Comparator.comparingDouble(LiquidationLevel::getPrice).reversed());
        shortLevels.sort(Comparator.comparingDouble(LiquidationLevel::getPrice));

        LiquidationMap map = new LiquidationMap();
        map.setSymbol(symbol);
        map.setCurrentPrice(currentPrice);
        map.setOpenInterestUsd(oiUsd);
        map.setLongPct(longPct);
        map.setShortPct(shortPct);
        map.setTimestamp(Instant.now());
        map.setLongLiquidationLevels(longLevels);
        map.setShortLiquidationLevels(shortLevels);
        map.setHighestLiquidityLongPrice(Math.round(maxLongLiqPrice * 100.0) / 100.0);
        map.setHighestLiquidityShortPrice(Math.round(maxShortLiqPrice * 100.0) / 100.0);
        map.setTotalEstimatedLongLiqUsd(totalLongLiq);
        map.setTotalEstimatedShortLiqUsd(totalShortLiq);

        return map;
    }

    private double getCurrentPrice(String symbol) {
        // Get mark price from cached funding rate data
        var rates = derivativesService.getAllFundingRates();
        for (var rate : rates) {
            if (symbol.equals(rate.getSymbol())) {
                return rate.getMarkPrice();
            }
        }
        return 0;
    }

    private LiquidationMap emptyMap(String symbol) {
        LiquidationMap map = new LiquidationMap();
        map.setSymbol(symbol);
        map.setTimestamp(Instant.now());
        map.setLongLiquidationLevels(List.of());
        map.setShortLiquidationLevels(List.of());
        return map;
    }
}

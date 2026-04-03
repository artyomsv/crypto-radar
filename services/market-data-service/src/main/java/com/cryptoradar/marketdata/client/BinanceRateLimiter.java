package com.cryptoradar.marketdata.client;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized rate limiter for all Binance API requests.
 *
 * Binance limits: 1200 request weight per minute per IP.
 * Each kline request = weight 2, ticker = weight 40.
 * We budget conservatively: max 400 weight per minute (~200 kline requests).
 *
 * All Binance calls must go through acquire() / recordResponse().
 */
@ApplicationScoped
public class BinanceRateLimiter {

    private static final Logger LOG = Logger.getLogger(BinanceRateLimiter.class);

    private static final int MAX_WEIGHT_PER_MINUTE = 800;
    private static final long MINUTE_MS = 60_000;
    private static final long MIN_DELAY_BETWEEN_REQUESTS_MS = 150;

    private final AtomicInteger usedWeight = new AtomicInteger(0);
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final Semaphore concurrencyLimit = new Semaphore(3);

    private volatile int backoffMs = 0;

    /**
     * Acquire permission to make a Binance request.
     * Blocks if rate limit is close to being exceeded.
     *
     * @param weight the request weight (2 for klines, 40 for all-tickers)
     */
    public void acquire(int weight) {
        try {
            concurrencyLimit.acquire();

            // Apply backoff if we got a 429 recently
            if (backoffMs > 0) {
                LOG.warnf("Rate limit backoff: waiting %dms", backoffMs);
                Thread.sleep(backoffMs);
            }

            // Check and reset the weight window
            long now = System.currentTimeMillis();
            long elapsed = now - windowStart.get();
            if (elapsed >= MINUTE_MS) {
                usedWeight.set(0);
                windowStart.set(now);
            }

            // If we'd exceed the budget, wait for the window to reset
            if (usedWeight.get() + weight > MAX_WEIGHT_PER_MINUTE) {
                long sleepMs = MINUTE_MS - elapsed + 1000;
                LOG.infof("Rate limit budget exhausted (%d/%d). Sleeping %dms for window reset.",
                        usedWeight.get(), MAX_WEIGHT_PER_MINUTE, sleepMs);
                Thread.sleep(sleepMs);
                usedWeight.set(0);
                windowStart.set(System.currentTimeMillis());
            }

            // Enforce minimum delay between requests
            long timeSinceLast = System.currentTimeMillis() - lastRequestTime.get();
            if (timeSinceLast < MIN_DELAY_BETWEEN_REQUESTS_MS) {
                Thread.sleep(MIN_DELAY_BETWEEN_REQUESTS_MS - timeSinceLast);
            }

            usedWeight.addAndGet(weight);
            lastRequestTime.set(System.currentTimeMillis());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Release the concurrency permit after a request completes.
     */
    public void release() {
        concurrencyLimit.release();
    }

    /**
     * Record the actual weight from Binance response header.
     * Also handles 429 (rate limited) and 418 (IP banned) responses.
     */
    public void recordResponse(int statusCode, String usedWeightHeader) {
        // Parse actual used weight from response header
        if (usedWeightHeader != null) {
            try {
                int actualWeight = Integer.parseInt(usedWeightHeader);
                usedWeight.set(actualWeight);
            } catch (NumberFormatException ignored) {}
        }

        if (statusCode == 429) {
            // Rate limited — exponential backoff
            backoffMs = Math.min(backoffMs == 0 ? 5000 : backoffMs * 2, 60_000);
            LOG.warnf("Binance 429 rate limit hit. Backoff increased to %dms", backoffMs);
        } else if (statusCode == 418) {
            // IP ban — long backoff
            backoffMs = 120_000;
            LOG.errorf("Binance 418 IP ban! Backing off for %dms", backoffMs);
        } else if (statusCode == 200) {
            // Success — gradually reduce backoff
            if (backoffMs > 0) {
                backoffMs = Math.max(0, backoffMs - 1000);
            }
        }
    }

    public int getUsedWeight() {
        return usedWeight.get();
    }

    public int getRemainingBudget() {
        return MAX_WEIGHT_PER_MINUTE - usedWeight.get();
    }
}

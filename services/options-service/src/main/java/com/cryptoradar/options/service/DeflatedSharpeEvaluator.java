package com.cryptoradar.options.service;

import com.cryptoradar.options.repository.OptionShortVolOpportunityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tier 3 — Bailey &amp; López de Prado deflated Sharpe (SSRN 2460551).
 *
 * <p>Pure-math evaluator: pulls the last N closed outcome P&amp;L values for
 * a strategy and computes a Sharpe deflated by:
 *   <ul>
 *     <li>Skewness and kurtosis of returns (vol strategies have fat tails)</li>
 *     <li>An estimate of multiple-testing inflation (we have iterated config
 *         many times — every config tweak is an implicit trial)</li>
 *   </ul>
 *
 * <p>Reports a single number per strategy. Callers (status endpoint,
 * Tier-3 auto-kill rule) compare against thresholds.
 *
 * <p>Formula (Bailey/LdP 2014, eq. 9):
 * <pre>
 *   DSR = Z[ (SR_hat - SR_0) * sqrt(T - 1) /
 *             sqrt(1 - gamma3*SR_hat + ((gamma4-1)/4) * SR_hat^2) ]
 * </pre>
 * where {@code SR_hat} is the observed Sharpe, {@code SR_0} is the expected
 * max across N trials under the null, {@code T} is sample size, gamma3/4
 * are skew/kurt, and Z is the standard normal CDF.
 */
@ApplicationScoped
public class DeflatedSharpeEvaluator {

    @Inject EntityManager em;
    @Inject OptionShortVolOpportunityRepository shortRepo;

    /**
     * How many configuration variants we should assume have been tried.
     * Bailey/LdP show that DSR is highly sensitive to this; honest accounting
     * matters more than a low number. The {@code deployment_markers} table
     * is the audit trail — currently ~10 entries.
     */
    @ConfigProperty(name = "options.eval.trial-count", defaultValue = "20")
    int trialCount;

    @ConfigProperty(name = "options.eval.lookback", defaultValue = "100")
    int lookbackTrades;

    /**
     * Computes deflated Sharpe for the long-vol strategy.
     */
    public Result evaluateLongVol() {
        List<Double> returns = readLongVolReturns(lookbackTrades);
        return computeDsr(returns, "long-vol");
    }

    /**
     * Computes deflated Sharpe for the short-vol strategy.
     */
    public Result evaluateShortVol() {
        List<Double> returns = shortRepo.recentClosedReturnsPct(lookbackTrades);
        return computeDsr(returns, "short-vol");
    }

    @SuppressWarnings("unchecked")
    @Transactional
    List<Double> readLongVolReturns(int limit) {
        List<Object> rows = em.createNativeQuery("""
            SELECT outcome_pnl_pct FROM option_opportunities
            WHERE outcome_resolved_at IS NOT NULL AND outcome_pnl_pct IS NOT NULL
            ORDER BY outcome_resolved_at DESC LIMIT :limit
            """)
                .setParameter("limit", limit)
                .getResultList();
        List<Double> out = new ArrayList<>(rows.size());
        for (Object o : rows) if (o != null) out.add(((Number) o).doubleValue());
        return out;
    }

    Result computeDsr(List<Double> returns, String strategyName) {
        Result r = new Result();
        r.strategy = strategyName;
        r.n = returns.size();
        if (r.n < 10) {
            r.verdict = "INSUFFICIENT_SAMPLE";
            return r;
        }
        double[] x = returns.stream().mapToDouble(Double::doubleValue).toArray();
        double mean = mean(x);
        double std = stdev(x, mean);
        r.mean = mean;
        r.std = std;
        if (std <= 0) {
            r.verdict = "ZERO_VARIANCE";
            return r;
        }
        r.sharpe = mean / std;

        // Higher moments for deflation.
        r.skew = skewness(x, mean, std);
        r.kurtosis = kurtosis(x, mean, std);

        // Expected max Sharpe under the null across {@code trialCount} trials
        // (Bailey/LdP eq. 8). The exact formula uses inverse normal CDF; we
        // use the well-known closed-form approximation.
        double expectedMaxSharpe = expectedMaxSharpeUnderNull(trialCount);
        r.expectedMaxSharpe = expectedMaxSharpe;

        double sharpeStd = Math.sqrt(
                (1 - r.skew * r.sharpe + ((r.kurtosis - 1) / 4.0) * r.sharpe * r.sharpe)
                        / (r.n - 1.0));
        double z = (r.sharpe - expectedMaxSharpe) / sharpeStd;
        r.deflatedSharpe = normalCdf(z);

        // Verdict thresholds (Bailey/LdP guidance):
        //  DSR > 0.95 → strong evidence the strategy edge is real
        //  DSR < 0.5  → no statistical edge over the trial-count null
        if (r.deflatedSharpe > 0.95) r.verdict = "EDGE_CONFIRMED";
        else if (r.deflatedSharpe > 0.5) r.verdict = "INCONCLUSIVE";
        else r.verdict = "NO_EDGE";

        return r;
    }

    private static double mean(double[] x) {
        double sum = 0;
        for (double v : x) sum += v;
        return sum / x.length;
    }

    private static double stdev(double[] x, double mean) {
        double s = 0;
        for (double v : x) s += (v - mean) * (v - mean);
        return Math.sqrt(s / (x.length - 1));
    }

    private static double skewness(double[] x, double mean, double std) {
        if (std <= 0) return 0;
        double s = 0;
        for (double v : x) {
            double d = (v - mean) / std;
            s += d * d * d;
        }
        return s / x.length;
    }

    private static double kurtosis(double[] x, double mean, double std) {
        if (std <= 0) return 3;
        double s = 0;
        for (double v : x) {
            double d = (v - mean) / std;
            s += d * d * d * d;
        }
        return s / x.length;
    }

    /**
     * Closed-form approximation for the expected maximum Sharpe across N
     * trials when the true edge is zero. Bailey/LdP eq. 8.
     *
     * <p>{@code E[max SR] ≈ (1 - γ) * Φ⁻¹(1 - 1/N) + γ * Φ⁻¹(1 - 1/(N*e))}
     * with {@code γ = Euler-Mascheroni constant ≈ 0.5772}.
     */
    static double expectedMaxSharpeUnderNull(int N) {
        if (N < 2) return 0;
        double gamma = 0.5772156649;
        double a = normalInvCdf(1.0 - 1.0 / N);
        double b = normalInvCdf(1.0 - 1.0 / (N * Math.E));
        return (1 - gamma) * a + gamma * b;
    }

    /** Standard normal CDF via the Abramowitz &amp; Stegun approximation 26.2.17. */
    static double normalCdf(double z) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
        double d = 0.3989422804 * Math.exp(-0.5 * z * z);
        double prob = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return z >= 0 ? 1 - prob : prob;
    }

    /** Inverse normal CDF — Acklam's algorithm; max abs error ~1e-9. */
    static double normalInvCdf(double p) {
        if (p < 1e-15) return -8;
        if (p > 1 - 1e-15) return 8;
        double a1 = -3.969683028665376e+01, a2 = 2.209460984245205e+02;
        double a3 = -2.759285104469687e+02, a4 = 1.383577518672690e+02;
        double a5 = -3.066479806614716e+01, a6 = 2.506628277459239e+00;
        double b1 = -5.447609879822406e+01, b2 = 1.615858368580409e+02;
        double b3 = -1.556989798598866e+02, b4 = 6.680131188771972e+01;
        double b5 = -1.328068155288572e+01;
        double c1 = -7.784894002430293e-03, c2 = -3.223964580411365e-01;
        double c3 = -2.400758277161838e+00, c4 = -2.549732539343734e+00;
        double c5 = 4.374664141464968e+00, c6 = 2.938163982698783e+00;
        double d1 = 7.784695709041462e-03, d2 = 3.224671290700398e-01;
        double d3 = 2.445134137142996e+00, d4 = 3.754408661907416e+00;
        double pLow = 0.02425;
        if (p < pLow) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c1 * q + c2) * q + c3) * q + c4) * q + c5) * q + c6)
                    / ((((d1 * q + d2) * q + d3) * q + d4) * q + 1);
        }
        if (p <= 1 - pLow) {
            double q = p - 0.5;
            double rr = q * q;
            return (((((a1 * rr + a2) * rr + a3) * rr + a4) * rr + a5) * rr + a6) * q
                    / (((((b1 * rr + b2) * rr + b3) * rr + b4) * rr + b5) * rr + 1);
        }
        double q = Math.sqrt(-2 * Math.log(1 - p));
        return -(((((c1 * q + c2) * q + c3) * q + c4) * q + c5) * q + c6)
                / ((((d1 * q + d2) * q + d3) * q + d4) * q + 1);
    }

    /** Result POJO — public fields so the REST resource can serialize directly. */
    public static class Result {
        public String strategy;
        public int n;
        public double mean;
        public double std;
        public double sharpe;
        public double skew;
        public double kurtosis;
        public double expectedMaxSharpe;
        public double deflatedSharpe;
        public String verdict;
    }

    /**
     * Aggregate report for both strategies. Used by the REST status endpoint.
     */
    public Map<String, Object> report() {
        Result longRes = evaluateLongVol();
        Result shortRes = evaluateShortVol();
        return Map.of(
                "evaluatedAt", java.time.Instant.now().toString(),
                "trialCount", trialCount,
                "lookback", lookbackTrades,
                "strategies", List.of(longRes, shortRes));
    }
}

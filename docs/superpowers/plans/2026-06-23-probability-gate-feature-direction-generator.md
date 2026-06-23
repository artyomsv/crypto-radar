# Probability Gate v3 Feature-Direction Generator — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a parallel shadow probability generator `v3-feature-dir` that derives trade direction from candle-derived technical indicators (via an in-process standardized logistic), running alongside the existing `v2-1to1-flip` control with identical 1:1 geometry, so direction is the only changed variable.

**Architecture:** The single-config hourly scan becomes a loop over CDI `CandidateGenerator` beans. The flip's existing inline logic is extracted into a behavior-preserving `FlipGenerator`; a new `FeatureDirectionGenerator` picks direction from a `DirectionModel` (z-score standardization + logistic regression) trained at startup/6h from real historical 1h candles, with labels computed by walking real forward price. Calibrator and calibration report become tag-aware so each config is measured separately. Everything stays shadow — no orders.

**Tech Stack:** Java 21, Quarkus 3.17, CDI (`@ApplicationScoped`, `@Inject`, `Instance<T>`), Hibernate/Panache, `@Scheduled`, JUnit 5. Build: `mvnd` in `services/signal-service`.

## Global Constraints

- Package: `com.cryptoradar.signal.probability` (tests mirror under `src/test/java/...`).
- 4-space indentation; explicit types over `var`; constructor-or-field `@Inject` matching existing probability-package style (field injection is the established pattern here — match it, do not convert to Spring constructor injection).
- **No-synthetic-data:** training uses only real historical candles and labels from real forward price. No fabricated rows. If `DirectionModel` is untrained, `FeatureDirectionGenerator` returns `Optional.empty()` (candidate skipped) — never a guessed direction.
- **Do not disturb `v2-1to1-flip`:** `FlipGenerator` must emit byte-identical candidates to the current inline logic (same tag `v2-1to1-flip`, direction `invert(sign(overallScore))`, stop `1.5×ATR`, target `1R`). Enforced by a regression test.
- Direction-model features are the **6 candle-derived `TechnicalIndicators`** only (rsi14, bollingerPercentB, macdHistogram, momentum10, realizedVolPct, volumeRatio). `liqImbalance24h` is intentionally excluded — it is a live DB aggregate with no per-timestamp historical reconstruction, so it cannot label historical training rows without fabrication. It remains logged in `features_json`.
- Geometry held at `stop-atr-mult=1.5`, `target-r=1.0` for both generators (one-variable comparison).
- Fail-open everywhere a query/HTTP/LLM call can fail (match existing pattern: log WARN, keep prior fit / skip the unit, never throw out of a `@Scheduled` tick).
- All probability code is shadow — places no orders, changes no execution.

---

## File Structure

**New files (all under `services/signal-service/src/main/java/com/cryptoradar/signal/probability/`):**
- `LogisticRegression.java` — width-parameterized logistic core (train/predict/isTrained).
- `DirectionModel.java` — z-score standardization + `LogisticRegression(6)`; predicts P(LONG hits 1:1 target before stop) from `TechnicalIndicators`.
- `LabelWalker.java` — pure forward-walk → realized status (HIT_TARGET/HIT_STOP/EXPIRED), mirroring the evaluator's stop-first-on-straddle rule.
- `AtrCalculator.java` — pure simple-average ATR (extracted so trainer and scanner share one implementation).
- `DirectionModelTrainer.java` — `@Observes StartupEvent` + `@Scheduled`; builds `(features,label)` rows from historical candles and trains `DirectionModel`.
- `CandidateGenerator.java` — interface (tag/enabled/runLlm/build).
- `DirectionContext.java` — record parameter object passed to generators.
- `FlipGenerator.java` — behavior-preserving extraction of the current inline candidate logic.
- `FeatureDirectionGenerator.java` — feature-model direction generator (tag `v3-feature-dir`).

**Modified files:**
- `LogisticWinModel.java` — delegate to `LogisticRegression(6, 100.0)`, public API unchanged.
- `ProbabilityScanScheduler.java` — iterate `Instance<CandidateGenerator>`; build `DirectionContext` once per symbol; score+persist per generator.
- `ProbabilityCalibrator.java` — tag-aware (`calibrate(String tag, Double raw)`; retrain per enabled generator tag).
- `CalibrationReporter.java` — add `report(String tag)`.
- `CalibrationResource.java` — optional `?tag=` query param.
- `services/signal-service/src/main/resources/application.properties` — generator + direction-model config.
- `db/init/signal-init.sql` — `v10` deployment marker.
- `CLAUDE.md` — document the v3 generator.

**Test files:**
- `src/test/java/com/cryptoradar/signal/probability/ProbabilityGateTest.java` — existing; keep passing (regression guard for the `LogisticWinModel` refactor).
- `src/test/java/com/cryptoradar/signal/probability/DirectionModelTest.java` — new.
- `src/test/java/com/cryptoradar/signal/probability/LabelWalkerTest.java` — new.
- `src/test/java/com/cryptoradar/signal/probability/AtrCalculatorTest.java` — new.
- `src/test/java/com/cryptoradar/signal/probability/GeneratorTest.java` — new (FlipGenerator regression + FeatureDirectionGenerator behavior).

---

### Task 1: Width-parameterized `LogisticRegression` + `LogisticWinModel` refactor

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/LogisticRegression.java`
- Modify: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/LogisticWinModel.java`
- Test: `services/signal-service/src/test/java/com/cryptoradar/signal/probability/ProbabilityGateTest.java` (existing tests are the regression guard)

**Interfaces:**
- Produces: `LogisticRegression(int features, double featureScale)`, `boolean isTrained()`, `double predict(double[] x)` (0.5 if untrained), `void train(double[][] X, int[] y, int epochs, double learningRate, double l2)`, package-static `double sigmoid(double z)`.
- `LogisticWinModel` keeps its public surface: `static final int FEATURES = 6`, `boolean isTrained()`, `double predict(double[])`, `void train(...)`, package-static `double sigmoid(double)`.

- [ ] **Step 1: Write the failing test** (append to `ProbabilityGateTest.java`)

```java
@Test
void logisticRegressionTrainsOnSevenFeatures() {
    // Feature 0 perfectly separates; widths other than 6 must work.
    double[][] x = {
            {3,0,0,0,0,0,0}, {2,0,0,0,0,0,0}, {4,0,0,0,0,0,0},
            {-3,0,0,0,0,0,0}, {-2,0,0,0,0,0,0}, {-4,0,0,0,0,0,0}
    };
    int[] y = {1, 1, 1, 0, 0, 0};
    LogisticRegression model = new LogisticRegression(7, 1.0);
    assertEquals(0.5, model.predict(new double[7]), EPS); // untrained
    assertFalse(model.isTrained());
    model.train(x, y, 2000, 0.5, 0.0);
    assertTrue(model.isTrained());
    assertTrue(model.predict(new double[]{3.5, 0, 0, 0, 0, 0, 0}) > 0.5);
    assertTrue(model.predict(new double[]{-3.5, 0, 0, 0, 0, 0, 0}) < 0.5);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/signal-service && mvnd test -Dtest=ProbabilityGateTest#logisticRegressionTrainsOnSevenFeatures`
Expected: FAIL — `LogisticRegression` does not exist (compile error).

- [ ] **Step 3: Create `LogisticRegression.java`**

```java
package com.cryptoradar.signal.probability;

/**
 * Width-parameterized logistic regression with L2-regularized batch gradient
 * descent. Pure (no framework, no I/O) so the math is unit-testable. Features are
 * divided by {@code featureScale} internally so one learning rate behaves across
 * inputs of similar magnitude; pass {@code 1.0} when callers pre-standardize.
 * Until {@link #train} runs on a non-empty dataset the model is untrained and
 * {@link #predict} returns {@code 0.5} (no information).
 */
public final class LogisticRegression {

    private final int features;
    private final double featureScale;
    private final double[] weights;
    private double bias = 0.0;
    private volatile boolean trained = false;

    public LogisticRegression(int features, double featureScale) {
        if (features <= 0) throw new IllegalArgumentException("features must be positive: " + features);
        if (featureScale == 0) throw new IllegalArgumentException("featureScale must be non-zero");
        this.features = features;
        this.featureScale = featureScale;
        this.weights = new double[features];
    }

    public boolean isTrained() {
        return trained;
    }

    static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    public double predict(double[] x) {
        if (!trained) return 0.5;
        return sigmoid(dot(weights, bias, x));
    }

    public void train(double[][] X, int[] y, int epochs, double learningRate, double l2) {
        if (X.length == 0 || X.length != y.length) return;
        double[] w = new double[features];
        double b = 0.0;
        int n = X.length;
        for (int epoch = 0; epoch < epochs; epoch++) {
            double[] gradW = new double[features];
            double gradB = 0.0;
            for (int i = 0; i < n; i++) {
                double error = sigmoid(dot(w, b, X[i])) - y[i];
                for (int j = 0; j < features; j++) {
                    gradW[j] += error * (X[i][j] / featureScale);
                }
                gradB += error;
            }
            for (int j = 0; j < features; j++) {
                w[j] -= learningRate * (gradW[j] / n + l2 * w[j]);
            }
            b -= learningRate * (gradB / n);
        }
        System.arraycopy(w, 0, weights, 0, features);
        this.bias = b;
        this.trained = true;
    }

    private double dot(double[] w, double b, double[] x) {
        double z = b;
        for (int i = 0; i < features; i++) {
            z += w[i] * (x[i] / featureScale);
        }
        return z;
    }
}
```

- [ ] **Step 4: Refactor `LogisticWinModel.java` to delegate**

Replace the entire class body with a delegating wrapper (public API preserved so existing tests and `WinProbabilityEstimator` are unaffected):

```java
package com.cryptoradar.signal.probability;

/**
 * Win-probability model over the six dimension scores (≈−100..+100). Thin wrapper
 * over {@link LogisticRegression} with a feature scale of 100 so the raw scores
 * train well; the generic core holds the math. Untrained → {@link #predict}
 * returns 0.5 so a cold start can never masquerade as a confident probability.
 */
public final class LogisticWinModel {

    public static final int FEATURES = 6;
    private static final double FEATURE_SCALE = 100.0;

    private final LogisticRegression core = new LogisticRegression(FEATURES, FEATURE_SCALE);

    public boolean isTrained() {
        return core.isTrained();
    }

    static double sigmoid(double z) {
        return LogisticRegression.sigmoid(z);
    }

    public double predict(double[] features) {
        return core.predict(features);
    }

    public void train(double[][] X, int[] y, int epochs, double learningRate, double l2) {
        core.train(X, y, epochs, learningRate, l2);
    }
}
```

- [ ] **Step 5: Run the full probability test class to verify the refactor + new test pass**

Run: `cd services/signal-service && mvnd test -Dtest=ProbabilityGateTest`
Expected: PASS — all existing `LogisticWinModel`/sigmoid/calibration/indicator tests plus the new `logisticRegressionTrainsOnSevenFeatures`.

- [ ] **Step 6: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/probability/LogisticRegression.java \
        services/signal-service/src/main/java/com/cryptoradar/signal/probability/LogisticWinModel.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/probability/ProbabilityGateTest.java
git commit -m "refactor(signal): extract width-parameterized LogisticRegression core"
```

---

### Task 2: `DirectionModel` (standardization + logistic over technical indicators)

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/DirectionModel.java`
- Test: `services/signal-service/src/test/java/com/cryptoradar/signal/probability/DirectionModelTest.java`

**Interfaces:**
- Consumes: `LogisticRegression` (Task 1), `TechnicalIndicators` (existing record).
- Produces:
  - `static final int FEATURES = 6`
  - `static double[] toVector(TechnicalIndicators ind)` — fixed order `{rsi14, bollingerPercentB, macdHistogram, momentum10, realizedVolPct, volumeRatio}`.
  - `void train(double[][] rawRows, int[] labels)` — fits the standardizer then the logistic.
  - `double longWinProbability(double[] rawFeatures)` — 0.5 if untrained.
  - `boolean isTrained()`.

- [ ] **Step 1: Write the failing test** (`DirectionModelTest.java`)

```java
package com.cryptoradar.signal.probability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectionModelTest {

    private static final double EPS = 1e-9;

    @Test
    void untrainedReturnsHalf() {
        DirectionModel model = new DirectionModel();
        assertFalse(model.isTrained());
        assertEquals(0.5, model.longWinProbability(new double[DirectionModel.FEATURES]), EPS);
    }

    @Test
    void toVectorPreservesIndicatorOrder() {
        TechnicalIndicators ind = new TechnicalIndicators(70.0, 0.9, 1.5, 0.05, 2.0, 1.3);
        double[] v = DirectionModel.toVector(ind);
        assertArrayEquals(new double[]{70.0, 0.9, 1.5, 0.05, 2.0, 1.3}, v, EPS);
    }

    @Test
    void trainsAcrossWildlyDifferentFeatureScales() {
        // Feature 0 (RSI-like 0..100) separates; feature 3 (momentum-like ~0.01)
        // is noise. Standardization must let the big-scale signal train.
        double[][] x = {
                {80, 0.5, 0, 0.001, 1, 1}, {75, 0.5, 0, -0.001, 1, 1}, {90, 0.5, 0, 0.002, 1, 1},
                {20, 0.5, 0, 0.001, 1, 1}, {25, 0.5, 0, -0.001, 1, 1}, {10, 0.5, 0, 0.002, 1, 1}
        };
        int[] y = {1, 1, 1, 0, 0, 0};
        DirectionModel model = new DirectionModel();
        model.train(x, y, 4000, 0.5, 0.0);
        assertTrue(model.isTrained());
        assertTrue(model.longWinProbability(new double[]{85, 0.5, 0, 0.0, 1, 1}) > 0.5);
        assertTrue(model.longWinProbability(new double[]{15, 0.5, 0, 0.0, 1, 1}) < 0.5);
    }

    @Test
    void trainIsNoOpOnEmptyData() {
        DirectionModel model = new DirectionModel();
        model.train(new double[0][], new int[0], 100, 0.3, 0.0);
        assertFalse(model.isTrained());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/signal-service && mvnd test -Dtest=DirectionModelTest`
Expected: FAIL — `DirectionModel` does not exist.

- [ ] **Step 3: Create `DirectionModel.java`**

```java
package com.cryptoradar.signal.probability;

/**
 * Predicts P(a LONG 1:1 trade hits its target before its stop) from the six
 * candle-derived {@link TechnicalIndicators}. Because those features live on
 * wildly different scales (RSI 0..100, %B ~0..1, momentum ~0.01), this owns
 * per-feature z-score standardization fitted at train time and reused at predict
 * time, then defers the fit to a {@link LogisticRegression}. Pure — no I/O — so
 * the training math is unit-testable; the CDI trainer feeds it real history.
 *
 * <p>Untrained → {@link #longWinProbability} returns 0.5 (no information), so the
 * generator that consumes it can skip rather than guess a direction.
 */
public final class DirectionModel {

    public static final int FEATURES = 6;

    private final LogisticRegression core = new LogisticRegression(FEATURES, 1.0);
    private volatile double[] mean;
    private volatile double[] std;

    public boolean isTrained() {
        return core.isTrained();
    }

    /** Fixed feature order — must match the trainer and any caller building a row. */
    public static double[] toVector(TechnicalIndicators ind) {
        return new double[]{
                ind.rsi14(), ind.bollingerPercentB(), ind.macdHistogram(),
                ind.momentum10(), ind.realizedVolPct(), ind.volumeRatio()
        };
    }

    public void train(double[][] rawRows, int[] labels, int epochs, double learningRate, double l2) {
        if (rawRows.length == 0 || rawRows.length != labels.length) return;
        double[] m = new double[FEATURES];
        double[] s = new double[FEATURES];
        for (double[] row : rawRows) {
            for (int j = 0; j < FEATURES; j++) m[j] += row[j];
        }
        for (int j = 0; j < FEATURES; j++) m[j] /= rawRows.length;
        for (double[] row : rawRows) {
            for (int j = 0; j < FEATURES; j++) {
                double d = row[j] - m[j];
                s[j] += d * d;
            }
        }
        for (int j = 0; j < FEATURES; j++) {
            s[j] = Math.sqrt(s[j] / rawRows.length);
            if (s[j] == 0) s[j] = 1.0; // constant feature → no scaling, avoids div-by-zero
        }
        double[][] standardized = new double[rawRows.length][FEATURES];
        for (int i = 0; i < rawRows.length; i++) {
            standardized[i] = standardize(rawRows[i], m, s);
        }
        this.mean = m;
        this.std = s;
        core.train(standardized, labels, epochs, learningRate, l2);
    }

    public double longWinProbability(double[] rawFeatures) {
        if (!core.isTrained()) return 0.5;
        return core.predict(standardize(rawFeatures, mean, std));
    }

    private static double[] standardize(double[] raw, double[] mean, double[] std) {
        double[] out = new double[FEATURES];
        for (int j = 0; j < FEATURES; j++) {
            out[j] = (raw[j] - mean[j]) / std[j];
        }
        return out;
    }
}
```

Note: the test calls `train(x, y, epochs, lr, l2)` — the 5-arg signature above. Update the Task-2 test Step-1 `model.train(x, y, 4000, 0.5, 0.0)` already matches; the `trainIsNoOpOnEmptyData` call `train(new double[0][], new int[0], 100, 0.3, 0.0)` matches too.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/signal-service && mvnd test -Dtest=DirectionModelTest`
Expected: PASS — all four tests.

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/probability/DirectionModel.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/probability/DirectionModelTest.java
git commit -m "feat(signal): DirectionModel — standardized logistic over indicators"
```

---

### Task 3: `LabelWalker` pure forward-eval helper

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/LabelWalker.java`
- Test: `services/signal-service/src/test/java/com/cryptoradar/signal/probability/LabelWalkerTest.java`

**Interfaces:**
- Consumes: `CandleBar` (existing), `ProbabilityCandidate` status constants.
- Produces: `static String resolve(List<CandleBar> forwardBars, double entry, double stop, double target, boolean isLong)` → one of `ProbabilityCandidate.STATUS_HIT_TARGET` / `STATUS_HIT_STOP` / `STATUS_EXPIRED`. Stop-first when a single bar straddles both (mirrors `ShadowOutcomeEvaluator.closeIfResolved`).

- [ ] **Step 1: Write the failing test** (`LabelWalkerTest.java`)

```java
package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabelWalkerTest {

    private static CandleBar bar(double high, double low) {
        return new CandleBar(Instant.parse("2026-01-01T00:00:00Z"), low, high, low, low, 0.0);
    }

    @Test
    void longHitsTargetBeforeStop() {
        List<CandleBar> fwd = List.of(bar(101, 99), bar(106, 104)); // entry 100, target 105
        assertEquals(ProbabilityCandidate.STATUS_HIT_TARGET,
                LabelWalker.resolve(fwd, 100, 95, 105, true));
    }

    @Test
    void longHitsStopBeforeTarget() {
        List<CandleBar> fwd = List.of(bar(101, 94)); // low 94 <= stop 95
        assertEquals(ProbabilityCandidate.STATUS_HIT_STOP,
                LabelWalker.resolve(fwd, 100, 95, 105, true));
    }

    @Test
    void straddleBarCountsAsStopForLong() {
        List<CandleBar> fwd = List.of(bar(106, 94)); // both stop(95) and target(105) inside
        assertEquals(ProbabilityCandidate.STATUS_HIT_STOP,
                LabelWalker.resolve(fwd, 100, 95, 105, true));
    }

    @Test
    void neitherHitWithinWindowExpires() {
        List<CandleBar> fwd = List.of(bar(101, 99), bar(102, 98));
        assertEquals(ProbabilityCandidate.STATUS_EXPIRED,
                LabelWalker.resolve(fwd, 100, 95, 105, true));
    }

    @Test
    void shortMirrorsLong() {
        List<CandleBar> fwd = List.of(bar(101, 94)); // short entry 100, target 95 reached at low 94
        assertEquals(ProbabilityCandidate.STATUS_HIT_TARGET,
                LabelWalker.resolve(fwd, 100, 105, 95, false));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/signal-service && mvnd test -Dtest=LabelWalkerTest`
Expected: FAIL — `LabelWalker` does not exist.

- [ ] **Step 3: Create `LabelWalker.java`**

```java
package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;

import java.util.List;

/**
 * Walks already-sliced forward bars and returns the realized status of a 1:1
 * shadow trade — the training label for {@link DirectionModel}. Encodes the same
 * stop-first-on-straddle rule as {@link ShadowOutcomeEvaluator} so model labels
 * and live shadow outcomes mean the same thing. Pure — caller supplies the
 * forward slice (bars strictly after entry), so there is no look-ahead here.
 */
public final class LabelWalker {

    private LabelWalker() {}

    public static String resolve(List<CandleBar> forwardBars, double entry,
                                 double stop, double target, boolean isLong) {
        for (CandleBar bar : forwardBars) {
            if (isLong) {
                if (bar.low() <= stop) return ProbabilityCandidate.STATUS_HIT_STOP;
                if (bar.high() >= target) return ProbabilityCandidate.STATUS_HIT_TARGET;
            } else {
                if (bar.high() >= stop) return ProbabilityCandidate.STATUS_HIT_STOP;
                if (bar.low() <= target) return ProbabilityCandidate.STATUS_HIT_TARGET;
            }
        }
        return ProbabilityCandidate.STATUS_EXPIRED;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/signal-service && mvnd test -Dtest=LabelWalkerTest`
Expected: PASS — all five tests.

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/probability/LabelWalker.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/probability/LabelWalkerTest.java
git commit -m "feat(signal): LabelWalker — pure forward-eval label for direction model"
```

---

### Task 4: `AtrCalculator` pure helper

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/AtrCalculator.java`
- Test: `services/signal-service/src/test/java/com/cryptoradar/signal/probability/AtrCalculatorTest.java`

**Interfaces:**
- Consumes: `CandleBar`.
- Produces: `static double atr(List<CandleBar> bars, int period)` — simple-average true range over the last `period` bars; returns 0 when fewer than `period+1` bars. Identical math to `ProbabilityScanScheduler.atr` (which Task 8 replaces with a call to this).

- [ ] **Step 1: Write the failing test** (`AtrCalculatorTest.java`)

```java
package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtrCalculatorTest {

    @Test
    void returnsZeroBelowMinBars() {
        List<CandleBar> bars = constantRangeBars(3, 10, 8); // need period+1
        assertEquals(0.0, AtrCalculator.atr(bars, 14), 1e-9);
    }

    @Test
    void averagesTrueRangeOverPeriod() {
        // Every bar spans high-low = 2, no gaps → ATR = 2.
        List<CandleBar> bars = constantRangeBars(20, 10, 8);
        assertEquals(2.0, AtrCalculator.atr(bars, 14), 1e-9);
    }

    private static List<CandleBar> constantRangeBars(int n, double high, double low) {
        List<CandleBar> bars = new ArrayList<>();
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < n; i++) {
            bars.add(new CandleBar(t, low, high, low, low, 0.0)); // close=low keeps prev-close inside range
            t = t.plusSeconds(3600);
        }
        return bars;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/signal-service && mvnd test -Dtest=AtrCalculatorTest`
Expected: FAIL — `AtrCalculator` does not exist.

- [ ] **Step 3: Create `AtrCalculator.java`**

```java
package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;

import java.util.List;

/**
 * Simple-average ATR over the last {@code period} true ranges. Pure helper shared
 * by the hourly scanner and the direction-model trainer so both derive geometry
 * from identical volatility math. Returns 0 when there are too few bars.
 */
public final class AtrCalculator {

    private AtrCalculator() {}

    public static double atr(List<CandleBar> bars, int period) {
        if (bars.size() < period + 1) return 0.0;
        double sum = 0;
        int count = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            CandleBar cur = bars.get(i);
            CandleBar prev = bars.get(i - 1);
            double tr = Math.max(cur.high() - cur.low(),
                    Math.max(Math.abs(cur.high() - prev.close()), Math.abs(cur.low() - prev.close())));
            sum += tr;
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/signal-service && mvnd test -Dtest=AtrCalculatorTest`
Expected: PASS — both tests.

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/probability/AtrCalculator.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/probability/AtrCalculatorTest.java
git commit -m "feat(signal): extract shared AtrCalculator"
```

---

### Task 5: `DirectionModelTrainer` (train from real historical candles)

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/DirectionModelTrainer.java`
- Test: covered by `DirectionModelTest` (model math) + `GeneratorTest` (Task 7, consumes the trained model). The trainer itself is an I/O orchestrator; its pure pieces (`DirectionModel`, `LabelWalker`, `AtrCalculator`, `TechnicalIndicators`) are already tested. No new unit test for the scheduled wiring — verified live in Task 11.

**Interfaces:**
- Consumes: `CandleClient.fetchRecent(String,String,int)`, `SignalService.getSignalOverview().getSignals()` (for the symbol list), `TechnicalIndicators.compute(List)`, `AtrCalculator.atr`, `CandidateBuilder.build`, `LabelWalker.resolve`, `DirectionModel`.
- Produces: a CDI singleton holding the trained `DirectionModel`, exposed via `DirectionModel model()` for injection by `FeatureDirectionGenerator`.

- [ ] **Step 1: Create `DirectionModelTrainer.java`**

```java
package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.TradingSignal;
import com.cryptoradar.signal.service.CandleClient;
import com.cryptoradar.signal.service.SignalService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Trains {@link DirectionModel} from real historical 1h candles. For each tracked
 * symbol it slides a window: at bar t it computes indicators from bars ≤ t and the
 * label from bars > t (did a LONG 1:1 trade hit target before stop within the hold
 * window). Strict look-ahead discipline — features never read bars after t.
 *
 * <p>Real data only: candles are real observations and labels come from real
 * forward price. No fabricated rows. Fail-open: any error keeps the prior fit.
 */
@ApplicationScoped
public class DirectionModelTrainer {

    private static final Logger LOG = Logger.getLogger(DirectionModelTrainer.class);
    private static final String INTERVAL = "1h";
    private static final int ATR_PERIOD = 14;
    private static final int MIN_FEATURE_BARS = 35; // TechnicalIndicators MIN_BARS
    private static final int EPOCHS = 4000;
    private static final double LEARNING_RATE = 0.3;
    private static final double L2 = 0.001;

    @Inject CandleClient candleClient;
    @Inject SignalService signalService;

    @ConfigProperty(name = "probability.direction-model.lookback-days", defaultValue = "60")
    int lookbackDays;
    @ConfigProperty(name = "probability.direction-model.hold-hours", defaultValue = "72")
    int holdHours;

    private final DirectionModel model = new DirectionModel();

    public DirectionModel model() {
        return model;
    }

    void onStart(@Observes StartupEvent event) {
        retrain();
    }

    @Scheduled(every = "{probability.direction-model.retrain-interval:6h}", delayed = "210s",
            identity = "direction-model-retrain")
    void scheduledRetrain() {
        retrain();
    }

    void retrain() {
        try {
            List<double[]> rows = new ArrayList<>();
            List<Integer> labels = new ArrayList<>();
            for (TradingSignal signal : signalService.getSignalOverview().getSignals()) {
                accumulate(signal.getSymbol(), rows, labels);
            }
            if (rows.isEmpty()) {
                LOG.warn("Direction-model retrain skipped — no training rows");
                return;
            }
            double[][] x = rows.toArray(new double[0][]);
            int[] y = labels.stream().mapToInt(Integer::intValue).toArray();
            model.train(x, y, EPOCHS, LEARNING_RATE, L2);
            LOG.infof("Direction model trained on %d rows (%d long-wins)",
                    y.length, java.util.Arrays.stream(y).sum());
        } catch (RuntimeException e) {
            LOG.warnf("Direction-model retrain failed, keeping prior fit: %s", e.getMessage());
        }
    }

    private void accumulate(String symbol, List<double[]> rows, List<Integer> labels) {
        List<CandleBar> bars = candleClient.fetchRecent(symbol, INTERVAL, lookbackDays * 24);
        int lastEntryIdx = bars.size() - holdHours - 1;
        for (int t = MIN_FEATURE_BARS - 1; t <= lastEntryIdx; t++) {
            List<CandleBar> window = bars.subList(0, t + 1);
            TechnicalIndicators ind = TechnicalIndicators.compute(window);
            if (ind == null) continue;
            double atr = AtrCalculator.atr(window, ATR_PERIOD);
            double entry = bars.get(t).close();
            if (atr <= 0 || entry <= 0) continue;
            Candidate c = CandidateBuilder.build(Candidate.LONG, entry, atr, 1.5, 1.0);
            List<CandleBar> forward = bars.subList(t + 1, Math.min(t + 1 + holdHours, bars.size()));
            String status = LabelWalker.resolve(forward, entry, c.stop(), c.target(), true);
            rows.add(DirectionModel.toVector(ind));
            labels.add(ProbabilityCandidate.STATUS_HIT_TARGET.equals(status) ? 1 : 0);
        }
    }
}
```

- [ ] **Step 2: Compile to verify it wires**

Run: `cd services/signal-service && mvnd compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run the probability test suite to confirm nothing regressed**

Run: `cd services/signal-service && mvnd test -Dtest=DirectionModelTest,LabelWalkerTest,AtrCalculatorTest,ProbabilityGateTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/probability/DirectionModelTrainer.java
git commit -m "feat(signal): DirectionModelTrainer — train from historical candles"
```

---

### Task 6: `CandidateGenerator` interface, `DirectionContext`, `FlipGenerator`

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/CandidateGenerator.java`
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/DirectionContext.java`
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/FlipGenerator.java`
- Test: `services/signal-service/src/test/java/com/cryptoradar/signal/probability/GeneratorTest.java`

**Interfaces:**
- Produces:
  - `interface CandidateGenerator { String tag(); boolean enabled(); boolean runLlm(); Optional<Candidate> build(DirectionContext ctx); }`
  - `record DirectionContext(TradingSignal signal, List<CandleBar> bars, double atr, double entry, TechnicalIndicators indicators, Map<String,Double> dimScores)`
  - `FlipGenerator` (CDI bean) — `tag()="v2-1to1-flip"`, direction `invert(sign(overallScore))`, `CandidateBuilder.build(dir, entry, atr, 1.5, 1.0)`.

- [ ] **Step 1: Write the failing test** (`GeneratorTest.java`)

```java
package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.TradingSignal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorTest {

    private static final double EPS = 1e-9;

    private static DirectionContext ctx(double overallScore) {
        TradingSignal signal = new TradingSignal();
        signal.setSymbol("BTCUSDT");
        signal.setOverallScore(overallScore);
        TechnicalIndicators ind = new TechnicalIndicators(60, 0.6, 0.2, 0.01, 1.0, 1.1);
        return new DirectionContext(signal, List.<CandleBar>of(), 4.0, 100.0, ind, Map.of());
    }

    @Test
    void flipInvertsBullishOverallScoreToShort() {
        FlipGenerator gen = new FlipGenerator();
        gen.invertDirection = true;
        gen.stopAtrMult = 1.5;
        gen.targetR = 1.0;
        gen.tag = "v2-1to1-flip";
        gen.enabled = true;
        gen.runLlm = true;

        Optional<Candidate> c = gen.build(ctx(50.0)); // bullish → inverted → SHORT
        assertTrue(c.isPresent());
        assertEquals(Candidate.SHORT, c.get().direction());
        // risk = max(1.5*4, 0.015*100) = 6 → SHORT stop above, target below at 1:1
        assertEquals(106.0, c.get().stop(), EPS);
        assertEquals(94.0, c.get().target(), EPS);
        assertEquals("v2-1to1-flip", gen.tag());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/signal-service && mvnd test -Dtest=GeneratorTest`
Expected: FAIL — `FlipGenerator` / `DirectionContext` / `CandidateGenerator` do not exist.

- [ ] **Step 3: Create the interface, context, and FlipGenerator**

`CandidateGenerator.java`:

```java
package com.cryptoradar.signal.probability;

import java.util.Optional;

/**
 * Produces one shadow trade candidate per symbol per scan for a single config
 * (geometry + direction policy), identified by {@link #tag()}. The scanner scores
 * and persists whatever each enabled generator builds. Adding a new experiment is
 * a new bean — no scanner change.
 */
public interface CandidateGenerator {

    String tag();

    boolean enabled();

    boolean runLlm();

    /** The candidate for this context, or empty to skip (e.g. model untrained). */
    Optional<Candidate> build(DirectionContext ctx);
}
```

`DirectionContext.java`:

```java
package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.TradingSignal;

import java.util.List;
import java.util.Map;

/**
 * Everything a {@link CandidateGenerator} needs to choose a direction and build
 * geometry for one symbol at scan time. {@code indicators} is null when there are
 * too few candles for the slowest indicator. Computed once per symbol and shared
 * across all generators in the scan.
 */
public record DirectionContext(
        TradingSignal signal,
        List<CandleBar> bars,
        double atr,
        double entry,
        TechnicalIndicators indicators,
        Map<String, Double> dimScores) {}
```

`FlipGenerator.java`:

```java
package com.cryptoradar.signal.probability;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * The Phase 2 control: direction = invert(sign(overallScore)), 1:1 geometry. A
 * behavior-preserving extraction of the original inline scan logic — the running
 * v2-1to1-flip experiment must not change shape. Geometry/direction knobs stay on
 * their original property names so existing config keeps driving it.
 */
@ApplicationScoped
public class FlipGenerator implements CandidateGenerator {

    @ConfigProperty(name = "probability.config-tag", defaultValue = "v2-1to1-flip")
    String tag;
    @ConfigProperty(name = "probability.geometry.stop-atr-mult", defaultValue = "1.5")
    double stopAtrMult;
    @ConfigProperty(name = "probability.geometry.target-r", defaultValue = "1.0")
    double targetR;
    @ConfigProperty(name = "probability.direction.invert", defaultValue = "true")
    boolean invertDirection;
    @ConfigProperty(name = "probability.generator.flip.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "probability.generator.flip.run-llm", defaultValue = "true")
    boolean runLlm;

    @Override public String tag() { return tag; }
    @Override public boolean enabled() { return enabled; }
    @Override public boolean runLlm() { return runLlm; }

    @Override
    public Optional<Candidate> build(DirectionContext ctx) {
        boolean bullish = ctx.signal().getOverallScore() >= 0;
        if (invertDirection) bullish = !bullish;
        String direction = bullish ? Candidate.LONG : Candidate.SHORT;
        return Optional.of(CandidateBuilder.build(direction, ctx.entry(), ctx.atr(), stopAtrMult, targetR));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/signal-service && mvnd test -Dtest=GeneratorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/probability/CandidateGenerator.java \
        services/signal-service/src/main/java/com/cryptoradar/signal/probability/DirectionContext.java \
        services/signal-service/src/main/java/com/cryptoradar/signal/probability/FlipGenerator.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/probability/GeneratorTest.java
git commit -m "feat(signal): CandidateGenerator interface + FlipGenerator extraction"
```

---

### Task 7: `FeatureDirectionGenerator`

**Files:**
- Create: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/FeatureDirectionGenerator.java`
- Test: `services/signal-service/src/test/java/com/cryptoradar/signal/probability/GeneratorTest.java` (append)

**Interfaces:**
- Consumes: `DirectionModelTrainer.model()` → `DirectionModel`, `DirectionContext`, `CandidateBuilder`.
- Produces: `FeatureDirectionGenerator` (CDI bean), `tag()="v3-feature-dir"`. Returns `empty()` when indicators are null OR the model is untrained.

- [ ] **Step 1: Write the failing test** (append to `GeneratorTest.java`)

```java
    @Test
    void featureDirGeneratorSkipsWhenModelUntrained() {
        FeatureDirectionGenerator gen = new FeatureDirectionGenerator();
        gen.trainer = new DirectionModelTrainer(); // model().isTrained() == false
        gen.stopAtrMult = 1.5;
        gen.targetR = 1.0;
        gen.tag = "v3-feature-dir";
        gen.enabled = true;
        gen.runLlm = true;
        assertTrue(gen.build(ctx(50.0)).isEmpty());
    }

    @Test
    void featureDirGeneratorSkipsWhenIndicatorsNull() {
        FeatureDirectionGenerator gen = new FeatureDirectionGenerator();
        gen.trainer = new DirectionModelTrainer();
        gen.stopAtrMult = 1.5;
        gen.targetR = 1.0;
        gen.tag = "v3-feature-dir";
        gen.enabled = true;
        gen.runLlm = true;
        TradingSignal s = new TradingSignal();
        s.setSymbol("BTCUSDT");
        s.setOverallScore(10);
        DirectionContext noInd = new DirectionContext(s, List.<CandleBar>of(), 4.0, 100.0, null, Map.of());
        assertTrue(gen.build(noInd).isEmpty());
    }
```

Note: `gen.trainer` is package-visible field injection (matches the probability package's `@Inject` field style); the test sets it directly. `DirectionModelTrainer`'s `model()` returns an untrained `DirectionModel` until `retrain()` runs, so no candle I/O happens in this unit test.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd services/signal-service && mvnd test -Dtest=GeneratorTest`
Expected: FAIL — `FeatureDirectionGenerator` does not exist.

- [ ] **Step 3: Create `FeatureDirectionGenerator.java`**

```java
package com.cryptoradar.signal.probability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Direction comes from {@link DirectionModel} over candle-derived indicators
 * rather than sign(overallScore); 1:1 geometry held identical to the flip so the
 * only changed variable is the direction source. Skips (returns empty) when the
 * indicators are unavailable or the model has not trained — an honest absence,
 * never a guessed direction.
 */
@ApplicationScoped
public class FeatureDirectionGenerator implements CandidateGenerator {

    @Inject DirectionModelTrainer trainer;

    @ConfigProperty(name = "probability.generator.feature-dir.tag", defaultValue = "v3-feature-dir")
    String tag;
    @ConfigProperty(name = "probability.generator.feature-dir.stop-atr-mult", defaultValue = "1.5")
    double stopAtrMult;
    @ConfigProperty(name = "probability.generator.feature-dir.target-r", defaultValue = "1.0")
    double targetR;
    @ConfigProperty(name = "probability.generator.feature-dir.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "probability.generator.feature-dir.run-llm", defaultValue = "true")
    boolean runLlm;

    @Override public String tag() { return tag; }
    @Override public boolean enabled() { return enabled; }
    @Override public boolean runLlm() { return runLlm; }

    @Override
    public Optional<Candidate> build(DirectionContext ctx) {
        if (ctx.indicators() == null) return Optional.empty();
        DirectionModel model = trainer.model();
        if (!model.isTrained()) return Optional.empty();
        double pLong = model.longWinProbability(DirectionModel.toVector(ctx.indicators()));
        String direction = pLong >= 0.5 ? Candidate.LONG : Candidate.SHORT;
        return Optional.of(CandidateBuilder.build(direction, ctx.entry(), ctx.atr(), stopAtrMult, targetR));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd services/signal-service && mvnd test -Dtest=GeneratorTest`
Expected: PASS — flip + both feature-dir tests.

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/probability/FeatureDirectionGenerator.java \
        services/signal-service/src/test/java/com/cryptoradar/signal/probability/GeneratorTest.java
git commit -m "feat(signal): FeatureDirectionGenerator — model-derived direction"
```

---

### Task 8: Refactor `ProbabilityScanScheduler` to iterate generators

**Files:**
- Modify: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/ProbabilityScanScheduler.java`

**Interfaces:**
- Consumes: `Instance<CandidateGenerator>`, `DirectionContext`, `AtrCalculator.atr`, `TechnicalIndicators.compute`, tag-aware `ProbabilityCalibrator.calibrate(String, Double)` (Task 9 — land Task 9 before this compiles clean, or stub then), `WinProbabilityEstimator`, `FeatureAssembler`, `ProbabilityCandidateRepository`.
- Produces: per scan, one persisted `ProbabilityCandidate` per (symbol × enabled generator), tagged by `generator.tag()`.

> **Ordering note:** Task 9 changes `ProbabilityCalibrator.calibrate` to take a tag. Implement Task 9 immediately before this task (or in the same session) so this file compiles. The persist call below already uses the tag-aware signature.

- [ ] **Step 1: Replace the scan/scanSymbol/persist logic**

Replace the fields block (lines ~37–56) and the `scan()`/`scanSymbol(...)`/`persist(...)` methods with the generator-iterating version. Keep `toJson`, `dimensionScores`, `buildPrompt` as-is; delete the private `atr(...)` method (now `AtrCalculator`). New body:

```java
    @Inject SignalService signalService;
    @Inject CandleClient candleClient;
    @Inject WinProbabilityEstimator estimator;
    @Inject ProbabilityCalibrator calibrator;
    @Inject ProbabilityCandidateRepository repository;
    @Inject FeatureAssembler featureAssembler;
    @Inject ObjectMapper mapper;
    @Inject jakarta.enterprise.inject.Instance<CandidateGenerator> generators;

    @Scheduled(every = "{probability.scan.interval:1h}", delayed = "90s", identity = "probability-scan")
    void scan() {
        List<TradingSignal> signals = signalService.getSignalOverview().getSignals();
        int persisted = 0;
        for (TradingSignal signal : signals) {
            try {
                persisted += scanSymbol(signal);
            } catch (RuntimeException e) {
                LOG.warnf("Probability scan failed for %s: %s", signal.getSymbol(), e.getMessage());
            }
        }
        LOG.infof("Probability scan complete — %d candidates persisted across %d symbols",
                persisted, signals.size());
    }

    int scanSymbol(TradingSignal signal) {
        String symbol = signal.getSymbol();
        List<CandleBar> bars = candleClient.fetchRecent(symbol, CANDLE_INTERVAL, CANDLE_LIMIT);
        if (bars.size() < ATR_PERIOD + 1) {
            LOG.debugf("Skipping %s — insufficient candles (%d)", symbol, bars.size());
            return 0;
        }
        double atr = AtrCalculator.atr(bars, ATR_PERIOD);
        double entry = bars.get(bars.size() - 1).close();
        if (atr <= 0 || entry <= 0) return 0;

        Map<String, Double> dimScores = dimensionScores(signal);
        TechnicalIndicators indicators = TechnicalIndicators.compute(bars);
        DirectionContext ctx = new DirectionContext(signal, bars, atr, entry, indicators, dimScores);

        int persisted = 0;
        for (CandidateGenerator generator : generators) {
            if (!generator.enabled()) continue;
            try {
                Optional<Candidate> candidate = generator.build(ctx);
                if (candidate.isEmpty()) continue;
                persistScored(generator, ctx, candidate.get());
                persisted++;
            } catch (RuntimeException e) {
                LOG.warnf("Generator %s failed for %s: %s", generator.tag(), symbol, e.getMessage());
            }
        }
        return persisted;
    }

    @Transactional
    void persistScored(CandidateGenerator generator, DirectionContext ctx, Candidate candidate) {
        TradingSignal signal = ctx.signal();
        String symbol = signal.getSymbol();
        double statsProb = estimator.statsProbability(ctx.dimScores());
        Optional<GeminiProbabilityClient.LlmEstimate> llm = generator.runLlm()
                ? estimator.llmProbability(buildPrompt(symbol, candidate, signal, ctx.dimScores()))
                : Optional.empty();
        Double llmProb = llm.map(GeminiProbabilityClient.LlmEstimate::probability).orElse(null);
        Double calibratedProb = calibrator.calibrate(generator.tag(), llmProb);
        String featuresJson = toJson(featureAssembler.assemble(signal, candidate, ctx.bars(), ctx.dimScores()));

        ProbabilityCandidate row = new ProbabilityCandidate();
        row.scannedAt = Instant.now();
        row.symbol = symbol;
        row.direction = candidate.direction();
        row.entryPrice = candidate.entry();
        row.stopPrice = candidate.stop();
        row.targetPrice = candidate.target();
        row.atr = candidate.atr();
        row.riskReward = candidate.riskReward();
        row.statsProb = statsProb;
        row.llmProb = llmProb;
        row.llmReasoning = llm.map(GeminiProbabilityClient.LlmEstimate::reasoning).orElse(null);
        row.calibratedProb = calibratedProb;
        row.configTag = generator.tag();
        row.featuresJson = featuresJson;
        row.status = ProbabilityCandidate.STATUS_PENDING;
        repository.persist(row);
    }
```

Remove the now-unused `@ConfigProperty` fields (`stopAtrMult`, `targetR`, `invertDirection`, `configTag`) and the old private `atr(List<CandleBar>)` method. Keep imports for `Optional`, `Instant`, `Map`, `List`, `Transactional`. Add import `com.cryptoradar.signal.model.TechnicalIndicators`? — `TechnicalIndicators` is in the `probability` package (same package), no import needed. `Candidate` is same package. Add `import jakarta.transaction.Transactional;` (already present).

- [ ] **Step 2: Compile**

Run: `cd services/signal-service && mvnd compile`
Expected: BUILD SUCCESS (requires Task 9's `calibrate(String, Double)` to exist).

- [ ] **Step 3: Run the probability test suite**

Run: `cd services/signal-service && mvnd test -Dtest=GeneratorTest,DirectionModelTest,LabelWalkerTest,AtrCalculatorTest,ProbabilityGateTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/probability/ProbabilityScanScheduler.java
git commit -m "refactor(signal): scan iterates CandidateGenerator beans"
```

---

### Task 9: Make `ProbabilityCalibrator` tag-aware

**Files:**
- Modify: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/ProbabilityCalibrator.java`

**Interfaces:**
- Consumes: `Instance<CandidateGenerator>` (to learn the set of tags), `ProbabilityCandidateRepository.findClosedWithLlmProbForTag(String)`.
- Produces: `Double calibrate(String tag, Double rawLlmProb)` (replaces the single-tag `calibrate(Double)`).

- [ ] **Step 1: Replace the calibrator body**

```java
package com.cryptoradar.signal.probability;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Empirical recalibration of the LLM probability, scoped per generator config tag:
 * each config's geometry/direction makes its own outcomes the only valid evidence,
 * so a separate bucket map (raw LLM decile → realized win rate) is learned for each
 * enabled generator's tag. Until a bucket has enough samples it falls back to that
 * tag's base rate; until any data exists {@link #calibrate} returns null.
 */
@ApplicationScoped
public class ProbabilityCalibrator {

    private static final Logger LOG = Logger.getLogger(ProbabilityCalibrator.class);
    private static final int BUCKETS = 10;
    private static final int MIN_BUCKET_SAMPLES = 5;

    @Inject ProbabilityCandidateRepository repository;
    @Inject Instance<CandidateGenerator> generators;

    private record Calibration(double[] bucketRate, double baseRate) {}

    private volatile Map<String, Calibration> byTag = Map.of();

    void onStart(@Observes StartupEvent event) {
        retrain();
    }

    @Scheduled(every = "{probability.calibrator.retrain-interval:1h}", delayed = "180s", identity = "prob-calibrator")
    void scheduledRetrain() {
        retrain();
    }

    @Transactional
    public void retrain() {
        try {
            Map<String, Calibration> next = new HashMap<>();
            for (CandidateGenerator generator : generators) {
                Calibration c = learn(generator.tag());
                if (c != null) next.put(generator.tag(), c);
            }
            byTag = next;
        } catch (RuntimeException e) {
            LOG.warnf("Calibrator retrain failed, keeping prior maps: %s", e.getMessage());
        }
    }

    private Calibration learn(String tag) {
        List<ProbabilityCandidate> closed = repository.findClosedWithLlmProbForTag(tag);
        if (closed.isEmpty()) return null;
        int[] counts = new int[BUCKETS];
        int[] wins = new int[BUCKETS];
        int totalWins = 0;
        for (ProbabilityCandidate c : closed) {
            int b = bucketIndex(c.llmProb);
            counts[b]++;
            int won = ProbabilityCandidate.STATUS_HIT_TARGET.equals(c.status) ? 1 : 0;
            wins[b] += won;
            totalWins += won;
        }
        double[] rates = new double[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            rates[i] = counts[i] >= MIN_BUCKET_SAMPLES ? (double) wins[i] / counts[i] : Double.NaN;
        }
        double baseRate = (double) totalWins / closed.size();
        LOG.infof("Calibrator retrained tag=%s on %d closed (base=%.3f)", tag, closed.size(), baseRate);
        return new Calibration(rates, baseRate);
    }

    /** Recalibrated probability for a raw LLM probability under one config tag, or null. */
    public Double calibrate(String tag, Double rawLlmProb) {
        if (rawLlmProb == null) return null;
        Calibration c = byTag.get(tag);
        if (c == null) return null;
        double rate = c.bucketRate()[bucketIndex(rawLlmProb)];
        return Double.isNaN(rate) ? c.baseRate() : rate;
    }

    private static int bucketIndex(double p) {
        int idx = (int) (p * BUCKETS);
        if (idx < 0) return 0;
        if (idx >= BUCKETS) return BUCKETS - 1;
        return idx;
    }
}
```

- [ ] **Step 2: Compile**

Run: `cd services/signal-service && mvnd compile`
Expected: BUILD SUCCESS (this is the signature `ProbabilityScanScheduler` Task 8 calls).

- [ ] **Step 3: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/probability/ProbabilityCalibrator.java
git commit -m "feat(signal): per-tag probability calibration"
```

---

### Task 10: Per-tag calibration report + endpoint param

**Files:**
- Modify: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/CalibrationReporter.java`
- Modify: `services/signal-service/src/main/java/com/cryptoradar/signal/probability/CalibrationResource.java`

**Interfaces:**
- Produces: `CalibrationReporter.report(String tag)`; `CalibrationResource` accepts `?tag=` (defaults to `v2-1to1-flip`).

- [ ] **Step 1: Add `report(String tag)` to `CalibrationReporter`**

Replace the `configTag` field + `report()` method with:

```java
    private static final String DEFAULT_TAG = "v2-1to1-flip";

    public Report report() {
        return report(DEFAULT_TAG);
    }

    @Transactional
    public Report report(String configTag) {
        List<ProbabilityCandidate> closed = repository.findClosedForTag(configTag);
        List<double[]> statsPairs = new ArrayList<>();
        List<double[]> llmPairs = new ArrayList<>();
        List<double[]> calibratedPairs = new ArrayList<>();
        int wins = 0;
        for (ProbabilityCandidate c : closed) {
            int won = ProbabilityCandidate.STATUS_HIT_TARGET.equals(c.status) ? 1 : 0;
            wins += won;
            if (c.statsProb != null) statsPairs.add(new double[]{c.statsProb, won});
            if (c.llmProb != null) llmPairs.add(new double[]{c.llmProb, won});
            if (c.calibratedProb != null) calibratedPairs.add(new double[]{c.calibratedProb, won});
        }
        double realized = closed.isEmpty() ? 0.0 : (double) wins / closed.size();
        return new Report(configTag, closed.size(), realized,
                bucketize(statsPairs), bucketize(llmPairs), bucketize(calibratedPairs));
    }
```

Remove the `@ConfigProperty ... configTag` field and its import if now unused (`org.eclipse.microprofile.config.inject.ConfigProperty`).

- [ ] **Step 2: Add the `?tag=` param to `CalibrationResource`**

```java
package com.cryptoradar.signal.probability;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves the shadow probability gate's calibration report. Without a tag it
 * returns the v2-1to1-flip control's curve (unchanged contract); {@code ?tag=}
 * selects another config, e.g. v3-feature-dir.
 */
@Path("/api/signals/probability")
public class CalibrationResource {

    private static final String DEFAULT_TAG = "v2-1to1-flip";

    @Inject
    CalibrationReporter reporter;

    @GET
    @Path("/calibration")
    @Produces(MediaType.APPLICATION_JSON)
    public CalibrationReporter.Report calibration(@QueryParam("tag") String tag) {
        return reporter.report(tag == null || tag.isBlank() ? DEFAULT_TAG : tag);
    }
}
```

- [ ] **Step 3: Compile + run the probability suite**

Run: `cd services/signal-service && mvnd test -Dtest=ProbabilityGateTest,GeneratorTest,DirectionModelTest,LabelWalkerTest,AtrCalculatorTest`
Expected: PASS (bucketize tests unchanged; nothing else references the removed field).

- [ ] **Step 4: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/probability/CalibrationReporter.java \
        services/signal-service/src/main/java/com/cryptoradar/signal/probability/CalibrationResource.java
git commit -m "feat(signal): per-tag calibration report endpoint"
```

---

### Task 11: Config, deployment marker, docs, full build + live verification

**Files:**
- Modify: `services/signal-service/src/main/resources/application.properties`
- Modify: `db/init/signal-init.sql`
- Modify: `CLAUDE.md`

**Interfaces:** none (configuration + documentation + verification).

- [ ] **Step 1: Add generator + direction-model config to `application.properties`**

Insert after the existing `probability.calibrator.retrain-interval=1h` line:

```properties
# --- Parallel generators (each a CDI CandidateGenerator bean) ---
# Flip (v2 control) keeps its original geometry/direction knobs above.
probability.generator.flip.enabled=true
probability.generator.flip.run-llm=true
# v3 feature-direction: direction from a logistic over candle indicators, same 1:1
# geometry as the flip so direction is the only changed variable. Shadow only.
probability.generator.feature-dir.tag=v3-feature-dir
probability.generator.feature-dir.enabled=true
probability.generator.feature-dir.run-llm=true
probability.generator.feature-dir.stop-atr-mult=1.5
probability.generator.feature-dir.target-r=1.0
# Direction model training (real historical 1h candles; forward 1:1 label).
probability.direction-model.retrain-interval=6h
probability.direction-model.hold-hours=72
probability.direction-model.lookback-days=60
```

- [ ] **Step 2: Add the `v10` deployment marker to `db/init/signal-init.sql`**

Append before the final newline of the file:

```sql
INSERT INTO deployment_markers (deployed_at, version, description) VALUES
    ('2026-06-23T00:00:00Z', 'v10-probability-feature-direction',
     'Parallel shadow generator v3-feature-dir: trade direction from a standardized ' ||
     'logistic over candle indicators (RSI/%B/MACD/momentum/vol/volume), trained from ' ||
     'historical candles with forward 1:1 labels. Runs alongside v2-1to1-flip control ' ||
     '(identical 1:1 geometry); per-tag calibration. No live execution change.')
ON CONFLICT (deployed_at) DO NOTHING;
```

- [ ] **Step 3: Document the v3 generator in `CLAUDE.md`**

In the "AI probability gate" section, append a bullet after the Phase 2 description:

```markdown
- **Parallel generators (v10)**: the hourly scan now iterates CDI `CandidateGenerator`
  beans. `FlipGenerator` is the v2 control (invert(sign(overallScore)), 1:1).
  `FeatureDirectionGenerator` (`v3-feature-dir`) derives direction from `DirectionModel`
  — a z-score-standardized logistic over the 6 candle `TechnicalIndicators`, trained by
  `DirectionModelTrainer` from historical 1h candles with forward 1:1 labels
  (`LabelWalker`). Same 1:1 geometry as the flip so direction is the only variable.
  Calibration is per-tag: `GET /api/signals/probability/calibration?tag=v3-feature-dir`.
  Still shadow. Tests whether raw features beat the dimension-score direction the flip
  showed is a coin-flip in either polarity.
```

- [ ] **Step 4: Full build + test**

Run: `cd services/signal-service && mvnd test`
Expected: BUILD SUCCESS — all prior tests plus the new probability tests pass.

- [ ] **Step 5: Rebuild + restart the service**

Run:
```bash
docker compose build signal-service && docker compose up -d --no-deps --force-recreate signal-service
```
Expected: container healthy.

- [ ] **Step 6: Verify both generators accrue + the trainer trained**

After ~2 minutes (startup train + first scan at +90s), run:
```bash
docker compose logs signal-service --since=5m | grep -E "Direction model trained|Probability scan complete"
docker exec projectr-x-timescaledb-1 psql -U cryptoradar -d marketdata -c \
  "SELECT config_tag, status, count(*) FROM probability_candidates GROUP BY config_tag, status ORDER BY config_tag, status;"
```
Expected: a "Direction model trained on N rows" log line with N > 0; within one scan, new PENDING rows for **both** `v2-1to1-flip` and `v3-feature-dir`.

- [ ] **Step 7: Verify both calibration curves return**

Run:
```bash
curl -s http://localhost:31086/api/signals/probability/calibration | head -c 200
curl -s "http://localhost:31086/api/signals/probability/calibration?tag=v3-feature-dir" | head -c 200
```
Expected: the first returns the v2 report (`"configTag":"v2-1to1-flip"`); the second returns `"configTag":"v3-feature-dir"` (buckets empty until v3 candidates close — acceptable).

- [ ] **Step 8: Cross-service log audit**

Run: `docker compose logs signal-service --since=5m | grep -cE " ERROR | WARN "`
Expected: 0 new ERROR/WARN attributable to the scan, trainer, or calibrator (pre-existing unrelated WARNs, if any, are fine — eyeball the matches).

- [ ] **Step 9: Commit**

```bash
git add services/signal-service/src/main/resources/application.properties db/init/signal-init.sql CLAUDE.md
git commit -m "feat(signal): enable v3-feature-dir generator + docs + marker"
```

---

## Self-Review

**Spec coverage:**
- Hypothesis (feature-derived direction vs overallScore) → Tasks 2, 5, 7. ✓
- CDI generator beans (Approach A) → Tasks 6, 7, 8. ✓
- `FlipGenerator` behavior-preserving + regression test → Task 6. ✓
- `DirectionModel` (logistic, standardized) → Task 2. ✓
- `DirectionModelTrainer` (real historical candles, look-ahead discipline) → Task 5. ✓
- Forward-eval unchanged (`ShadowOutcomeEvaluator` tag-agnostic) → no task needed; `LabelWalker` mirrors its rule for training (Task 3). ✓
- Per-tag config/report → Tasks 9, 10. ✓ Both run LLM → config Task 11 (`run-llm=true`). ✓
- Config knobs, `v10` marker, CLAUDE.md → Task 11. ✓
- No-synthetic-data (skip when untrained; real candles) → Tasks 5, 7. ✓
- **Deviation from spec (intentional):** spec listed liq-imbalance among features; implementation uses the 6 candle indicators only because liq has no historical reconstruction for labels. Documented in Global Constraints and surfaced to the user. ✓

**Placeholder scan:** no TBD/TODO; every code step shows complete code; every run step has an expected result. ✓

**Type consistency:**
- `LogisticRegression(int, double)` / `predict(double[])` / `train(double[][],int[],int,double,double)` — consistent across Tasks 1, 2.
- `DirectionModel.train(double[][],int[],int,double,double)`, `longWinProbability(double[])`, `toVector(TechnicalIndicators)`, `FEATURES=6` — consistent Tasks 2, 5, 7.
- `LabelWalker.resolve(List<CandleBar>,double,double,double,boolean)` → status String — consistent Tasks 3, 5.
- `AtrCalculator.atr(List<CandleBar>,int)` — consistent Tasks 4, 5, 8.
- `CandidateGenerator{tag,enabled,runLlm,build}` + `DirectionContext(signal,bars,atr,entry,indicators,dimScores)` — consistent Tasks 6, 7, 8.
- `ProbabilityCalibrator.calibrate(String,Double)` — defined Task 9, called Task 8. ✓
- `CalibrationReporter.report(String)` — defined Task 10, called by resource Task 10. ✓

**Assumption to verify during execution:** `TradingSignal` exposes `setSymbol`, `setOverallScore` (used in `GeneratorTest`). If the model is immutable/builder-based, adjust the test fixtures to its real constructor — the production code only reads `getSymbol()`/`getOverallScore()`, which already exist.

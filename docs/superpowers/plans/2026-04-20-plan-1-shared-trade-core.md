# Plan 1 — shared-trade-core + signal-service refactor

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the trailing-stop math and R-unit helpers from `signal-service` into a new standalone Maven module `shared-trade-core`, so the upcoming `trade-execution-service` can depend on the same source-of-truth algorithm. Pure refactor — no behavior change.

**Architecture:** New `shared-trade-core` module sits at the repo root as a plain JAR (no Quarkus deps). It contains `TrailCalculator` (pure function), `TrailConfig` (record, moved verbatim from signal-service), and `RUnitMath` (qty/risk helpers — new code covered by TDD). `signal-service/pom.xml` adds a dependency on the shared module. `OutcomeEvaluator.updateTrailingStop` delegates the pure calculation to `TrailCalculator` and keeps only the entity-write and logging concerns. Because the shared module is built separately, `signal-service`'s Dockerfile changes its build context to repo root so it can `mvn install` the shared module before building the service.

**Tech Stack:** Java 21, Maven 3.9, JUnit 5, Docker Compose.

**Spec reference:** `docs/superpowers/specs/2026-04-20-trade-execution-service-design.md` — Section 1 (Component topology, the `shared-trade-core` module definition).

---

## File structure

**Create:**
- `shared-trade-core/pom.xml`
- `shared-trade-core/src/main/java/com/cryptoradar/core/TrailConfig.java`
- `shared-trade-core/src/main/java/com/cryptoradar/core/TrailCalculator.java`
- `shared-trade-core/src/main/java/com/cryptoradar/core/RUnitMath.java`
- `shared-trade-core/src/test/java/com/cryptoradar/core/TrailConfigTest.java`
- `shared-trade-core/src/test/java/com/cryptoradar/core/TrailCalculatorTest.java`
- `shared-trade-core/src/test/java/com/cryptoradar/core/RUnitMathTest.java`

**Modify:**
- `services/signal-service/pom.xml` — add dependency on `shared-trade-core`
- `services/signal-service/src/main/java/com/cryptoradar/signal/model/TradeSetup.java` — update `TrailConfig` import
- `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java` — delegate trail math to `TrailCalculator`
- `services/signal-service/Dockerfile` — install shared module before building service
- `docker-compose.yml` — change signal-service build context to repo root

**Delete:**
- `services/signal-service/src/main/java/com/cryptoradar/signal/model/TrailConfig.java`

---

## Task 1: Create the `shared-trade-core` Maven module skeleton

**Goal:** A buildable, empty module with a sanity test that passes.

**Files:**
- Create: `shared-trade-core/pom.xml`
- Create: `shared-trade-core/src/main/java/com/cryptoradar/core/package-info.java`
- Create: `shared-trade-core/src/test/java/com/cryptoradar/core/SanityTest.java`

- [ ] **Step 1: Create the module pom.xml**

Write `shared-trade-core/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.cryptoradar</groupId>
    <artifactId>shared-trade-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <description>
        Shared primitives for trade-outcome calculations — trailing-stop ladder math,
        R-multiple helpers. Depended on by signal-service (outcome evaluator) and
        trade-execution-service (live trail mirror). No Quarkus or framework deps.
    </description>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.11.3</junit.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create the package marker**

Write `shared-trade-core/src/main/java/com/cryptoradar/core/package-info.java`:

```java
/**
 * Shared trade-outcome primitives — trailing-stop math and R-unit helpers.
 * Depended on by {@code signal-service} and {@code trade-execution-service}.
 * Keep this package framework-free (no Quarkus, no Spring, no Panache).
 */
package com.cryptoradar.core;
```

- [ ] **Step 3: Write a sanity test to prove the module compiles**

Write `shared-trade-core/src/test/java/com/cryptoradar/core/SanityTest.java`:

```java
package com.cryptoradar.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SanityTest {

    @Test
    void moduleCompilesAndRunsJUnit5() {
        assertEquals(4, 2 + 2);
    }
}
```

- [ ] **Step 4: Build the module and run tests**

Run: `cd shared-trade-core && mvn test -B`
Expected: `BUILD SUCCESS`, `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add shared-trade-core/
git commit -m "feat(shared-trade-core): scaffold module with JUnit 5"
```

---

## Task 2: Move `TrailConfig` into `shared-trade-core`

**Goal:** Bring the existing `TrailConfig` record over verbatim. Add a dedicated test file (previously covered only indirectly).

**Files:**
- Create: `shared-trade-core/src/main/java/com/cryptoradar/core/TrailConfig.java`
- Create: `shared-trade-core/src/test/java/com/cryptoradar/core/TrailConfigTest.java`

- [ ] **Step 1: Write the failing test**

Write `shared-trade-core/src/test/java/com/cryptoradar/core/TrailConfigTest.java`:

```java
package com.cryptoradar.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrailConfigTest {

    @Test
    void defaultValuesAreActivation1StepHalfOffsetHalf() {
        TrailConfig config = TrailConfig.DEFAULT;
        assertEquals(1.0, config.activationR());
        assertEquals(0.5, config.stepR());
        assertEquals(0.5, config.offsetR());
    }

    @Test
    void customValuesArePreserved() {
        TrailConfig config = new TrailConfig(2.0, 1.0, 0.25);
        assertEquals(2.0, config.activationR());
        assertEquals(1.0, config.stepR());
        assertEquals(0.25, config.offsetR());
    }

    @Test
    void rejectsZeroActivation() {
        assertThrows(IllegalArgumentException.class, () -> new TrailConfig(0.0, 0.5, 0.5));
    }

    @Test
    void rejectsNegativeActivation() {
        assertThrows(IllegalArgumentException.class, () -> new TrailConfig(-0.1, 0.5, 0.5));
    }

    @Test
    void rejectsZeroStep() {
        assertThrows(IllegalArgumentException.class, () -> new TrailConfig(1.0, 0.0, 0.5));
    }

    @Test
    void rejectsNegativeOffset() {
        assertThrows(IllegalArgumentException.class, () -> new TrailConfig(1.0, 0.5, -0.1));
    }

    @Test
    void acceptsZeroOffset() {
        TrailConfig config = new TrailConfig(1.0, 0.5, 0.0);
        assertEquals(0.0, config.offsetR());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd shared-trade-core && mvn test -Dtest=TrailConfigTest -B`
Expected: FAIL with `cannot find symbol: class TrailConfig`

- [ ] **Step 3: Write the TrailConfig record**

Write `shared-trade-core/src/main/java/com/cryptoradar/core/TrailConfig.java`:

```java
package com.cryptoradar.core;

/**
 * Per-strategy trailing-stop parameters in R-units.
 *
 * <p>{@link #DEFAULT} matches the parameters optimal across the full outcome
 * dataset in the initial simulation: activation at 1R, rung size 0.5R, trail
 * sits 0.5R behind the current rung.
 *
 * @param activationR MFE threshold at which the trail first ratchets from the
 *                    initial stop. Below this, dynamic stop stays unset.
 * @param stepR       Rung size; the trail advances one rung per this much MFE.
 * @param offsetR     Distance (in R) the trail sits behind the current rung.
 */
public record TrailConfig(double activationR, double stepR, double offsetR) {

    public static final TrailConfig DEFAULT = new TrailConfig(1.0, 0.5, 0.5);

    public TrailConfig {
        if (activationR <= 0 || stepR <= 0 || offsetR < 0) {
            throw new IllegalArgumentException(
                    "TrailConfig requires activationR>0, stepR>0, offsetR>=0 — got "
                            + activationR + "/" + stepR + "/" + offsetR);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd shared-trade-core && mvn test -Dtest=TrailConfigTest -B`
Expected: PASS `Tests run: 7, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add shared-trade-core/src/main/java/com/cryptoradar/core/TrailConfig.java \
        shared-trade-core/src/test/java/com/cryptoradar/core/TrailConfigTest.java
git commit -m "feat(shared-trade-core): add TrailConfig record with validation"
```

---

## Task 3: Add `TrailCalculator` (pure trail-rung math)

**Goal:** Extract the rung calculation from `OutcomeEvaluator.updateTrailingStop` into a pure function. Reveals the algorithm boundary: given MFE progress + config + current rung, return the new rung (or empty if no advance).

**Files:**
- Create: `shared-trade-core/src/main/java/com/cryptoradar/core/TrailCalculator.java`
- Create: `shared-trade-core/src/test/java/com/cryptoradar/core/TrailCalculatorTest.java`

- [ ] **Step 1: Write the failing tests**

Write `shared-trade-core/src/test/java/com/cryptoradar/core/TrailCalculatorTest.java`:

```java
package com.cryptoradar.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrailCalculatorTest {

    private static final TrailConfig DEFAULTS = TrailConfig.DEFAULT;   // activation=1.0 step=0.5 offset=0.5

    @Test
    void belowActivationReturnsEmpty() {
        Optional<Double> result = TrailCalculator.computeNewTrailR(0.5, DEFAULTS, 0.0);
        assertTrue(result.isEmpty());
    }

    @Test
    void exactlyAtActivationReturnsActivationMinusOffset() {
        // mfeR=1.0, activation=1.0 → rung=0, newTrailR = 1.0 + 0*0.5 - 0.5 = 0.5
        Optional<Double> result = TrailCalculator.computeNewTrailR(1.0, DEFAULTS, 0.0);
        assertEquals(0.5, result.orElseThrow(), 1e-9);
    }

    @Test
    void aboveActivationAdvancesByFullRungs() {
        // mfeR=1.7, activation=1.0 → rung=floor(0.7/0.5)=1, newTrailR = 1.0 + 1*0.5 - 0.5 = 1.0
        Optional<Double> result = TrailCalculator.computeNewTrailR(1.7, DEFAULTS, 0.0);
        assertEquals(1.0, result.orElseThrow(), 1e-9);
    }

    @Test
    void twoFullRungsAbove() {
        // mfeR=2.2, activation=1.0 → rung=floor(1.2/0.5)=2, newTrailR = 1.0 + 2*0.5 - 0.5 = 1.5
        Optional<Double> result = TrailCalculator.computeNewTrailR(2.2, DEFAULTS, 0.0);
        assertEquals(1.5, result.orElseThrow(), 1e-9);
    }

    @Test
    void returnsEmptyWhenCurrentHighestAlreadyReached() {
        // mfeR=1.7 would compute newTrailR=1.0, but currentHighestR=1.0 already
        Optional<Double> result = TrailCalculator.computeNewTrailR(1.7, DEFAULTS, 1.0);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenCurrentHighestExceedsWhatMfeImplies() {
        // Monotonic: price pulled back, mfeR=1.2, but we previously ratcheted to 1.5
        Optional<Double> result = TrailCalculator.computeNewTrailR(1.2, DEFAULTS, 1.5);
        assertTrue(result.isEmpty());
    }

    @Test
    void advancesFromNonZeroHighest() {
        // mfeR=3.2, activation=1.0 → rung=floor(2.2/0.5)=4, newTrailR = 1.0 + 4*0.5 - 0.5 = 2.5
        // currentHighest=1.5, so advance to 2.5
        Optional<Double> result = TrailCalculator.computeNewTrailR(3.2, DEFAULTS, 1.5);
        assertEquals(2.5, result.orElseThrow(), 1e-9);
    }

    @Test
    void customConfigRespected() {
        // activation=2.0, step=1.0, offset=0.0
        TrailConfig config = new TrailConfig(2.0, 1.0, 0.0);
        // mfeR=3.5 → rung=floor(1.5/1.0)=1, newTrailR = 2.0 + 1*1.0 - 0.0 = 3.0
        Optional<Double> result = TrailCalculator.computeNewTrailR(3.5, config, 0.0);
        assertEquals(3.0, result.orElseThrow(), 1e-9);
    }

    @Test
    void zeroOffsetConfigParksTrailAtExactRung() {
        TrailConfig config = new TrailConfig(1.0, 0.5, 0.0);
        // mfeR=2.0 → rung=floor(1.0/0.5)=2, newTrailR = 1.0 + 2*0.5 - 0.0 = 2.0
        Optional<Double> result = TrailCalculator.computeNewTrailR(2.0, config, 0.0);
        assertEquals(2.0, result.orElseThrow(), 1e-9);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd shared-trade-core && mvn test -Dtest=TrailCalculatorTest -B`
Expected: FAIL with `cannot find symbol: class TrailCalculator`

- [ ] **Step 3: Write the TrailCalculator implementation**

Write `shared-trade-core/src/main/java/com/cryptoradar/core/TrailCalculator.java`:

```java
package com.cryptoradar.core;

import java.util.Optional;

/**
 * Pure trailing-stop rung math. Given MFE progress, config, and current rung,
 * returns the new rung — or empty if no advance.
 *
 * <p>Algorithm (matches the spec trail ladder, R-units throughout):
 * <pre>
 *   if mfeR &lt; activationR → no advance
 *   rung      = floor((mfeR - activationR) / stepR)
 *   newTrailR = activationR + rung * stepR - offsetR
 *   if newTrailR &lt;= currentHighestR → no advance (monotonic)
 *   else → advance to newTrailR
 * </pre>
 *
 * <p>Monotonic: never loosens when price pulls back below the current rung.
 * Translating newTrailR to a concrete price is the caller's job (they know
 * direction and entry).
 */
public final class TrailCalculator {

    private TrailCalculator() {}

    public static Optional<Double> computeNewTrailR(double mfeR, TrailConfig config, double currentHighestR) {
        if (mfeR < config.activationR()) {
            return Optional.empty();
        }
        double rung = Math.floor((mfeR - config.activationR()) / config.stepR());
        double newTrailR = config.activationR() + rung * config.stepR() - config.offsetR();
        if (newTrailR <= currentHighestR) {
            return Optional.empty();
        }
        return Optional.of(newTrailR);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd shared-trade-core && mvn test -Dtest=TrailCalculatorTest -B`
Expected: PASS `Tests run: 9, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add shared-trade-core/src/main/java/com/cryptoradar/core/TrailCalculator.java \
        shared-trade-core/src/test/java/com/cryptoradar/core/TrailCalculatorTest.java
git commit -m "feat(shared-trade-core): add TrailCalculator for trail-rung math"
```

---

## Task 4: Add `RUnitMath` (quantity + risk helpers)

**Goal:** Small utility that computes order quantity from equity + risk % + stop distance, floored to exchange lot size. Currently only used by the upcoming `trade-execution-service`, but belongs in the shared module as a companion to `TrailCalculator`.

**Files:**
- Create: `shared-trade-core/src/main/java/com/cryptoradar/core/RUnitMath.java`
- Create: `shared-trade-core/src/test/java/com/cryptoradar/core/RUnitMathTest.java`

- [ ] **Step 1: Write the failing tests**

Write `shared-trade-core/src/test/java/com/cryptoradar/core/RUnitMathTest.java`:

```java
package com.cryptoradar.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RUnitMathTest {

    @Test
    void qtyForOnePercentRiskOnFiftyBpsStop() {
        // equity=1000, risk=1% → $10 risk
        // entry=50000, stop=49750 → distance=250
        // rawQty = 10/250 = 0.04
        // lotSize=0.001 → floor(0.04 / 0.001) * 0.001 = 0.040
        double qty = RUnitMath.computeQty(1000.0, 1.0, 50000.0, 49750.0, 0.001);
        assertEquals(0.040, qty, 1e-9);
    }

    @Test
    void qtyRoundsDownToLotSize() {
        // equity=1000, risk=1% → $10 risk
        // entry=50000, stop=49000 → distance=1000
        // rawQty = 10/1000 = 0.01
        // lotSize=0.003 → floor(0.01 / 0.003) * 0.003 = 3 * 0.003 = 0.009
        double qty = RUnitMath.computeQty(1000.0, 1.0, 50000.0, 49000.0, 0.003);
        assertEquals(0.009, qty, 1e-9);
    }

    @Test
    void qtyWorksForShort() {
        // Distance is absolute — direction doesn't matter
        // equity=1000, risk=1%, entry=3000, stop=3050 → distance=50
        // rawQty = 10/50 = 0.2, lotSize=0.01 → 0.20
        double qty = RUnitMath.computeQty(1000.0, 1.0, 3000.0, 3050.0, 0.01);
        assertEquals(0.20, qty, 1e-9);
    }

    @Test
    void qtyScalesWithEquity() {
        double qty1k = RUnitMath.computeQty(1000.0, 1.0, 100.0, 99.0, 0.01);
        double qty10k = RUnitMath.computeQty(10000.0, 1.0, 100.0, 99.0, 0.01);
        assertEquals(qty1k * 10.0, qty10k, 1e-9);
    }

    @Test
    void qtyScalesWithRiskPercent() {
        double qty1pct = RUnitMath.computeQty(1000.0, 1.0, 100.0, 99.0, 0.01);
        double qty2pct = RUnitMath.computeQty(1000.0, 2.0, 100.0, 99.0, 0.01);
        assertEquals(qty1pct * 2.0, qty2pct, 1e-9);
    }

    @Test
    void zeroStopDistanceRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RUnitMath.computeQty(1000.0, 1.0, 100.0, 100.0, 0.01));
    }

    @Test
    void zeroOrNegativeEquityRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RUnitMath.computeQty(0.0, 1.0, 100.0, 99.0, 0.01));
        assertThrows(IllegalArgumentException.class,
                () -> RUnitMath.computeQty(-100.0, 1.0, 100.0, 99.0, 0.01));
    }

    @Test
    void zeroOrNegativeLotSizeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RUnitMath.computeQty(1000.0, 1.0, 100.0, 99.0, 0.0));
    }

    @Test
    void riskPercentAsFraction() {
        // riskPercent is whole-number percent (1.0 = 1%), not a fraction (0.01 = 1%)
        // Confirm 1.0 argument means 1%: equity=1000, rp=1.0 → risk=10
        double qty = RUnitMath.computeQty(1000.0, 1.0, 100.0, 99.0, 0.01);
        // distance=1, rawQty=10, floor(10/0.01)*0.01 = 10.00
        assertEquals(10.0, qty, 1e-9);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd shared-trade-core && mvn test -Dtest=RUnitMathTest -B`
Expected: FAIL with `cannot find symbol: class RUnitMath`

- [ ] **Step 3: Write the RUnitMath implementation**

Write `shared-trade-core/src/main/java/com/cryptoradar/core/RUnitMath.java`:

```java
package com.cryptoradar.core;

/**
 * Position-sizing helpers. R-multiple accounting: risk is `equity × riskPercent/100`,
 * and position quantity is that dollar risk divided by the entry-to-stop distance.
 *
 * <p>`riskPercent` is expressed as a whole-number percent (1.0 = 1%, not 0.01).
 */
public final class RUnitMath {

    private RUnitMath() {}

    /**
     * Compute order quantity from equity, risk %, entry price, stop price, and
     * exchange lot step. Result is floored to the lot step so the returned qty
     * is always a valid submittable size.
     *
     * @param equity       account equity in quote currency (USDT)
     * @param riskPercent  percent of equity to risk on this trade (1.0 = 1%)
     * @param entryPrice   planned entry price
     * @param stopPrice    planned stop price (absolute, any side)
     * @param lotSize      exchange-defined quantity step (e.g., 0.001 for BTC)
     * @return qty rounded down to the nearest multiple of lotSize
     * @throws IllegalArgumentException if equity &lt;= 0, lotSize &lt;= 0, or entry equals stop
     */
    public static double computeQty(double equity, double riskPercent,
                                    double entryPrice, double stopPrice, double lotSize) {
        if (equity <= 0) {
            throw new IllegalArgumentException("equity must be > 0, got " + equity);
        }
        if (lotSize <= 0) {
            throw new IllegalArgumentException("lotSize must be > 0, got " + lotSize);
        }
        double distance = Math.abs(entryPrice - stopPrice);
        if (distance == 0) {
            throw new IllegalArgumentException(
                    "entry and stop prices are equal — cannot size position (entry=" + entryPrice + ")");
        }
        double riskAmount = equity * riskPercent / 100.0;
        double rawQty = riskAmount / distance;
        return Math.floor(rawQty / lotSize) * lotSize;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd shared-trade-core && mvn test -Dtest=RUnitMathTest -B`
Expected: PASS `Tests run: 9, Failures: 0`

- [ ] **Step 5: Install the shared module into local Maven repo**

Run: `cd shared-trade-core && mvn install -DskipTests -B`
Expected: `BUILD SUCCESS` and output includes `Installing .../shared-trade-core-1.0.0-SNAPSHOT.jar to ~/.m2/repository/com/cryptoradar/shared-trade-core/1.0.0-SNAPSHOT/`

- [ ] **Step 6: Commit**

```bash
git add shared-trade-core/src/main/java/com/cryptoradar/core/RUnitMath.java \
        shared-trade-core/src/test/java/com/cryptoradar/core/RUnitMathTest.java
git commit -m "feat(shared-trade-core): add RUnitMath qty/risk helpers"
```

---

## Task 5: Add `shared-trade-core` dependency to signal-service; delete old `TrailConfig`

**Goal:** Point signal-service at the shared module. Delete the old in-module `TrailConfig`. Fix imports everywhere.

**Files:**
- Modify: `services/signal-service/pom.xml`
- Modify: `services/signal-service/src/main/java/com/cryptoradar/signal/model/TradeSetup.java`
- Delete: `services/signal-service/src/main/java/com/cryptoradar/signal/model/TrailConfig.java`

- [ ] **Step 1: Add the dependency to signal-service pom**

Edit `services/signal-service/pom.xml` — inside the `<dependencies>` block, add (place near the top, above the Quarkus entries):

```xml
        <!-- Shared trade-outcome primitives (trail math, R-unit helpers) -->
        <dependency>
            <groupId>com.cryptoradar</groupId>
            <artifactId>shared-trade-core</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </dependency>
```

- [ ] **Step 2: Delete the old TrailConfig file**

Run: `rm services/signal-service/src/main/java/com/cryptoradar/signal/model/TrailConfig.java`

- [ ] **Step 3: Update imports in TradeSetup (the only current consumer besides OutcomeEvaluator)**

Read `services/signal-service/src/main/java/com/cryptoradar/signal/model/TradeSetup.java` and find:
```java
import com.cryptoradar.signal.model.TrailConfig;
```
or the `TrailConfig` reference may be bare (same package, no explicit import).

Replace any `com.cryptoradar.signal.model.TrailConfig` import with:
```java
import com.cryptoradar.core.TrailConfig;
```

If `TrailConfig` was used unqualified (same-package resolution), add the new explicit import at the top of the file:
```java
import com.cryptoradar.core.TrailConfig;
```

- [ ] **Step 4: Compile signal-service to catch remaining import breakages**

Run: `cd services/signal-service && mvn compile -B`
Expected: `BUILD SUCCESS`. If any file fails with `cannot find symbol: class TrailConfig`, add `import com.cryptoradar.core.TrailConfig;` to it.

Common spots that may also need fixing (grep before trusting the compiler alone):
```bash
grep -rn "TrailConfig" services/signal-service/src/main/java/
```
Every hit must resolve to `com.cryptoradar.core.TrailConfig`.

- [ ] **Step 5: Run the full signal-service test suite**

Run: `cd services/signal-service && mvn test -B`
Expected: `BUILD SUCCESS`, every previously-passing test still passes (44 tests across `SignalEngineBiasTest`, `SignalEngineStopPlacementTest`, `SignalEngineRegimeTest`, `OutcomeEvaluatorTrailingTest`, `OutcomeEvaluatorTimingAndFeesTest`, `MarketRegimeServiceTest`, `LiquiditySweepDetectorTest`).

- [ ] **Step 6: Commit**

```bash
git add services/signal-service/pom.xml \
        services/signal-service/src/main/java/com/cryptoradar/signal/model/TradeSetup.java
git rm services/signal-service/src/main/java/com/cryptoradar/signal/model/TrailConfig.java
git commit -m "refactor(signal-service): source TrailConfig from shared-trade-core"
```

---

## Task 6: Refactor `OutcomeEvaluator.updateTrailingStop` to delegate to `TrailCalculator`

**Goal:** Replace the inlined trail math with a `TrailCalculator.computeNewTrailR(...)` call. Existing `OutcomeEvaluatorTrailingTest` is the safety net — not a line of its assertions should change.

**Files:**
- Modify: `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java`

- [ ] **Step 1: Add the import**

In `services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java`, add to the imports section:
```java
import com.cryptoradar.core.TrailCalculator;
import com.cryptoradar.core.TrailConfig;

import java.util.Optional;
```

- [ ] **Step 2: Replace the method body**

Find the existing `updateTrailingStop` method (around line 150). The current body (lines 150-187) is:

```java
void updateTrailingStop(SignalOutcome outcome, CandleBar bar) {
    boolean isLong = DIRECTION_LONG.equals(outcome.getDirection());
    double entry = outcome.getEntryPrice();
    double risk = Math.abs(entry - outcome.getStopPrice());
    if (risk <= 0) return;

    // Use the cumulative MFE (already refreshed by updateExcursions for this
    // bar). Doing this — rather than reading bar.high() / bar.low() in
    // isolation — matters for two cases:
    //   1. Backfill: existing PENDING rows with historical peaks above
    //      activation must ratchet the trail immediately on the first
    //      evaluator tick after deploy, without waiting for a new peak.
    //   2. Pull-backs: a bar whose high is below the lifetime peak must
    //      still track the ratcheted level rather than reset to a lower rung.
    double riskPct = risk / entry * 100.0;
    if (riskPct <= 0) return;
    double mfeR = outcome.getMaxFavorablePct() / riskPct;

    double activationR = outcome.getTrailActivationR();
    if (mfeR < activationR) return;

    double stepR = outcome.getTrailStepR();
    double offsetR = outcome.getTrailOffsetR();
    double rung = Math.floor((mfeR - activationR) / stepR);
    double newTrailR = activationR + rung * stepR - offsetR;

    if (newTrailR <= outcome.getTrailHighestR()) return;

    outcome.setTrailHighestR(newTrailR);
    double newStop = isLong ? entry + newTrailR * risk
                            : entry - newTrailR * risk;
    outcome.setDynamicStopPrice(newStop);
    if (outcome.getTrailTriggeredAt() == null) {
        outcome.setTrailTriggeredAt(bar.time());
        LOG.infof("TRAIL activated %s %s at rung %.2fR → stop=%.4f",
                outcome.getSymbol(), outcome.getDirection(), newTrailR, newStop);
    }
}
```

Replace it with:

```java
void updateTrailingStop(SignalOutcome outcome, CandleBar bar) {
    boolean isLong = DIRECTION_LONG.equals(outcome.getDirection());
    double entry = outcome.getEntryPrice();
    double risk = Math.abs(entry - outcome.getStopPrice());
    if (risk <= 0) return;

    // Use cumulative MFE so backfill (historical peak) and pull-backs (peak
    // already past) both work on the first evaluator tick.
    double riskPct = risk / entry * 100.0;
    if (riskPct <= 0) return;
    double mfeR = outcome.getMaxFavorablePct() / riskPct;

    TrailConfig config = new TrailConfig(
            outcome.getTrailActivationR(),
            outcome.getTrailStepR(),
            outcome.getTrailOffsetR());

    Optional<Double> newTrailR = TrailCalculator.computeNewTrailR(
            mfeR, config, outcome.getTrailHighestR());
    if (newTrailR.isEmpty()) return;

    double newR = newTrailR.get();
    outcome.setTrailHighestR(newR);
    double newStop = isLong ? entry + newR * risk : entry - newR * risk;
    outcome.setDynamicStopPrice(newStop);
    if (outcome.getTrailTriggeredAt() == null) {
        outcome.setTrailTriggeredAt(bar.time());
        LOG.infof("TRAIL activated %s %s at rung %.2fR → stop=%.4f",
                outcome.getSymbol(), outcome.getDirection(), newR, newStop);
    }
}
```

- [ ] **Step 3: Run the existing trail test suite**

Run: `cd services/signal-service && mvn test -Dtest=OutcomeEvaluatorTrailingTest -B`
Expected: PASS — every assertion identical outcome vs before.

- [ ] **Step 4: Run the full signal-service test suite**

Run: `cd services/signal-service && mvn test -B`
Expected: PASS on all 44 tests.

- [ ] **Step 5: Commit**

```bash
git add services/signal-service/src/main/java/com/cryptoradar/signal/service/OutcomeEvaluator.java
git commit -m "refactor(signal-service): delegate trail math to TrailCalculator"
```

---

## Task 7: Update signal-service Dockerfile + docker-compose.yml for multi-module build

**Goal:** The Docker build now needs to install `shared-trade-core` before building signal-service. Change the build context to repo root; update `COPY` paths accordingly; add a shared-install stage.

**Files:**
- Modify: `services/signal-service/Dockerfile`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Rewrite the signal-service Dockerfile**

Replace the entire contents of `services/signal-service/Dockerfile` with:

```dockerfile
# Stage 1: Install shared-trade-core into local Maven repo
FROM maven:3.9-eclipse-temurin-21-alpine AS shared

WORKDIR /build/shared-trade-core
COPY shared-trade-core/pom.xml ./pom.xml
COPY shared-trade-core/src ./src
RUN mvn install -DskipTests -B

# Stage 2: Build signal-service (reads shared module from the reused /root/.m2)
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

COPY --from=shared /root/.m2 /root/.m2

WORKDIR /build
COPY services/signal-service/pom.xml ./pom.xml
RUN mvn dependency:go-offline -B

COPY services/signal-service/src/ ./src/
RUN mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 1001 appgroup && \
    adduser -u 1001 -G appgroup -D appuser

WORKDIR /app

COPY --from=builder /build/target/*-runner.jar app.jar

EXPOSE 8086

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8086/q/health/ready || exit 1

USER 1001

CMD ["java", "-jar", "app.jar"]
```

Key changes vs the old Dockerfile:
- Adds Stage 1 that builds and installs `shared-trade-core`.
- Stage 2 pulls the Maven `.m2` cache forward so the signal-service build finds the installed artifact.
- `COPY` paths now reach into `services/signal-service/...` because the build context is the repo root (set in the next step).

- [ ] **Step 2: Change signal-service build context in docker-compose.yml**

Open `docker-compose.yml` and find the `signal-service:` entry. Find its `build:` block, which currently looks like:

```yaml
  signal-service:
    build: ./services/signal-service
```

Replace with:

```yaml
  signal-service:
    build:
      context: .
      dockerfile: services/signal-service/Dockerfile
```

Leave every other field on the service (environment, depends_on, ports, volumes, etc.) unchanged.

- [ ] **Step 3: Build the image from scratch**

Run: `docker compose build --no-cache signal-service`
Expected: Build completes all three stages, final message `Successfully built` and `Successfully tagged`. No errors about missing `shared-trade-core` artifact.

- [ ] **Step 4: Bring the service up and verify it reaches healthy state**

Run: `docker compose up -d --no-deps signal-service`

Wait ~15 seconds, then:

Run: `docker compose ps signal-service`
Expected: `Status` column reads `Up (healthy)` (or `Up` then `Up (healthy)` after the first healthcheck tick).

If the container is unhealthy or restarting, check logs:
Run: `docker compose logs signal-service --tail=200`
Look for `Quarkus started in ... seconds` and no `NoClassDefFoundError` on `TrailConfig` or `TrailCalculator`.

- [ ] **Step 5: Smoke-test the outcome-tracking endpoint**

Run: `curl -fsS http://localhost:31086/q/health/ready`
Expected: JSON body, `"status":"UP"`.

Run: `curl -fsS "http://localhost:31086/api/signals/outcomes?limit=5" | head -c 500`
Expected: JSON array (possibly empty — content not important, just that the endpoint answers).

- [ ] **Step 6: Commit**

```bash
git add services/signal-service/Dockerfile docker-compose.yml
git commit -m "build(signal-service): multi-module Dockerfile + repo-root build context"
```

---

## Task 8: Verify end-to-end — local test + Docker build + Docker runtime

**Goal:** Final go/no-go. Confirms nothing regressed in either the local Maven flow or the containerized flow.

**Files:** (no code changes in this task)

- [ ] **Step 1: Fresh local Maven build from scratch**

Run:
```bash
cd shared-trade-core && mvn clean install -B
cd ../services/signal-service && mvn clean test -B
```
Expected: Both `BUILD SUCCESS`. Signal-service reports 44 tests passing.

- [ ] **Step 2: Fresh Docker build from scratch**

Run: `docker compose build --no-cache signal-service`
Expected: `BUILD SUCCESS` on all three stages.

- [ ] **Step 3: Restart the service and tail logs for 30 seconds**

Run: `docker compose up -d --force-recreate --no-deps signal-service`
Run: `docker compose logs signal-service --tail=100 -f` (Ctrl-C after 30s once you see startup + first `@Scheduled` tick on OutcomeEvaluator)

Expected:
- `Quarkus ... started in X.XXs. Listening on: http://0.0.0.0:8086`
- No stack traces, no `NoClassDefFoundError`, no `ClassCastException`
- If there's a PENDING outcome row in the DB, an `OutcomeEvaluator` INFO line appears on the next tick (within 60s)

- [ ] **Step 4: Optional sanity — trigger the trail code path live**

If any `status=PENDING` row exists in `signal_outcomes`, the refactor will run against it on the next 60s tick. Watch for:
- No `ERROR`-level logs from `OutcomeEvaluator`
- If a row has `max_favorable_pct >= activation × riskPct`, expect a `TRAIL activated ...` log (same format as before the refactor)

Run to inspect: `docker exec projectr-x-timescaledb-1 psql -U cryptoradar -d marketdata -c "SELECT signal_id, status, max_favorable_pct, trail_highest_r, dynamic_stop_price FROM signal_outcomes WHERE status='PENDING' LIMIT 5;"`

- [ ] **Step 5: Update the project CLAUDE.md to reference the new module**

Edit `CLAUDE.md`, find the "Stack" section (near the top). In the first paragraph after "**Build chain**", add a new bullet just below the existing one:

```markdown
- **Shared Java module**: `shared-trade-core/` — pure-JAR, zero framework deps. Holds `TrailCalculator`, `TrailConfig`, `RUnitMath`. Built as a separate Maven module and installed into local `.m2`; `signal-service` (and the upcoming `trade-execution-service`) depend on it.
```

- [ ] **Step 6: Final commit and push**

```bash
git add CLAUDE.md
git commit -m "docs(claude-md): document shared-trade-core module"
git push origin master
```

Expected: Push succeeds, remote `master` now contains every commit from this plan.

---

## Self-review checklist (for the implementer)

Before declaring the plan done, confirm:

- [ ] `shared-trade-core` builds standalone (`mvn install` from that dir alone).
- [ ] `shared-trade-core` has no Quarkus or Panache dependency (check `pom.xml` — only `junit-jupiter` test scope).
- [ ] `signal-service` `mvn test` reports the same test count as before the refactor (44 tests).
- [ ] `docker compose build signal-service` succeeds from a clean cache.
- [ ] Container starts, reports healthy, answers `/q/health/ready` with status UP.
- [ ] No file in `services/signal-service/src/main/java/com/cryptoradar/signal/model/` is named `TrailConfig.java` (it was moved).
- [ ] Every `TrailConfig` reference in signal-service resolves to `com.cryptoradar.core.TrailConfig` (no lingering `com.cryptoradar.signal.model.TrailConfig` references — grep to confirm).
- [ ] Git log shows at least 7 commits from this plan, all prefixed `feat(shared-trade-core):`, `refactor(signal-service):`, `build(signal-service):`, or `docs(...):`.

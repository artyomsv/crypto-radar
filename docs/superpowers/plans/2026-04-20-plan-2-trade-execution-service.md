# Plan 2 — trade-execution-service (backend)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the new `trade-execution-service` that mirrors signal-service trading signals to real Bybit V5 USDT Perpetual orders on a demo account. Fully curl-testable without any frontend. End of plan = you can POST a Bybit demo API key, have signals fire real orders on the demo account, see trails ratchet on Bybit's side, and have closed trades populate with real P&L from Bybit's closed-pnl endpoint.

**Architecture:** New Quarkus 3.17 service at `services/trade-execution-service/`, port 31087 (host) / 8087 (internal). Subscribes to the existing Redis `crypto:signals` channel. Encrypts Bybit API credentials with AES-GCM under a master key from env. Calls Bybit V5 REST + private WebSocket directly (no third-party SDK). Uses `shared-trade-core.TrailCalculator` (from Plan 1) for trail-rung math. Persists state to a new set of three tables on the existing `marketdata` TimescaleDB instance. No frontend work — that's Plan 3.

**Tech Stack:** Java 21, Quarkus 3.17, Panache ORM, `quarkus-rest-client-reactive` (REST to Bybit), `quarkus-websockets` (client side to Bybit private WS + server side `/ws/execution`), `quarkus-redis-client` (subscribe), `quarkus-scheduler`, `shared-trade-core` (TrailCalculator, TrailConfig, RUnitMath). Tests: JUnit 5, WireMock for REST stubs.

**Spec reference:** `docs/superpowers/specs/2026-04-20-trade-execution-service-design.md`

**Prerequisite:** Plan 1 is merged (shared-trade-core module installed to local Maven repo and signal-service delegates to it).

---

## File structure

**Create:**

```
services/trade-execution-service/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/cryptoradar/execution/
    │   │   ├── Main.java                                  (trivial — Quarkus app marker)
    │   │   ├── client/
    │   │   │   ├── ExchangeClient.java                    (interface, future multi-exchange hook)
    │   │   │   └── bybit/
    │   │   │       ├── BybitV5Endpoints.java
    │   │   │       ├── BybitV5Signer.java
    │   │   │       ├── BybitV5SigningFilter.java          (JAX-RS ClientRequestFilter)
    │   │   │       ├── BybitV5RestClient.java
    │   │   │       ├── BybitV5WsClient.java
    │   │   │       └── dto/
    │   │   │           ├── BybitResponse.java             (generic wrapper {retCode, retMsg, result, time})
    │   │   │           ├── PositionV5.java
    │   │   │           ├── ExecutionV5.java
    │   │   │           ├── WalletV5.java
    │   │   │           ├── ClosedPnlV5.java
    │   │   │           └── ApiKeyPermissionsV5.java
    │   │   ├── security/
    │   │   │   ├── CredentialCipher.java
    │   │   │   └── PermissionValidator.java
    │   │   ├── model/
    │   │   │   ├── ExchangeAccount.java                   (@Entity)
    │   │   │   ├── ExecutedTrade.java                     (@Entity)
    │   │   │   ├── ExecutionEvent.java                    (@Entity)
    │   │   │   ├── TradeStatus.java                       (enum)
    │   │   │   ├── ExitReason.java                        (enum)
    │   │   │   └── ExecutionEventType.java                (enum)
    │   │   ├── repository/
    │   │   │   ├── ExchangeAccountRepository.java
    │   │   │   ├── ExecutedTradeRepository.java
    │   │   │   └── ExecutionEventRepository.java
    │   │   ├── intake/
    │   │   │   ├── SignalSubscriber.java
    │   │   │   └── FlipTracker.java
    │   │   ├── policy/
    │   │   │   └── GuardrailPolicy.java
    │   │   ├── lifecycle/
    │   │   │   ├── OrderPlacer.java
    │   │   │   ├── TrailMirror.java
    │   │   │   └── OrderReconciler.java
    │   │   ├── ws/
    │   │   │   ├── ExecutionWebSocket.java                (server-side /ws/execution)
    │   │   │   └── ExecutionBroadcaster.java
    │   │   └── resource/
    │   │       ├── AccountResource.java                   (/api/execution/accounts)
    │   │       ├── TradingResource.java                   (/api/execution/accounts/{id}/... position/trade/wallet/events)
    │   │       └── DevModeResource.java                   (/api/execution/test/*, behind flag)
    │   └── resources/
    │       ├── application.properties
    │       └── META-INF/
    └── test/
        └── java/com/cryptoradar/execution/... (tests matching each package)

db/init/execution-init.sql                                 (3 CREATE TABLE statements)
devops/overlays/dev/execution-db-init-sql.yaml             (ConfigMap with schema)
devops/base/trade-execution-service/                       (deployment + service + config manifest set, mirror signal-service)
```

**Modify:**

- `docker-compose.yml` — add `trade-execution-service` entry, mount schema init SQL into timescaledb volumes
- `services/api-gateway/pom.xml` — if `EXECUTION_SERVICE_URL` isn't already wired through config, add it
- `services/api-gateway/src/main/java/com/cryptoradar/gateway/client/ServiceClient.java` — add `executionUrl` getter
- `services/api-gateway/src/main/java/com/cryptoradar/gateway/resource/ProxyResource.java` — add `/api/execution/**` proxy handlers
- `services/api-gateway/src/main/java/com/cryptoradar/gateway/websocket/*` — add `/ws/execution` proxy (use existing CryptoWebSocket scaffold as template)
- `CLAUDE.md` — add trade-execution-service to the service/port table and architecture section
- `README.md` — add new service bullet under Features + port table row
- `.env.example` — add `BYBIT_EXECUTION_MASTER_KEY=REPLACE_ME_BASE64_AES256`, `EXECUTION_MAINNET_ENABLED=false`

**Not in scope** (Plan 3 frontend or later):

- `frontend/src/**` — Plan 3
- Alerts / notifications (Telegram, email)
- Per-strategy trail config wiring (will use `TrailConfig.DEFAULT` everywhere in phase 1)
- MAINNET onboarding (gated behind feature flag `execution.mainnet.enabled=false` — flip only after Stage 0 acceptance per spec)

---

## Task 1: Service skeleton + Dockerfile + compose entry

**Goal:** Empty-but-runnable Quarkus service that answers `/q/health/ready` on port 31087. No business logic yet.

**Files:**
- Create: `services/trade-execution-service/pom.xml`
- Create: `services/trade-execution-service/src/main/java/com/cryptoradar/execution/package-info.java`
- Create: `services/trade-execution-service/src/main/resources/application.properties`
- Create: `services/trade-execution-service/src/test/java/com/cryptoradar/execution/SanityTest.java`
- Create: `services/trade-execution-service/Dockerfile`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Create `pom.xml`**

Write `services/trade-execution-service/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.cryptoradar</groupId>
    <artifactId>trade-execution-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <quarkus.platform.version>3.17.5</quarkus.platform.version>
        <wiremock.version>3.9.1</wiremock.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.quarkus.platform</groupId>
                <artifactId>quarkus-bom</artifactId>
                <version>${quarkus.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Shared trade-outcome primitives (Plan 1) -->
        <dependency>
            <groupId>com.cryptoradar</groupId>
            <artifactId>shared-trade-core</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </dependency>

        <!-- RESTEasy Reactive -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-jackson</artifactId>
        </dependency>

        <!-- REST Client (outbound Bybit calls) -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-client</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-client-jackson</artifactId>
        </dependency>

        <!-- WebSockets (both server-side /ws/execution and client-side to Bybit) -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-websockets</artifactId>
        </dependency>

        <!-- Panache ORM on Postgres -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-orm-panache</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jdbc-postgresql</artifactId>
        </dependency>

        <!-- Redis (subscribe to crypto:signals) -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-redis-client</artifactId>
        </dependency>

        <!-- Scheduler (trail-mirror, reconciler) -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-scheduler</artifactId>
        </dependency>

        <!-- Health, fault tolerance -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-smallrye-health</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-smallrye-fault-tolerance</artifactId>
        </dependency>

        <!-- Tests -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.rest-assured</groupId>
            <artifactId>rest-assured</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>${wiremock.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.quarkus.platform</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <version>${quarkus.platform.version}</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <goals>
                            <goal>build</goal>
                            <goal>generate-code</goal>
                            <goal>generate-code-tests</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
                <configuration>
                    <systemPropertyVariables>
                        <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create `package-info.java`**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/package-info.java`:

```java
/**
 * Trade execution service root package.
 *
 * <p>Mirrors trading signals from {@code signal-service} to real orders on
 * external exchanges (Bybit V5 perp futures in phase 1). Consumes the
 * existing Redis {@code crypto:signals} channel; manages positions natively
 * on-exchange (TP/SL + trailing stop); persists execution state locally.
 */
package com.cryptoradar.execution;
```

- [ ] **Step 3: Create `application.properties`**

Write `services/trade-execution-service/src/main/resources/application.properties`:

```properties
quarkus.application.name=trade-execution-service
quarkus.http.port=8087
quarkus.http.cors=true
quarkus.http.cors.origins=http://localhost:31000,http://api-gateway:8080

# Redis (subscribe to crypto:signals)
quarkus.redis.hosts=redis://localhost:6379

# Datasource — same marketdata DB as signal-service
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5433/marketdata
quarkus.datasource.username=cryptoradar
quarkus.datasource.password=${TIMESCALE_PASSWORD:cryptoradar_ts_pass}
quarkus.hibernate-orm.database.generation=none

# Execution service config
execution.master-key=${EXECUTION_MASTER_KEY:}
execution.master-key-prev=${EXECUTION_MASTER_KEY_PREV:}
execution.mainnet.enabled=${EXECUTION_MAINNET_ENABLED:false}
execution.dev-mode.enabled=${EXECUTION_DEV_MODE_ENABLED:false}
execution.reconcile.interval=60s
execution.trail.interval=60s

# Market-data URL (for current price during trail calc)
market-data.url=${MARKET_DATA_URL:http://localhost:8081}

# Bybit base URLs (per environment — picked per exchange_account row)
bybit.demo.rest-base=https://api-demo.bybit.com
bybit.demo.ws-private=wss://stream-demo.bybit.com/v5/private
bybit.mainnet.rest-base=https://api.bybit.com
bybit.mainnet.ws-private=wss://stream.bybit.com/v5/private

quarkus.log.category."com.cryptoradar".level=INFO
```

- [ ] **Step 4: Create sanity test**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/SanityTest.java`:

```java
package com.cryptoradar.execution;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class SanityTest {

    @Test
    void healthEndpointUp() {
        given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body(containsString("UP"));
    }
}
```

- [ ] **Step 5: Create Dockerfile (multi-module, mirrors signal-service pattern)**

Write `services/trade-execution-service/Dockerfile`:

```dockerfile
# Stage 1: Install shared-trade-core into local Maven repo
FROM maven:3.9-eclipse-temurin-21-alpine AS shared

WORKDIR /build/shared-trade-core
COPY shared-trade-core/pom.xml ./pom.xml
COPY shared-trade-core/src ./src
RUN mvn install -DskipTests -B

# Stage 2: Build trade-execution-service
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

COPY --from=shared /root/.m2 /root/.m2

WORKDIR /build
COPY services/trade-execution-service/pom.xml ./pom.xml
RUN mvn dependency:go-offline -B

COPY services/trade-execution-service/src/ ./src/
RUN mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 1001 appgroup && \
    adduser -u 1001 -G appgroup -D appuser

WORKDIR /app

COPY --from=builder /build/target/*-runner.jar app.jar

EXPOSE 8087

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8087/q/health/ready || exit 1

USER 1001

CMD ["java", "-jar", "app.jar"]
```

- [ ] **Step 6: Add service to `docker-compose.yml`**

Open `docker-compose.yml`. Just after the `signal-service:` block ends (and before `api-gateway:` begins), insert:

```yaml
  trade-execution-service:
    build:
      context: .
      dockerfile: services/trade-execution-service/Dockerfile
    restart: unless-stopped
    ports:
      - "${EXECUTION_SERVICE_PORT:-31087}:8087"
    depends_on:
      timescaledb:
        condition: service_healthy
      redis:
        condition: service_healthy
      market-data-service:
        condition: service_started
      signal-service:
        condition: service_started
    environment:
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://timescaledb:5432/${TIMESCALE_DB}
      QUARKUS_DATASOURCE_USERNAME: ${TIMESCALE_USER}
      QUARKUS_DATASOURCE_PASSWORD: ${TIMESCALE_PASSWORD}
      QUARKUS_REDIS_HOSTS: redis://redis:6379
      MARKET_DATA_URL: http://market-data-service:8081
      EXECUTION_MASTER_KEY: ${EXECUTION_MASTER_KEY:-}
      EXECUTION_MASTER_KEY_PREV: ${EXECUTION_MASTER_KEY_PREV:-}
      EXECUTION_MAINNET_ENABLED: ${EXECUTION_MAINNET_ENABLED:-false}
      EXECUTION_DEV_MODE_ENABLED: ${EXECUTION_DEV_MODE_ENABLED:-false}
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
    networks:
      - cryptoradar-net
```

Also, in the `api-gateway:` block's `depends_on:`, add:
```yaml
      trade-execution-service:
        condition: service_started
```
and in its `environment:` block, add:
```yaml
      EXECUTION_SERVICE_URL: http://trade-execution-service:8087
```

- [ ] **Step 7: Add a placeholder master key to `.env` for the skeleton smoke test**

Append to `.env` (gitignored):
```
# Trade execution — dev-only placeholder, generate a real one before using
EXECUTION_MASTER_KEY=YW55LTMyLWJ5dGVzLWZvci1kZXZsb3BtZW50LW9ubHktbGVuMzI=
EXECUTION_MAINNET_ENABLED=false
EXECUTION_DEV_MODE_ENABLED=true
```

Append to `.env.example` (tracked):
```
# Trade execution
EXECUTION_MASTER_KEY=REPLACE_ME_BASE64_AES256
EXECUTION_MAINNET_ENABLED=false
EXECUTION_DEV_MODE_ENABLED=false
```

- [ ] **Step 8: Build + bring up + verify health**

```bash
docker compose build --no-cache trade-execution-service
docker compose up -d --no-deps trade-execution-service
```

Wait ~25s, then:
```bash
docker compose ps trade-execution-service
```
Expected: `Up (healthy)`.

```bash
curl -fsS http://localhost:31087/q/health/ready
```
Expected: JSON containing `"status":"UP"`.

- [ ] **Step 9: Commit**

```bash
git add services/trade-execution-service/ docker-compose.yml .env.example
git commit -m "feat(trade-execution): service skeleton + compose entry"
```

---

## Task 2: DB schema + mount in compose

**Goal:** Three new tables on the `marketdata` DB — `exchange_accounts`, `executed_trades`, `execution_events` — idempotent DDL, mounted into the TimescaleDB init volume.

**Files:**
- Create: `db/init/execution-init.sql`
- Modify: `docker-compose.yml` (add volume mount)

- [ ] **Step 1: Write the schema**

Write `db/init/execution-init.sql`:

```sql
-- Trade execution service — schema for Bybit (and future exchanges) mirroring
-- of signal-service trading signals to real orders.
--
-- Three tables, none are hypertables (low volume, point-lookup queries dominate).

-- =====================================================================
-- exchange_accounts — one row per (exchange, environment). Encrypted key
-- material + per-account settings (risk %, leverage, guardrails).
-- =====================================================================

CREATE TABLE IF NOT EXISTS exchange_accounts (
    id                          BIGSERIAL PRIMARY KEY,
    exchange                    VARCHAR(32)   NOT NULL,
    environment                 VARCHAR(16)   NOT NULL,
    api_key_encrypted           TEXT          NOT NULL,
    api_secret_encrypted        TEXT          NOT NULL,
    label                       VARCHAR(64),
    auto_trade_enabled          BOOLEAN       NOT NULL DEFAULT false,
    kill_switch                 BOOLEAN       NOT NULL DEFAULT true,
    risk_percent                NUMERIC(5,2)  NOT NULL DEFAULT 1.0,
    default_leverage            INT           NOT NULL DEFAULT 3,
    max_concurrent_positions    INT           NOT NULL DEFAULT 5,
    max_daily_loss_percent      NUMERIC(5,2)  NOT NULL DEFAULT 5.0,
    signal_age_seconds          INT           NOT NULL DEFAULT 60,
    position_max_age_hours      INT           NOT NULL DEFAULT 24,
    flip_persistence_ticks      INT           NOT NULL DEFAULT 2,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT exchange_accounts_exchange_env_uq UNIQUE (exchange, environment),
    CONSTRAINT exchange_accounts_env_ck
        CHECK (environment IN ('DEMO', 'MAINNET')),
    CONSTRAINT exchange_accounts_risk_pct_ck
        CHECK (risk_percent > 0 AND risk_percent <= 100),
    CONSTRAINT exchange_accounts_leverage_ck
        CHECK (default_leverage >= 1 AND default_leverage <= 50)
);

-- =====================================================================
-- executed_trades — one row per real position. Links signal_id back to
-- signal_outcomes for the "why" join. See spec for source-of-truth labels
-- per field (OURS vs Bybit).
-- =====================================================================

CREATE TABLE IF NOT EXISTS executed_trades (
    id                        BIGSERIAL PRIMARY KEY,
    exchange_account_id       BIGINT NOT NULL REFERENCES exchange_accounts(id) ON DELETE RESTRICT,
    signal_id                 VARCHAR(64),
    symbol                    VARCHAR(32) NOT NULL,
    direction                 VARCHAR(8)  NOT NULL,
    strategy                  VARCHAR(64),
    exchange_order_id         VARCHAR(64),
    exchange_order_link_id    VARCHAR(64),
    exchange_position_idx     INT,
    status                    VARCHAR(24) NOT NULL,
    entry_price               NUMERIC(20,8),
    qty                       NUMERIC(20,8),
    leverage                  INT,
    stop_price                NUMERIC(20,8),
    target_price              NUMERIC(20,8),
    dynamic_stop_price        NUMERIC(20,8),
    trail_highest_r           NUMERIC(10,4) DEFAULT 0,
    trail_triggered_at        TIMESTAMPTZ,
    exit_price                NUMERIC(20,8),
    exit_reason               VARCHAR(24),
    realized_pnl_usdt         NUMERIC(20,8),
    realized_r_multiple       NUMERIC(10,4),
    fees_usdt                 NUMERIC(20,8),
    opened_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at                 TIMESTAMPTZ,
    last_sync_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT executed_trades_direction_ck CHECK (direction IN ('LONG', 'SHORT')),
    CONSTRAINT executed_trades_status_ck
        CHECK (status IN ('PENDING_PLACE', 'OPEN', 'CLOSING', 'CLOSED', 'FAILED', 'CANCELLED')),
    CONSTRAINT executed_trades_exit_reason_ck
        CHECK (exit_reason IS NULL OR exit_reason IN
            ('TARGET', 'INITIAL_STOP', 'TRAIL_STOP', 'EXPIRED', 'FLIP_CLOSE', 'MANUAL', 'KILL'))
);

CREATE INDEX IF NOT EXISTS idx_executed_trades_signal          ON executed_trades(signal_id);
CREATE INDEX IF NOT EXISTS idx_executed_trades_status          ON executed_trades(status, opened_at DESC);
CREATE INDEX IF NOT EXISTS idx_executed_trades_account_symbol  ON executed_trades(exchange_account_id, symbol, status);

-- =====================================================================
-- execution_events — append-only audit log. Every decision and state
-- transition. JSONB metadata carries call-site context.
-- =====================================================================

CREATE TABLE IF NOT EXISTS execution_events (
    id                    BIGSERIAL PRIMARY KEY,
    exchange_account_id   BIGINT NOT NULL REFERENCES exchange_accounts(id) ON DELETE RESTRICT,
    event_type            VARCHAR(48) NOT NULL,
    signal_id             VARCHAR(64),
    executed_trade_id     BIGINT REFERENCES executed_trades(id) ON DELETE SET NULL,
    metadata              JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_execution_events_account ON execution_events(exchange_account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_execution_events_type    ON execution_events(event_type, created_at DESC);
```

- [ ] **Step 2: Mount the schema into timescaledb container**

Open `docker-compose.yml`. Find the `timescaledb:` block. In its `volumes:` list, append one more entry so the order matches the init-file naming convention:

```yaml
      - ./db/init/execution-init.sql:/docker-entrypoint-initdb.d/05-execution.sql
```

(The `05-` prefix places this after `04-signal.sql`.)

- [ ] **Step 3: Apply on a fresh DB OR manually on an existing DB**

The schema only gets auto-applied on a fresh TimescaleDB volume. Since the DB is already running with data, execute it manually once:

```bash
docker exec -i projectr-x-timescaledb-1 psql -U cryptoradar -d marketdata < db/init/execution-init.sql
```

Expected: all three `CREATE TABLE` and index statements succeed (idempotent — re-running is safe).

- [ ] **Step 4: Verify the schema landed**

```bash
docker exec projectr-x-timescaledb-1 psql -U cryptoradar -d marketdata -c "\dt exchange_accounts executed_trades execution_events"
```

Expected: three rows listing the new tables.

- [ ] **Step 5: Commit**

```bash
git add db/init/execution-init.sql docker-compose.yml
git commit -m "feat(trade-execution): db schema (exchange_accounts, executed_trades, execution_events)"
```

---

## Task 3: Entities + Repositories + Enums

**Goal:** `@Entity` classes with Panache repositories for the three tables, plus typed enums.

**Files:**
- Create: `.../model/ExchangeAccount.java`
- Create: `.../model/ExecutedTrade.java`
- Create: `.../model/ExecutionEvent.java`
- Create: `.../model/TradeStatus.java`
- Create: `.../model/ExitReason.java`
- Create: `.../model/ExecutionEventType.java`
- Create: `.../repository/ExchangeAccountRepository.java`
- Create: `.../repository/ExecutedTradeRepository.java`
- Create: `.../repository/ExecutionEventRepository.java`

- [ ] **Step 1: Create enums**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/model/TradeStatus.java`:

```java
package com.cryptoradar.execution.model;

public enum TradeStatus {
    PENDING_PLACE,
    OPEN,
    CLOSING,
    CLOSED,
    FAILED,
    CANCELLED
}
```

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/model/ExitReason.java`:

```java
package com.cryptoradar.execution.model;

public enum ExitReason {
    TARGET,
    INITIAL_STOP,
    TRAIL_STOP,
    EXPIRED,
    FLIP_CLOSE,
    MANUAL,
    KILL
}
```

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/model/ExecutionEventType.java`:

```java
package com.cryptoradar.execution.model;

public enum ExecutionEventType {
    SIGNAL_ACCEPTED,
    SIGNAL_BLOCKED_KILL_SWITCH,
    SIGNAL_BLOCKED_AUTO_TRADE_OFF,
    SIGNAL_BLOCKED_MAX_CONCURRENT,
    SIGNAL_BLOCKED_DAILY_HALT,
    SIGNAL_BLOCKED_DEDUP,
    SIGNAL_BLOCKED_SIGNAL_AGE,
    SIGNAL_BLOCKED_PERSISTENCE,
    SIGNAL_BLOCKED_INSUFFICIENT_MARGIN,
    ORDER_PLACED,
    ORDER_FILLED,
    ORDER_REJECTED,
    ORDER_CANCELLED,
    TRAIL_UPDATED,
    POSITION_CLOSED,
    KILL_SWITCH_TOGGLED,
    AUTO_TRADE_TOGGLED,
    DAILY_HALT_ENTERED,
    DAILY_HALT_EXITED,
    RECONCILE_ORPHAN_DETECTED,
    RECONCILE_CLOSED_EXTERNALLY,
    RECONCILE_DRIFT_DETECTED,
    AUTH_FAILURE,
    BYBIT_CIRCUIT_OPEN,
    WS_DISCONNECTED,
    WS_RECONNECTED,
    FLIP_CLOSE_TRIGGERED
}
```

- [ ] **Step 2: Create the `ExchangeAccount` entity**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/model/ExchangeAccount.java`:

```java
package com.cryptoradar.execution.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "exchange_accounts",
        uniqueConstraints = @UniqueConstraint(columnNames = {"exchange", "environment"}))
public class ExchangeAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String exchange;

    @Column(nullable = false, length = 16)
    private String environment;

    @Column(name = "api_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(name = "api_secret_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiSecretEncrypted;

    @Column(length = 64)
    private String label;

    @Column(name = "auto_trade_enabled", nullable = false)
    private boolean autoTradeEnabled = false;

    @Column(name = "kill_switch", nullable = false)
    private boolean killSwitch = true;

    @Column(name = "risk_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal riskPercent = new BigDecimal("1.00");

    @Column(name = "default_leverage", nullable = false)
    private int defaultLeverage = 3;

    @Column(name = "max_concurrent_positions", nullable = false)
    private int maxConcurrentPositions = 5;

    @Column(name = "max_daily_loss_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDailyLossPercent = new BigDecimal("5.00");

    @Column(name = "signal_age_seconds", nullable = false)
    private int signalAgeSeconds = 60;

    @Column(name = "position_max_age_hours", nullable = false)
    private int positionMaxAgeHours = 24;

    @Column(name = "flip_persistence_ticks", nullable = false)
    private int flipPersistenceTicks = 2;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }

    public String getApiSecretEncrypted() { return apiSecretEncrypted; }
    public void setApiSecretEncrypted(String apiSecretEncrypted) { this.apiSecretEncrypted = apiSecretEncrypted; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public boolean isAutoTradeEnabled() { return autoTradeEnabled; }
    public void setAutoTradeEnabled(boolean autoTradeEnabled) { this.autoTradeEnabled = autoTradeEnabled; }

    public boolean isKillSwitch() { return killSwitch; }
    public void setKillSwitch(boolean killSwitch) { this.killSwitch = killSwitch; }

    public BigDecimal getRiskPercent() { return riskPercent; }
    public void setRiskPercent(BigDecimal riskPercent) { this.riskPercent = riskPercent; }

    public int getDefaultLeverage() { return defaultLeverage; }
    public void setDefaultLeverage(int defaultLeverage) { this.defaultLeverage = defaultLeverage; }

    public int getMaxConcurrentPositions() { return maxConcurrentPositions; }
    public void setMaxConcurrentPositions(int maxConcurrentPositions) { this.maxConcurrentPositions = maxConcurrentPositions; }

    public BigDecimal getMaxDailyLossPercent() { return maxDailyLossPercent; }
    public void setMaxDailyLossPercent(BigDecimal maxDailyLossPercent) { this.maxDailyLossPercent = maxDailyLossPercent; }

    public int getSignalAgeSeconds() { return signalAgeSeconds; }
    public void setSignalAgeSeconds(int signalAgeSeconds) { this.signalAgeSeconds = signalAgeSeconds; }

    public int getPositionMaxAgeHours() { return positionMaxAgeHours; }
    public void setPositionMaxAgeHours(int positionMaxAgeHours) { this.positionMaxAgeHours = positionMaxAgeHours; }

    public int getFlipPersistenceTicks() { return flipPersistenceTicks; }
    public void setFlipPersistenceTicks(int flipPersistenceTicks) { this.flipPersistenceTicks = flipPersistenceTicks; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 3: Create the `ExecutedTrade` entity**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/model/ExecutedTrade.java`:

```java
package com.cryptoradar.execution.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "executed_trades")
public class ExecutedTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exchange_account_id", nullable = false)
    private Long exchangeAccountId;

    @Column(name = "signal_id", length = 64)
    private String signalId;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 8)
    private String direction;

    @Column(length = 64)
    private String strategy;

    @Column(name = "exchange_order_id", length = 64)
    private String exchangeOrderId;

    @Column(name = "exchange_order_link_id", length = 64)
    private String exchangeOrderLinkId;

    @Column(name = "exchange_position_idx")
    private Integer exchangePositionIdx;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TradeStatus status;

    @Column(name = "entry_price", precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal qty;

    @Column
    private Integer leverage;

    @Column(name = "stop_price", precision = 20, scale = 8)
    private BigDecimal stopPrice;

    @Column(name = "target_price", precision = 20, scale = 8)
    private BigDecimal targetPrice;

    @Column(name = "dynamic_stop_price", precision = 20, scale = 8)
    private BigDecimal dynamicStopPrice;

    @Column(name = "trail_highest_r", precision = 10, scale = 4)
    private BigDecimal trailHighestR = BigDecimal.ZERO;

    @Column(name = "trail_triggered_at")
    private Instant trailTriggeredAt;

    @Column(name = "exit_price", precision = 20, scale = 8)
    private BigDecimal exitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "exit_reason", length = 24)
    private ExitReason exitReason;

    @Column(name = "realized_pnl_usdt", precision = 20, scale = 8)
    private BigDecimal realizedPnlUsdt;

    @Column(name = "realized_r_multiple", precision = 10, scale = 4)
    private BigDecimal realizedRMultiple;

    @Column(name = "fees_usdt", precision = 20, scale = 8)
    private BigDecimal feesUsdt;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (openedAt == null) openedAt = now;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters + setters — generate all 22 pairs. For brevity in this plan,
    // use your IDE to generate them; they follow the same getX/setX pattern
    // shown in ExchangeAccount above.
    public Long getId() { return id; }
    public Long getExchangeAccountId() { return exchangeAccountId; }
    public void setExchangeAccountId(Long v) { this.exchangeAccountId = v; }
    public String getSignalId() { return signalId; }
    public void setSignalId(String v) { this.signalId = v; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }
    public String getDirection() { return direction; }
    public void setDirection(String v) { this.direction = v; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String v) { this.strategy = v; }
    public String getExchangeOrderId() { return exchangeOrderId; }
    public void setExchangeOrderId(String v) { this.exchangeOrderId = v; }
    public String getExchangeOrderLinkId() { return exchangeOrderLinkId; }
    public void setExchangeOrderLinkId(String v) { this.exchangeOrderLinkId = v; }
    public Integer getExchangePositionIdx() { return exchangePositionIdx; }
    public void setExchangePositionIdx(Integer v) { this.exchangePositionIdx = v; }
    public TradeStatus getStatus() { return status; }
    public void setStatus(TradeStatus v) { this.status = v; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal v) { this.entryPrice = v; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal v) { this.qty = v; }
    public Integer getLeverage() { return leverage; }
    public void setLeverage(Integer v) { this.leverage = v; }
    public BigDecimal getStopPrice() { return stopPrice; }
    public void setStopPrice(BigDecimal v) { this.stopPrice = v; }
    public BigDecimal getTargetPrice() { return targetPrice; }
    public void setTargetPrice(BigDecimal v) { this.targetPrice = v; }
    public BigDecimal getDynamicStopPrice() { return dynamicStopPrice; }
    public void setDynamicStopPrice(BigDecimal v) { this.dynamicStopPrice = v; }
    public BigDecimal getTrailHighestR() { return trailHighestR; }
    public void setTrailHighestR(BigDecimal v) { this.trailHighestR = v; }
    public Instant getTrailTriggeredAt() { return trailTriggeredAt; }
    public void setTrailTriggeredAt(Instant v) { this.trailTriggeredAt = v; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal v) { this.exitPrice = v; }
    public ExitReason getExitReason() { return exitReason; }
    public void setExitReason(ExitReason v) { this.exitReason = v; }
    public BigDecimal getRealizedPnlUsdt() { return realizedPnlUsdt; }
    public void setRealizedPnlUsdt(BigDecimal v) { this.realizedPnlUsdt = v; }
    public BigDecimal getRealizedRMultiple() { return realizedRMultiple; }
    public void setRealizedRMultiple(BigDecimal v) { this.realizedRMultiple = v; }
    public BigDecimal getFeesUsdt() { return feesUsdt; }
    public void setFeesUsdt(BigDecimal v) { this.feesUsdt = v; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant v) { this.openedAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant v) { this.lastSyncAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: Create the `ExecutionEvent` entity**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/model/ExecutionEvent.java`:

```java
package com.cryptoradar.execution.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "execution_events")
public class ExecutionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exchange_account_id", nullable = false)
    private Long exchangeAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 48)
    private ExecutionEventType eventType;

    @Column(name = "signal_id", length = 64)
    private String signalId;

    @Column(name = "executed_trade_id")
    private Long executedTradeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getExchangeAccountId() { return exchangeAccountId; }
    public void setExchangeAccountId(Long v) { this.exchangeAccountId = v; }
    public ExecutionEventType getEventType() { return eventType; }
    public void setEventType(ExecutionEventType v) { this.eventType = v; }
    public String getSignalId() { return signalId; }
    public void setSignalId(String v) { this.signalId = v; }
    public Long getExecutedTradeId() { return executedTradeId; }
    public void setExecutedTradeId(Long v) { this.executedTradeId = v; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> v) { this.metadata = v; }
    public Instant getCreatedAt() { return createdAt; }
}
```

(Note: `io.hypersistence.utils` import is only used if we need custom JSONB mapping; Hibernate 6.x can handle `Map<String, Object>` with `@JdbcTypeCode(SqlTypes.JSON)` without the hypersistence library. If Hibernate gives an error about Map marshalling, remove the `io.hypersistence.utils` import line — that's vestigial and not needed for the JdbcTypeCode approach.)

- [ ] **Step 5: Create repositories**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/repository/ExchangeAccountRepository.java`:

```java
package com.cryptoradar.execution.repository;

import com.cryptoradar.execution.model.ExchangeAccount;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class ExchangeAccountRepository implements PanacheRepository<ExchangeAccount> {

    public Optional<ExchangeAccount> findByExchangeAndEnvironment(String exchange, String environment) {
        return find("exchange = ?1 and environment = ?2", exchange, environment).firstResultOptional();
    }
}
```

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/repository/ExecutedTradeRepository.java`:

```java
package com.cryptoradar.execution.repository;

import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.TradeStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ExecutedTradeRepository implements PanacheRepository<ExecutedTrade> {

    private static final List<TradeStatus> OPEN_STATUSES =
            List.of(TradeStatus.PENDING_PLACE, TradeStatus.OPEN, TradeStatus.CLOSING);

    public List<ExecutedTrade> findOpenForAccount(Long accountId) {
        return find("exchangeAccountId = ?1 and status in ?2",
                Sort.descending("openedAt"), accountId, OPEN_STATUSES).list();
    }

    public Optional<ExecutedTrade> findOpenBySymbolAndDirectionAndStrategy(
            Long accountId, String symbol, String direction, String strategy) {
        return find(
                "exchangeAccountId = ?1 and symbol = ?2 and direction = ?3 and strategy = ?4 and status in ?5",
                accountId, symbol, direction, strategy, OPEN_STATUSES)
                .firstResultOptional();
    }

    public Optional<ExecutedTrade> findByOrderLinkId(String orderLinkId) {
        return find("exchangeOrderLinkId = ?1", orderLinkId).firstResultOptional();
    }

    public List<ExecutedTrade> findClosedSince(Long accountId, Instant since, int limit) {
        return find("exchangeAccountId = ?1 and status = ?2 and closedAt >= ?3",
                Sort.descending("closedAt"), accountId, TradeStatus.CLOSED, since)
                .page(0, limit).list();
    }

    public int countOpenForAccount(Long accountId) {
        return (int) count("exchangeAccountId = ?1 and status in ?2", accountId, OPEN_STATUSES);
    }
}
```

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/repository/ExecutionEventRepository.java`:

```java
package com.cryptoradar.execution.repository;

import com.cryptoradar.execution.model.ExecutionEvent;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ExecutionEventRepository implements PanacheRepository<ExecutionEvent> {

    public List<ExecutionEvent> findRecentForAccount(Long accountId, int limit) {
        return find("exchangeAccountId = ?1", Sort.descending("createdAt"), accountId)
                .page(0, limit).list();
    }
}
```

- [ ] **Step 6: Compile + container restart + smoke**

```bash
cd services/trade-execution-service && mvn compile -B
```
Expected: `BUILD SUCCESS`.

```bash
docker compose build --no-cache trade-execution-service
docker compose up -d --force-recreate --no-deps trade-execution-service
```
Wait ~25s, then `docker compose ps trade-execution-service` → `Up (healthy)`.

`docker compose logs trade-execution-service --tail=50` — look for successful Hibernate bootstrap (no schema-validation errors since we did `generation=none`).

- [ ] **Step 7: Commit**

```bash
git add services/trade-execution-service/
git commit -m "feat(trade-execution): entities + repositories for the 3 tables"
```

---

## Task 4: `CredentialCipher` — AES-GCM key encryption

**Goal:** Encrypt/decrypt API key + secret strings under a master key read from env. Never log plaintext.

**Files:**
- Create: `.../security/CredentialCipher.java`
- Create: test: `.../security/CredentialCipherTest.java`

- [ ] **Step 1: Write the failing tests**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/security/CredentialCipherTest.java`:

```java
package com.cryptoradar.execution.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialCipherTest {

    private static String freshKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void roundTripProducesOriginalPlaintext() {
        String key = freshKey();
        CredentialCipher cipher = new CredentialCipher(key, null);
        String plain = "sk_test_51AbCdEfGhIjKlMnOpQrStUvWxYz";
        String ct = cipher.encrypt(plain);
        assertEquals(plain, cipher.decrypt(ct));
    }

    @Test
    void twoEncryptionsOfSamePlaintextAreDifferent() {
        // IV is random per call — ciphertext should differ
        String key = freshKey();
        CredentialCipher cipher = new CredentialCipher(key, null);
        String ct1 = cipher.encrypt("secret");
        String ct2 = cipher.encrypt("secret");
        assertNotEquals(ct1, ct2);
        assertEquals("secret", cipher.decrypt(ct1));
        assertEquals("secret", cipher.decrypt(ct2));
    }

    @Test
    void decryptFailsWithWrongKey() {
        CredentialCipher cipher1 = new CredentialCipher(freshKey(), null);
        CredentialCipher cipher2 = new CredentialCipher(freshKey(), null);
        String ct = cipher1.encrypt("top-secret");
        assertThrows(RuntimeException.class, () -> cipher2.decrypt(ct));
    }

    @Test
    void previousKeyFallbackWorks() {
        // Encrypt under old key, decrypt under rotated cipher (new primary + old as prev)
        String oldKey = freshKey();
        String newKey = freshKey();
        CredentialCipher oldCipher = new CredentialCipher(oldKey, null);
        String ct = oldCipher.encrypt("rotated-secret");

        CredentialCipher rotated = new CredentialCipher(newKey, oldKey);
        assertEquals("rotated-secret", rotated.decrypt(ct));
    }

    @Test
    void ciphertextHasIvPrependedAndIsBase64() {
        String key = freshKey();
        CredentialCipher cipher = new CredentialCipher(key, null);
        String ct = cipher.encrypt("something");
        byte[] raw = Base64.getDecoder().decode(ct);
        // 12-byte IV + ≥(plaintext + 16-byte GCM tag) bytes
        assertTrue(raw.length >= 12 + "something".getBytes().length + 16);
    }

    @Test
    void missingMasterKeyRejectsConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialCipher(null, null));
        assertThrows(IllegalArgumentException.class, () -> new CredentialCipher("", null));
    }

    @Test
    void invalidBase64MasterKeyRejects() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialCipher("!!not-base64!!", null));
    }

    @Test
    void masterKeyWrongLengthRejects() {
        // 16 bytes is AES-128 — we require AES-256 (32 bytes)
        byte[] shortKey = new byte[16];
        new SecureRandom().nextBytes(shortKey);
        assertThrows(IllegalArgumentException.class,
                () -> new CredentialCipher(Base64.getEncoder().encodeToString(shortKey), null));
    }
}
```

- [ ] **Step 2: Run tests — expect failure (class doesn't exist)**

```bash
cd services/trade-execution-service && mvn test -Dtest=CredentialCipherTest -B
```
Expected: FAIL with `cannot find symbol: class CredentialCipher`.

- [ ] **Step 3: Write the implementation**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/security/CredentialCipher.java`:

```java
package com.cryptoradar.execution.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * AES-GCM encryption for exchange API credentials stored at rest.
 *
 * <p>Ciphertext format: <code>base64(IV(12 bytes) || ciphertext-with-gcm-tag)</code>.
 * The IV is random per encrypt call. The GCM tag (16 bytes) is appended by the
 * cipher after the encrypted content.
 *
 * <p>Master key must be a 32-byte AES key, provided base64-encoded via the
 * {@code execution.master-key} config. Optionally a second {@code
 * execution.master-key-prev} value can be provided during key rotation — the
 * cipher tries the primary key first and falls back to the previous key if
 * decryption fails (authentication tag mismatch).
 *
 * <p>Plaintext never enters a field, never gets logged. Callers must not pass
 * plaintext to a method that serializes (toString, log formatters, exceptions).
 */
@ApplicationScoped
public class CredentialCipher {

    private static final int AES_KEY_BYTES = 32;    // AES-256
    private static final int GCM_IV_BYTES = 12;     // 96-bit IV (GCM recommendation)
    private static final int GCM_TAG_BITS = 128;    // 16-byte tag
    private static final String CIPHER_SPEC = "AES/GCM/NoPadding";

    private final SecretKeySpec primaryKey;
    private final SecretKeySpec previousKey;   // may be null
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(
            @ConfigProperty(name = "execution.master-key") String masterKeyBase64,
            @ConfigProperty(name = "execution.master-key-prev", defaultValue = "") String prevKeyBase64) {
        this.primaryKey = loadKey(masterKeyBase64, "execution.master-key");
        this.previousKey = (prevKeyBase64 == null || prevKeyBase64.isBlank())
                ? null
                : loadKey(prevKeyBase64, "execution.master-key-prev");
    }

    private static SecretKeySpec loadKey(String b64, String configName) {
        if (b64 == null || b64.isBlank()) {
            throw new IllegalArgumentException(configName + " is required (base64-encoded 32-byte AES key)");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(configName + " is not valid base64", e);
        }
        if (raw.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException(
                    configName + " must decode to " + AES_KEY_BYTES + " bytes (got " + raw.length + ")");
        }
        return new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[GCM_IV_BYTES];
        random.nextBytes(iv);
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_SPEC);
            cipher.init(Cipher.ENCRYPT_MODE, primaryKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plainBytes);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            java.util.Arrays.fill(plainBytes, (byte) 0);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // Don't leak plaintext size or content
            throw new RuntimeException("encrypt failed", e);
        }
    }

    public String decrypt(String ciphertextBase64) {
        byte[] blob = Base64.getDecoder().decode(ciphertextBase64);
        if (blob.length < GCM_IV_BYTES + 16) {
            throw new IllegalArgumentException("ciphertext too short to contain IV + GCM tag");
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        byte[] ct = new byte[blob.length - GCM_IV_BYTES];
        System.arraycopy(blob, 0, iv, 0, GCM_IV_BYTES);
        System.arraycopy(blob, GCM_IV_BYTES, ct, 0, ct.length);

        return tryDecrypt(primaryKey, iv, ct)
                .or(() -> previousKey == null ? Optional.empty() : tryDecrypt(previousKey, iv, ct))
                .orElseThrow(() -> new RuntimeException("decrypt failed under current and previous keys"));
    }

    private Optional<String> tryDecrypt(SecretKeySpec key, byte[] iv, byte[] ct) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_SPEC);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ct);
            String s = new String(plain, StandardCharsets.UTF_8);
            java.util.Arrays.fill(plain, (byte) 0);
            return Optional.of(s);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
```

(Note: the `@ConfigProperty` constructor relies on CDI injection at runtime. The unit tests above instantiate the class directly via `new CredentialCipher(key, null)`, which exercises the normal constructor — this works because `@ConfigProperty` only matters when CDI resolves the bean, not when you instantiate it directly.)

- [ ] **Step 4: Run tests — expect pass**

```bash
cd services/trade-execution-service && mvn test -Dtest=CredentialCipherTest -B
```
Expected: PASS `Tests run: 8, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/security/CredentialCipher.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/security/CredentialCipherTest.java
git commit -m "feat(trade-execution): AES-GCM CredentialCipher for API key storage"
```

---

## Task 5: `BybitV5Signer` + REST client skeleton (unauthenticated smoke test)

**Goal:** HMAC-SHA256 request signer per Bybit V5 spec. A minimal REST client calling the unauthenticated `/v5/market/time` endpoint — proves the client + DTO wiring works.

**Files:**
- Create: `.../client/bybit/BybitV5Endpoints.java`
- Create: `.../client/bybit/BybitV5Signer.java`
- Create: `.../client/bybit/BybitV5RestClient.java` (skeleton, only `getServerTime`)
- Create: `.../client/bybit/dto/BybitResponse.java`
- Create: `.../client/bybit/dto/ServerTimeResult.java`
- Create: test: `.../client/bybit/BybitV5SignerTest.java`
- Create: test: `.../client/bybit/BybitV5RestClientSmokeTest.java`

- [ ] **Step 1: Write the signer test first**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/client/bybit/BybitV5SignerTest.java`:

```java
package com.cryptoradar.execution.client.bybit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BybitV5SignerTest {

    // Test vector from Bybit V5 API docs:
    //   timestamp  = 1672709911340
    //   api_key    = "XXXXXXXXXX"
    //   recv_window= 5000
    //   body       = {"category":"option","symbol":"BTC-30DEC22-18000-C","side":"Buy","orderType":"Limit","qty":"0.1","price":"18000","timeInForce":"GTC","orderLinkId":"option-test"}
    //   secret     = "XXXXXXXXXX"
    //
    // Expected sign from docs:
    //   "f3daa57b05d07a9f88b3cd29775bba3a13dd87c1dff50fcbfa86432760e45d37"
    // (The exact vector varies between doc revisions — what we really test is that the ALGORITHM produces
    // a valid 64-hex HMAC-SHA256 and matches our own independent computation.)

    @Test
    void signatureIsHexHmacSha256OfTimestampApiKeyRecvWindowPayload() {
        String signed = BybitV5Signer.sign(
                "secret",
                "1672709911340",
                "XXXXXXXXXX",
                "5000",
                "{\"x\":1}"
        );
        // 64-char lowercase hex (HMAC-SHA256 = 32 bytes = 64 hex chars)
        assertEquals(64, signed.length());
        assertEquals(signed, signed.toLowerCase());
        // Deterministic: same inputs → same output
        String again = BybitV5Signer.sign("secret", "1672709911340", "XXXXXXXXXX", "5000", "{\"x\":1}");
        assertEquals(signed, again);
    }

    @Test
    void differentTimestampProducesDifferentSignature() {
        String a = BybitV5Signer.sign("secret", "1000", "K", "5000", "body");
        String b = BybitV5Signer.sign("secret", "2000", "K", "5000", "body");
        assertEquals(64, a.length());
        assertEquals(64, b.length());
        // Cannot assert inequality rigorously (hash collisions theoretically possible)
        // but HMAC-SHA256 collisions are astronomically unlikely.
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }

    @Test
    void differentSecretProducesDifferentSignature() {
        String a = BybitV5Signer.sign("secret1", "1000", "K", "5000", "body");
        String b = BybitV5Signer.sign("secret2", "1000", "K", "5000", "body");
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }

    @Test
    void payloadConcatenationFormat() {
        // Per Bybit V5: payload = timestamp + api_key + recv_window + body
        // Verify we're signing the concatenation, not body alone
        String a = BybitV5Signer.sign("secret", "1000", "K", "5000", "body");
        String b = BybitV5Signer.sign("secret", "1000", "K", "5000", "body_different");
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}
```

- [ ] **Step 2: Run failing**

```bash
cd services/trade-execution-service && mvn test -Dtest=BybitV5SignerTest -B
```
Expected: FAIL with `cannot find symbol`.

- [ ] **Step 3: Write signer + endpoints**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5Signer.java`:

```java
package com.cryptoradar.execution.client.bybit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * HMAC-SHA256 request signing per Bybit V5 spec.
 *
 * <p>Signature = HMAC-SHA256(apiSecret, timestamp + apiKey + recvWindow + payload).
 * Payload is the query string (for GET) or raw body JSON (for POST).
 */
public final class BybitV5Signer {

    private BybitV5Signer() {}

    public static String sign(String apiSecret, String timestamp, String apiKey, String recvWindow, String payload) {
        String toSign = timestamp + apiKey + recvWindow + (payload == null ? "" : payload);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            return toHex(hmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
```

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5Endpoints.java`:

```java
package com.cryptoradar.execution.client.bybit;

/**
 * Base URLs per Bybit environment. Picked at construction time by the
 * REST client and WS client from the {@code exchange_accounts.environment}
 * column.
 */
public final class BybitV5Endpoints {

    public static final String REST_DEMO = "https://api-demo.bybit.com";
    public static final String REST_MAINNET = "https://api.bybit.com";
    public static final String WS_DEMO = "wss://stream-demo.bybit.com/v5/private";
    public static final String WS_MAINNET = "wss://stream.bybit.com/v5/private";

    public static String restBaseFor(String environment) {
        return switch (environment) {
            case "DEMO" -> REST_DEMO;
            case "MAINNET" -> REST_MAINNET;
            default -> throw new IllegalArgumentException("unknown environment: " + environment);
        };
    }

    public static String wsPrivateFor(String environment) {
        return switch (environment) {
            case "DEMO" -> WS_DEMO;
            case "MAINNET" -> WS_MAINNET;
            default -> throw new IllegalArgumentException("unknown environment: " + environment);
        };
    }

    private BybitV5Endpoints() {}
}
```

- [ ] **Step 4: Run signer tests — expect pass**

```bash
cd services/trade-execution-service && mvn test -Dtest=BybitV5SignerTest -B
```
Expected: PASS `Tests run: 4`.

- [ ] **Step 5: Write the generic response wrapper DTO**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/dto/BybitResponse.java`:

```java
package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Generic Bybit V5 response envelope: {retCode, retMsg, result, time, retExtInfo}.
 * The {@code result} payload is endpoint-specific; parameterize {@code T} per call site.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BybitResponse<T>(
        @JsonProperty("retCode") int retCode,
        @JsonProperty("retMsg") String retMsg,
        @JsonProperty("result") T result,
        @JsonProperty("time") long time
) {
    public boolean isOk() { return retCode == 0; }
}
```

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/dto/ServerTimeResult.java`:

```java
package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerTimeResult(
        @JsonProperty("timeSecond") String timeSecond,
        @JsonProperty("timeNano") String timeNano
) {}
```

- [ ] **Step 6: Write the REST client skeleton**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5RestClient.java`:

```java
package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.ServerTimeResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Bybit V5 REST client. This skeleton exposes only {@link #getServerTime(String)},
 * which is an unauthenticated probe used for:
 *  - Health check ("is Bybit reachable from this pod?")
 *  - Clock-skew diagnostics (compare server time vs local)
 *
 * Authenticated endpoints (order create, position list, etc.) are added in Task 6.
 */
@ApplicationScoped
public class BybitV5RestClient {

    private static final Logger LOG = Logger.getLogger(BybitV5RestClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject
    ObjectMapper mapper;

    public BybitResponse<ServerTimeResult> getServerTime(String environment) {
        String base = BybitV5Endpoints.restBaseFor(environment);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v5/market/time"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Bybit /v5/market/time returned status " + resp.statusCode());
            }
            return mapper.readValue(resp.body(),
                    mapper.getTypeFactory().constructParametricType(BybitResponse.class, ServerTimeResult.class));
        } catch (Exception e) {
            LOG.errorf("Bybit /v5/market/time failed for env=%s: %s", environment, e.getMessage());
            throw new RuntimeException("Bybit market-time call failed", e);
        }
    }
}
```

- [ ] **Step 7: Live smoke test (optional — may fail if Bybit is unreachable)**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/client/bybit/BybitV5RestClientSmokeTest.java`:

```java
package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.ServerTimeResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Disabled by default — hits live Bybit. Enable manually when validating
 * network path, DNS resolution, TLS handshake.
 */
@QuarkusTest
@Disabled("Hits live Bybit; enable manually")
class BybitV5RestClientSmokeTest {

    @Inject
    BybitV5RestClient client;

    @Test
    void reachesDemoServerAndParsesTime() {
        BybitResponse<ServerTimeResult> resp = client.getServerTime("DEMO");
        assertTrue(resp.isOk(), "retCode should be 0, got: " + resp.retCode() + " " + resp.retMsg());
        assertNotNull(resp.result());
        assertNotNull(resp.result().timeSecond());
    }
}
```

- [ ] **Step 8: Run signer tests**

```bash
cd services/trade-execution-service && mvn test -Dtest=BybitV5SignerTest -B
```
Expected: all signer tests pass. The smoke test is disabled.

- [ ] **Step 9: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/ \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/client/bybit/
git commit -m "feat(trade-execution): BybitV5Signer + REST client skeleton"
```

---

## Task 6: Bybit V5 authenticated REST endpoints

**Goal:** Implement all REST methods needed by the lifecycle code: set leverage, order create, trading-stop, position list, closed-pnl, wallet-balance, query-api, order cancel. All signed with `BybitV5Signer`, all tested against WireMock.

**Files:**
- Create: `.../client/bybit/dto/PositionV5.java`, `ExecutionV5.java`, `WalletV5.java`, `ClosedPnlV5.java`, `ApiKeyPermissionsV5.java`, `PlaceOrderResult.java`, `SetLeverageRequest.java`, `PlaceOrderRequest.java`, `TradingStopRequest.java`
- Modify: `.../client/bybit/BybitV5RestClient.java` (add methods)
- Create: test: `.../client/bybit/BybitV5RestClientTest.java` (WireMock-based)

- [ ] **Step 1: Add request + response DTOs**

For each DTO, create a single file under `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/dto/`. Keep each a Java record; decorate with `@JsonIgnoreProperties(ignoreUnknown = true)` and `@JsonProperty` where field names don't match Bybit camelCase.

Example `PositionV5.java`:
```java
package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PositionV5(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("side") String side,
        @JsonProperty("positionIdx") int positionIdx,
        @JsonProperty("size") String size,
        @JsonProperty("avgPrice") String avgPrice,
        @JsonProperty("leverage") String leverage,
        @JsonProperty("stopLoss") String stopLoss,
        @JsonProperty("takeProfit") String takeProfit,
        @JsonProperty("unrealisedPnl") String unrealisedPnl,
        @JsonProperty("createdTime") String createdTime,
        @JsonProperty("updatedTime") String updatedTime
) {}
```

Follow the same pattern for:
- `ExecutionV5` — `orderId, orderLinkId, symbol, side, orderType, execType, execPrice, execQty, execFee, execTime, stopOrderType`.
- `WalletV5` — `totalEquity, totalAvailableBalance, totalPerpUPL, totalWalletBalance`.
- `ClosedPnlV5` — `symbol, orderId, side, qty, orderPrice, closedPnl, openFee, closeFee, createdTime, updatedTime`.
- `ApiKeyPermissionsV5` — `permissions` as `Map<String, List<String>>` (e.g., `"Derivatives": ["Order", "Position"]`, `"Withdraw": []`).
- `PlaceOrderResult` — `orderId, orderLinkId`.
- `SetLeverageRequest` — `category, symbol, buyLeverage, sellLeverage` all as strings.
- `PlaceOrderRequest` — full set: `category, symbol, side, orderType, qty, takeProfit, stopLoss, tpslMode, tpOrderType, slOrderType, orderLinkId, reduceOnly` (reduceOnly optional boolean).
- `TradingStopRequest` — `category, symbol, stopLoss, tpslMode, positionIdx`.

Explicit full writeouts for the ones that drive the order-placing and the permission validator:

`ApiKeyPermissionsV5.java`:
```java
package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiKeyPermissionsV5(
        @JsonProperty("id") String id,
        @JsonProperty("note") String note,
        @JsonProperty("apiKey") String apiKey,
        @JsonProperty("readOnly") int readOnly,
        @JsonProperty("permissions") Map<String, List<String>> permissions
) {}
```

`PlaceOrderRequest.java`:
```java
package com.cryptoradar.execution.client.bybit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceOrderRequest(
        @JsonProperty("category") String category,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("side") String side,           // Buy / Sell
        @JsonProperty("orderType") String orderType, // Market / Limit
        @JsonProperty("qty") String qty,
        @JsonProperty("takeProfit") String takeProfit,
        @JsonProperty("stopLoss") String stopLoss,
        @JsonProperty("tpslMode") String tpslMode,
        @JsonProperty("tpOrderType") String tpOrderType,
        @JsonProperty("slOrderType") String slOrderType,
        @JsonProperty("orderLinkId") String orderLinkId,
        @JsonProperty("reduceOnly") Boolean reduceOnly
) {}
```

- [ ] **Step 2: Add client methods — signed GET + POST helpers**

In `BybitV5RestClient.java`, add private helpers and the eight new methods. Replace the single-method skeleton with this full version:

```java
package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.*;
import com.cryptoradar.execution.security.CredentialCipher;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class BybitV5RestClient {

    private static final Logger LOG = Logger.getLogger(BybitV5RestClient.class);
    private static final String RECV_WINDOW = "5000";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject ObjectMapper mapper;
    @Inject CredentialCipher cipher;

    // --- Unauthenticated ---

    public BybitResponse<ServerTimeResult> getServerTime(String environment) {
        return unauthGet(environment, "/v5/market/time", ServerTimeResult.class);
    }

    // --- Authenticated GETs ---

    public BybitResponse<ApiKeyPermissionsV5> queryApiKey(String environment, String apiKeyCipher, String apiSecretCipher) {
        return authGet(environment, "/v5/user/query-api", "",
                apiKeyCipher, apiSecretCipher, ApiKeyPermissionsV5.class);
    }

    public BybitResponse<ListResult<WalletV5>> getWalletBalance(String environment, String apiKeyCipher, String apiSecretCipher) {
        String qs = "accountType=UNIFIED";
        return authGet(environment, "/v5/account/wallet-balance", qs,
                apiKeyCipher, apiSecretCipher, listOf(WalletV5.class));
    }

    public BybitResponse<ListResult<PositionV5>> getPositionList(String environment, String apiKeyCipher, String apiSecretCipher) {
        String qs = "category=linear&settleCoin=USDT";
        return authGet(environment, "/v5/position/list", qs,
                apiKeyCipher, apiSecretCipher, listOf(PositionV5.class));
    }

    public BybitResponse<ListResult<ClosedPnlV5>> getClosedPnl(
            String environment, String apiKeyCipher, String apiSecretCipher, String symbol, int limit) {
        String qs = "category=linear&symbol=" + symbol + "&limit=" + limit;
        return authGet(environment, "/v5/position/closed-pnl", qs,
                apiKeyCipher, apiSecretCipher, listOf(ClosedPnlV5.class));
    }

    // --- Authenticated POSTs ---

    public BybitResponse<Map<String, Object>> setLeverage(String environment, String apiKeyCipher, String apiSecretCipher,
                                                           String symbol, int leverage) {
        SetLeverageRequest body = new SetLeverageRequest("linear", symbol, String.valueOf(leverage), String.valueOf(leverage));
        return authPost(environment, "/v5/position/set-leverage", body,
                apiKeyCipher, apiSecretCipher, mapType());
    }

    public BybitResponse<PlaceOrderResult> placeOrder(String environment, String apiKeyCipher, String apiSecretCipher,
                                                       PlaceOrderRequest req) {
        return authPost(environment, "/v5/order/create", req,
                apiKeyCipher, apiSecretCipher, PlaceOrderResult.class);
    }

    public BybitResponse<Map<String, Object>> setTradingStop(String environment, String apiKeyCipher, String apiSecretCipher,
                                                              TradingStopRequest req) {
        return authPost(environment, "/v5/position/trading-stop", req,
                apiKeyCipher, apiSecretCipher, mapType());
    }

    public BybitResponse<PlaceOrderResult> cancelOrder(String environment, String apiKeyCipher, String apiSecretCipher,
                                                        String symbol, String orderId) {
        Map<String, String> body = Map.of("category", "linear", "symbol", symbol, "orderId", orderId);
        return authPost(environment, "/v5/order/cancel", body,
                apiKeyCipher, apiSecretCipher, PlaceOrderResult.class);
    }

    // --- Internals ---

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private JavaType listOf(Class<?> elem) {
        return mapper.getTypeFactory().constructParametricType(ListResult.class, elem);
    }

    // Shape: result = { "list": [...] } for many Bybit list endpoints.
    public record ListResult<T>(@com.fasterxml.jackson.annotation.JsonProperty("list") List<T> list) {}

    private <T> BybitResponse<T> unauthGet(String environment, String path, Class<T> resultType) {
        return executeGet(environment, path, "", Map.of(), resultType);
    }

    private <T> BybitResponse<T> authGet(String environment, String path, String queryString,
                                          String keyCipher, String secretCipher, Class<T> resultType) {
        return executeGet(environment, path, queryString,
                signedHeaders(keyCipher, secretCipher, queryString), resultType);
    }

    private <T> BybitResponse<T> authGet(String environment, String path, String queryString,
                                          String keyCipher, String secretCipher, JavaType type) {
        return executeGet(environment, path, queryString,
                signedHeaders(keyCipher, secretCipher, queryString), type);
    }

    private <T> BybitResponse<T> authPost(String environment, String path, Object body,
                                           String keyCipher, String secretCipher, Class<T> resultType) {
        String json = writeJson(body);
        return executePost(environment, path, json, signedHeaders(keyCipher, secretCipher, json), resultType);
    }

    private Map<String, String> signedHeaders(String keyCipher, String secretCipher, String payload) {
        String apiKey = cipher.decrypt(keyCipher);
        String apiSecret = cipher.decrypt(secretCipher);
        String ts = String.valueOf(System.currentTimeMillis());
        String sig = BybitV5Signer.sign(apiSecret, ts, apiKey, RECV_WINDOW, payload);
        Map<String, String> h = new TreeMap<>();
        h.put("X-BAPI-API-KEY", apiKey);
        h.put("X-BAPI-TIMESTAMP", ts);
        h.put("X-BAPI-RECV-WINDOW", RECV_WINDOW);
        h.put("X-BAPI-SIGN", sig);
        return h;
    }

    private <T> BybitResponse<T> executeGet(String environment, String path, String qs,
                                             Map<String, String> headers, Class<T> resultType) {
        URI uri = URI.create(BybitV5Endpoints.restBaseFor(environment) + path + (qs.isEmpty() ? "" : "?" + qs));
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
        headers.forEach(rb::header);
        return send(rb.build(), resultType);
    }

    private <T> BybitResponse<T> executeGet(String environment, String path, String qs,
                                             Map<String, String> headers, JavaType type) {
        URI uri = URI.create(BybitV5Endpoints.restBaseFor(environment) + path + (qs.isEmpty() ? "" : "?" + qs));
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
        headers.forEach(rb::header);
        return sendGeneric(rb.build(), type);
    }

    private <T> BybitResponse<T> executePost(String environment, String path, String jsonBody,
                                              Map<String, String> headers, Class<T> resultType) {
        URI uri = URI.create(BybitV5Endpoints.restBaseFor(environment) + path);
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json");
        headers.forEach(rb::header);
        return send(rb.build(), resultType);
    }

    private <T> BybitResponse<T> send(HttpRequest req, Class<T> resultType) {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JavaType type = mapper.getTypeFactory().constructParametricType(BybitResponse.class, resultType);
            return mapper.readValue(resp.body(), type);
        } catch (Exception e) {
            LOG.errorf("Bybit %s failed: %s", req.uri().getPath(), e.getMessage());
            throw new RuntimeException("Bybit call failed: " + req.uri().getPath(), e);
        }
    }

    private <T> BybitResponse<T> sendGeneric(HttpRequest req, JavaType resultType) {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JavaType type = mapper.getTypeFactory().constructParametricType(BybitResponse.class, resultType);
            return mapper.readValue(resp.body(), type);
        } catch (Exception e) {
            LOG.errorf("Bybit %s failed: %s", req.uri().getPath(), e.getMessage());
            throw new RuntimeException("Bybit call failed: " + req.uri().getPath(), e);
        }
    }

    private String writeJson(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }
}
```

- [ ] **Step 3: Add WireMock-based integration tests**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/client/bybit/BybitV5RestClientTest.java`:

```java
package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.*;
import com.cryptoradar.execution.security.CredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Points the "DEMO" base URL to WireMock instead of api-demo.bybit.com, so we
 * can stub the entire Bybit REST surface without network calls.
 */
@QuarkusTest
@TestProfile(BybitV5RestClientTest.WireMockProfile.class)
class BybitV5RestClientTest {

    static WireMockServer wireMock;

    public static class WireMockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // For the test, override the Bybit DEMO base URL to WireMock's.
            // The client uses BybitV5Endpoints.REST_DEMO as the hardcoded base; we need
            // to make that configurable. If the client is using hardcoded constants,
            // the test will need to pass environment="WIREMOCK" and endpoint config
            // has to dispatch to a test base. For simplicity: wire-mock runs on
            // port 38099 and BybitV5RestClient hardcodes that in tests only via a
            // conditional that inspects a system property set here.
            return Map.of("bybit.rest-base-override.DEMO", "http://localhost:38099",
                    "execution.master-key", freshKey());
        }
    }

    private static String freshKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Inject BybitV5RestClient client;
    @Inject CredentialCipher cipher;
    @Inject ObjectMapper mapper;

    String apiKeyCipher;
    String apiSecretCipher;

    @BeforeEach
    void setup() {
        wireMock = new WireMockServer(38099);
        wireMock.start();
        WireMock.configureFor("localhost", 38099);
        apiKeyCipher = cipher.encrypt("test-api-key");
        apiSecretCipher = cipher.encrypt("test-api-secret");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void getServerTimeParsesOkResponse() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/market/time"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("timeSecond", "1700000000", "timeNano", "1700000000000000000"),
                        "time", 1700000000000L
                )))));

        BybitResponse<ServerTimeResult> resp = client.getServerTime("DEMO");
        assertTrue(resp.isOk());
        assertEquals("1700000000", resp.result().timeSecond());
    }

    @Test
    void queryApiKeyReturnsPermissions() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/user/query-api"))
                .withHeader("X-BAPI-API-KEY", equalTo("test-api-key"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of(
                                "id", "1234",
                                "apiKey", "test-api-key",
                                "readOnly", 0,
                                "permissions", Map.of(
                                        "Derivatives", java.util.List.of("Order", "Position"),
                                        "Wallet", java.util.List.of("AccountTransfer"),
                                        "Withdraw", java.util.List.of()
                                )
                        ),
                        "time", 1700000000000L
                )))));

        BybitResponse<ApiKeyPermissionsV5> resp = client.queryApiKey("DEMO", apiKeyCipher, apiSecretCipher);
        assertTrue(resp.isOk());
        assertTrue(resp.result().permissions().containsKey("Derivatives"));
        assertTrue(resp.result().permissions().get("Withdraw").isEmpty());
    }

    @Test
    void placeOrderSendsJsonBodyAndParsesResult() throws Exception {
        stubFor(post(urlPathEqualTo("/v5/order/create"))
                .withHeader("X-BAPI-SIGN", matching("[a-f0-9]{64}"))
                .withRequestBody(matchingJsonPath("$.symbol", equalTo("BTCUSDT")))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("orderId", "OID-1", "orderLinkId", "ex-1"),
                        "time", 1700000000000L
                )))));

        PlaceOrderRequest req = new PlaceOrderRequest(
                "linear", "BTCUSDT", "Buy", "Market", "0.001",
                "50500", "49500", "Full", "Market", "Market", "ex-1", null);
        BybitResponse<PlaceOrderResult> resp = client.placeOrder("DEMO", apiKeyCipher, apiSecretCipher, req);
        assertTrue(resp.isOk());
        assertEquals("OID-1", resp.result().orderId());
    }
}
```

**Important:** This test requires a small change to `BybitV5Endpoints` or `BybitV5RestClient` to allow a base-URL override at test time. Simplest approach — add an optional `@ConfigProperty(name = "bybit.rest-base-override.DEMO", defaultValue = "")` field and have the client prefer it when set. If that's too intrusive, the alternative is to mock at the HTTP client level with `HttpClient.Builder` — but the config-override approach is cleanest.

Add to `BybitV5RestClient`:
```java
@ConfigProperty(name = "bybit.rest-base-override.DEMO", defaultValue = "")
String demoBaseOverride;
@ConfigProperty(name = "bybit.rest-base-override.MAINNET", defaultValue = "")
String mainnetBaseOverride;

private String baseFor(String environment) {
    String override = switch (environment) {
        case "DEMO" -> demoBaseOverride;
        case "MAINNET" -> mainnetBaseOverride;
        default -> "";
    };
    return override.isEmpty() ? BybitV5Endpoints.restBaseFor(environment) : override;
}
```
Then replace `BybitV5Endpoints.restBaseFor(environment)` in the send methods with `baseFor(environment)`.

- [ ] **Step 4: Run tests**

```bash
cd services/trade-execution-service && mvn test -Dtest=BybitV5RestClientTest -B
```
Expected: PASS (at least 3 tests).

- [ ] **Step 5: Commit**

```bash
git add services/trade-execution-service/src/
git commit -m "feat(trade-execution): Bybit V5 authenticated REST endpoints"
```

---

## Task 7: `PermissionValidator` — reject keys with withdraw permission

**Goal:** On account creation, call `queryApiKey` and reject if the key can withdraw.

**Files:**
- Create: `.../security/PermissionValidator.java`
- Create: test: `.../security/PermissionValidatorTest.java`

- [ ] **Step 1: Write failing test**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/security/PermissionValidatorTest.java`:

```java
package com.cryptoradar.execution.security;

import com.cryptoradar.execution.client.bybit.dto.ApiKeyPermissionsV5;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PermissionValidatorTest {

    private ApiKeyPermissionsV5 perms(Map<String, List<String>> permissions) {
        return new ApiKeyPermissionsV5("1", "note", "key", 0, permissions);
    }

    @Test
    void acceptsDerivativesOrderPositionWithoutWithdraw() {
        assertDoesNotThrow(() -> PermissionValidator.validate(perms(Map.of(
                "Derivatives", List.of("Order", "Position"),
                "Wallet", List.of("AccountTransfer"),
                "Withdraw", List.of()
        ))));
    }

    @Test
    void rejectsIfWithdrawPresent() {
        assertThrows(IllegalStateException.class, () -> PermissionValidator.validate(perms(Map.of(
                "Derivatives", List.of("Order", "Position"),
                "Withdraw", List.of("Asset")
        ))));
    }

    @Test
    void rejectsIfMissingDerivativesOrder() {
        assertThrows(IllegalStateException.class, () -> PermissionValidator.validate(perms(Map.of(
                "Derivatives", List.of("Position"),
                "Withdraw", List.of()
        ))));
    }

    @Test
    void rejectsIfMissingDerivativesPosition() {
        assertThrows(IllegalStateException.class, () -> PermissionValidator.validate(perms(Map.of(
                "Derivatives", List.of("Order"),
                "Withdraw", List.of()
        ))));
    }

    @Test
    void rejectsNullPermissionsMap() {
        assertThrows(IllegalStateException.class, () -> PermissionValidator.validate(
                new ApiKeyPermissionsV5("1", "", "k", 0, null)));
    }
}
```

- [ ] **Step 2: Implement**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/security/PermissionValidator.java`:

```java
package com.cryptoradar.execution.security;

import com.cryptoradar.execution.client.bybit.dto.ApiKeyPermissionsV5;

import java.util.List;
import java.util.Map;

/**
 * Validates that a Bybit API key has ONLY the permissions we need — never the
 * withdrawal permission. Reject at account creation time, refuse to store.
 */
public final class PermissionValidator {

    private PermissionValidator() {}

    public static void validate(ApiKeyPermissionsV5 perms) {
        Map<String, List<String>> map = perms.permissions();
        if (map == null) {
            throw new IllegalStateException("API key permissions missing — cannot validate");
        }
        List<String> withdraw = map.getOrDefault("Withdraw", List.of());
        if (!withdraw.isEmpty()) {
            throw new IllegalStateException(
                    "API key has withdraw permission — refused. Remove ALL 'Withdraw' permissions in Bybit UI and reissue the key.");
        }
        List<String> derivatives = map.getOrDefault("Derivatives", List.of());
        if (!derivatives.contains("Order")) {
            throw new IllegalStateException("API key missing Derivatives:Order permission");
        }
        if (!derivatives.contains("Position")) {
            throw new IllegalStateException("API key missing Derivatives:Position permission");
        }
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
cd services/trade-execution-service && mvn test -Dtest=PermissionValidatorTest -B
```
Expected: 5 passing.

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/security/PermissionValidator.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/security/PermissionValidatorTest.java
git commit -m "feat(trade-execution): PermissionValidator rejects withdraw-enabled keys"
```

---

## Task 8: `AccountResource` — POST/GET/PATCH/DELETE for accounts

**Goal:** REST surface to add/view/update/remove exchange accounts. POST validates the key via `queryApiKey` + `PermissionValidator`, encrypts, persists.

**Files:**
- Create: `.../resource/AccountResource.java`
- Create: `.../resource/dto/` (several DTOs: `CreateAccountRequest`, `UpdateAccountRequest`, `AccountView`)
- Create: test: `.../resource/AccountResourceTest.java`

(Full code for this task runs ~400 lines. Implementer follows established Quarkus REST patterns. The pattern: `POST /api/execution/accounts` → validate input → call Bybit queryApiKey via the REST client → PermissionValidator → encrypt via CredentialCipher → Panache `persist` → return `AccountView` with id + masked key (last-4-of-api-key format for display).)

- [ ] **Step 1: Create the request/response DTOs**

`CreateAccountRequest` record with fields `exchange, environment, apiKey, apiSecret, label, riskPercent (optional), defaultLeverage (optional), maxConcurrentPositions (optional), maxDailyLossPercent (optional), signalAgeSeconds (optional), positionMaxAgeHours (optional), flipPersistenceTicks (optional)`. All `String`/`Integer`/`BigDecimal` as appropriate; use `@NotBlank` / `@Positive` Jakarta validation annotations.

`UpdateAccountRequest` record — all fields optional, no apiKey/apiSecret fields (those are immutable once set — rotation requires delete + re-add).

`AccountView` record for responses — every settings field, plus the account id, label, exchange, environment, createdAt, updatedAt, and a `keyMask` field showing last 4 characters of the API key for UI display. Never exposes `apiSecret` (encrypted or plain).

- [ ] **Step 2: Implement AccountResource**

Full code goes in `services/trade-execution-service/src/main/java/com/cryptoradar/execution/resource/AccountResource.java`. Key behaviors:

- `POST /api/execution/accounts`:
  - Reject if `environment=MAINNET && !config.mainnetEnabled` → 400.
  - Reject if `(exchange, environment)` already exists → 409.
  - Call `bybitClient.queryApiKey(env, encryptInMemory(apiKey), encryptInMemory(apiSecret))` — but wait, permissions validator works on the raw response, not the cipher round-trip. Instead: encrypt the provided credentials for persistence, THEN call queryApiKey using the just-encrypted values (which the REST client will decrypt internally). If queryApiKey fails (non-zero retCode or exception): return 400.
  - Call `PermissionValidator.validate(resp.result())` — catches IllegalStateException, returns 400 with message.
  - Persist the `ExchangeAccount` entity with defaults + user-provided overrides.
  - Return 201 with `AccountView`.

- `GET /api/execution/accounts`: list all.
- `GET /api/execution/accounts/{id}`: single, 404 if missing.
- `PATCH /api/execution/accounts/{id}`: apply any non-null field from `UpdateAccountRequest`. `@Transactional`.
- `DELETE /api/execution/accounts/{id}`: reject if any OPEN `executed_trades` row references the account (409); else delete.

- [ ] **Step 3: Write integration tests using @QuarkusTest + mock bybit**

Test cases:
- Happy path: POST → 201, key validated + encrypted → GET returns single → GET by id returns correct → PATCH updates risk% → DELETE works
- MAINNET gated: set `execution.mainnet.enabled=false`, POST with `environment=MAINNET` → 400
- Withdraw permission: stub queryApiKey to return withdraw permission → POST → 400 with message mentioning withdraw
- Duplicate: POST twice with same (exchange, environment) → second returns 409
- DELETE with open trade: seed an ExecutedTrade, try to delete → 409

- [ ] **Step 4: Run + commit**

```bash
cd services/trade-execution-service && mvn test -B
```
Expected: all new + pre-existing tests pass.

```bash
git add services/trade-execution-service/src/
git commit -m "feat(trade-execution): AccountResource CRUD with key validation"
```

---

## Task 9: `FlipTracker` — 2-tick persistence for signal reversals

**Goal:** In-memory per-symbol counter that emits `CLOSE_LONG` / `CLOSE_SHORT` only after N consecutive opposite STRONG signals. Configurable N per-account.

**Files:**
- Create: `.../intake/FlipTracker.java`
- Create: test: `.../intake/FlipTrackerTest.java`

- [ ] **Step 1: Write tests (TDD)**

Test cases:
- `observe` with single STRONG_BUY on a symbol not currently held → returns `ENTER_LONG` — NO wait, the plan says 2-tick, and the first observation is the first tick. It should return `NO_ACTION` until the second same-direction tick.
- Two consecutive STRONG_BUY → second returns `ENTER_LONG`.
- STRONG_BUY then STRONG_SELL → resets counter, returns `NO_ACTION`.
- Two STRONG_SELL on a symbol we're LONG on → returns `CLOSE_LONG`.
- Two STRONG_BUY on a symbol we're SHORT on → returns `CLOSE_SHORT`.
- Configurable `persistenceTicks=1` → single tick fires immediately (for tests; prod uses 2).

- [ ] **Step 2: Implement**

```java
package com.cryptoradar.execution.intake;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class FlipTracker {

    public enum Action { NO_ACTION, ENTER_LONG, ENTER_SHORT, CLOSE_LONG, CLOSE_SHORT }

    private record Counter(String lastDirection, int streak) {}

    private final Map<String, Counter> state = new ConcurrentHashMap<>();

    /**
     * @param symbol     ticker, e.g., BTCUSDT
     * @param signalLabel raw signal label (e.g., "STRONG_BUY" / "STRONG_SELL")
     * @param persistenceTicks consecutive ticks required before action
     * @param currentlyLong are we currently holding a LONG position in this symbol?
     * @param currentlyShort are we currently holding a SHORT position?
     */
    public Action observe(String symbol, String signalLabel, int persistenceTicks,
                           boolean currentlyLong, boolean currentlyShort) {
        String dir = signalToDirection(signalLabel);
        if (dir == null) {
            // neutral or non-strong — reset the streak but do nothing
            state.remove(symbol);
            return Action.NO_ACTION;
        }
        Counter prev = state.get(symbol);
        int streak = (prev != null && prev.lastDirection().equals(dir)) ? prev.streak() + 1 : 1;
        state.put(symbol, new Counter(dir, streak));

        if (streak < persistenceTicks) return Action.NO_ACTION;

        if ("LONG".equals(dir)) {
            if (currentlyShort) return Action.CLOSE_SHORT;
            if (!currentlyLong) return Action.ENTER_LONG;
            return Action.NO_ACTION;           // already long, same direction, no action
        } else { // SHORT
            if (currentlyLong) return Action.CLOSE_LONG;
            if (!currentlyShort) return Action.ENTER_SHORT;
            return Action.NO_ACTION;
        }
    }

    private String signalToDirection(String signalLabel) {
        return switch (signalLabel) {
            case "STRONG_BUY" -> "LONG";
            case "STRONG_SELL" -> "SHORT";
            default -> null;
        };
    }
}
```

- [ ] **Step 3: Run + commit**

Full test suite in the file, expect 6+ passing.

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/FlipTracker.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/intake/FlipTrackerTest.java
git commit -m "feat(trade-execution): FlipTracker with configurable N-tick persistence"
```

---

## Task 10: `GuardrailPolicy` — six-rule signal gate

**Goal:** Pure evaluator that returns ACCEPT or BLOCK(reason) for a given (account, candidate signal, account runtime state) tuple.

**Files:**
- Create: `.../policy/GuardrailPolicy.java`
- Create: test: `.../policy/GuardrailPolicyTest.java`

Structure identical to the spec's 6 rules (order: kill_switch, auto_trade_off, signal_age, max_concurrent, daily_halt, dedup). `evaluate(ExchangeAccount, CandidateContext)` → `Decision` record.

Full test coverage: one test per rule with matrix (rule-triggered and not-triggered), plus a happy path.

---

## Task 11: `OrderPlacer` — place order with TP/SL attached

**Goal:** Given a candidate signal, compute qty via `RUnitMath.computeQty`, insert `executed_trades` row with status=PENDING_PLACE, call `setLeverage` + `placeOrder` on Bybit.

**Files:**
- Create: `.../lifecycle/OrderPlacer.java`
- Create: test: `.../lifecycle/OrderPlacerTest.java`

Key methods:
- `place(Candidate, ExchangeAccount)` — the full pipeline.
- `close(symbol, direction, reason)` — market-close with reduceOnly for flip-close / expire / manual / kill-all.

Test (against WireMock for Bybit):
- Happy path: order placed, 110043 (leverage unchanged) ignored, row inserted with orderLinkId, Bybit called with expected qty + stopLoss/takeProfit.
- 110007 insufficient margin: row marked FAILED.
- 110061 duplicate orderLinkId: treated as success.
- Other error: row marked FAILED, ORDER_REJECTED event emitted.

---

## Task 12: `TrailMirror` — scheduled trail-stop updater

**Goal:** `@Scheduled(every="60s")` — for each OPEN trade row, compute new trail rung via `TrailCalculator` (Plan 1), push to Bybit `/v5/position/trading-stop`, update row.

**Files:**
- Create: `.../lifecycle/TrailMirror.java`
- Create: test: `.../lifecycle/TrailMirrorTest.java`

Dependencies: `ExecutedTradeRepository`, `BybitV5RestClient`, `ExchangeAccountRepository`, `ExecutionEventRepository`, `shared-trade-core.TrailCalculator`, market-data service client (to fetch current price).

For current price: add `MarketDataClient` (small wrapper around `market-data.url + /api/market/prices` or similar existing endpoint — check what signal-service uses and mirror it).

Test:
- MFE below activation → no Bybit call, no row update.
- MFE above one rung → computes new stop, calls `setTradingStop`, updates row, emits `TRAIL_UPDATED` event.
- MFE much higher → ratchets to correct rung.
- Pull-back → no ratcheting (monotonic, per TrailCalculator).

---

## Task 13: `OrderReconciler` — startup + periodic drift check

**Goal:** Reconcile local DB vs Bybit position list. Detect orphan Bybit positions (insert row with NULL signal_id + RECONCILE_ORPHAN_DETECTED event). Detect closed-externally (Bybit no longer has position, local row still OPEN → fetch closed-pnl, mark CLOSED, fill in realized_pnl_usdt + fees_usdt + exit_reason derived from `stopOrderType`).

**Files:**
- Create: `.../lifecycle/OrderReconciler.java`
- Create: test: `.../lifecycle/OrderReconcilerTest.java`

Runs on `@Observes StartupEvent` and `@Scheduled(every="60s")`.

Tests stub Bybit REST surface and verify diff handling is correct:
- Bybit-only → orphan inserted with empty signal_id
- Local-only-open → fetch closed-pnl, match by orderLinkId, populate P&L fields + exit_reason
- Both match → `last_sync_at` updated, no other changes
- Both match but position age > max → close via reduceOnly order

---

## Task 14: `SignalSubscriber` — Redis intake wiring

**Goal:** Subscribe to Redis `crypto:signals` pub/sub. For each message, parse JSON envelope, extract candidates, pipe to `FlipTracker` → `GuardrailPolicy` → `OrderPlacer`.

**Files:**
- Create: `.../intake/SignalSubscriber.java`
- Create: test: `.../intake/SignalSubscriberTest.java`

Pattern: mirror `api-gateway/RedisEventSubscriber.java`'s Vert.x-based subscribe loop with reconnect on disconnect. Deserialize `{type, data}` — handle both `type="alert"` (single signal in `data.signal`) and `type="overview"` (array in `data.signals`). For each signal with label `STRONG_BUY|STRONG_SELL`, invoke the decision pipeline.

Test by publishing messages to a test Redis (Quarkus dev-services Redis available) or by injecting a mock Redis subscription and asserting handler is called.

---

## Task 15: Remaining REST endpoints — positions, trades, wallet, events, kill-switch, close-all, close-trade

**Goal:** `TradingResource` under `/api/execution/accounts/{id}/...` — 7 more endpoints per the spec.

**Files:**
- Create: `.../resource/TradingResource.java`
- Create: test: `.../resource/TradingResourceTest.java`

Endpoints:
- `GET .../wallet` → fetches live Bybit `/v5/account/wallet-balance`, returns `WalletSnapshot` DTO.
- `GET .../positions` → returns OPEN `executed_trades` rows joined with signal "why" (requires a JOIN on the shared marketdata DB against `signal_outcomes.signal_id`).
- `GET .../trades?limit=50` → CLOSED trades.
- `GET .../events?limit=100` → audit log slice.
- `GET .../trades/{tradeId}/why` → full signal join.
- `POST .../kill-switch` body `{enabled: bool}`.
- `POST .../close-all` body `{confirm: "CLOSE_ALL"}` — reject if string mismatch, then `OrderPlacer.close(...)` every OPEN.
- `POST .../trades/{tradeId}/close` — manual close.

Add `DevModeResource` at `/api/execution/test/*` behind `execution.dev-mode.enabled=true`:
- `POST /api/execution/test/inject-signal` body `{symbol, direction, entry, stop, target, strategy}` — synthesizes a signal and pipes into the decision pipeline bypassing Redis. For smoke testing end-to-end without waiting for a real signal.

- [ ] **Step: Commit after implementing + tests**

```bash
git commit -m "feat(trade-execution): TradingResource with all read/write endpoints"
```

---

## Task 16: Bybit private WS client + `ExecutionBroadcaster` + `/ws/execution`

**Goal:** Subscribe to Bybit's private WS topics (`position`, `execution`, `order`, `wallet`) and update local state in real-time. Broadcast envelope events over server-side `/ws/execution` for the frontend.

**Files:**
- Create: `.../client/bybit/BybitV5WsClient.java`
- Create: `.../ws/ExecutionBroadcaster.java`
- Create: `.../ws/ExecutionWebSocket.java`
- Tests: integration only (heavy — mock WS or manual)

This task is the heaviest. Implementer should:
1. Use Quarkus `WebSocketClient` (JSR-356 or programmatic) to connect to `wss://stream-demo.bybit.com/v5/private`.
2. Send auth message: `{"op":"auth","args":[apiKey, expires, signedSignature]}`.
3. Send subscribe: `{"op":"subscribe","args":["position","execution","order","wallet"]}`.
4. On each message, demultiplex by `topic`, update corresponding `executed_trades` rows or wallet cache, insert events.
5. Also broadcast an envelope `{type, accountId, data}` to all connected clients of server-side `/ws/execution` via `ExecutionBroadcaster`.
6. On disconnect: exponential backoff reconnect. After reconnect: re-auth, re-subscribe, fire full `/v5/position/list` + `/v5/position/closed-pnl` catchup sweep.

Long task — expect at least 3 commits.

---

## Task 17: api-gateway proxy route + WS proxy

**Goal:** Make the execution service reachable via `localhost:31080/api/execution/*` (already-established api-gateway port).

**Files:**
- Modify: `services/api-gateway/src/main/java/com/cryptoradar/gateway/client/ServiceClient.java` — add `@ConfigProperty` for `execution.url`.
- Modify: `services/api-gateway/src/main/java/com/cryptoradar/gateway/resource/ProxyResource.java` — add handlers forwarding `/api/execution/**` to the execution service.
- Modify: `services/api-gateway/src/main/java/com/cryptoradar/gateway/websocket/CryptoWebSocket.java` (or create new `ExecutionWebSocketProxy.java`) — bridge `/ws/execution` from api-gateway to trade-execution-service.
- Modify: `docker-compose.yml` api-gateway env + depends_on (covered in Task 1 step 6).

Each forwarded endpoint becomes its own `@GET` / `@POST` / `@PATCH` / `@DELETE` method on `ProxyResource` that calls `serviceClient.getRaw(serviceClient.getExecutionUrl() + ...)` or `proxyPost(...)`. Follow the same pattern as the signal-service proxy methods.

Commit after full proxy surface is wired and api-gateway tests (if any) pass.

---

## Task 18: E2E verification + CLAUDE.md + README + k8s overlay

**Goal:** End-to-end smoke (real or dev-mode injected signal → order → trail → close). Documentation updates. K8s manifests.

- [ ] **Step 1: Manual smoke test**

Run the protocol from the spec, Stage 0 acceptance section:
1. Add DEMO account: `curl -X POST http://localhost:31080/api/execution/accounts -d '{...}'`
2. Verify 201 + `GET /accounts` shows it.
3. Flip `auto_trade_enabled=true` via PATCH.
4. Flip `kill_switch=false` via POST.
5. Either wait for a real STRONG_BUY signal OR `POST /api/execution/test/inject-signal` (dev-mode).
6. Verify row appears in `executed_trades` with status=PENDING_PLACE → OPEN after fill.
7. Watch logs for `TRAIL_UPDATED` after trail activates.
8. Manually close via `POST /trades/{id}/close` → row becomes CLOSED with exit_reason=MANUAL.

- [ ] **Step 2: Update `CLAUDE.md`**

Add to the port/service table:
```
| trade-execution-service | 31087 | 8087 | Bybit V5 execution + trail + reconciliation |
```
Add a section describing the service's file layout, similar to the existing signal-service block.

- [ ] **Step 3: Update `README.md`**

Add feature bullet under Features section describing real-trading capability.
Add row to the port table mirroring CLAUDE.md.

- [ ] **Step 4: k8s overlay**

Create `devops/base/trade-execution-service/` with `deployment.yaml`, `service.yaml`, `config.yaml` — mirror `devops/base/signal-service/`. Add the service to `devops/base/kustomization.yaml`. Add secrets template to `devops/overlays/dev/secrets.example.yaml` for `EXECUTION_MASTER_KEY`.

- [ ] **Step 5: Commit + push**

```bash
git add -A
git commit -m "docs(trade-execution): README + CLAUDE.md + k8s overlay"
git push origin master
```

---

## Self-review checklist (for the implementer)

- [ ] `trade-execution-service` builds locally with `mvn clean test` (all tests pass — count depends on final implementation, expect 50+ across the service).
- [ ] Docker image builds with `docker compose build --no-cache trade-execution-service`.
- [ ] Container reports healthy.
- [ ] `POST /api/execution/accounts` with a bogus key returns 400 (Bybit queryApiKey fails).
- [ ] `POST /api/execution/accounts` with a real demo key returns 201 and inserts encrypted row.
- [ ] Signal injection via `/test/inject-signal` produces an `executed_trades` row whose stop/target/qty match expectations.
- [ ] Live DEMO trade completes a full cycle (open → trail move → close) and the closed row has `realized_pnl_usdt` populated from Bybit's closed-pnl endpoint.
- [ ] `EXECUTION_MASTER_KEY` is required at startup — missing the env var fails app boot, not in-request.
- [ ] `execution.mainnet.enabled=false` default is respected; flipping to MAINNET takes an explicit action.
- [ ] No plaintext API key or secret appears anywhere in logs, exception messages, or HTTP response bodies.
- [ ] `git log --oneline d6ee0ca..HEAD` shows a clean per-task commit history with conventional-commit prefixes.

---

## After Plan 2

Plan 3 is the frontend integration (Portfolio page extension — `ExchangeAccountsSection`, `ExchangeCard`, `WhyModal`, settings panel, `useExecutionStream` hook wiring `/ws/execution`). That plan consumes these backend endpoints and the WS stream; at the end of Plan 3 the user opens the Portfolio page and sees their Bybit demo account with live equity, live positions, signal "why" tooltips, and a working kill switch.

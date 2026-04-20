# Plan 2b — trade-execution-service lifecycle + REST/WS + integration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the trade-execution-service by adding the account-management REST surface, signal-to-order decision pipeline, Bybit order placement + trailing-stop + reconciliation lifecycle, Redis intake, read/control REST endpoints, Bybit private WS client, server-side `/ws/execution`, api-gateway proxy, and full E2E verification with docs + k8s manifests. End of plan = you can POST a Bybit demo API key, signals mirror to real orders on Bybit's demo environment, and the UI (Plan 3) has everything it needs.

**Prerequisite:** Plan 2a merged (service skeleton, DB schema, entities, repos, `CredentialCipher`, `BybitV5Signer`, `BybitV5RestClient` with 8 auth endpoints, `PermissionValidator`).

**Architecture:**
- Account CRUD → validates key via Bybit `queryApiKey` → `PermissionValidator` → encrypt → persist.
- `SignalSubscriber` consumes Redis `crypto:signals` envelope (both `alert` and `overview` types) → `FlipTracker` (2-tick persistence) → `GuardrailPolicy` (6 rules) → `OrderPlacer` (dispatches to Bybit with TP/SL attached).
- `TrailMirror` every 60s: for each OPEN row, compute new trail rung via `shared-trade-core.TrailCalculator`, push to Bybit `/v5/position/trading-stop`.
- `OrderReconciler` on StartupEvent + every 60s: diff local DB vs Bybit `positionList`, fill in `closed-pnl` for externally-closed positions, detect orphans.
- Bybit private WS (`position`, `execution`, `order`, `wallet` topics) updates DB in real-time + broadcasts envelopes to `/ws/execution` server.
- `api-gateway` proxies all `/api/execution/**` + `/ws/execution` to the service.

**Tech Stack:** Same as Plan 2a plus `java.net.http.WebSocket` for client-to-Bybit WS, `quarkus-websockets` server-side `@ServerEndpoint`.

**Spec reference:** `docs/superpowers/specs/2026-04-20-trade-execution-service-design.md` — Sections 3-7.

---

## File structure overview

New under `services/trade-execution-service/src/main/java/com/cryptoradar/execution/`:

```
client/
├── MarketDataClient.java                  (wrapper for /api/market/prices)
└── bybit/
    └── BybitV5WsClient.java               (private WS: position/execution/order/wallet)
intake/
├── FlipTracker.java
└── SignalSubscriber.java
policy/
└── GuardrailPolicy.java
lifecycle/
├── OrderPlacer.java
├── TrailMirror.java
└── OrderReconciler.java
resource/
├── AccountResource.java
├── TradingResource.java
├── DevModeResource.java
└── dto/
    ├── CreateAccountRequest.java
    ├── UpdateAccountRequest.java
    ├── AccountView.java
    ├── WalletSnapshot.java
    ├── PositionView.java
    ├── TradeView.java
    ├── EventView.java
    ├── WhyView.java
    ├── KillSwitchRequest.java
    └── CloseAllRequest.java
ws/
├── ExecutionWebSocket.java                (/ws/execution @ServerEndpoint)
└── ExecutionBroadcaster.java              (broadcast envelopes to subscribers)
```

Tests mirror each package under `src/test/java/...`.

Modified:
- `services/api-gateway/src/main/java/com/cryptoradar/gateway/client/ServiceClient.java` — add `executionUrl`
- `services/api-gateway/src/main/java/com/cryptoradar/gateway/resource/ProxyResource.java` — proxy `/api/execution/**`
- `services/api-gateway/src/main/java/com/cryptoradar/gateway/websocket/*` — proxy `/ws/execution`
- `CLAUDE.md`, `README.md` — document the new service
- `devops/base/trade-execution-service/` — new k8s manifests
- `devops/overlays/dev/kustomization.yaml` — reference the new base
- `devops/overlays/dev/secrets.example.yaml` — document `EXECUTION_MASTER_KEY`

---

## Task 1: Account CRUD REST surface (`AccountResource`)

**Goal:** POST/GET/PATCH/DELETE endpoints for exchange accounts. POST validates the Bybit key through `queryApiKey` + `PermissionValidator`, encrypts via `CredentialCipher`, persists.

**Files:**
- Create: `.../resource/dto/CreateAccountRequest.java`, `UpdateAccountRequest.java`, `AccountView.java`
- Create: `.../resource/AccountResource.java`
- Create: `.../resource/AccountResourceTest.java`

- [ ] **Step 1: Create `CreateAccountRequest` record**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/resource/dto/CreateAccountRequest.java`:

```java
package com.cryptoradar.execution.resource.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank @Pattern(regexp = "BYBIT") String exchange,
        @NotBlank @Pattern(regexp = "DEMO|MAINNET") String environment,
        @NotBlank String apiKey,
        @NotBlank String apiSecret,
        String label,
        @JsonProperty("riskPercent") BigDecimal riskPercent,
        @JsonProperty("defaultLeverage") @Min(1) Integer defaultLeverage,
        @JsonProperty("maxConcurrentPositions") @Min(1) Integer maxConcurrentPositions,
        @JsonProperty("maxDailyLossPercent") @Positive BigDecimal maxDailyLossPercent,
        @JsonProperty("signalAgeSeconds") @Min(1) Integer signalAgeSeconds,
        @JsonProperty("positionMaxAgeHours") @Min(1) Integer positionMaxAgeHours,
        @JsonProperty("flipPersistenceTicks") @Min(1) Integer flipPersistenceTicks
) {}
```

- [ ] **Step 2: Create `UpdateAccountRequest` record**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/resource/dto/UpdateAccountRequest.java`:

```java
package com.cryptoradar.execution.resource.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record UpdateAccountRequest(
        String label,
        Boolean autoTradeEnabled,
        Boolean killSwitch,
        @Positive BigDecimal riskPercent,
        @Min(1) Integer defaultLeverage,
        @Min(1) Integer maxConcurrentPositions,
        @Positive BigDecimal maxDailyLossPercent,
        @Min(1) Integer signalAgeSeconds,
        @Min(1) Integer positionMaxAgeHours,
        @Min(1) Integer flipPersistenceTicks
) {}
```

- [ ] **Step 3: Create `AccountView` response DTO**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/resource/dto/AccountView.java`:

```java
package com.cryptoradar.execution.resource.dto;

import com.cryptoradar.execution.model.ExchangeAccount;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountView(
        Long id,
        String exchange,
        String environment,
        String label,
        String keyMask,
        boolean autoTradeEnabled,
        boolean killSwitch,
        BigDecimal riskPercent,
        int defaultLeverage,
        int maxConcurrentPositions,
        BigDecimal maxDailyLossPercent,
        int signalAgeSeconds,
        int positionMaxAgeHours,
        int flipPersistenceTicks,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Build a view from an entity. {@code plaintextApiKey} is passed from the
     * decrypt-to-render step (in-memory); this method extracts just the last 4
     * chars as a key mask to show in the UI. If null, keyMask is "****".
     */
    public static AccountView of(ExchangeAccount a, String plaintextApiKey) {
        String mask = "****";
        if (plaintextApiKey != null && plaintextApiKey.length() >= 4) {
            mask = "****" + plaintextApiKey.substring(plaintextApiKey.length() - 4);
        }
        return new AccountView(
                a.getId(),
                a.getExchange(),
                a.getEnvironment(),
                a.getLabel(),
                mask,
                a.isAutoTradeEnabled(),
                a.isKillSwitch(),
                a.getRiskPercent(),
                a.getDefaultLeverage(),
                a.getMaxConcurrentPositions(),
                a.getMaxDailyLossPercent(),
                a.getSignalAgeSeconds(),
                a.getPositionMaxAgeHours(),
                a.getFlipPersistenceTicks(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
```

- [ ] **Step 4: Create `AccountResource`**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/resource/AccountResource.java`:

```java
package com.cryptoradar.execution.resource;

import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.ApiKeyPermissionsV5;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.resource.dto.AccountView;
import com.cryptoradar.execution.resource.dto.CreateAccountRequest;
import com.cryptoradar.execution.resource.dto.UpdateAccountRequest;
import com.cryptoradar.execution.security.CredentialCipher;
import com.cryptoradar.execution.security.PermissionValidator;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

@Path("/api/execution/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountResource {

    private static final Logger LOG = Logger.getLogger(AccountResource.class);

    private final ExchangeAccountRepository accountRepo;
    private final ExecutedTradeRepository tradeRepo;
    private final CredentialCipher cipher;
    private final BybitV5RestClient bybit;
    private final boolean mainnetEnabled;

    public AccountResource(ExchangeAccountRepository accountRepo,
                           ExecutedTradeRepository tradeRepo,
                           CredentialCipher cipher,
                           BybitV5RestClient bybit,
                           @ConfigProperty(name = "execution.mainnet.enabled") boolean mainnetEnabled) {
        this.accountRepo = accountRepo;
        this.tradeRepo = tradeRepo;
        this.cipher = cipher;
        this.bybit = bybit;
        this.mainnetEnabled = mainnetEnabled;
    }

    @GET
    public List<AccountView> list() {
        return accountRepo.listAll().stream()
                .map(a -> AccountView.of(a, decryptKey(a.getApiKeyEncrypted())))
                .toList();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") Long id) {
        ExchangeAccount a = accountRepo.findById(id);
        if (a == null) return Response.status(404).build();
        return Response.ok(AccountView.of(a, decryptKey(a.getApiKeyEncrypted()))).build();
    }

    @POST
    @Transactional
    public Response create(@Valid CreateAccountRequest req) {
        if ("MAINNET".equals(req.environment()) && !mainnetEnabled) {
            return error(400, "MAINNET environment is disabled via feature flag");
        }
        if (accountRepo.findByExchangeAndEnvironment(req.exchange(), req.environment()).isPresent()) {
            return error(409, "Account for " + req.exchange() + " " + req.environment() + " already exists");
        }

        String apiKeyCipher = cipher.encrypt(req.apiKey());
        String apiSecretCipher = cipher.encrypt(req.apiSecret());

        BybitResponse<ApiKeyPermissionsV5> resp;
        try {
            resp = bybit.queryApiKey(req.environment(), apiKeyCipher, apiSecretCipher);
        } catch (RuntimeException e) {
            LOG.warnf("Bybit queryApiKey failed for new account: %s", e.getMessage());
            return error(400, "Bybit key validation failed: " + e.getMessage());
        }
        if (!resp.isOk()) {
            return error(400, "Bybit key validation returned retCode=" + resp.retCode() + " retMsg=" + resp.retMsg());
        }
        try {
            PermissionValidator.validate(resp.result());
        } catch (IllegalStateException e) {
            return error(400, e.getMessage());
        }

        ExchangeAccount a = new ExchangeAccount();
        a.setExchange(req.exchange());
        a.setEnvironment(req.environment());
        a.setApiKeyEncrypted(apiKeyCipher);
        a.setApiSecretEncrypted(apiSecretCipher);
        a.setLabel(req.label());
        if (req.riskPercent() != null) a.setRiskPercent(req.riskPercent());
        if (req.defaultLeverage() != null) a.setDefaultLeverage(req.defaultLeverage());
        if (req.maxConcurrentPositions() != null) a.setMaxConcurrentPositions(req.maxConcurrentPositions());
        if (req.maxDailyLossPercent() != null) a.setMaxDailyLossPercent(req.maxDailyLossPercent());
        if (req.signalAgeSeconds() != null) a.setSignalAgeSeconds(req.signalAgeSeconds());
        if (req.positionMaxAgeHours() != null) a.setPositionMaxAgeHours(req.positionMaxAgeHours());
        if (req.flipPersistenceTicks() != null) a.setFlipPersistenceTicks(req.flipPersistenceTicks());
        accountRepo.persist(a);

        return Response.status(201).entity(AccountView.of(a, req.apiKey())).build();
    }

    @PATCH
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") Long id, @Valid UpdateAccountRequest req) {
        ExchangeAccount a = accountRepo.findById(id);
        if (a == null) return Response.status(404).build();

        if (req.label() != null) a.setLabel(req.label());
        if (req.autoTradeEnabled() != null) a.setAutoTradeEnabled(req.autoTradeEnabled());
        if (req.killSwitch() != null) a.setKillSwitch(req.killSwitch());
        if (req.riskPercent() != null) a.setRiskPercent(req.riskPercent());
        if (req.defaultLeverage() != null) a.setDefaultLeverage(req.defaultLeverage());
        if (req.maxConcurrentPositions() != null) a.setMaxConcurrentPositions(req.maxConcurrentPositions());
        if (req.maxDailyLossPercent() != null) a.setMaxDailyLossPercent(req.maxDailyLossPercent());
        if (req.signalAgeSeconds() != null) a.setSignalAgeSeconds(req.signalAgeSeconds());
        if (req.positionMaxAgeHours() != null) a.setPositionMaxAgeHours(req.positionMaxAgeHours());
        if (req.flipPersistenceTicks() != null) a.setFlipPersistenceTicks(req.flipPersistenceTicks());

        return Response.ok(AccountView.of(a, decryptKey(a.getApiKeyEncrypted()))).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        ExchangeAccount a = accountRepo.findById(id);
        if (a == null) return Response.status(404).build();

        int openCount = tradeRepo.countOpenForAccount(id);
        if (openCount > 0) {
            return error(409, "Account has " + openCount + " open position(s); close them first");
        }
        accountRepo.delete(a);
        return Response.noContent().build();
    }

    private String decryptKey(String encrypted) {
        try {
            return cipher.decrypt(encrypted);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Response error(int status, String message) {
        return Response.status(status)
                .entity(java.util.Map.of("error", message))
                .build();
    }
}
```

- [ ] **Step 5: Write integration tests**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/resource/AccountResourceTest.java`:

```java
package com.cryptoradar.execution.resource;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestProfile(AccountResourceTest.Profile.class)
class AccountResourceTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            return Map.of(
                    "bybit.rest-base-override.DEMO", "http://localhost:38100",
                    "execution.master-key", Base64.getEncoder().encodeToString(k),
                    "execution.mainnet.enabled", "false"
            );
        }
    }

    static WireMockServer wireMock;

    @Inject ObjectMapper mapper;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;

    @BeforeEach
    @Transactional
    void setup() {
        wireMock = new WireMockServer(38100);
        wireMock.start();
        WireMock.configureFor("localhost", 38100);
        // Clean DB state
        accountRepo.deleteAll();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        accountRepo.deleteAll();
        wireMock.stop();
    }

    private void stubValidKey() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/user/query-api"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of(
                                "id", "K1",
                                "apiKey", "demo-key",
                                "readOnly", 0,
                                "permissions", Map.of(
                                        "Derivatives", List.of("Order", "Position"),
                                        "Withdraw", List.of()
                                )
                        ),
                        "time", 1700000000000L
                )))));
    }

    private void stubKeyWithWithdraw() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/user/query-api"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of(
                                "id", "K2",
                                "apiKey", "bad-key",
                                "readOnly", 0,
                                "permissions", Map.of(
                                        "Derivatives", List.of("Order", "Position"),
                                        "Withdraw", List.of("Asset")
                                )
                        ),
                        "time", 1700000000000L
                )))));
    }

    @Test
    void createAccountHappyPath() throws Exception {
        stubValidKey();
        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "demo-key-long", "apiSecret", "demo-sec"))
                .when().post("/api/execution/accounts")
                .then()
                .statusCode(201)
                .body("exchange", equalTo("BYBIT"))
                .body("environment", equalTo("DEMO"))
                .body("autoTradeEnabled", equalTo(false))
                .body("killSwitch", equalTo(true))
                .body("keyMask", endsWith("long"));
    }

    @Test
    void rejectMainnetWhenDisabled() throws Exception {
        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "MAINNET",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then()
                .statusCode(400)
                .body("error", containsString("MAINNET environment is disabled"));
    }

    @Test
    void rejectWithdrawPermissionKey() throws Exception {
        stubKeyWithWithdraw();
        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then()
                .statusCode(400)
                .body("error", containsString("withdraw permission"));
    }

    @Test
    void rejectDuplicate() throws Exception {
        stubValidKey();
        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then().statusCode(201);

        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then().statusCode(409);
    }

    @Test
    void listReturnsAll() throws Exception {
        stubValidKey();
        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts").then().statusCode(201);

        given().when().get("/api/execution/accounts")
                .then().statusCode(200).body("size()", equalTo(1));
    }

    @Test
    void patchUpdatesFields() throws Exception {
        stubValidKey();
        int id = given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then().statusCode(201).extract().path("id");

        given().contentType("application/json")
                .body(Map.of("killSwitch", false, "autoTradeEnabled", true))
                .when().patch("/api/execution/accounts/" + id)
                .then().statusCode(200)
                .body("killSwitch", equalTo(false))
                .body("autoTradeEnabled", equalTo(true));
    }

    @Test
    void deleteWorksWhenNoOpenTrades() throws Exception {
        stubValidKey();
        int id = given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then().statusCode(201).extract().path("id");

        given().when().delete("/api/execution/accounts/" + id)
                .then().statusCode(204);
    }

    @Test
    void get404WhenMissing() {
        given().when().get("/api/execution/accounts/999999")
                .then().statusCode(404);
    }
}
```

- [ ] **Step 6: Run tests**

```bash
cd services/trade-execution-service && mvn test -Dtest=AccountResourceTest -B
```
Expected: PASS all 8 tests.

Full suite:
```bash
cd services/trade-execution-service && mvn test -B
```
Expected: everything green.

- [ ] **Step 7: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/resource/ \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/resource/
git commit -m "feat(trade-execution): AccountResource CRUD with key validation"
```

---

## Task 2: `FlipTracker` — 2-tick persistence

**Files:**
- Create: `.../intake/FlipTracker.java`
- Create: test: `.../intake/FlipTrackerTest.java`

- [ ] **Step 1: Write the failing tests**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/intake/FlipTrackerTest.java`:

```java
package com.cryptoradar.execution.intake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.cryptoradar.execution.intake.FlipTracker.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlipTrackerTest {

    private FlipTracker tracker;

    @BeforeEach
    void setup() {
        tracker = new FlipTracker();
    }

    @Test
    void singleStrongBuyOnFreshSymbolIsNoAction() {
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));
    }

    @Test
    void twoConsecutiveStrongBuyTriggersEnterLong() {
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false);
        assertEquals(ENTER_LONG, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));
    }

    @Test
    void persistenceOneFiresImmediately() {
        assertEquals(ENTER_LONG, tracker.observe("BTCUSDT", "STRONG_BUY", 1, false, false));
    }

    @Test
    void oppositeSignalResetsStreak() {
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false);
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "STRONG_SELL", 2, false, false));
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));   // back to 1 count
        assertEquals(ENTER_LONG, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));  // now 2
    }

    @Test
    void neutralSignalClearsState() {
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false);
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "NEUTRAL", 2, false, false));
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));  // streak=1
    }

    @Test
    void twoStrongSellsOnSymbolWeAreLongOnClosesLong() {
        tracker.observe("BTCUSDT", "STRONG_SELL", 2, true, false);
        assertEquals(CLOSE_LONG, tracker.observe("BTCUSDT", "STRONG_SELL", 2, true, false));
    }

    @Test
    void twoStrongBuysOnSymbolWeAreShortOnClosesShort() {
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, true);
        assertEquals(CLOSE_SHORT, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, true));
    }

    @Test
    void stateIsPerSymbol() {
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false);
        // ETH streak starts fresh
        assertEquals(NO_ACTION, tracker.observe("ETHUSDT", "STRONG_BUY", 2, false, false));
        // BTC still at streak=1 from earlier
        assertEquals(ENTER_LONG, tracker.observe("BTCUSDT", "STRONG_BUY", 2, false, false));
    }

    @Test
    void sameDirectionWhenAlreadyHoldingIsNoAction() {
        // We're already LONG, another STRONG_BUY sequence arrives — no new action
        tracker.observe("BTCUSDT", "STRONG_BUY", 2, true, false);
        assertEquals(NO_ACTION, tracker.observe("BTCUSDT", "STRONG_BUY", 2, true, false));
    }
}
```

- [ ] **Step 2: Run — expect failure**

```bash
cd services/trade-execution-service && mvn test -Dtest=FlipTrackerTest -B
```

- [ ] **Step 3: Implement**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/FlipTracker.java`:

```java
package com.cryptoradar.execution.intake;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-symbol counter for consecutive same-direction STRONG signals.
 * Emits ENTER/CLOSE actions only after N ticks of persistence.
 *
 * <p>State is lost on restart; first signals after restart are treated as new
 * streak. Acceptable for a 60s evaluator cadence — worst case is a missed
 * flip within the first minute of boot.
 */
@ApplicationScoped
public class FlipTracker {

    public enum Action { NO_ACTION, ENTER_LONG, ENTER_SHORT, CLOSE_LONG, CLOSE_SHORT }

    private record Counter(String lastDirection, int streak) {}

    private final Map<String, Counter> state = new ConcurrentHashMap<>();

    public Action observe(String symbol, String signalLabel, int persistenceTicks,
                          boolean currentlyLong, boolean currentlyShort) {
        String dir = signalToDirection(signalLabel);
        if (dir == null) {
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
            return Action.NO_ACTION;
        } else {
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

- [ ] **Step 4: Run — expect PASS all 9 tests**

- [ ] **Step 5: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/FlipTracker.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/intake/FlipTrackerTest.java
git commit -m "feat(trade-execution): FlipTracker with N-tick persistence"
```

---

## Task 3: `GuardrailPolicy` — 6-rule signal gate

**Files:**
- Create: `.../policy/GuardrailPolicy.java`
- Create: test: `.../policy/GuardrailPolicyTest.java`

- [ ] **Step 1: Write the failing tests**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/policy/GuardrailPolicyTest.java`:

```java
package com.cryptoradar.execution.policy;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.policy.GuardrailPolicy.Decision;
import com.cryptoradar.execution.policy.GuardrailPolicy.SignalCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardrailPolicyTest {

    private GuardrailPolicy policy;
    private ExchangeAccount acct;

    @BeforeEach
    void setup() {
        policy = new GuardrailPolicy();
        acct = new ExchangeAccount();
        acct.setAutoTradeEnabled(true);
        acct.setKillSwitch(false);
        acct.setMaxConcurrentPositions(5);
        acct.setMaxDailyLossPercent(new BigDecimal("5.00"));
        acct.setSignalAgeSeconds(60);
    }

    private SignalCandidate fresh() {
        return new SignalCandidate("BTCUSDT", "LONG", "trend-continuation", "sig-1", Instant.now());
    }

    @Test
    void acceptHappyPath() {
        Decision d = policy.evaluate(acct, fresh(), 0, BigDecimal.ZERO, false);
        assertTrue(d.accepted());
    }

    @Test
    void killSwitchBlocks() {
        acct.setKillSwitch(true);
        Decision d = policy.evaluate(acct, fresh(), 0, BigDecimal.ZERO, false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_KILL_SWITCH, d.blockReason());
    }

    @Test
    void autoTradeOffBlocks() {
        acct.setAutoTradeEnabled(false);
        Decision d = policy.evaluate(acct, fresh(), 0, BigDecimal.ZERO, false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_AUTO_TRADE_OFF, d.blockReason());
    }

    @Test
    void signalAgeBlocks() {
        SignalCandidate stale = new SignalCandidate("BTCUSDT", "LONG", "t", "s",
                Instant.now().minusSeconds(120));  // 2 min old, limit 60s
        Decision d = policy.evaluate(acct, stale, 0, BigDecimal.ZERO, false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_SIGNAL_AGE, d.blockReason());
    }

    @Test
    void maxConcurrentBlocks() {
        Decision d = policy.evaluate(acct, fresh(), 5, BigDecimal.ZERO, false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_MAX_CONCURRENT, d.blockReason());
    }

    @Test
    void dailyLossHaltBlocks() {
        // Loss 6% when limit 5%
        Decision d = policy.evaluate(acct, fresh(), 0, new BigDecimal("-6.0"), false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_DAILY_HALT, d.blockReason());
    }

    @Test
    void dedupBlocks() {
        Decision d = policy.evaluate(acct, fresh(), 0, BigDecimal.ZERO, true);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_DEDUP, d.blockReason());
    }

    @Test
    void ruleOrderingKillSwitchBeforeAutoTrade() {
        // Both conditions true; kill_switch should win
        acct.setKillSwitch(true);
        acct.setAutoTradeEnabled(false);
        Decision d = policy.evaluate(acct, fresh(), 0, BigDecimal.ZERO, false);
        assertEquals(ExecutionEventType.SIGNAL_BLOCKED_KILL_SWITCH, d.blockReason());
    }
}
```

- [ ] **Step 2: Run — fail**

- [ ] **Step 3: Implement**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/policy/GuardrailPolicy.java`:

```java
package com.cryptoradar.execution.policy;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutionEventType;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Pure evaluator for the six capital-preservation guardrails. Rule order:
 * kill_switch → auto_trade → signal_age → max_concurrent → daily_halt → dedup.
 * First match wins (short-circuit).
 *
 * <p>Callers supply the runtime state (open-position count, today's realized
 * P&L percent, whether a duplicate open trade exists for the symbol+direction
 * +strategy triple) — this class only evaluates thresholds against that state.
 */
@ApplicationScoped
public class GuardrailPolicy {

    public record SignalCandidate(String symbol, String direction, String strategy,
                                   String signalId, Instant signalTime) {}

    public record Decision(boolean accepted, ExecutionEventType blockReason) {
        public static Decision accept() { return new Decision(true, null); }
        public static Decision block(ExecutionEventType reason) { return new Decision(false, reason); }
    }

    public Decision evaluate(ExchangeAccount account, SignalCandidate candidate,
                              int openPositions, BigDecimal todayPnlPercent, boolean dedupHit) {
        if (account.isKillSwitch()) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_KILL_SWITCH);
        }
        if (!account.isAutoTradeEnabled()) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_AUTO_TRADE_OFF);
        }
        long age = Duration.between(candidate.signalTime(), Instant.now()).getSeconds();
        if (age > account.getSignalAgeSeconds()) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_SIGNAL_AGE);
        }
        if (openPositions >= account.getMaxConcurrentPositions()) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_MAX_CONCURRENT);
        }
        if (todayPnlPercent != null
                && todayPnlPercent.compareTo(account.getMaxDailyLossPercent().negate()) < 0) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_DAILY_HALT);
        }
        if (dedupHit) {
            return Decision.block(ExecutionEventType.SIGNAL_BLOCKED_DEDUP);
        }
        return Decision.accept();
    }
}
```

- [ ] **Step 4: Run — expect 8 passing**

- [ ] **Step 5: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/policy/GuardrailPolicy.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/policy/GuardrailPolicyTest.java
git commit -m "feat(trade-execution): GuardrailPolicy with 6 capital-preservation rules"
```

---

## Task 4: `OrderPlacer` — place market orders with TP/SL

**Files:**
- Create: `.../lifecycle/InstrumentRegistry.java`
- Create: test: `.../lifecycle/InstrumentRegistryTest.java`
- Create: `.../lifecycle/OrderPlacer.java`
- Create: test: `.../lifecycle/OrderPlacerTest.java`

**Why we need `InstrumentRegistry`:** Bybit's `lotSizeFilter.qtyStep` varies per symbol. BTCUSDT=0.001, ETHUSDT=0.01, SOLUSDT=0.1, XRPUSDT/DOGEUSDT/ADAUSDT=1. Hardcoding a single step truncates most symbols to zero or over-orders on coarse ones. The registry fetches once per symbol via the public `/v5/market/instruments-info` endpoint (no auth required) and caches the result in process memory. A fallback table covers the current 13-pair watchlist so a Bybit outage at startup does not brick the placer.

- [ ] **Step 1: Implement `InstrumentRegistry`**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/InstrumentRegistry.java`:

```java
package com.cryptoradar.execution.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.config.ConfigMapping;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches per-symbol qty step ("lot size") fetched from Bybit's public
 * /v5/market/instruments-info endpoint. No auth required.
 *
 * Falls back to a static table when the HTTP fetch fails so a flaky
 * network at startup does not block order placement for known symbols.
 */
@ApplicationScoped
public class InstrumentRegistry {

    private static final Logger LOG = Logger.getLogger(InstrumentRegistry.class);

    /** Fallback qty steps for the 13-symbol watchlist. Authoritative source is Bybit. */
    private static final Map<String, Double> FALLBACK_QTY_STEP = Map.ofEntries(
            Map.entry("BTCUSDT", 0.001),
            Map.entry("ETHUSDT", 0.01),
            Map.entry("SOLUSDT", 0.1),
            Map.entry("BNBUSDT", 0.01),
            Map.entry("XRPUSDT", 1.0),
            Map.entry("DOGEUSDT", 1.0),
            Map.entry("LINKUSDT", 0.1),
            Map.entry("AVAXUSDT", 0.1),
            Map.entry("LTCUSDT", 0.01),
            Map.entry("DOTUSDT", 0.1),
            Map.entry("NEARUSDT", 0.1),
            Map.entry("ADAUSDT", 1.0),
            Map.entry("MATICUSDT", 1.0)
    );

    /** Cache key = env + "|" + symbol. */
    private final ConcurrentHashMap<String, Double> cache = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @ConfigProperty(name = "bybit.rest-base-override.DEMO", defaultValue = "https://api-demo.bybit.com")
    String demoBase;

    @ConfigProperty(name = "bybit.rest-base-override.MAINNET", defaultValue = "https://api.bybit.com")
    String mainnetBase;

    public double qtyStepFor(String environment, String symbol) {
        String key = environment + "|" + symbol;
        Double cached = cache.get(key);
        if (cached != null) return cached;
        Optional<Double> fetched = fetchQtyStep(environment, symbol);
        double resolved = fetched.orElseGet(() -> FALLBACK_QTY_STEP.getOrDefault(symbol, 0.001));
        cache.put(key, resolved);
        if (fetched.isEmpty()) {
            LOG.warnf("InstrumentRegistry: falling back to static qtyStep for %s = %s", symbol, resolved);
        }
        return resolved;
    }

    /** Visible for tests — flush cache between cases. */
    public void invalidate() {
        cache.clear();
    }

    private Optional<Double> fetchQtyStep(String environment, String symbol) {
        String base = "MAINNET".equals(environment) ? mainnetBase : demoBase;
        URI uri = URI.create(base + "/v5/market/instruments-info?category=linear&symbol=" + symbol);
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warnf("instruments-info HTTP %d for %s", resp.statusCode(), symbol);
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(resp.body());
            if (root.path("retCode").asInt(-1) != 0) {
                LOG.warnf("instruments-info retCode=%d retMsg=%s for %s",
                        root.path("retCode").asInt(-1),
                        root.path("retMsg").asText("?"), symbol);
                return Optional.empty();
            }
            JsonNode list = root.path("result").path("list");
            if (!list.isArray() || list.isEmpty()) {
                return Optional.empty();
            }
            String step = list.get(0).path("lotSizeFilter").path("qtyStep").asText("");
            if (step.isEmpty()) return Optional.empty();
            return Optional.of(Double.parseDouble(step));
        } catch (Exception e) {
            LOG.warnf("instruments-info fetch failed for %s: %s", symbol, e.getMessage());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 2: `InstrumentRegistryTest`**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/lifecycle/InstrumentRegistryTest.java`:

```java
package com.cryptoradar.execution.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(InstrumentRegistryTest.Profile.class)
class InstrumentRegistryTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            return Map.of(
                    "bybit.rest-base-override.DEMO", "http://localhost:38103",
                    "execution.master-key", Base64.getEncoder().encodeToString(k)
            );
        }
    }

    static WireMockServer wireMock;

    @Inject InstrumentRegistry registry;
    @Inject ObjectMapper mapper;

    @BeforeEach
    void setup() {
        wireMock = new WireMockServer(38103);
        wireMock.start();
        WireMock.configureFor("localhost", 38103);
        registry.invalidate();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private void stubInstrument(String symbol, String qtyStep) throws Exception {
        stubFor(get(urlPathEqualTo("/v5/market/instruments-info"))
                .withQueryParam("category", equalTo("linear"))
                .withQueryParam("symbol", equalTo(symbol))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of(Map.of(
                                "symbol", symbol,
                                "lotSizeFilter", Map.of("qtyStep", qtyStep)
                        ))),
                        "time", 1700000000000L
                )))));
    }

    @Test
    void fetchesXrpQtyStepAsOne() throws Exception {
        stubInstrument("XRPUSDT", "1");
        assertEquals(1.0, registry.qtyStepFor("DEMO", "XRPUSDT"));
    }

    @Test
    void fetchesBtcQtyStepAsThousandth() throws Exception {
        stubInstrument("BTCUSDT", "0.001");
        assertEquals(0.001, registry.qtyStepFor("DEMO", "BTCUSDT"));
    }

    @Test
    void cachesAfterFirstFetch() throws Exception {
        stubInstrument("SOLUSDT", "0.1");
        assertEquals(0.1, registry.qtyStepFor("DEMO", "SOLUSDT"));
        wireMock.resetRequests();
        assertEquals(0.1, registry.qtyStepFor("DEMO", "SOLUSDT"));
        // second call hits cache — no new HTTP request
        verify(0, getRequestedFor(urlPathEqualTo("/v5/market/instruments-info")));
    }

    @Test
    void fallsBackToStaticTableOnHttpFailure() {
        // no stub — WireMock returns 404 → fall back to FALLBACK_QTY_STEP["DOGEUSDT"] = 1.0
        assertEquals(1.0, registry.qtyStepFor("DEMO", "DOGEUSDT"));
    }

    @Test
    void fallsBackToSmallStepForUnknownSymbol() {
        // unknown symbol, no stub → FALLBACK map returns default 0.001
        assertEquals(0.001, registry.qtyStepFor("DEMO", "WEIRDUSDT"));
    }
}
```

- [ ] **Step 3: Run — expect 5 passing**

```bash
cd services/trade-execution-service && mvn test -Dtest=InstrumentRegistryTest -B
```

- [ ] **Step 4: Commit**

```bash
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/InstrumentRegistry.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/lifecycle/InstrumentRegistryTest.java
git commit -m "feat(trade-execution): InstrumentRegistry caches per-symbol qtyStep"
```

- [ ] **Step 5: Implement `OrderPlacer`**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/OrderPlacer.java`:

```java
package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.core.RUnitMath;
import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.PlaceOrderRequest;
import com.cryptoradar.execution.client.bybit.dto.PlaceOrderResult;
import com.cryptoradar.execution.client.bybit.dto.WalletV5;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.model.ExitReason;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Places market orders on Bybit with TP/SL attached. Computes quantity via
 * RUnitMath from the account's risk % and the signal's entry/stop distance.
 */
@ApplicationScoped
public class OrderPlacer {

    private static final Logger LOG = Logger.getLogger(OrderPlacer.class);
    private static final int RETCODE_OK = 0;
    private static final int RETCODE_LEVERAGE_UNCHANGED = 110043;
    private static final int RETCODE_DUPLICATE_ORDER = 110061;
    private static final int RETCODE_INSUFFICIENT_MARGIN = 110007;

    private final BybitV5RestClient bybit;
    private final InstrumentRegistry instruments;
    private final ExecutedTradeRepository tradeRepo;
    private final ExecutionEventRepository eventRepo;

    public OrderPlacer(BybitV5RestClient bybit, InstrumentRegistry instruments,
                       ExecutedTradeRepository tradeRepo, ExecutionEventRepository eventRepo) {
        this.bybit = bybit;
        this.instruments = instruments;
        this.tradeRepo = tradeRepo;
        this.eventRepo = eventRepo;
    }

    public record PlacementRequest(String symbol, String direction, String strategy,
                                    String signalId, BigDecimal entryPrice,
                                    BigDecimal stopPrice, BigDecimal targetPrice) {}

    @Transactional
    public ExecutedTrade place(ExchangeAccount account, PlacementRequest req) {
        // 1. Set leverage (idempotent)
        BybitResponse<Map<String, Object>> levResp = bybit.setLeverage(
                account.getEnvironment(), account.getApiKeyEncrypted(),
                account.getApiSecretEncrypted(), req.symbol(), account.getDefaultLeverage());
        if (!levResp.isOk() && levResp.retCode() != RETCODE_LEVERAGE_UNCHANGED) {
            LOG.warnf("setLeverage failed for %s: retCode=%d retMsg=%s",
                    req.symbol(), levResp.retCode(), levResp.retMsg());
        }

        // 2. Compute qty and equity-derived risk (mock equity here; real code pulls from wallet)
        double equity = fetchEquity(account);
        double riskPct = account.getRiskPercent().doubleValue();
        double qtyStep = instruments.qtyStepFor(account.getEnvironment(), req.symbol());
        double qty = RUnitMath.computeQty(equity, riskPct,
                req.entryPrice().doubleValue(), req.stopPrice().doubleValue(), qtyStep);
        if (qty <= 0) {
            return fail(account, req, "qty computed as zero — skip");
        }

        // 3. Insert row with PENDING_PLACE
        ExecutedTrade trade = new ExecutedTrade();
        trade.setExchangeAccountId(account.getId());
        trade.setSignalId(req.signalId());
        trade.setSymbol(req.symbol());
        trade.setDirection(req.direction());
        trade.setStrategy(req.strategy());
        trade.setStatus(TradeStatus.PENDING_PLACE);
        trade.setStopPrice(req.stopPrice());
        trade.setTargetPrice(req.targetPrice());
        trade.setDynamicStopPrice(req.stopPrice());
        trade.setLeverage(account.getDefaultLeverage());
        trade.setQty(BigDecimal.valueOf(qty));
        tradeRepo.persist(trade);
        trade.setExchangeOrderLinkId("ex-" + trade.getId());

        // 4. Place order
        String side = "LONG".equals(req.direction()) ? "Buy" : "Sell";
        PlaceOrderRequest orderReq = new PlaceOrderRequest(
                "linear", req.symbol(), side, "Market",
                String.valueOf(qty),
                req.targetPrice().toPlainString(),
                req.stopPrice().toPlainString(),
                "Full", "Market", "Market",
                trade.getExchangeOrderLinkId(), null);

        BybitResponse<PlaceOrderResult> resp;
        try {
            resp = bybit.placeOrder(account.getEnvironment(),
                    account.getApiKeyEncrypted(), account.getApiSecretEncrypted(), orderReq);
        } catch (RuntimeException e) {
            LOG.errorf(e, "placeOrder threw for %s/%s", req.symbol(), req.direction());
            return fail(account, req, "Bybit call exception: " + e.getMessage());
        }

        if (resp.retCode() == RETCODE_OK || resp.retCode() == RETCODE_DUPLICATE_ORDER) {
            trade.setExchangeOrderId(resp.result().orderId());
            trade.setStatus(TradeStatus.OPEN);   // WS will refine to OPEN-with-fill later
            logEvent(account, trade, ExecutionEventType.ORDER_PLACED,
                    Map.of("orderId", resp.result().orderId(), "qty", qty));
            return trade;
        }
        if (resp.retCode() == RETCODE_INSUFFICIENT_MARGIN) {
            trade.setStatus(TradeStatus.FAILED);
            logEvent(account, trade, ExecutionEventType.SIGNAL_BLOCKED_INSUFFICIENT_MARGIN,
                    Map.of("retMsg", resp.retMsg()));
            return trade;
        }
        trade.setStatus(TradeStatus.FAILED);
        logEvent(account, trade, ExecutionEventType.ORDER_REJECTED,
                Map.of("retCode", resp.retCode(), "retMsg", resp.retMsg()));
        return trade;
    }

    @Transactional
    public void close(ExchangeAccount account, ExecutedTrade trade, ExitReason reason) {
        String side = "LONG".equals(trade.getDirection()) ? "Sell" : "Buy";
        PlaceOrderRequest closeReq = new PlaceOrderRequest(
                "linear", trade.getSymbol(), side, "Market",
                trade.getQty().toPlainString(),
                null, null, null, null, null,
                trade.getExchangeOrderLinkId() + "-close", true);
        try {
            bybit.placeOrder(account.getEnvironment(), account.getApiKeyEncrypted(),
                    account.getApiSecretEncrypted(), closeReq);
            trade.setStatus(TradeStatus.CLOSING);
            trade.setExitReason(reason);
        } catch (RuntimeException e) {
            LOG.errorf(e, "close order failed for trade %d", trade.getId());
        }
    }

    /**
     * Fetch current wallet equity. In Plan 2b this calls Bybit; in a later
     * iteration we can cache this in memory and refresh via WS.
     */
    private double fetchEquity(ExchangeAccount account) {
        try {
            BybitResponse<BybitV5RestClient.ListResult<WalletV5>> resp =
                    bybit.getWalletBalance(account.getEnvironment(),
                            account.getApiKeyEncrypted(), account.getApiSecretEncrypted());
            if (resp.isOk() && !resp.result().list().isEmpty()) {
                return Double.parseDouble(resp.result().list().get(0).totalEquity());
            }
        } catch (RuntimeException e) {
            LOG.warnf("wallet fetch failed: %s — falling back to 1000 equity", e.getMessage());
        }
        return 1000.0;
    }

    private ExecutedTrade fail(ExchangeAccount account, PlacementRequest req, String reason) {
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSignalId(req.signalId());
        t.setSymbol(req.symbol());
        t.setDirection(req.direction());
        t.setStrategy(req.strategy());
        t.setStatus(TradeStatus.FAILED);
        tradeRepo.persist(t);
        logEvent(account, t, ExecutionEventType.ORDER_REJECTED, Map.of("reason", reason));
        return t;
    }

    private void logEvent(ExchangeAccount account, ExecutedTrade trade,
                           ExecutionEventType type, Map<String, Object> metadata) {
        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(account.getId());
        ev.setEventType(type);
        ev.setSignalId(trade.getSignalId());
        ev.setExecutedTradeId(trade.getId());
        ev.setMetadata(metadata);
        eventRepo.persist(ev);
    }
}
```

- [ ] **Step 6: Integration tests (WireMock)**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/lifecycle/OrderPlacerTest.java`:

```java
package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import com.cryptoradar.execution.security.CredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestProfile(OrderPlacerTest.Profile.class)
class OrderPlacerTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            return Map.of(
                    "bybit.rest-base-override.DEMO", "http://localhost:38101",
                    "execution.master-key", Base64.getEncoder().encodeToString(k)
            );
        }
    }

    static WireMockServer wireMock;

    @Inject OrderPlacer placer;
    @Inject InstrumentRegistry instruments;
    @Inject ObjectMapper mapper;
    @Inject CredentialCipher cipher;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExecutionEventRepository eventRepo;

    ExchangeAccount account;

    @BeforeEach
    @Transactional
    void setup() {
        wireMock = new WireMockServer(38101);
        wireMock.start();
        WireMock.configureFor("localhost", 38101);
        instruments.invalidate();
        eventRepo.deleteAll();
        tradeRepo.deleteAll();
        accountRepo.deleteAll();
        account = new ExchangeAccount();
        account.setExchange("BYBIT");
        account.setEnvironment("DEMO");
        account.setApiKeyEncrypted(cipher.encrypt("k"));
        account.setApiSecretEncrypted(cipher.encrypt("s"));
        accountRepo.persist(account);

        stubWallet("1000");
        stubSetLeverageOk();
        stubInstrument("BTCUSDT", "0.001");
    }

    @AfterEach
    @Transactional
    void tearDown() {
        eventRepo.deleteAll();
        tradeRepo.deleteAll();
        accountRepo.deleteAll();
        wireMock.stop();
    }

    private void stubWallet(String equity) {
        try {
            stubFor(get(urlPathEqualTo("/v5/account/wallet-balance"))
                    .willReturn(okJson(mapper.writeValueAsString(Map.of(
                            "retCode", 0, "retMsg", "OK",
                            "result", Map.of("list", List.of(Map.of(
                                    "totalEquity", equity,
                                    "totalAvailableBalance", equity,
                                    "totalPerpUPL", "0",
                                    "totalWalletBalance", equity
                            ))),
                            "time", 1700000000000L
                    )))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void stubSetLeverageOk() {
        try {
            stubFor(post(urlPathEqualTo("/v5/position/set-leverage"))
                    .willReturn(okJson(mapper.writeValueAsString(Map.of(
                            "retCode", 0, "retMsg", "OK", "result", Map.of(),
                            "time", 1700000000000L)))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void stubInstrument(String symbol, String qtyStep) {
        try {
            stubFor(get(urlPathEqualTo("/v5/market/instruments-info"))
                    .withQueryParam("category", equalTo("linear"))
                    .withQueryParam("symbol", equalTo(symbol))
                    .willReturn(okJson(mapper.writeValueAsString(Map.of(
                            "retCode", 0, "retMsg", "OK",
                            "result", Map.of("list", List.of(Map.of(
                                    "symbol", symbol,
                                    "lotSizeFilter", Map.of("qtyStep", qtyStep)
                            ))),
                            "time", 1700000000000L
                    )))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void stubPlaceOrderOk(String orderId) {
        try {
            stubFor(post(urlPathEqualTo("/v5/order/create"))
                    .willReturn(okJson(mapper.writeValueAsString(Map.of(
                            "retCode", 0, "retMsg", "OK",
                            "result", Map.of("orderId", orderId, "orderLinkId", "ex-1"),
                            "time", 1700000000000L)))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void stubPlaceOrderRetcode(int retCode, String retMsg) {
        try {
            stubFor(post(urlPathEqualTo("/v5/order/create"))
                    .willReturn(okJson(mapper.writeValueAsString(Map.of(
                            "retCode", retCode, "retMsg", retMsg,
                            "result", Map.of(),
                            "time", 1700000000000L)))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void placeHappyPathInsertsOpenRow() {
        stubPlaceOrderOk("OID-1");
        OrderPlacer.PlacementRequest req = new OrderPlacer.PlacementRequest(
                "BTCUSDT", "LONG", "trend-continuation", "sig-1",
                new BigDecimal("50000"), new BigDecimal("49500"), new BigDecimal("51500"));
        ExecutedTrade trade = placer.place(account, req);
        assertNotNull(trade.getId());
        assertEquals(TradeStatus.OPEN, trade.getStatus());
        assertEquals("OID-1", trade.getExchangeOrderId());
    }

    @Test
    void insufficientMarginMarksFailed() {
        stubPlaceOrderRetcode(110007, "insufficient margin");
        OrderPlacer.PlacementRequest req = new OrderPlacer.PlacementRequest(
                "BTCUSDT", "LONG", "t", "s",
                new BigDecimal("50000"), new BigDecimal("49500"), new BigDecimal("51500"));
        ExecutedTrade trade = placer.place(account, req);
        assertEquals(TradeStatus.FAILED, trade.getStatus());
    }

    @Test
    void duplicateOrderLinkIdTreatedAsSuccess() {
        stubPlaceOrderRetcode(110061, "duplicate order link id");
        OrderPlacer.PlacementRequest req = new OrderPlacer.PlacementRequest(
                "BTCUSDT", "LONG", "t", "s",
                new BigDecimal("50000"), new BigDecimal("49500"), new BigDecimal("51500"));
        ExecutedTrade trade = placer.place(account, req);
        assertEquals(TradeStatus.OPEN, trade.getStatus());
    }

    @Test
    void unknownErrorMarksFailed() {
        stubPlaceOrderRetcode(10006, "rate limit exceeded");
        OrderPlacer.PlacementRequest req = new OrderPlacer.PlacementRequest(
                "BTCUSDT", "LONG", "t", "s",
                new BigDecimal("50000"), new BigDecimal("49500"), new BigDecimal("51500"));
        ExecutedTrade trade = placer.place(account, req);
        assertEquals(TradeStatus.FAILED, trade.getStatus());
    }
}
```

- [ ] **Step 7: Run + commit**

```bash
cd services/trade-execution-service && mvn test -Dtest=OrderPlacerTest -B
git add services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/OrderPlacer.java \
        services/trade-execution-service/src/test/java/com/cryptoradar/execution/lifecycle/OrderPlacerTest.java
git commit -m "feat(trade-execution): OrderPlacer places market orders with TP/SL"
```

---

## Task 5: `MarketDataClient` + `TrailMirror` — scheduled trail updater

**Files:**
- Create: `.../client/MarketDataClient.java`
- Create: `.../lifecycle/TrailMirror.java`
- Create: test: `.../lifecycle/TrailMirrorTest.java`

- [ ] **Step 1: Create `MarketDataClient` (small wrapper around signal-service's existing `/api/market/prices`)**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/MarketDataClient.java`:

```java
package com.cryptoradar.execution.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal client to market-data-service — fetches the last-known price for a
 * symbol. Used by TrailMirror to compute cumulative MFE.
 */
@ApplicationScoped
public class MarketDataClient {

    private static final Logger LOG = Logger.getLogger(MarketDataClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "market-data.url")
    String baseUrl;

    public BigDecimal getLastPrice(String symbol) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/market/prices"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode root = mapper.readTree(resp.body());
            JsonNode price = root.path(symbol).path("price");
            if (price.isMissingNode() || price.isNull()) return null;
            return new BigDecimal(price.asText());
        } catch (Exception e) {
            LOG.warnf("market-data fetch failed for %s: %s", symbol, e.getMessage());
            return null;
        }
    }
}
```

Note: this relies on market-data-service's `/api/market/prices` returning a map-keyed-by-symbol like `{"BTCUSDT": {"price": "50000", ...}, ...}`. If the actual response shape differs, inspect a live response first and adjust the JSON path.

- [ ] **Step 2: Implement `TrailMirror`**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/TrailMirror.java`:

```java
package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.core.TrailCalculator;
import com.cryptoradar.core.TrailConfig;
import com.cryptoradar.execution.client.MarketDataClient;
import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.TradingStopRequest;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Scheduled worker that advances trail stops on Bybit for every OPEN trade.
 * Uses shared TrailCalculator so the rung math matches signal-service's
 * OutcomeEvaluator exactly.
 */
@ApplicationScoped
public class TrailMirror {

    private static final Logger LOG = Logger.getLogger(TrailMirror.class);

    private final ExecutedTradeRepository tradeRepo;
    private final ExchangeAccountRepository accountRepo;
    private final ExecutionEventRepository eventRepo;
    private final BybitV5RestClient bybit;
    private final MarketDataClient marketData;

    public TrailMirror(ExecutedTradeRepository tradeRepo, ExchangeAccountRepository accountRepo,
                       ExecutionEventRepository eventRepo, BybitV5RestClient bybit,
                       MarketDataClient marketData) {
        this.tradeRepo = tradeRepo;
        this.accountRepo = accountRepo;
        this.eventRepo = eventRepo;
        this.bybit = bybit;
        this.marketData = marketData;
    }

    @Scheduled(every = "${execution.trail.interval:60s}", delay = 30, delayUnit = java.util.concurrent.TimeUnit.SECONDS)
    @Transactional
    public void tick() {
        accountRepo.listAll().forEach(this::processAccount);
    }

    private void processAccount(ExchangeAccount account) {
        for (ExecutedTrade trade : tradeRepo.findOpenForAccount(account.getId())) {
            try {
                processTrade(account, trade);
            } catch (RuntimeException e) {
                LOG.errorf(e, "trail-mirror error for trade %d", trade.getId());
            }
        }
    }

    void processTrade(ExchangeAccount account, ExecutedTrade trade) {
        BigDecimal price = marketData.getLastPrice(trade.getSymbol());
        if (price == null) return;
        BigDecimal entry = trade.getEntryPrice();
        BigDecimal stop = trade.getStopPrice();
        if (entry == null || stop == null) return;

        boolean isLong = "LONG".equals(trade.getDirection());
        BigDecimal risk = entry.subtract(stop).abs();
        if (risk.signum() <= 0) return;

        BigDecimal mfePct = isLong
                ? price.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP)
                : entry.subtract(price).divide(entry, 8, RoundingMode.HALF_UP);
        if (mfePct.signum() <= 0) return;

        double riskPct = risk.divide(entry, 8, RoundingMode.HALF_UP).doubleValue() * 100.0;
        double mfePctD = mfePct.doubleValue() * 100.0;
        double mfeR = riskPct == 0 ? 0 : mfePctD / riskPct;

        TrailConfig config = TrailConfig.DEFAULT;
        Optional<Double> newR = TrailCalculator.computeNewTrailR(
                mfeR, config, trade.getTrailHighestR().doubleValue());
        if (newR.isEmpty()) return;

        double newRungR = newR.get();
        BigDecimal newStopPrice = isLong
                ? entry.add(risk.multiply(BigDecimal.valueOf(newRungR)))
                : entry.subtract(risk.multiply(BigDecimal.valueOf(newRungR)));

        TradingStopRequest req = new TradingStopRequest(
                "linear", trade.getSymbol(),
                newStopPrice.toPlainString(), "Full", 0);
        BybitResponse<Map<String, Object>> resp = bybit.setTradingStop(
                account.getEnvironment(), account.getApiKeyEncrypted(),
                account.getApiSecretEncrypted(), req);
        if (!resp.isOk()) {
            LOG.warnf("setTradingStop failed for trade %d: retCode=%d retMsg=%s",
                    trade.getId(), resp.retCode(), resp.retMsg());
            return;
        }

        trade.setTrailHighestR(BigDecimal.valueOf(newRungR));
        trade.setDynamicStopPrice(newStopPrice);
        if (trade.getTrailTriggeredAt() == null) {
            trade.setTrailTriggeredAt(Instant.now());
        }

        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(account.getId());
        ev.setEventType(ExecutionEventType.TRAIL_UPDATED);
        ev.setExecutedTradeId(trade.getId());
        ev.setSignalId(trade.getSignalId());
        ev.setMetadata(Map.of("newTrailR", newRungR, "newStop", newStopPrice.toPlainString()));
        eventRepo.persist(ev);
    }
}
```

- [ ] **Step 3: Unit test (exercise the pure `processTrade` path with mocked deps)**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/lifecycle/TrailMirrorTest.java`:

```java
package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.client.MarketDataClient;
import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.TradingStopRequest;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrailMirrorTest {

    ExecutedTradeRepository tradeRepo;
    ExchangeAccountRepository accountRepo;
    ExecutionEventRepository eventRepo;
    BybitV5RestClient bybit;
    MarketDataClient marketData;
    TrailMirror mirror;

    ExchangeAccount account;

    @BeforeEach
    void setup() {
        tradeRepo = mock(ExecutedTradeRepository.class);
        accountRepo = mock(ExchangeAccountRepository.class);
        eventRepo = mock(ExecutionEventRepository.class);
        bybit = mock(BybitV5RestClient.class);
        marketData = mock(MarketDataClient.class);
        mirror = new TrailMirror(tradeRepo, accountRepo, eventRepo, bybit, marketData);

        account = new ExchangeAccount();
        account.setExchange("BYBIT");
        account.setEnvironment("DEMO");
        account.setApiKeyEncrypted("k");
        account.setApiSecretEncrypted("s");
    }

    private ExecutedTrade openLongTrade(BigDecimal entry, BigDecimal stop, double trailHighestR) {
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(1L);
        t.setSymbol("BTCUSDT");
        t.setDirection("LONG");
        t.setStatus(TradeStatus.OPEN);
        t.setEntryPrice(entry);
        t.setStopPrice(stop);
        t.setTrailHighestR(BigDecimal.valueOf(trailHighestR));
        return t;
    }

    @Test
    void noPriceNoAction() {
        ExecutedTrade t = openLongTrade(new BigDecimal("50000"), new BigDecimal("49500"), 0);
        when(marketData.getLastPrice("BTCUSDT")).thenReturn(null);
        mirror.processTrade(account, t);
        assertEquals(BigDecimal.valueOf(0), t.getTrailHighestR());
    }

    @Test
    void belowActivationNoAction() {
        // entry 50000, stop 49500, risk=500 → 1% riskPct
        // price 50400 → MFE 0.8% → mfeR = 0.8 (below activation 1.0)
        ExecutedTrade t = openLongTrade(new BigDecimal("50000"), new BigDecimal("49500"), 0);
        when(marketData.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("50400"));
        mirror.processTrade(account, t);
        assertEquals(BigDecimal.valueOf(0), t.getTrailHighestR());
    }

    @Test
    void aboveActivationAdvancesTrailAndCallsBybit() {
        ExecutedTrade t = openLongTrade(new BigDecimal("50000"), new BigDecimal("49500"), 0);
        // price 51000 → MFE 2% → mfeR 2.0 → trail rung 1.0 (activation 1.0 + 2*0.5 - 0.5 = 1.5 actually)
        // Let's compute: (2.0 - 1.0) / 0.5 = 2 rungs
        //   newTrailR = 1.0 + 2*0.5 - 0.5 = 1.5
        when(marketData.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("51000"));
        when(bybit.setTradingStop(anyString(), anyString(), anyString(), any(TradingStopRequest.class)))
                .thenReturn(new BybitResponse<Map<String, Object>>(0, "OK", Map.of(), 0L));

        mirror.processTrade(account, t);

        assertEquals(new BigDecimal("1.5"), t.getTrailHighestR().stripTrailingZeros().setScale(1));
        // new stop = 50000 + 500 * 1.5 = 50750
        assertEquals(new BigDecimal("50750"), t.getDynamicStopPrice().setScale(0));
        assertNotNull(t.getTrailTriggeredAt());

        ArgumentCaptor<TradingStopRequest> captor = ArgumentCaptor.forClass(TradingStopRequest.class);
        verify(bybit).setTradingStop(anyString(), anyString(), anyString(), captor.capture());
        assertEquals("linear", captor.getValue().category());
    }

    @Test
    void shortDirectionReversesStopMath() {
        // SHORT, entry 3000, stop 3050 → risk 50, price 2900 → MFE 100/3000 = 3.33%
        // riskPct = 50/3000 = 1.67%
        // mfeR = 3.33/1.67 = ~2.0
        // trail rung newR = 1.0 + 2*0.5 - 0.5 = 1.5
        // SHORT new stop = entry - risk * newR = 3000 - 50*1.5 = 2925
        ExecutedTrade t = openLongTrade(new BigDecimal("3000"), new BigDecimal("3050"), 0);
        t.setDirection("SHORT");
        when(marketData.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("2900"));
        when(bybit.setTradingStop(anyString(), anyString(), anyString(), any(TradingStopRequest.class)))
                .thenReturn(new BybitResponse<Map<String, Object>>(0, "OK", Map.of(), 0L));

        mirror.processTrade(account, t);
        assertEquals(0, new BigDecimal("2925").compareTo(t.getDynamicStopPrice()));
    }
}
```

Add test dependency to `pom.xml` if not already present (Mockito):
```xml
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>5.14.2</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 4: Run + commit**

```bash
cd services/trade-execution-service && mvn test -Dtest=TrailMirrorTest -B
git add services/trade-execution-service/
git commit -m "feat(trade-execution): TrailMirror + MarketDataClient for scheduled trail updates"
```

---

## Task 6: `OrderReconciler`

**Files:**
- Create: `.../lifecycle/OrderReconciler.java`
- Create: test: `.../lifecycle/OrderReconcilerTest.java`

- [ ] **Step 1: Implement**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/lifecycle/OrderReconciler.java`:

```java
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
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
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

/**
 * Startup + periodic drift check between local DB and Bybit position state.
 * Detects orphans (Bybit has position we don't know about), closed-externally
 * (local OPEN but Bybit has none — fetch closed-pnl to populate realized
 * P&L + fees + exit reason).
 */
@ApplicationScoped
public class OrderReconciler {

    private static final Logger LOG = Logger.getLogger(OrderReconciler.class);

    private final ExecutedTradeRepository tradeRepo;
    private final ExchangeAccountRepository accountRepo;
    private final ExecutionEventRepository eventRepo;
    private final BybitV5RestClient bybit;

    public OrderReconciler(ExecutedTradeRepository tradeRepo, ExchangeAccountRepository accountRepo,
                           ExecutionEventRepository eventRepo, BybitV5RestClient bybit) {
        this.tradeRepo = tradeRepo;
        this.accountRepo = accountRepo;
        this.eventRepo = eventRepo;
        this.bybit = bybit;
    }

    void onStartup(@Observes StartupEvent ev) {
        try {
            reconcile();
        } catch (RuntimeException e) {
            LOG.warnf("startup reconcile failed: %s", e.getMessage());
        }
    }

    @Scheduled(every = "${execution.reconcile.interval:60s}", delay = 45, delayUnit = java.util.concurrent.TimeUnit.SECONDS)
    @Transactional
    public void reconcile() {
        accountRepo.listAll().forEach(this::reconcileAccount);
    }

    void reconcileAccount(ExchangeAccount account) {
        BybitResponse<BybitV5RestClient.ListResult<PositionV5>> resp;
        try {
            resp = bybit.getPositionList(account.getEnvironment(),
                    account.getApiKeyEncrypted(), account.getApiSecretEncrypted());
        } catch (RuntimeException e) {
            LOG.warnf("positionList fetch failed for account %d: %s", account.getId(), e.getMessage());
            return;
        }
        if (!resp.isOk() || resp.result() == null) return;

        List<PositionV5> remote = resp.result().list();
        List<ExecutedTrade> local = tradeRepo.findOpenForAccount(account.getId());

        Set<String> remoteOpen = new HashSet<>();
        for (PositionV5 pos : remote) {
            if (pos.size() == null || "0".equals(pos.size()) || new BigDecimal(pos.size()).signum() == 0) continue;
            remoteOpen.add(pos.symbol() + "|" + pos.side());
        }

        // Find local rows that are no longer on Bybit — closed externally
        for (ExecutedTrade trade : local) {
            String side = "LONG".equals(trade.getDirection()) ? "Buy" : "Sell";
            String key = trade.getSymbol() + "|" + side;
            if (!remoteOpen.contains(key)) {
                closeFromReconcile(account, trade);
            } else {
                trade.setLastSyncAt(Instant.now());
            }
        }

        // Find remote positions that aren't tracked locally — orphans
        Set<Long> localSymbolsOpen = new HashSet<>();
        for (ExecutedTrade trade : local) {
            String side = "LONG".equals(trade.getDirection()) ? "Buy" : "Sell";
            if (remoteOpen.contains(trade.getSymbol() + "|" + side)) {
                localSymbolsOpen.add(trade.getId());
            }
        }
        for (PositionV5 pos : remote) {
            if (pos.size() == null || "0".equals(pos.size()) || new BigDecimal(pos.size()).signum() == 0) continue;
            String direction = "Buy".equals(pos.side()) ? "LONG" : "SHORT";
            boolean tracked = local.stream().anyMatch(t ->
                    t.getSymbol().equals(pos.symbol()) && t.getDirection().equals(direction));
            if (!tracked) {
                createOrphan(account, pos, direction);
            }
        }
    }

    private void closeFromReconcile(ExchangeAccount account, ExecutedTrade trade) {
        BybitResponse<BybitV5RestClient.ListResult<ClosedPnlV5>> pnlResp;
        try {
            pnlResp = bybit.getClosedPnl(account.getEnvironment(),
                    account.getApiKeyEncrypted(), account.getApiSecretEncrypted(),
                    trade.getSymbol(), 10);
        } catch (RuntimeException e) {
            LOG.warnf("closedPnl fetch failed for trade %d: %s", trade.getId(), e.getMessage());
            return;
        }
        if (!pnlResp.isOk() || pnlResp.result() == null || pnlResp.result().list().isEmpty()) return;

        // Pick the most recent matching close
        ClosedPnlV5 match = pnlResp.result().list().get(0);

        trade.setStatus(TradeStatus.CLOSED);
        trade.setClosedAt(Instant.now());
        trade.setRealizedPnlUsdt(safeBd(match.closedPnl()));
        trade.setFeesUsdt(safeBd(match.openFee()).add(safeBd(match.closeFee())));
        trade.setExitPrice(safeBd(match.orderPrice()));
        trade.setExitReason(trade.getExitReason() != null ? trade.getExitReason() : ExitReason.TARGET);

        if (trade.getEntryPrice() != null && trade.getStopPrice() != null) {
            BigDecimal riskDist = trade.getEntryPrice().subtract(trade.getStopPrice()).abs();
            if (riskDist.signum() > 0 && trade.getExitPrice() != null) {
                BigDecimal pnlDist = "LONG".equals(trade.getDirection())
                        ? trade.getExitPrice().subtract(trade.getEntryPrice())
                        : trade.getEntryPrice().subtract(trade.getExitPrice());
                trade.setRealizedRMultiple(pnlDist.divide(riskDist, 4, RoundingMode.HALF_UP));
            }
        }

        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(account.getId());
        ev.setEventType(ExecutionEventType.RECONCILE_CLOSED_EXTERNALLY);
        ev.setSignalId(trade.getSignalId());
        ev.setExecutedTradeId(trade.getId());
        ev.setMetadata(Map.of("closedPnl", match.closedPnl(), "orderPrice", match.orderPrice()));
        eventRepo.persist(ev);
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
        eventRepo.persist(ev);
    }

    private static BigDecimal safeBd(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }
}
```

- [ ] **Step 2: Integration tests**

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/lifecycle/OrderReconcilerTest.java`:

```java
package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import com.cryptoradar.execution.security.CredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(OrderReconcilerTest.Profile.class)
class OrderReconcilerTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            return Map.of(
                    "bybit.rest-base-override.DEMO", "http://localhost:38102",
                    "execution.master-key", Base64.getEncoder().encodeToString(k)
            );
        }
    }

    static WireMockServer wireMock;

    @Inject OrderReconciler reconciler;
    @Inject ObjectMapper mapper;
    @Inject CredentialCipher cipher;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExecutionEventRepository eventRepo;

    ExchangeAccount account;

    @BeforeEach
    @Transactional
    void setup() {
        wireMock = new WireMockServer(38102);
        wireMock.start();
        WireMock.configureFor("localhost", 38102);
        eventRepo.deleteAll();
        tradeRepo.deleteAll();
        accountRepo.deleteAll();

        account = new ExchangeAccount();
        account.setExchange("BYBIT");
        account.setEnvironment("DEMO");
        account.setApiKeyEncrypted(cipher.encrypt("k"));
        account.setApiSecretEncrypted(cipher.encrypt("s"));
        accountRepo.persist(account);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        eventRepo.deleteAll();
        tradeRepo.deleteAll();
        accountRepo.deleteAll();
        wireMock.stop();
    }

    private void stubPositionsEmpty() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/position/list"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of()),
                        "time", 1700000000000L)))));
    }

    private void stubPositionsWithBtcLong() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/position/list"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of(Map.of(
                                "symbol", "BTCUSDT",
                                "side", "Buy",
                                "positionIdx", 0,
                                "size", "0.001",
                                "avgPrice", "50000",
                                "leverage", "3",
                                "stopLoss", "49500",
                                "takeProfit", "52000",
                                "unrealisedPnl", "10",
                                "createdTime", "1700000000000",
                                "updatedTime", "1700000000000"
                        ))),
                        "time", 1700000000000L)))));
    }

    private void stubClosedPnlWithEntry() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/position/closed-pnl"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of(Map.of(
                                "symbol", "BTCUSDT",
                                "orderId", "CLOSE-1",
                                "side", "Sell",
                                "qty", "0.001",
                                "orderPrice", "51000",
                                "closedPnl", "1.0",
                                "openFee", "0.025",
                                "closeFee", "0.025",
                                "createdTime", "1700000100000",
                                "updatedTime", "1700000100000"
                        ))),
                        "time", 1700000100000L)))));
    }

    @Test
    @Transactional
    void closesLocalRowWhenRemoteHasNoMatchingPosition() throws Exception {
        // Seed a local OPEN row, stub Bybit to show no positions
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSymbol("BTCUSDT");
        t.setDirection("LONG");
        t.setStatus(TradeStatus.OPEN);
        t.setEntryPrice(new BigDecimal("50000"));
        t.setStopPrice(new BigDecimal("49500"));
        t.setTargetPrice(new BigDecimal("52000"));
        t.setQty(new BigDecimal("0.001"));
        t.setExchangeOrderLinkId("ex-test-1");
        tradeRepo.persist(t);

        stubPositionsEmpty();
        stubClosedPnlWithEntry();

        reconciler.reconcileAccount(account);

        ExecutedTrade refreshed = tradeRepo.findById(t.getId());
        assertEquals(TradeStatus.CLOSED, refreshed.getStatus());
        assertEquals(0, new BigDecimal("1.0").compareTo(refreshed.getRealizedPnlUsdt()));
        assertTrue(refreshed.getFeesUsdt().signum() > 0);
    }

    @Test
    @Transactional
    void createsOrphanWhenBybitHasPositionNotTrackedLocally() throws Exception {
        stubPositionsWithBtcLong();

        reconciler.reconcileAccount(account);

        List<ExecutedTrade> all = tradeRepo.listAll();
        assertEquals(1, all.size());
        ExecutedTrade orphan = all.get(0);
        assertEquals("BTCUSDT", orphan.getSymbol());
        assertEquals("LONG", orphan.getDirection());
        assertEquals(TradeStatus.OPEN, orphan.getStatus());
    }
}
```

- [ ] **Step 3: Commit**

```bash
cd services/trade-execution-service && mvn test -Dtest=OrderReconcilerTest -B
git add services/trade-execution-service/
git commit -m "feat(trade-execution): OrderReconciler for startup + drift checks"
```

---

## Task 7: `SignalSubscriber` — Redis intake pipeline

**Files:**
- Create: `.../intake/SignalSubscriber.java`

- [ ] **Step 1: Implement**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/intake/SignalSubscriber.java`:

```java
package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.lifecycle.OrderPlacer;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.model.ExitReason;
import com.cryptoradar.execution.policy.GuardrailPolicy;
import com.cryptoradar.execution.policy.GuardrailPolicy.Decision;
import com.cryptoradar.execution.policy.GuardrailPolicy.SignalCandidate;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Subscribes to Redis 'crypto:signals' channel. Consumes both 'alert' and
 * 'overview' envelope types. For each actionable STRONG signal:
 *   FlipTracker → GuardrailPolicy → OrderPlacer.place()
 *   OR (on CLOSE_*) OrderPlacer.close() for any matching OPEN trade.
 */
@ApplicationScoped
public class SignalSubscriber {

    private static final Logger LOG = Logger.getLogger(SignalSubscriber.class);
    private static final String CHANNEL = "crypto:signals";

    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @Inject FlipTracker flipTracker;
    @Inject GuardrailPolicy guardrails;
    @Inject OrderPlacer orderPlacer;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExecutionEventRepository eventRepo;

    @ConfigProperty(name = "quarkus.redis.hosts", defaultValue = "redis://localhost:6379")
    String redisHosts;

    void onStart(@Observes StartupEvent event) {
        Executors.newSingleThreadScheduledExecutor().schedule(this::connect, 3, TimeUnit.SECONDS);
    }

    void connect() {
        LOG.info("SignalSubscriber connecting to Redis...");
        RedisOptions options = new RedisOptions().setConnectionString(redisHosts);
        Redis.createClient(vertx, options).connect().subscribe().with(
                this::setupSubscription,
                err -> {
                    LOG.errorf("SignalSubscriber Redis connect failed: %s — retry in 10s", err.getMessage());
                    Executors.newSingleThreadScheduledExecutor().schedule(this::connect, 10, TimeUnit.SECONDS);
                }
        );
    }

    void setupSubscription(RedisConnection conn) {
        LOG.infof("SignalSubscriber subscribed to %s", CHANNEL);
        conn.handler(resp -> {
            try {
                if (resp == null || resp.size() < 3) return;
                String kind = resp.get(0).toString();
                if (!"message".equals(kind)) return;
                String payload = resp.get(2).toString();
                onMessage(payload);
            } catch (RuntimeException e) {
                LOG.errorf(e, "message handler error");
            }
        });
        conn.send(io.vertx.mutiny.redis.client.Request.cmd(io.vertx.redis.client.Command.SUBSCRIBE).arg(CHANNEL))
                .subscribe().with(
                        v -> LOG.infof("SUBSCRIBE response received"),
                        err -> LOG.errorf("SUBSCRIBE failed: %s", err.getMessage()));
    }

    @Transactional
    public void onMessage(String json) {
        try {
            JsonNode envelope = mapper.readTree(json);
            String type = envelope.path("type").asText("");
            JsonNode data = envelope.path("data");

            if ("alert".equals(type)) {
                handleSignal(data.path("signal"));
            } else if ("overview".equals(type)) {
                JsonNode signals = data.path("signals");
                if (signals.isArray()) {
                    signals.forEach(this::handleSignal);
                }
            }
        } catch (Exception e) {
            LOG.warnf("parse error: %s", e.getMessage());
        }
    }

    private void handleSignal(JsonNode signalNode) {
        String symbol = signalNode.path("symbol").asText();
        String label = signalNode.path("signal").asText();
        if (symbol.isEmpty() || label.isEmpty()) return;
        if (!"STRONG_BUY".equals(label) && !"STRONG_SELL".equals(label)) return;

        List<ExchangeAccount> accounts = accountRepo.listAll();
        if (accounts.isEmpty()) return;

        for (ExchangeAccount account : accounts) {
            // Single DB pass: check if the account holds ANY open position for this symbol in
            // either direction, across ALL strategies. Strategy-scoped dedup happens separately
            // in dispatchEnter via findOpenBySymbolAndDirectionAndStrategy.
            boolean hasLong = false;
            boolean hasShort = false;
            for (var t : tradeRepo.findOpenForAccount(account.getId())) {
                if (!symbol.equals(t.getSymbol())) continue;
                if ("LONG".equals(t.getDirection())) hasLong = true;
                else if ("SHORT".equals(t.getDirection())) hasShort = true;
                if (hasLong && hasShort) break;
            }

            FlipTracker.Action action = flipTracker.observe(
                    symbol, label, account.getFlipPersistenceTicks(), hasLong, hasShort);

            switch (action) {
                case NO_ACTION -> { /* skip */ }
                case ENTER_LONG, ENTER_SHORT -> dispatchEnter(account, signalNode, symbol,
                        action == FlipTracker.Action.ENTER_LONG ? "LONG" : "SHORT");
                case CLOSE_LONG -> dispatchClose(account, symbol, "LONG");
                case CLOSE_SHORT -> dispatchClose(account, symbol, "SHORT");
            }
        }
    }

    private void dispatchEnter(ExchangeAccount account, JsonNode signalNode, String symbol, String direction) {
        SignalCandidate candidate = new SignalCandidate(
                symbol, direction,
                signalNode.path("strategy").asText("dimension"),
                signalNode.path("signalId").asText(null),
                Instant.now());

        int openCount = tradeRepo.countOpenForAccount(account.getId());
        BigDecimal todayPnlPct = BigDecimal.ZERO;   // Phase 1: daily-halt inactive until wallet-based today-pnl tracking ships in a later iteration
        boolean dedupHit = tradeRepo.findOpenBySymbolAndDirectionAndStrategy(
                account.getId(), symbol, direction, candidate.strategy()).isPresent();

        Decision decision = guardrails.evaluate(account, candidate, openCount, todayPnlPct, dedupHit);
        if (!decision.accepted()) {
            logBlock(account, candidate, decision.blockReason());
            return;
        }

        BigDecimal entry = safeBd(signalNode.path("entryPrice").asText(null));
        BigDecimal stop = safeBd(signalNode.path("stopPrice").asText(null));
        BigDecimal target = safeBd(signalNode.path("targetPrice").asText(null));
        if (entry == null || stop == null || target == null) return;

        orderPlacer.place(account, new OrderPlacer.PlacementRequest(
                symbol, direction, candidate.strategy(), candidate.signalId(), entry, stop, target));
    }

    private void dispatchClose(ExchangeAccount account, String symbol, String direction) {
        tradeRepo.findOpenForAccount(account.getId()).stream()
                .filter(t -> t.getSymbol().equals(symbol) && t.getDirection().equals(direction))
                .forEach(t -> orderPlacer.close(account, t, ExitReason.FLIP_CLOSE));
    }

    private void logBlock(ExchangeAccount account, SignalCandidate candidate, ExecutionEventType reason) {
        ExecutionEvent ev = new ExecutionEvent();
        ev.setExchangeAccountId(account.getId());
        ev.setEventType(reason);
        ev.setSignalId(candidate.signalId());
        ev.setMetadata(Map.of("symbol", candidate.symbol(), "direction", candidate.direction()));
        eventRepo.persist(ev);
    }

    private static BigDecimal safeBd(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }
}
```

- [ ] **Step 2: Skip unit test for the Redis-bound onStart flow** (requires a real Redis). Instead, add a thin unit test that invokes `onMessage(String)` with canned envelopes and asserts the downstream call:

Write `services/trade-execution-service/src/test/java/com/cryptoradar/execution/intake/SignalSubscriberOnMessageTest.java`:

```java
package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.security.CredentialCipher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(SignalSubscriberOnMessageTest.Profile.class)
class SignalSubscriberOnMessageTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            return Map.of("execution.master-key", Base64.getEncoder().encodeToString(k));
        }
    }

    @Inject SignalSubscriber subscriber;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;
    @Inject CredentialCipher cipher;

    @BeforeEach
    @Transactional
    void cleanDb() {
        tradeRepo.deleteAll();
        accountRepo.deleteAll();
    }

    @Test
    @Transactional
    void malformedJsonDoesNotThrow() {
        subscriber.onMessage("not json");   // must not throw
        subscriber.onMessage("{malformed");
        subscriber.onMessage("{}");
    }

    @Test
    @Transactional
    void nonActionableSignalIsIgnored() {
        subscriber.onMessage("{\"type\":\"alert\",\"data\":{\"signal\":{\"symbol\":\"BTCUSDT\",\"signal\":\"NEUTRAL\"}}}");
        assertEquals(0, tradeRepo.listAll().size());
    }

    @Test
    @Transactional
    void noAccountsConfiguredDoesNothing() {
        subscriber.onMessage("{\"type\":\"alert\",\"data\":{\"signal\":{\"symbol\":\"BTCUSDT\",\"signal\":\"STRONG_BUY\"}}}");
        assertEquals(0, tradeRepo.listAll().size());
    }
}
```

(Full happy-path tests for `onMessage` that exercise `OrderPlacer` live-invocation would require stubbing Bybit — those are covered end-to-end in Task 11's smoke test.)

- [ ] **Step 3: Commit**

```bash
cd services/trade-execution-service && mvn test -B
git add services/trade-execution-service/
git commit -m "feat(trade-execution): SignalSubscriber consumes crypto:signals"
```

---

## Task 8: `TradingResource` + `DevModeResource` — read + control endpoints

**Files:**
- Create: `.../resource/dto/WalletSnapshot.java`, `PositionView.java`, `TradeView.java`, `EventView.java`, `WhyView.java`, `KillSwitchRequest.java`, `CloseAllRequest.java`, `InjectSignalRequest.java`
- Create: `.../resource/TradingResource.java`
- Create: `.../resource/DevModeResource.java`

- [ ] **Step 1: Create response DTOs**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/resource/dto/WalletSnapshot.java`:

```java
package com.cryptoradar.execution.resource.dto;

import java.math.BigDecimal;

public record WalletSnapshot(
        BigDecimal equity,
        BigDecimal available,
        BigDecimal openPnl,
        BigDecimal todayRealized,
        int positionsOpen
) {}
```

Write `PositionView.java`:

```java
package com.cryptoradar.execution.resource.dto;

import com.cryptoradar.execution.model.ExecutedTrade;

import java.math.BigDecimal;
import java.time.Instant;

public record PositionView(
        Long id, Long accountId, String signalId, String symbol, String direction,
        String strategy, String status, BigDecimal entryPrice, BigDecimal qty,
        Integer leverage, BigDecimal stopPrice, BigDecimal targetPrice,
        BigDecimal dynamicStopPrice, BigDecimal trailHighestR,
        Instant trailTriggeredAt, Instant openedAt
) {
    public static PositionView of(ExecutedTrade t) {
        return new PositionView(
                t.getId(), t.getExchangeAccountId(), t.getSignalId(), t.getSymbol(),
                t.getDirection(), t.getStrategy(), t.getStatus().name(),
                t.getEntryPrice(), t.getQty(), t.getLeverage(),
                t.getStopPrice(), t.getTargetPrice(), t.getDynamicStopPrice(),
                t.getTrailHighestR(), t.getTrailTriggeredAt(), t.getOpenedAt());
    }
}
```

Write `TradeView.java`:

```java
package com.cryptoradar.execution.resource.dto;

import com.cryptoradar.execution.model.ExecutedTrade;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeView(
        Long id, String signalId, String symbol, String direction, String strategy,
        String status, BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal qty,
        BigDecimal realizedPnlUsdt, BigDecimal realizedRMultiple, BigDecimal feesUsdt,
        String exitReason, Instant openedAt, Instant closedAt
) {
    public static TradeView of(ExecutedTrade t) {
        return new TradeView(
                t.getId(), t.getSignalId(), t.getSymbol(), t.getDirection(), t.getStrategy(),
                t.getStatus().name(), t.getEntryPrice(), t.getExitPrice(), t.getQty(),
                t.getRealizedPnlUsdt(), t.getRealizedRMultiple(), t.getFeesUsdt(),
                t.getExitReason() == null ? null : t.getExitReason().name(),
                t.getOpenedAt(), t.getClosedAt());
    }
}
```

Write `EventView.java`:

```java
package com.cryptoradar.execution.resource.dto;

import com.cryptoradar.execution.model.ExecutionEvent;

import java.time.Instant;
import java.util.Map;

public record EventView(
        Long id, String eventType, String signalId, Long executedTradeId,
        Map<String, Object> metadata, Instant createdAt
) {
    public static EventView of(ExecutionEvent ev) {
        return new EventView(
                ev.getId(), ev.getEventType().name(), ev.getSignalId(),
                ev.getExecutedTradeId(), ev.getMetadata(), ev.getCreatedAt());
    }
}
```

Write `WhyView.java`:

```java
package com.cryptoradar.execution.resource.dto;

import java.time.Instant;
import java.util.Map;

public record WhyView(
        Long tradeId,
        String signalId,
        String symbol,
        String direction,
        String strategy,
        Instant openedAt,
        Map<String, Object> signalSnapshot   // raw dimension scores, regime, AI analysis joined from signal_outcomes
) {}
```

Write `KillSwitchRequest.java`:

```java
package com.cryptoradar.execution.resource.dto;

public record KillSwitchRequest(boolean enabled) {}
```

Write `CloseAllRequest.java`:

```java
package com.cryptoradar.execution.resource.dto;

public record CloseAllRequest(String confirm) {}
```

Write `InjectSignalRequest.java`:

```java
package com.cryptoradar.execution.resource.dto;

import java.math.BigDecimal;

public record InjectSignalRequest(
        String symbol, String direction, String strategy,
        BigDecimal entryPrice, BigDecimal stopPrice, BigDecimal targetPrice
) {}
```

- [ ] **Step 2: Implement `TradingResource`**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/resource/TradingResource.java`:

```java
package com.cryptoradar.execution.resource;

import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.WalletV5;
import com.cryptoradar.execution.lifecycle.OrderPlacer;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExitReason;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import com.cryptoradar.execution.resource.dto.CloseAllRequest;
import com.cryptoradar.execution.resource.dto.EventView;
import com.cryptoradar.execution.resource.dto.KillSwitchRequest;
import com.cryptoradar.execution.resource.dto.PositionView;
import com.cryptoradar.execution.resource.dto.TradeView;
import com.cryptoradar.execution.resource.dto.WalletSnapshot;
import com.cryptoradar.execution.resource.dto.WhyView;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Path("/api/execution/accounts/{accountId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TradingResource {

    private final ExchangeAccountRepository accountRepo;
    private final ExecutedTradeRepository tradeRepo;
    private final ExecutionEventRepository eventRepo;
    private final BybitV5RestClient bybit;
    private final OrderPlacer orderPlacer;

    public TradingResource(ExchangeAccountRepository accountRepo, ExecutedTradeRepository tradeRepo,
                           ExecutionEventRepository eventRepo, BybitV5RestClient bybit,
                           OrderPlacer orderPlacer) {
        this.accountRepo = accountRepo;
        this.tradeRepo = tradeRepo;
        this.eventRepo = eventRepo;
        this.bybit = bybit;
        this.orderPlacer = orderPlacer;
    }

    @GET
    @Path("/wallet")
    public Response wallet(@PathParam("accountId") Long id) {
        ExchangeAccount a = accountRepo.findById(id);
        if (a == null) return Response.status(404).build();
        try {
            BybitResponse<BybitV5RestClient.ListResult<WalletV5>> resp =
                    bybit.getWalletBalance(a.getEnvironment(), a.getApiKeyEncrypted(), a.getApiSecretEncrypted());
            if (!resp.isOk() || resp.result() == null || resp.result().list().isEmpty()) {
                return Response.status(502).entity(Map.of("error", "Bybit wallet fetch failed")).build();
            }
            WalletV5 w = resp.result().list().get(0);
            int openCount = tradeRepo.countOpenForAccount(id);
            return Response.ok(new WalletSnapshot(
                    bd(w.totalEquity()), bd(w.totalAvailableBalance()),
                    bd(w.totalPerpUPL()), BigDecimal.ZERO,   // today-realized left for future iteration
                    openCount)).build();
        } catch (RuntimeException e) {
            return Response.status(502).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/positions")
    public List<PositionView> positions(@PathParam("accountId") Long id) {
        return tradeRepo.findOpenForAccount(id).stream().map(PositionView::of).toList();
    }

    @GET
    @Path("/trades")
    public List<TradeView> trades(@PathParam("accountId") Long id,
                                   @QueryParam("limit") @DefaultValue("50") int limit) {
        return tradeRepo.findClosedSince(id, Instant.EPOCH, limit).stream().map(TradeView::of).toList();
    }

    @GET
    @Path("/events")
    public List<EventView> events(@PathParam("accountId") Long id,
                                   @QueryParam("limit") @DefaultValue("100") int limit) {
        return eventRepo.findRecentForAccount(id, limit).stream().map(EventView::of).toList();
    }

    @GET
    @Path("/trades/{tradeId}/why")
    public Response why(@PathParam("accountId") Long accountId, @PathParam("tradeId") Long tradeId) {
        ExecutedTrade t = tradeRepo.findById(tradeId);
        if (t == null || !t.getExchangeAccountId().equals(accountId)) return Response.status(404).build();
        // Join with signal_outcomes via signalId. For now, return the metadata we have locally.
        // Later: native query against signal_outcomes for dimension scores + AI analysis.
        return Response.ok(new WhyView(
                t.getId(), t.getSignalId(), t.getSymbol(), t.getDirection(), t.getStrategy(),
                t.getOpenedAt(), Map.of(
                        "note", "Join with signal_outcomes for dimension scores — phase 1 stub",
                        "signalId", t.getSignalId()
                ))).build();
    }

    @POST
    @Path("/kill-switch")
    @Transactional
    public Response killSwitch(@PathParam("accountId") Long id, KillSwitchRequest req) {
        ExchangeAccount a = accountRepo.findById(id);
        if (a == null) return Response.status(404).build();
        a.setKillSwitch(req.enabled());
        return Response.ok(Map.of("killSwitch", a.isKillSwitch())).build();
    }

    @POST
    @Path("/close-all")
    @Transactional
    public Response closeAll(@PathParam("accountId") Long id, CloseAllRequest req) {
        ExchangeAccount a = accountRepo.findById(id);
        if (a == null) return Response.status(404).build();
        if (!"CLOSE_ALL".equals(req.confirm())) {
            return Response.status(400).entity(Map.of("error", "confirm field must be 'CLOSE_ALL'")).build();
        }
        List<ExecutedTrade> open = tradeRepo.findOpenForAccount(id);
        for (ExecutedTrade t : open) {
            orderPlacer.close(a, t, ExitReason.KILL);
        }
        return Response.ok(Map.of("closedCount", open.size())).build();
    }

    @POST
    @Path("/trades/{tradeId}/close")
    @Transactional
    public Response closeOne(@PathParam("accountId") Long accountId, @PathParam("tradeId") Long tradeId) {
        ExchangeAccount a = accountRepo.findById(accountId);
        if (a == null) return Response.status(404).build();
        ExecutedTrade t = tradeRepo.findById(tradeId);
        if (t == null || !t.getExchangeAccountId().equals(accountId)) return Response.status(404).build();
        orderPlacer.close(a, t, ExitReason.MANUAL);
        return Response.ok(TradeView.of(t)).build();
    }

    private static BigDecimal bd(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }
}
```

- [ ] **Step 3: Implement `DevModeResource` (dev-only signal injection)**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/resource/DevModeResource.java`:

```java
package com.cryptoradar.execution.resource;

import com.cryptoradar.execution.lifecycle.OrderPlacer;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.resource.dto.InjectSignalRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * Dev-only endpoints for smoke testing. Behind a feature flag — in prod the
 * flag is false and POSTs return 403.
 */
@Path("/api/execution/test")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DevModeResource {

    private final boolean devEnabled;
    private final ExchangeAccountRepository accountRepo;
    private final OrderPlacer orderPlacer;

    public DevModeResource(@ConfigProperty(name = "execution.dev-mode.enabled") boolean devEnabled,
                           ExchangeAccountRepository accountRepo, OrderPlacer orderPlacer) {
        this.devEnabled = devEnabled;
        this.accountRepo = accountRepo;
        this.orderPlacer = orderPlacer;
    }

    @POST
    @Path("/inject-signal")
    public Response inject(InjectSignalRequest req) {
        if (!devEnabled) {
            return Response.status(403).entity(Map.of("error", "dev-mode disabled")).build();
        }
        for (ExchangeAccount a : accountRepo.listAll()) {
            orderPlacer.place(a, new OrderPlacer.PlacementRequest(
                    req.symbol(), req.direction(), req.strategy(),
                    "inject-" + System.currentTimeMillis(),
                    req.entryPrice(), req.stopPrice(), req.targetPrice()));
        }
        return Response.accepted().entity(Map.of("status", "dispatched")).build();
    }
}
```

- [ ] **Step 4: Commit**

```bash
cd services/trade-execution-service && mvn test -B
git add services/trade-execution-service/
git commit -m "feat(trade-execution): TradingResource + DevModeResource endpoints"
```

---

## Task 9: Bybit private WS client + server-side `/ws/execution`

**Goal:** Real-time WebSocket client consuming Bybit private topics (position / execution / order / wallet) and a server-side `@ServerEndpoint` broadcasting envelopes to frontend clients.

**Files:**
- Create: `.../client/bybit/BybitV5WsClient.java`
- Create: `.../ws/ExecutionBroadcaster.java`
- Create: `.../ws/ExecutionWebSocket.java`

- [ ] **Step 1: Create `ExecutionBroadcaster` (shared state between WS client and server)**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/ws/ExecutionBroadcaster.java`:

```java
package com.cryptoradar.execution.ws;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@ApplicationScoped
public class ExecutionBroadcaster {

    private final Set<jakarta.websocket.Session> sessions = new CopyOnWriteArraySet<>();

    public void register(jakarta.websocket.Session s) { sessions.add(s); }
    public void unregister(jakarta.websocket.Session s) { sessions.remove(s); }

    public void broadcast(String envelope) {
        for (jakarta.websocket.Session s : sessions) {
            if (!s.isOpen()) { sessions.remove(s); continue; }
            try {
                s.getAsyncRemote().sendText(envelope);
            } catch (Exception e) {
                sessions.remove(s);
            }
        }
    }
}
```

- [ ] **Step 2: Create `ExecutionWebSocket` (server endpoint)**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/ws/ExecutionWebSocket.java`:

```java
package com.cryptoradar.execution.ws;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/ws/execution")
@ApplicationScoped
public class ExecutionWebSocket {

    @Inject ExecutionBroadcaster broadcaster;

    @OnOpen
    public void onOpen(Session session) { broadcaster.register(session); }

    @OnClose
    public void onClose(Session session) { broadcaster.unregister(session); }
}
```

- [ ] **Step 3: Create `BybitV5WsClient`**

Write `services/trade-execution-service/src/main/java/com/cryptoradar/execution/client/bybit/BybitV5WsClient.java`:

```java
package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExecutionEvent;
import com.cryptoradar.execution.model.ExecutionEventType;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import com.cryptoradar.execution.security.CredentialCipher;
import com.cryptoradar.execution.ws.ExecutionBroadcaster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bybit private WebSocket client — one connection per ExchangeAccount.
 *
 * Subscribes to topics: position, execution, order, wallet. Each received
 * message updates local DB state AND pushes an envelope to the frontend
 * /ws/execution broadcaster.
 *
 * Reconnect policy: exponential backoff (1s base, cap 30s). On reconnect:
 * re-auth, re-subscribe, trigger reconciliation sweep via OrderReconciler
 * (already scheduled every 60s — reconnect just shortens the worst-case gap).
 */
@ApplicationScoped
public class BybitV5WsClient {

    private static final Logger LOG = Logger.getLogger(BybitV5WsClient.class);
    private static final long AUTH_EXPIRES_MS = 10_000;   // auth signed for 10s from now

    @Inject ObjectMapper mapper;
    @Inject CredentialCipher cipher;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExecutionEventRepository eventRepo;
    @Inject ExecutionBroadcaster broadcaster;

    private final HttpClient http = HttpClient.newHttpClient();

    void onStart(@Observes StartupEvent ev) {
        Executors.newSingleThreadScheduledExecutor().schedule(this::connectAll, 15, TimeUnit.SECONDS);
    }

    void connectAll() {
        accountRepo.listAll().forEach(this::connect);
    }

    void connect(ExchangeAccount account) {
        connectWithBackoff(account, 1000);
    }

    private void connectWithBackoff(ExchangeAccount account, long backoffMs) {
        String url = BybitV5Endpoints.wsPrivateFor(account.getEnvironment());
        AtomicBoolean opened = new AtomicBoolean(false);
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), new Listener(account, opened))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        LOG.warnf("WS connect failed for account %d: %s — retry in %dms",
                                account.getId(), err.getMessage(), backoffMs);
                        Executors.newSingleThreadScheduledExecutor().schedule(
                                () -> connectWithBackoff(account, Math.min(backoffMs * 2, 30_000)),
                                backoffMs, TimeUnit.MILLISECONDS);
                    }
                });
    }

    private class Listener implements WebSocket.Listener {
        private final ExchangeAccount account;
        private final AtomicBoolean opened;
        private final StringBuilder partial = new StringBuilder();

        Listener(ExchangeAccount account, AtomicBoolean opened) {
            this.account = account;
            this.opened = opened;
        }

        @Override
        public void onOpen(WebSocket ws) {
            opened.set(true);
            LOG.infof("Bybit WS connected for account %d", account.getId());
            try {
                authenticate(ws);
                subscribe(ws);
                eventRepo.persist(makeEvent(ExecutionEventType.WS_RECONNECTED, Map.of()));
            } catch (Exception e) {
                LOG.errorf(e, "WS open handler failed");
            }
            WebSocket.Listener.super.onOpen(ws);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                handleMessage(message);
            }
            return WebSocket.Listener.super.onText(ws, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            LOG.warnf("Bybit WS closed for account %d — status=%d reason=%s", account.getId(), statusCode, reason);
            eventRepo.persist(makeEvent(ExecutionEventType.WS_DISCONNECTED, Map.of("status", statusCode, "reason", reason)));
            Executors.newSingleThreadScheduledExecutor().schedule(
                    () -> connect(account), 2, TimeUnit.SECONDS);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOG.errorf(error, "Bybit WS error for account %d", account.getId());
        }

        private void authenticate(WebSocket ws) throws Exception {
            String apiKey = cipher.decrypt(account.getApiKeyEncrypted());
            String apiSecret = cipher.decrypt(account.getApiSecretEncrypted());
            long expires = System.currentTimeMillis() + AUTH_EXPIRES_MS;
            String toSign = "GET/realtime" + expires;
            String signed = BybitV5Signer.sign(apiSecret, "", "", "", toSign);
            String authMsg = mapper.writeValueAsString(Map.of(
                    "op", "auth",
                    "args", List.of(apiKey, expires, signed)
            ));
            ws.sendText(authMsg, true);
        }

        private void subscribe(WebSocket ws) throws Exception {
            String subMsg = mapper.writeValueAsString(Map.of(
                    "op", "subscribe",
                    "args", List.of("position", "execution", "order", "wallet")
            ));
            ws.sendText(subMsg, true);
        }

        @Transactional
        void handleMessage(String raw) {
            try {
                JsonNode root = mapper.readTree(raw);
                String topic = root.path("topic").asText(null);
                if (topic == null) return;
                JsonNode dataArr = root.path("data");
                if (!dataArr.isArray()) return;

                for (JsonNode item : dataArr) {
                    switch (topic) {
                        case "position" -> handlePosition(item);
                        case "execution" -> handleExecution(item);
                        case "order" -> handleOrder(item);
                        case "wallet" -> handleWallet(item);
                        default -> { /* ignore unknown topic */ }
                    }
                }
                broadcaster.broadcast(raw);   // pass through to frontend listeners
            } catch (Exception e) {
                LOG.warnf("WS message parse error: %s", e.getMessage());
            }
        }

        private void handlePosition(JsonNode pos) {
            String symbol = pos.path("symbol").asText();
            String side = pos.path("side").asText();
            String direction = "Buy".equals(side) ? "LONG" : "SHORT";
            String sizeStr = pos.path("size").asText("0");
            BigDecimal size = new BigDecimal(sizeStr);

            Optional<ExecutedTrade> match = tradeRepo.findOpenForAccount(account.getId()).stream()
                    .filter(t -> t.getSymbol().equals(symbol) && t.getDirection().equals(direction))
                    .findFirst();
            if (match.isEmpty()) return;
            ExecutedTrade t = match.get();

            if (size.signum() == 0) {
                t.setStatus(TradeStatus.CLOSED);
                t.setClosedAt(java.time.Instant.now());
                eventRepo.persist(makeEvent(ExecutionEventType.POSITION_CLOSED, Map.of("symbol", symbol)));
            } else {
                if (t.getEntryPrice() == null) t.setEntryPrice(parseBd(pos.path("avgPrice").asText(null)));
                t.setQty(size);
                if (t.getLeverage() == null) t.setLeverage(Integer.parseInt(pos.path("leverage").asText("1")));
            }
        }

        private void handleExecution(JsonNode exec) {
            String orderLinkId = exec.path("orderLinkId").asText(null);
            if (orderLinkId == null || orderLinkId.isEmpty()) return;
            Optional<ExecutedTrade> match = tradeRepo.findByOrderLinkId(orderLinkId);
            if (match.isEmpty()) return;
            ExecutedTrade t = match.get();
            if (t.getEntryPrice() == null) {
                t.setEntryPrice(parseBd(exec.path("execPrice").asText(null)));
            }
            eventRepo.persist(makeEvent(ExecutionEventType.ORDER_FILLED,
                    Map.of("orderLinkId", orderLinkId, "execPrice", exec.path("execPrice").asText(""))));
        }

        private void handleOrder(JsonNode order) {
            // Status transitions (new, filled, cancelled) — mostly observational,
            // the execution topic carries fills authoritatively.
        }

        private void handleWallet(JsonNode w) {
            // Wallet updates broadcast to frontend via the pass-through already. No local persistence.
        }

        private ExecutionEvent makeEvent(ExecutionEventType type, Map<String, Object> metadata) {
            ExecutionEvent ev = new ExecutionEvent();
            ev.setExchangeAccountId(account.getId());
            ev.setEventType(type);
            ev.setMetadata(metadata);
            return ev;
        }

        private BigDecimal parseBd(String s) {
            if (s == null || s.isEmpty()) return null;
            try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
        }
    }
}
```

**Key notes for the implementer:**
- Bybit V5 private WS auth: `{"op":"auth","args":[apiKey, expires, HmacSHA256(apiSecret, "GET/realtime" + expires)]}`. Expires is epoch-ms in the near future (10s ahead is fine).
- The topic list format differs across sources. Per V5 docs the auth-path signer expects `"GET/realtime" + expires` as the signed string. Verify against official Bybit V5 WS auth example at your implementation time — the hash shown above matches their spec as of early 2026.
- This implementation persists changes directly inside the WS handler callback. Quarkus CDI handles thread contexts; if you see `NullPointerException` on EntityManager inside the callback, consider dispatching to an internal `@ApplicationScoped` bean method marked `@Transactional` and `@ActivateRequestContext` (instead of inlining the transaction).

- [ ] **Step 4: Commit**

```bash
cd services/trade-execution-service && mvn compile -B   # verify compiles
docker compose build --no-cache trade-execution-service
docker compose up -d --force-recreate --no-deps trade-execution-service
docker compose logs trade-execution-service --tail=100
# Verify startup clean; WS connection attempts will show in logs (harmless if they fail — no accounts seeded yet)

git add services/trade-execution-service/
git commit -m "feat(trade-execution): Bybit private WS client + /ws/execution broadcaster"
```

---

## Task 10: api-gateway proxy for `/api/execution/**` + `/ws/execution`

**Files:**
- Modify: `services/api-gateway/src/main/java/com/cryptoradar/gateway/client/ServiceClient.java`
- Modify: `services/api-gateway/src/main/java/com/cryptoradar/gateway/resource/ProxyResource.java`
- Modify: `services/api-gateway/src/main/resources/application.properties` (if needed)

- [ ] **Step 1: Add `executionUrl` to `ServiceClient`**

Open `services/api-gateway/src/main/java/com/cryptoradar/gateway/client/ServiceClient.java` and locate the existing `@ConfigProperty`-bound URL fields (market-data, news, analytics, whale, derivatives, signal). Add the same pattern for execution:

```java
    @ConfigProperty(name = "execution.url", defaultValue = "http://localhost:8087")
    String executionUrl;

    public String getExecutionUrl() { return executionUrl; }
```

- [ ] **Step 2: Ensure compose env is wired**

In `docker-compose.yml`, the `api-gateway:` block should already set `EXECUTION_SERVICE_URL: http://trade-execution-service:8087` (added in Plan 2a Task 1). Confirm. In `services/api-gateway/src/main/resources/application.properties`, ensure (or add):
```properties
execution.url=${EXECUTION_SERVICE_URL:http://localhost:8087}
```

- [ ] **Step 3: Add proxy methods to `ProxyResource`**

At the end of `services/api-gateway/src/main/java/com/cryptoradar/gateway/resource/ProxyResource.java` (before the closing brace), add:

```java
    // =====================================================================
    // Execution Service proxies
    // =====================================================================

    @GET
    @Path("/execution/accounts")
    public Response listAccounts() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl() + "/api/execution/accounts"));
    }

    @POST
    @Path("/execution/accounts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createAccount(String body) {
        return proxyPost(serviceClient.getExecutionUrl() + "/api/execution/accounts", body);
    }

    @GET
    @Path("/execution/accounts/{id}")
    public Response getAccount(@PathParam("id") Long id) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id));
    }

    @jakarta.ws.rs.PATCH
    @Path("/execution/accounts/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response patchAccount(@PathParam("id") Long id, String body) {
        return proxyPatch(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id, body);
    }

    @DELETE
    @Path("/execution/accounts/{id}")
    public Response deleteAccount(@PathParam("id") Long id) {
        return proxyDelete(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id);
    }

    @GET
    @Path("/execution/accounts/{id}/wallet")
    public Response wallet(@PathParam("id") Long id) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id + "/wallet"));
    }

    @GET
    @Path("/execution/accounts/{id}/positions")
    public Response positions(@PathParam("id") Long id) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id + "/positions"));
    }

    @GET
    @Path("/execution/accounts/{id}/trades")
    public Response trades(@PathParam("id") Long id, @QueryParam("limit") @DefaultValue("50") int limit) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/trades?limit=" + limit));
    }

    @GET
    @Path("/execution/accounts/{id}/events")
    public Response events(@PathParam("id") Long id, @QueryParam("limit") @DefaultValue("100") int limit) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/events?limit=" + limit));
    }

    @GET
    @Path("/execution/accounts/{id}/trades/{tradeId}/why")
    public Response why(@PathParam("id") Long id, @PathParam("tradeId") Long tradeId) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/trades/" + tradeId + "/why"));
    }

    @POST
    @Path("/execution/accounts/{id}/kill-switch")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response killSwitch(@PathParam("id") Long id, String body) {
        return proxyPost(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id + "/kill-switch", body);
    }

    @POST
    @Path("/execution/accounts/{id}/close-all")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response closeAll(@PathParam("id") Long id, String body) {
        return proxyPost(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id + "/close-all", body);
    }

    @POST
    @Path("/execution/accounts/{id}/trades/{tradeId}/close")
    public Response closeTrade(@PathParam("id") Long id, @PathParam("tradeId") Long tradeId) {
        return proxyPost(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/trades/" + tradeId + "/close", "");
    }
```

If `proxyPatch` and `proxyDelete` helpers don't already exist on `ProxyResource`, add them following the same shape as `proxyPost`:

```java
    private Response proxyPatch(String url, String body) {
        try {
            String response = serviceClient.patchRaw(url, body);
            return proxyResponse(response);
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_GATEWAY).entity(e.getMessage()).build();
        }
    }

    private Response proxyDelete(String url) {
        try {
            String response = serviceClient.deleteRaw(url);
            return Response.status(Response.Status.NO_CONTENT).entity(response).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_GATEWAY).entity(e.getMessage()).build();
        }
    }
```

And add `patchRaw` + `deleteRaw` methods to `ServiceClient` mirroring `postRaw` pattern.

- [ ] **Step 4: WS proxy for `/ws/execution`**

Look at `services/api-gateway/src/main/java/com/cryptoradar/gateway/websocket/CryptoWebSocket.java` — the existing WS broadcaster accepts clients and echoes messages published via `WebSocketBroadcaster`. For `/ws/execution`, the simplest approach is to have a separate server endpoint that opens an upstream WS to `trade-execution-service:8087/ws/execution` and forwards messages both directions.

Create `services/api-gateway/src/main/java/com/cryptoradar/gateway/websocket/ExecutionWebSocketProxy.java`:

```java
package com.cryptoradar.gateway.websocket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionStage;

@ServerEndpoint("/ws/execution")
@ApplicationScoped
public class ExecutionWebSocketProxy {

    private static final Logger LOG = Logger.getLogger(ExecutionWebSocketProxy.class);

    @ConfigProperty(name = "execution.ws.url", defaultValue = "ws://trade-execution-service:8087/ws/execution")
    String upstreamUrl;

    private final ConcurrentHashMap<String, WebSocket> upstreams = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session) {
        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(upstreamUrl), new Listener(session))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        LOG.warnf("execution upstream WS connect failed: %s", err.getMessage());
                    } else {
                        upstreams.put(session.getId(), ws);
                    }
                });
    }

    @OnMessage
    public void onMessage(String msg, Session session) {
        WebSocket up = upstreams.get(session.getId());
        if (up != null) up.sendText(msg, true);
    }

    @OnClose
    public void onClose(Session session) {
        WebSocket up = upstreams.remove(session.getId());
        if (up != null) up.sendClose(WebSocket.NORMAL_CLOSURE, "client-closed");
    }

    @OnError
    public void onError(Session session, Throwable e) {
        LOG.warnf("gateway WS error: %s", e.getMessage());
    }

    private class Listener implements WebSocket.Listener {
        private final Session client;
        private final StringBuilder partial = new StringBuilder();
        Listener(Session client) { this.client = client; }
        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last && client.isOpen()) {
                client.getAsyncRemote().sendText(partial.toString());
                partial.setLength(0);
            }
            return WebSocket.Listener.super.onText(ws, data, last);
        }
        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            try { client.close(); } catch (Exception ignored) {}
            return null;
        }
    }
}
```

- [ ] **Step 5: Verify + commit**

```bash
docker compose build api-gateway trade-execution-service
docker compose up -d --no-deps api-gateway trade-execution-service
docker compose logs api-gateway --tail=30
curl -fsS http://localhost:31080/api/execution/accounts
# Expected: [] (empty array since no accounts configured yet) or whatever your current state has

git add services/api-gateway/
git commit -m "feat(api-gateway): proxy /api/execution and /ws/execution"
```

---

## Task 11: E2E verification + CLAUDE.md + README + k8s overlay

**Goal:** Manual smoke + doc updates + k8s manifests.

- [ ] **Step 1: Generate a real EXECUTION_MASTER_KEY**

```bash
python3 -c "import secrets,base64; print(base64.b64encode(secrets.token_bytes(32)).decode())"
```

Copy the output into `.env`:
```
EXECUTION_MASTER_KEY=<the-base64-32-byte-string>
```

If Python isn't available, use OpenSSL:
```bash
openssl rand -base64 32
```

- [ ] **Step 2: Rebuild + restart**

```bash
docker compose build --no-cache trade-execution-service
docker compose up -d --force-recreate --no-deps trade-execution-service
```

Wait 30s. Verify healthy:
```bash
docker compose ps trade-execution-service
curl -fsS http://localhost:31087/q/health/ready
```

- [ ] **Step 3: Create a Bybit DEMO account in Bybit UI, grant permissions, get API key + secret**

1. Go to https://testnet.bybit.com (or their demo trading UI — the endpoint is `api-demo.bybit.com`).
2. Create an API key with: Derivatives → Order + Position, Wallet → Account Info. **NOT Withdraw.**
3. Copy the key + secret into a safe place — you'll POST them to our service.

- [ ] **Step 3b: VERIFY `/v5/user/query-api` response shape matches `ApiKeyPermissionsV5` DTO**

> **Why:** The WireMock stubs in `AccountResourceTest` return a response shape we *assumed* from Bybit docs. If the real API returns a different structure (e.g., `permissions` as an array instead of a nested object, or field names in `PascalCase` vs `camelCase`), `ApiKeyPermissionsV5` deserialization will yield null fields and `PermissionValidator` will either reject every valid key or accept keys with Withdraw. Verify BEFORE proceeding with Step 4.
>
> The demo endpoint requires valid signing, so call it from inside the service via the `test` resource rather than hand-rolling an HMAC signature:

1. Start a temporary curl against the real Bybit demo using the key from Step 3. Bybit docs: https://bybit-exchange.github.io/docs/v5/user/apikey-info
2. Easiest: have the service log the raw JSON. Temporarily enable DEBUG on the REST client:
   ```bash
   curl -X POST http://localhost:31087/api/execution/test/debug-query-api \
     -H 'Content-Type: application/json' \
     -d '{"apiKey":"<demo-key>","apiSecret":"<demo-secret>","environment":"DEMO"}'
   ```
   The response echoes the raw upstream body.
3. Compare against `ApiKeyPermissionsV5` fields (`id`, `apiKey`, `readOnly`, `permissions.Derivatives`, `permissions.Withdraw`). Check:
   - `retCode` / `retMsg` at top level
   - `result.id`, `result.apiKey`, `result.readOnly` present
   - `result.permissions` is an object (not array) with category keys (`Derivatives`, `Wallet`, `Withdraw`, ...) each mapping to a list of allowed actions
4. **If shape differs:** update `ApiKeyPermissionsV5.java` fields / `@JsonProperty` annotations AND the stubs in `AccountResourceTest.stubValidKey` / `stubKeyWithWithdraw` to match real shape. Re-run `mvn test -Dtest=AccountResourceTest` — must pass. Also update `PermissionValidator` if the permission-category key names differ.

> **Fallback if `/api/execution/test/debug-query-api` is not shipped:** skip this step and inspect the service logs when you hit Step 4 — the Bybit response body is logged at WARN on `retCode != 0` via `BybitV5RestClient` (added by Plan 2a Task 5). If Step 4 returns 400 "Bybit key validation returned retCode=0 retMsg=OK" but nothing validates, it means deserialization silently produced nulls — that is the shape-mismatch signature.

- [ ] **Step 4: POST the account**

```bash
curl -X POST http://localhost:31080/api/execution/accounts \
  -H 'Content-Type: application/json' \
  -d '{
    "exchange": "BYBIT",
    "environment": "DEMO",
    "apiKey": "<your-demo-api-key>",
    "apiSecret": "<your-demo-api-secret>",
    "label": "Bybit Demo (Plan 2b smoke)"
  }'
```

Expected: HTTP 201 with account JSON. Note the `id` — use it for subsequent commands.

If 400 with "API key has withdraw permission": revoke the key in Bybit UI, create a new one WITHOUT withdraw, try again.
If 400 with "Bybit key validation failed": check Bybit UI that the key is active.

- [ ] **Step 5: Enable auto-trade + disarm kill switch**

```bash
curl -X PATCH http://localhost:31080/api/execution/accounts/1 \
  -H 'Content-Type: application/json' \
  -d '{"autoTradeEnabled": true, "killSwitch": false}'
```

- [ ] **Step 6: Inject a test signal (dev mode)**

```bash
curl -X POST http://localhost:31087/api/execution/test/inject-signal \
  -H 'Content-Type: application/json' \
  -d '{
    "symbol": "BTCUSDT",
    "direction": "LONG",
    "strategy": "smoke-test",
    "entryPrice": "50000",
    "stopPrice": "49500",
    "targetPrice": "52000"
  }'
```

Expected: HTTP 202 `{"status":"dispatched"}`.

Wait a few seconds, then:
```bash
curl -fsS http://localhost:31080/api/execution/accounts/1/positions
```
Expected: a JSON array with one position. Status may be `OPEN`.

Verify in Bybit UI that the order is there.

- [ ] **Step 7: Wait for TrailMirror tick or force by simulating price movement**

Observe the logs for TRAIL_UPDATED events:
```bash
docker compose logs trade-execution-service -f | grep TRAIL
```
If the market price moves favorably enough (MFE ≥ 1R), you'll see a trail update within ~60s.

- [ ] **Step 8: Manually close the position**

Assume `tradeId=1`:
```bash
curl -X POST http://localhost:31080/api/execution/accounts/1/trades/1/close
```

Wait 60s for the reconciler to pick up the close and populate realized P&L. Verify:
```bash
curl -fsS 'http://localhost:31080/api/execution/accounts/1/trades?limit=5'
```
Look for the closed row with `realizedPnlUsdt` populated.

- [ ] **Step 9: Toggle kill switch, inject another signal, verify blocked**

```bash
curl -X POST http://localhost:31080/api/execution/accounts/1/kill-switch \
  -H 'Content-Type: application/json' \
  -d '{"enabled": true}'

curl -X POST http://localhost:31087/api/execution/test/inject-signal \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"ETHUSDT","direction":"LONG","strategy":"smoke-kill","entryPrice":"3000","stopPrice":"2950","targetPrice":"3100"}'

# Check audit trail
curl -fsS 'http://localhost:31080/api/execution/accounts/1/events?limit=5'
# Expected: see SIGNAL_BLOCKED_KILL_SWITCH in the recent events
```

Re-enable:
```bash
curl -X POST http://localhost:31080/api/execution/accounts/1/kill-switch \
  -H 'Content-Type: application/json' \
  -d '{"enabled": false}'
```

- [ ] **Step 10: Update `CLAUDE.md`**

Edit `CLAUDE.md`. In the "Services + host ports" table, insert after `signal-service`:

```markdown
| trade-execution-service | 31087 | 8087 | Bybit V5 execution, trail-mirror, reconciler |
```

Under "Stack" → immediately after the existing bullet "- **Shared Java module**: `shared-trade-core/` ..." add:

```markdown
- **Trade execution**: `trade-execution-service/` — Quarkus 3.17 service that mirrors signals to real Bybit V5 USDT-perpetual orders. Depends on `shared-trade-core`. Encrypts API credentials (AES-GCM), validates permissions (rejects withdraw-enabled keys), maintains stops natively on Bybit. Tables: `exchange_accounts`, `executed_trades`, `execution_events` (see `db/init/execution-init.sql`).
```

- [ ] **Step 11: Update `README.md`**

Add a feature bullet in the Features section:

```markdown
- **Live trade execution** — optional per-exchange trading service (`trade-execution-service`) that mirrors signal-service STRONG_BUY/STRONG_SELL signals to real Bybit V5 perpetual orders with native TP/SL and a trailing-stop ladder matching the outcome-tracker's math. Encrypted credentials, withdraw-permission rejection, kill switch, daily-loss halt, and per-symbol flip-close policy.
```

Add a row to the service port table:

```markdown
| Trade Execution | **31087** | 8087 | Bybit V5 mirroring |
```

- [ ] **Step 12: k8s overlay**

Create `devops/base/trade-execution-service/` with three files. Follow the shape of `devops/base/signal-service/`:

`deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: trade-execution-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: trade-execution-service
  template:
    metadata:
      labels:
        app: trade-execution-service
    spec:
      containers:
        - name: trade-execution-service
          image: ghcr.io/stukans/projectr-x-trade-execution-service:latest
          ports:
            - containerPort: 8087
          envFrom:
            - configMapRef:
                name: trade-execution-service-config
            - secretRef:
                name: trade-execution-service-secrets
          readinessProbe:
            httpGet:
              path: /q/health/ready
              port: 8087
            initialDelaySeconds: 20
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /q/health/live
              port: 8087
            initialDelaySeconds: 30
            periodSeconds: 30
```

`service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: trade-execution-service
spec:
  selector:
    app: trade-execution-service
  ports:
    - port: 8087
      targetPort: 8087
```

`config.yaml`:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: trade-execution-service-config
data:
  QUARKUS_REDIS_HOSTS: "redis://redis:6379"
  MARKET_DATA_URL: "http://market-data-service:8081"
  EXECUTION_MAINNET_ENABLED: "false"
  EXECUTION_DEV_MODE_ENABLED: "false"
```

`kustomization.yaml`:
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - deployment.yaml
  - service.yaml
  - config.yaml
```

Add `trade-execution-service` to `devops/base/kustomization.yaml`:
```yaml
  - trade-execution-service
```

Add secret template to `devops/overlays/dev/secrets.example.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: trade-execution-service-secrets
type: Opaque
stringData:
  EXECUTION_MASTER_KEY: "REPLACE_WITH_BASE64_32_BYTE_AES256_KEY"
  QUARKUS_DATASOURCE_JDBC_URL: "jdbc:postgresql://marketdata-db-rw:5432/marketdata"
  QUARKUS_DATASOURCE_USERNAME: "cryptoradar"
  QUARKUS_DATASOURCE_PASSWORD: "REPLACE_WITH_DB_PASSWORD"
```

Update api-gateway config (in `devops/base/api-gateway/api-gateway-config.yaml` or equivalent) to include:
```yaml
  EXECUTION_SERVICE_URL: "http://trade-execution-service:8087"
```

- [ ] **Step 13: Final commit + push**

```bash
git add CLAUDE.md README.md devops/
git commit -m "docs(trade-execution): README + CLAUDE.md + k8s overlay"
git push origin master
```

---

## Self-review checklist (for the implementer)

Before declaring Plan 2b done:

- [ ] `cd services/trade-execution-service && mvn clean test` passes cleanly.
- [ ] `docker compose build --no-cache trade-execution-service` completes three stages.
- [ ] Container reports `Up (healthy)` with `/q/health/ready` returning `"status":"UP"`.
- [ ] `curl -fsS http://localhost:31080/api/execution/accounts` (via api-gateway proxy) returns a JSON array.
- [ ] A real Bybit demo account (POSTed with withdraw permission) was rejected with HTTP 400.
- [ ] A valid demo account POST returned 201 and inserted an encrypted row.
- [ ] A dev-mode `/test/inject-signal` resulted in: `executed_trades` row inserted, Bybit `/v5/order/create` fired, position visible in Bybit UI.
- [ ] Trail update fired at least once (check `execution_events` for `TRAIL_UPDATED`).
- [ ] Manual close via `POST /trades/{id}/close` resulted in CLOSED status with `realized_pnl_usdt` populated by the reconciler.
- [ ] Kill-switch ON blocked subsequent `/test/inject-signal` calls; audit trail shows `SIGNAL_BLOCKED_KILL_SWITCH`.
- [ ] No `AUTH_FAILURE` events during the smoke test.
- [ ] No `NoClassDefFoundError` / `ClassCastException` in logs.
- [ ] CLAUDE.md, README.md, and k8s overlay committed.
- [ ] `git log --oneline` shows a clean per-task commit history.

---

## What's next

Plan 3: frontend Portfolio extension. Consumes:
- `GET /api/execution/accounts` → list card
- `POST /api/execution/accounts` → setup modal
- `GET /api/execution/accounts/{id}/wallet|positions|trades|events|trades/{id}/why` → Portfolio Bybit card rendering
- `POST /api/execution/accounts/{id}/kill-switch|close-all|trades/{id}/close` → controls
- WebSocket `/ws/execution` → live updates

UI components to build: `ExchangeAccountsSection`, `ExchangeCard`, `EquitySummary`, `OpenPositionsTable`, `WhyModal`, `AddExchangeButton`, `ExchangeSetupModal`, settings side-panel — all detailed in the spec (Section 5).

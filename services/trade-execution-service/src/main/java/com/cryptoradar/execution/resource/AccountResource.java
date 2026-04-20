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
import java.util.Map;

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
            LOG.warn("Bybit queryApiKey failed for new account", e);
            return error(400, "Bybit key validation failed (see server logs)");
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
            LOG.warn("Failed to decrypt apiKey for exchange account (returning masked placeholder)", e);
            return null;
        }
    }

    private static Response error(int status, String message) {
        return Response.status(status)
                .entity(Map.of("error", message))
                .build();
    }
}

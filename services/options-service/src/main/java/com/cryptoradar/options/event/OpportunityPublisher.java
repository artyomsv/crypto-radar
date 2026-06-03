package com.cryptoradar.options.event;

import com.cryptoradar.options.model.OptionOpportunity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.client.RedisClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes scored opportunities to Redis channel {@code crypto:options:opportunities}.
 * Payload is intentionally machine-readable for a future auto-quote bot
 * (full leg symbols + premium + confidence + expiry epoch).
 */
@ApplicationScoped
public class OpportunityPublisher {

    private static final Logger LOG = Logger.getLogger(OpportunityPublisher.class);
    private static final String CHANNEL = "crypto:options:opportunities";

    @Inject RedisClient redis;
    @Inject ObjectMapper mapper;

    public void publish(OptionOpportunity opp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", opp.getId());
        payload.put("detectedAt", opp.getDetectedAt().toEpochMilli());
        payload.put("underlying", opp.getUnderlying());
        payload.put("expiry", opp.getExpiry().toString());
        payload.put("callSymbol", opp.getCallSymbol());
        payload.put("putSymbol", opp.getPutSymbol());
        payload.put("strikeCall", opp.getStrikeCall());
        payload.put("strikePut", opp.getStrikePut());
        payload.put("premium", opp.getStranglePremium());
        payload.put("impliedVolAtm", opp.getImpliedVolAtm());
        payload.put("realizedVol14d", opp.getRealizedVol14d());
        payload.put("ivRvSpread", opp.getIvRvSpread());
        payload.put("signalOverlay", opp.getSignalOverlay());
        payload.put("confidence", opp.getConfidence());

        try {
            String json = mapper.writeValueAsString(payload);
            redis.publish(CHANNEL, json);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "failed to serialize opportunity id=%s", opp.getId());
        } catch (Exception e) {
            LOG.warnf(e, "Redis publish failed for opportunity id=%s", opp.getId());
        }
    }
}

package com.cryptoradar.options.event;

import com.cryptoradar.options.model.OptionOpportunity;
import com.cryptoradar.options.model.OptionShortVolOpportunity;
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
    private static final String CHANNEL_SHORT_VOL = "crypto:options:short_vol_opportunities";

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

    /**
     * Mirror of {@link #publish(OptionOpportunity)} for the short-vol side.
     * Separate channel so subscribers (Telegram bridge, future auto-execution
     * bot) can opt in independently.
     */
    public void publishShortVol(OptionShortVolOpportunity opp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", opp.getId());
        payload.put("detectedAt", opp.getDetectedAt().toEpochMilli());
        payload.put("underlying", opp.getUnderlying());
        payload.put("expiry", opp.getExpiry().toString());
        payload.put("structureType", opp.getStructureType());
        payload.put("shortCallSymbol", opp.getShortCallSymbol());
        payload.put("shortPutSymbol", opp.getShortPutSymbol());
        payload.put("longCallSymbol", opp.getLongCallSymbol());
        payload.put("longPutSymbol", opp.getLongPutSymbol());
        payload.put("netCredit", opp.getNetCredit());
        payload.put("maxLossUsd", opp.getMaxLossUsd());
        payload.put("popPct", opp.getPopPct());
        payload.put("breakEvenLow", opp.getBreakEvenLow());
        payload.put("breakEvenHigh", opp.getBreakEvenHigh());
        payload.put("impliedVolAtm", opp.getImpliedVolAtm());
        payload.put("realizedVol14d", opp.getRealizedVol14d());
        payload.put("ivRvPremiumPct", opp.getIvRvPremiumPct());
        payload.put("confidence", opp.getConfidence());

        try {
            String json = mapper.writeValueAsString(payload);
            redis.publish(CHANNEL_SHORT_VOL, json);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "failed to serialize short-vol opportunity id=%s", opp.getId());
        } catch (Exception e) {
            LOG.warnf(e, "Redis publish failed for short-vol opportunity id=%s", opp.getId());
        }
    }
}

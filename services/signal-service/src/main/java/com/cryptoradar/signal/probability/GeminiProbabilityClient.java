package com.cryptoradar.signal.probability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * LLM reasoning overlay: asks Gemini for P(target before stop) plus a one-line
 * rationale, given a compact feature summary and the candidate's levels.
 *
 * <p>This is the "reasoning" half of the hybrid estimator — strong at fusing
 * news/context the numeric model can't see. Its output is logged separately from
 * the stats probability and is NOT trusted as calibrated until the calibration
 * report proves it. Entirely fail-open: any error (including no API key) returns
 * empty, and the candidate is still recorded with the stats probability alone.
 */
@ApplicationScoped
public class GeminiProbabilityClient {

    private static final Logger LOG = Logger.getLogger(GeminiProbabilityClient.class);
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "gemini.api.key", defaultValue = "")
    String apiKey;

    /** LLM verdict for a candidate. */
    public record LlmEstimate(double probability, String reasoning) {}

    public Optional<LlmEstimate> estimate(String prompt) {
        if (apiKey.isBlank()) return Optional.empty();
        try {
            String body = mapper.writeValueAsString(java.util.Map.of(
                    "contents", java.util.List.of(java.util.Map.of(
                            "parts", java.util.List.of(java.util.Map.of("text", prompt))))));
            HttpRequest req = HttpRequest.newBuilder(URI.create(GEMINI_URL + "?key=" + apiKey))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warnf("Gemini probability call non-200: %d", resp.statusCode());
                return Optional.empty();
            }
            return parse(resp.body());
        } catch (Exception e) {
            LOG.warnf("Gemini probability call failed: %s", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<LlmEstimate> parse(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        String text = root.path("candidates").path(0).path("content")
                .path("parts").path(0).path("text").asText("");
        if (text.isBlank()) return Optional.empty();
        // The model is asked to return a JSON object; extract the first {...} block.
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();
        JsonNode obj = mapper.readTree(text.substring(start, end + 1));
        if (!obj.has("probability")) return Optional.empty();
        double p = clamp(obj.path("probability").asDouble());
        String reasoning = obj.path("reasoning").asText("");
        return Optional.of(new LlmEstimate(p, reasoning));
    }

    private static double clamp(double p) {
        if (p < 0) return 0;
        if (p > 1) return 1;
        return p;
    }
}

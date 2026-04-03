package com.cryptoradar.news.client;

import com.cryptoradar.news.model.NewsArticle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

@ApplicationScoped
public class CryptoCompareClient {

    private static final Logger LOG = Logger.getLogger(CryptoCompareClient.class);

    private static final Map<String, String> NAME_TO_SYMBOL = Map.ofEntries(
            Map.entry("bitcoin", "BTC"), Map.entry("btc", "BTC"),
            Map.entry("ethereum", "ETH"), Map.entry("eth", "ETH"),
            Map.entry("binance", "BNB"), Map.entry("bnb", "BNB"),
            Map.entry("solana", "SOL"), Map.entry("sol", "SOL"),
            Map.entry("ripple", "XRP"), Map.entry("xrp", "XRP"),
            Map.entry("cardano", "ADA"), Map.entry("ada", "ADA"),
            Map.entry("avalanche", "AVAX"), Map.entry("avax", "AVAX"),
            Map.entry("polkadot", "DOT"), Map.entry("dot", "DOT"),
            Map.entry("chainlink", "LINK"), Map.entry("link", "LINK"),
            Map.entry("dogecoin", "DOGE"), Map.entry("doge", "DOGE")
    );

    private static final String[][] SAMPLE_NEWS = {
            {"Bitcoin Surges Past Key Resistance Level as Institutional Demand Grows", "BTC", "CoinDesk", "positive"},
            {"Ethereum Network Upgrade Scheduled for Next Month, Developers Confirm", "ETH", "The Block", "positive"},
            {"Solana DeFi TVL Hits New All-Time High Amid Growing Ecosystem", "SOL", "Decrypt", "positive"},
            {"XRP Gains Momentum After Favorable Court Ruling in SEC Case", "XRP", "CoinTelegraph", "positive"},
            {"Bitcoin Mining Difficulty Reaches Record High as Hash Rate Surges", "BTC", "Bitcoin Magazine", "neutral"},
            {"Cardano Launches New Smart Contract Capabilities in Latest Update", "ADA", "CryptoSlate", "positive"},
            {"Dogecoin Community Rallies Around New Utility Proposals", "DOGE", "NewsBTC", "neutral"},
            {"Avalanche Foundation Announces $50M DeFi Incentive Program", "AVAX", "The Defiant", "positive"},
            {"Chainlink Integrates With Major Banking Protocol for Cross-Chain Data", "LINK", "CoinDesk", "positive"},
            {"Polkadot Parachain Auctions See Record Participation From Projects", "DOT", "The Block", "positive"},
            {"BNB Chain Introduces Zero-Knowledge Proof Layer for Enhanced Privacy", "BNB", "CryptoSlate", "positive"},
            {"Crypto Market Faces Uncertainty as Fed Signals Rate Decision", "BTC,ETH", "Reuters", "negative"},
            {"Ethereum Gas Fees Drop to Multi-Year Lows After EIP Implementation", "ETH", "Decrypt", "positive"},
            {"Bitcoin ETF Inflows Hit $500M in Single Day, Setting Weekly Record", "BTC", "Bloomberg", "positive"},
            {"Solana Memecoin Frenzy Raises Concerns About Network Sustainability", "SOL", "The Block", "negative"},
            {"Global Crypto Adoption Index Shows 34% Year-Over-Year Growth", "BTC,ETH,SOL", "Chainalysis", "positive"},
            {"Major Exchange Reports 200% Surge in Altcoin Trading Volume", "BNB,AVAX,LINK", "CoinTelegraph", "positive"},
            {"Regulatory Framework for Crypto Assets Advances in EU Parliament", "BTC,ETH", "Financial Times", "neutral"},
            {"DeFi Protocol Hack Results in $8M Loss, Audit Gaps Identified", "ETH", "Rekt News", "negative"},
            {"Bitcoin Lightning Network Capacity Doubles in Six Months", "BTC", "Lightning Labs", "positive"},
    };

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();
    private boolean sampleNewsGenerated = false;

    @ConfigProperty(name = "cryptocompare.api.base-url")
    String baseUrl;

    @ConfigProperty(name = "cryptocompare.api.news-path")
    String newsPath;

    public CryptoCompareClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public List<NewsArticle> fetchLatestNews() {
        // Try real API first
        List<NewsArticle> articles = fetchFromApi();
        if (!articles.isEmpty()) {
            return articles;
        }

        // Fallback to sample data for prototype demo
        if (!sampleNewsGenerated) {
            LOG.info("CryptoCompare API unavailable (requires API key). Generating sample news for prototype.");
            sampleNewsGenerated = true;
            return generateSampleNews();
        }

        // On subsequent calls, generate a few new articles occasionally
        if (random.nextInt(3) == 0) {
            return generateRandomArticle();
        }

        return Collections.emptyList();
    }

    private List<NewsArticle> fetchFromApi() {
        try {
            String url = baseUrl + newsPath + "?lang=EN&sortOrder=latest";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response.body());
            if ("Error".equals(root.path("Response").asText())) {
                return Collections.emptyList();
            }

            JsonNode data = root.get("Data");
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }

            List<NewsArticle> articles = new ArrayList<>();
            for (JsonNode item : data) {
                articles.add(mapToArticle(item));
            }
            LOG.infof("Fetched %d news articles from CryptoCompare API", articles.size());
            return articles;
        } catch (Exception e) {
            LOG.debugf("CryptoCompare API call failed: %s", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<NewsArticle> generateSampleNews() {
        List<NewsArticle> articles = new ArrayList<>();
        Instant now = Instant.now();

        for (int i = 0; i < SAMPLE_NEWS.length; i++) {
            String[] data = SAMPLE_NEWS[i];
            NewsArticle article = new NewsArticle();
            article.externalId = "sample-" + (1000 + i);
            article.title = data[0];
            article.body = "Analysis and market insights regarding " + data[0].toLowerCase() + ". Market participants continue to watch developments closely.";
            article.url = "https://example.com/crypto-news/" + (1000 + i);
            article.source = data[2];
            article.publishedAt = now.minus(i * 45L + random.nextInt(30), ChronoUnit.MINUTES);
            article.fetchedAt = now;

            List<String> symbols = new ArrayList<>();
            for (String sym : data[1].split(",")) {
                symbols.add(sym.trim());
            }
            article.setRelatedSymbols(symbols);
            article.setCategories(List.of("Cryptocurrency", "Markets"));

            articles.add(article);
        }

        return articles;
    }

    private List<NewsArticle> generateRandomArticle() {
        String[] entry = SAMPLE_NEWS[random.nextInt(SAMPLE_NEWS.length)];
        NewsArticle article = new NewsArticle();
        article.externalId = "sample-" + System.currentTimeMillis();
        article.title = entry[0] + " — " + Instant.now().toString().substring(11, 16);
        article.body = "Latest market update and analysis.";
        article.url = "https://example.com/crypto-news/" + System.currentTimeMillis();
        article.source = entry[2];
        article.publishedAt = Instant.now();
        article.fetchedAt = Instant.now();
        article.setRelatedSymbols(List.of(entry[1].split(",")[0].trim()));
        article.setCategories(List.of("Markets"));
        return List.of(article);
    }

    private NewsArticle mapToArticle(JsonNode item) {
        NewsArticle article = new NewsArticle();
        article.externalId = String.valueOf(item.get("id").asLong());
        article.title = item.has("title") ? item.get("title").asText() : "";
        article.body = item.has("body") ? item.get("body").asText() : "";
        article.url = item.has("url") ? item.get("url").asText() : "";
        article.source = item.has("source") ? item.get("source").asText() : "";
        article.imageUrl = item.has("imageurl") ? item.get("imageurl").asText() : null;
        article.publishedAt = Instant.ofEpochSecond(item.get("published_on").asLong());
        article.fetchedAt = Instant.now();

        String categories = item.has("categories") ? item.get("categories").asText() : "";
        article.setCategories(parseCategories(categories));
        article.setRelatedSymbols(extractSymbols(categories, article.title));
        return article;
    }

    private List<String> parseCategories(String categoriesStr) {
        if (categoriesStr == null || categoriesStr.isBlank()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String cat : categoriesStr.split("\\|")) {
            String trimmed = cat.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private List<String> extractSymbols(String categories, String title) {
        List<String> symbols = new ArrayList<>();
        String searchText = (categories + " " + title).toLowerCase();
        for (Map.Entry<String, String> entry : NAME_TO_SYMBOL.entrySet()) {
            if (searchText.contains(entry.getKey()) && !symbols.contains(entry.getValue())) {
                symbols.add(entry.getValue());
            }
        }
        return symbols;
    }
}

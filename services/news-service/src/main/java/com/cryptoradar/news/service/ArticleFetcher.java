package com.cryptoradar.news.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and extracts readable article content from a news URL.
 * Server-side proxy to avoid CORS issues in the browser.
 */
@ApplicationScoped
public class ArticleFetcher {

    private static final Logger LOG = Logger.getLogger(ArticleFetcher.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern P_TAG = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern IMG_TAG = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.DOTALL);
    private static final Pattern OG_IMAGE = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.DOTALL);
    private static final Pattern OG_DESC = Pattern.compile("<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.DOTALL);
    private static final Pattern TITLE_TAG = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern H1_TAG = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL);
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    public Map<String, Object> fetchArticle(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "CryptoRadar/1.0 (News Reader)")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Map.of("error", "Source returned HTTP " + response.statusCode());
            }

            String html = response.body();
            return parseArticle(html, url);

        } catch (Exception e) {
            LOG.warnf("Failed to fetch article from %s: %s", url, e.getMessage());
            return Map.of("error", "Failed to fetch article: " + e.getMessage());
        }
    }

    private Map<String, Object> parseArticle(String html, String url) {
        String title = extractFirst(H1_TAG, html);
        if (title == null || title.isBlank()) {
            title = extractFirst(TITLE_TAG, html);
        }

        String ogImage = extractFirstAttr(OG_IMAGE, html);
        String ogDesc = extractFirstAttr(OG_DESC, html);

        // Remove script and style blocks before extracting paragraphs
        String cleaned = html.replaceAll("(?s)<script[^>]*>.*?</script>", "")
                .replaceAll("(?s)<style[^>]*>.*?</style>", "")
                .replaceAll("(?s)<noscript[^>]*>.*?</noscript>", "");

        // Extract paragraphs from <p> tags
        List<String> paragraphs = new ArrayList<>();
        Matcher pMatcher = P_TAG.matcher(cleaned);
        while (pMatcher.find()) {
            String text = cleanHtml(pMatcher.group(1));
            // Skip tiny fragments, CSS/JS artifacts, and navigation text
            if (text.length() > 40 && !text.contains("{") && !text.contains("function")
                    && !text.startsWith("var ") && !text.startsWith("window.")) {
                paragraphs.add(text);
            }
        }

        String fullText = String.join("\n\n", paragraphs);
        if (fullText.isBlank() && ogDesc != null) {
            fullText = cleanHtml(ogDesc);
        }

        // Extract images
        List<String> images = new ArrayList<>();
        if (ogImage != null) {
            images.add(ogImage);
        }
        Matcher imgMatcher = IMG_TAG.matcher(html);
        int imgCount = 0;
        while (imgMatcher.find() && imgCount < 5) {
            String src = imgMatcher.group(1);
            if (src.startsWith("http") && !src.contains("logo") && !src.contains("icon")
                    && !src.contains("avatar") && !src.contains("tracking")) {
                if (!images.contains(src)) {
                    images.add(src);
                    imgCount++;
                }
            }
        }

        return Map.of(
                "title", title != null ? cleanHtml(title) : "",
                "body", fullText,
                "imageUrl", images.isEmpty() ? "" : images.getFirst(),
                "images", images,
                "url", url,
                "paragraphCount", paragraphs.size()
        );
    }

    private String extractFirst(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String extractFirstAttr(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String cleanHtml(String text) {
        if (text == null) return "";
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        text = HTML_TAGS.matcher(text).replaceAll("");
        text = MULTI_SPACE.matcher(text).replaceAll(" ");
        return text.trim();
    }
}

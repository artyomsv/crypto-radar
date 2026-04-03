package com.cryptoradar.news.provider;

import com.cryptoradar.news.model.NewsArticle;
import org.jboss.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Abstract base for RSS feed news providers.
 * Handles XML parsing and converts RSS items to our NewsArticle model.
 * Subclasses only need to provide the feed URL and source name.
 */
public abstract class RssNewsProvider implements NewsProvider {

    private static final Logger LOG = Logger.getLogger(RssNewsProvider.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** RSS feed URL */
    protected abstract String getFeedUrl();

    @Override
    public List<NewsArticle> fetchLatestArticles() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getFeedUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "CryptoRadar/1.0")
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("[%s] RSS returned %d", getSourceName(), response.statusCode());
                return Collections.emptyList();
            }

            return parseRss(response.body());

        } catch (Exception e) {
            LOG.errorf(e, "[%s] RSS fetch failed", getSourceName());
            return Collections.emptyList();
        }
    }

    private List<NewsArticle> parseRss(String xml) {
        List<NewsArticle> articles = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList items = doc.getElementsByTagName("item");
            int limit = Math.min(items.getLength(), 15);

            for (int i = 0; i < limit; i++) {
                Element item = (Element) items.item(i);
                NewsArticle article = mapRssItem(item);
                if (article != null) {
                    articles.add(article);
                }
            }

            LOG.infof("[%s] Parsed %d articles from RSS", getSourceName(), articles.size());
        } catch (Exception e) {
            LOG.errorf(e, "[%s] RSS parse failed", getSourceName());
        }

        return articles;
    }

    private NewsArticle mapRssItem(Element item) {
        try {
            NewsArticle article = new NewsArticle();

            String title = getElementText(item, "title");
            String link = getElementText(item, "link");
            String description = getElementText(item, "description");
            String pubDate = getElementText(item, "pubDate");

            if (title == null || title.isBlank()) return null;

            article.externalId = getSourceName().toLowerCase().replaceAll("\\s+", "-")
                    + "-" + Math.abs(link != null ? link.hashCode() : title.hashCode());
            article.title = cleanHtml(title);
            article.url = link;
            article.source = getSourceName();
            article.fetchedAt = Instant.now();

            // Parse description, strip HTML
            if (description != null) {
                String clean = cleanHtml(description);
                article.body = clean.length() > 500 ? clean.substring(0, 500) : clean;
            }

            // Parse publication date
            article.publishedAt = parsePubDate(pubDate);

            // Extract image from media:content or enclosure
            String imageUrl = getMediaUrl(item);
            article.imageUrl = imageUrl;

            // Extract symbols from title + description
            String searchText = title + " " + (description != null ? description : "");
            article.setRelatedSymbols(SymbolExtractor.extract(searchText));
            article.setCategories(List.of("Crypto"));

            return article;
        } catch (Exception e) {
            LOG.debugf("Failed to parse RSS item: %s", e.getMessage());
            return null;
        }
    }

    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0 && nodes.item(0).getTextContent() != null) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    private String getMediaUrl(Element item) {
        // Try media:thumbnail
        NodeList media = item.getElementsByTagName("media:thumbnail");
        if (media.getLength() > 0) {
            Element el = (Element) media.item(0);
            String url = el.getAttribute("url");
            if (url != null && !url.isBlank()) return url;
        }
        // Try media:content
        media = item.getElementsByTagName("media:content");
        if (media.getLength() > 0) {
            Element el = (Element) media.item(0);
            String url = el.getAttribute("url");
            if (url != null && !url.isBlank()) return url;
        }
        // Try enclosure
        NodeList enclosures = item.getElementsByTagName("enclosure");
        if (enclosures.getLength() > 0) {
            Element el = (Element) enclosures.item(0);
            String url = el.getAttribute("url");
            if (url != null && !url.isBlank()) return url;
        }
        return null;
    }

    private Instant parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return Instant.now();
        try {
            return ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            try {
                return ZonedDateTime.parse(pubDate).toInstant();
            } catch (Exception e2) {
                return Instant.now();
            }
        }
    }

    private String cleanHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}

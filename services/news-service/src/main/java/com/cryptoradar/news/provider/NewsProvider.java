package com.cryptoradar.news.provider;

import com.cryptoradar.news.model.NewsArticle;

import java.util.List;

/**
 * Abstract contract for all news source integrations.
 * Each provider fetches from a different source and converts to our common NewsArticle model.
 */
public interface NewsProvider {

    /** Human-readable name of this news source */
    String getSourceName();

    /** Fetch latest articles from this source */
    List<NewsArticle> fetchLatestArticles();

    /** Whether this provider is currently enabled */
    default boolean isEnabled() {
        return true;
    }
}

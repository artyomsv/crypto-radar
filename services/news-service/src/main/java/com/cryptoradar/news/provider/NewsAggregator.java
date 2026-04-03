package com.cryptoradar.news.provider;

import com.cryptoradar.news.model.NewsArticle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates news from all registered NewsProvider implementations.
 * CDI automatically discovers all beans implementing NewsProvider.
 */
@ApplicationScoped
public class NewsAggregator {

    private static final Logger LOG = Logger.getLogger(NewsAggregator.class);

    @Inject
    Instance<NewsProvider> providers;

    /**
     * Fetches from all enabled providers and returns combined results.
     */
    public List<NewsArticle> fetchFromAllSources() {
        List<NewsArticle> allArticles = new ArrayList<>();

        for (NewsProvider provider : providers) {
            if (!provider.isEnabled()) {
                LOG.debugf("Skipping disabled provider: %s", provider.getSourceName());
                continue;
            }

            try {
                List<NewsArticle> articles = provider.fetchLatestArticles();
                allArticles.addAll(articles);
                LOG.infof("[%s] returned %d articles", provider.getSourceName(), articles.size());
            } catch (Exception e) {
                LOG.errorf(e, "[%s] failed to fetch articles", provider.getSourceName());
            }
        }

        LOG.infof("Total aggregated: %d articles from %d providers",
                allArticles.size(), providers.stream().count());
        return allArticles;
    }

    /**
     * Lists all registered provider names and their status.
     */
    public List<String> getProviderNames() {
        List<String> names = new ArrayList<>();
        for (NewsProvider provider : providers) {
            names.add(provider.getSourceName() + (provider.isEnabled() ? " [active]" : " [disabled]"));
        }
        return names;
    }
}

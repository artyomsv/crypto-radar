package com.cryptoradar.news.service;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NewsScheduler {

    private static final Logger LOG = Logger.getLogger(NewsScheduler.class);

    @Inject
    NewsService newsService;

    void onStartup(@Observes StartupEvent event) {
        LOG.info("News service started, performing initial news fetch");
        try {
            int count = newsService.fetchAndStoreNews();
            LOG.infof("Initial fetch stored %d new articles", count);
        } catch (Exception e) {
            LOG.error("Initial news fetch failed", e);
        }
    }

    @Scheduled(every = "{scheduler.news.interval}", identity = "news-fetch-job")
    void scheduledNewsFetch() {
        LOG.info("Scheduled news fetch triggered");
        try {
            int count = newsService.fetchAndStoreNews();
            LOG.infof("Scheduled fetch stored %d new articles", count);
        } catch (Exception e) {
            LOG.error("Scheduled news fetch failed", e);
        }
    }
}

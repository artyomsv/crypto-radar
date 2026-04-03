package com.cryptoradar.news.provider;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CoinTelegraphProvider extends RssNewsProvider {

    @Override
    public String getSourceName() {
        return "CoinTelegraph";
    }

    @Override
    protected String getFeedUrl() {
        return "https://cointelegraph.com/rss";
    }
}

package com.cryptoradar.news.provider;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DecryptProvider extends RssNewsProvider {

    @Override
    public String getSourceName() {
        return "Decrypt";
    }

    @Override
    protected String getFeedUrl() {
        return "https://decrypt.co/feed";
    }
}

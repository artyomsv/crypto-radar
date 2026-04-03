package com.cryptoradar.news.resource;

import com.cryptoradar.news.model.DailySentiment;
import com.cryptoradar.news.model.NewsArticle;
import com.cryptoradar.news.provider.NewsAggregator;
import com.cryptoradar.news.service.NewsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

@Path("/api/news")
@Produces(MediaType.APPLICATION_JSON)
public class NewsResource {

    @Inject
    NewsService newsService;

    @Inject
    NewsAggregator newsAggregator;

    @GET
    public List<NewsArticle> getNews(
            @QueryParam("symbol") String symbol,
            @QueryParam("limit") @DefaultValue("20") int limit) {
        if (symbol != null && !symbol.isBlank()) {
            return newsService.getNewsBySymbol(symbol, limit);
        }
        return newsService.getLatestNews(limit);
    }

    @GET
    @Path("/latest")
    public List<NewsArticle> getLatestNews(
            @QueryParam("limit") @DefaultValue("20") int limit) {
        return newsService.getLatestNews(limit);
    }

    @GET
    @Path("/sentiment/{symbol}")
    public Map<String, Object> getSentiment(@PathParam("symbol") String symbol) {
        return newsService.getSentimentBySymbol(symbol);
    }

    @GET
    @Path("/providers")
    public List<String> getProviders() {
        return newsAggregator.getProviderNames();
    }

    @GET
    @Path("/sentiment/{symbol}/history")
    public List<DailySentiment> getSentimentHistory(
            @PathParam("symbol") String symbol,
            @QueryParam("days") @DefaultValue("30") int days) {
        return newsService.getDailySentiment(symbol, days);
    }
}

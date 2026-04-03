package com.cryptoradar.news.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Table(name = "news_articles")
public class NewsArticle extends PanacheEntity {

    @Column(name = "external_id", unique = true, nullable = false)
    public String externalId;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String title;

    @Column(columnDefinition = "TEXT")
    public String body;

    @Column
    public String url;

    @Column
    public String source;

    @Column(name = "image_url")
    public String imageUrl;

    @Column(name = "published_at", nullable = false)
    public Instant publishedAt;

    @Column(name = "fetched_at", nullable = false)
    public Instant fetchedAt;

    @Column(name = "sentiment_score")
    public Double sentimentScore;

    @Column(name = "sentiment_label")
    public String sentimentLabel;

    @Column(name = "categories", columnDefinition = "TEXT")
    public String categoriesRaw;

    @Column(name = "related_symbols", columnDefinition = "TEXT")
    public String relatedSymbolsRaw;

    @Column(nullable = false)
    public String language = "en";

    @Transient
    public List<String> categories;

    @Transient
    public List<String> relatedSymbols;

    public void setCategories(List<String> list) {
        this.categories = list;
        this.categoriesRaw = list != null ? String.join(",", list) : null;
    }

    public List<String> getCategories() {
        if (categories != null) return categories;
        if (categoriesRaw == null || categoriesRaw.isBlank()) return Collections.emptyList();
        return Arrays.stream(categoriesRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    public void setRelatedSymbols(List<String> list) {
        this.relatedSymbols = list;
        this.relatedSymbolsRaw = list != null ? String.join(",", list) : null;
    }

    public List<String> getRelatedSymbols() {
        if (relatedSymbols != null) return relatedSymbols;
        if (relatedSymbolsRaw == null || relatedSymbolsRaw.isBlank()) return Collections.emptyList();
        return Arrays.stream(relatedSymbolsRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    public static List<NewsArticle> findBySymbol(String symbol, int limit) {
        return find("relatedSymbolsRaw LIKE ?1 ORDER BY publishedAt DESC",
                "%" + symbol + "%"
        ).page(0, limit).list();
    }

    public static List<NewsArticle> findLatest(int limit) {
        return find("ORDER BY publishedAt DESC").page(0, limit).list();
    }

    public static NewsArticle findByExternalId(String externalId) {
        return find("externalId", externalId).firstResult();
    }
}

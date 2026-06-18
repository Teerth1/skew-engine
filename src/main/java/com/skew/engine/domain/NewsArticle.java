package com.skew.engine.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persists a single news article fetched from a third-party provider (e.g. Alpha Vantage).
 *
 * <p>Fields match the Phase 1 spec in AGENTS.md.</p>
 */
@Entity
@Table(
    name = "news_articles",
    indexes = {
        @Index(name = "idx_news_symbol",      columnList = "symbol"),
        @Index(name = "idx_news_published_at", columnList = "publishedAt")
    }
)
public class NewsArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ticker this article is associated with (e.g. "SPY", "SPX"). */
    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 500)
    private String title;

    /** Publisher / media outlet name. */
    @Column(length = 200)
    private String source;

    @Column(length = 1000)
    private String url;

    @Column(nullable = false)
    private LocalDateTime publishedAt;

    /** Short summary from the provider (may be null if not supplied). */
    @Column(length = 2000)
    private String summary;

    /** Provider identifier string, e.g. "alpha_vantage". */
    @Column(length = 50)
    private String provider;

    /**
     * Raw sentiment score/label as returned by the provider
     * (e.g. "Bullish", "Bearish", "0.35").
     */
    @Column(length = 50)
    private String rawSentiment;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Required by JPA. */
    public NewsArticle() {}

    public NewsArticle(String symbol, String title, String source, String url,
                       LocalDateTime publishedAt, String summary,
                       String provider, String rawSentiment) {
        this.symbol       = symbol;
        this.title        = title;
        this.source       = source;
        this.url          = url;
        this.publishedAt  = publishedAt;
        this.summary      = summary;
        this.provider     = provider;
        this.rawSentiment = rawSentiment;
    }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public Long getId()                         { return id; }
    public void setId(Long id)                  { this.id = id; }

    public String getSymbol()                   { return symbol; }
    public void setSymbol(String symbol)        { this.symbol = symbol; }

    public String getTitle()                    { return title; }
    public void setTitle(String title)          { this.title = title; }

    public String getSource()                   { return source; }
    public void setSource(String source)        { this.source = source; }

    public String getUrl()                      { return url; }
    public void setUrl(String url)              { this.url = url; }

    public LocalDateTime getPublishedAt()              { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }

    public String getSummary()                  { return summary; }
    public void setSummary(String summary)      { this.summary = summary; }

    public String getProvider()                 { return provider; }
    public void setProvider(String provider)    { this.provider = provider; }

    public String getRawSentiment()                     { return rawSentiment; }
    public void setRawSentiment(String rawSentiment)    { this.rawSentiment = rawSentiment; }
}

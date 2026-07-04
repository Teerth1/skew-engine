package com.skew.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skew.engine.domain.NewsArticle;
import com.skew.engine.repository.NewsArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches ticker-specific and global macro news from the Alpha Vantage
 * News Sentiment API and persists new articles to the {@code news_articles} table.
 *
 * <h3>Design constraints (AGENTS.md Phase 1)</h3>
 * <ul>
 *   <li>Java-native HTTP only — no Python, no extra Maven dependencies.</li>
 *   <li>10-minute in-memory cache per symbol to avoid spamming the free API tier.</li>
 *   <li>Never throws into the Kafka ingestion path — returns empty list on any failure.</li>
 *   <li>Async-safe: cache is a {@link ConcurrentHashMap}; DB writes are idempotent (dedup by title+symbol).</li>
 * </ul>
 *
 * <h3>Alpha Vantage Free Tier</h3>
 * 25 requests/day on the free key. The 10-minute cache keeps usage well within limits
 * even with continuous tick ingestion.
 */
@Service
public class NewsService {

    private static final Logger logger = LoggerFactory.getLogger(NewsService.class);

    private static final String AV_BASE           = "https://www.alphavantage.co/query";
    private static final String PROVIDER           = "alpha_vantage";
    private static final int    CACHE_TTL_MINUTES  = 10;
    private static final int    MAX_ARTICLES       = 10;   // per fetch
    private static final DateTimeFormatter AV_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private final NewsArticleRepository repository;
    private final RestTemplate          restTemplate = new RestTemplate();
    private final ObjectMapper          objectMapper = new ObjectMapper();

    @Value("${alphavantage.api-key:}")
    private String apiKey;

    // -------------------------------------------------------------------------
    // Cache: symbol → (fetched-at, articles)
    // -------------------------------------------------------------------------

    private record CachedNews(LocalDateTime fetchedAt, List<NewsArticle> articles) {}
    private final Map<String, CachedNews> cache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public NewsService(NewsArticleRepository repository) {
        this.repository = repository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns recent news articles for a ticker symbol.
     * Serves from cache if still fresh; otherwise fetches from Alpha Vantage and refreshes.
     *
     * <p>This method is safe to call from the Kafka consumer thread — it will
     * never propagate an exception.</p>
     *
     * @param symbol ticker, e.g. "SPY"
     * @return up to {@value #MAX_ARTICLES} articles, newest first; empty list on any error
     */
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "newsService", fallbackMethod = "getRecentNewsFallback")
    @io.github.resilience4j.retry.annotation.Retry(name = "newsService", fallbackMethod = "getRecentNewsFallback")
    public List<NewsArticle> getRecentNews(String symbol) {
        CachedNews cached = cache.get(symbol);
        if (cached != null &&
            cached.fetchedAt().isAfter(LocalDateTime.now().minusMinutes(CACHE_TTL_MINUTES))) {
            logger.debug("NewsService: cache hit for {} ({} articles)", symbol, cached.articles().size());
            return cached.articles();
        }

        return refreshCache(symbol);
    }

    /**
     * Returns recent news articles for a ticker symbol as of a specific point-in-time.
     * Enforces strict point-in-time constraints to avoid look-ahead bias.
     */
    public List<NewsArticle> getRecentNewsAsOf(String symbol, LocalDateTime asOf) {
        if (asOf == null) {
            return getRecentNews(symbol);
        }
        return repository.findBySymbolAndPublishedAtLessThanEqualOrderByPublishedAtDesc(
                symbol, asOf, PageRequest.of(0, MAX_ARTICLES));
    }

    public List<NewsArticle> getRecentNewsFallback(String symbol, Throwable t) {
        logger.warn("Circuit breaker or retry exhausted for NewsService ({}). Reason: {}", symbol, t.getMessage());
        CachedNews cached = cache.get(symbol);
        return cached != null ? cached.articles() : List.of();
    }

    /**
     * Returns {@code true} if an Alpha Vantage API key is configured.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<NewsArticle> refreshCache(String symbol) {
        if (!isConfigured()) {
            logger.debug("NewsService: no Alpha Vantage key configured — returning empty news.");
            cache.put(symbol, new CachedNews(LocalDateTime.now(), List.of()));
            return List.of();
        }

        try {
            String url = UriComponentsBuilder.fromUriString(AV_BASE)
                    .queryParam("function", "NEWS_SENTIMENT")
                    .queryParam("tickers",  symbol)
                    .queryParam("limit",    MAX_ARTICLES)
                    .queryParam("apikey",   apiKey)
                    .toUriString();

            Map<?, ?> response = restTemplate.getForObject(url, Map.class);
            if (response == null || !response.containsKey("feed")) {
                logger.warn("NewsService: empty or unexpected response from Alpha Vantage for {}", symbol);
                cache.put(symbol, new CachedNews(LocalDateTime.now(), List.of()));
                return List.of();
            }

            List<Map<String, Object>> feed = (List<Map<String, Object>>) response.get("feed");
            List<NewsArticle> articles = new ArrayList<>();

            for (Map<String, Object> item : feed) {
                String title       = safeStr(item, "title");
                String url2        = safeStr(item, "url");
                String sourceName  = safeStr(item, "source");
                String summary     = safeStr(item, "summary");
                String timeStr     = safeStr(item, "time_published");
                String sentiment   = extractSentiment(item, symbol);

                LocalDateTime publishedAt = parseAvDate(timeStr);

                // Dedup: skip if this article already persisted
                if (repository.existsBySymbolAndTitle(symbol, title)) {
                    // Still include in the returned list (already in DB)
                    continue;
                }

                NewsArticle article = new NewsArticle(
                        symbol, title, sourceName, url2,
                        publishedAt, summary, PROVIDER, sentiment);
                repository.save(article);
                articles.add(article);
            }

            // Merge fresh persisted articles with existing DB articles for cache
            List<NewsArticle> allRecent = repository
                    .findBySymbolOrderByPublishedAtDesc(symbol, PageRequest.of(0, MAX_ARTICLES));

            cache.put(symbol, new CachedNews(LocalDateTime.now(), allRecent));
            logger.info("NewsService: refreshed {} — {} new articles persisted, {} in cache",
                    symbol, articles.size(), allRecent.size());
            return allRecent;

        } catch (Exception e) {
            logger.error("NewsService: fetch failed for {} — {}", symbol, e.getMessage());
            cache.put(symbol, new CachedNews(LocalDateTime.now(), List.of()));
            return List.of();
        }
    }

    /**
     * Extracts the overall sentiment label for the requested ticker from the
     * Alpha Vantage {@code ticker_sentiment} array, falling back to the
     * overall_sentiment_label at article level.
     */
    @SuppressWarnings("unchecked")
    private String extractSentiment(Map<String, Object> item, String symbol) {
        try {
            List<Map<String, Object>> tickers =
                    (List<Map<String, Object>>) item.get("ticker_sentiment");
            if (tickers != null) {
                for (Map<String, Object> t : tickers) {
                    if (symbol.equalsIgnoreCase(safeStr(t, "ticker"))) {
                        return safeStr(t, "ticker_sentiment_label");
                    }
                }
            }
        } catch (Exception ignored) {}
        return safeStr(item, "overall_sentiment_label");
    }

    private LocalDateTime parseAvDate(String s) {
        if (s == null || s.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(s, AV_DATE_FMT);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private String safeStr(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }
}

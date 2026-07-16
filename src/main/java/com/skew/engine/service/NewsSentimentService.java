package com.skew.engine.service;

import com.skew.engine.domain.NewsArticle;
import com.skew.engine.domain.DataCategory;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts a list of recent {@link NewsArticle} objects into a structured
 * {@link SentimentBand} by calling Gemini via Spring AI ChatClient.
 *
 * <p><strong>Design (AGENTS.md Phase 2):</strong></p>
 * <ul>
 *   <li>Grounded in real news inputs only — no hallucinated market data.</li>
 *   <li>Returns {@link SentimentBand#NEUTRAL} silently on any failure.</li>
 *   <li>Uses Spring AI's .entity() for structured mapping.</li>
 * </ul>
 */
@Service
public class NewsSentimentService {

    private static final Logger logger = LoggerFactory.getLogger(NewsSentimentService.class);

    private static final Set<DataCategory> ALLOWED_CATEGORIES = Set.of(
            DataCategory.NARRATIVE_EVENT,
            DataCategory.NARRATIVE_SENTIMENT
    );

    private final ChatClient chatClient;

    public NewsSentimentService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    record SentimentResponse(String sentiment) {}

    /**
     * Classifies the sentiment of a list of recent news articles into a {@link SentimentBand}.
     *
     * @param articles  Recent news articles (may be empty)
     * @param ticker    Ticker context, e.g. "SPY"
     * @return classified sentiment band; never throws
     */
    @CircuitBreaker(name = "newsService", fallbackMethod = "ruleBasedFallback")
    public SentimentBand classify(List<NewsArticle> articles, String ticker) {
        if (articles == null || articles.isEmpty()) {
            logger.debug("NewsSentimentService: no articles for {} — returning NEUTRAL", ticker);
            return SentimentBand.NEUTRAL;
        }

        // Provenance Gate: Filter out articles that do not belong to permitted categories
        List<NewsArticle> permittedArticles = articles.stream()
                .filter(a -> ALLOWED_CATEGORIES.contains(a.getDataCategory()))
                .toList();

        if (permittedArticles.isEmpty()) {
            logger.warn("NewsSentimentService: all articles for {} were rejected by the provenance gate — returning NEUTRAL", ticker);
            return SentimentBand.NEUTRAL;
        }

        try {
            return callGemini(permittedArticles, ticker);
        } catch (Exception e) {
            logger.warn("NewsSentimentService: Gemini call failed for {} — {}", ticker, e.getMessage());
            return doRuleBasedFallback(permittedArticles);
        }
    }

    private SentimentBand callGemini(List<NewsArticle> articles, String ticker) {
        String headlines = articles.stream()
                .limit(5)
                .map(a -> {
                    String sentiment = (a.getRawSentiment() != null && !a.getRawSentiment().isBlank()) ? a.getRawSentiment() : "N/A";
                    String summary = (a.getSummary() != null && !a.getSummary().isBlank()) ? " | Summary: " + a.getSummary() : "";
                    return "- [%s] %s (Provider Sentiment: %s)%s".formatted(
                            a.getSource() != null ? a.getSource() : "Unknown Source",
                            a.getTitle(),
                            sentiment,
                            summary);
                })
                .collect(Collectors.joining("\n"));

        String prompt = """
                You are an expert financial analyst specializing in macroeconomic trends and equity sentiment.
                Classify the overall market sentiment for %s based on the following recent news events and summaries.
                
                Guidelines:
                - Evaluate macroeconomic drivers (e.g., Federal Reserve rate expectations, inflation data, geopolitical risk, earnings reports).
                - Weigh both provider sentiment scores and narrative summaries.
                - Choose EXACTLY ONE of the following structured bands: BULLISH, MILDLY_BULLISH, NEUTRAL, MIXED, MILDLY_BEARISH, BEARISH.
                
                Recent News Events:
                %s
                """.formatted(ticker, headlines);

        SentimentResponse response = chatClient.prompt()
                .user(prompt)
                .call()
                .entity(SentimentResponse.class);

        if (response == null || response.sentiment() == null) {
            return SentimentBand.NEUTRAL;
        }

        try {
            return SentimentBand.valueOf(response.sentiment().toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("NewsSentimentService: unrecognised sentiment '{}' — defaulting NEUTRAL", response.sentiment());
            return SentimentBand.NEUTRAL;
        }
    }

    /**
     * Fallback method required by @CircuitBreaker. Matches the signature of classify() + Throwable.
     */
    public SentimentBand ruleBasedFallback(List<NewsArticle> articles, String ticker, Throwable t) {
        logger.warn("Circuit breaker open or Gemini failed for {}: using rule-based fallback. Reason: {}", ticker, t.getMessage());
        return doRuleBasedFallback(articles);
    }
    
    private SentimentBand doRuleBasedFallback(List<NewsArticle> articles) {
        long bullish = articles.stream()
                .filter(a -> a.getRawSentiment() != null &&
                             a.getRawSentiment().toLowerCase().contains("bullish"))
                .count();
        long bearish = articles.stream()
                .filter(a -> a.getRawSentiment() != null &&
                             a.getRawSentiment().toLowerCase().contains("bearish"))
                .count();

        if (bullish > bearish * 2) return SentimentBand.BULLISH;
        if (bullish > bearish)     return SentimentBand.MILDLY_BULLISH;
        if (bearish > bullish * 2) return SentimentBand.BEARISH;
        if (bearish > bullish)     return SentimentBand.MILDLY_BEARISH;
        if (bullish > 0 || bearish > 0) return SentimentBand.MIXED;
        return SentimentBand.NEUTRAL;
    }
}

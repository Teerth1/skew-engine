package com.skew.engine.service;

/**
 * Structured sentiment rating produced by {@link NewsSentimentService}
 * for a set of recent news articles.
 *
 * <p>Inspired by TradingAgents structured outputs (AGENTS.md Phase 2).</p>
 */
public enum SentimentBand {
    BULLISH,
    MILDLY_BULLISH,
    NEUTRAL,
    MIXED,
    MILDLY_BEARISH,
    BEARISH
}

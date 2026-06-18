package com.skew.engine.domain;

import java.time.Instant;

/**
 * Event published to the 'trade-signals' Kafka topic when the fast-path
 * OptionTickConsumer detects a skew divergence.
 *
 * This decouples raw tick ingestion from slow API/LLM evaluation.
 */
public record TradeSignalEvent(
        String ticker,
        String signalType,
        String optionType,
        double spotPrice,
        double putIv,
        double callIv,
        double spotReturn,
        double skewChange,
        Instant detectedAt
) {}

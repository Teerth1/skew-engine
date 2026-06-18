package com.skew.engine.service;

import java.time.LocalDateTime;

/**
 * Immutable handoff object between signal detection and order execution.
 *
 * <p>The {@link com.skew.engine.consumer.OptionTickConsumer} creates a
 * {@code TradeIntent} when a skew divergence signal fires. It is passed to
 * {@link AgentDecisionService} (advisory) and then to
 * {@link RiskManagerService} (deterministic gate) before
 * {@link OrderManagerService} (sole order executor) is ever called.</p>
 *
 * @param signalType   "BEARISH_DIVERGENCE" or "BULLISH_DIVERGENCE"
 * @param optionType   "put" or "call"
 * @param optionSymbol OCC-formatted symbol found by AlpacaService (or mock)
 * @param quantity     Number of contracts (always 1 in paper mode)
 * @param spotPrice    SPX spot at signal time
 * @param putIv        OTM put IV at signal time
 * @param callIv       OTM call IV at signal time
 * @param spotReturn   Spot return over lookback window
 * @param skewChange   Skew change over lookback window
 * @param createdAt    When this intent was created
 */
public record TradeIntent(
        String        signalType,
        String        optionType,
        String        optionSymbol,
        int           quantity,
        double        spotPrice,
        double        putIv,
        double        callIv,
        double        spotReturn,
        double        skewChange,
        LocalDateTime createdAt
) {
    /** Convenience constructor that defaults createdAt to now. */
    public TradeIntent(String signalType, String optionType, String optionSymbol,
                       int quantity, double spotPrice,
                       double putIv, double callIv,
                       double spotReturn, double skewChange) {
        this(signalType, optionType, optionSymbol, quantity,
             spotPrice, putIv, callIv, spotReturn, skewChange,
             LocalDateTime.now());
    }
}

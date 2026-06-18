package com.skew.engine.domain;

public record OptionTickEvent(
    String symbol,             // Unique identifier, e.g., SPX_260618P5000 (SPX 18-Jun-2026 Put at 5000 strike)
    String ticker,             // Underlying ticker, e.g., SPX
    String optionType,         // CALL or PUT
    double strikePrice,        // Strike price of the option contract
    double underlyingPrice,    // Current price of SPX (spot price)
    double impliedVolatility,  // Implied Volatility (IV) calculated from option price
    double last,               // Last traded price of the option
    double bid,                // Highest bid price
    double ask,                // Lowest ask price
    long timestamp             // Unix timestamp of the tick in milliseconds
) {
    // Compact constructor for validation and normalization
    public OptionTickEvent {
        if (ticker != null) {
            ticker = ticker.toUpperCase().trim();
        }
        if (optionType != null) {
            optionType = optionType.toUpperCase().trim();
        }

        // Guard clauses to protect mathematical integrity
        if (impliedVolatility < 0) {
            throw new IllegalArgumentException("Implied Volatility cannot be negative! Received: " + impliedVolatility);
        }
        if (underlyingPrice <= 0) {
            throw new IllegalArgumentException("Underlying price must be greater than zero. Received: " + underlyingPrice);
        }
    }
}
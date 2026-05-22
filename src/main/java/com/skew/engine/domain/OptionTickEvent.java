package com.skew.engine.domain;


public record OptionTickEvent(
    //Automatically private final is applied on the variables 
    String symbol,
    double last,
    double bid,
    double ask,
    long timestamp
) {
    
    public OptionsMarketData {
        if (ticker != null) {
            ticker = ticker.toUpperCase().trim();
        }
        if (optionType != null) {
            optionType = optionType.toUpperCase().trim();
        }

        // 2. Guard Clauses: Protect the mathematical integrity of the system
        if (impliedVolatility < 0) {
            throw new IllegalArgumentException("Implied Volatility cannot be negative! Received: " + impliedVolatility);
        }
        if (underlyingPrice <= 0) {
            throw new IllegalArgumentException("Underlying price must be greater than zero. Received: " + underlyingPrice);
        }
    }
}
package com.skew.engine.domain;

/**
 * Types of options trading strategies.
 * 
 * Each type determines how legs are auto-generated:
 * - SINGLE: One leg (long call/put)
 * - VERTICAL: Two legs (bull/bear spread)
 * - FLY/BUTTERFLY: Three legs (buy-sell-buy)
 * - STRADDLE: Call + Put at same strike
 * - IRON_CONDOR: Four legs (put spread + call spread)
 */
public enum StrategyType {
    SINGLE,
    VERTICAL,
    SPREAD, // Alias for VERTICAL
    FLY,
    BUTTERFLY, // Alias for FLY
    STRADDLE,
    IRON_CONDOR,
    IC, // Alias for IRON_CONDOR
    CUSTOM;

    /**
     * Parse strategy type from string, handling aliases.
     */
    public static StrategyType fromString(String type) {
        String upper = type.toUpperCase();

        // Handle aliases
        if (upper.equals("SPREAD"))
            return VERTICAL;
        if (upper.equals("BUTTERFLY"))
            return FLY;
        if (upper.equals("IC"))
            return IRON_CONDOR;

        try {
            return StrategyType.valueOf(upper);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown strategy type: " + type +
                            ". Use FLY, VERTICAL, STRADDLE, or IRON_CONDOR");
        }
    }
}

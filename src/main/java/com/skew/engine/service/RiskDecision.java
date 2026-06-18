package com.skew.engine.service;

/**
 * Immutable result from {@link RiskManagerService}.
 *
 * @param approved {@code true} if all risk checks passed and the trade may proceed.
 * @param reason   Human-readable explanation (first failing check, or "All checks passed").
 */
public record RiskDecision(boolean approved, String reason) {

    public static RiskDecision approve() {
        return new RiskDecision(true, "All risk checks passed");
    }

    public static RiskDecision reject(String reason) {
        return new RiskDecision(false, reason);
    }
}

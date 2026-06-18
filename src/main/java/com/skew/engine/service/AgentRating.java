package com.skew.engine.service;

/**
 * Structured trade-direction rating produced by {@link AgentDecisionService}.
 *
 * <p>Maps loosely to analyst-style ratings. The LLM returns this as part of
 * a structured JSON response. The rating is advisory — it never directly
 * triggers an order. The {@link com.skew.engine.service.RiskManagerService}
 * makes the final binary approve/reject decision.</p>
 */
public enum AgentRating {
    BUY,
    OVERWEIGHT,
    HOLD,
    UNDERWEIGHT,
    SELL
}

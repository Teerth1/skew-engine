package com.skew.engine.service;

/**
 * Immutable result from {@link AgentDecisionService}.
 *
 * <p>This is an advisory output only — it is passed to
 * {@link RiskManagerService} which makes the final deterministic
 * approve/reject decision. The agent never places orders directly.</p>
 *
 * @param rating     High-level trade direction rating.
 * @param sentiment  News sentiment context that influenced the decision.
 * @param confidence 0.0–1.0 confidence score in the rating.
 * @param rationale  1-3 sentence human-readable explanation.
 * @param riskNotes  Optional concerns or caveats the agent flagged.
 */
public record AgentDecision(
        AgentRating  rating,
        double       confidence,
        String       rationale,
        String       riskNotes
) {
    /** Convenience factory for a neutral/fallback decision. */
    public static AgentDecision neutral(String reason) {
        return new AgentDecision(AgentRating.HOLD, 0.0, reason, "");
    }
}

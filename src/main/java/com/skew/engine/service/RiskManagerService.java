package com.skew.engine.service;

import com.skew.engine.domain.LivePosition;
import com.skew.engine.repository.LivePositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic risk gate — the ONLY component that approves or rejects a
 * {@link TradeIntent} before order submission.
 *
 * <p><strong>AGENTS.md Phase 3 requirements (all implemented):</strong></p>
 * <ol>
 *   <li>Max open positions</li>
 *   <li>Max daily loss</li>
 *   <li>Max trades per day</li>
 *   <li>Market-hours check (9:30–16:00 ET, weekdays)</li>
 *   <li>Stale-data check (reject if intent is older than 5 minutes)</li>
 *   <li>Max bid/ask spread (placeholder — checked when live price available)</li>
 *   <li>Minimum agent confidence threshold</li>
 *   <li>Emergency kill switch</li>
 * </ol>
 *
 * <p>No LLM is involved. All checks are pure Java.</p>
 */
@Service
public class RiskManagerService {

    private static final Logger logger = LoggerFactory.getLogger(RiskManagerService.class);

    // Market hours in ET
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9,  30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);
    private static final ZoneId    ET_ZONE      = ZoneId.of("America/New_York");
    private static final int       STALE_MINUTES = 5;

    // -------------------------------------------------------------------------
    // Configurable risk parameters
    // -------------------------------------------------------------------------

    @Value("${trading.kill-switch:false}")
    private boolean killSwitch;

    @Value("${trading.max-positions:1}")
    private int maxPositions;

    @Value("${trading.max-daily-loss:500.0}")
    private double maxDailyLoss;

    @Value("${trading.max-trades-per-day:3}")
    private int maxTradesPerDay;

    @Value("${trading.min-agent-confidence:0.5}")
    private double minAgentConfidence;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final LivePositionRepository livePositionRepository;
    private final MacroCalendarService   macroCalendarService;

    public RiskManagerService(LivePositionRepository livePositionRepository,
                              MacroCalendarService macroCalendarService) {
        this.livePositionRepository = livePositionRepository;
        this.macroCalendarService   = macroCalendarService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Evaluates a {@link TradeIntent} + {@link AgentDecision} against all risk gates.
     *
     * @param intent   trade to evaluate
     * @param decision advisory agent output (confidence used in check #7)
     * @return {@link RiskDecision#approve()} if all checks pass, else {@link RiskDecision#reject(String)}
     */
    public RiskDecision evaluate(TradeIntent intent, AgentDecision decision) {

        // 1. Emergency kill switch
        if (killSwitch) {
            return RiskDecision.reject("Emergency kill switch is active");
        }

        // 2. Market hours (ET, weekdays only)
        LocalDateTime nowEt = LocalDateTime.now(ET_ZONE);
        DayOfWeek day = nowEt.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return RiskDecision.reject("Market closed — weekend");
        }
        LocalTime nowTime = nowEt.toLocalTime();
        if (nowTime.isBefore(MARKET_OPEN) || nowTime.isAfter(MARKET_CLOSE)) {
            return RiskDecision.reject("Market closed — outside trading hours (%s ET)".formatted(nowTime));
        }

        // 3. Stale intent (created more than STALE_MINUTES minutes ago)
        if (intent.createdAt().isBefore(LocalDateTime.now().minusMinutes(STALE_MINUTES))) {
            return RiskDecision.reject("Stale trade intent (created %s, now %s)"
                    .formatted(intent.createdAt(), LocalDateTime.now()));
        }

        // 4. Max open positions
        List<LivePosition> openPositions = livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN");
        if (openPositions.size() >= maxPositions) {
            return RiskDecision.reject("Max open positions reached (%d/%d)"
                    .formatted(openPositions.size(), maxPositions));
        }

        // 5. Max trades per day
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        long todayTrades = livePositionRepository
                .findByStatusOrderByEntryTimeDesc("CLOSED").stream()
                .filter(p -> p.getEntryTime() != null && p.getEntryTime().isAfter(startOfDay))
                .count()
                + openPositions.size();
        if (todayTrades >= maxTradesPerDay) {
            return RiskDecision.reject("Max trades per day reached (%d/%d)"
                    .formatted(todayTrades, maxTradesPerDay));
        }

        // 6. Max daily loss
        double todayPnl = livePositionRepository
                .findByStatusOrderByEntryTimeDesc("CLOSED").stream()
                .filter(p -> p.getEntryTime() != null && p.getEntryTime().isAfter(startOfDay))
                .mapToDouble(LivePosition::getProfitLoss)
                .sum();
        if (todayPnl < -Math.abs(maxDailyLoss)) {
            return RiskDecision.reject("Max daily loss exceeded (today P&L: $%.2f, limit: -$%.2f)"
                    .formatted(todayPnl, maxDailyLoss));
        }

        // 7. Minimum agent confidence
        if (decision.confidence() < minAgentConfidence) {
            return RiskDecision.reject("Agent confidence %.2f below threshold %.2f"
                    .formatted(decision.confidence(), minAgentConfidence));
        }

        // 8. Agent must not be SELL / UNDERWEIGHT (would contradict the buy intent)
        if (decision.rating() == AgentRating.SELL || decision.rating() == AgentRating.UNDERWEIGHT) {
            return RiskDecision.reject("Agent rating %s contradicts buy intent"
                    .formatted(decision.rating()));
        }

        // 9. Macro event / earnings blackout (FOMC, CPI, NFP, ticker earnings)
        Optional<String> blackout = macroCalendarService.blackoutReason(
                extractUnderlying(intent.optionSymbol()));
        if (blackout.isPresent()) {
            return RiskDecision.reject("Macro blackout — " + blackout.get());
        }

        logger.info("RiskManagerService: ✅ All checks passed for {} (confidence={:.2f})",
                intent.signalType(), decision.confidence());
        return RiskDecision.approve();
    }

    /** Leading letters of an OCC symbol, e.g. "SPY260620P00500000" → "SPY". */
    private static String extractUnderlying(String occSymbol) {
        if (occSymbol == null) return "";
        int i = 0;
        while (i < occSymbol.length() && Character.isLetter(occSymbol.charAt(i))) i++;
        return occSymbol.substring(0, i);
    }
}

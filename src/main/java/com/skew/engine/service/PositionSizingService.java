package com.skew.engine.service;

import com.skew.engine.domain.StrategyDecisionLog;
import com.skew.engine.repository.StrategyDecisionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dynamic position sizing via the (fractional) Kelly Criterion.
 *
 * <p>Replaces fixed 1-contract sizing with a size derived from:</p>
 * <ol>
 *   <li><strong>Historical edge</strong> — realized win rate and win/loss payoff
 *       ratio for this signal type, pulled from closed
 *       {@link StrategyDecisionLog} entries (the same records
 *       {@link TradeMemoryService} embeds into pgvector).</li>
 *   <li><strong>Agent conviction</strong> — the AI confidence score is blended
 *       with the historical win rate so a hesitant agent shrinks size and a
 *       confident agent (backed by data) grows it.</li>
 * </ol>
 *
 * <p>Kelly fraction: {@code f* = p - (1 - p) / b} where {@code p} is the
 * blended win probability and {@code b} the avg-win / avg-loss payoff ratio.
 * A half-Kelly multiplier is applied by default — full Kelly is famously
 * over-aggressive under parameter uncertainty — and the result is capped by a
 * hard per-trade risk budget so a bad estimate can never blow the daily loss
 * limit in one order.</p>
 */
@Service
public class PositionSizingService {

    private static final Logger logger = LoggerFactory.getLogger(PositionSizingService.class);

    /** Minimum closed-trade sample before historical stats are trusted at all. */
    private static final int MIN_SAMPLE_SIZE = 10;

    private final StrategyDecisionLogRepository decisionLogRepository;

    @Value("${trading.sizing.enabled:true}")
    private boolean enabled;

    /** Fraction of full Kelly to bet (0.5 = half-Kelly). */
    @Value("${trading.sizing.kelly-fraction:0.5}")
    private double kellyFraction;

    /** Notional bankroll allocated to this strategy, in USD. */
    @Value("${trading.sizing.bankroll:10000.0}")
    private double bankroll;

    /** Hard cap on premium at risk per trade, in USD. */
    @Value("${trading.sizing.max-risk-per-trade:1000.0}")
    private double maxRiskPerTrade;

    @Value("${trading.sizing.max-contracts:5}")
    private int maxContracts;

    public PositionSizingService(StrategyDecisionLogRepository decisionLogRepository) {
        this.decisionLogRepository = decisionLogRepository;
    }

    /**
     * Computes the contract quantity for an approved intent.
     *
     * @param intent          the trade intent (used for signal-type history lookup)
     * @param agentConfidence AI agent confidence in [0, 1]
     * @param entryPrice      option premium per contract-share (multiplied by 100 for notional)
     * @return contract count in [1, maxContracts]
     */
    public int computeQuantity(TradeIntent intent, double agentConfidence, double entryPrice) {
        if (!enabled || entryPrice <= 0.0) {
            return 1;
        }

        HistoricalEdge edge = historicalEdge(intent.signalType());

        // Blend: with a thin sample, lean on the agent; as history accumulates,
        // lean on realized statistics. Weight ramps 0 → 0.7 over 30 trades.
        double historyWeight = Math.min(edge.sampleSize() / 30.0, 1.0) * 0.7;
        double p = historyWeight * edge.winRate() + (1.0 - historyWeight) * agentConfidence;
        double b = edge.payoffRatio();

        double fullKelly = p - (1.0 - p) / b;
        if (fullKelly <= 0.0) {
            logger.info("PositionSizingService: Kelly non-positive (p={}, b={}) — minimum size 1",
                    String.format("%.2f", p), String.format("%.2f", b));
            return 1;
        }

        double betNotional = Math.min(fullKelly * kellyFraction * bankroll, maxRiskPerTrade);
        double perContractCost = entryPrice * 100.0;
        int quantity = (int) Math.floor(betNotional / perContractCost);
        quantity = Math.max(1, Math.min(quantity, maxContracts));

        logger.info("PositionSizingService: {} | winRate={} (n={}) payoff={} conf={} -> kelly={} bet=${} qty={}",
                intent.signalType(),
                String.format("%.2f", edge.winRate()), edge.sampleSize(),
                String.format("%.2f", b), String.format("%.2f", agentConfidence),
                String.format("%.3f", fullKelly), String.format("%.0f", betNotional), quantity);
        return quantity;
    }

    /**
     * Realized win rate and payoff ratio for a signal type from closed trades.
     * Falls back to a conservative neutral prior when the sample is thin.
     */
    private HistoricalEdge historicalEdge(String signalType) {
        List<StrategyDecisionLog> closed = decisionLogRepository
                .findBySignalTypeOrderByCreatedAtDesc(signalType).stream()
                .filter(l -> l.getClosedAt() != null && l.getRealizedPnl() != 0.0)
                .limit(100)
                .toList();

        if (closed.size() < MIN_SAMPLE_SIZE) {
            return new HistoricalEdge(0.5, 1.5, closed.size());
        }

        long wins = closed.stream().filter(l -> l.getRealizedPnl() > 0).count();
        double winRate = (double) wins / closed.size();

        double avgWin = closed.stream().filter(l -> l.getRealizedPnl() > 0)
                .mapToDouble(StrategyDecisionLog::getRealizedPnl).average().orElse(0.0);
        double avgLoss = Math.abs(closed.stream().filter(l -> l.getRealizedPnl() < 0)
                .mapToDouble(StrategyDecisionLog::getRealizedPnl).average().orElse(-1.0));

        double payoffRatio = avgLoss > 0 ? Math.max(avgWin / avgLoss, 0.1) : 1.5;
        return new HistoricalEdge(winRate, payoffRatio, closed.size());
    }

    private record HistoricalEdge(double winRate, double payoffRatio, int sampleSize) {}
}

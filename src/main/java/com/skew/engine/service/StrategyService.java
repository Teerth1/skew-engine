package com.skew.engine.service;

import java.util.List;
import org.springframework.stereotype.Service;
import com.skew.engine.domain.Strategy;
import com.skew.engine.domain.StrategyStatus;
import com.skew.engine.domain.Leg;
import com.skew.engine.repository.StrategyRepository;

/**
 * Service layer for managing options trading strategies.
 * 
 * Handles the business logic for:
 * - Opening new strategies (single-leg or multi-leg spreads)
 * - Retrieving user portfolios
 * - Closing/selling strategies
 * 
 * A Strategy can contain multiple Legs (e.g., vertical spread has 2 legs,
 * iron condor has 4 legs). Each Leg represents one options contract.
 * 
 * @see Strategy
 * @see Leg
 */
@Service
public class StrategyService {

    private final StrategyRepository strategyRepo;

    /**
     * Constructor with dependency injection.
     * Spring automatically injects the StrategyRepository.
     */
    public StrategyService(StrategyRepository strategyRepo) {
        this.strategyRepo = strategyRepo;
    }

    /**
     * Create and save a new strategy with its legs and net cost.
     * This is the primary method for recording a trade.
     * 
     * @param userId       The Discord user ID who made the trade.
     * @param strategyType e.g., "VERTICAL", "IRON_CONDOR".
     * @param ticker       The underlying symbol (e.g., SPX).
     * @param legs         List of option legs included in this trade.
     * @param netCost      Total debit (positive) or credit (negative/null) for the
     *                     trade.
     * @return The saved Strategy entity.
     */
    public Strategy openStrategy(String userId, String strategyType, String ticker, List<Leg> legs, Double netCost) {
        Strategy strategy = new Strategy(userId, strategyType, ticker, netCost);
        // Link parent strategy to each child leg
        for (Leg leg : legs) {
            leg.setStrategy(strategy);
        }
        strategy.getLegs().addAll(legs);
        return strategyRepo.save(strategy);
    }

    /**
     * Convenience method for opening a strategy without specifying net cost
     * (defaults to null/0).
     */
    public Strategy openStrategy(String userId, String strategyType, String ticker, List<Leg> legs) {
        return openStrategy(userId, strategyType, ticker, legs, null);
    }

    /**
     * Get all active (OPEN) strategies for a user.
     * Used to display the /portfolio view.
     */
    public List<Strategy> getOpenStrategies(String userId) {

        return strategyRepo.findByUserIdAndStatus(userId, StrategyStatus.OPEN);
    }

    /**
     * Close a strategy by ID.
     */
    public void closeStrategy(Long strategyId) {

        Strategy strategy = strategyRepo.findById(strategyId).get();
        strategy.setStatus(StrategyStatus.CLOSED);
        strategyRepo.save(strategy);
    }
}

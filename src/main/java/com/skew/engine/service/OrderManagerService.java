package com.skew.engine.service;

import com.skew.engine.domain.LivePosition;
import com.skew.engine.domain.Leg;
import com.skew.engine.domain.Strategy;
import com.skew.engine.repository.LivePositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The <strong>sole component</strong> allowed to call Alpaca order endpoints.
 *
 * <p>All buy/sell order submissions MUST flow through this class.
 * {@link OptionTickConsumer} (or any other component) must NOT call
 * {@link AlpacaService#submitBuyOrder} or {@link AlpacaService#submitSellOrder} directly.</p>
 *
 * <p><strong>AGENTS.md Phase 3:</strong></p>
 * <ul>
 *   <li>Validates a {@link RiskDecision#approved()} == true before any order.</li>
 *   <li>Logs every order attempt for audit purposes.</li>
 *   <li>Returns a populated {@link LivePosition} ready for persistence.</li>
 * </ul>
 */
@Service
public class OrderManagerService {

    private static final Logger logger = LoggerFactory.getLogger(OrderManagerService.class);

    private final AlpacaService          alpacaService;
    private final CommentaryService      commentaryService;
    private final LivePositionRepository livePositionRepository;

    public OrderManagerService(AlpacaService alpacaService,
                               CommentaryService commentaryService,
                               LivePositionRepository livePositionRepository) {
        this.alpacaService          = alpacaService;
        this.commentaryService      = commentaryService;
        this.livePositionRepository = livePositionRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Executes a {@link TradeIntent} that has been approved by {@link RiskManagerService}.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Verifies the risk decision is approved (double-gate).</li>
     *   <li>Submits a BUY order via {@link AlpacaService}.</li>
     *   <li>Fetches the entry option price.</li>
     *   <li>Generates Gemini commentary.</li>
     *   <li>Persists and returns a {@link LivePosition}.</li>
     * </ol>
     *
     * @param intent       The approved trade intent
     * @param decision     Advisory agent decision (for logging/commentary enrichment)
     * @param riskDecision Must have {@code approved == true} — method rejects otherwise
     * @return The persisted {@link LivePosition}, or {@code null} if rejected or failed
     */
    public LivePosition executeIntent(TradeIntent intent, AgentDecision decision,
                                      RiskDecision riskDecision) {
        if (!riskDecision.approved()) {
            logger.warn("OrderManagerService: attempted execution on rejected intent — {}",
                    riskDecision.reason());
            return null;
        }

        logger.info("OrderManagerService: executing {} {} intent | confidence={:.2f} | reason={}",
                intent.signalType(), intent.optionType(),
                decision.confidence(), riskDecision.reason());

        // (a) Submit BUY order on Alpaca
        String orderId = alpacaService.submitBuyOrder(intent.optionSymbol(), intent.quantity());

        // (b) Fetch entry price (falls back to BS approximation if price unavailable)
        double entryPrice = alpacaService.getLatestOptionPrice(intent.optionSymbol());
        if (entryPrice <= 0.0) {
            double entryIv = "put".equalsIgnoreCase(intent.optionType())
                    ? intent.putIv() : intent.callIv();
            entryPrice = entryIv * (intent.spotPrice() / 10.0)
                    * Math.sqrt(30.0 / 365.0 / (2 * Math.PI));
        }

        // (c) Generate commentary (includes agent rationale)
        String commentary = commentaryService.generateCommentary(
                intent.signalType(), intent.spotPrice(),
                intent.putIv(), intent.callIv(),
                intent.putIv() - intent.callIv(),
                intent.spotReturn(), intent.skewChange());

        // Append agent rationale if meaningful
        if (decision.rationale() != null && !decision.rationale().isBlank()) {
            commentary += " Agent: " + decision.rationale();
        }

        // (d) Build and persist LivePosition
        LivePosition pos = new LivePosition(
                LocalDateTime.now(),
                intent.optionSymbol(),
                intent.optionType().toUpperCase(),
                intent.quantity(),
                intent.spotPrice(),
                intent.signalType());

        pos.setAlpacaOrderId(orderId);
        pos.setCommentary(commentary);
        pos.recordEntryPrice(entryPrice);

        livePositionRepository.save(pos);

        logger.info("✅ OrderManagerService: position opened {} {} @ ${} | orderId={}",
                intent.signalType(), intent.optionSymbol(),
                String.format("%.2f", entryPrice), orderId);

        return pos;
    }

    /**
     * Closes an open position via Alpaca SELL order.
     * Called by the tick loop or manual close endpoint.
     *
     * @param pos  The open position to close
     * @param exitPrice  Latest option mid price (may be 0 → falls back to entry)
     */
    public void closePosition(LivePosition pos, double exitPrice) {
        logger.info("OrderManagerService: closing position {} ({})", pos.getId(), pos.getOptionSymbol());
        alpacaService.submitSellOrder(pos.getOptionSymbol(), pos.getQuantity());
        double finalPrice = exitPrice > 0.0 ? exitPrice : pos.getEntryOptionPrice();
        pos.close(LocalDateTime.now(), finalPrice);
        livePositionRepository.save(pos);
        logger.info("🔒 OrderManagerService: closed {} P&L=${}", pos.getOptionSymbol(),
                String.format("%.2f", pos.getProfitLoss()));
    }

    /**
     * Executes multi-leg spread strategy legs on Alpaca paper trading.
     */
    public void executeSpreadStrategy(Strategy strategy) {
        if (!alpacaService.isConfigured()) {
            logger.info("OrderManagerService: Alpaca paper trading not configured, skipping broker execution for strategy #{}", strategy.getId());
            return;
        }
        logger.info("OrderManagerService: Executing multi-leg spread strategy #{} ({}) across {} legs", strategy.getId(), strategy.getStrategy(), strategy.getLegs().size());
        for (Leg leg : strategy.getLegs()) {
            String symbol = resolveOrBuildContractSymbol(strategy.getTicker(), leg.getOptionType(), leg.getStrikePrice(), leg.getExpiration());
            int qty = Math.abs(leg.getQuantity());
            if (leg.getQuantity() > 0) {
                String orderId = alpacaService.submitBuyOrder(symbol, qty);
                logger.info("Spread leg BUY {} x{}: orderId={}", symbol, qty, orderId);
            } else if (leg.getQuantity() < 0) {
                String orderId = alpacaService.submitSellOrder(symbol, qty);
                logger.info("Spread leg SELL {} x{}: orderId={}", symbol, qty, orderId);
            }
        }
    }

    /**
     * Closes multi-leg spread strategy legs on Alpaca paper trading and returns total realized P&L.
     */
    public double closeSpreadStrategy(Strategy strategy) {
        logger.info("OrderManagerService: Closing multi-leg spread strategy #{} ({})", strategy.getId(), strategy.getStrategy());
        double totalRealizedPnl = 0.0;
        for (Leg leg : strategy.getLegs()) {
            String symbol = resolveOrBuildContractSymbol(strategy.getTicker(), leg.getOptionType(), leg.getStrikePrice(), leg.getExpiration());
            int qty = Math.abs(leg.getQuantity());
            if (alpacaService.isConfigured()) {
                if (leg.getQuantity() > 0) {
                    alpacaService.submitSellOrder(symbol, qty); // sell to close long leg
                } else if (leg.getQuantity() < 0) {
                    alpacaService.submitBuyOrder(symbol, qty);  // buy to close short leg
                }
            }
            double exitPrice = alpacaService.getLatestOptionPrice(symbol);
            if (exitPrice <= 0.0) {
                exitPrice = leg.getEntryPrice() != null ? leg.getEntryPrice() : 0.0;
            }
            double legEntry = leg.getEntryPrice() != null ? leg.getEntryPrice() : 0.0;
            // P&L per leg: (exit - entry) * qty * 100
            double legPnl = (exitPrice - legEntry) * leg.getQuantity() * 100.0;
            totalRealizedPnl += legPnl;
        }
        return totalRealizedPnl;
    }

    private String resolveOrBuildContractSymbol(String ticker, String optionType, double strike, LocalDate expiration) {
        String baseTicker = (ticker != null && ticker.startsWith("SPX")) ? "SPY" : (ticker != null ? ticker : "SPY");
        double adjustedStrike = (ticker != null && ticker.startsWith("SPX")) ? Math.round(strike / 10.0) : strike;
        LocalDate exp = expiration != null ? expiration : LocalDate.now().plusDays(30);
        String expStr = exp.format(DateTimeFormatter.ofPattern("yyMMdd"));
        String typeChar = "put".equalsIgnoreCase(optionType) ? "P" : "C";
        return "%-6s%s%s%08d".formatted(baseTicker, expStr, typeChar, (int) Math.round(adjustedStrike * 1000)).replaceAll("\\s+", "");
    }
}

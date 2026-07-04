package com.skew.engine.service;

import com.skew.engine.agent.TradingAgentTools;
import com.skew.engine.domain.LivePosition;
import com.skew.engine.domain.NewsArticle;
import com.skew.engine.domain.StrategyDecisionLog;
import com.skew.engine.repository.LivePositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Produces an advisory {@link AgentDecision} by combining market data and 
 * tool invocations via Spring AI ChatClient or external TradingAgents service.
 *
 * <p><strong>AGENTS.md constraint:</strong> The LLM output may advise, explain, or score
 * a trade, but must NOT directly place orders. The returned {@link AgentDecision} is
 * passed to {@link RiskManagerService} which makes the final binary decision.</p>
 */
@Service
public class AgentDecisionService {

    private static final Logger logger = LoggerFactory.getLogger(AgentDecisionService.class);

    private final ChatClient chatClient;
    private final LivePositionRepository livePositionRepository;
    private final TradeMemoryService tradeMemoryService;
    private final TradingAgentsClientService tradingAgentsClientService;
    private final NewsService newsService;

    public AgentDecisionService(ChatClient.Builder chatClientBuilder, 
                                TradingAgentTools tradingAgentTools,
                                LivePositionRepository livePositionRepository,
                                TradeMemoryService tradeMemoryService,
                                TradingAgentsClientService tradingAgentsClientService,
                                NewsService newsService) {
        this.chatClient = chatClientBuilder
                .defaultTools(tradingAgentTools)
                .build();
        this.livePositionRepository = livePositionRepository;
        this.tradeMemoryService = tradeMemoryService;
        this.tradingAgentsClientService = tradingAgentsClientService;
        this.newsService = newsService;
    }

    record AgentDecisionResponse(String rating, double confidence, String rationale, String riskNotes) {}

    /**
     * Produces an advisory {@link AgentDecision} for a given trade intent.
     * The agent queries external TradingAgents first if enabled, or falls back to Gemini / heuristics.
     *
     * @param intent The trade being evaluated
     * @return advisory decision — never throws; falls back to HOLD on error
     */
    public AgentDecision decide(TradeIntent intent) {
        try {
            if (tradingAgentsClientService.isEnabled()) {
                String ticker = intent.optionSymbol() != null && intent.optionSymbol().length() >= 3 
                        ? intent.optionSymbol().substring(0, 3) : "SPY";
                List<NewsArticle> recentNews = newsService.getRecentNewsAsOf(ticker, intent.createdAt());
                int openPositions = livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN").size();
                Optional<AgentDecision> externalDecision = tradingAgentsClientService.requestAnalysis(intent, recentNews, openPositions);
                if (externalDecision.isPresent()) {
                    return externalDecision.get();
                }
            }
            return callGemini(intent);
        } catch (Exception e) {
            logger.warn("AgentDecisionService: Agent/Gemini call failed — {}", e.getMessage());
            return ruleBasedDecision(intent);
        }
    }

    private AgentDecision callGemini(TradeIntent intent) {
        List<StrategyDecisionLog> similarTrades = tradeMemoryService.findSimilarTrades(intent);
        String prompt = buildPrompt(intent, similarTrades);

        AgentDecisionResponse response = chatClient.prompt()
                .user(prompt)
                .call()
                .entity(AgentDecisionResponse.class);

        if (response == null || response.rating() == null) {
            return AgentDecision.neutral("Empty AgentDecisionResponse");
        }

        AgentRating rating;
        try {
            rating = AgentRating.valueOf(response.rating().toUpperCase());
        } catch (Exception e) {
            rating = AgentRating.HOLD;
        }

        double confidence = Math.max(0.0, Math.min(1.0, response.confidence()));

        logger.info("AgentDecisionService: {} → rating={} confidence={:.2f}", intent.signalType(), rating, confidence);
        return new AgentDecision(rating, confidence, response.rationale(), response.riskNotes());
    }

    private String buildPrompt(TradeIntent intent, List<StrategyDecisionLog> similarTrades) {
        String ticker = intent.optionSymbol() != null && intent.optionSymbol().length() >= 3 
                ? intent.optionSymbol().substring(0, 3) 
                : "SPY";

        StringBuilder history = new StringBuilder();
        if (!similarTrades.isEmpty()) {
            history.append("\n## Historical Context (Similar Past Trades)\n");
            for (int i = 0; i < similarTrades.size(); i++) {
                StrategyDecisionLog log = similarTrades.get(i);
                history.append("%d. %s".formatted(i + 1, log.getTradeMemorySummary()));
            }
        }

        return """
                You are an options market analyst advisory agent. 
                Evaluate the following trading signal.
                You have tools available to fetch recent news (to gauge sentiment) and check open paper positions.
                Decide if you need these tools, analyze the data, and return a JSON object representing your decision.
                
                Valid ratings: BUY, OVERWEIGHT, HOLD, UNDERWEIGHT, SELL
                
                Signal: %s
                Ticker: %s
                Option type to trade: %s
                SPX Spot: %.0f
                Spot return over lookback: %.2f%%
                Skew change over lookback: %.2f%%
                Put IV: %.1f%%  |  Call IV: %.1f%%
                %s
                """.formatted(
                intent.signalType(),
                ticker,
                intent.optionType().toUpperCase(),
                intent.spotPrice(),
                intent.spotReturn() * 100,
                intent.skewChange() * 100,
                intent.putIv() * 100,
                intent.callIv() * 100,
                history.toString()
        );
    }

    /**
     * Fallback rule-based decision if AI fails.
     */
    private AgentDecision ruleBasedDecision(TradeIntent intent) {
        List<LivePosition> openPositions = livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN");
        AgentRating rating = openPositions.isEmpty() ? AgentRating.BUY : AgentRating.HOLD;
        double confidence  = 0.55;
        String rationale   = "Fallback rule-based decision due to AI failure.";
        return new AgentDecision(rating, confidence, rationale, "");
    }
}

package com.skew.engine.service;

import com.skew.engine.domain.NewsArticle;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service client for Phase 5 optional TradingAgents (Python/LangGraph) integration.
 *
 * <p><strong>AGENTS.md Phase 5:</strong></p>
 * <ul>
 *   <li>Calls external Python service via narrow API endpoint.</li>
 *   <li>Sends market state, skew signal, recent news, and current positions.</li>
 *   <li>Receives structured rating, confidence, rationale, and risk notes.</li>
 *   <li>If disabled or unreachable, returns {@link Optional#empty()} to trigger seamless fallback.</li>
 * </ul>
 */
@Service
public class TradingAgentsClientService {

    private static final Logger logger = LoggerFactory.getLogger(TradingAgentsClientService.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${trading.agents.enabled:false}")
    private boolean enabled;

    @Value("${trading.agents.service-url:http://localhost:8000/analyze}")
    private String serviceUrl;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        logger.info("TradingAgentsClientService enabled set to: {}", enabled);
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    @CircuitBreaker(name = "tradingAgentsClientService", fallbackMethod = "requestAnalysisFallback")
    @Retry(name = "tradingAgentsClientService", fallbackMethod = "requestAnalysisFallback")
    @SuppressWarnings("unchecked")
    public Optional<AgentDecision> requestAnalysis(TradeIntent intent, List<NewsArticle> recentNews, int openPositions) {
        if (!enabled) {
            return Optional.empty();
        }

        logger.info("TradingAgentsClientService: Requesting multi-agent analysis from {}", serviceUrl);

        Map<String, Object> payload = new HashMap<>();
        
        Map<String, Object> marketState = new HashMap<>();
        marketState.put("ticker", intent.optionSymbol() != null && intent.optionSymbol().length() >= 3 
                ? intent.optionSymbol().substring(0, 3) : "SPY");
        marketState.put("spotPrice", intent.spotPrice());
        marketState.put("putIv", intent.putIv());
        marketState.put("callIv", intent.callIv());
        marketState.put("skew", intent.putIv() - intent.callIv());
        payload.put("marketState", marketState);

        Map<String, Object> skewSignal = new HashMap<>();
        skewSignal.put("signalType", intent.signalType());
        skewSignal.put("optionType", intent.optionType());
        skewSignal.put("spotReturn", intent.spotReturn());
        skewSignal.put("skewChange", intent.skewChange());
        payload.put("skewSignal", skewSignal);

        List<String> headlines = recentNews.stream()
                .limit(5)
                .map(NewsArticle::getTitle)
                .collect(Collectors.toList());
        payload.put("recentNews", headlines);
        payload.put("currentPositions", openPositions);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    serviceUrl, new HttpEntity<>(payload, headers), Map.class);

            if (response.getBody() == null) {
                return Optional.empty();
            }

            Map<String, Object> resBody = response.getBody();
            String ratingStr = String.valueOf(resBody.getOrDefault("rating", "HOLD"));
            AgentRating rating;
            try {
                rating = AgentRating.valueOf(ratingStr.toUpperCase());
            } catch (Exception e) {
                rating = AgentRating.HOLD;
            }

            double confidence = 0.5;
            Object confObj = resBody.get("confidence");
            if (confObj instanceof Number num) {
                confidence = Math.max(0.0, Math.min(1.0, num.doubleValue()));
            }

            String rationale = String.valueOf(resBody.getOrDefault("rationale", "External TradingAgents analysis"));
            String riskNotes = String.valueOf(resBody.getOrDefault("riskNotes", ""));

            AgentDecision decision = new AgentDecision(rating, confidence, "[LangGraph Agent] " + rationale, riskNotes);
            logger.info("✅ TradingAgentsClientService: Analysis received -> rating={} confidence={:.2f}", rating, confidence);
            return Optional.of(decision);

        } catch (Exception e) {
            logger.warn("TradingAgentsClientService request failed: {}", e.getMessage());
            throw e; // rethrow to let resilience4j handle retry/circuitbreaker
        }
    }

    public Optional<AgentDecision> requestAnalysisFallback(TradeIntent intent, List<NewsArticle> recentNews, int openPositions, Throwable t) {
        logger.debug("TradingAgentsClientService fallback triggered: {}", t.getMessage());
        return Optional.empty();
    }
}

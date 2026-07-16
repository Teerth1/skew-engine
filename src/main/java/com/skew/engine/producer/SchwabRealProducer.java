package com.skew.engine.producer;

import com.skew.engine.config.KafkaConfig;
import com.skew.engine.domain.OptionTickEvent;
import com.skew.engine.service.SchwabApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@EnableScheduling
public class SchwabRealProducer {

    private static final Logger logger = LoggerFactory.getLogger(SchwabRealProducer.class);

    private final KafkaTemplate<String, OptionTickEvent> kafkaTemplate;
    private final SchwabApiService schwabApiService;
    private final com.skew.engine.service.SchwabDataGrabberService schwabDataGrabberService;
    private boolean streamingEnabled = false; // Off by default until activated by user
    private double spotPrice = 5000.0;
    private double putIv = 0.20;
    private double callIv = 0.15;

    public SchwabRealProducer(KafkaTemplate<String, OptionTickEvent> kafkaTemplate,
                              SchwabApiService schwabApiService,
                              com.skew.engine.service.SchwabDataGrabberService schwabDataGrabberService) {
        this.kafkaTemplate = kafkaTemplate;
        this.schwabApiService = schwabApiService;
        this.schwabDataGrabberService = schwabDataGrabberService;
    }

    public void setStreamingEnabled(boolean enabled) {
        this.streamingEnabled = enabled;
        logger.info("Real Schwab Option Tick Feed streaming set to: {}", enabled);
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    @Scheduled(fixedRate = 10000) // Runs every 10 seconds (to avoid aggressive rate limits)
    public void generateRealTicks() {
        if (!streamingEnabled) {
            return;
        }

        if (!schwabApiService.isAuthorized()) {
            logger.warn("Real Schwab stream enabled, but API is not authorized. Pause stream or complete OAuth.");
            return;
        }

        try {
            Map<String, Object> chain = schwabApiService.getOptionsChain("$SPX");
            if (chain != null && !chain.isEmpty()) {
                schwabDataGrabberService.archiveSnapshot("$SPX", chain);
            }
            processOptionsChain(chain);
        } catch (Exception e) {
            logger.error("Failed to query real-time options chain from Schwab: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void processOptionsChain(Map<String, Object> chain) {
        if (chain == null || !chain.containsKey("underlyingPrice")) {
            logger.warn("Received empty options chain from Schwab.");
            return;
        }

        double spot = ((Number) chain.get("underlyingPrice")).doubleValue();
        Map<String, Object> putExpDateMap = (Map<String, Object>) chain.get("putExpDateMap");
        Map<String, Object> callExpDateMap = (Map<String, Object>) chain.get("callExpDateMap");

        if (putExpDateMap == null || callExpDateMap == null || putExpDateMap.isEmpty() || callExpDateMap.isEmpty()) {
            logger.warn("Options chain maps are empty.");
            return;
        }

        // Pick the first expiration date returned
        String putExp = putExpDateMap.keySet().iterator().next();
        String callExp = callExpDateMap.keySet().iterator().next();

        Map<String, List<Map<String, Object>>> putsAtExp = (Map<String, List<Map<String, Object>>>) putExpDateMap.get(putExp);
        Map<String, List<Map<String, Object>>> callsAtExp = (Map<String, List<Map<String, Object>>>) callExpDateMap.get(callExp);

        double targetPutStrike = spot * 0.95;
        double targetCallStrike = spot * 1.05;

        Map<String, Object> bestPut = findClosestStrikeOption(putsAtExp, targetPutStrike);
        Map<String, Object> bestCall = findClosestStrikeOption(callsAtExp, targetCallStrike);

        if (bestPut != null && bestCall != null) {
            publishEvent(bestPut, spot, "PUT");
            publishEvent(bestCall, spot, "CALL");
        } else {
            logger.warn("Could not find matching Put and Call contracts on the Schwab chain.");
        }
    }

    private Map<String, Object> findClosestStrikeOption(Map<String, List<Map<String, Object>>> strikeMap, double targetStrike) {
        Map<String, Object> bestOption = null;
        double minDiff = Double.MAX_VALUE;

        for (String strikeStr : strikeMap.keySet()) {
            try {
                double strike = Double.parseDouble(strikeStr);
                double diff = Math.abs(strike - targetStrike);
                if (diff < minDiff) {
                    List<Map<String, Object>> optionsList = strikeMap.get(strikeStr);
                    if (optionsList != null && !optionsList.isEmpty()) {
                        bestOption = optionsList.get(0);
                        minDiff = diff;
                    }
                }
            } catch (NumberFormatException e) {
                // Ignore parsing errors for expiration details keys
            }
        }
        return bestOption;
    }

    private void publishEvent(Map<String, Object> option, double spot, String type) {
        String symbol = (String) option.get("symbol");
        double strike = ((Number) option.get("strikePrice")).doubleValue();
        double iv = ((Number) option.get("volatility")).doubleValue();

        // Normalize Implied Volatility: Schwab returns percentage points e.g. 18.5 vs decimal 0.185
        if (iv > 1.0) {
            iv = iv / 100.0;
        }

        double mark = option.containsKey("mark") ? ((Number) option.get("mark")).doubleValue() : 0.0;
        double bid = option.containsKey("bid") ? ((Number) option.get("bid")).doubleValue() : 0.0;
        double ask = option.containsKey("ask") ? ((Number) option.get("ask")).doubleValue() : 0.0;

        OptionTickEvent event = new OptionTickEvent(
                symbol,
                "SPX",
                type,
                strike,
                spot,
                iv,
                mark,
                bid,
                ask,
                System.currentTimeMillis()
        );

        kafkaTemplate.send(KafkaConfig.SCHWAB_TICKS_TOPIC, type, event);
        
        // Save latest state
        this.spotPrice = spot;
        if ("PUT".equalsIgnoreCase(type)) {
            this.putIv = iv;
        } else if ("CALL".equalsIgnoreCase(type)) {
            this.callIv = iv;
        }

        logger.info("Real Schwab Tick Broadcasted -> Type: {}, Strike: {}, Spot: {}, IV: {}%", 
                type, strike, spot, String.format("%.2f", iv * 100));
    }

    public double getSpotPrice() { return spotPrice; }
    public double getPutIv() { return putIv; }
    public double getCallIv() { return callIv; }
}

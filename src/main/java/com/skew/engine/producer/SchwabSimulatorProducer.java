package com.skew.engine.producer;

import com.skew.engine.config.KafkaConfig;
import com.skew.engine.domain.OptionTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Random;

@Service
@EnableScheduling
public class SchwabSimulatorProducer {

    private static final Logger logger = LoggerFactory.getLogger(SchwabSimulatorProducer.class);

    private final KafkaTemplate<String, OptionTickEvent> kafkaTemplate;
    private final Random random = new Random();

    // Running states
    private double spotPrice = 5000.0;
    private double putIv = 0.20;  // 20% IV for 95% strike put
    private double callIv = 0.15; // 15% IV for 105% strike call
    
    private boolean streamingEnabled = true;
    private String anomalyMode = "NONE"; // NONE, BEARISH_DIVERGENCE, BULLISH_DIVERGENCE
    private int anomalyTicksRemaining = 0;

    public SchwabSimulatorProducer(KafkaTemplate<String, OptionTickEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedRate = 2000) // Runs every 2 seconds
    public void generateMarketTicks() {
        if (!streamingEnabled) {
            return;
        }

        // 1. Process active anomaly modes or perform normal random walk
        double spotChange;
        double skewChange;

        if ("BEARISH_DIVERGENCE".equalsIgnoreCase(anomalyMode) && anomalyTicksRemaining > 0) {
            // Bearish Divergence: Spot goes UP, but Skew goes UP (both Put IV spikes)
            spotChange = 2.0 + random.nextDouble() * 3.0; // Spot goes up +2 to +5
            putIv += 0.002 + random.nextDouble() * 0.003; // Put IV goes up (fear rising despite rally)
            callIv -= 0.0005; // Call IV stays flat or down
            anomalyTicksRemaining--;
            if (anomalyTicksRemaining == 0) {
                anomalyMode = "NONE";
                logger.info("Bearish Divergence anomaly simulation finished.");
            }
        } else if ("BULLISH_DIVERGENCE".equalsIgnoreCase(anomalyMode) && anomalyTicksRemaining > 0) {
            // Bullish Divergence: Spot goes DOWN, but Skew goes DOWN (Put IV falls relative to calls)
            spotChange = -2.0 - random.nextDouble() * 3.0; // Spot goes down -2 to -5
            putIv -= 0.003 - random.nextDouble() * 0.002; // Put IV falls (fear drops despite selloff)
            callIv += 0.0005;
            anomalyTicksRemaining--;
            if (anomalyTicksRemaining == 0) {
                anomalyMode = "NONE";
                logger.info("Bullish Divergence anomaly simulation finished.");
            }
        } else {
            // Normal correlation: Spot up = Skew down, Spot down = Skew up
            spotChange = (random.nextDouble() - 0.48) * 6.0; // slight upward drift on average
            if (spotChange > 0) {
                // Spot up, Put IV drops, Call IV drops slightly
                putIv -= (0.0002 + random.nextDouble() * 0.0003);
                callIv -= 0.0001;
            } else {
                // Spot down, Put IV rises, Call IV rises slightly
                putIv += (0.0003 + random.nextDouble() * 0.0004);
                callIv += 0.0001;
            }
        }

        // Apply state updates (constrain boundaries)
        spotPrice = Math.max(1000.0, spotPrice + spotChange);
        putIv = Math.max(0.05, Math.min(0.80, putIv));
        callIv = Math.max(0.02, Math.min(0.60, callIv));

        long timestamp = System.currentTimeMillis();

        // 2. Generate and publish Put Option Event
        double putStrike = Math.round(spotPrice * 0.95);
        OptionTickEvent putEvent = new OptionTickEvent(
            "SPX_OTM_PUT",
            "SPX",
            "PUT",
            putStrike,
            spotPrice,
            putIv,
            calculateOptionPrice(spotPrice, putStrike, putIv, "PUT"),
            calculateOptionPrice(spotPrice, putStrike, putIv * 0.98, "PUT"),
            calculateOptionPrice(spotPrice, putStrike, putIv * 1.02, "PUT"),
            timestamp
        );

        // 3. Generate and publish Call Option Event
        double callStrike = Math.round(spotPrice * 1.05);
        OptionTickEvent callEvent = new OptionTickEvent(
            "SPX_OTM_CALL",
            "SPX",
            "CALL",
            callStrike,
            spotPrice,
            callIv,
            calculateOptionPrice(spotPrice, callStrike, callIv, "CALL"),
            calculateOptionPrice(spotPrice, callStrike, callIv * 0.98, "CALL"),
            calculateOptionPrice(spotPrice, callStrike, callIv * 1.02, "CALL"),
            timestamp
        );

        // 4. Send to Kafka topic
        try {
            kafkaTemplate.send(KafkaConfig.SCHWAB_TICKS_TOPIC, "PUT", putEvent);
            kafkaTemplate.send(KafkaConfig.SCHWAB_TICKS_TOPIC, "CALL", callEvent);
            
            logger.debug("Simulated Schwab ticks sent. Spot: {}, Put IV: {}%, Call IV: {}%, Skew: {}%", 
                String.format("%.2f", spotPrice),
                String.format("%.2f", putIv * 100),
                String.format("%.2f", callIv * 100),
                String.format("%.2f", (putIv - callIv) * 100)
            );
        } catch (Exception e) {
            logger.error("Failed to stream simulated Schwab option ticks to Kafka: {}", e.getMessage());
        }
    }

    // A simplified Black-Scholes-like pricing model for simulation bids/asks
    private double calculateOptionPrice(double spot, double strike, double iv, String type) {
        double d1 = (Math.log(spot / strike) + (iv * iv / 2)) / (iv + 0.001);
        double d2 = d1 - iv;
        if ("CALL".equalsIgnoreCase(type)) {
            return Math.max(0.05, spot * normalCDF(d1) - strike * normalCDF(d2));
        } else {
            return Math.max(0.05, strike * normalCDF(-d2) - spot * normalCDF(-d1));
        }
    }

    private double normalCDF(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private double erf(double z) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
        double ans = 1.0 - t * Math.exp(-z * z - 1.26551223 +
                t * (1.00002368 +
                t * (0.37409196 +
                t * (0.09678418 +
                t * (-0.18628806 +
                t * (0.27886807 +
                t * (-1.13520398 +
                t * (1.48851587 +
                t * (-0.82215223 +
                t * 0.17087277)))))))));
        return z >= 0 ? ans : -ans;
    }

    // Controls for REST API
    public void setStreamingEnabled(boolean enabled) {
        this.streamingEnabled = enabled;
        logger.info("Schwab Option Tick Simulator streaming set to: {}", enabled);
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public void triggerAnomaly(String mode, int durationTicks) {
        this.anomalyMode = mode.toUpperCase();
        this.anomalyTicksRemaining = durationTicks;
        logger.info("Triggered anomaly mode: {} for {} ticks", anomalyMode, durationTicks);
    }

    public String getAnomalyMode() {
        return anomalyMode;
    }

    public double getSpotPrice() { return spotPrice; }
    public double getPutIv() { return putIv; }
    public double getCallIv() { return callIv; }
}

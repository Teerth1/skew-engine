package com.skew.engine.consumer;

import com.skew.engine.config.KafkaConfig;
import com.skew.engine.domain.GexSnapshot;
import com.skew.engine.domain.LivePosition;
import com.skew.engine.domain.OptionTickEvent;
import com.skew.engine.domain.SkewRecord;
import com.skew.engine.domain.StrategyDecisionLog;
import com.skew.engine.domain.TradeSignalEvent;
import com.skew.engine.repository.GexSnapshotRepository;
import com.skew.engine.repository.LivePositionRepository;
import com.skew.engine.repository.SkewRecordRepository;
import com.skew.engine.repository.StrategyDecisionLogRepository;
import com.skew.engine.service.AlpacaService;
import com.skew.engine.service.OrderManagerService;
import com.skew.engine.service.SkewSignal;
import com.skew.engine.service.SkewSignalDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes raw {@link OptionTickEvent} messages from the Kafka topic.
 *
 * <h3>Refactored pipeline (Phase 1B)</h3>
 * <ol>
 *   <li><strong>Persist</strong> — paired PUT+CALL → {@link SkewRecord}</li>
 *   <li><strong>Signal detection</strong> — rolling lookback via {@link SkewSignalDetector}</li>
 *   <li><strong>Publish</strong> — send TradeSignalEvent to `trade-signals` topic</li>
 * </ol>
 */
@Service
public class OptionTickConsumer {

    private static final Logger logger = LoggerFactory.getLogger(OptionTickConsumer.class);

    private final SkewRecordRepository         skewRecordRepository;
    private final LivePositionRepository       livePositionRepository;
    private final StrategyDecisionLogRepository decisionLogRepository;
    private final AlpacaService                alpacaService;
    private final OrderManagerService          orderManagerService;
    private final SkewSignalDetector           skewSignalDetector;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final com.skew.engine.service.TradeMemoryService tradeMemoryService;
    private final GexSnapshotRepository        gexSnapshotRepository;

    @Value("${trading.lookback-ticks:10}")
    private int lookbackTicks;

    @Value("${trading.holding-ticks:10}")
    private int holdingTicks;

    @Value("${trading.spot-threshold:0.0005}")
    private double spotThreshold;

    @Value("${trading.skew-threshold:0.0005}")
    private double skewThreshold;

    // ---- Dynamic exit rules (Phase 6) ----

    /** Close when the option premium is up this much vs entry (0.5 = +50%). */
    @Value("${trading.exit.take-profit:0.50}")
    private double takeProfitPct;

    /** Close when the option premium is down this much vs entry (0.25 = -25%). */
    @Value("${trading.exit.stop-loss:0.25}")
    private double stopLossPct;

    /** Trailing stop activates once the position is up this much... */
    @Value("${trading.exit.trailing-activation:0.20}")
    private double trailingActivationPct;

    /** ...then closes if the premium falls this far off its high-water mark. */
    @Value("${trading.exit.trailing-drawdown:0.15}")
    private double trailingDrawdownPct;

    /** Close CALLs when spot breaches the dealer Call Wall (and PUTs at the Put Wall). */
    @Value("${trading.exit.gex-wall-exit:true}")
    private boolean gexWallExitEnabled;

    /** Ignore GEX snapshots older than this many minutes. */
    @Value("${trading.exit.gex-max-age-minutes:30}")
    private int gexMaxAgeMinutes;

    private final Map<String, OptionTickEvent> latestPutTick  = new ConcurrentHashMap<>();
    private final Map<String, OptionTickEvent> latestCallTick = new ConcurrentHashMap<>();
    private final Map<String, Deque<Double>>   spotWindow     = new ConcurrentHashMap<>();
    private final Map<String, Deque<Double>>   skewWindow     = new ConcurrentHashMap<>();

    private volatile String latestCommentary = "Monitoring for skew divergence signals…";
    private volatile String latestSignal     = "NONE";

    public OptionTickConsumer(SkewRecordRepository skewRecordRepository,
                              LivePositionRepository livePositionRepository,
                              StrategyDecisionLogRepository decisionLogRepository,
                              AlpacaService alpacaService,
                              OrderManagerService orderManagerService,
                              SkewSignalDetector skewSignalDetector,
                              KafkaTemplate<String, Object> kafkaTemplate,
                              com.skew.engine.service.TradeMemoryService tradeMemoryService,
                              GexSnapshotRepository gexSnapshotRepository) {
        this.skewRecordRepository  = skewRecordRepository;
        this.livePositionRepository = livePositionRepository;
        this.decisionLogRepository  = decisionLogRepository;
        this.alpacaService          = alpacaService;
        this.orderManagerService    = orderManagerService;
        this.skewSignalDetector     = skewSignalDetector;
        this.kafkaTemplate          = kafkaTemplate;
        this.tradeMemoryService     = tradeMemoryService;
        this.gexSnapshotRepository  = gexSnapshotRepository;
    }

    @KafkaListener(topics = KafkaConfig.SCHWAB_TICKS_TOPIC, groupId = "skew-engine-group")
    public void consume(OptionTickEvent event, @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        if (event == null || event.ticker() == null) {
            logger.warn("Received null or incomplete OptionTickEvent — skipping.");
            return;
        }

        String ticker = event.ticker();

        if ("PUT".equalsIgnoreCase(event.optionType())) {
            latestPutTick.put(ticker, event);
        } else if ("CALL".equalsIgnoreCase(event.optionType())) {
            latestCallTick.put(ticker, event);
        } else {
            logger.warn("Unknown option type '{}' for ticker {} — skipping.", event.optionType(), ticker);
            return;
        }

        OptionTickEvent put  = latestPutTick.get(ticker);
        OptionTickEvent call = latestCallTick.get(ticker);
        if (put == null || call == null) return;

        double skew      = put.impliedVolatility() - call.impliedVolatility();
        double spotPrice = event.underlyingPrice();

        persistSkewRecord(ticker, put, call, skew, event, spotPrice);
        updateWindows(ticker, spotPrice, skew);
        runSignalEngine(ticker, put, call, skew, spotPrice);
        tickOpenPositions(spotPrice);
    }

    private void runSignalEngine(String ticker, OptionTickEvent put, OptionTickEvent call,
                                 double skew, double spotPrice) {
        Deque<Double> spots = spotWindow.get(ticker);
        Deque<Double> skews = skewWindow.get(ticker);

        if (spots == null || spots.size() < lookbackTicks) return;

        double pastSpot = ((ArrayDeque<Double>) spots).peekFirst();
        double pastSkew = ((ArrayDeque<Double>) skews).peekFirst();

        if (pastSpot <= 0.0) return;

        SkewSignal detectedSignal = skewSignalDetector.detect(
                pastSpot, spotPrice, pastSkew, skew, spotThreshold, skewThreshold);
        if (!detectedSignal.isTriggered()) return;

        String signal      = detectedSignal.type().name();
        double spotReturn  = detectedSignal.spotReturn();
        double skewChange  = detectedSignal.skewChange();

        if (!livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN").isEmpty()) {
            logger.debug("Signal {} detected but position already open — skipping.", signal);
            return;
        }

        latestSignal = signal;
        logger.info("🔔 SIGNAL: {} | SPX={} spotReturn={}% skewChange={}%",
                signal, String.format("%.0f", spotPrice),
                String.format("%.2f", spotReturn * 100),
                String.format("%.2f", skewChange * 100));

        String optionType = "BEARISH_DIVERGENCE".equals(signal) ? "put" : "call";

        TradeSignalEvent signalEvent = new TradeSignalEvent(
                ticker, signal, optionType, spotPrice,
                put.impliedVolatility(), call.impliedVolatility(),
                spotReturn, skewChange, Instant.now()
        );

        // Publish event to trade-signals for async processing
        kafkaTemplate.send(KafkaConfig.TRADE_SIGNALS_TOPIC, ticker, signalEvent);
    }

    private void tickOpenPositions(double spotPrice) {
        List<LivePosition> openPositions = livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN");
        for (LivePosition pos : openPositions) {
            pos.incrementTicksHeld();

            double latestPrice = alpacaService.getLatestOptionPrice(pos.getOptionSymbol());
            if (latestPrice > 0.0) {
                pos.updatePnl(latestPrice);
            }

            String exitReason = evaluateExit(pos, spotPrice, latestPrice > 0.0);
            if (exitReason != null) {
                double exitPrice = latestPrice > 0.0 ? latestPrice : pos.getCurrentOptionPrice();
                pos.setExitReason(exitReason);
                logger.info("🚪 EXIT [{}] {} | ret={}% peak-dd={}% ticks={}",
                        exitReason, pos.getOptionSymbol(),
                        String.format("%.1f", pos.unrealizedReturn() * 100),
                        String.format("%.1f", pos.drawdownFromPeak() * 100),
                        pos.getTicksHeld());
                orderManagerService.closePosition(pos, exitPrice);
                updateDecisionLogOutcome(pos);
            } else {
                livePositionRepository.save(pos);
            }
        }
    }

    /**
     * Dynamic exit rules, evaluated in priority order. Returns the exit reason
     * or {@code null} to keep holding.
     *
     * <ol>
     *   <li><strong>STOP_LOSS</strong> — premium down more than the limit.</li>
     *   <li><strong>TAKE_PROFIT</strong> — premium up past the target.</li>
     *   <li><strong>TRAILING_STOP</strong> — was up past the activation level,
     *       then gave back too much from the high-water mark.</li>
     *   <li><strong>GEX_WALL_BREACH</strong> — spot crossed the dealer wall
     *       working against the position (calls capped at the Call Wall,
     *       puts supported at the Put Wall).</li>
     *   <li><strong>MAX_HOLDING</strong> — tick-count backstop (theta guard).</li>
     * </ol>
     *
     * <p>P&L-based rules only fire on a fresh price ({@code hasFreshPrice}) so a
     * stale quote can never trigger a phantom stop.</p>
     */
    private String evaluateExit(LivePosition pos, double spotPrice, boolean hasFreshPrice) {
        if (hasFreshPrice && pos.getEntryOptionPrice() > 0.0) {
            double ret = pos.unrealizedReturn();

            if (ret <= -Math.abs(stopLossPct)) {
                return "STOP_LOSS";
            }
            if (ret >= takeProfitPct) {
                return "TAKE_PROFIT";
            }
            double peakGain = (pos.getPeakOptionPrice() - pos.getEntryOptionPrice())
                    / pos.getEntryOptionPrice();
            if (peakGain >= trailingActivationPct && pos.drawdownFromPeak() >= trailingDrawdownPct) {
                return "TRAILING_STOP";
            }
        }

        if (gexWallExitEnabled && spotPrice > 0.0) {
            GexSnapshot gex = gexSnapshotRepository.findTopByTickerOrderByTimestampDesc("SPX");
            if (gex != null && gex.getTimestamp() != null
                    && gex.getTimestamp().isAfter(LocalDateTime.now().minusMinutes(gexMaxAgeMinutes))) {
                boolean isCall = "CALL".equalsIgnoreCase(pos.getOptionType());
                if (isCall && gex.getCallWall() > 0.0 && spotPrice >= gex.getCallWall()) {
                    return "GEX_WALL_BREACH"; // dealers sell into rallies above the Call Wall
                }
                if (!isCall && gex.getPutWall() > 0.0 && spotPrice <= gex.getPutWall()) {
                    return "GEX_WALL_BREACH"; // dealers buy dips at the Put Wall — downside capped
                }
            }
        }

        if (pos.getTicksHeld() >= holdingTicks) {
            return "MAX_HOLDING";
        }
        return null;
    }

    private void updateDecisionLogOutcome(LivePosition pos) {
        List<StrategyDecisionLog> logs =
                decisionLogRepository.findBySignalTypeOrderByCreatedAtDesc(pos.getSignalType());
        if (!logs.isEmpty()) {
            StrategyDecisionLog log = logs.get(0);
            log.setExitPrice(pos.getCurrentOptionPrice());
            log.setRealizedPnl(pos.getProfitLoss());
            log.setClosedAt(pos.getExitTime());
            decisionLogRepository.save(log);
            
            // Phase 3: RAG memory embedding
            tradeMemoryService.embedClosedTrade(log);
        }
    }

    private void persistSkewRecord(String ticker, OptionTickEvent put, OptionTickEvent call,
                                   double skew, OptionTickEvent event, double spotPrice) {
        LocalDateTime eventTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(event.timestamp()), ZoneId.systemDefault());

        SkewRecord record = new SkewRecord(
                eventTime, ticker, spotPrice,
                put.impliedVolatility(), call.impliedVolatility(),
                skew, 0.0, 0.0, "NONE");

        try {
            skewRecordRepository.save(record);
        } catch (Exception e) {
            logger.error("Failed to persist SkewRecord for ticker {}: {}", ticker, e.getMessage());
        }
    }

    private void updateWindows(String ticker, double spotPrice, double skew) {
        spotWindow.computeIfAbsent(ticker, k -> new ArrayDeque<>());
        skewWindow.computeIfAbsent(ticker, k -> new ArrayDeque<>());

        Deque<Double> spots = spotWindow.get(ticker);
        Deque<Double> skews = skewWindow.get(ticker);

        spots.addLast(spotPrice);
        skews.addLast(skew);

        while (spots.size() > lookbackTicks) spots.pollFirst();
        while (skews.size() > lookbackTicks) skews.pollFirst();
    }

    public String getLatestCommentary() { return latestCommentary; }
    public void setLatestCommentary(String commentary) { this.latestCommentary = commentary; }
    public String getLatestSignal()     { return latestSignal; }
}

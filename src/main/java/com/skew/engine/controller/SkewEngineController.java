package com.skew.engine.controller;

import com.skew.engine.consumer.OptionTickConsumer;
import com.skew.engine.domain.BacktestResult;
import com.skew.engine.domain.LivePosition;
import com.skew.engine.domain.SkewRecord;
import com.skew.engine.producer.SchwabSimulatorProducer;
import com.skew.engine.repository.LivePositionRepository;
import com.skew.engine.repository.SkewRecordRepository;
import com.skew.engine.service.AlpacaService;
import com.skew.engine.service.BacktesterService;
import com.skew.engine.service.CommentaryService;
import com.skew.engine.service.DatabaseSeederService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST controller exposing all endpoints consumed by the frontend dashboard.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET  /api/skew-history          — last 500 historical SkewRecords for charting</li>
 *   <li>POST /api/backtest              — run the Imran Lakha divergence backtest</li>
 *   <li>GET  /api/simulator/status      — current live state (market data + latest signal + commentary)</li>
 *   <li>POST /api/simulator/toggle      — start / pause the Kafka tick stream</li>
 *   <li>POST /api/simulator/anomaly     — inject a bearish or bullish skew divergence</li>
 *   <li>GET  /api/positions             — all live paper trading positions</li>
 *   <li>POST /api/positions/close/{id}  — manually close a live position</li>
 *   <li>POST /api/schwab/seed           — re-seed database from real Schwab price history</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class SkewEngineController {

    private static final Logger logger = LoggerFactory.getLogger(SkewEngineController.class);

    private final SkewRecordRepository    skewRecordRepository;
    private final BacktesterService       backtesterService;
    private final SchwabSimulatorProducer simulatorProducer;
    private final com.skew.engine.producer.SchwabRealProducer realProducer;
    private final DatabaseSeederService   databaseSeederService;
    private final LivePositionRepository  livePositionRepository;
    private final AlpacaService           alpacaService;
    private final CommentaryService       commentaryService;
    private final OptionTickConsumer      optionTickConsumer;

    private String activeFeed = "SIMULATOR"; // SIMULATOR, REAL

    public SkewEngineController(SkewRecordRepository skewRecordRepository,
                                BacktesterService backtesterService,
                                SchwabSimulatorProducer simulatorProducer,
                                com.skew.engine.producer.SchwabRealProducer realProducer,
                                DatabaseSeederService databaseSeederService,
                                LivePositionRepository livePositionRepository,
                                AlpacaService alpacaService,
                                CommentaryService commentaryService,
                                OptionTickConsumer optionTickConsumer) {
        this.skewRecordRepository  = skewRecordRepository;
        this.backtesterService     = backtesterService;
        this.simulatorProducer     = simulatorProducer;
        this.realProducer          = realProducer;
        this.databaseSeederService = databaseSeederService;
        this.livePositionRepository = livePositionRepository;
        this.alpacaService         = alpacaService;
        this.commentaryService     = commentaryService;
        this.optionTickConsumer    = optionTickConsumer;
    }

    // -------------------------------------------------------------------------
    // Historical Data
    // -------------------------------------------------------------------------

    /**
     * Returns the most recent 500 skew records ordered ascending by timestamp so
     * the frontend chart renders chronologically left-to-right.
     */
    @GetMapping("/skew-history")
    public ResponseEntity<List<SkewRecord>> getSkewHistory() {
        List<SkewRecord> records = skewRecordRepository.findAllByOrderByTimestampAsc();
        int fromIndex = Math.max(0, records.size() - 500);
        return ResponseEntity.ok(records.subList(fromIndex, records.size()));
    }

    // -------------------------------------------------------------------------
    // Backtester
    // -------------------------------------------------------------------------

    /**
     * Triggers a full backtest run using the provided parameters.
     *
     * @param initialCapital  starting portfolio value in USD (default: 100 000)
     * @param lookbackDays    rolling window size in trading days (default: 10 ≈ 2 weeks)
     * @param holdingPeriod   how long to hold each position in trading days (default: 10)
     * @param skewThreshold   minimum skew change (as a decimal) to trigger a signal (default: 0.005)
     */
    @PostMapping("/backtest")
    public ResponseEntity<BacktestResult> runBacktest(
            @RequestParam(defaultValue = "100000") double initialCapital,
            @RequestParam(defaultValue = "10")     int    lookbackDays,
            @RequestParam(defaultValue = "10")     int    holdingPeriod,
            @RequestParam(defaultValue = "0.005")  double skewThreshold) {

        logger.info("Backtest requested: capital={}, lookback={}, hold={}, threshold={}",
                initialCapital, lookbackDays, holdingPeriod, skewThreshold);

        BacktestResult result = backtesterService.runBacktest(
                initialCapital, lookbackDays, holdingPeriod, skewThreshold);

        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Simulator Controls
    // -------------------------------------------------------------------------

    /**
     * Returns the current streaming state, latest market values, and the most recent
     * AI commentary + signal from the live signal engine.
     * Polled by the frontend every 1.5 seconds.
     */
    @GetMapping("/simulator/status")
    public ResponseEntity<Map<String, Object>> getSimulatorStatus() {
        boolean streamingEnabled = "REAL".equalsIgnoreCase(activeFeed)
            ? realProducer.isStreamingEnabled()
            : simulatorProducer.isStreamingEnabled();

        double spotPrice = "REAL".equalsIgnoreCase(activeFeed)
            ? realProducer.getSpotPrice()
            : simulatorProducer.getSpotPrice();

        double putIv = "REAL".equalsIgnoreCase(activeFeed)
            ? realProducer.getPutIv()
            : simulatorProducer.getPutIv();

        double callIv = "REAL".equalsIgnoreCase(activeFeed)
            ? realProducer.getCallIv()
            : simulatorProducer.getCallIv();

        double skew = putIv - callIv;

        // Count open positions for dashboard badge
        long openCount = livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN").size();

        Map<String, Object> status = new java.util.HashMap<>();
        status.put("activeFeed",        activeFeed);
        status.put("streamingEnabled",  streamingEnabled);
        status.put("anomalyMode",       "REAL".equalsIgnoreCase(activeFeed) ? "NONE" : simulatorProducer.getAnomalyMode());
        status.put("spotPrice",         spotPrice);
        status.put("putIv",             putIv);
        status.put("callIv",            callIv);
        status.put("skew",              skew);
        status.put("latestSignal",      optionTickConsumer.getLatestSignal());
        status.put("latestCommentary",  optionTickConsumer.getLatestCommentary());
        status.put("openPositions",     openCount);
        status.put("alpacaConfigured",  alpacaService.isConfigured());
        status.put("geminiConfigured",  commentaryService.isConfigured());
        return ResponseEntity.ok(status);
    }

    /**
     * Starts or pauses the active Kafka tick stream.
     *
     * @param enabled {@code true} to resume streaming, {@code false} to pause
     */
    @PostMapping("/simulator/toggle")
    public ResponseEntity<Map<String, Object>> toggleSimulator(
            @RequestParam boolean enabled) {

        if ("REAL".equalsIgnoreCase(activeFeed)) {
            realProducer.setStreamingEnabled(enabled);
        } else {
            simulatorProducer.setStreamingEnabled(enabled);
        }
        logger.info("Streaming set to {} for active feed: {}", enabled, activeFeed);

        return ResponseEntity.ok(Map.of(
                "activeFeed",        activeFeed,
                "streamingEnabled",  enabled,
                "message",           enabled ? "Stream started" : "Stream paused"
        ));
    }

    /**
     * Injects a synthetic skew divergence anomaly into the live Kafka stream.
     *
     * @param mode          {@code "bearish"} or {@code "bullish"}
     * @param durationTicks number of ticks the anomaly persists (default: 15)
     */
    @PostMapping("/simulator/anomaly")
    public ResponseEntity<Map<String, Object>> triggerAnomaly(
            @RequestParam                    String mode,
            @RequestParam(defaultValue = "15") int  durationTicks) {

        simulatorProducer.triggerAnomaly(mode, durationTicks);
        logger.info("Anomaly injected: mode={}, duration={} ticks", mode, durationTicks);

        return ResponseEntity.ok(Map.of(
                "anomalyMode",    mode.toUpperCase(),
                "durationTicks",  durationTicks,
                "message",        "Anomaly injected into Kafka stream"
        ));
    }

    @PostMapping("/simulator/feed")
    public ResponseEntity<Map<String, Object>> switchFeed(@RequestParam String feedType) {
        activeFeed = feedType.toUpperCase();
        simulatorProducer.setStreamingEnabled(false);
        realProducer.setStreamingEnabled(false);

        logger.info("Market data feed switched to: {}", activeFeed);
        return ResponseEntity.ok(Map.of(
                "activeFeed", activeFeed,
                "message",    "Market data feed switched to " + activeFeed
        ));
    }

    // -------------------------------------------------------------------------
    // Live Positions (Alpaca Paper Trades)
    // -------------------------------------------------------------------------

    /**
     * Returns all live paper trading positions, most recent first.
     * Includes both OPEN (unrealized) and CLOSED (realized) positions.
     */
    @GetMapping("/positions")
    public ResponseEntity<List<LivePosition>> getPositions() {
        List<LivePosition> positions = livePositionRepository.findAllByOrderByEntryTimeDesc();
        return ResponseEntity.ok(positions);
    }

    /**
     * Returns only currently open (unrealized) positions.
     */
    @GetMapping("/positions/open")
    public ResponseEntity<List<LivePosition>> getOpenPositions() {
        return ResponseEntity.ok(
                livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN"));
    }

    /**
     * Manually closes a live position by ID (triggers a SELL order on Alpaca).
     *
     * @param id the {@link LivePosition} database ID
     */
    @PostMapping("/positions/close/{id}")
    public ResponseEntity<Map<String, Object>> closePosition(@PathVariable Long id) {
        return livePositionRepository.findById(id).map(pos -> {
            if ("CLOSED".equals(pos.getStatus())) {
                return ResponseEntity.ok(Map.<String, Object>of(
                        "message", "Position " + id + " is already closed."));
            }

            // Submit a sell-to-close order on Alpaca
            alpacaService.submitSellOrder(pos.getOptionSymbol(), pos.getQuantity());

            // Get latest price for final P&L
            double exitPrice = alpacaService.getLatestOptionPrice(pos.getOptionSymbol());
            if (exitPrice <= 0.0) exitPrice = pos.getCurrentOptionPrice();

            pos.close(LocalDateTime.now(), exitPrice);
            livePositionRepository.save(pos);

            logger.info("Position {} manually closed. P&L: ${:.2f}", id, pos.getProfitLoss());
            return ResponseEntity.ok(Map.<String, Object>of(
                    "message",    "Position closed successfully.",
                    "profitLoss", pos.getProfitLoss()
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    // -------------------------------------------------------------------------
    // Schwab Real Data Seeder
    // -------------------------------------------------------------------------

    @PostMapping("/schwab/seed")
    public ResponseEntity<Map<String, Object>> seedRealHistory() {
        try {
            databaseSeederService.seedFromSchwab();
            return ResponseEntity.ok(Map.of("message", "Database successfully re-seeded with real Schwab price history."));
        } catch (Exception e) {
            logger.error("Failed to seed from Schwab: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}

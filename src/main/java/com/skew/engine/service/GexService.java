package com.skew.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import com.skew.engine.domain.GexSnapshot;
import com.skew.engine.repository.GexSnapshotRepository;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import java.util.*;

/**
 * GEX (Gamma Exposure) Calculator
 *
 * GEX tells us how much dollar-gamma Market Makers need to hedge at each
 * strike.
 * Positive GEX = MMs stabilize the market (buy dips, sell rips).
 * Negative GEX = MMs amplify moves (volatility expands).
 *
 * Formula per strike:
 * Call GEX = gamma * openInterest * 100 * spotPrice^2 * 0.01
 * Put GEX = -gamma * openInterest * 100 * spotPrice^2 * 0.01
 * Net GEX = Call GEX + Put GEX
 */
@Service
public class GexService {

    private static final Logger logger = LoggerFactory.getLogger(GexService.class);

    private final SchwabApiService schwabService;
    private final GexSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final BlackScholesService blackScholesService;

    public GexService(SchwabApiService schwabService, 
                      GexSnapshotRepository snapshotRepository, 
                      ObjectMapper objectMapper,
                      BlackScholesService blackScholesService) {
        this.schwabService = schwabService;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
        this.blackScholesService = blackScholesService;
    }

    // -------------------------------------------------------------------------
    // Data Classes — represent one row in the GEX ladder and the full result
    // -------------------------------------------------------------------------

    public static class GexRow {
        public double strike;
        public double callGex;
        public double putGex;
        public double netGex;
        
        // Institutional Greeks
        public double callVanna;
        public double putVanna;
        public double callCharm;
        public double putCharm;

        public String label; // e.g. "CALL WALL", "PUT WALL", "ZERO FLIP"

        // Default constructor for Jackson
        public GexRow() {}

        public GexRow(double strike, double callGex, double putGex, double callVanna, double putVanna, double callCharm, double putCharm) {
            this.strike = strike;
            this.callGex = callGex;
            this.putGex = putGex;
            this.netGex = callGex + putGex;
            this.callVanna = callVanna;
            this.putVanna = putVanna;
            this.callCharm = callCharm;
            this.putCharm = putCharm;
        }
    }

    public static class GexResult {
        public String symbol;
        public double spotPrice;
        public List<GexRow> rows; // all strikes, sorted highest → lowest
        public double callWall; // strike with highest net GEX (ceiling)
        public double putWall; // strike with lowest net GEX (floor)
        public double zeroFlip; // strike where GEX flips negative (danger)
        public boolean hasZeroFlip;

        public GexResult(String symbol, double spotPrice, List<GexRow> rows,
                double callWall, double putWall,
                double zeroFlip, boolean hasZeroFlip) {
            this.symbol = symbol;
            this.spotPrice = spotPrice;
            this.rows = rows;
            this.callWall = callWall;
            this.putWall = putWall;
            this.zeroFlip = zeroFlip;
            this.hasZeroFlip = hasZeroFlip;
        }
    }

    @Scheduled(cron = "0 * 9-16 * * MON-FRI", zone = "America/New_York")
    public void recordSpxSnapshot() {
        // Precise market hour check: 9:30 AM - 4:00 PM EST
        LocalTime now = LocalTime.now(ZoneId.of("America/New_York"));
        if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(16, 0))) {
            return;
        }

        Optional<JsonNode> chainOpt = schwabService.getFullOptionChain("SPX");
        if (chainOpt.isEmpty()) {
            logger.warn("Failed to retrieve SPX option chain for snapshot.");
            return;
        }

        Optional<GexResult> gexResultOpt = calculateGex(chainOpt.get(), "SPX", null);
        if (gexResultOpt.isEmpty()) {
            logger.warn("Failed to calculate GEX for SPX snapshot.");
            return;
        }

        GexResult result = gexResultOpt.get();

        GexSnapshot snapshot = new GexSnapshot();
        snapshot.setTicker("SPX");
        snapshot.setTimestamp(LocalDateTime.now());
        snapshot.setSpotPrice(result.spotPrice);
        snapshot.setCallWall(result.callWall);
        snapshot.setPutWall(result.putWall);
        snapshot.setZeroGamma(result.zeroFlip);
        
        try {
            snapshot.setStrikeDataJson(objectMapper.writeValueAsString(result.rows));
        } catch (Exception e) {
            logger.error("Failed to serialize GEX rows into JSON", e);
            snapshot.setStrikeDataJson("[]");
        }
        
        snapshotRepository.save(snapshot);
        logger.info("Saved SPX GEX snapshot at spot {}", result.spotPrice);
    }
    // -------------------------------------------------------------------------
    // Main method — call this from DiscordBotService
    // -------------------------------------------------------------------------

    /**
     * Calculate GEX from the raw Schwab option chain JSON.
     */
    public Optional<GexResult> calculateGex(JsonNode chainRoot, String symbol, Integer targetDte) {

        double spotPrice = chainRoot.get("underlyingPrice").asDouble();

        TreeMap<Double, double[]> gexMap = new TreeMap<>();
        
        // Process Calls
        processOptionMap(chainRoot.get("callExpDateMap"), gexMap, spotPrice, targetDte, true);
        
        // Process Puts
        processOptionMap(chainRoot.get("putExpDateMap"), gexMap, spotPrice, targetDte, false);

        List<GexRow> rows = new ArrayList<>();

        for (Map.Entry<Double, double[]> entry : gexMap.descendingMap().entrySet()) {
            double strike = entry.getKey();
            double callGex = entry.getValue()[0];
            double putGex = entry.getValue()[1];
            double callVanna = entry.getValue()[2];
            double putVanna = entry.getValue()[3];
            double callCharm = entry.getValue()[4];
            double putCharm = entry.getValue()[5];
            rows.add(new GexRow(strike, callGex, putGex, callVanna, putVanna, callCharm, putCharm));
        }

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        // CALL WALL
        GexRow callWallRow = rows.get(0);
        for (GexRow row : rows) {
            if (row.netGex > callWallRow.netGex) {
                callWallRow = row;
            }
        }

        // PUT WALL
        GexRow putWallRow = rows.get(0);
        for (GexRow row : rows) {
            if (row.netGex < putWallRow.netGex) {
                putWallRow = row;
            }
        }

        // ZERO FLIP — first strike BELOW spot where net GEX goes negative
        // rows are sorted high→low, so skip anything at or above spot first
        GexRow zeroFlipRow = null;
        boolean passedSpot = false;
        for (GexRow row : rows) {
            if (row.strike <= spotPrice)
                passedSpot = true;
            if (passedSpot && row.netGex < 0) {
                zeroFlipRow = row;
                break;
            }
        }

        // Then update the label + return accordingly:
        if (zeroFlipRow != null)
            zeroFlipRow.label = "⚡ ZERO FLIP";

        return Optional.of(new GexResult(
                symbol, spotPrice, rows,
                callWallRow.strike,
                putWallRow.strike,
                zeroFlipRow != null ? zeroFlipRow.strike : Double.NaN,
                zeroFlipRow != null // false if no flip found
        ));
    }

    private void processOptionMap(com.fasterxml.jackson.databind.JsonNode optionMapPart, java.util.TreeMap<Double, double[]> gexMap, 
                                  double spotPrice, Integer targetDte, boolean isCall) {
        if (optionMapPart == null) return;
        java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> expirations = optionMapPart.fields();

        while (expirations.hasNext()) {
            com.fasterxml.jackson.databind.JsonNode strikesForThisExpiry = expirations.next().getValue();
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> strikes = strikesForThisExpiry.fields();

            while (strikes.hasNext()) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> strikeEntry = strikes.next();
                double strike = Double.parseDouble(strikeEntry.getKey());
                com.fasterxml.jackson.databind.JsonNode contract = strikeEntry.getValue().get(0);

                int daysToExpiration = contract.path("daysToExpiration").asInt(0);
                if (targetDte != null && daysToExpiration != targetDte) {
                    continue; // Skip contracts that don't match the requested DTE
                }

                double gamma = contract.path("gamma").asDouble(0);
                double oi = contract.path("openInterest").asDouble(0);
                double iv = contract.path("volatility").asDouble(0.01);
                
                if (iv > 10) iv = iv / 100.0;
                if (iv <= 0) iv = 0.01;
                
                double t = Math.max(1, daysToExpiration) / 365.0; 
                double r = 0.05; 
                
                double rawVanna = blackScholesService.calculateVanna(spotPrice, strike, t, iv, r);
                double rawCharm = blackScholesService.calculateCharm(spotPrice, strike, t, iv, r, isCall ? "call" : "put");
                
                double gex = gamma * oi * 100 * spotPrice * spotPrice * 0.01;
                if (!isCall) gex = -gex; // Short Gamma for Puts
                
                // Vanna Ex: Dollar impact of a total IV collapse
                double vanna = rawVanna * oi * 100 * spotPrice * iv; 
                // Charm Ex: Hourly dealer delta decay
                double charm = (rawCharm / 24.0) * oi * 100 * spotPrice;

                gexMap.computeIfAbsent(strike, k -> new double[6]);
                
                if (isCall) {
                    gexMap.get(strike)[0] += gex;
                    gexMap.get(strike)[2] += vanna;
                    gexMap.get(strike)[4] += charm;
                } else {
                    gexMap.get(strike)[1] += gex;
                    gexMap.get(strike)[3] += vanna;
                    gexMap.get(strike)[5] += charm;
                }
            }
        }
    }
}

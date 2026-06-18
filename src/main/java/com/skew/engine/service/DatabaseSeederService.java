package com.skew.engine.service;

import com.skew.engine.domain.SkewRecord;
import com.skew.engine.repository.SkewRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class DatabaseSeederService implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseSeederService.class);

    private final SkewRecordRepository skewRecordRepository;
    private final SchwabApiService schwabApiService;
    private final Random random = new Random(42); // Fixed seed for reproducibility

    public DatabaseSeederService(SkewRecordRepository skewRecordRepository, SchwabApiService schwabApiService) {
        this.skewRecordRepository = skewRecordRepository;
        this.schwabApiService = schwabApiService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (skewRecordRepository.count() > 0) {
            logger.info("Database already seeded with historical records. Skipping startup seeder.");
            return;
        }

        if (schwabApiService.isAuthorized()) {
            try {
                seedFromSchwab();
                return;
            } catch (Exception e) {
                logger.error("Failed to seed from Schwab on startup: {}. Falling back to mock data.", e.getMessage());
            }
        }

        logger.info("Seeding database with fallback mock SPX and Skew data (500 days)...");
        seedMockData();
    }

    @SuppressWarnings("unchecked")
    public void seedFromSchwab() throws Exception {
        logger.info("Wiping database and starting real Schwab price history seeder...");
        skewRecordRepository.deleteAll();

        Map<String, Object> history = schwabApiService.getHistoricalPrice("$SPX", 500);
        if (history == null || !history.containsKey("candles")) {
            throw new RuntimeException("No price history returned from Schwab Developer API.");
        }

        List<Map<String, Object>> candles = (List<Map<String, Object>>) history.get("candles");
        logger.info("Retrieved {} daily candles from Schwab. Seeding database...", candles.size());

        List<SkewRecord> records = new ArrayList<>();
        double lastSkew = 0.06;

        for (int i = 0; i < candles.size(); i++) {
            Map<String, Object> candle = candles.get(i);
            double spot = ((Number) candle.get("close")).doubleValue();
            long datetime = ((Number) candle.get("datetime")).longValue();
            
            LocalDateTime dayTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(datetime), ZoneId.systemDefault());

            double dailySpotReturn = 0.0;
            if (i > 0) {
                double prevSpot = ((Number) candles.get(i - 1).get("close")).doubleValue();
                dailySpotReturn = (spot - prevSpot) / prevSpot;
            }

            // Spot down -> Skew up, Spot up -> Skew down
            double dailySkewChange = -0.5 * dailySpotReturn + (random.nextDouble() - 0.5) * 0.002;
            double skew = Math.max(0.01, Math.min(0.20, lastSkew + dailySkewChange));
            lastSkew = skew;

            double baseCallIv = 0.12 + (spot > 4500 ? 0.01 : 0);
            double putIv = baseCallIv + skew;
            double callIv = baseCallIv;

            // 10-day lookback (2 weeks of trading days)
            double twoWeekSpotReturn = 0.0;
            double twoWeekSkewChange = 0.0;
            String signalType = "NONE";

            if (i >= 10) {
                SkewRecord pastRecord = records.get(i - 10);
                double pastSpot = pastRecord.getSpotPrice();
                double pastSkew = pastRecord.getSkew();

                twoWeekSpotReturn = (spot - pastSpot) / pastSpot;
                twoWeekSkewChange = skew - pastSkew;

                if (twoWeekSpotReturn > 0.02 && twoWeekSkewChange > 0.005) {
                    signalType = "BEARISH_DIVERGENCE";
                } else if (twoWeekSpotReturn < -0.02 && twoWeekSkewChange < -0.005) {
                    signalType = "BULLISH_DIVERGENCE";
                } else {
                    signalType = "CONFIRMING";
                }
            }

            records.add(new SkewRecord(
                dayTime,
                "SPX",
                spot,
                putIv,
                callIv,
                skew,
                twoWeekSpotReturn,
                twoWeekSkewChange,
                signalType
            ));
        }

        skewRecordRepository.saveAll(records);
        logger.info("Successfully seeded database with {} real historical SPX records from Schwab.", records.size());
    }

    private void seedMockData() {
        List<SkewRecord> records = new ArrayList<>();
        LocalDateTime startTime = LocalDateTime.now().minusDays(500);
        double spot = 4000.0;
        double skew = 0.06;

        for (int i = 0; i < 500; i++) {
            LocalDateTime dayTime = startTime.plusDays(i);
            double dailySpotReturn;
            double dailySkewChange;

            // 1. Day 100-110: Bearish Divergence
            if (i >= 100 && i < 110) {
                dailySpotReturn = 0.005 + random.nextDouble() * 0.004;
                dailySkewChange = 0.001 + random.nextDouble() * 0.0015;
            }
            // Day 110-125: Correction
            else if (i >= 110 && i < 125) {
                dailySpotReturn = -0.008 - random.nextDouble() * 0.008;
                dailySkewChange = 0.002 + random.nextDouble() * 0.002;
            }
            // 2. Day 250-260: Bullish Divergence
            else if (i >= 250 && i < 260) {
                dailySpotReturn = -0.004 - random.nextDouble() * 0.003;
                dailySkewChange = -0.0015 - random.nextDouble() * 0.0015;
            }
            // Day 260-280: Recovery
            else if (i >= 260 && i < 280) {
                dailySpotReturn = 0.005 + random.nextDouble() * 0.006;
                dailySkewChange = -0.001 - random.nextDouble() * 0.001;
            }
            // 3. Day 400-410: Bearish Divergence
            else if (i >= 400 && i < 410) {
                dailySpotReturn = 0.004 + random.nextDouble() * 0.004;
                dailySkewChange = 0.0012 + random.nextDouble() * 0.0013;
            }
            // Day 410-425: Correction
            else if (i >= 410 && i < 425) {
                dailySpotReturn = -0.007 - random.nextDouble() * 0.009;
                dailySkewChange = 0.0025 + random.nextDouble() * 0.0025;
            }
            else {
                dailySpotReturn = (random.nextDouble() - 0.47) * 0.015;
                if (dailySpotReturn > 0) {
                    dailySkewChange = -0.001 * (dailySpotReturn / 0.01) + (random.nextDouble() - 0.5) * 0.001;
                } else {
                    dailySkewChange = -1.2 * dailySpotReturn + (random.nextDouble() - 0.5) * 0.001;
                }
            }

            spot = Math.max(1000.0, spot * (1 + dailySpotReturn));
            skew = Math.max(0.01, Math.min(0.20, skew + dailySkewChange));

            double baseCallIv = 0.12 + (spot > 4500 ? 0.01 : 0);
            double putIv = baseCallIv + skew;
            double callIv = baseCallIv;

            double twoWeekSpotReturn = 0.0;
            double twoWeekSkewChange = 0.0;
            String signalType = "NONE";

            if (i >= 10) {
                SkewRecord pastRecord = records.get(i - 10);
                double pastSpot = pastRecord.getSpotPrice();
                double pastSkew = pastRecord.getSkew();

                twoWeekSpotReturn = (spot - pastSpot) / pastSpot;
                twoWeekSkewChange = skew - pastSkew;

                if (twoWeekSpotReturn > 0.02 && twoWeekSkewChange > 0.005) {
                    signalType = "BEARISH_DIVERGENCE";
                } else if (twoWeekSpotReturn < -0.02 && twoWeekSkewChange < -0.005) {
                    signalType = "BULLISH_DIVERGENCE";
                } else {
                    signalType = "CONFIRMING";
                }
            }

            records.add(new SkewRecord(
                dayTime,
                "SPX",
                spot,
                putIv,
                callIv,
                skew,
                twoWeekSpotReturn,
                twoWeekSkewChange,
                signalType
            ));
        }

        skewRecordRepository.saveAll(records);
        logger.info("Fallback mock seeding complete.");
    }
}

package com.skew.engine.service;

import com.skew.engine.domain.BacktestResult;
import com.skew.engine.domain.SkewRecord;
import com.skew.engine.domain.TradeLog;
import com.skew.engine.repository.SkewRecordRepository;
import com.skew.engine.repository.TradeLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class BacktesterService {

    private static final Logger logger = LoggerFactory.getLogger(BacktesterService.class);
    private static final double OPTION_ALLOCATION_PCT = 0.10; // Allocate 10% of portfolio capital to buying the ATM option

    private final SkewRecordRepository skewRecordRepository;
    private final TradeLogRepository tradeLogRepository;
    private final SkewSignalDetector skewSignalDetector;
    private final OptionPricingService optionPricingService;

    public BacktesterService(SkewRecordRepository skewRecordRepository,
                             TradeLogRepository tradeLogRepository,
                             SkewSignalDetector skewSignalDetector,
                             OptionPricingService optionPricingService) {
        this.skewRecordRepository = skewRecordRepository;
        this.tradeLogRepository = tradeLogRepository;
        this.skewSignalDetector = skewSignalDetector;
        this.optionPricingService = optionPricingService;
    }

    @Transactional
    public BacktestResult runBacktest(double initialCapital, int lookbackDays, int holdingPeriod, double skewThreshold) {
        logger.info("Running SPX Skew Backtest: Capital={}, Lookback={}d, Hold={}d, Threshold={}%",
            initialCapital, lookbackDays, holdingPeriod, String.format("%.2f", skewThreshold * 100));

        // Clear old trade logs from the database for this new backtest run
        tradeLogRepository.deleteAll();

        List<SkewRecord> records = skewRecordRepository.findAllByOrderByTimestampAsc();
        if (records.size() <= lookbackDays) {
            logger.warn("Not enough historical data to run backtest. Required: >{}, Found: {}", lookbackDays, records.size());
            return new BacktestResult(initialCapital, initialCapital, 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of());
        }

        double capital = initialCapital;
        double initialSpot = records.get(0).getSpotPrice();
        double finalSpot = records.get(records.size() - 1).getSpotPrice();
        double buyAndHoldReturn = (finalSpot - initialSpot) / initialSpot;

        List<TradeLog> closedTrades = new ArrayList<>();
        List<Double> equityCurve = new ArrayList<>();
        List<Double> spotCurve = new ArrayList<>();
        List<String> dates = new ArrayList<>();

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        TradeLog openTrade = null;
        int openTradeStartIndex = -1;

        // Initialize curves for days prior to running strategy
        for (int i = 0; i < lookbackDays; i++) {
            SkewRecord current = records.get(i);
            equityCurve.add(initialCapital);
            spotCurve.add(initialCapital * (current.getSpotPrice() / initialSpot));
            dates.add(current.getTimestamp().format(dateFormatter));
        }

        // Loop through history starting from lookback boundary
        for (int i = lookbackDays; i < records.size(); i++) {
            SkewRecord current = records.get(i);
            double currentSpot = current.getSpotPrice();
            double currentSkew = current.getSkew();
            dates.add(current.getTimestamp().format(dateFormatter));

            // 1. Process exit if trade is open
            if (openTrade != null) {
                int daysInTrade = i - openTradeStartIndex;
                if (daysInTrade >= holdingPeriod || i == records.size() - 1) {
                    SkewRecord entryRecord = records.get(openTradeStartIndex);
                    double entryIv = (entryRecord.getPutIv() + entryRecord.getCallIv()) / 2.0;
                    double exitIv = (current.getPutIv() + current.getCallIv()) / 2.0;
                    
                    String optionType = "SHORT".equalsIgnoreCase(openTrade.getPositionType()) ? "PUT" : "CALL";
                    
                    double tEntry = 30.0 / 365.0;
                    double tExit = Math.max(0.001, (30.0 - daysInTrade) / 365.0);
                    
                    double pEntry = optionPricingService.calculateBlackScholesPrice(
                        openTrade.getEntrySpot(), openTrade.getEntrySpot(), entryIv, tEntry, optionType);
                    double pExit = optionPricingService.calculateBlackScholesPrice(
                        currentSpot, openTrade.getEntrySpot(), exitIv, tExit, optionType);
                    
                    double optionReturn = (pExit - pEntry) / pEntry;
                    double tradePL = capital * OPTION_ALLOCATION_PCT * optionReturn;

                    // Close the trade
                    openTrade.close(current.getTimestamp(), currentSpot, currentSkew, capital);
                    // Override returns with option returns
                    openTrade.setTradeReturn(optionReturn);
                    openTrade.setProfitLoss(tradePL);

                    capital += tradePL;
                    
                    // Save closed trade to DB
                    tradeLogRepository.save(openTrade);
                    closedTrades.add(openTrade);
                    
                    logger.debug("Closed ATM option trade: {} at Spot {}, Option Return: {}%, PL: {}", 
                        openTrade.getPositionType(), currentSpot, String.format("%.2f", optionReturn * 100), String.format("%.2f", tradePL));
                    
                    openTrade = null;
                    openTradeStartIndex = -1;
                }
            }

            // 2. Process entry if no trade is open
            if (openTrade == null && i < records.size() - 1) {
                SkewRecord past = records.get(i - lookbackDays);
                double pastSpot = past.getSpotPrice();
                SkewSignal signal = skewSignalDetector.detect(
                    pastSpot, currentSpot, past.getSkew(), currentSkew, 0.02, skewThreshold);

                // Imran Lakha Divergence Trading Rules
                // Bearish Divergence (Spot up, Skew up) -> Short position
                if (signal.type() == SkewSignalType.BEARISH_DIVERGENCE) {
                    openTrade = new TradeLog(
                        current.getTimestamp(),
                        "Imran Lakha 2W Divergence",
                        "SHORT",
                        currentSpot,
                        currentSkew
                    );
                    openTradeStartIndex = i;
                    logger.debug("Entered SHORT trade at Spot {}, Skew {}%", currentSpot, String.format("%.2f", currentSkew * 100));
                }
                // Bullish Divergence (Spot down, Skew down) -> Long position
                else if (signal.type() == SkewSignalType.BULLISH_DIVERGENCE) {
                    openTrade = new TradeLog(
                        current.getTimestamp(),
                        "Imran Lakha 2W Divergence",
                        "LONG",
                        currentSpot,
                        currentSkew
                    );
                    openTradeStartIndex = i;
                    logger.debug("Entered LONG trade at Spot {}, Skew {}%", currentSpot, String.format("%.2f", currentSkew * 100));
                }
            }

            // 3. Compute daily equity point (compounding unrealized gains/losses)
            double dailyEquity = capital;
            if (openTrade != null) {
                SkewRecord entryRecord = records.get(openTradeStartIndex);
                double entryIv = (entryRecord.getPutIv() + entryRecord.getCallIv()) / 2.0;
                double currentIv = (current.getPutIv() + current.getCallIv()) / 2.0;
                
                String optionType = "SHORT".equalsIgnoreCase(openTrade.getPositionType()) ? "PUT" : "CALL";
                int daysElapsed = i - openTradeStartIndex;
                
                double tEntry = 30.0 / 365.0;
                double tCurrent = Math.max(0.001, (30.0 - daysElapsed) / 365.0);
                
                double pEntry = optionPricingService.calculateBlackScholesPrice(
                    openTrade.getEntrySpot(), openTrade.getEntrySpot(), entryIv, tEntry, optionType);
                double pCurrent = optionPricingService.calculateBlackScholesPrice(
                    currentSpot, openTrade.getEntrySpot(), currentIv, tCurrent, optionType);
                
                double optionReturn = (pCurrent - pEntry) / pEntry;
                dailyEquity = capital + (capital * OPTION_ALLOCATION_PCT * optionReturn);
            }
            equityCurve.add(dailyEquity);
            spotCurve.add(initialCapital * (currentSpot / initialSpot));
        }

        // 4. Calculate Risk Metrics
        double finalCapital = equityCurve.get(equityCurve.size() - 1);
        double totalReturn = (finalCapital - initialCapital) / initialCapital;

        // Win Rate
        int totalTradesCount = closedTrades.size();
        int winningTradesCount = 0;
        for (TradeLog t : closedTrades) {
            if (t.getProfitLoss() > 0) {
                winningTradesCount++;
            }
        }
        double winRate = totalTradesCount > 0 ? (double) winningTradesCount / totalTradesCount : 0.0;

        // Max Drawdown
        double maxDrawdown = 0.0;
        double peak = initialCapital;
        for (double equity : equityCurve) {
            if (equity > peak) {
                peak = equity;
            }
            double drawdown = (peak - equity) / peak;
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
            }
        }

        // Sharpe Ratio (using daily strategy returns)
        List<Double> dailyReturns = new ArrayList<>();
        for (int j = 1; j < equityCurve.size(); j++) {
            double prev = equityCurve.get(j - 1);
            double curr = equityCurve.get(j);
            dailyReturns.add((curr - prev) / (prev == 0 ? 1 : prev));
        }

        double averageDailyReturn = 0.0;
        for (double r : dailyReturns) {
            averageDailyReturn += r;
        }
        averageDailyReturn = dailyReturns.isEmpty() ? 0.0 : averageDailyReturn / dailyReturns.size();

        double variance = 0.0;
        for (double r : dailyReturns) {
            variance += Math.pow(r - averageDailyReturn, 2);
        }
        double dailyStdDev = dailyReturns.isEmpty() ? 0.0 : Math.sqrt(variance / dailyReturns.size());

        // Annualized Sharpe Ratio = (Mean / StdDev) * sqrt(252 trading days)
        double sharpeRatio = dailyStdDev == 0 ? 0.0 : (averageDailyReturn / dailyStdDev) * Math.sqrt(252);

        logger.info("Backtest Completed: Final Capital={}, Win Rate={}%", 
            String.format("%.2f", finalCapital), String.format("%.2f", winRate * 100));

        return new BacktestResult(
            initialCapital,
            finalCapital,
            totalReturn,
            buyAndHoldReturn,
            winRate,
            totalTradesCount,
            winningTradesCount,
            maxDrawdown,
            sharpeRatio,
            closedTrades,
            equityCurve,
            spotCurve,
            dates
        );
    }

}

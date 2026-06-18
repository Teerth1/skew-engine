package com.skew.engine.domain;

import java.util.List;

public record BacktestResult(
    double initialCapital,
    double finalCapital,
    double totalReturn,       // Strategy percentage return
    double buyAndHoldReturn,  // Benchmark SPX percentage return
    double winRate,           // Ratio of profitable trades to total trades
    int totalTrades,
    int winningTrades,
    double maxDrawdown,       // Peak-to-trough decline of strategy equity
    double sharpeRatio,       // Risk-adjusted return metric
    List<TradeLog> trades,    // Detail log of all entry and exits
    List<Double> equityCurve, // Strategy account value day-by-day
    List<Double> spotCurve,   // Underlying SPX price normalized to initial capital
    List<String> dates        // Day-by-day date labels (e.g. YYYY-MM-DD)
) {}

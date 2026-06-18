package com.skew.engine.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class MarketDataService {

    /**
     * Scrapes the current price of a stock/ticker from CNBC.
     * Results are cached for 5 minutes to reduce external API calls.
     * 
     * @param ticker The stock symbol (e.g. "NVDA")
     * @return The current price, or 0.0 if not found/error
     */
    @Cacheable(value = "stockPrices", key = "#ticker")
    public double getPrice(String ticker) {
        try {
            // URL for CNBC Quote Page (e.g. https://www.cnbc.com/quotes/NVDA)
            String url = "https://www.cnbc.com/quotes/" + ticker.toUpperCase();
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(5000) // Don't wait more than 5 seconds
                    .get();

            // Parse the price from the HTML
            String priceText = doc.select(".QuoteStrip-lastPrice").text();

            // Clean the text (Remove "$" symbols and "," separators)
            priceText = priceText.replace("$", "").replace(",", "");

            if (priceText.isEmpty()) {
                return 0.0;
            }

            return Double.parseDouble(priceText);

        } catch (Exception e) {
            System.out.println("⚠️ Could not fetch price for " + ticker + ": " + e.getMessage());
            return 0.0;
        }

    }

    public static class HistoricalDataPoint {
        private String date;
        private double closePrice;

        public HistoricalDataPoint(String data, double closePrice) {
            this.date = data;
            this.closePrice = closePrice;
        }

        public String getDate() {
            return date;
        }

        public double getClosePrice() {
            return closePrice;
        }
    }

}

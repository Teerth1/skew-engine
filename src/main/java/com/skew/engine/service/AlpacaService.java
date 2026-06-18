package com.skew.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Client for the Alpaca Paper Trading API.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Find the nearest ATM SPY option contract for a given SPX price and option type.</li>
 *   <li>Submit a market BUY order for an options contract (paper account).</li>
 *   <li>Close an open option position via a SELL-to-close market order.</li>
 *   <li>Fetch the latest bid-ask mid price of an option contract for P&amp;L tracking.</li>
 * </ul>
 *
 * <p><strong>Why SPY instead of SPX?</strong>
 * Cash-settled index ($SPX) options are not tradeable on Alpaca's free paper tier.
 * SPY (which tracks SPX at ~1/10 scale) is fully supported. We select the ATM strike
 * by dividing the SPX spot price by 10 and rounding to the nearest integer.</p>
 */
@Service
public class AlpacaService {

    private static final Logger logger = LoggerFactory.getLogger(AlpacaService.class);

    private static final String ORDERS_PATH      = "/v2/orders";
    private static final String POSITIONS_PATH   = "/v2/positions";
    private static final String CONTRACTS_PATH   = "/v2/options/contracts";
    // Alpaca Market Data API (separate host from trading API)
    private static final String MARKET_DATA_BASE = "https://data.alpaca.markets";
    private static final String OPTION_BARS_PATH = "/v1beta1/options/bars/latest";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper  = new ObjectMapper();

    @Value("${alpaca.api-key:}")
    private String apiKey;

    @Value("${alpaca.secret-key:}")
    private String secretKey;

    @Value("${alpaca.base-url:https://paper-api.alpaca.markets}")
    private String baseUrl;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if Alpaca API credentials are configured.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
            && secretKey != null && !secretKey.isBlank();
    }

    /**
     * Searches for the nearest ATM SPY option contract closest to 30 days from today.
     *
     * @param optionType "call" or "put"
     * @param spxPrice   current SPX index level (used to compute target SPY strike)
     * @return OCC-formatted option symbol string (e.g. "SPY260620P00500000"),
     *         or {@code null} if no suitable contract was found.
     */
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "alpacaService", fallbackMethod = "findAtmOptionContractFallback")
    @io.github.resilience4j.retry.annotation.Retry(name = "alpacaService", fallbackMethod = "findAtmOptionContractFallback")
    @SuppressWarnings("unchecked")
    public String findAtmOptionContract(String optionType, double spxPrice) {
        if (!isConfigured()) {
            logger.warn("Alpaca not configured — skipping contract lookup.");
            return null;
        }

        // SPY trades at ~1/10 of SPX. Round to nearest whole-dollar strike.
        double targetStrike = Math.round(spxPrice / 10.0);
        // Target expiration: ~30 days from now (use 21-45 day range to find something liquid)
        LocalDate today        = LocalDate.now();
        LocalDate minExp       = today.plusDays(14);
        LocalDate maxExp       = today.plusDays(60);
        LocalDate targetExp    = today.plusDays(30);
        DateTimeFormatter fmt  = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        String url = UriComponentsBuilder.fromUriString(baseUrl + CONTRACTS_PATH)
                .queryParam("underlying_symbols", "SPY")
                .queryParam("type", optionType.toLowerCase())
                .queryParam("strike_price_gte", String.valueOf((int)(targetStrike - 5)))
                .queryParam("strike_price_lte", String.valueOf((int)(targetStrike + 5)))
                .queryParam("expiration_date_gte", minExp.format(fmt))
                .queryParam("expiration_date_lte", maxExp.format(fmt))
                .queryParam("status", "active")
                .queryParam("limit", "50")
                .toUriString();

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders()), Map.class);

            if (response.getBody() == null) return null;

            List<Map<String, Object>> contracts =
                    (List<Map<String, Object>>) response.getBody().get("option_contracts");
            if (contracts == null || contracts.isEmpty()) {
                logger.warn("No active SPY {} contracts found near strike {}.", optionType, targetStrike);
                return null;
            }

            // Pick the contract with strike closest to target and expiration closest to 30d
            Map<String, Object> best = null;
            double bestScore = Double.MAX_VALUE;

            for (Map<String, Object> c : contracts) {
                if (!"true".equalsIgnoreCase(String.valueOf(c.get("tradable")))) continue;
                double strike = Double.parseDouble(String.valueOf(c.get("strike_price")));
                LocalDate exp = LocalDate.parse((String) c.get("expiration_date"), fmt);

                double strikeDelta = Math.abs(strike - targetStrike);
                double expDelta    = Math.abs(exp.toEpochDay() - targetExp.toEpochDay());
                double score       = strikeDelta * 2.0 + expDelta * 0.1;  // weight strike proximity more

                if (score < bestScore) {
                    bestScore = score;
                    best      = c;
                }
            }

            if (best == null) return null;
            String symbol = (String) best.get("symbol");
            logger.info("Selected ATM {} contract: {} (strike={}, expiration={})",
                    optionType, symbol, best.get("strike_price"), best.get("expiration_date"));
            return symbol;

        } catch (Exception e) {
            logger.error("Failed to look up SPY {} contracts: {}", optionType, e.getMessage());
            return null;
        }
    }

    /**
     * Submits a market BUY order for the given options contract on the paper account.
     *
     * @param optionSymbol OCC contract symbol (e.g. "SPY260620P00500000")
     * @param qty          number of contracts (1 by default)
     * @return Alpaca order ID string, or {@code null} on failure.
     */
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "alpacaService", fallbackMethod = "submitBuyOrderFallback")
    @io.github.resilience4j.retry.annotation.Retry(name = "alpacaService", fallbackMethod = "submitBuyOrderFallback")
    @SuppressWarnings("unchecked")
    public String submitBuyOrder(String optionSymbol, int qty) {
        if (!isConfigured()) {
            logger.warn("Alpaca not configured — skipping order submission for {}.", optionSymbol);
            return null;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("symbol",        optionSymbol);
        body.put("qty",           String.valueOf(qty));
        body.put("side",          "buy");
        body.put("type",          "market");
        body.put("time_in_force", "day");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + ORDERS_PATH,
                    HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders()),
                    Map.class);

            if (response.getBody() == null) return null;
            String orderId = (String) response.getBody().get("id");
            logger.info("Alpaca BUY order submitted for {} x{}: orderId={}", optionSymbol, qty, orderId);
            return orderId;

        } catch (HttpClientErrorException e) {
            logger.error("Alpaca rejected BUY order for {}: {} — {}", optionSymbol, e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            logger.error("Failed to submit BUY order for {}: {}", optionSymbol, e.getMessage());
            return null;
        }
    }

    /**
     * Submits a market SELL order to close an existing options position.
     *
     * @param optionSymbol OCC contract symbol
     * @param qty          number of contracts to sell
     * @return Alpaca order ID of the closing order, or {@code null} on failure.
     */
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "alpacaService", fallbackMethod = "submitSellOrderFallback")
    @io.github.resilience4j.retry.annotation.Retry(name = "alpacaService", fallbackMethod = "submitSellOrderFallback")
    @SuppressWarnings("unchecked")
    public String submitSellOrder(String optionSymbol, int qty) {
        if (!isConfigured()) {
            logger.warn("Alpaca not configured — skipping SELL for {}.", optionSymbol);
            return null;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("symbol",        optionSymbol);
        body.put("qty",           String.valueOf(qty));
        body.put("side",          "sell");
        body.put("type",          "market");
        body.put("time_in_force", "day");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + ORDERS_PATH,
                    HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders()),
                    Map.class);

            if (response.getBody() == null) return null;
            String orderId = (String) response.getBody().get("id");
            logger.info("Alpaca SELL order submitted for {} x{}: orderId={}", optionSymbol, qty, orderId);
            return orderId;

        } catch (HttpClientErrorException e) {
            logger.error("Alpaca rejected SELL order for {}: {} — {}", optionSymbol, e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            logger.error("Failed to submit SELL order for {}: {}", optionSymbol, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the latest trade/bar mid price for an option contract from Alpaca Market Data.
     *
     * @param optionSymbol OCC contract symbol
     * @return option price per share (not per contract), or {@code 0.0} if unavailable.
     */
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "alpacaService", fallbackMethod = "getLatestOptionPriceFallback")
    @io.github.resilience4j.retry.annotation.Retry(name = "alpacaService", fallbackMethod = "getLatestOptionPriceFallback")
    @SuppressWarnings("unchecked")
    public double getLatestOptionPrice(String optionSymbol) {
        if (!isConfigured()) return 0.0;

        String url = UriComponentsBuilder.fromUriString(MARKET_DATA_BASE + OPTION_BARS_PATH)
                .queryParam("symbols", optionSymbol)
                .toUriString();

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders()), Map.class);

            if (response.getBody() == null) return 0.0;
            Map<String, Object> bars = (Map<String, Object>) response.getBody().get("bars");
            if (bars == null || !bars.containsKey(optionSymbol)) return 0.0;

            Map<String, Object> bar = (Map<String, Object>) bars.get(optionSymbol);
            Number close = (Number) bar.get("c");
            return close != null ? close.doubleValue() : 0.0;

        } catch (Exception e) {
            logger.debug("Could not fetch latest price for {}: {}", optionSymbol, e.getMessage());
            return 0.0;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    public String findAtmOptionContractFallback(String optionType, double spxPrice, Throwable t) {
        logger.warn("Alpaca findAtmOptionContract failed: {}", t.getMessage());
        return null;
    }

    public String submitBuyOrderFallback(String optionSymbol, int qty, Throwable t) {
        logger.warn("Alpaca submitBuyOrder failed: {}", t.getMessage());
        return null;
    }

    public String submitSellOrderFallback(String optionSymbol, int qty, Throwable t) {
        logger.warn("Alpaca submitSellOrder failed: {}", t.getMessage());
        return null;
    }

    public double getLatestOptionPriceFallback(String optionSymbol, Throwable t) {
        logger.warn("Alpaca getLatestOptionPrice failed: {}", t.getMessage());
        return 0.0;
    }

    /** Builds required Alpaca authentication headers. */
    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("APCA-API-KEY-ID",     apiKey);
        headers.set("APCA-API-SECRET-KEY", secretKey);
        return headers;
    }
}

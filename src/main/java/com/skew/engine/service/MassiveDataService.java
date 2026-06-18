package com.skew.engine.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MassiveDataService (Refactored for Yahoo Finance)
 * Fetches real-time option data from Yahoo Finance's unofficial API.
 * Includes explicit session management (Cookie + Crumb) to bypass 401 errors.
 */
@Service
public class MassiveDataService {

    private final RestClient restClient;
    private static final String YAHOO_BASE = "https://query1.finance.yahoo.com/v7/finance/options/";
    private static final String CRUMB_URL = "https://query1.finance.yahoo.com/v1/test/getcrumb";
    private static final String COOKIE_URL = "https://fc.yahoo.com";

    // Session State
    private String currentCookie = null;
    private String currentCrumb = null;

    public MassiveDataService(RestClient.Builder restClientBuilder) {
        // Yahoo requires a User-Agent to avoid 403 Forbidden
        this.restClient = restClientBuilder
                .baseUrl(YAHOO_BASE)
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build();
    }

    /**
     * DTO for Option Snapshot used by DiscordBotService
     */
    @lombok.Data
    public static class OptionSnapshot {
        private double bid;
        private double ask;
        private int volume;
        private int openInterest;
        private double strike;

        public OptionSnapshot(double bid, double ask, int volume, int openInterest, double strike) {
            this.bid = bid;
            this.ask = ask;
            this.volume = volume;
            this.openInterest = openInterest;
            this.strike = strike;
        }

        public double getBid() {
            return bid;
        }

        public double getAsk() {
            return ask;
        }

        public int getVolume() {
            return volume;
        }

        public int getOpenInterest() {
            return openInterest;
        }

        public double getStrike() {
            return strike;
        }
    }

    /**
     * Initializes or refreshes Yahoo session (Cookie + Crumb).
     * This is required because Yahoo returns 401 if you don't have a valid "crumb".
     */
    private synchronized void ensureSession() {
        if (currentCrumb != null && currentCookie != null)
            return;

        System.out.println("DEBUG: initializing Yahoo Finance Session...");
        try {
            CookieManager cookieManager = new CookieManager();
            HttpClient client = HttpClient.newBuilder()
                    .cookieHandler(cookieManager)
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(COOKIE_URL))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .GET()
                    .build();

            client.send(request, HttpResponse.BodyHandlers.discarding());

            CookieStore cookieStore = cookieManager.getCookieStore();
            List<HttpCookie> cookies = cookieStore.getCookies();

            if (cookies.isEmpty()) {
                System.err.println("ERROR: Failed to obtain Yahoo Cookie.");
                return;
            }

            this.currentCookie = cookies.stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));

            System.out.println("DEBUG: Got Yahoo Cookie: " + (currentCookie.length() > 10 ? "Yes" : "No"));

            HttpRequest crumbRequest = HttpRequest.newBuilder()
                    .uri(URI.create(CRUMB_URL))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .GET()
                    .build();

            HttpResponse<String> crumbResponse = client.send(crumbRequest, HttpResponse.BodyHandlers.ofString());

            if (crumbResponse.statusCode() == 200) {
                this.currentCrumb = crumbResponse.body();
                System.out.println("DEBUG: Got Yahoo Crumb: " + currentCrumb);
            } else {
                System.err.println("ERROR: Failed to get Crumb. Status: " + crumbResponse.statusCode());
            }

        } catch (Exception e) {
            System.err.println("ERROR: Failed to refresh Yahoo session: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Fetch a specific option contract snapshot using Yahoo Finance.
     */
    public Optional<OptionSnapshot> getOptionSnapshot(String ticker, double strike, String type, int days) {
        ensureSession();

        try {
            YahooOptionsResponse metadata = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(ticker)
                            .queryParam("crumb", currentCrumb)
                            .build())
                    .header("Cookie", currentCookie)
                    .retrieve()
                    .body(YahooOptionsResponse.class);

            if (metadata == null || metadata.optionChain == null || metadata.optionChain.result.isEmpty()) {
                System.out.println("DEBUG: No data found for ticker " + ticker);
                return Optional.empty();
            }

            List<Long> expirations = metadata.optionChain.result.get(0).expirationDates;
            if (expirations == null || expirations.isEmpty()) {
                System.out.println("DEBUG: No expiration dates found for " + ticker);
                return Optional.empty();
            }

            long bestTimestamp = findClosestExpiration(expirations, days);
            System.out.println("DEBUG: Requested " + days + " days. Using expiration timestamp: " + bestTimestamp
                    + " (" + Instant.ofEpochSecond(bestTimestamp).atZone(ZoneId.systemDefault()).toLocalDate() + ")");

            YahooOptionsResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(ticker)
                            .queryParam("date", bestTimestamp)
                            .queryParam("crumb", currentCrumb)
                            .build())
                    .header("Cookie", currentCookie)
                    .retrieve()
                    .body(YahooOptionsResponse.class);

            if (response == null || response.optionChain == null || response.optionChain.result.isEmpty()) {
                return Optional.empty();
            }

            YahooOptionsResponse.OptionData chainData = response.optionChain.result.get(0).options.get(0);
            List<YahooOptionsResponse.Contract> contracts = type.equalsIgnoreCase("call") ? chainData.calls
                    : chainData.puts;

            if (contracts == null || contracts.isEmpty()) {
                System.out.println("DEBUG: No " + type + " contracts found for this date.");
                return Optional.empty();
            }

            for (YahooOptionsResponse.Contract contract : contracts) {
                if (Math.abs(contract.strike - strike) < 0.01) {
                    return Optional.of(new OptionSnapshot(
                            contract.bid,
                            contract.ask,
                            contract.volume,
                            contract.openInterest,
                            contract.strike));
                }
            }

            System.out.println("DEBUG: Strike " + strike + " not found in chain for " + ticker);

        } catch (Exception e) {
            System.err.println("Error fetching Yahoo data: " + e.getMessage());
            if (e.getMessage().contains("401")) {
                this.currentCookie = null;
                this.currentCrumb = null;
            }
            e.printStackTrace();
        }

        return Optional.empty();
    }

    private long findClosestExpiration(List<Long> expirations, int targetDays) {
        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.plusDays(targetDays);

        long bestTimestamp = expirations.get(0);
        long minDiff = Long.MAX_VALUE;

        for (Long ts : expirations) {
            LocalDate expDate = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate();
            long diff = Math.abs(ChronoUnit.DAYS.between(targetDate, expDate));

            if (diff < minDiff) {
                minDiff = diff;
                bestTimestamp = ts;
            }
        }
        return bestTimestamp;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private static class YahooOptionsResponse {
        public OptionChain optionChain;

        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        public static class OptionChain {
            public List<Result> result;
        }

        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        public static class Result {
            public Quote quote;
            public List<Long> expirationDates;
            public List<OptionData> options;
        }

        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        public static class Quote {
            public String language;
            public String region;
            public String quoteType;
            public String currency;
            public double regularMarketPrice;
        }

        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        public static class OptionData {
            public long expirationDate;
            public List<Contract> calls;
            public List<Contract> puts;
        }

        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        public static class Contract {
            public double strike;
            public double lastPrice;
            public double bid;
            public double ask;
            public int volume;
            public int openInterest;
            public double impliedVolatility;
            public boolean inTheMoney;
            public String contractSymbol;
        }
    }
}

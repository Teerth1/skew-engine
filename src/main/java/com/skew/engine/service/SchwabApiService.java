package com.skew.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class SchwabApiService {

    private static final Logger logger = LoggerFactory.getLogger(SchwabApiService.class);
    private static final String TOKEN_URL = "https://api.schwabapi.com/v1/oauth/token";
    private static final String AUTH_BASE_URL = "https://api.schwabapi.com/v1/oauth/authorize";
    private static final String PRICE_HISTORY_URL = "https://api.schwabapi.com/marketdata/v1/pricehistory";
    private static final String CHAINS_URL = "https://api.schwabapi.com/marketdata/v1/chains";
    private static final String TOKEN_FILE_PATH = "schwab-tokens.json";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${schwab.client-id:}")
    private String configClientId;

    @Value("${schwab.client-secret:}")
    private String configClientSecret;

    @Value("${schwab.redirect-uri:https://127.0.0.1}")
    private String configRedirectUri;

    private String clientId;
    private String clientSecret;
    private String redirectUri;

    private String accessToken;
    private String refreshToken;
    private long expiresAt; // epoch milliseconds

    @PostConstruct
    public void init() {
        this.clientId = configClientId;
        this.clientSecret = configClientSecret;
        this.redirectUri = configRedirectUri;
        loadTokens();
    }

    public synchronized void updateCredentials(String clientId, String clientSecret, String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        logger.info("Schwab API credentials updated in-memory.");
    }

    public String getClientId() { return clientId; }
    public String getClientSecret() { return clientSecret; }
    public String getRedirectUri() { return redirectUri; }

    public String getAuthorizationUrl() {
        if (clientId == null || clientId.trim().isEmpty()) {
            return "";
        }
        try {
            return AUTH_BASE_URL + "?response_type=code&client_id=" + 
                URLEncoder.encode(clientId, StandardCharsets.UTF_8.toString()) + 
                "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            logger.error("Failed to encode Schwab authorization URL parameters", e);
            return "";
        }
    }

    public synchronized boolean isAuthorized() {
        return accessToken != null && (refreshToken != null || System.currentTimeMillis() < expiresAt);
    }

    public synchronized Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("configured", clientId != null && !clientId.trim().isEmpty() && clientSecret != null && !clientSecret.trim().isEmpty());
        status.put("authorized", isAuthorized());
        status.put("clientId", clientId);
        status.put("redirectUri", redirectUri);
        if (isAuthorized()) {
            status.put("expiresInSeconds", Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000));
        } else {
            status.put("expiresInSeconds", 0);
        }
        return status;
    }

    public synchronized void exchangeCodeForTokens(String code) throws Exception {
        logger.info("Exchanging authorization code for tokens with Schwab...");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        
        // Schwab requires HTTP Basic Auth using AppKey:AppSecret base64 encoded
        String authString = clientId + ":" + clientSecret;
        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encodedAuth);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(TOKEN_URL, request, Map.class);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            parseAndSaveTokens(response.getBody());
            logger.info("Successfully authenticated with Schwab.");
        } else {
            throw new RuntimeException("Failed token exchange: HTTP " + response.getStatusCode());
        }
    }

    public synchronized void refreshAccessToken() {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            logger.warn("Cannot refresh access token: Refresh token is missing.");
            return;
        }
        logger.info("Refreshing Schwab access token...");
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            
            String authString = clientId + ":" + clientSecret;
            String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + encodedAuth);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "refresh_token");
            body.add("refresh_token", refreshToken);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(TOKEN_URL, request, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                parseAndSaveTokens(response.getBody());
                logger.info("Schwab access token refreshed successfully.");
            } else {
                logger.error("Failed to refresh token: HTTP {}", response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Error occurred while refreshing Schwab access token: {}", e.getMessage());
        }
    }

    public synchronized String getAccessToken() {
        if (accessToken == null) {
            return null;
        }
        // If token expires in less than 2 minutes, refresh it first
        if (System.currentTimeMillis() + 120000 > expiresAt) {
            refreshAccessToken();
        }
        return accessToken;
    }

    private void parseAndSaveTokens(Map<String, Object> body) {
        this.accessToken = (String) body.get("access_token");
        // Schwab might not always send a new refresh token, so keep the old one if it is missing
        if (body.containsKey("refresh_token")) {
            this.refreshToken = (String) body.get("refresh_token");
        }
        
        // expires_in is in seconds
        int expiresIn = body.containsKey("expires_in") ? ((Number) body.get("expires_in")).intValue() : 1800;
        this.expiresAt = System.currentTimeMillis() + (expiresIn * 1000L);

        saveTokens();
    }

    private void saveTokens() {
        try {
            Map<String, Object> tokenData = new HashMap<>();
            tokenData.put("accessToken", accessToken);
            tokenData.put("refreshToken", refreshToken);
            tokenData.put("expiresAt", expiresAt);
            tokenData.put("clientId", clientId);
            tokenData.put("clientSecret", clientSecret);
            tokenData.put("redirectUri", redirectUri);

            objectMapper.writeValue(new File(TOKEN_FILE_PATH), tokenData);
        } catch (IOException e) {
            logger.error("Failed to save Schwab tokens locally: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized void importRefreshToken(String clientId, String clientSecret, String redirectUri, String refreshToken) throws Exception {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.refreshToken = refreshToken;
        this.accessToken = null;
        this.expiresAt = 0L;
        
        logger.info("Importing manual Schwab Refresh Token...");
        
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            
            String authString = clientId + ":" + clientSecret;
            String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + encodedAuth);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "refresh_token");
            body.add("refresh_token", refreshToken);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(TOKEN_URL, request, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                parseAndSaveTokens(response.getBody());
                logger.info("Manual Refresh Token imported and verified successfully.");
            } else {
                throw new RuntimeException("Failed token refresh: HTTP " + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Failed to verify imported Schwab Refresh Token: {}", e.getMessage());
            this.refreshToken = null;
            saveTokens();
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private void loadTokens() {
        File tokenFile = new File(TOKEN_FILE_PATH);
        if (!tokenFile.exists()) {
            tokenFile = new File("schwab_tokens.json");
            if (!tokenFile.exists()) {
                return;
            }
        }
        logger.info("Loading saved Schwab tokens from local storage ({})...", tokenFile.getName());
        try {
            Map<String, Object> tokenData = objectMapper.readValue(tokenFile, Map.class);
            this.accessToken = tokenData.containsKey("accessToken") ? (String) tokenData.get("accessToken") : (String) tokenData.get("access_token");
            this.refreshToken = tokenData.containsKey("refreshToken") ? (String) tokenData.get("refreshToken") : (String) tokenData.get("refresh_token");
            
            Object expiresVal = tokenData.containsKey("expiresAt") ? tokenData.get("expiresAt") : tokenData.get("expires_at");
            if (expiresVal == null) {
                expiresVal = tokenData.get("updated_at");
            }
            this.expiresAt = expiresVal instanceof Number ? ((Number) expiresVal).longValue() : 0L;
            
            String fileClientId = tokenData.containsKey("clientId") ? (String) tokenData.get("clientId") : (String) tokenData.get("client_id");
            String fileClientSecret = tokenData.containsKey("clientSecret") ? (String) tokenData.get("clientSecret") : (String) tokenData.get("client_secret");
            String fileRedirectUri = tokenData.containsKey("redirectUri") ? (String) tokenData.get("redirectUri") : (String) tokenData.get("redirect_uri");
            
            if ((clientId == null || clientId.trim().isEmpty()) && fileClientId != null) {
                this.clientId = fileClientId;
                this.clientSecret = fileClientSecret;
                this.redirectUri = fileRedirectUri != null ? fileRedirectUri : "https://127.0.0.1";
            }
        } catch (IOException e) {
            logger.error("Failed to load Schwab tokens locally: {}", e.getMessage());
        }
    }

    public Map<String, Object> getHistoricalPrice(String symbol, int days) {
        String token = getAccessToken();
        if (token == null) {
            throw new IllegalStateException("Schwab API not authenticated.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // Map days to Schwab periodType/period/frequencyType
        // Schwab doesn't have a direct "days" parameter but we can query by ytd or years.
        // E.g. periodType=year, period=2, frequencyType=daily, frequency=1 gives 252 trading days.
        // We will fetch 2 years to get > 500 records.
        String url = PRICE_HISTORY_URL + "?symbol=" + symbol +
                     "&periodType=year&period=2&frequencyType=daily&frequency=1";

        logger.info("Calling Schwab Price History API for: {}", symbol);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
        return response.getBody();
    }

    public Map<String, Object> getOptionsChain(String symbol) {
        String token = getAccessToken();
        if (token == null) {
            throw new IllegalStateException("Schwab API not authenticated.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // Options chain query parameters:
        // symbol: $SPX
        // strikeCount: 60 (to capture 95% and 105% strikes)
        // strategy: SINGLE
        // range: OTM (Out-of-the-money options contain skew data)
        String url = CHAINS_URL + "?symbol=" + symbol + "&strikeCount=60&strategy=SINGLE&range=OTM";

        logger.debug("Calling Schwab Option Chains API for: {}", symbol);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
        return response.getBody();
     }

    public Optional<SPXStraddle> getSpxStraddle(int dte) {
        String token = getAccessToken();

        if (token == null) {
            logger.error("No valid Schwab access token available after refresh attempt");
            return Optional.empty();
        }

        try {
            // Calculate expiration date
            LocalDate expDate = LocalDate.now().plusDays(dte);
            String expDateStr = expDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

            // Build request URL for SPX option chain
            String url = "https://api.schwabapi.com/marketdata/v1/chains?symbol=$SPX" +
                    "&contractType=ALL" +
                    "&strikeCount=5" + // Get 5 strikes around ATM
                    "&fromDate=" + expDateStr +
                    "&toDate=" + expDateStr;

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                return parseOptionChain(response.getBody());
            } else {
                logger.error("Schwab API error: {} - {}", response.getStatusCode(), response.getBody());
                return Optional.empty();
            }
        } catch (Exception e) {
            logger.error("Error fetching SPX options from Schwab", e);
            return Optional.empty();
        }
    }

    private Optional<SPXStraddle> parseOptionChain(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);

            double underlyingPrice = root.path("underlyingPrice").asDouble();

            // Find the closest strike to underlying price
            JsonNode callExpDateMap = root.path("callExpDateMap");
            JsonNode putExpDateMap = root.path("putExpDateMap");

            if (callExpDateMap.isMissingNode() || putExpDateMap.isMissingNode()) {
                logger.warn("No option data found in Schwab response");
                return Optional.empty();
            }

            // Get the first expiration date's strikes
            String firstExpKey = callExpDateMap.fieldNames().next();
            JsonNode callStrikes = callExpDateMap.path(firstExpKey);
            JsonNode putStrikes = putExpDateMap.path(firstExpKey);

            // Find ATM strike (closest to underlying)
            double atmStrike = Math.round(underlyingPrice / 5.0) * 5.0;

            // Get call and put at ATM strike
            JsonNode callOption = findStrike(callStrikes, atmStrike);
            JsonNode putOption = findStrike(putStrikes, atmStrike);

            if (callOption == null || putOption == null) {
                logger.warn("Could not find ATM strike {} in option chain", atmStrike);
                return Optional.empty();
            }

            double callBid = callOption.path("bid").asDouble();
            double callAsk = callOption.path("ask").asDouble();
            double putBid = putOption.path("bid").asDouble();
            double putAsk = putOption.path("ask").asDouble();

            // Extract implied volatility from each contract
            double callIV = callOption.path("volatility").asDouble();
            double putIV = putOption.path("volatility").asDouble();

            // Parse actual expiration date from the key (format: "2026-01-05:3")
            String actualExpDate = firstExpKey.split(":")[0];

            return Optional.of(new SPXStraddle(
                    callBid, callAsk, putBid, putAsk,
                    underlyingPrice, atmStrike, actualExpDate,
                    callIV, putIV));

        } catch (Exception e) {
            logger.error("Error parsing Schwab option chain", e);
            return Optional.empty();
        }
    }

    private JsonNode findStrike(JsonNode strikes, double targetStrike) {
        var fields = strikes.fields();
        double minDiff = Double.MAX_VALUE;
        JsonNode closest = null;

        while (fields.hasNext()) {
            var entry = fields.next();
            double strike = Double.parseDouble(entry.getKey());
            double diff = Math.abs(strike - targetStrike);

            if (diff < minDiff) {
                minDiff = diff;
                closest = entry.getValue().get(0); // First contract at this strike
            }
        }
        return closest;
    }

    private static String toSchwabSymbol(String ticker) {
        String upper = ticker.trim().toUpperCase();
        if (upper.equals("SPX") || upper.equals("SPXW")) {
            return "$" + upper;
        }
        return upper;
    }

    public java.util.Optional<JsonNode> getFullOptionChain(String ticker) {
        String token = getAccessToken();
        if (token == null) {
            logger.error("No valid Schwab token available for getFullOptionChain");
            return java.util.Optional.empty();
        }

        String symbol = toSchwabSymbol(ticker);
        try {
            String encodedSymbol = java.net.URLEncoder.encode(symbol, StandardCharsets.UTF_8.toString());
            // Dynamic strike count to prevent "Body buffer overflow" on heavy indices like SPX
            int strikeCount = (symbol.startsWith("$") || symbol.equals("SPY") || symbol.equals("QQQ") || symbol.equals("IWM")) ? 50 : 100;

            String url = "https://api.schwabapi.com/marketdata/v1/chains"
                    + "?symbol=" + encodedSymbol
                    + "&contractType=ALL"
                    + "&strikeCount=" + strikeCount
                    + "&includeUnderlyingQuote=true"; // embeds spot price in response

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode root = objectMapper.readTree(response.getBody());
                logger.info("Fetched full option chain for {} ({} status)", symbol, response.getStatusCode());
                return java.util.Optional.of(root);
            } else {
                logger.error("Schwab option chain error for {}: {} — {}", symbol, response.getStatusCode(), response.getBody());
                return java.util.Optional.empty();
            }
        } catch (Exception e) {
            logger.error("Error fetching option chain for {}", symbol, e);
            return java.util.Optional.empty();
        }
    }

    public static class SPXStraddle {
        private final double callBid;
        private final double callAsk;
        private final double putBid;
        private final double putAsk;
        private final double underlyingPrice;
        private final double strike;
        private final String expirationDate;
        private final double callIV;
        private final double putIV;

        public SPXStraddle(double callBid, double callAsk, double putBid, double putAsk,
                double underlyingPrice, double strike, String expirationDate,
                double callIV, double putIV) {
            this.callBid = callBid;
            this.callAsk = callAsk;
            this.putBid = putBid;
            this.putAsk = putAsk;
            this.underlyingPrice = underlyingPrice;
            this.strike = strike;
            this.expirationDate = expirationDate;
            this.callIV = callIV;
            this.putIV = putIV;
        }

        public double getCallMid() {
            return (callBid + callAsk) / 2;
        }

        public double getPutMid() {
            return (putBid + putAsk) / 2;
        }

        public double getStraddlePrice() {
            return getCallMid() + getPutMid();
        }

        public double getUnderlyingPrice() {
            return underlyingPrice;
        }

        public double getStrike() {
            return strike;
        }

        public String getExpirationDate() {
            return expirationDate;
        }

        public double getCallIV() {
            return callIV;
        }

        public double getPutIV() {
            return putIV;
        }

        public double getAverageIV() {
            return (callIV + putIV) / 2.0;
        }
    }
}

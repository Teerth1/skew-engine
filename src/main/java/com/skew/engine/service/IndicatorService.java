package com.skew.engine.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service for calling the Python Indicators Lambda API.
 * Implements secure API key authentication and structured logging.
 */
@Service
public class IndicatorService {

    private static final Logger logger = LoggerFactory.getLogger(IndicatorService.class);

    // AWS Lambda API Gateway URL
    private static final String API_URL = "https://2hs6pnvedh.execute-api.us-east-2.amazonaws.com";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // API Key loaded from environment variable (set in Railway)
    @Value("${LAMBDA_API_KEY:}")
    private String apiKey;

    /**
     * Get all mean reversion indicators for a ticker.
     * 
     * @param ticker Stock symbol (e.g., "SPY")
     * @return Map containing zscore, half_life, acf, signal
     */
    public Map<String, Object> getAllIndicators(String ticker) throws Exception {
        String url = API_URL + "/all?ticker=" + ticker;

        logger.info("Calling Lambda API: ticker={}", ticker);

        // Build request with API key header for authentication
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .GET();

        // Add API key if configured
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("X-API-Key", apiKey);
        }

        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Log response for observability
        logger.info("Lambda response: ticker={}, status={}", ticker, response.statusCode());

        if (response.statusCode() == 401) {
            logger.error("Lambda API authentication failed - check LAMBDA_API_KEY");
            throw new RuntimeException("Lambda API authentication failed");
        }

        if (response.statusCode() != 200) {
            logger.error("Lambda API error: {}", response.body());
            throw new RuntimeException("Lambda API error: " + response.body());
        }

        return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
        });
    }
}

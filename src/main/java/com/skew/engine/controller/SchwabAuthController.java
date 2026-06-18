package com.skew.engine.controller;

import com.skew.engine.service.SchwabApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/schwab")
public class SchwabAuthController {

    private static final Logger logger = LoggerFactory.getLogger(SchwabAuthController.class);
    private final SchwabApiService schwabApiService;

    public SchwabAuthController(SchwabApiService schwabApiService) {
        this.schwabApiService = schwabApiService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(schwabApiService.getStatus());
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(
            @RequestParam String clientId,
            @RequestParam String clientSecret,
            @RequestParam String redirectUri) {
        
        schwabApiService.updateCredentials(clientId, clientSecret, redirectUri);
        logger.info("Schwab credentials configured via controller.");
        return ResponseEntity.ok(schwabApiService.getStatus());
    }

    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl() {
        String authUrl = schwabApiService.getAuthorizationUrl();
        return ResponseEntity.ok(Map.of("url", authUrl));
    }

    @PostMapping("/authorize")
    public ResponseEntity<Map<String, Object>> authorize(@RequestParam String code) {
        try {
            // If the user pasted the entire redirected URL, extract only the code parameter
            String authCode = code;
            if (code.contains("code=")) {
                int start = code.indexOf("code=") + 5;
                int end = code.indexOf("&", start);
                if (end == -1) {
                    authCode = code.substring(start);
                } else {
                    authCode = code.substring(start, end);
                }
            }
            
            schwabApiService.exchangeCodeForTokens(authCode);
            return ResponseEntity.ok(schwabApiService.getStatus());
        } catch (Exception e) {
            logger.error("Schwab authorization failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Authorization failed: " + e.getMessage(),
                    "configured", schwabApiService.getStatus().get("configured"),
                    "authorized", false
            ));
        }
    }

    @PostMapping("/import-token")
    public ResponseEntity<Map<String, Object>> importToken(
            @RequestParam String clientId,
            @RequestParam String clientSecret,
            @RequestParam String redirectUri,
            @RequestParam String refreshToken) {
        
        try {
            schwabApiService.importRefreshToken(clientId, clientSecret, redirectUri, refreshToken);
            return ResponseEntity.ok(schwabApiService.getStatus());
        } catch (Exception e) {
            logger.error("Token import failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Import failed: " + e.getMessage(),
                    "configured", schwabApiService.getStatus().get("configured"),
                    "authorized", false
            ));
        }
    }
}

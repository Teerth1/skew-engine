package com.skew.engine.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TradingAgentsClientServiceTest {

    private TradingAgentsClientService service;

    @BeforeEach
    void setUp() {
        service = new TradingAgentsClientService();
    }

    @Test
    void testDisabledReturnsEmpty() {
        service.setEnabled(false);
        TradeIntent intent = new TradeIntent("BUY_PUT_SKEW_SPIKE", "put", "SPY260620P00580000", 1, 5800.0, 0.25, 0.15, -0.01, 0.05, LocalDateTime.now());
        Optional<AgentDecision> decision = service.requestAnalysis(intent, Collections.emptyList(), 0);
        assertTrue(decision.isEmpty(), "Disabled service should return empty optional");
    }

    @Test
    void testFallbackReturnsEmpty() {
        TradeIntent intent = new TradeIntent("BUY_PUT_SKEW_SPIKE", "put", "SPY260620P00580000", 1, 5800.0, 0.25, 0.15, -0.01, 0.05, LocalDateTime.now());
        Optional<AgentDecision> decision = service.requestAnalysisFallback(intent, Collections.emptyList(), 0, new RuntimeException("Connection refused"));
        assertTrue(decision.isEmpty(), "Fallback should return empty optional");
    }

    @Test
    void testToggleEnabled() {
        assertFalse(service.isEnabled());
        service.setEnabled(true);
        assertTrue(service.isEnabled());
        service.setServiceUrl("http://test-url:8000/analyze");
        assertEquals("http://test-url:8000/analyze", service.getServiceUrl());
    }
}

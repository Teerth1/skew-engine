package com.skew.engine.service;

import com.skew.engine.agent.TradingAgentTools;
import com.skew.engine.domain.LivePosition;
import com.skew.engine.repository.LivePositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

class AgentDecisionServiceTest {

    private ChatClient.Builder chatClientBuilder;
    private ChatClient chatClient;
    private TradingAgentTools tradingAgentTools;
    private LivePositionRepository livePositionRepository;
    private TradeMemoryService tradeMemoryService;
    private TradingAgentsClientService tradingAgentsClientService;
    private NewsService newsService;
    private AgentDecisionService agentDecisionService;

    @BeforeEach
    void setUp() {
        chatClientBuilder = Mockito.mock(ChatClient.Builder.class);
        chatClient = Mockito.mock(ChatClient.class);
        when(chatClientBuilder.defaultTools(any(Object[].class))).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);

        tradingAgentTools = Mockito.mock(TradingAgentTools.class);
        livePositionRepository = Mockito.mock(LivePositionRepository.class);
        tradeMemoryService = Mockito.mock(TradeMemoryService.class);
        tradingAgentsClientService = Mockito.mock(TradingAgentsClientService.class);
        newsService = Mockito.mock(NewsService.class);

        agentDecisionService = new AgentDecisionService(
                chatClientBuilder, tradingAgentTools, livePositionRepository,
                tradeMemoryService, tradingAgentsClientService, newsService
        );
    }

    @Test
    void decideReturnsExternalDecisionWhenTradingAgentsClientIsEnabled() {
        TradeIntent intent = new TradeIntent("BUY_PUT_SKEW_SPIKE", "put", "SPY260620P00580000", 1, 5800.0, 0.25, 0.15, -0.01, 0.05, LocalDateTime.now());
        when(tradingAgentsClientService.isEnabled()).thenReturn(true);
        when(newsService.getRecentNewsAsOf(any(), any())).thenReturn(Collections.emptyList());
        when(livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN")).thenReturn(Collections.emptyList());

        AgentDecision expectedDecision = new AgentDecision(AgentRating.BUY, 0.85, "LangGraph Buy Signal", "No risk");
        when(tradingAgentsClientService.requestAnalysis(any(), anyList(), anyInt())).thenReturn(Optional.of(expectedDecision));

        AgentDecision decision = agentDecisionService.decide(intent);

        assertThat(decision).isEqualTo(expectedDecision);
    }

    @Test
    void decideFallsBackToRuleBasedWhenChatClientThrowsException() {
        TradeIntent intent = new TradeIntent("BUY_PUT_SKEW_SPIKE", "put", "SPY260620P00580000", 1, 5800.0, 0.25, 0.15, -0.01, 0.05, LocalDateTime.now());
        when(tradingAgentsClientService.isEnabled()).thenReturn(false);
        when(tradeMemoryService.findSimilarTrades(intent)).thenReturn(Collections.emptyList());

        // Simulate ChatClient failure / unconfigured API key
        when(chatClient.prompt()).thenThrow(new RuntimeException("Gemini API error"));
        when(livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN")).thenReturn(Collections.emptyList());

        AgentDecision decision = agentDecisionService.decide(intent);

        assertThat(decision.rating()).isEqualTo(AgentRating.BUY);
        assertThat(decision.confidence()).isEqualTo(0.55);
        assertThat(decision.rationale()).contains("Fallback rule-based decision");
    }

    @Test
    void decideFallsBackToHoldWhenOpenPositionsExistAndAiFails() {
        TradeIntent intent = new TradeIntent("BUY_PUT_SKEW_SPIKE", "put", "SPY260620P00580000", 1, 5800.0, 0.25, 0.15, -0.01, 0.05, LocalDateTime.now());
        when(tradingAgentsClientService.isEnabled()).thenReturn(false);
        when(chatClient.prompt()).thenThrow(new RuntimeException("Gemini API error"));
        when(livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN")).thenReturn(List.of(new LivePosition()));

        AgentDecision decision = agentDecisionService.decide(intent);

        assertThat(decision.rating()).isEqualTo(AgentRating.HOLD);
        assertThat(decision.confidence()).isEqualTo(0.55);
    }
}

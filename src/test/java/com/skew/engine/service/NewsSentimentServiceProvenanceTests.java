package com.skew.engine.service;

import com.skew.engine.domain.DataCategory;
import com.skew.engine.domain.NewsArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewsSentimentServiceProvenanceTests {

    private NewsSentimentService newsSentimentService;

    @BeforeEach
    void setUp() {
        // Setup ChatClient mock to be safe (though it shouldn't be called if articles are filtered out)
        ChatClient.Builder builder = Mockito.mock(ChatClient.Builder.class);
        ChatClient chatClient = Mockito.mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);

        newsSentimentService = new NewsSentimentService(builder);
    }

    // Helper mock function to mimic mockito in setup
    private static <T> org.mockito.stubbing.OngoingStubbing<T> when(T methodCall) {
        return Mockito.when(methodCall);
    }

    @Test
    void classifyFiltersOutDisallowedDataCategories() {
        // Given an article with a disallowed category (e.g. PRICE_OBSERVATION)
        NewsArticle articlePrice = new NewsArticle(
                "AAPL", "Price dropped", "Yahoo", "url", LocalDateTime.now(),
                "Summary", "Yahoo", "Neutral", DataCategory.PRICE_OBSERVATION, true);

        // When we classify, it should filter out the article and return NEUTRAL
        SentimentBand sentiment = newsSentimentService.classify(List.of(articlePrice), "AAPL");

        assertThat(sentiment).isEqualTo(SentimentBand.NEUTRAL);
    }
}

package com.skew.engine.agent;

import com.skew.engine.domain.LivePosition;
import com.skew.engine.domain.NewsArticle;
import com.skew.engine.repository.LivePositionRepository;
import com.skew.engine.service.BlackScholesService;
import com.skew.engine.service.GexService;
import com.skew.engine.service.NewsService;
import com.skew.engine.service.SchwabApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class TradingAgentToolsTest {

    private NewsService newsService;
    private LivePositionRepository livePositionRepository;
    private SchwabApiService schwabService;
    private GexService gexService;
    private BlackScholesService bsService;
    private TradingAgentTools tools;

    @BeforeEach
    void setUp() {
        newsService = Mockito.mock(NewsService.class);
        livePositionRepository = Mockito.mock(LivePositionRepository.class);
        schwabService = Mockito.mock(SchwabApiService.class);
        gexService = Mockito.mock(GexService.class);
        bsService = Mockito.mock(BlackScholesService.class);

        tools = new TradingAgentTools(newsService, livePositionRepository, schwabService, gexService, bsService);
    }

    @Test
    void getNewsForTickerFormatsEnrichedString() {
        NewsArticle article = new NewsArticle(
                "SPY", "Market Rallies", "Bloomberg", "http://url",
                LocalDateTime.now(), "Strong earnings report driven by tech sector.", "alpha_vantage", "Bullish"
        );
        when(newsService.getRecentNews("SPY")).thenReturn(List.of(article));

        List<String> result = tools.getNewsForTicker("SPY");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("[Bloomberg] Market Rallies | Sentiment: Bullish | Summary: Strong earnings report driven by tech sector.");
    }

    @Test
    void getNewsForTickerHandlesNullSentimentAndSummary() {
        NewsArticle article = new NewsArticle(
                "SPY", "Market Rallies", null, "http://url",
                LocalDateTime.now(), null, "alpha_vantage", null
        );
        when(newsService.getRecentNews("SPY")).thenReturn(List.of(article));

        List<String> result = tools.getNewsForTicker("SPY");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("[Unknown Source] Market Rallies | Sentiment: N/A");
    }

    @Test
    void getOpenPositionCountReturnsCorrectSize() {
        when(livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN"))
                .thenReturn(List.of(new LivePosition(), new LivePosition()));

        int count = tools.getOpenPositionCount();

        assertThat(count).isEqualTo(2);
    }

    @Test
    void getTheoreticalOptionPriceDelegatesToBsService() {
        when(bsService.blackScholes(5000.0, 5000.0, 30.0 / 365.0, 0.20, 0.05, "call"))
                .thenReturn(125.50);

        double price = tools.getTheoreticalOptionPrice(5000.0, 5000.0, 30, 0.20, "call");

        assertThat(price).isEqualTo(125.50);
    }
}

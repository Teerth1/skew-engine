package com.skew.engine.agent;

import com.skew.engine.domain.NewsArticle;
import com.skew.engine.repository.LivePositionRepository;
import com.skew.engine.service.NewsService;
import com.skew.engine.service.SchwabApiService;
import com.skew.engine.service.GexService;
import com.skew.engine.service.BlackScholesService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class TradingAgentTools {

    private final NewsService newsService;
    private final LivePositionRepository livePositionRepository;
    private final SchwabApiService schwabService;
    private final GexService gexService;
    private final BlackScholesService bsService;

    public TradingAgentTools(NewsService newsService, LivePositionRepository livePositionRepository,
                             SchwabApiService schwabService, GexService gexService, BlackScholesService bsService) {
        this.newsService = newsService;
        this.livePositionRepository = livePositionRepository;
        this.schwabService = schwabService;
        this.gexService = gexService;
        this.bsService = bsService;
    }

    @Tool(description = "Fetch recent news headlines for a stock ticker symbol to determine market sentiment")
    public List<String> getNewsForTicker(String ticker) {
        List<NewsArticle> articles = newsService.getRecentNews(ticker);
        return articles.stream()
                .map(a -> "[%s] %s".formatted(a.getSource(), a.getTitle()))
                .collect(Collectors.toList());
    }

    @Tool(description = "Get the count of currently open paper trading positions. Used to determine if a new position can be opened based on risk rules.")
    public int getOpenPositionCount() {
        return livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN").size();
    }

    @Tool(description = "Calculate Gamma Exposure (GEX) metrics (spot, call wall, put wall, zero flip) for a ticker symbol like SPX or SPY")
    public String getGexProfile(String ticker) {
        var chainOpt = schwabService.getFullOptionChain(ticker);
        if (chainOpt.isEmpty()) {
            return "Error: Could not retrieve option chain for " + ticker;
        }
        var resultOpt = gexService.calculateGex(chainOpt.get(), ticker, null);
        if (resultOpt.isEmpty()) {
            return "Error: GEX calculation failed for " + ticker;
        }
        var result = resultOpt.get();
        return "GEX Profile for %s: Spot Price: $%.2f, Call Wall Strike: $%.2f, Put Wall Strike: $%.2f, Zero Flip Strike: $%.2f"
                .formatted(ticker, result.spotPrice, result.callWall, result.putWall, result.zeroFlip);
    }

    @Tool(description = "Calculate the theoretical Black-Scholes price of an option contract")
    public double getTheoreticalOptionPrice(double s, double k, int daysToExpire, double volatility, String optionType) {
        return bsService.blackScholes(s, k, daysToExpire / 365.0, volatility, 0.05, optionType);
    }
}


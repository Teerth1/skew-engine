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

    @Tool(description = "Fetch recent news articles, sentiment scores, and summaries for a stock ticker symbol to evaluate market sentiment and narrative drivers.")
    public List<String> getNewsForTicker(String ticker) {
        List<NewsArticle> articles = newsService.getRecentNews(ticker);
        return articles.stream()
                .map(a -> {
                    String sentiment = (a.getRawSentiment() != null && !a.getRawSentiment().isBlank()) ? a.getRawSentiment() : "N/A";
                    String summary = (a.getSummary() != null && !a.getSummary().isBlank()) ? " | Summary: " + a.getSummary() : "";
                    return "[%s] %s | Sentiment: %s%s".formatted(
                            a.getSource() != null ? a.getSource() : "Unknown Source",
                            a.getTitle(),
                            sentiment,
                            summary);
                })
                .collect(Collectors.toList());
    }

    @Tool(description = "Get the count of currently open paper trading positions. Used to determine if a new position can be opened based on risk rules.")
    public int getOpenPositionCount() {
        return livePositionRepository.findByStatusOrderByEntryTimeDesc("OPEN").size();
    }

    @Tool(description = "Calculate Gamma Exposure (GEX) profile for a ticker symbol (e.g. 'SPX', 'SPY'). Returns Spot Price, Call Wall (major resistance), Put Wall (major support), and Zero Flip (volatility regime boundary).")
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

    @Tool(description = "Calculate the theoretical Black-Scholes price of an option contract. Parameters: s = spot price, k = strike price, daysToExpire = days to expiration (integer), volatility = implied volatility as a decimal (e.g. 0.20 for 20%), optionType = 'call' or 'put'")
    public double getTheoreticalOptionPrice(double s, double k, int daysToExpire, double volatility, String optionType) {
        return bsService.blackScholes(s, k, daysToExpire / 365.0, volatility, 0.05, optionType);
    }
}


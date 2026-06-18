package com.skew.engine.consumer;

import com.skew.engine.config.KafkaConfig;
import com.skew.engine.domain.LivePosition;
import com.skew.engine.domain.NewsArticle;
import com.skew.engine.domain.StrategyDecisionLog;
import com.skew.engine.domain.TradeSignalEvent;
import com.skew.engine.repository.StrategyDecisionLogRepository;
import com.skew.engine.service.AgentDecision;
import com.skew.engine.service.AgentDecisionService;
import com.skew.engine.service.AlpacaService;
import com.skew.engine.service.NewsSentimentService;
import com.skew.engine.service.NewsService;
import com.skew.engine.service.OrderManagerService;
import com.skew.engine.service.RiskDecision;
import com.skew.engine.service.RiskManagerService;
import com.skew.engine.service.SentimentBand;
import com.skew.engine.service.TradeIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TradeSignalConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TradeSignalConsumer.class);

    private final AlpacaService                alpacaService;
    private final NewsService                  newsService;
    private final NewsSentimentService         newsSentimentService;
    private final AgentDecisionService         agentDecisionService;
    private final RiskManagerService           riskManagerService;
    private final OrderManagerService          orderManagerService;
    private final StrategyDecisionLogRepository decisionLogRepository;

    public TradeSignalConsumer(AlpacaService alpacaService,
                               NewsService newsService,
                               NewsSentimentService newsSentimentService,
                               AgentDecisionService agentDecisionService,
                               RiskManagerService riskManagerService,
                               OrderManagerService orderManagerService,
                               StrategyDecisionLogRepository decisionLogRepository) {
        this.alpacaService          = alpacaService;
        this.newsService            = newsService;
        this.newsSentimentService   = newsSentimentService;
        this.agentDecisionService   = agentDecisionService;
        this.riskManagerService     = riskManagerService;
        this.orderManagerService    = orderManagerService;
        this.decisionLogRepository  = decisionLogRepository;
    }

    @KafkaListener(topics = KafkaConfig.TRADE_SIGNALS_TOPIC, 
                   groupId = "skew-engine-group",
                   containerFactory = "tradeSignalContainerFactory")
    public void consume(TradeSignalEvent event) {
        logger.info("Processing TradeSignalEvent async: {}", event.signalType());

        // Step 1: Find contract
        String optionSymbol = alpacaService.findAtmOptionContract(event.optionType(), event.spotPrice());
        if (optionSymbol == null || optionSymbol.isBlank()) {
            optionSymbol = buildMockSymbol(event.optionType(), event.spotPrice());
            logger.warn("Alpaca contract lookup failed — using mock symbol: {}", optionSymbol);
        }

        TradeIntent intent = new TradeIntent(
                event.signalType(), event.optionType(), optionSymbol, 1,
                event.spotPrice(), event.putIv(), event.callIv(),
                event.spotReturn(), event.skewChange(), 
                event.detectedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());

        // Step 2: News + Sentiment
        List<NewsArticle> news = newsService.getRecentNews(event.ticker().startsWith("SPX") ? "SPY" : event.ticker());
        SentimentBand sentiment = newsSentimentService.classify(news, event.ticker());

        // Step 3: Agent Decision
        AgentDecision decision = agentDecisionService.decide(intent);

        // Step 4: Risk Gate
        RiskDecision riskDecision = riskManagerService.evaluate(intent, decision);

        // Step 5: Audit Log
        StrategyDecisionLog log = buildDecisionLog(intent, news, sentiment, decision, riskDecision);
        decisionLogRepository.save(log);

        // Step 6: Execute
        if (riskDecision.approved()) {
            LivePosition pos = orderManagerService.executeIntent(intent, decision, riskDecision);
            if (pos != null) {
                log.setAlpacaOrderId(pos.getAlpacaOrderId());
                log.setBrokerStatus(pos.getAlpacaOrderId() != null ? "SUBMITTED" : "MOCK");
                log.setEntryPrice(pos.getEntryOptionPrice());
                decisionLogRepository.save(log);
            }
        } else {
            logger.info("🚫 Trade rejected by RiskManager: {}", riskDecision.reason());
        }
    }

    private String buildMockSymbol(String optionType, double spxPrice) {
        int strike = (int) Math.round(spxPrice / 10.0);
        String typeChar = "put".equalsIgnoreCase(optionType) ? "P" : "C";
        java.time.LocalDate exp = java.time.LocalDate.now().plusDays(30);
        String expStr = exp.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        return "SPY%s%s%08d".formatted(expStr, typeChar, strike * 1000);
    }

    private StrategyDecisionLog buildDecisionLog(TradeIntent intent,
                                                 List<NewsArticle> news,
                                                 SentimentBand sentiment,
                                                 AgentDecision decision,
                                                 RiskDecision riskDecision) {
        StrategyDecisionLog log = new StrategyDecisionLog(
                intent.signalType(),
                intent.spotPrice(),
                intent.putIv(),
                intent.callIv(),
                intent.putIv() - intent.callIv(),
                intent.spotReturn(),
                intent.skewChange());

        String headlines = news.stream().limit(5)
                .map(NewsArticle::getTitle)
                .collect(Collectors.joining("\",\"", "[\"", "\"]"));
        log.setNewsHeadlines(headlines.equals("[\"\"]") ? "[]" : headlines);
        log.setNewsSentiment(sentiment.name());

        log.setAgentRating(decision.rating().name());
        log.setAgentConfidence(decision.confidence());
        log.setAgentRationale(decision.rationale());
        log.setAgentRiskNotes(decision.riskNotes());

        log.setRiskApproved(riskDecision.approved());
        log.setRiskReason(riskDecision.reason());

        return log;
    }
}

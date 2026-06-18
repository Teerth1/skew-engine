package com.skew.engine.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Audit log capturing the full decision trail for every skew signal, from
 * raw inputs through AI/agent advisory to risk gate to broker response and final P&L.
 *
 * <p><strong>AGENTS.md Phase 4 fields:</strong></p>
 * <ul>
 *   <li>Signal inputs</li>
 *   <li>News snapshot</li>
 *   <li>AI/agent decision</li>
 *   <li>Risk approval or rejection</li>
 *   <li>Broker response</li>
 *   <li>Entry and exit prices</li>
 *   <li>Realized P&L</li>
 * </ul>
 *
 * <p>This table is append-only — records are written when a signal fires and
 * updated when the position closes. Backtests write to a separate run scope
 * (see {@code BacktestRun}) and never delete this table.</p>
 */
@Entity
@Table(
    name = "strategy_decision_logs",
    indexes = {
        @Index(name = "idx_sdl_created_at",  columnList = "createdAt"),
        @Index(name = "idx_sdl_signal_type", columnList = "signalType"),
        @Index(name = "idx_sdl_risk_approved", columnList = "riskApproved")
    }
)
public class StrategyDecisionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ---- Signal inputs -------------------------------------------------------

    @Column(nullable = false, length = 30)
    private String signalType;         // BEARISH_DIVERGENCE / BULLISH_DIVERGENCE

    @Column(nullable = false)
    private double spotPrice;

    private double putIv;
    private double callIv;
    private double skewValue;
    private double spotReturn;
    private double skewChange;

    // ---- News snapshot -------------------------------------------------------

    /** JSON array of recent headline strings (top-5) captured at signal time. */
    @Column(length = 3000)
    private String newsHeadlines;

    @Column(length = 20)
    private String newsSentiment;      // SentimentBand name

    // ---- AI/Agent decision ---------------------------------------------------

    @Column(length = 15)
    private String agentRating;        // AgentRating name

    private double agentConfidence;

    @Column(length = 500)
    private String agentRationale;

    @Column(length = 500)
    private String agentRiskNotes;

    // ---- Risk decision -------------------------------------------------------

    private boolean riskApproved;

    @Column(length = 300)
    private String riskReason;

    // ---- Broker response -----------------------------------------------------

    @Column(length = 60)
    private String alpacaOrderId;

    @Column(length = 20)
    private String brokerStatus;       // SUBMITTED, FILLED, REJECTED, MOCK

    // ---- Outcome (filled in when position closes) ----------------------------

    private double entryPrice;
    private double exitPrice;
    private double realizedPnl;

    // ---- Trade Memory (pgvector Phase 3) -------------------------------------

    @Column(columnDefinition = "text")
    private String tradeMemorySummary;

    @Column(columnDefinition = "vector(768)")
    private float[] embedding;

    // ---- Timestamps ----------------------------------------------------------

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime closedAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Required by JPA. */
    public StrategyDecisionLog() {}

    /** Minimal constructor — fill outcome fields when position closes. */
    public StrategyDecisionLog(String signalType, double spotPrice,
                                double putIv, double callIv,
                                double skewValue, double spotReturn, double skewChange) {
        this.signalType  = signalType;
        this.spotPrice   = spotPrice;
        this.putIv       = putIv;
        this.callIv      = callIv;
        this.skewValue   = skewValue;
        this.spotReturn  = spotReturn;
        this.skewChange  = skewChange;
        this.createdAt   = LocalDateTime.now();
    }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public Long getId()                              { return id; }
    public void setId(Long id)                       { this.id = id; }

    public String getSignalType()                    { return signalType; }
    public void setSignalType(String signalType)     { this.signalType = signalType; }

    public double getSpotPrice()                     { return spotPrice; }
    public void setSpotPrice(double spotPrice)       { this.spotPrice = spotPrice; }

    public double getPutIv()                         { return putIv; }
    public void setPutIv(double putIv)               { this.putIv = putIv; }

    public double getCallIv()                        { return callIv; }
    public void setCallIv(double callIv)             { this.callIv = callIv; }

    public double getSkewValue()                     { return skewValue; }
    public void setSkewValue(double skewValue)       { this.skewValue = skewValue; }

    public double getSpotReturn()                    { return spotReturn; }
    public void setSpotReturn(double spotReturn)     { this.spotReturn = spotReturn; }

    public double getSkewChange()                    { return skewChange; }
    public void setSkewChange(double skewChange)     { this.skewChange = skewChange; }

    public String getNewsHeadlines()                         { return newsHeadlines; }
    public void setNewsHeadlines(String newsHeadlines)       { this.newsHeadlines = newsHeadlines; }

    public String getNewsSentiment()                         { return newsSentiment; }
    public void setNewsSentiment(String newsSentiment)       { this.newsSentiment = newsSentiment; }

    public String getAgentRating()                           { return agentRating; }
    public void setAgentRating(String agentRating)           { this.agentRating = agentRating; }

    public double getAgentConfidence()                       { return agentConfidence; }
    public void setAgentConfidence(double agentConfidence)   { this.agentConfidence = agentConfidence; }

    public String getAgentRationale()                        { return agentRationale; }
    public void setAgentRationale(String agentRationale)     { this.agentRationale = agentRationale; }

    public String getAgentRiskNotes()                        { return agentRiskNotes; }
    public void setAgentRiskNotes(String agentRiskNotes)     { this.agentRiskNotes = agentRiskNotes; }

    public boolean isRiskApproved()                          { return riskApproved; }
    public void setRiskApproved(boolean riskApproved)        { this.riskApproved = riskApproved; }

    public String getRiskReason()                            { return riskReason; }
    public void setRiskReason(String riskReason)             { this.riskReason = riskReason; }

    public String getAlpacaOrderId()                         { return alpacaOrderId; }
    public void setAlpacaOrderId(String alpacaOrderId)       { this.alpacaOrderId = alpacaOrderId; }

    public String getBrokerStatus()                          { return brokerStatus; }
    public void setBrokerStatus(String brokerStatus)         { this.brokerStatus = brokerStatus; }

    public double getEntryPrice()                            { return entryPrice; }
    public void setEntryPrice(double entryPrice)             { this.entryPrice = entryPrice; }

    public double getExitPrice()                             { return exitPrice; }
    public void setExitPrice(double exitPrice)               { this.exitPrice = exitPrice; }

    public double getRealizedPnl()                           { return realizedPnl; }
    public void setRealizedPnl(double realizedPnl)           { this.realizedPnl = realizedPnl; }

    public LocalDateTime getCreatedAt()                      { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)        { this.createdAt = createdAt; }

    public LocalDateTime getClosedAt()                       { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt)          { this.closedAt = closedAt; }

    public String getTradeMemorySummary()                    { return tradeMemorySummary; }
    public void setTradeMemorySummary(String tradeMemorySummary) { this.tradeMemorySummary = tradeMemorySummary; }

    public float[] getEmbedding()                            { return embedding; }
    public void setEmbedding(float[] embedding)              { this.embedding = embedding; }
}

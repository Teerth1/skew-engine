package com.skew.engine.service;

import com.pgvector.PGvector;
import com.skew.engine.domain.StrategyDecisionLog;
import com.skew.engine.repository.StrategyDecisionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TradeMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(TradeMemoryService.class);

    private final EmbeddingModel embeddingModel;
    private final StrategyDecisionLogRepository decisionLogRepository;
    private final JdbcTemplate jdbcTemplate;

    public TradeMemoryService(EmbeddingModel embeddingModel, 
                              StrategyDecisionLogRepository decisionLogRepository,
                              JdbcTemplate jdbcTemplate) {
        this.embeddingModel = embeddingModel;
        this.decisionLogRepository = decisionLogRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void embedClosedTrade(StrategyDecisionLog log) {
        if (log == null || log.getExitPrice() == 0.0) return;

        String summary = """
                %s signal on %.0f spot. Agent confidence %.2f, risk %s.
                Resulted in PnL of $%.2f. Rationale: %s
                """.formatted(
                log.getSignalType(),
                log.getSpotPrice(),
                log.getAgentConfidence(),
                log.isRiskApproved() ? "APPROVED" : "REJECTED",
                log.getRealizedPnl(),
                log.getAgentRationale()
        );

        try {
            float[] embeds = embeddingModel.embed(summary);
            
            log.setTradeMemorySummary(summary);
            log.setEmbedding(embeds);
            decisionLogRepository.save(log);
            logger.info("TradeMemoryService: Embedded and saved memory for trade {}", log.getId());
        } catch (Exception e) {
            logger.error("TradeMemoryService: Failed to generate embedding for trade {}: {}", log.getId(), e.getMessage());
        }
    }

    public List<StrategyDecisionLog> findSimilarTrades(TradeIntent intent) {
        String query = "%s spotReturn=%.2f%% skewChange=%.2f%%".formatted(
                intent.signalType(), intent.spotReturn() * 100, intent.skewChange() * 100);
        try {
            float[] currentEmbedding = embeddingModel.embed(query);
            return findSimilarTrades(intent.signalType(), currentEmbedding, 3);
        } catch (Exception e) {
            logger.warn("TradeMemoryService: Failed to fetch similar trades: {}", e.getMessage());
            return List.of();
        }
    }

    public List<StrategyDecisionLog> findSimilarTrades(String signalType, float[] currentEmbedding, int limit) {
        String sql = """
            SELECT * FROM strategy_decision_logs 
            WHERE embedding IS NOT NULL 
              AND signal_type = ? 
            ORDER BY embedding <=> ? 
            LIMIT ?
            """;
            
        return jdbcTemplate.query(sql, 
            (rs, rowNum) -> mapRowToLog(rs),
            signalType, 
            new PGvector(currentEmbedding), 
            limit
        );
    }

    private StrategyDecisionLog mapRowToLog(java.sql.ResultSet rs) throws java.sql.SQLException {
        StrategyDecisionLog log = new StrategyDecisionLog();
        log.setId(rs.getLong("id"));
        log.setSignalType(rs.getString("signal_type"));
        log.setSpotPrice(rs.getDouble("spot_price"));
        log.setPutIv(rs.getDouble("put_iv"));
        log.setCallIv(rs.getDouble("call_iv"));
        log.setSkewValue(rs.getDouble("skew_value"));
        log.setSpotReturn(rs.getDouble("spot_return"));
        log.setSkewChange(rs.getDouble("skew_change"));
        log.setNewsHeadlines(rs.getString("news_headlines"));
        log.setNewsSentiment(rs.getString("news_sentiment"));
        log.setAgentRating(rs.getString("agent_rating"));
        log.setAgentConfidence(rs.getDouble("agent_confidence"));
        log.setAgentRationale(rs.getString("agent_rationale"));
        log.setAgentRiskNotes(rs.getString("agent_risk_notes"));
        log.setRiskApproved(rs.getBoolean("risk_approved"));
        log.setRiskReason(rs.getString("risk_reason"));
        log.setAlpacaOrderId(rs.getString("alpaca_order_id"));
        log.setBrokerStatus(rs.getString("broker_status"));
        log.setEntryPrice(rs.getDouble("entry_price"));
        log.setExitPrice(rs.getDouble("exit_price"));
        log.setRealizedPnl(rs.getDouble("realized_pnl"));
        
        java.sql.Timestamp createdTs = rs.getTimestamp("created_at");
        if (createdTs != null) log.setCreatedAt(createdTs.toLocalDateTime());
        
        java.sql.Timestamp closedTs = rs.getTimestamp("closed_at");
        if (closedTs != null) log.setClosedAt(closedTs.toLocalDateTime());
        
        log.setTradeMemorySummary(rs.getString("trade_memory_summary"));
        return log;
    }
}

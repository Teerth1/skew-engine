package com.skew.engine.repository;

import com.skew.engine.domain.StrategyDecisionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StrategyDecisionLogRepository extends JpaRepository<StrategyDecisionLog, Long> {

    List<StrategyDecisionLog> findBySignalTypeOrderByCreatedAtDesc(String signalType);

    List<StrategyDecisionLog> findByRiskApprovedOrderByCreatedAtDesc(boolean riskApproved);

    List<StrategyDecisionLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            LocalDateTime from, LocalDateTime to);

    /** All logs in the last N hours, newest first. */
    default List<StrategyDecisionLog> findRecent(int hours) {
        return findByCreatedAtBetweenOrderByCreatedAtDesc(
                LocalDateTime.now().minusHours(hours), LocalDateTime.now());
    }

}

package com.skew.engine.repository;

import com.skew.engine.domain.TradeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradeLogRepository extends JpaRepository<TradeLog, Long> {
    
    // Find all trades sorted by entry time descending (showing latest trades first)
    List<TradeLog> findAllByOrderByEntryTimeDesc();
    
    // Delete all records (useful when restarting backtests)
    void deleteAll();
}

package com.skew.engine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import com.skew.engine.domain.Strategy;
import com.skew.engine.domain.StrategyStatus;

public interface StrategyRepository extends JpaRepository<Strategy, Long> {
    List<Strategy> findByUserId(String userId);

    List<Strategy> findByUserIdAndStatus(String userId, StrategyStatus status);
}

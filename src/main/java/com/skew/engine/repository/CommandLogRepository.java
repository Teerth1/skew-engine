package com.skew.engine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.skew.engine.domain.CommandLog;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for command usage analytics.
 */
public interface CommandLogRepository extends JpaRepository<CommandLog, Long> {

    /** Count commands since a given time */
    long countByTimestampAfter(LocalDateTime since);

    /** Count commands for a specific command type */
    long countByCommand(String command);

    /** Get command counts grouped by command name (for top commands) */
    @Query("SELECT c.command, COUNT(c) FROM CommandLog c GROUP BY c.command ORDER BY COUNT(c) DESC")
    List<Object[]> countByCommandGrouped();

    /** Count unique users */
    @Query("SELECT COUNT(DISTINCT c.userId) FROM CommandLog c")
    long countDistinctUsers();

    /** Count commands today */
    @Query("SELECT COUNT(c) FROM CommandLog c WHERE c.timestamp >= :startOfDay")
    long countToday(LocalDateTime startOfDay);
}

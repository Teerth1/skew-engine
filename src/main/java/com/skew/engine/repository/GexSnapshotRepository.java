package com.skew.engine.repository;

import com.skew.engine.domain.GexSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GexSnapshotRepository extends JpaRepository<GexSnapshot, Long> {
    
    // Find snapshots for a specific ticker that occurred after a given time
    // Useful for getting the "last 30 minutes" of data
    List<GexSnapshot> findByTickerAndTimestampAfterOrderByTimestampAsc(String ticker, LocalDateTime timestamp);
    
    // Find snapshots between two times (e.g. intraday history)
    List<GexSnapshot> findByTickerAndTimestampBetweenOrderByTimestampAsc(String ticker, LocalDateTime start, LocalDateTime end);

    // Find the single most recent snapshot for a ticker
    GexSnapshot findTopByTickerOrderByTimestampDesc(String ticker);

    // For cleanup tasks to prevent DB bloat
    void deleteByTimestampBefore(LocalDateTime timestamp);
}

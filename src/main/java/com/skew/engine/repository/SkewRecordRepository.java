package com.skew.engine.repository;

import com.skew.engine.domain.SkewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SkewRecordRepository extends JpaRepository<SkewRecord, Long> {
    
    // Retrieve historical skew records sorted by timestamp ascending (crucial for charting and backtesting)
    List<SkewRecord> findAllByOrderByTimestampAsc();
    
    // Find the latest records for real-time display
    @Query("SELECT s FROM SkewRecord s ORDER BY s.timestamp DESC")
    List<SkewRecord> findLatest(org.springframework.data.domain.Pageable pageable);
}

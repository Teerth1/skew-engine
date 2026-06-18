package com.skew.engine.repository;

import com.skew.engine.domain.LivePosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link LivePosition} entities.
 *
 * <p>Provides queries for retrieving open/closed real-time paper trading positions
 * and looking up a position by its Alpaca order ID.</p>
 */
@Repository
public interface LivePositionRepository extends JpaRepository<LivePosition, Long> {

    /** Returns all positions with the given status ("OPEN" or "CLOSED"), most recent first. */
    List<LivePosition> findByStatusOrderByEntryTimeDesc(String status);

    /** Returns all positions ordered by entry time descending. */
    List<LivePosition> findAllByOrderByEntryTimeDesc();

    /** Look up a position by Alpaca order ID (e.g. to match fill events). */
    Optional<LivePosition> findByAlpacaOrderId(String alpacaOrderId);
}

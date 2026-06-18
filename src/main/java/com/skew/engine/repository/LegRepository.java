package com.skew.engine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.skew.engine.domain.Leg;

public interface LegRepository extends JpaRepository<Leg, Long> {
    // No custom methods needed for now!
    // Legs are accessed through their parent Strategy
}

package com.skew.engine.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an options trading strategy.
 * 
 * A Strategy is the parent container for one or more Legs (options contracts).
 * Examples:
 * - SINGLE: One leg (e.g., buying a call)
 * - VERTICAL: Two legs (e.g., bull call spread)
 * - IRON_CONDOR: Four legs
 * 
 * Each user can have multiple open strategies, tracked by userId.
 */
@Entity
@Data
@Table(name = "strategies")
public class Strategy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Discord username of the strategy owner */
    private String userId;

    /** Strategy type: SINGLE, VERTICAL, IRON_CONDOR, STRADDLE, CUSTOM */
    private String strategy;

    /** Underlying stock ticker (e.g., NVDA, TSLA) */
    private String ticker;

    /** Timestamp when the strategy was opened */
    private LocalDateTime openedAt = LocalDateTime.now();

    /** Current status: OPEN or CLOSED */
    @Enumerated(EnumType.STRING)
    private StrategyStatus status = StrategyStatus.OPEN;

    /** Net debit/credit for the entire strategy (for spreads) */
    private Double netCost;

    /**
     * List of option legs in this strategy.
     * Uses cascade to automatically save/delete legs with the strategy.
     * Eager fetch ensures legs are loaded with the strategy.
     */
    @OneToMany(mappedBy = "strategy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Leg> legs = new ArrayList<>();

    public Strategy() {
    }

    public Strategy(String userId, String strategy, String ticker) {
        this.userId = userId;
        this.strategy = strategy;
        this.ticker = ticker;
    }

    public Strategy(String userId, String strategy, String ticker, Double netCost) {
        this.userId = userId;
        this.strategy = strategy;
        this.ticker = ticker;
        this.netCost = netCost;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getTicker() {
        return ticker;
    }

    public StrategyStatus getStatus() {
        return status;
    }

    public Double getNetCost() {
        return netCost;
    }

    public List<Leg> getLegs() {
        return legs;
    }

    public void setStatus(StrategyStatus status) {
        this.status = status;
    }
}

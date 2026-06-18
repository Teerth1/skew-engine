package com.skew.engine.domain;

import java.time.LocalDate;
import jakarta.persistence.*;
import lombok.Data;

/**
 * Represents a single options contract leg within a Strategy.
 * 
 * Each Leg stores the details of one options position:
 * - Option type (call/put)
 * - Strike price
 * - Expiration date
 * - Entry price paid/received
 * - Quantity (positive = long, negative = short)
 * 
 * Multiple Legs combine to form multi-leg strategies like spreads.
 */
@Entity
@Data
@Table(name = "legs")
public class Leg {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Parent strategy this leg belongs to */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strategy_id")
    private Strategy strategy;

    /** Option type: "call" or "put" */
    private String optionType;

    /** Strike price of the option */
    private Double strikePrice;

    /** Expiration date of the option */
    private LocalDate expiration;

    /** Price paid (long) or received (short) per contract */
    private Double entryPrice;

    /** Quantity: positive = long position, negative = short position */
    private Integer quantity;

    public Leg() {
    }

    public Leg(String optionType, Double strikePrice, LocalDate expiration, Double entryPrice,
            Integer quantity) {
        this.optionType = optionType;
        this.strikePrice = strikePrice;
        this.expiration = expiration;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public String getOptionType() {
        return optionType;
    }

    public void setOptionType(String optionType) {
        this.optionType = optionType;
    }

    public Double getStrikePrice() {
        return strikePrice;
    }

    public void setStrikePrice(Double strikePrice) {
        this.strikePrice = strikePrice;
    }

    public LocalDate getExpiration() {
        return expiration;
    }

    public void setExpiration(LocalDate expiration) {
        this.expiration = expiration;
    }

    public Double getEntryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(Double entryPrice) {
        this.entryPrice = entryPrice;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}

package com.skew.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "gex_snapshots")
public class GexSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // e.g. "SPX", "SPY"
    private String ticker;

    // The exact minute this snapshot was taken
    private LocalDateTime timestamp;

    private double spotPrice;

    // Core Milestones at this specific minute
    private double zeroGamma;
    private double callWall;
    private double putWall;

    // To save database space, we store the entire strike ladder 
    // for this minute as a JSON string (Strike -> GEX values)
    @Column(columnDefinition = "TEXT")
    private String strikeDataJson;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public double getSpotPrice() { return spotPrice; }
    public void setSpotPrice(double spotPrice) { this.spotPrice = spotPrice; }

    public double getZeroGamma() { return zeroGamma; }
    public void setZeroGamma(double zeroGamma) { this.zeroGamma = zeroGamma; }

    public double getCallWall() { return callWall; }
    public void setCallWall(double callWall) { this.callWall = callWall; }

    public double getPutWall() { return putWall; }
    public void setPutWall(double putWall) { this.putWall = putWall; }

    public String getStrikeDataJson() { return strikeDataJson; }
    public void setStrikeDataJson(String strikeDataJson) { this.strikeDataJson = strikeDataJson; }
}

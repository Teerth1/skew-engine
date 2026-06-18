package com.skew.engine.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "trade_logs")
public class TradeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime entryTime;

    private LocalDateTime exitTime;

    @Column(nullable = false)
    private String strategyName;

    @Column(nullable = false, length = 10)
    private String positionType; // LONG, SHORT

    @Column(nullable = false)
    private double entrySpot;

    private double exitSpot;

    @Column(nullable = false)
    private double entrySkew;

    private double exitSkew;

    private double tradeReturn; // e.g., 0.05 for 5% return

    @Column(nullable = false, length = 15)
    private String status; // OPEN, CLOSED

    private double profitLoss; // Numerical return (capital difference)

    // Default constructor for JPA
    public TradeLog() {}

    public TradeLog(LocalDateTime entryTime, String strategyName, String positionType, double entrySpot, double entrySkew) {
        this.entryTime = entryTime;
        this.strategyName = strategyName;
        this.positionType = positionType;
        this.entrySpot = entrySpot;
        this.entrySkew = entrySkew;
        this.status = "OPEN";
    }

    // Helper to close a trade
    public void close(LocalDateTime exitTime, double exitSpot, double exitSkew, double capital) {
        this.exitTime = exitTime;
        this.exitSpot = exitSpot;
        this.exitSkew = exitSkew;
        this.status = "CLOSED";
        
        // Return is calculated as percentage return of the underlying spot movement
        // For a LONG trade: (exitSpot - entrySpot) / entrySpot
        // For a SHORT trade: (entrySpot - exitSpot) / entrySpot
        double spotReturn = (exitSpot - entrySpot) / entrySpot;
        if ("SHORT".equalsIgnoreCase(positionType)) {
            this.tradeReturn = -spotReturn;
        } else {
            this.tradeReturn = spotReturn;
        }
        
        this.profitLoss = capital * this.tradeReturn;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getEntryTime() { return entryTime; }
    public void setEntryTime(LocalDateTime entryTime) { this.entryTime = entryTime; }

    public LocalDateTime getExitTime() { return exitTime; }
    public void setExitTime(LocalDateTime exitTime) { this.exitTime = exitTime; }

    public String getStrategyName() { return strategyName; }
    public void setStrategyName(String strategyName) { this.strategyName = strategyName; }

    public String getPositionType() { return positionType; }
    public void setPositionType(String positionType) { this.positionType = positionType; }

    public double getEntrySpot() { return entrySpot; }
    public void setEntrySpot(double entrySpot) { this.entrySpot = entrySpot; }

    public double getExitSpot() { return exitSpot; }
    public void setExitSpot(double exitSpot) { this.exitSpot = exitSpot; }

    public double getEntrySkew() { return entrySkew; }
    public void setEntrySkew(double entrySkew) { this.entrySkew = entrySkew; }

    public double getExitSkew() { return exitSkew; }
    public void setExitSkew(double exitSkew) { this.exitSkew = exitSkew; }

    public double getTradeReturn() { return tradeReturn; }
    public void setTradeReturn(double tradeReturn) { this.tradeReturn = tradeReturn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getProfitLoss() { return profitLoss; }
    public void setProfitLoss(double profitLoss) { this.profitLoss = profitLoss; }
}

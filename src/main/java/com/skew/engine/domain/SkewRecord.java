package com.skew.engine.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "skew_records", indexes = {
    @Index(name = "idx_skew_timestamp", columnList = "timestamp"),
    @Index(name = "idx_skew_symbol", columnList = "underlyingSymbol")
})
public class SkewRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false)
    private String underlyingSymbol;

    @Column(nullable = false)
    private double spotPrice;

    @Column(nullable = false)
    private double putIv; // IV of Put at 95% of Spot

    @Column(nullable = false)
    private double callIv; // IV of Call at 105% of Spot

    @Column(nullable = false)
    private double skew; // putIv - callIv

    private double twoWeekSpotReturn; // 14-day spot percentage return

    private double twoWeekSkewChange; // 14-day skew change

    @Column(length = 30)
    private String signalType; // BEARISH_DIVERGENCE, BULLISH_DIVERGENCE, CONFIRMING, NONE

    // Default constructor for JPA
    public SkewRecord() {}

    public SkewRecord(LocalDateTime timestamp, String underlyingSymbol, double spotPrice, double putIv, double callIv, double skew, double twoWeekSpotReturn, double twoWeekSkewChange, String signalType) {
        this.timestamp = timestamp;
        this.underlyingSymbol = underlyingSymbol;
        this.spotPrice = spotPrice;
        this.putIv = putIv;
        this.callIv = callIv;
        this.skew = skew;
        this.twoWeekSpotReturn = twoWeekSpotReturn;
        this.twoWeekSkewChange = twoWeekSkewChange;
        this.signalType = signalType;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getUnderlyingSymbol() { return underlyingSymbol; }
    public void setUnderlyingSymbol(String underlyingSymbol) { this.underlyingSymbol = underlyingSymbol; }

    public double getSpotPrice() { return spotPrice; }
    public void setSpotPrice(double spotPrice) { this.spotPrice = spotPrice; }

    public double getPutIv() { return putIv; }
    public void setPutIv(double putIv) { this.putIv = putIv; }

    public double getCallIv() { return callIv; }
    public void setCallIv(double callIv) { this.callIv = callIv; }

    public double getSkew() { return skew; }
    public void setSkew(double skew) { this.skew = skew; }

    public double getTwoWeekSpotReturn() { return twoWeekSpotReturn; }
    public void setTwoWeekSpotReturn(double twoWeekSpotReturn) { this.twoWeekSpotReturn = twoWeekSpotReturn; }

    public double getTwoWeekSkewChange() { return twoWeekSkewChange; }
    public void setTwoWeekSkewChange(double twoWeekSkewChange) { this.twoWeekSkewChange = twoWeekSkewChange; }

    public String getSignalType() { return signalType; }
    public void setSignalType(String signalType) { this.signalType = signalType; }
}

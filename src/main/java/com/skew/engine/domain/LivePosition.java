package com.skew.engine.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Tracks a single live paper-trading options position opened by the real-time
 * skew signal engine.
 *
 * <p>When a skew divergence signal fires during live tick ingestion, the engine
 * opens a position by submitting a market order to Alpaca (paper account) and
 * persists the details here. Positions are automatically closed after a
 * configurable number of ticks (see {@code trading.holding-ticks}).</p>
 */
@Entity
@Table(name = "live_positions")
public class LivePosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** When the position was first opened. */
    @Column(nullable = false)
    private LocalDateTime entryTime;

    /** When the position was closed (null while still open). */
    private LocalDateTime exitTime;

    /** OCC-formatted option contract symbol, e.g. "SPY260620P00500000". */
    @Column(nullable = false, length = 30)
    private String optionSymbol;

    /** "CALL" or "PUT". */
    @Column(nullable = false, length = 5)
    private String optionType;

    /** Number of contracts traded (each contract = 100 shares). */
    @Column(nullable = false)
    private int quantity;

    /** Underlying (SPX) spot price at trade entry. */
    @Column(nullable = false)
    private double entrySpotPrice;

    /** Option mid-price at time of entry (used for P&L baseline). */
    private double entryOptionPrice;

    /** Latest fetched option mid-price for unrealized P&L computation. */
    private double currentOptionPrice;

    /** Realized or unrealized P&L in USD. */
    private double profitLoss;

    /** "OPEN" while in progress; "CLOSED" once the position is exited. */
    @Column(nullable = false, length = 10)
    private String status;

    /** Number of ticks the position has been held for (used to trigger auto-close). */
    @Column(nullable = false)
    private int ticksHeld;

    /** Alpaca order ID for the opening buy order (null if Alpaca is unavailable). */
    @Column(length = 60)
    private String alpacaOrderId;

    /** Which divergence signal triggered this trade. */
    @Column(nullable = false, length = 30)
    private String signalType; // BEARISH_DIVERGENCE, BULLISH_DIVERGENCE

    /** Gemini-generated 1-2 sentence market commentary for this signal. */
    @Column(length = 1000)
    private String commentary;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Required by JPA. */
    public LivePosition() {}

    public LivePosition(LocalDateTime entryTime, String optionSymbol, String optionType,
                        int quantity, double entrySpotPrice, String signalType) {
        this.entryTime = entryTime;
        this.optionSymbol = optionSymbol;
        this.optionType = optionType;
        this.quantity = quantity;
        this.entrySpotPrice = entrySpotPrice;
        this.signalType = signalType;
        this.status = "OPEN";
        this.ticksHeld = 0;
        this.profitLoss = 0.0;
    }

    // -------------------------------------------------------------------------
    // Business helpers
    // -------------------------------------------------------------------------

    /** Records entry price for P&L computation. */
    public void recordEntryPrice(double entryOptionPrice) {
        this.entryOptionPrice = entryOptionPrice;
        this.currentOptionPrice = entryOptionPrice;
    }

    /** Updates current market price and recomputes unrealized P&L. */
    public void updatePnl(double latestOptionPrice) {
        this.currentOptionPrice = latestOptionPrice;
        // P&L = (current price - entry price) × contracts × 100 (shares per contract)
        this.profitLoss = (latestOptionPrice - entryOptionPrice) * quantity * 100.0;
    }

    /** Marks the position as closed with final P&L. */
    public void close(LocalDateTime exitTime, double exitOptionPrice) {
        this.exitTime = exitTime;
        this.currentOptionPrice = exitOptionPrice;
        this.profitLoss = (exitOptionPrice - entryOptionPrice) * quantity * 100.0;
        this.status = "CLOSED";
    }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getEntryTime() { return entryTime; }
    public void setEntryTime(LocalDateTime entryTime) { this.entryTime = entryTime; }

    public LocalDateTime getExitTime() { return exitTime; }
    public void setExitTime(LocalDateTime exitTime) { this.exitTime = exitTime; }

    public String getOptionSymbol() { return optionSymbol; }
    public void setOptionSymbol(String optionSymbol) { this.optionSymbol = optionSymbol; }

    public String getOptionType() { return optionType; }
    public void setOptionType(String optionType) { this.optionType = optionType; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public double getEntrySpotPrice() { return entrySpotPrice; }
    public void setEntrySpotPrice(double entrySpotPrice) { this.entrySpotPrice = entrySpotPrice; }

    public double getEntryOptionPrice() { return entryOptionPrice; }
    public void setEntryOptionPrice(double entryOptionPrice) { this.entryOptionPrice = entryOptionPrice; }

    public double getCurrentOptionPrice() { return currentOptionPrice; }
    public void setCurrentOptionPrice(double currentOptionPrice) { this.currentOptionPrice = currentOptionPrice; }

    public double getProfitLoss() { return profitLoss; }
    public void setProfitLoss(double profitLoss) { this.profitLoss = profitLoss; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getTicksHeld() { return ticksHeld; }
    public void setTicksHeld(int ticksHeld) { this.ticksHeld = ticksHeld; }
    public void incrementTicksHeld() { this.ticksHeld++; }

    public String getAlpacaOrderId() { return alpacaOrderId; }
    public void setAlpacaOrderId(String alpacaOrderId) { this.alpacaOrderId = alpacaOrderId; }

    public String getSignalType() { return signalType; }
    public void setSignalType(String signalType) { this.signalType = signalType; }

    public String getCommentary() { return commentary; }
    public void setCommentary(String commentary) { this.commentary = commentary; }
}

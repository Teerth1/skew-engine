package com.skew.engine.domain;

/**
 * Defines the provenance categories of market evidence, separating deterministic facts
 * from soft narrative/sentiment components as inspired by the market-impact-graph project.
 */
public enum DataCategory {
    /** Durable static details, corporate identity structure, known mappings. */
    STRUCTURAL_DETERMINISTIC,

    /** Fundamental information extracted from SEC filings (10-K, 10-Q, etc.). */
    FILING_FUNDAMENTAL,

    /** Macro-economic time series data points (FRED CPI, interest rates, etc.). */
    MACRO_TIMESERIES,

    /** Market positioning flows (CFTC Commitments of Traders, options GEX, volume). */
    POSITIONING_FLOW,

    /** Dated real-world events, policy decisions, news report headlines. */
    NARRATIVE_EVENT,

    /** Soft sentiment indicators, news text tone, social volume. */
    NARRATIVE_SENTIMENT,

    /** Price and volume observation data (Yahoo Prices, Alpha Vantage closes). */
    PRICE_OBSERVATION
}

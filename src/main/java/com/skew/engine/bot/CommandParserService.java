package com.skew.engine.bot;

import org.springframework.stereotype.Service;

/**
 * Service class for parsing user input commands into structured option data.
 *
 * Handles natural language option notation like "NVDA 150c 30d" and converts
 * it into structured data for portfolio management and pricing calculations.
 */
@Service
public class CommandParserService {

    /**
     * Data class representing a parsed option contract.
     * Immutable to prevent modification after parsing.
     */
    public static class ParsedOption {
        public final String ticker;
        public final double strike;
        public final String type;
        public final int days;

        public ParsedOption(String ticker, double strike, String type, int days) {
            this.ticker = ticker;
            this.strike = strike;
            this.type = type;
            this.days = days;
        }
    }

    /**
     * Parses a user-friendly option notation string into structured data.
     *
     * Supported formats:
     * - "NVDA 150c 30d" - NVDA call option, $150 strike, 30 days to expiration
     * - "AAPL 200p 45" - AAPL put option, $200 strike, 45 days to expiration
     * - "TSLA 300 20d" - TSLA call option (default), $300 strike, 20 days
     *
     * @param query User input string in format: TICKER STRIKE[c/p] DAYS[d]
     * @return ParsedOption containing structured option data
     * @throws IllegalArgumentException if the format is invalid
     */
    public ParsedOption parse(String query) {
        String[] parts = query.toUpperCase().split(" ");

        // Validate minimum required parts
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid command format. Expected: <TICKER> <STRIKE><c/p> <DAYS>d");
        }

        // Extract ticker symbol (first part)
        String ticker = parts[0];

        // Parse strike price and option type (second part)
        // Format: "150C" (call), "200P" (put), or "300" (defaults to call)
        String strikePart = parts[1];
        String type;
        double strike;

        if (strikePart.endsWith("C")) {
            type = "call";
            strike = Double.parseDouble(strikePart.substring(0, strikePart.length() - 1));
        } else if (strikePart.endsWith("P")) {
            type = "put";
            strike = Double.parseDouble(strikePart.substring(0, strikePart.length() - 1));
        } else {
            // No type specified - default to call option
            type = "call";
            strike = Double.parseDouble(strikePart);
        }

        // Parse days to expiration (third part)
        // Format: "30d" or "30" (both accepted)
        String daysPart = parts[2].replace("D", "");
        int days = Integer.parseInt(daysPart);

        return new ParsedOption(ticker, strike, type, days);
    }

}

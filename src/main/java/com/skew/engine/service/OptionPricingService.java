package com.skew.engine.service;

import org.springframework.stereotype.Service;

@Service
public class OptionPricingService {

    private static final double DEFAULT_RISK_FREE_RATE = 0.04;
    private static final double MIN_OPTION_PRICE = 0.01;
    private static final double MIN_TIME_TO_EXPIRY = 0.001;

    public double calculateBlackScholesPrice(double spot,
                                             double strike,
                                             double impliedVolatility,
                                             double yearsToExpiry,
                                             String optionType) {
        validateInputs(spot, strike, impliedVolatility, optionType);

        if (yearsToExpiry <= MIN_TIME_TO_EXPIRY || impliedVolatility == 0.0) {
            return intrinsicValue(spot, strike, optionType);
        }

        double sqrtTime = Math.sqrt(yearsToExpiry);
        double d1 = (Math.log(spot / strike)
                + (DEFAULT_RISK_FREE_RATE + impliedVolatility * impliedVolatility / 2.0) * yearsToExpiry)
                / (impliedVolatility * sqrtTime);
        double d2 = d1 - impliedVolatility * sqrtTime;

        if ("CALL".equalsIgnoreCase(optionType)) {
            return Math.max(MIN_OPTION_PRICE,
                    spot * normalCdf(d1)
                            - strike * Math.exp(-DEFAULT_RISK_FREE_RATE * yearsToExpiry) * normalCdf(d2));
        }

        return Math.max(MIN_OPTION_PRICE,
                strike * Math.exp(-DEFAULT_RISK_FREE_RATE * yearsToExpiry) * normalCdf(-d2)
                        - spot * normalCdf(-d1));
    }

    private void validateInputs(double spot, double strike, double impliedVolatility, String optionType) {
        if (spot <= 0.0) {
            throw new IllegalArgumentException("Spot price must be greater than zero.");
        }
        if (strike <= 0.0) {
            throw new IllegalArgumentException("Strike price must be greater than zero.");
        }
        if (impliedVolatility < 0.0) {
            throw new IllegalArgumentException("Implied volatility cannot be negative.");
        }
        if (!"CALL".equalsIgnoreCase(optionType) && !"PUT".equalsIgnoreCase(optionType)) {
            throw new IllegalArgumentException("Option type must be CALL or PUT.");
        }
    }

    private double intrinsicValue(double spot, double strike, String optionType) {
        if ("CALL".equalsIgnoreCase(optionType)) {
            return Math.max(MIN_OPTION_PRICE, spot - strike);
        }
        return Math.max(MIN_OPTION_PRICE, strike - spot);
    }

    private double normalCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private double erf(double z) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
        double ans = 1.0 - t * Math.exp(-z * z - 1.26551223
                + t * (1.00002368
                + t * (0.37409196
                + t * (0.09678418
                + t * (-0.18628806
                + t * (0.27886807
                + t * (-1.13520398
                + t * (1.48851587
                + t * (-0.82215223
                + t * 0.17087277)))))))));
        return z >= 0 ? ans : -ans;
    }
}

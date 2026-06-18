package com.skew.engine.service;

import org.springframework.stereotype.Service;

/**
 * Service class for Black-Scholes option pricing model calculations.
 *
 * Implements the Black-Scholes formula to calculate theoretical prices
 * for European call and put options based on current market conditions.
 */
@Service
public class BlackScholesService {

    /**
     * Calculates the theoretical price of an option using the Black-Scholes model.
     *
     * Formula for Call: C = S * N(d1) - K * e^(-rt) * N(d2)
     * Formula for Put:  P = K * e^(-rt) * N(-d2) - S * N(-d1)
     *
     * @param s Current stock price
     * @param k Strike price of the option
     * @param t Time to expiration in years (e.g., 30 days = 30/365.0)
     * @param v Volatility of the underlying stock (annual standard deviation)
     * @param r Risk-free interest rate (annual)
     * @param optionType Type of option - "call" or "put"
     * @return Theoretical option price rounded to 2 decimal places
     */
    public double blackScholes(double s, double k, double t, double v, double r, String optionType) {
        // Calculate d1: (ln(S/K) + (r + σ²/2)t) / (σ√t)
        double d1 = (Math.log(s / k) + (r + 0.5 * Math.pow(v, 2)) * t) / (v * Math.sqrt(t));
        // Calculate d2: d1 - σ√t
        double d2 = d1 - v * Math.sqrt(t);

        double price;
        if ("call".equalsIgnoreCase(optionType)) {
            // Call option formula: S * N(d1) - K * e^(-rt) * N(d2)
            price = s * cumulativeDistribution(d1) - k * Math.exp(-r * t) * cumulativeDistribution(d2);
        } else {
            // Put option formula: K * e^(-rt) * N(-d2) - S * N(-d1)
            price = k * Math.exp(-r * t) * cumulativeDistribution(-d2) - s * cumulativeDistribution(-d1);
        }

        // Round to 2 decimal places (cents)
        return Math.round(price * 100.0) / 100.0;
    }

    /**
     * Standard Normal Probability Density Function (PDF), denoted as N'(x)
     */
    private double standardNormalPdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
    }

    /**
     * Standard Normal Cumulative Distribution Function (CDF), denoted as N(x)
     */
    private double cumulativeDistribution(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    // --- Core d1 and d2 calculations ---
    public double calculateD1(double s, double k, double t, double v, double r) {
        if (t <= 0 || v <= 0) return 0.0;
        return (Math.log(s / k) + (r + 0.5 * Math.pow(v, 2)) * t) / (v * Math.sqrt(t));
    }

    public double calculateD2(double s, double k, double t, double v, double r) {
        if (t <= 0 || v <= 0) return 0.0;
        return calculateD1(s, k, t, v, r) - v * Math.sqrt(t);
    }
    
    // --- Greek Calculations ---

    public double calculateDelta(double s, double k, double t, double v, double r, String optionType) {
        if (t <= 0) return ("call".equalsIgnoreCase(optionType) && s > k) ? 1.0 : (("put".equalsIgnoreCase(optionType) && s < k) ? -1.0 : 0.0);
        double d1 = calculateD1(s, k, t, v, r);
        if ("call".equalsIgnoreCase(optionType)) {
            return cumulativeDistribution(d1);
        } else {
            return cumulativeDistribution(d1) - 1.0;
        }
    }

    /**
     * Vanna = dDelta / dVol. Measures the change in Delta per 1% change in implied volatility.
     */
    public double calculateVanna(double s, double k, double t, double v, double r) {
        if (t <= 0 || v <= 0) return 0.0;
        double d1 = calculateD1(s, k, t, v, r);
        double d2 = calculateD2(s, k, t, v, r);
        // Vanna is the same for Call and Put
        // Vanna = - N'(d1) * d2 / sigma
        return -standardNormalPdf(d1) * d2 / v;
    }

    /**
     * Charm = dDelta / dt (sometimes called Delta Decay). Measures the change in Delta per day.
     * Returned as daily charm (per 1 calendar day).
     */
    public double calculateCharm(double s, double k, double t, double v, double r, String optionType) {
        if (t <= 0 || v <= 0) return 0.0;
        double d1 = calculateD1(s, k, t, v, r);
        double d2 = calculateD2(s, k, t, v, r);
        
        double nPrimeD1 = standardNormalPdf(d1);
        
        if ("call".equalsIgnoreCase(optionType)) {
            double charm = -nPrimeD1 * ( (r / (v * Math.sqrt(t))) - (d2 / (2 * t)) );
            // Convert to daily by dividing by 365
            return charm / 365.0;
        } else {
            double charm = -nPrimeD1 * ( (r / (v * Math.sqrt(t))) - (d2 / (2 * t)) );
            return charm / 365.0;
        }
    }

    /**
     * Approximates the error function using Horner's method.
     *
     * The error function is used in probability, statistics, and partial
     * differential equations. This implementation uses a polynomial approximation
     * for computational efficiency.
     *
     * @param z Input value
     * @return Error function value erf(z)
     */
    private double erf(double z) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));

        // Horner's method polynomial approximation for error function
        double ans = 1 - t * Math.exp(-z * z - 1.26551223 +
                t * (1.00002368 +
                t * (0.37409196 +
                t * (0.09678418 +
                t * (-0.18628806 +
                t * (0.27886807 +
                t * (-1.13520398 +
                t * (1.48851587 +
                t * (-0.82215223 +
                t * 0.17087277)))))))));

        // Return positive or negative value based on input sign
        if (z >= 0) return ans;
        else return -ans;
    }

}

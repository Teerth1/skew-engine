package com.skew.engine.service;

import org.springframework.stereotype.Service;

@Service
public class SkewSignalDetector {

    public SkewSignal detect(double pastSpot,
                             double currentSpot,
                             double pastSkew,
                             double currentSkew,
                             double spotThreshold,
                             double skewThreshold) {
        if (pastSpot <= 0.0) {
            throw new IllegalArgumentException("Past spot price must be greater than zero.");
        }
        if (currentSpot <= 0.0) {
            throw new IllegalArgumentException("Current spot price must be greater than zero.");
        }

        double spotReturn = (currentSpot - pastSpot) / pastSpot;
        double skewChange = currentSkew - pastSkew;
        return detectFromChanges(spotReturn, skewChange, spotThreshold, skewThreshold);
    }

    public SkewSignal detectFromChanges(double spotReturn,
                                        double skewChange,
                                        double spotThreshold,
                                        double skewThreshold) {
        if (spotThreshold < 0.0 || skewThreshold < 0.0) {
            throw new IllegalArgumentException("Signal thresholds must be non-negative.");
        }

        SkewSignalType type = SkewSignalType.NONE;
        if (spotReturn > spotThreshold && skewChange > skewThreshold) {
            type = SkewSignalType.BEARISH_DIVERGENCE;
        } else if (spotReturn < -spotThreshold && skewChange < -skewThreshold) {
            type = SkewSignalType.BULLISH_DIVERGENCE;
        }

        return new SkewSignal(type, spotReturn, skewChange);
    }
}

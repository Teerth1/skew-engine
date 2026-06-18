package com.skew.engine.service;

public enum SkewSignalType {
    NONE,
    BEARISH_DIVERGENCE,
    BULLISH_DIVERGENCE;

    public boolean isTriggered() {
        return this != NONE;
    }
}

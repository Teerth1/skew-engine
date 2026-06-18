package com.skew.engine.service;

public record SkewSignal(
        SkewSignalType type,
        double spotReturn,
        double skewChange
) {
    public boolean isTriggered() {
        return type.isTriggered();
    }
}

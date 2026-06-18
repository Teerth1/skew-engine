package com.skew.engine.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkewSignalDetectorTests {

    private final SkewSignalDetector detector = new SkewSignalDetector();

    @Test
    void detectsBearishDivergenceWhenSpotAndSkewRisePastThresholds() {
        SkewSignal signal = detector.detect(
                5_000.0,
                5_150.0,
                0.050,
                0.058,
                0.02,
                0.005);

        assertThat(signal.type()).isEqualTo(SkewSignalType.BEARISH_DIVERGENCE);
        assertThat(signal.spotReturn()).isCloseTo(0.03, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(signal.skewChange()).isCloseTo(0.008, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(signal.isTriggered()).isTrue();
    }

    @Test
    void detectsBullishDivergenceWhenSpotAndSkewFallPastThresholds() {
        SkewSignal signal = detector.detect(
                5_000.0,
                4_850.0,
                0.050,
                0.042,
                0.02,
                0.005);

        assertThat(signal.type()).isEqualTo(SkewSignalType.BULLISH_DIVERGENCE);
        assertThat(signal.spotReturn()).isCloseTo(-0.03, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(signal.skewChange()).isCloseTo(-0.008, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(signal.isTriggered()).isTrue();
    }

    @Test
    void returnsNoneWhenOnlyOneSideOfDivergencePassesThreshold() {
        SkewSignal signal = detector.detect(
                5_000.0,
                5_150.0,
                0.050,
                0.052,
                0.02,
                0.005);

        assertThat(signal.type()).isEqualTo(SkewSignalType.NONE);
        assertThat(signal.isTriggered()).isFalse();
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> detector.detect(0.0, 5_000.0, 0.05, 0.06, 0.02, 0.005))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Past spot");

        assertThatThrownBy(() -> detector.detectFromChanges(0.03, 0.01, -0.01, 0.005))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thresholds");
    }
}

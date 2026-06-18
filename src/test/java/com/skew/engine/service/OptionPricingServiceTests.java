package com.skew.engine.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class OptionPricingServiceTests {

    private final OptionPricingService pricingService = new OptionPricingService();

    @Test
    void calculatesAtmCallAndPutPricesNearParity() {
        double call = pricingService.calculateBlackScholesPrice(100.0, 100.0, 0.20, 30.0 / 365.0, "CALL");
        double put = pricingService.calculateBlackScholesPrice(100.0, 100.0, 0.20, 30.0 / 365.0, "PUT");

        assertThat(call).isCloseTo(2.45, offset(0.02));
        assertThat(put).isCloseTo(2.12, offset(0.02));
        assertThat(call - put).isCloseTo(0.33, offset(0.02));
    }

    @Test
    void returnsIntrinsicValueAtExpiryWithMinimumFloor() {
        double inTheMoneyCall = pricingService.calculateBlackScholesPrice(105.0, 100.0, 0.20, 0.0, "CALL");
        double outOfTheMoneyPut = pricingService.calculateBlackScholesPrice(105.0, 100.0, 0.20, 0.0, "PUT");

        assertThat(inTheMoneyCall).isEqualTo(5.0);
        assertThat(outOfTheMoneyPut).isEqualTo(0.01);
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> pricingService.calculateBlackScholesPrice(0.0, 100.0, 0.20, 1.0, "CALL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Spot");

        assertThatThrownBy(() -> pricingService.calculateBlackScholesPrice(100.0, 100.0, -0.01, 1.0, "CALL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("volatility");

        assertThatThrownBy(() -> pricingService.calculateBlackScholesPrice(100.0, 100.0, 0.20, 1.0, "STRADDLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CALL or PUT");
    }
}

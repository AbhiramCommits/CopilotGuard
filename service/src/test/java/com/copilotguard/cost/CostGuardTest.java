package com.copilotguard.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.copilotguard.config.CopilotGuardProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CostGuardTest {

    private CostGuard guard(BigDecimal maxUsdPerRun) {
        return new CostGuard(
                new CopilotGuardProperties(
                        null, null, null, null, new CopilotGuardProperties.Cost(maxUsdPerRun)));
    }

    @Test
    void allowsCostWithinBudget() {
        guard(new BigDecimal("1.0")).verifyWithinBudget(new BigDecimal("0.9"));
    }

    @Test
    void allowsCostAtBudgetBoundary() {
        guard(new BigDecimal("1.0")).verifyWithinBudget(new BigDecimal("1.0"));
    }

    @Test
    void throwsWhenBudgetExceeded() {
        assertThatThrownBy(
                        () ->
                                guard(new BigDecimal("1.0"))
                                        .verifyWithinBudget(new BigDecimal("1.0001")))
                .isInstanceOf(CostLimitExceededException.class)
                .hasMessageContaining("exceeds configured budget");
    }

    @Test
    void unlimitedWhenBudgetNull() {
        guard(null).verifyWithinBudget(new BigDecimal("999999"));
        assertThat(true).isTrue();
    }
}

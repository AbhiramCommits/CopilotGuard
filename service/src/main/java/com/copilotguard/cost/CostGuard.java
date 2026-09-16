package com.copilotguard.cost;

import com.copilotguard.config.CopilotGuardProperties;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class CostGuard {

    private final CopilotGuardProperties properties;

    public CostGuard(CopilotGuardProperties properties) {
        this.properties = properties;
    }

    public void verifyWithinBudget(BigDecimal totalCostUsd) {
        BigDecimal max = properties.cost().maxUsdPerRun();
        if (max == null || max.signum() < 0) {
            return;
        }
        if (totalCostUsd.compareTo(max) > 0) {
            throw new CostLimitExceededException(
                    "run cost "
                            + totalCostUsd
                            + " USD exceeds configured budget of "
                            + max
                            + " USD");
        }
    }
}

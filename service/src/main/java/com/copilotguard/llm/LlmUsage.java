package com.copilotguard.llm;

import java.math.BigDecimal;

public record LlmUsage(int tokensIn, int tokensOut, BigDecimal costUsd) {
}

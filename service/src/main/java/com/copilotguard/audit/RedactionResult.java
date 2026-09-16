package com.copilotguard.audit;

import java.util.List;

public record RedactionResult(String redacted, List<String> hits) {
}

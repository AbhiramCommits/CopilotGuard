package com.copilotguard.audit;

import java.util.List;

public record RedactionResult(String redacted, List<RedactionHit> hits) {

    public List<String> hitNames() {
        return hits.stream().map(RedactionHit::name).toList();
    }

    public boolean hasBlocker() {
        return hits.stream().anyMatch(RedactionHit::blocker);
    }
}

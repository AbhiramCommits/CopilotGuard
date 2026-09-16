package com.copilotguard.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.copilotguard.domain.ReviewRun;
import com.copilotguard.llm.LlmUsage;
import com.copilotguard.prompt.PromptPurpose;
import com.copilotguard.prompt.PromptTemplate;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditServiceTest {

    private final PromptAuditRepository promptAuditRepository = mock(PromptAuditRepository.class);
    private final AuditService auditService =
            new AuditService(promptAuditRepository, new PromptRedactor());

    @Test
    void recordStoresRedactedPromptAndMergesHits() {
        ReviewRun run = new ReviewRun();
        run.setId(9L);
        PromptTemplate template =
                new PromptTemplate("generate_tests", "v1", PromptPurpose.TEST_GEN, "c", "t");

        auditService.record(
                run,
                template,
                "review of dev@example.com",
                "raw response",
                "claude-test",
                15L,
                new LlmUsage(10, 5, new BigDecimal("0.1")),
                List.of(new RedactionHit("aws_key", true)));

        ArgumentCaptor<PromptAudit> captor = ArgumentCaptor.forClass(PromptAudit.class);
        verify(promptAuditRepository).save(captor.capture());
        PromptAudit audit = captor.getValue();
        assertThat(audit.getRunId()).isEqualTo("9");
        assertThat(audit.getTemplateId()).isEqualTo("generate_tests");
        assertThat(audit.getTemplateVersion()).isEqualTo("v1");
        assertThat(audit.getRedactedPrompt()).contains("[REDACTED:EMAIL:1]");
        assertThat(audit.getRedactedPrompt()).doesNotContain("dev@example.com");
        assertThat(audit.getRedactionHits()).contains("email", "aws_key");
        assertThat(audit.getRawResponse()).isEqualTo("raw response");
        assertThat(audit.getModel()).isEqualTo("claude-test");
        assertThat(audit.getLatencyMs()).isEqualTo(15L);
        assertThat(audit.getTokensIn()).isEqualTo(10);
        assertThat(audit.getTokensOut()).isEqualTo(5);
        assertThat(audit.getTimestamp()).isNotNull();
    }

    @Test
    void recordBlockedStoresRedactedTrailWithoutPromptHits() {
        ReviewRun run = new ReviewRun();
        run.setId(9L);
        RedactionResult redaction = new PromptRedactor().redact("legacy key AKIAIOSFODNN7EXAMPLE");

        auditService.recordBlocked(run, redaction);

        ArgumentCaptor<PromptAudit> captor = ArgumentCaptor.forClass(PromptAudit.class);
        verify(promptAuditRepository).save(captor.capture());
        PromptAudit audit = captor.getValue();
        assertThat(audit.getRunId()).isEqualTo("9");
        assertThat(audit.getTemplateId()).isEqualTo("blocked");
        assertThat(audit.getTemplateVersion()).isEqualTo("n/a");
        assertThat(audit.getRedactedPrompt()).contains("[REDACTED:AWS_KEY:1]");
        assertThat(audit.getRedactedPrompt()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(audit.getRedactionHits()).contains("aws_key");
        assertThat(audit.getRawResponse()).isEmpty();
    }
}

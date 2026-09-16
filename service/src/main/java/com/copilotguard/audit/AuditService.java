package com.copilotguard.audit;

import com.copilotguard.domain.ReviewRun;
import com.copilotguard.llm.LlmUsage;
import com.copilotguard.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditService {

    private final PromptAuditRepository promptAuditRepository;
    private final PromptRedactor promptRedactor;

    public AuditService(PromptAuditRepository promptAuditRepository, PromptRedactor promptRedactor) {
        this.promptAuditRepository = promptAuditRepository;
        this.promptRedactor = promptRedactor;
    }

    public void record(ReviewRun run, PromptTemplate template, String prompt, String rawResponse,
            String model, long latencyMs, LlmUsage usage) {
        RedactionResult redaction = promptRedactor.redact(prompt);
        PromptAudit audit = PromptAudit.builder()
                .runId(String.valueOf(run.getId()))
                .templateId(template.id())
                .templateVersion(template.version())
                .redactedPrompt(redaction.redacted())
                .rawResponse(rawResponse)
                .model(model)
                .latencyMs(latencyMs)
                .tokensIn(usage.tokensIn())
                .tokensOut(usage.tokensOut())
                .redactionHits(redaction.hits())
                .timestamp(Instant.now())
                .build();
        promptAuditRepository.save(audit);
    }
}

package com.copilotguard.audit;

import com.copilotguard.domain.ReviewRun;
import com.copilotguard.llm.LlmUsage;
import com.copilotguard.prompt.PromptTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final PromptAuditRepository promptAuditRepository;
    private final PromptRedactor promptRedactor;

    public AuditService(
            PromptAuditRepository promptAuditRepository, PromptRedactor promptRedactor) {
        this.promptAuditRepository = promptAuditRepository;
        this.promptRedactor = promptRedactor;
    }

    public void record(
            ReviewRun run,
            PromptTemplate template,
            String prompt,
            String rawResponse,
            String model,
            long latencyMs,
            LlmUsage usage,
            List<RedactionHit> diffHits) {
        RedactionResult redaction = promptRedactor.redact(prompt);
        List<String> hits = new ArrayList<>(diffHits.stream().map(RedactionHit::name).toList());
        redaction
                .hitNames()
                .forEach(
                        name -> {
                            if (!hits.contains(name)) {
                                hits.add(name);
                            }
                        });
        PromptAudit audit =
                PromptAudit.builder()
                        .runId(String.valueOf(run.getId()))
                        .templateId(template.id())
                        .templateVersion(template.version())
                        .redactedPrompt(redaction.redacted())
                        .rawResponse(rawResponse)
                        .model(model)
                        .latencyMs(latencyMs)
                        .tokensIn(usage.tokensIn())
                        .tokensOut(usage.tokensOut())
                        .redactionHits(hits)
                        .timestamp(Instant.now())
                        .build();
        promptAuditRepository.save(audit);
    }

    public void recordBlocked(ReviewRun run, RedactionResult redaction) {
        PromptAudit audit =
                PromptAudit.builder()
                        .runId(String.valueOf(run.getId()))
                        .templateId("blocked")
                        .templateVersion("n/a")
                        .redactedPrompt(redaction.redacted())
                        .rawResponse("")
                        .model("")
                        .latencyMs(0L)
                        .tokensIn(0)
                        .tokensOut(0)
                        .redactionHits(redaction.hitNames())
                        .timestamp(Instant.now())
                        .build();
        promptAuditRepository.save(audit);
    }
}

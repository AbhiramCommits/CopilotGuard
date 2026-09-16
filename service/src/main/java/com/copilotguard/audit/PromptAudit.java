package com.copilotguard.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("prompt_audit")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptAudit {

    @Id
    private String id;

    private String runId;
    private String templateId;
    private String templateVersion;
    private String redactedPrompt;
    private String rawResponse;
    private String model;
    private Long latencyMs;
    private Integer tokensIn;
    private Integer tokensOut;
    private List<String> redactionHits;
    private Instant timestamp;
}

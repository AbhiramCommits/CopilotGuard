package com.copilotguard.audit;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PromptAuditRepository extends MongoRepository<PromptAudit, String> {

    List<PromptAudit> findByRunId(String runId);
}

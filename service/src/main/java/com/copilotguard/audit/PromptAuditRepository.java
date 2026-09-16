package com.copilotguard.audit;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PromptAuditRepository extends MongoRepository<PromptAudit, String> {

    List<PromptAudit> findByRunId(String runId);
}

package com.copilotguard.audit;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PromptAuditRepository extends MongoRepository<PromptAudit, String> {
}

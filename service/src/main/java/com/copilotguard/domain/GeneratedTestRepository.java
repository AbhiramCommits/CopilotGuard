package com.copilotguard.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedTestRepository extends JpaRepository<GeneratedTest, Long> {

    List<GeneratedTest> findByReviewRunId(Long reviewRunId);

    long countByValidationStatus(ValidationStatus validationStatus);
}

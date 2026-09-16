package com.copilotguard.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratedTestRepository extends JpaRepository<GeneratedTest, Long> {

    List<GeneratedTest> findByReviewRunId(Long reviewRunId);

    long countByValidationStatus(ValidationStatus validationStatus);
}

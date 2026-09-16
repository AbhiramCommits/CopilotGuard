package com.copilotguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "review_run")
@Getter
@Setter
@NoArgsConstructor
public class ReviewRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String repo;

    @Column(name = "base_sha", nullable = false, length = 64)
    private String baseSha;

    @Column(name = "head_sha", nullable = false, length = 64)
    private String headSha;

    @Column(name = "prompt_template_id")
    private String promptTemplateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReviewRunStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "token_input")
    private Long tokenInput;

    @Column(name = "token_output")
    private Long tokenOutput;

    @Column(name = "cost_usd", precision = 19, scale = 4)
    private BigDecimal costUsd;
}

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

@Entity
@Table(name = "review_comment")
@Getter
@Setter
@NoArgsConstructor
public class ReviewComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_run_id", nullable = false)
    private Long reviewRunId;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    @Column
    private Integer line;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CommentCategory category;

    @Column(nullable = false, length = 8192)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "human_verdict", length = 32)
    private HumanVerdict humanVerdict;
}

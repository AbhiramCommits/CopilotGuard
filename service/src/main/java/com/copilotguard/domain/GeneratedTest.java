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
@Table(name = "generated_test")
@Getter
@Setter
@NoArgsConstructor
public class GeneratedTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_run_id", nullable = false)
    private Long reviewRunId;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    @Column(nullable = false)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(name = "compile_status", nullable = false, length = 32)
    private CompileStatus compileStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "pass_status", nullable = false, length = 32)
    private PassStatus passStatus;
}

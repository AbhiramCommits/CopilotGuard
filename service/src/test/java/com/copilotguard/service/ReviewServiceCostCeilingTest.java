package com.copilotguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.copilotguard.api.ReviewRequest;
import com.copilotguard.api.ReviewResponse;
import com.copilotguard.audit.AuditService;
import com.copilotguard.audit.PromptRedactor;
import com.copilotguard.audit.RedactionResult;
import com.copilotguard.config.CopilotGuardProperties;
import com.copilotguard.conventions.ConventionsLoader;
import com.copilotguard.conventions.ConventionsValidator;
import com.copilotguard.cost.CostGuard;
import com.copilotguard.cost.CostLimitExceededException;
import com.copilotguard.diff.DiffRenderer;
import com.copilotguard.diff.FilePatch;
import com.copilotguard.diff.Hunk;
import com.copilotguard.diff.HunkLine;
import com.copilotguard.diff.UnifiedDiffParser;
import com.copilotguard.domain.GeneratedTestRepository;
import com.copilotguard.domain.ReviewCommentRepository;
import com.copilotguard.domain.ReviewRun;
import com.copilotguard.domain.ReviewRunRepository;
import com.copilotguard.domain.ReviewRunStatus;
import com.copilotguard.github.GitHubClient;
import com.copilotguard.llm.GeneratedTestFile;
import com.copilotguard.llm.LlmClient;
import com.copilotguard.llm.LlmUsage;
import com.copilotguard.llm.ReviewGenerationResult;
import com.copilotguard.llm.TestGenerationResult;
import com.copilotguard.prompt.PromptRenderer;
import com.copilotguard.prompt.PromptTemplate;
import com.copilotguard.prompt.PromptTemplateRegistry;
import com.copilotguard.validation.GitHubWorkspaceProvider;
import com.copilotguard.validation.SyntheticWorkspaceProvider;
import com.copilotguard.validation.TestValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewServiceCostCeilingTest {

    private final ReviewRunRepository reviewRunRepository = mock(ReviewRunRepository.class);
    private final GeneratedTestRepository generatedTestRepository =
            mock(GeneratedTestRepository.class);
    private final ReviewCommentRepository reviewCommentRepository =
            mock(ReviewCommentRepository.class);
    private final UnifiedDiffParser diffParser = mock(UnifiedDiffParser.class);
    private final DiffRenderer diffRenderer = mock(DiffRenderer.class);
    private final GitHubClient gitHubClient = mock(GitHubClient.class);
    private final ConventionsLoader conventionsLoader = mock(ConventionsLoader.class);
    private final ConventionsValidator conventionsValidator = mock(ConventionsValidator.class);
    private final PromptTemplateRegistry templateRegistry = mock(PromptTemplateRegistry.class);
    private final PromptRenderer promptRenderer = mock(PromptRenderer.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final PromptRedactor promptRedactor = mock(PromptRedactor.class);
    private final AuditService auditService = mock(AuditService.class);
    private final TestValidator testValidator = mock(TestValidator.class);
    private final GitHubWorkspaceProvider gitHubWorkspaceProvider =
            mock(GitHubWorkspaceProvider.class);
    private final SyntheticWorkspaceProvider syntheticWorkspaceProvider =
            mock(SyntheticWorkspaceProvider.class);
    private final CostGuard costGuard;
    private final ReviewService reviewService;

    ReviewServiceCostCeilingTest() {
        CopilotGuardProperties properties =
                new CopilotGuardProperties(
                        null,
                        null,
                        null,
                        null,
                        new CopilotGuardProperties.Cost(new BigDecimal("1.0")));
        costGuard = new CostGuard(properties);
        reviewService =
                new ReviewService(
                        reviewRunRepository,
                        generatedTestRepository,
                        reviewCommentRepository,
                        diffParser,
                        diffRenderer,
                        gitHubClient,
                        conventionsLoader,
                        conventionsValidator,
                        templateRegistry,
                        promptRenderer,
                        llmClient,
                        promptRedactor,
                        auditService,
                        testValidator,
                        gitHubWorkspaceProvider,
                        syntheticWorkspaceProvider,
                        costGuard,
                        new SimpleMeterRegistry());
    }

    @Test
    void abortsRunWhenCumulativeCostExceedsBudget() {
        stubCommonMocks();
        when(llmClient.generateTests(anyString()))
                .thenReturn(
                        new TestGenerationResult(
                                List.of(
                                        new GeneratedTestFile(
                                                "src/test/java/A.java", "class A {}")),
                                new LlmUsage(100, 100, new BigDecimal("0.9")),
                                "req",
                                "resp",
                                1L,
                                "claude-test"));
        when(llmClient.reviewDiff(anyString()))
                .thenReturn(
                        new ReviewGenerationResult(
                                List.of(),
                                new LlmUsage(50, 50, new BigDecimal("0.5")),
                                "req",
                                "resp",
                                1L,
                                "claude-test"));

        assertThatThrownBy(
                        () ->
                                reviewService.createReview(
                                        new ReviewRequest(
                                                SAMPLE_DIFF,
                                                null,
                                                null,
                                                null,
                                                null,
                                                false,
                                                null,
                                                null)))
                .isInstanceOf(CostLimitExceededException.class);

        ArgumentCaptor<ReviewRun> captor = ArgumentCaptor.forClass(ReviewRun.class);
        verify(reviewRunRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().stream().map(ReviewRun::getStatus))
                .contains(ReviewRunStatus.FAILED);
    }

    @Test
    void abortsBeforeReviewCallWhenTestGenerationAlreadyExceedsBudget() {
        stubCommonMocks();
        when(llmClient.generateTests(anyString()))
                .thenReturn(
                        new TestGenerationResult(
                                List.of(
                                        new GeneratedTestFile(
                                                "src/test/java/A.java", "class A {}")),
                                new LlmUsage(100, 100, new BigDecimal("1.2")),
                                "req",
                                "resp",
                                1L,
                                "claude-test"));

        assertThatThrownBy(
                        () ->
                                reviewService.createReview(
                                        new ReviewRequest(
                                                SAMPLE_DIFF,
                                                null,
                                                null,
                                                null,
                                                null,
                                                false,
                                                null,
                                                null)))
                .isInstanceOf(CostLimitExceededException.class);

        verify(llmClient, never()).reviewDiff(anyString());
    }

    @Test
    void succeedsWhenCostStaysWithinBudget() {
        stubCommonMocks();
        when(llmClient.generateTests(anyString()))
                .thenReturn(
                        new TestGenerationResult(
                                List.of(
                                        new GeneratedTestFile(
                                                "src/test/java/A.java", "class A {}")),
                                new LlmUsage(100, 100, new BigDecimal("0.2")),
                                "req",
                                "resp",
                                1L,
                                "claude-test"));
        when(llmClient.reviewDiff(anyString()))
                .thenReturn(
                        new ReviewGenerationResult(
                                List.of(),
                                new LlmUsage(50, 50, new BigDecimal("0.3")),
                                "req",
                                "resp",
                                1L,
                                "claude-test"));

        ReviewResponse response =
                reviewService.createReview(
                        new ReviewRequest(SAMPLE_DIFF, null, null, null, null, false, null, null));

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.costUsd()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    private void stubCommonMocks() {
        when(diffParser.parse(anyString()))
                .thenReturn(
                        List.of(
                                new FilePatch(
                                        "A.java",
                                        "A.java",
                                        List.of(
                                                new Hunk(
                                                        1,
                                                        1,
                                                        1,
                                                        1,
                                                        List.of(
                                                                new HunkLine(
                                                                        HunkLine.LineType.ADD,
                                                                        null,
                                                                        1,
                                                                        "x")))))));
        when(diffRenderer.render(anyList())).thenReturn("+x");
        when(promptRedactor.redact(anyString()))
                .thenReturn(new RedactionResult(SAMPLE_DIFF, List.of()));
        when(conventionsLoader.parse(anyString())).thenReturn(ConventionsLoader.defaults());
        when(templateRegistry.latest("generate_tests"))
                .thenReturn(
                        new PromptTemplate(
                                "generate_tests",
                                "v1",
                                com.copilotguard.prompt.PromptPurpose.TEST_GEN,
                                "c",
                                "t"));
        when(templateRegistry.latest("review_diff"))
                .thenReturn(
                        new PromptTemplate(
                                "review_diff",
                                "v1",
                                com.copilotguard.prompt.PromptPurpose.REVIEW,
                                "c",
                                "t"));
        when(promptRenderer.render(any(), any())).thenReturn("prompt");
        when(conventionsValidator.validate(anyList(), any())).thenReturn(List.of());
        when(reviewRunRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            ReviewRun saved = invocation.getArgument(0);
                            if (saved.getId() == null) {
                                saved.setId(42L);
                            }
                            return saved;
                        });
        when(generatedTestRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(testValidator.validate(any())).thenReturn(List.of());
    }

    private static final String SAMPLE_DIFF =
            "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-x\n+y";
}

package com.copilotguard.service;

import com.copilotguard.api.ReviewRequest;
import com.copilotguard.api.ReviewResponse;
import com.copilotguard.audit.AuditService;
import com.copilotguard.audit.BlockedSecretException;
import com.copilotguard.audit.PromptRedactor;
import com.copilotguard.audit.RedactionHit;
import com.copilotguard.audit.RedactionResult;
import com.copilotguard.conventions.ConventionViolation;
import com.copilotguard.conventions.ConventionsLoader;
import com.copilotguard.conventions.ConventionsValidator;
import com.copilotguard.conventions.CopilotGuardConventions;
import com.copilotguard.diff.DiffRenderer;
import com.copilotguard.diff.FilePatch;
import com.copilotguard.diff.UnifiedDiffParser;
import com.copilotguard.domain.CommentCategory;
import com.copilotguard.domain.CompileStatus;
import com.copilotguard.domain.GeneratedTest;
import com.copilotguard.domain.GeneratedTestRepository;
import com.copilotguard.domain.HumanVerdict;
import com.copilotguard.domain.PassStatus;
import com.copilotguard.domain.ReviewComment;
import com.copilotguard.domain.ReviewCommentRepository;
import com.copilotguard.domain.ReviewRun;
import com.copilotguard.domain.ReviewRunRepository;
import com.copilotguard.domain.ReviewRunStatus;
import com.copilotguard.domain.ValidationStatus;
import com.copilotguard.github.GitHubClient;
import com.copilotguard.llm.GeneratedTestFile;
import com.copilotguard.llm.LlmClient;
import com.copilotguard.llm.ReviewCommentSuggestion;
import com.copilotguard.llm.ReviewGenerationResult;
import com.copilotguard.llm.TestGenerationResult;
import com.copilotguard.prompt.PromptRenderer;
import com.copilotguard.prompt.PromptTemplate;
import com.copilotguard.prompt.PromptTemplateRegistry;
import com.copilotguard.validation.GitHubWorkspaceProvider;
import com.copilotguard.validation.SyntheticWorkspaceProvider;
import com.copilotguard.validation.TestValidationResult;
import com.copilotguard.validation.TestValidator;
import com.copilotguard.validation.ValidationRequest;
import com.copilotguard.validation.WorkspaceProvider;
import com.copilotguard.validation.WorkspaceSpec;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReviewService {

    private final ReviewRunRepository reviewRunRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final UnifiedDiffParser diffParser;
    private final DiffRenderer diffRenderer;
    private final GitHubClient gitHubClient;
    private final ConventionsLoader conventionsLoader;
    private final ConventionsValidator conventionsValidator;
    private final PromptTemplateRegistry templateRegistry;
    private final PromptRenderer promptRenderer;
    private final LlmClient llmClient;
    private final PromptRedactor promptRedactor;
    private final AuditService auditService;
    private final TestValidator testValidator;
    private final GitHubWorkspaceProvider gitHubWorkspaceProvider;
    private final SyntheticWorkspaceProvider syntheticWorkspaceProvider;
    private final MeterRegistry meterRegistry;
    private final Yaml yaml = new Yaml();

    public ReviewService(ReviewRunRepository reviewRunRepository,
            GeneratedTestRepository generatedTestRepository,
            ReviewCommentRepository reviewCommentRepository,
            UnifiedDiffParser diffParser,
            DiffRenderer diffRenderer,
            GitHubClient gitHubClient,
            ConventionsLoader conventionsLoader,
            ConventionsValidator conventionsValidator,
            PromptTemplateRegistry templateRegistry,
            PromptRenderer promptRenderer,
            LlmClient llmClient,
            PromptRedactor promptRedactor,
            AuditService auditService,
            TestValidator testValidator,
            GitHubWorkspaceProvider gitHubWorkspaceProvider,
            SyntheticWorkspaceProvider syntheticWorkspaceProvider,
            MeterRegistry meterRegistry) {
        this.reviewRunRepository = reviewRunRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.diffParser = diffParser;
        this.diffRenderer = diffRenderer;
        this.gitHubClient = gitHubClient;
        this.conventionsLoader = conventionsLoader;
        this.conventionsValidator = conventionsValidator;
        this.templateRegistry = templateRegistry;
        this.promptRenderer = promptRenderer;
        this.llmClient = llmClient;
        this.promptRedactor = promptRedactor;
        this.auditService = auditService;
        this.testValidator = testValidator;
        this.gitHubWorkspaceProvider = gitHubWorkspaceProvider;
        this.syntheticWorkspaceProvider = syntheticWorkspaceProvider;
        this.meterRegistry = meterRegistry;
    }

    public ReviewResponse createReview(ReviewRequest request) {
        DiffSource source = resolveDiff(request);
        List<FilePatch> patches = diffParser.parse(source.diff());
        CopilotGuardConventions conventions = resolveConventions(request, source);
        PromptTemplate testTemplate = templateRegistry.latest("generate_tests");
        PromptTemplate reviewTemplate = templateRegistry.latest("review_diff");

        ReviewRun run = new ReviewRun();
        run.setRepo(source.repo());
        run.setBaseSha(source.baseSha());
        run.setHeadSha(source.headSha());
        run.setPromptTemplateId(testTemplate.id() + ":" + testTemplate.version());
        run.setStatus(ReviewRunStatus.IN_PROGRESS);
        run.setCreatedAt(Instant.now());
        reviewRunRepository.save(run);

        Path workspace = null;
        try {
            RedactionResult redaction = promptRedactor.redact(source.diff());
            if (redaction.hasBlocker() && !request.allowRedactedSend()) {
                failRun(run);
                auditService.recordBlocked(run, redaction);
                throw new BlockedSecretException("diff contains BLOCKER-class secrets: "
                        + redaction.hits().stream().filter(RedactionHit::blocker).map(RedactionHit::name)
                                .sorted().collect(Collectors.joining(", "))
                        + ". Set allowRedactedSend=true to send redacted content.");
            }

            Map<String, Object> context = Map.of(
                    "repo", source.repo(),
                    "baseSha", source.baseSha(),
                    "headSha", source.headSha(),
                    "diff", diffRenderer.render(diffParser.parse(redaction.redacted())),
                    "conventions", conventionsToText(conventions));

            String testPrompt = promptRenderer.render(testTemplate, context);
            TestGenerationResult testResult = llmClient.generateTests(testPrompt);
            String reviewPrompt = promptRenderer.render(reviewTemplate, context);
            ReviewGenerationResult reviewResult = llmClient.reviewDiff(reviewPrompt);

            workspace = prepareWorkspace(source);
            List<TestValidationResult> validationResults =
                    testValidator.validate(new ValidationRequest(workspace, testResult.files()));

            List<GeneratedTest> tests = persistTests(run, testResult, validationResults);
            List<ReviewComment> comments = persistComments(run, reviewResult,
                    conventionsValidator.validate(testResult.files(), conventions));

            auditService.record(run, testTemplate, testPrompt, testResult.rawResponse(), testResult.model(),
                    testResult.latencyMs(), testResult.usage(), redaction.hits());
            auditService.record(run, reviewTemplate, reviewPrompt, reviewResult.rawResponse(), reviewResult.model(),
                    reviewResult.latencyMs(), reviewResult.usage(), redaction.hits());

            long tokensIn = (long) testResult.usage().tokensIn() + reviewResult.usage().tokensIn();
            long tokensOut = (long) testResult.usage().tokensOut() + reviewResult.usage().tokensOut();
            BigDecimal cost = testResult.usage().costUsd().add(reviewResult.usage().costUsd());
            run.setTokenInput(tokensIn);
            run.setTokenOutput(tokensOut);
            run.setCostUsd(cost);
            run.setStatus(ReviewRunStatus.SUCCEEDED);
            reviewRunRepository.save(run);
            meterRegistry.counter("copilotguard.reviews.succeeded").increment();
            return toResponse(run, tests, comments, testResult.files());
        } catch (RuntimeException ex) {
            failRun(run);
            meterRegistry.counter("copilotguard.reviews.failed").increment();
            throw ex;
        } finally {
            if (workspace != null) {
                deleteRecursively(workspace);
            }
        }
    }

    private void failRun(ReviewRun run) {
        run.setStatus(ReviewRunStatus.FAILED);
        reviewRunRepository.save(run);
    }

    private Path prepareWorkspace(DiffSource source) {
        WorkspaceSpec spec = new WorkspaceSpec(source.repo(), source.cloneUrl(), source.headSha(), source.baseSha());
        WorkspaceProvider provider = StringUtils.hasText(source.cloneUrl())
                ? gitHubWorkspaceProvider
                : syntheticWorkspaceProvider;
        return provider.prepare(spec);
    }

    private DiffSource resolveDiff(ReviewRequest request) {
        if (StringUtils.hasText(request.diff())) {
            return new DiffSource("local", "unknown", "unknown", null, request.diff());
        }
        GitHubClient.PrInfo info = gitHubClient.getPrInfo(request.owner(), request.repo(), request.prNumber());
        String diff = gitHubClient.getPrDiff(request.owner(), request.repo(), request.prNumber());
        return new DiffSource(request.owner() + "/" + request.repo(), info.baseSha(), info.headSha(),
                info.cloneUrl(), diff);
    }

    private CopilotGuardConventions resolveConventions(ReviewRequest request, DiffSource source) {
        if (StringUtils.hasText(request.conventions())) {
            return conventionsLoader.parse(request.conventions());
        }
        if (StringUtils.hasText(request.owner())) {
            return gitHubClient.fetchConventionsYaml(request.owner(), request.repo())
                    .map(conventionsLoader::parse)
                    .orElseGet(ConventionsLoader::defaults);
        }
        return ConventionsLoader.defaults();
    }

    private List<GeneratedTest> persistTests(ReviewRun run, TestGenerationResult result,
            List<TestValidationResult> validationResults) {
        Map<String, TestValidationResult> byPath = validationResults.stream()
                .collect(Collectors.toMap(v -> v.test().path(), Function.identity(), (a, b) -> a));
        List<GeneratedTest> saved = new ArrayList<>();
        for (GeneratedTestFile file : result.files()) {
            TestValidationResult validation = byPath.get(file.path());
            GeneratedTest test = new GeneratedTest();
            test.setReviewRunId(run.getId());
            test.setFilePath(file.path());
            test.setSource("anthropic:" + result.model());
            if (validation == null) {
                test.setCompileStatus(CompileStatus.PENDING);
                test.setPassStatus(PassStatus.PENDING);
                test.setValidationStatus(ValidationStatus.PENDING);
            } else {
                test.setValidationStatus(validation.status());
                test.setValidationDetail(validation.detail());
                switch (validation.status()) {
                    case COMPILE_FAIL -> {
                        test.setCompileStatus(CompileStatus.FAILURE);
                        test.setPassStatus(PassStatus.PENDING);
                    }
                    case TEST_FAIL, FLAKY -> {
                        test.setCompileStatus(CompileStatus.SUCCESS);
                        test.setPassStatus(PassStatus.FAILED);
                    }
                    case PASSING -> {
                        test.setCompileStatus(CompileStatus.SUCCESS);
                        test.setPassStatus(PassStatus.PASSED);
                    }
                    case PENDING -> {
                        test.setCompileStatus(CompileStatus.PENDING);
                        test.setPassStatus(PassStatus.PENDING);
                    }
                }
            }
            saved.add(generatedTestRepository.save(test));
        }
        return saved;
    }

    private List<ReviewComment> persistComments(ReviewRun run, ReviewGenerationResult result,
            List<ConventionViolation> violations) {
        List<ReviewComment> saved = new ArrayList<>();
        for (ReviewCommentSuggestion suggestion : result.comments()) {
            ReviewComment comment = new ReviewComment();
            comment.setReviewRunId(run.getId());
            comment.setFilePath(suggestion.file());
            comment.setLine(suggestion.line());
            comment.setSeverity(suggestion.severity());
            comment.setCategory(suggestion.category());
            String body = suggestion.body();
            if (suggestion.suggestedFix() != null && !suggestion.suggestedFix().isBlank()) {
                body = body + "\n\nSuggested fix: " + suggestion.suggestedFix();
            }
            comment.setBody(body);
            comment.setHumanVerdict(HumanVerdict.PENDING);
            saved.add(reviewCommentRepository.save(comment));
        }
        for (ConventionViolation violation : violations) {
            ReviewComment comment = new ReviewComment();
            comment.setReviewRunId(run.getId());
            comment.setFilePath(violation.filePath());
            comment.setLine(violation.line());
            comment.setSeverity(violation.severity());
            comment.setCategory(CommentCategory.CONVENTIONS);
            comment.setBody(violation.message());
            comment.setHumanVerdict(HumanVerdict.PENDING);
            saved.add(reviewCommentRepository.save(comment));
        }
        return saved;
    }

    private String conventionsToText(CopilotGuardConventions conventions) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("testClassNamePattern", conventions.testClassNamePattern());
        map.put("bannedApis", conventions.bannedApis());
        map.put("requiredTestAnnotations", conventions.requiredTestAnnotations());
        map.put("maxMethodLength", conventions.maxMethodLength());
        return yaml.dump(map);
    }

    private ReviewResponse toResponse(ReviewRun run, List<GeneratedTest> tests, List<ReviewComment> comments,
            List<GeneratedTestFile> generatedFiles) {
        Map<String, String> contentByPath = generatedFiles.stream()
                .collect(Collectors.toMap(GeneratedTestFile::path, GeneratedTestFile::content, (a, b) -> a));
        return new ReviewResponse(
                run.getId(),
                run.getRepo(),
                run.getBaseSha(),
                run.getHeadSha(),
                run.getPromptTemplateId(),
                run.getStatus().name(),
                run.getTokenInput() == null ? 0 : run.getTokenInput(),
                run.getTokenOutput() == null ? 0 : run.getTokenOutput(),
                run.getCostUsd(),
                tests.stream().map(t -> new ReviewResponse.TestSummary(t.getFilePath(),
                        t.getCompileStatus().name(),
                        t.getPassStatus().name(),
                        t.getValidationStatus().name(),
                        t.getValidationStatus() == ValidationStatus.PASSING,
                        t.getValidationDetail(),
                        t.getValidationStatus() == ValidationStatus.PASSING
                                ? contentByPath.get(t.getFilePath())
                                : null)).toList(),
                comments.stream().map(c -> new ReviewResponse.CommentSummary(c.getId(), c.getFilePath(), c.getLine(),
                        c.getSeverity().name(), c.getCategory().name(), c.getBody())).toList());
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private record DiffSource(String repo, String baseSha, String headSha, String cloneUrl, String diff) {
    }
}

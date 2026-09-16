package com.copilotguard.service;

import com.copilotguard.api.ReviewRequest;
import com.copilotguard.api.ReviewResponse;
import com.copilotguard.audit.AuditService;
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
import com.copilotguard.github.GitHubClient;
import com.copilotguard.llm.GeneratedTestFile;
import com.copilotguard.llm.LlmClient;
import com.copilotguard.llm.ReviewCommentSuggestion;
import com.copilotguard.llm.ReviewGenerationResult;
import com.copilotguard.llm.TestGenerationResult;
import com.copilotguard.prompt.PromptRenderer;
import com.copilotguard.prompt.PromptTemplate;
import com.copilotguard.prompt.PromptTemplateRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final AuditService auditService;
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
            AuditService auditService,
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
        this.auditService = auditService;
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

        try {
            Map<String, Object> context = Map.of(
                    "repo", source.repo(),
                    "baseSha", source.baseSha(),
                    "headSha", source.headSha(),
                    "diff", diffRenderer.render(patches),
                    "conventions", conventionsToText(conventions));

            String testPrompt = promptRenderer.render(testTemplate, context);
            TestGenerationResult testResult = llmClient.generateTests(testPrompt);
            String reviewPrompt = promptRenderer.render(reviewTemplate, context);
            ReviewGenerationResult reviewResult = llmClient.reviewDiff(reviewPrompt);

            List<GeneratedTest> tests = persistTests(run, testResult);
            List<ReviewComment> comments = persistComments(run, reviewResult,
                    conventionsValidator.validate(testResult.files(), conventions));

            auditService.record(run, testTemplate, testPrompt, testResult.rawResponse(), testResult.model(),
                    testResult.latencyMs(), testResult.usage());
            auditService.record(run, reviewTemplate, reviewPrompt, reviewResult.rawResponse(), reviewResult.model(),
                    reviewResult.latencyMs(), reviewResult.usage());

            long tokensIn = (long) testResult.usage().tokensIn() + reviewResult.usage().tokensIn();
            long tokensOut = (long) testResult.usage().tokensOut() + reviewResult.usage().tokensOut();
            BigDecimal cost = testResult.usage().costUsd().add(reviewResult.usage().costUsd());
            run.setTokenInput(tokensIn);
            run.setTokenOutput(tokensOut);
            run.setCostUsd(cost);
            run.setStatus(ReviewRunStatus.SUCCEEDED);
            reviewRunRepository.save(run);
            meterRegistry.counter("copilotguard.reviews.succeeded").increment();
            return toResponse(run, tests, comments);
        } catch (RuntimeException ex) {
            run.setStatus(ReviewRunStatus.FAILED);
            reviewRunRepository.save(run);
            meterRegistry.counter("copilotguard.reviews.failed").increment();
            throw ex;
        }
    }

    private DiffSource resolveDiff(ReviewRequest request) {
        if (StringUtils.hasText(request.diff())) {
            return new DiffSource("local", "unknown", "unknown", request.diff());
        }
        GitHubClient.PrInfo info = gitHubClient.getPrInfo(request.owner(), request.repo(), request.prNumber());
        String diff = gitHubClient.getPrDiff(request.owner(), request.repo(), request.prNumber());
        return new DiffSource(request.owner() + "/" + request.repo(), info.baseSha(), info.headSha(), diff);
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

    private List<GeneratedTest> persistTests(ReviewRun run, TestGenerationResult result) {
        List<GeneratedTest> saved = new ArrayList<>();
        for (GeneratedTestFile file : result.files()) {
            GeneratedTest test = new GeneratedTest();
            test.setReviewRunId(run.getId());
            test.setFilePath(file.path());
            test.setSource("anthropic:" + result.model());
            test.setCompileStatus(CompileStatus.PENDING);
            test.setPassStatus(PassStatus.PENDING);
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

    private ReviewResponse toResponse(ReviewRun run, List<GeneratedTest> tests, List<ReviewComment> comments) {
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
                        t.getCompileStatus().name(), t.getPassStatus().name())).toList(),
                comments.stream().map(c -> new ReviewResponse.CommentSummary(c.getId(), c.getFilePath(), c.getLine(),
                        c.getSeverity().name(), c.getCategory().name(), c.getBody())).toList());
    }

    private record DiffSource(String repo, String baseSha, String headSha, String diff) {
    }
}

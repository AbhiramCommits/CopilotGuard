package com.copilotguard.validation;

import com.copilotguard.config.CopilotGuardProperties;
import com.copilotguard.domain.ValidationStatus;
import com.copilotguard.llm.GeneratedTestFile;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.DockerImageName;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Component
public class DockerTestValidator implements TestValidator {

    private final CopilotGuardProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "validation-timeout-killer");
        thread.setDaemon(true);
        return thread;
    });

    public DockerTestValidator(CopilotGuardProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<TestValidationResult> validate(ValidationRequest request) {
        CopilotGuardProperties.Validation config = properties.validation();
        Path junitJar = Path.of(config.junitConsoleJar());
        if (!Files.isRegularFile(junitJar)) {
            throw new ValidationException("junit console standalone jar not found at " + junitJar);
        }
        Path workspace = request.workspaceDir();
        List<Path> containerTestPaths = copyTestsIntoWorkspace(workspace, request.tests());
        Path reportsDir = createTempDir("copilotguard-reports");
        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(config.image()))) {
            container.withCommand("sleep", "infinity")
                    .withWorkingDirectory("/workspace")
                    .withFileSystemBind(workspace.toAbsolutePath().toString(), "/workspace", BindMode.READ_ONLY)
                    .withFileSystemBind(reportsDir.toAbsolutePath().toString(), "/tmp/reports", BindMode.READ_WRITE)
                    .withFileSystemBind(
                            junitJar.toAbsolutePath().toString(), "/tmp/junit-console-standalone.jar", BindMode.READ_ONLY)
                    .withTmpFs(Map.of("/tmp", "rw"))
                    .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(cmd.getHostConfig()
                            .withNetworkMode("none")
                            .withReadonlyRootfs(true)
                            .withMemory(config.memoryMb() * 1024L * 1024L)
                            .withMemorySwap(config.memoryMb() * 1024L * 1024L)
                            .withCpuPeriod(100_000L)
                            .withCpuQuota((long) (config.cpus() * 100_000L))));
            container.start();

            List<TestValidationResult> results = new ArrayList<>();
            for (int i = 0; i < request.tests().size(); i++) {
                GeneratedTestFile test = request.tests().get(i);
                results.add(validateFile(container, test, containerTestPaths.get(i), reportsDir, i));
            }
            return results;
        } finally {
            deleteRecursively(reportsDir);
        }
    }

    private TestValidationResult validateFile(GenericContainer<?> container, GeneratedTestFile test,
            Path containerTestPath, Path reportsRoot, int index) {
        CopilotGuardProperties.Validation config = properties.validation();
        String className = simpleClassName(test.path());
        String containerPath = "/workspace/" + containerTestPath.toString().replace('\\', '/');

        ExecResult compile = execWithTimeout(container, config.compileTimeout(), "sh", "-c",
                "find /workspace -name '*.java' -not -path '/workspace/.copilotguard-tests/*' -print > /tmp/sources.txt"
                        + " && echo '" + containerPath + "' >> /tmp/sources.txt"
                        + " && javac -d /tmp/out -cp /tmp/junit-console-standalone.jar @/tmp/sources.txt");
        if (compile.getExitCode() != 0) {
            return new TestValidationResult(test, ValidationStatus.COMPILE_FAIL,
                    "javac failed: " + tail(compile.getStderr(), 500));
        }

        boolean run1 = runTests(container, className, reportsRoot.resolve("run-" + index + "-1"), config.runTimeout());
        boolean run2 = runTests(container, className, reportsRoot.resolve("run-" + index + "-2"), config.runTimeout());
        if (run1 && run2) {
            return new TestValidationResult(test, ValidationStatus.PASSING, "tests passed in both runs");
        }
        if (!run1 && !run2) {
            return new TestValidationResult(test, ValidationStatus.TEST_FAIL,
                    "tests failed in both runs (see surefire report)");
        }
        return new TestValidationResult(test, ValidationStatus.FLAKY,
                "non-deterministic result: run 1 " + (run1 ? "passed" : "failed")
                        + ", run 2 " + (run2 ? "passed" : "failed"));
    }

    private boolean runTests(GenericContainer<?> container, String className, Path hostReportsDir, Duration timeout) {
        String containerReportsDir = "/tmp/reports/" + hostReportsDir.getFileName();
        execWithTimeout(container, timeout, "sh", "-c",
                "mkdir -p " + containerReportsDir
                        + " && java -jar /tmp/junit-console-standalone.jar execute"
                        + " --scan-class-path"
                        + " --class-path /tmp/out"
                        + " --include-classname '.*" + className + "'"
                        + " --reports-dir " + containerReportsDir
                        + " --disable-ansi-colors");
        ReportSummary summary = parseReports(hostReportsDir);
        return summary.tests() > 0 && summary.failures() == 0 && summary.errors() == 0;
    }

    private ReportSummary parseReports(Path reportsDir) {
        int tests = 0;
        int failures = 0;
        int errors = 0;
        try (Stream<Path> files = Files.list(reportsDir)) {
            for (Path xml : files.filter(path -> path.toString().endsWith(".xml")).toList()) {
                Document document = parseXml(xml);
                NodeList suites = document.getElementsByTagName("testsuite");
                for (int i = 0; i < suites.getLength(); i++) {
                    Element suite = (Element) suites.item(i);
                    tests += intAttribute(suite, "tests");
                    failures += intAttribute(suite, "failures");
                    errors += intAttribute(suite, "errors");
                }
            }
        } catch (IOException ex) {
            return new ReportSummary(0, 0, 0);
        }
        return new ReportSummary(tests, failures, errors);
    }

    private static Document parseXml(Path xml) {
        try (InputStream in = Files.newInputStream(xml)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(in);
        } catch (Exception ex) {
            throw new ValidationException("failed to parse surefire report " + xml, ex);
        }
    }

    private static int intAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private ExecResult execWithTimeout(GenericContainer<?> container, Duration timeout, String... command) {
        ScheduledFuture<?> killer = scheduler.schedule(() -> {
            try {
                container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(5).exec();
            } catch (RuntimeException ignored) {
                // container already gone
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return container.execInContainer(command);
        } catch (IOException | InterruptedException ex) {
            throw new ValidationException("failed to execute command in validation container", ex);
        } finally {
            killer.cancel(false);
        }
    }

    private static List<Path> copyTestsIntoWorkspace(Path workspace, List<GeneratedTestFile> tests) {
        List<Path> paths = new ArrayList<>();
        try {
            Path testsRoot = workspace.resolve(".copilotguard-tests");
            for (int i = 0; i < tests.size(); i++) {
                Path target = testsRoot.resolve(String.valueOf(i + 1)).resolve(simpleClassName(tests.get(i).path()) + ".java");
                Files.createDirectories(target.getParent());
                Files.writeString(target, tests.get(i).content());
                paths.add(workspace.relativize(target));
            }
        } catch (IOException ex) {
            throw new ValidationException("failed to stage generated tests in workspace", ex);
        }
        return paths;
    }

    private static String simpleClassName(String filePath) {
        String name = filePath.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace(".java", "");
        return name.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? name : "GeneratedTest";
    }

    private static String tail(String text, int maxLength) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return "..." + trimmed.substring(trimmed.length() - maxLength);
    }

    private static Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException ex) {
            throw new ValidationException("failed to create temporary directory", ex);
        }
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

    private record ReportSummary(int tests, int failures, int errors) {
    }
}

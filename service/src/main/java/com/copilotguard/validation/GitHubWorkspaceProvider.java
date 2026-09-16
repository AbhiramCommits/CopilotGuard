package com.copilotguard.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GitHubWorkspaceProvider implements WorkspaceProvider {

    private static final long GIT_TIMEOUT_SECONDS = 120;

    @Override
    public Path prepare(WorkspaceSpec spec) {
        if (!StringUtils.hasText(spec.cloneUrl())) {
            throw new ValidationException("no clone URL available for workspace checkout");
        }
        Path dir;
        try {
            dir = Files.createTempDirectory("copilotguard-workspace");
        } catch (IOException ex) {
            throw new ValidationException("failed to create workspace directory", ex);
        }
        try {
            run("git", "clone", "--no-checkout", "--depth", "50", spec.cloneUrl(), dir.toString());
            run("git", "-C", dir.toString(), "checkout", spec.headSha());
            return dir;
        } catch (RuntimeException | IOException ex) {
            deleteRecursively(dir);
            if (ex instanceof ValidationException validationException) {
                throw validationException;
            }
            throw new ValidationException("failed to clone workspace: " + ex.getMessage(), ex);
        }
    }

    private static void run(String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ValidationException(
                        "git command timed out: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new ValidationException(
                        "git command failed ("
                                + process.exitValue()
                                + "): "
                                + String.join(" ", command));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ValidationException("interrupted during git operation", ex);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
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
}

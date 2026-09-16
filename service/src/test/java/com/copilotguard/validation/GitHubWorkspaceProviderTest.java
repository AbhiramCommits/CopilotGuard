package com.copilotguard.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GitHubWorkspaceProviderTest {

    private final GitHubWorkspaceProvider provider = new GitHubWorkspaceProvider();

    @Test
    void clonesLocalRepositoryAtHeadSha() throws IOException, InterruptedException {
        Path repoDir = Files.createTempDirectory("copilotguard-provider-repo");
        Files.createDirectories(repoDir.resolve("src/main/java/com/example"));
        Files.writeString(
                repoDir.resolve("src/main/java/com/example/Calculator.java"),
                "package com.example;\npublic class Calculator {\n}\n");
        run("git", "init", repoDir.toString());
        run("git", "-C", repoDir.toString(), "add", ".");
        run(
                "git",
                "-C",
                repoDir.toString(),
                "-c",
                "user.email=test@example.com",
                "-c",
                "user.name=Test",
                "commit",
                "-m",
                "initial");
        String sha = capture("git", "-C", repoDir.toString(), "rev-parse", "HEAD").strip();

        Path workspace =
                provider.prepare(
                        new WorkspaceSpec("acme/widgets", "file://" + repoDir, sha, "base"));

        try {
            assertThat(workspace.resolve("src/main/java/com/example/Calculator.java")).exists();
        } finally {
            deleteRecursively(workspace);
            deleteRecursively(repoDir);
        }
    }

    @Test
    void rejectsMissingCloneUrl() {
        assertThatThrownBy(() -> provider.prepare(new WorkspaceSpec("local", "", "sha", "base")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no clone URL");
    }

    private static void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", command));
        }
    }

    private static String capture(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", command));
        }
        return output;
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // best effort
                                }
                            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}

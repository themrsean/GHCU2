package service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for service.GitService
 *
 * Verified production symbols used:
 * - src/main/java/service/GitService.java : public String buildCommitHistoryMarkdown(Path repoPath)
 * - src/main/java/service/ProcessResult.java : public record ProcessResult(int exitCode, List<String> outputLines)
 */
public class GitServiceTest {

    @Test
    public void buildCommitHistoryMarkdown_noHistory_whenExitNonZeroOrEmpty() {
        // stub ProcessRunner to simulate failure / no output
        ProcessRunner pr = new ProcessRunner() {
            @Override
            public ProcessResult runCaptureLinesWithExitCode(List<String> args, Path workingDir) {
                return new ProcessResult(-1, List.of());
            }
        };

        GitService svc = new GitService(pr);

        String out = svc.buildCommitHistoryMarkdown(Path.of("/some/repo"));

        assertNotNull(out);
        assertTrue(out.contains("No commit history available"), "Expected message when no history available");
        // should end with a line separator
        assertTrue(out.endsWith(System.lineSeparator()));
    }

    @Test
    public void buildCommitHistoryMarkdown_withHistory_outputsCodeBlock() {
        List<String> lines = List.of(
                "abc123 2021-01-01 Alice - initial commit",
                "def456 2021-01-02 Bob - second"
        );

        ProcessRunner pr = new ProcessRunner() {
            @Override
            public ProcessResult runCaptureLinesWithExitCode(List<String> args, Path workingDir) {
                return new ProcessResult(0, lines);
            }
        };

        GitService svc = new GitService(pr);

        String out = svc.buildCommitHistoryMarkdown(Path.of("/some/repo"));

        String nl = System.lineSeparator();
        assertNotNull(out);
        assertTrue(out.startsWith("```" + nl), "Should start with code fence");
        // each line from process output should appear in the markdown
        for (String l : lines) {
            assertTrue(out.contains(l + nl), () -> "Expected line in output: " + l);
        }
        assertTrue(out.endsWith("```" + nl), "Should end with code fence and newline");
    }
}

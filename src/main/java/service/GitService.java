package service;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class GitService {

    private final ProcessRunner processRunner;

    public GitService(ProcessRunner processRunner) {
        this.processRunner = Objects.requireNonNull(processRunner);
    }

    public String buildCommitHistoryMarkdown(Path repoPath) {
        StringBuilder sb = new StringBuilder();

        final int historyCount = 10;
        List<String> args = List.of(
                "git",
                "log",
                "-n",
                String.valueOf(historyCount),
                "--pretty=format:%h %ad %an - %s",
                "--date=short"
        );

        ProcessResult result = processRunner.runCaptureLinesWithExitCode(args, repoPath);

        if (result.exitCode() != 0 || result.outputLines().isEmpty()) {
            sb.append("_No commit history available._").append(System.lineSeparator());
        } else {
            sb.append("```").append(System.lineSeparator());
            for (String line : result.outputLines()) {
                sb.append(line).append(System.lineSeparator());
            }
            sb.append("```").append(System.lineSeparator());
        }

        return sb.toString();
    }
}

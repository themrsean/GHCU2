/*
 * Course: CSC-1120
 * GitHub Classroom Utilities
 */
package service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class GradingSyncService {

    private static final long GIT_COMMAND_TIMEOUT_SECONDS = 30L;

    public interface DraftAccess {

        boolean isLoadedFromDisk(String studentPackage);

        String getMarkdown(String studentPackage);

        void setMarkdown(String studentPackage, String markdown);

        void setLoadedFromDisk(String studentPackage, boolean loadedFromDisk);

        void setCaretPosition(String studentPackage, int caretPosition);
    }

    public SaveDraftResult saveDrafts(List<String> studentPackages,
                                      DraftAccess draftAccess,
                                      String currentStudent,
                                      Function<String, String> initialMarkdownLoader,
                                      Function<String, Path> repoDirFinder,
                                      GradingDraftService gradingDraftService,
                                      String assignmentId) {
        return saveDrafts(
                studentPackages,
                draftAccess,
                currentStudent,
                initialMarkdownLoader,
                repoDirFinder,
                gradingDraftService,
                assignmentId,
                null
        );
    }

    public SaveDraftResult saveDrafts(List<String> studentPackages,
                                      DraftAccess draftAccess,
                                      String currentStudent,
                                      Function<String, String> initialMarkdownLoader,
                                      Function<String, Path> repoDirFinder,
                                      GradingDraftService gradingDraftService,
                                      String assignmentId,
                                      Path feedbackRoot) {
        boolean success = true;
        int wrote = 0;
        String message = "";
        List<String> failureDetails = new ArrayList<>();

        for (String pkg : studentPackages) {
            boolean needsLoad = !draftAccess.isLoadedFromDisk(pkg);
            String markdown = draftAccess.getMarkdown(pkg);

            if (needsLoad || markdown == null || markdown.trim().isEmpty()) {
                markdown = initialMarkdownLoader.apply(pkg);
                draftAccess.setMarkdown(pkg, markdown);
                draftAccess.setLoadedFromDisk(pkg, true);

                if (!pkg.equals(currentStudent)) {
                    draftAccess.setCaretPosition(pkg, 0);
                }
            }

            Path repoDir = repoDirFinder.apply(pkg);
            if (repoDir == null) {
                success = false;
                failureDetails.add("Could not find repo for " + pkg);
            } else {
                try {
                    String markdownToSave = markdown == null ? "" : markdown;

                    gradingDraftService.saveReportMarkdown(
                            assignmentId,
                            pkg,
                            repoDir,
                            markdownToSave,
                            feedbackRoot
                    );

                    wrote++;
                } catch (IOException e) {
                    success = false;
                    failureDetails.add("Failed writing report for "
                            + pkg + ": " + e.getMessage());
                }
            }
        }

        if (success) {
            message = "Saved " + wrote + " HTML report(s) to student repositories.";
        } else {
            message = summarizeDetails(failureDetails);
        }

        return new SaveDraftResult(success, message);
    }

    public PushResult pushAllRepos(List<String> studentPackages,
                                   Function<String, Path> repoDirFinder,
                                   String assignmentId) {
        int pushed = 0;
        int skipped = 0;
        int failed = 0;
        List<String> details = new ArrayList<>();

        for (String pkg : studentPackages) {
            Path repoDir = repoDirFinder.apply(pkg);
            if (repoDir == null) {
                failed++;
                details.add(pkg + ": repo path missing");
                continue;
            }

            String baseName = assignmentId + pkg;
            Path outHtml = repoDir.resolve(baseName + ".html");

            if (!Files.exists(outHtml)) {
                skipped++;
                details.add(pkg + ": report file missing");
                continue;
            }

            try {
                PreflightResult preflight = preflightPush(repoDir, outHtml.getFileName().toString());

                if (!preflight.allowed()) {
                    skipped++;
                    details.add(pkg + ": " + preflight.message());
                    continue;
                }

                GitCommandResult pull = runGit(repoDir, "pull");

                if (pull.exitCode() != 0) {
                    failed++;
                    details.add(pkg + ": git pull failed: "
                            + summarizeGitOutput(pull.output()));
                    continue;
                }

                GitCommandResult add = runGit(repoDir, "add", "--", "*.html");

                if (add.exitCode() != 0) {
                    failed++;
                    details.add(pkg + ": git add failed: " + summarizeGitOutput(add.output()));
                } else {
                    GitCommandResult commit =
                            runGit(repoDir, "commit", "-m", "Add feedback for " + assignmentId);

                    if (commit.exitCode() != 0) {
                        skipped++;
                        details.add(pkg + ": git commit skipped: "
                                + summarizeGitOutput(commit.output()));
                    } else {
                        GitCommandResult push = runGit(repoDir, "push");

                        if (push.exitCode() != 0) {
                            failed++;
                            details.add(pkg + ": git push failed: "
                                    + summarizeGitOutput(push.output()));
                        } else {
                            pushed++;
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                failed++;
                details.add(pkg + ": " + e.getMessage());
            }
        }

        return new PushResult(pushed, skipped, failed, summarizeDetails(details));
    }

    public PreflightResult preflightPush(Path repoDir,
                                         String reportFileName)
            throws IOException, InterruptedException {
        GitCommandResult branch = runGit(repoDir, "rev-parse", "--abbrev-ref", "HEAD");

        if (branch.exitCode() != 0) {
            return new PreflightResult(false, "unable to determine current branch");
        }

        String branchName = branch.output().trim();
        if (branchName.isBlank() || "HEAD".equals(branchName)) {
            return new PreflightResult(false, "repository is on a detached HEAD");
        }

        GitCommandResult upstream = runGit(
                repoDir,
                "rev-parse",
                "--abbrev-ref",
                "--symbolic-full-name",
                "@{u}"
        );

        if (upstream.exitCode() != 0) {
            return new PreflightResult(false, "branch has no upstream");
        }

        return new PreflightResult(true, "");
    }

    public String summarizeDetails(List<String> details) {
        if (details == null || details.isEmpty()) {
            return "";
        }

        int detailLimit = Math.min(3, details.size());
        return String.join("; ", details.subList(0, detailLimit));
    }

    static List<String> parsePorcelainStatusPaths(String output) {
        List<String> paths = new ArrayList<>();

        if (output == null || output.isEmpty()) {
            return paths;
        }

        String[] tokens = output.split("\\u0000", -1);

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token == null || token.isEmpty()) {
                continue;
            }

            final int minHeaderLength = 4;
            final int pathStartIndex = 3;
            if (token.length() < minHeaderLength) {
                continue;
            }

            String status = token.substring(0, 2);
            String pathText = token.substring(pathStartIndex);

            if (isRenameOrCopyStatus(status) && i + 1 < tokens.length) {
                String targetPath = tokens[i + 1];
                if (targetPath != null && !targetPath.isEmpty()) {
                    pathText = targetPath;
                    i++;
                }
            }

            String normalized = pathText == null ? "" : pathText.trim();
            if (!normalized.isEmpty()) {
                paths.add(normalized);
            }
        }

        return paths;
    }

    private static boolean isRenameOrCopyStatus(String status) {
        if (status == null || status.length() < 2) {
            return false;
        }

        char x = status.charAt(0);
        char y = status.charAt(1);
        return x == 'R' || x == 'C' || y == 'R' || y == 'C';
    }

    private String summarizeGitOutput(String output) {
        if (output == null || output.isBlank()) {
            return "no output";
        }

        String[] lines = output.split("\\R");
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }

        return "no output";
    }

    private GitCommandResult runGit(Path repoDir,
                                    String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        Collections.addAll(cmd, args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GCM_INTERACTIVE", "never");

        Process p = pb.start();
        boolean finished = p.waitFor(GIT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return new GitCommandResult(
                    124,
                    "git command timed out after " + GIT_COMMAND_TIMEOUT_SECONDS + " seconds"
            );
        }

        String output;
        try (var in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode = p.exitValue();
        return new GitCommandResult(exitCode, output);
    }

    private record GitCommandResult(int exitCode,
                                    String output) {
    }

    public record PreflightResult(boolean allowed,
                                  String message) {
    }

    public record PushResult(int pushed,
                             int skipped,
                             int failed,
                             String detailSummary) {
    }

    public record SaveDraftResult(boolean success,
                                  String message) {
    }
}

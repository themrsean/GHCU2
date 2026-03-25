package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GradingSyncServiceTest {

    @Test
    public void summarizeDetails_limitsSummaryToFirstThreeEntries() {
        GradingSyncService service = new GradingSyncService();

        String summary = service.summarizeDetails(List.of("one", "two", "three", "four"));

        assertEquals("one; two; three", summary);
    }

    @Test
    public void parsePorcelainStatusPaths_parsesNormalAndRenameEntries() {
        String output = " M A1pkg.html\u0000R  old-report.html\u0000new report.html\u0000"
                + "?? notes.txt\u0000";

        List<String> paths = GradingSyncService.parsePorcelainStatusPaths(output);

        assertEquals(List.of("A1pkg.html", "new report.html", "notes.txt"), paths);
    }

    @Test
    public void saveDrafts_writesHtmlReportAndReturnsSuccess(@TempDir Path tmp) {
        GradingSyncService service = new GradingSyncService();
        TestDraftAccess draftAccess = new TestDraftAccess();
        GradingDraftService gradingDraftService =
                new GradingDraftService(new ReportHtmlWrapper());
        Path repoDir = tmp.resolve("repo-save-success");
        String assignmentId = "A1";
        String studentPackage = "smith";

        draftAccess.setMarkdown(studentPackage, "# Feedback");
        draftAccess.setLoadedFromDisk(studentPackage, true);
        createDirectories(repoDir);

        GradingSyncService.SaveDraftResult result = service.saveDrafts(
                List.of(studentPackage),
                draftAccess,
                studentPackage,
                ignored -> "",
                ignored -> repoDir,
                gradingDraftService,
                assignmentId
        );

        assertTrue(result.success());
        assertEquals("Saved 1 HTML report(s) to student repositories.", result.message());
        assertTrue(Files.exists(repoDir.resolve("A1smith.html")));
    }

    @Test
    public void saveDrafts_collectsAndLimitsFailureDetails() {
        GradingSyncService service = new GradingSyncService();
        TestDraftAccess draftAccess = new TestDraftAccess();
        GradingDraftService gradingDraftService =
                new GradingDraftService(new ReportHtmlWrapper());
        List<String> studentPackages = List.of("pkg1", "pkg2", "pkg3", "pkg4");

        for (String pkg : studentPackages) {
            draftAccess.setMarkdown(pkg, "# Feedback");
            draftAccess.setLoadedFromDisk(pkg, true);
        }

        GradingSyncService.SaveDraftResult result = service.saveDrafts(
                studentPackages,
                draftAccess,
                "pkg1",
                ignored -> "",
                ignored -> null,
                gradingDraftService,
                "A1"
        );

        assertFalse(result.success());
        assertEquals(
                "Could not find repo for pkg1; Could not find repo for pkg2; "
                        + "Could not find repo for pkg3",
                result.message()
        );
    }

    @Test
    public void preflightPush_allowsWhenBranchAndUpstreamConfigured(@TempDir Path tmp)
            throws Exception {
        GradingSyncService service = new GradingSyncService();
        Path repoDir = createRepoWithUpstream(tmp);

        Files.writeString(repoDir.resolve("report.html"), "feedback");

        GradingSyncService.PreflightResult result =
                service.preflightPush(repoDir, "report.html");

        assertTrue(result.allowed());
        assertEquals("", result.message());
    }

    @Test
    public void preflightPush_allowsReportFileWithSpaceInName(@TempDir Path tmp) throws Exception {
        GradingSyncService service = new GradingSyncService();
        Path repoDir = createRepoWithUpstream(tmp);
        String reportName = "report with space.html";

        Files.writeString(repoDir.resolve(reportName), "feedback");

        GradingSyncService.PreflightResult result =
                service.preflightPush(repoDir, reportName);

        assertTrue(result.allowed());
        assertEquals("", result.message());
    }

    @Test
    public void pushAllRepos_collectsDetailSummaryForReportedFailures(@TempDir Path tmp)
            throws Exception {
        GradingSyncService service = new GradingSyncService();
        Path repoNotGit = tmp.resolve("repo-not-git");
        Path repoMissingReport = tmp.resolve("repo-missing-report");
        Path repoNoUpstream = createRepoWithCommit(tmp.resolve("repo-no-upstream-push"));

        Files.createDirectories(repoNotGit);
        Files.createDirectories(repoMissingReport);
        Files.writeString(repoNotGit.resolve("A1pkg1.html"), "feedback");
        Files.writeString(repoNoUpstream.resolve("A1pkg3.html"), "feedback");

        Map<String, Path> repos = Map.of(
                "pkg1", repoNotGit,
                "pkg2", repoMissingReport,
                "pkg3", repoNoUpstream
        );

        GradingSyncService.PushResult result = service.pushAllRepos(
                List.of("pkg1", "pkg2", "pkg3"),
                repos::get,
                "A1"
        );

        assertEquals(0, result.pushed());
        assertEquals(3, result.skipped());
        assertEquals(0, result.failed());
        assertEquals(
                "pkg1: unable to determine current branch; pkg2: report file missing; "
                        + "pkg3: branch has no upstream",
                result.detailSummary()
        );
    }

    @Test
    public void pushAllRepos_nonFastForwardPush_isRecoveredByPullBeforePush(
            @TempDir Path tmp
    ) throws Exception {
        GradingSyncService service = new GradingSyncService();
        Path remoteDir = tmp.resolve("remote-nff.git");
        Path localRepo = createRepoWithUpstream(tmp.resolve("repo-local"), remoteDir);
        Path divergingClone = tmp.resolve("repo-diverge");
        cloneRepo(remoteDir, divergingClone);
        runGit(divergingClone, "checkout", "-B", "main", "origin/main");

        // Advance remote history from another clone so local push becomes non-fast-forward.
        Files.writeString(divergingClone.resolve("ADVANCE.txt"), "remote advanced");
        runGit(divergingClone, "add", "ADVANCE.txt");
        runGit(divergingClone, "commit", "-m", "advance remote");
        runGit(divergingClone, "push", "-u", "origin", "main");

        Files.writeString(localRepo.resolve("A1pkg1.html"), "feedback");
        Map<String, Path> repos = Map.of("pkg1", localRepo);

        GradingSyncService.PushResult result = service.pushAllRepos(
                List.of("pkg1"),
                repos::get,
                "A1"
        );

        assertEquals(1, result.pushed());
        assertEquals(0, result.skipped());
        assertEquals(0, result.failed());
        assertEquals("", result.detailSummary());
    }

    @Test
    public void pushAllRepos_unrelatedNonHtmlChange_stillPushesHtmlReport(@TempDir Path tmp)
            throws Exception {
        GradingSyncService service = new GradingSyncService();
        Path repoDir = createRepoWithUpstream(tmp);

        Files.writeString(repoDir.resolve("NOTES.txt"), "do not stage");
        Files.writeString(repoDir.resolve("A1pkg1.html"), "feedback");

        Map<String, Path> repos = Map.of("pkg1", repoDir);

        GradingSyncService.PushResult result = service.pushAllRepos(
                List.of("pkg1"),
                repos::get,
                "A1"
        );

        assertEquals(1, result.pushed());
        assertEquals(0, result.skipped());
        assertEquals(0, result.failed());
        assertEquals("", result.detailSummary());
    }

    @Test
    public void pushAllRepos_pullFailure_reportsGitPullFailure(@TempDir Path tmp)
            throws Exception {
        GradingSyncService service = new GradingSyncService();
        Path remoteDir = tmp.resolve("remote-pull-fail.git");
        Path localRepo = createRepoWithUpstream(tmp.resolve("repo-local-pull-fail"), remoteDir);
        Path divergingClone = tmp.resolve("repo-diverge-pull-fail");
        cloneRepo(remoteDir, divergingClone);
        runGit(divergingClone, "checkout", "-B", "main", "origin/main");

        Files.writeString(divergingClone.resolve("README.md"), "remote update");
        runGit(divergingClone, "add", "README.md");
        runGit(divergingClone, "commit", "-m", "remote update");
        runGit(divergingClone, "push", "-u", "origin", "main");

        Files.writeString(localRepo.resolve("README.md"), "local uncommitted change");
        Files.writeString(localRepo.resolve("A1pkg1.html"), "feedback");

        Map<String, Path> repos = Map.of("pkg1", localRepo);

        GradingSyncService.PushResult result = service.pushAllRepos(
                List.of("pkg1"),
                repos::get,
                "A1"
        );

        assertEquals(0, result.pushed());
        assertEquals(0, result.skipped());
        assertEquals(1, result.failed());
        assertTrue(result.detailSummary().startsWith("pkg1: git pull failed:"));
    }

    @Test
    public void pushAllRepos_fourFailures_truncatesDetailSummaryToFirstThree(@TempDir Path tmp)
            throws Exception {
        GradingSyncService service = new GradingSyncService();
        Path repoNotGitA = tmp.resolve("repo-not-git-a");
        Path repoNotGitB = tmp.resolve("repo-not-git-b");
        Path repoNotGitC = tmp.resolve("repo-not-git-c");
        Path repoNotGitD = tmp.resolve("repo-not-git-d");

        Files.createDirectories(repoNotGitA);
        Files.createDirectories(repoNotGitB);
        Files.createDirectories(repoNotGitC);
        Files.createDirectories(repoNotGitD);

        Files.writeString(repoNotGitA.resolve("A1pkg1.html"), "feedback");
        Files.writeString(repoNotGitB.resolve("A1pkg2.html"), "feedback");
        Files.writeString(repoNotGitC.resolve("A1pkg3.html"), "feedback");
        Files.writeString(repoNotGitD.resolve("A1pkg4.html"), "feedback");

        Map<String, Path> repos = Map.of(
                "pkg1", repoNotGitA,
                "pkg2", repoNotGitB,
                "pkg3", repoNotGitC,
                "pkg4", repoNotGitD
        );

        GradingSyncService.PushResult result = service.pushAllRepos(
                List.of("pkg1", "pkg2", "pkg3", "pkg4"),
                repos::get,
                "A1"
        );

        assertEquals(0, result.pushed());
        assertEquals(4, result.skipped());
        assertEquals(0, result.failed());
        assertEquals(
                "pkg1: unable to determine current branch; "
                        + "pkg2: unable to determine current branch; "
                        + "pkg3: unable to determine current branch",
                result.detailSummary()
        );
    }

    private Path createRepoWithCommit(Path repoDir) throws Exception {
        Files.createDirectories(repoDir);
        runGit(repoDir, "init");
        runGit(repoDir, "config", "user.email", "test@example.com");
        runGit(repoDir, "config", "user.name", "Test User");
        Files.writeString(repoDir.resolve("README.md"), "init");
        runGit(repoDir, "add", "README.md");
        runGit(repoDir, "commit", "-m", "init");
        runGit(repoDir, "branch", "-M", "main");
        return repoDir;
    }

    private Path createRepoWithUpstream(Path tmp) throws Exception {
        Path remoteDir = tmp.resolve("remote.git");
        Path repoDir = tmp.resolve("repo-with-upstream");

        Files.createDirectories(remoteDir);
        runGit(remoteDir, "init", "--bare");

        createRepoWithCommit(repoDir);
        runGit(repoDir, "remote", "add", "origin", remoteDir.toString());
        runGit(repoDir, "push", "-u", "origin", "main");

        return repoDir;
    }

    private Path createRepoWithUpstream(Path repoDir,
                                        Path remoteDir) throws Exception {
        Files.createDirectories(remoteDir);
        runGit(remoteDir, "init", "--bare");

        createRepoWithCommit(repoDir);
        runGit(repoDir, "remote", "add", "origin", remoteDir.toString());
        runGit(repoDir, "push", "-u", "origin", "main");

        return repoDir;
    }

    private void cloneRepo(Path remoteDir,
                           Path cloneDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "git",
                "clone",
                remoteDir.toString(),
                cloneDir.toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, output);

        runGit(cloneDir, "config", "user.email", "test@example.com");
        runGit(cloneDir, "config", "user.name", "Test User");
    }

    private void runGit(Path workingDir,
                        String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(args));
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
    }

    private List<String> buildCommand(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }

    private void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class TestDraftAccess implements GradingSyncService.DraftAccess {

        private final Map<String, String> markdownByStudent = new HashMap<>();
        private final Map<String, Boolean> loadedByStudent = new HashMap<>();
        private final Map<String, Integer> caretByStudent = new HashMap<>();

        @Override
        public boolean isLoadedFromDisk(String studentPackage) {
            return loadedByStudent.getOrDefault(studentPackage, false);
        }

        @Override
        public String getMarkdown(String studentPackage) {
            return markdownByStudent.get(studentPackage);
        }

        @Override
        public void setMarkdown(String studentPackage, String markdown) {
            markdownByStudent.put(studentPackage, markdown);
        }

        @Override
        public void setLoadedFromDisk(String studentPackage, boolean loadedFromDisk) {
            loadedByStudent.put(studentPackage, loadedFromDisk);
        }

        @Override
        public void setCaretPosition(String studentPackage, int caretPosition) {
            caretByStudent.put(studentPackage, caretPosition);
        }
    }
}

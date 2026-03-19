/*
 * Course: CSC-1110/1120
 * GitHub Classroom Utilities
 */
package service;

import model.Assignment;
import model.AssignmentsFile;
import model.RepoMapping;
import model.RubricTableBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ReportService {

    private final AssignmentsFile assignmentsFile;
    private final ReportDependencies deps;

    public ReportService(AssignmentsFile assignmentsFile,
                         ReportDependencies deps) {

        this.assignmentsFile = Objects.requireNonNull(assignmentsFile);
        this.deps = Objects.requireNonNull(deps);
    }

    public ReportGenerationResult generateReports(Assignment assignment,
                                                  Path selectedRootPath,
                                                  Path mappingsPath) {

        boolean wroteAny = false;
        boolean hadFailures = false;

        if (assignment == null) {
            deps.log("Generate Reports failed: assignment is null.");
            return new ReportGenerationResult(false, false);
        }
        if (selectedRootPath == null) {
            deps.log("Generate Reports failed: selectedRootPath is null.");
            return new ReportGenerationResult(false, false);
        }
        if (mappingsPath == null) {
            deps.log("Generate Reports failed: mappingsPath is null.");
            return new ReportGenerationResult(false, false);
        }

        final String assignmentId = assignment.getCourseCode() + assignment.getAssignmentCode();
        final String packagesFolderName = "packages";
        final String reportExtension = ".html";

        Path packagesRoot = selectedRootPath.resolve(packagesFolderName);

        if (!Files.exists(packagesRoot) || !Files.isDirectory(packagesRoot)) {
            deps.log("Generate Reports failed: packages folder missing: " + packagesRoot);
            deps.log("Run Extract Packages first.");
            return new ReportGenerationResult(false, false);
        }

        if (!Files.exists(mappingsPath) || !Files.isRegularFile(mappingsPath)) {
            deps.log("Generate Reports failed: mapping.json not found: " + mappingsPath);
            deps.log("Run Extract Packages first.");
            return new ReportGenerationResult(false, false);
        }

        Map<String, RepoMapping> mapping = deps.loadMapping(mappingsPath);

        if (mapping == null || mapping.isEmpty()) {
            deps.log("Generate Reports failed: mapping.json is empty or invalid.");
            return new ReportGenerationResult(false, false);
        }

        List<String> packageNames = new ArrayList<>(mapping.keySet());
        packageNames.sort(String::compareTo);

        deps.log("Generating reports for " + packageNames.size() + " student package(s).");

        for (String pkg : packageNames) {

            RepoMapping repo = mapping.get(pkg);
            if (repo == null) {
                deps.log("SKIP " + pkg + ": mapping missing.");
                hadFailures = true;
                continue;
            }

            String repoPathStr = repo.getRepoPath();
            if (repoPathStr == null || repoPathStr.trim().isEmpty()) {
                deps.log("SKIP " + pkg + ": repoPath missing in mapping.");
                hadFailures = true;
                continue;
            }

            Path mappedRepoPath = Path.of(repoPathStr);

            if (!Files.exists(mappedRepoPath) || !Files.isDirectory(mappedRepoPath)) {
                deps.log("SKIP " + pkg + ": repo path missing: " + mappedRepoPath);
                hadFailures = true;
                continue;
            }

            Path repoRoot = deps.resolveRepoRoot(mappedRepoPath);

            if (repoRoot == null || !Files.isDirectory(repoRoot)) {
                deps.log("SKIP " + pkg + ": could not resolve repo root from: " + mappedRepoPath);
                hadFailures = true;
                continue;
            }

            String reportFileName = assignmentId + pkg + reportExtension;
            Path reportPath = repoRoot.resolve(reportFileName);

            try {
                String markdown = buildReportMarkdown(assignment, pkg, repoRoot);
                String html = deps.wrapMarkdownAsHtml(pkg, markdown);

                Files.deleteIfExists(reportPath);
                Files.writeString(reportPath, html);

                wroteAny = true;
                deps.log("OK " + pkg + ": wrote report " + reportFileName);

            } catch (IOException e) {
                deps.log("FAIL " + pkg + ": could not write report: " + e.getMessage());
                hadFailures = true;
            }
        }

        deps.log("Generate Reports complete.");

        return new ReportGenerationResult(wroteAny, hadFailures);
    }

    private String buildReportMarkdown(Assignment assignment,
                                       String studentPackage,
                                       Path repoPath) {

        final String newline = System.lineSeparator();
        final String assignmentId = assignment.getCourseCode() + assignment.getAssignmentCode();

        CheckstyleService.CheckstyleResult cs = deps.buildCheckstyleResult(repoPath);

        UnitTestService.UnitTestResult ut =
                deps.buildUnitTestResultMarkdown(studentPackage, repoPath);

        Map<String, Integer> manualDeductions =
                deps.loadManualDeductionsFromGradingDraft(
                        assignmentId,
                        studentPackage,
                        repoPath
                );

        String feedbackMarkdown =
                deps.loadFeedbackSectionMarkdown(
                        assignmentId,
                        studentPackage,
                        repoPath
                );

        if (feedbackMarkdown == null || feedbackMarkdown.isBlank()) {
            feedbackMarkdown = "> * No feedback provided";
        }

        String rubricTableMarkdown =
                RubricTableBuilder.buildRubricMarkdown(
                        assignment,
                        assignmentsFile,
                        cs.totalViolations(),
                        (double) ut.failedTests(),
                        ut.totalTests(),
                        manualDeductions
                );

        return
                "# " + assignment.getAssignmentName() + newline + newline +
                        rubricTableMarkdown + newline +
                        ">" + newline +
                        ">" + newline +
                        "> # Feedback" + newline +
                        feedbackMarkdown + newline + newline +
                        deps.buildSourceCodeMarkdown(assignment, studentPackage, repoPath) + newline + newline +
                        "## Checkstyle Violations" + newline + newline +
                        cs.markdown() + newline + newline +
                        "## Failed Unit Tests" + newline + newline +
                        ut.markdown() + newline + newline +
                        "## Commit History (Last 10)" + newline + newline +
                        deps.buildCommitHistoryMarkdown(repoPath) + newline;
    }

    public record ReportGenerationResult(boolean wroteAny, boolean hadFailures) {

        public boolean isSuccess() {
            return wroteAny && !hadFailures;
        }
    }

    public interface ReportDependencies {

        void log(String msg);

        Map<String, RepoMapping> loadMapping(Path mappingsPath);

        Path resolveRepoRoot(Path mappedRepoPath);

        CheckstyleService.CheckstyleResult buildCheckstyleResult(Path repoPath);

        UnitTestService.UnitTestResult buildUnitTestResultMarkdown(
                String studentPackage,
                Path repoPath);

        Map<String, Integer> loadManualDeductionsFromGradingDraft(
                String assignmentId,
                String studentPackage,
                Path rootPath);

        String loadFeedbackSectionMarkdown(
                String assignmentId,
                String studentPackage,
                Path rootPath);

        String buildSourceCodeMarkdown(
                Assignment assignment,
                String studentPackage,
                Path repoPath);

        String buildCommitHistoryMarkdown(Path repoPath);

        String wrapMarkdownAsHtml(String title, String markdown);
    }
}
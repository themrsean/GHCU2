package service.steps;

import model.AssignmentsFile;
import model.Assignment;
import model.RepoMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import service.ReportService;
import service.ServiceLogger;
import service.WorkflowContext;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for service.steps.ReportsStep
 *
 * Verified production sources read before writing these tests:
 * - src/main/java/service/steps/ReportsStep.java
 *     public RunAllStep stepType()
 *     public StepResult execute(WorkflowContext context)
 * - src/main/java/service/ReportService.java
 *     public ReportGenerationResult generateReports(Assignment assignment, Path selectedRootPath, Path mappingsPath)
 * - src/main/java/service/WorkflowContext.java
 *     public WorkflowContext(String cloneCmd, Assignment assignment, Path root, Path mappingsPath)
 */
public class ReportsStepTest {

    @Test
    public void stepType_isReports() {
        ServiceLogger logger = s -> {};

        // Minimal ReportDependencies implementation used only to construct ReportService
        ReportService.ReportDependencies deps = new ReportService.ReportDependencies() {
            @Override public void log(String msg) { }
            @Override public Map<String, RepoMapping> loadMapping(Path mappingsPath) { return Collections.emptyMap(); }
            @Override public Path resolveRepoRoot(Path mappedRepoPath) { return null; }
            @Override public service.CheckstyleService.CheckstyleResult buildCheckstyleResult(Path repoPath) { return new service.CheckstyleService.CheckstyleResult("", 0); }
            @Override public service.UnitTestService.UnitTestResult buildUnitTestResultMarkdown(String studentPackage, Path repoPath) { return new service.UnitTestService.UnitTestResult("", 0, 0); }
            @Override public java.util.Map<String, Integer> loadManualDeductionsFromGradingDraft(String assignmentId, String studentPackage, Path rootPath) { return Collections.emptyMap(); }
            @Override public String loadFeedbackSectionMarkdown(String assignmentId,
                                                                String studentPackage,
                                                                Path rootPath) { return ""; }
            @Override public String buildSourceCodeMarkdown(Assignment assignment, String studentPackage, Path repoPath) { return ""; }
            @Override public String buildCommitHistoryMarkdown(Path repoPath) { return ""; }
            @Override public String wrapMarkdownAsHtml(String title, String markdown) { return markdown; }
        };

        ReportService rs = new ReportService(new AssignmentsFile(), deps) {
            @Override
            public ReportGenerationResult generateReports(Assignment assignment, Path selectedRootPath, Path mappingsPath) {
                return new ReportGenerationResult(true, false);
            }
        };

        ReportsStep step = new ReportsStep(rs, logger);
        assertEquals(RunAllStep.REPORTS, step.stepType());
    }

    @Test
    public void execute_success_returnsSuccess(@TempDir Path tmp) {
        AtomicReference<String> lastLog = new AtomicReference<>();
        ServiceLogger logger = lastLog::set;

        ReportService.ReportDependencies deps = new ReportService.ReportDependencies() {
            @Override public void log(String msg) { }
            @Override public Map<String, RepoMapping> loadMapping(Path mappingsPath) { return Collections.emptyMap(); }
            @Override public Path resolveRepoRoot(Path mappedRepoPath) { return null; }
            @Override public service.CheckstyleService.CheckstyleResult buildCheckstyleResult(Path repoPath) { return new service.CheckstyleService.CheckstyleResult("", 0); }
            @Override public service.UnitTestService.UnitTestResult buildUnitTestResultMarkdown(String studentPackage, Path repoPath) { return new service.UnitTestService.UnitTestResult("", 0, 0); }
            @Override public java.util.Map<String, Integer> loadManualDeductionsFromGradingDraft(String assignmentId, String studentPackage, Path rootPath) { return Collections.emptyMap(); }
            @Override public String loadFeedbackSectionMarkdown(String assignmentId,
                                                                String studentPackage,
                                                                Path rootPath) { return ""; }
            @Override public String buildSourceCodeMarkdown(Assignment assignment, String studentPackage, Path repoPath) { return ""; }
            @Override public String buildCommitHistoryMarkdown(Path repoPath) { return ""; }
            @Override public String wrapMarkdownAsHtml(String title, String markdown) { return markdown; }
        };

        ReportService rs = new ReportService(new AssignmentsFile(), deps) {
            @Override
            public ReportGenerationResult generateReports(Assignment assignment, Path selectedRootPath, Path mappingsPath) {
                return new ReportGenerationResult(true, false);
            }
        };

        ReportsStep step = new ReportsStep(rs, logger);
        WorkflowContext ctx = new WorkflowContext("cmd", null, tmp, tmp.resolve("mappings.json"));

        StepResult result = step.execute(ctx);

        assertTrue(result.isSuccess());
        assertFalse(result.isFailed());
        assertEquals("", result.message());
        assertNull(lastLog.get(), "No log expected on success");
    }

    @Test
    public void execute_failure_logsAndReturnsFailed(@TempDir Path tmp) {
        AtomicReference<String> lastLog = new AtomicReference<>();
        ServiceLogger logger = lastLog::set;

        ReportService.ReportDependencies deps = new ReportService.ReportDependencies() {
            @Override public void log(String msg) { lastLog.set(msg); }
            @Override public Map<String, RepoMapping> loadMapping(Path mappingsPath) { return Collections.emptyMap(); }
            @Override public Path resolveRepoRoot(Path mappedRepoPath) { return null; }
            @Override public service.CheckstyleService.CheckstyleResult buildCheckstyleResult(Path repoPath) { return new service.CheckstyleService.CheckstyleResult("", 0); }
            @Override public service.UnitTestService.UnitTestResult buildUnitTestResultMarkdown(String studentPackage, Path repoPath) { return new service.UnitTestService.UnitTestResult("", 0, 0); }
            @Override public java.util.Map<String, Integer> loadManualDeductionsFromGradingDraft(String assignmentId, String studentPackage, Path rootPath) { return Collections.emptyMap(); }
            @Override public String loadFeedbackSectionMarkdown(String assignmentId,
                                                                String studentPackage,
                                                                Path rootPath) { return ""; }
            @Override public String buildSourceCodeMarkdown(Assignment assignment, String studentPackage, Path repoPath) { return ""; }
            @Override public String buildCommitHistoryMarkdown(Path repoPath) { return ""; }
            @Override public String wrapMarkdownAsHtml(String title, String markdown) { return markdown; }
        };

        ReportService rs = new ReportService(new AssignmentsFile(), deps) {
            @Override
            public ReportGenerationResult generateReports(Assignment assignment, Path selectedRootPath, Path mappingsPath) {
                return new ReportGenerationResult(false, false);
            }
        };

        ReportsStep step = new ReportsStep(rs, logger);
        WorkflowContext ctx = new WorkflowContext("cmd", null, tmp, tmp.resolve("mappings.json"));

        StepResult result = step.execute(ctx);

        assertTrue(result.isFailed());
        assertFalse(result.isSuccess());
        assertEquals("Report generation failed.", result.message());

        assertNotNull(lastLog.get());
        assertTrue(lastLog.get().contains("Report generation failed."));
    }
}

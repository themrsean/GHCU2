package service;

import model.AssignmentsFile;
import model.Assignment;
import model.RepoMapping;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ReportServiceTest {

    /**
     * Tests a successful report generation flow driven by a minimal ReportDependencies stub.
     *
     * This exercises ReportService.generateReports(Assignment, Path, Path) and verifies
     * that a report file is written to the resolved repo root and the result reports success.
     */
    @Test
    public void generateReports_writesReportFile_andReturnsSuccess() throws Exception {
        AssignmentsFile af = new AssignmentsFile();

        // Minimal assignment used to build report filename and title
        Assignment a = new Assignment();
        a.setCourseCode("CSC101");
        a.setAssignmentCode("A1");
        a.setAssignmentName("Intro Assignment");

        Path root = Files.createTempDirectory("rs-root");
        // create packages folder as expected by ReportService
        Files.createDirectories(root.resolve("packages"));

        Path mappingsPath = Files.createTempFile("mapping", ".json");

        // Create a fake student repo directory that the mapping will point to
        Path studentRepo = Files.createTempDirectory("student-repo");

        // Build a mapping with one package
        Map<String, RepoMapping> mapping = new HashMap<>();
        RepoMapping rm = new RepoMapping();
        rm.setRepoPath(studentRepo.toString());
        mapping.put("username1", rm);

        // Create a stub dependencies implementation
        ReportService.ReportDependencies deps = new ReportService.ReportDependencies() {
            @Override
            public void log(String msg) {
                // no-op for test
            }

            @Override
            public Map<String, RepoMapping> loadMapping(Path mappingsPath) {
                return mapping;
            }

            @Override
            public Path resolveRepoRoot(Path mappedRepoPath) {
                // In this test the mapped repo path is already the repo root
                return mappedRepoPath;
            }

            @Override
            public CheckstyleService.CheckstyleResult buildCheckstyleResult(Path repoPath) {
                return new CheckstyleService.CheckstyleResult("_No checkstyle violations._", 0);
            }

            @Override
            public UnitTestService.UnitTestResult buildUnitTestResultMarkdown(String studentPackage, Path repoPath) {
                return new UnitTestService.UnitTestResult("_No failed unit tests._", 0, 0);
            }

            @Override
            public Map<String, Integer> loadManualDeductionsFromGradingDraft(String assignmentId, String studentPackage, Path rootPath) {
                return Map.of();
            }

            @Override
            public String loadFeedbackSectionMarkdown(String assignmentId,
                                                     String studentPackage,
                                                     Path rootPath) {
                return "> * No feedback provided";
            }

            @Override
            public String buildSourceCodeMarkdown(Assignment assignment, String studentPackage, Path repoPath) {
                return "// source code";
            }

            @Override
            public String buildCommitHistoryMarkdown(Path repoPath) {
                return "- commit history";
            }

            @Override
            public String wrapMarkdownAsHtml(String title, String markdown) {
                return "<html><body>" + title + "</body></html>";
            }
        };

        ReportService svc = new ReportService(af, deps);

        ReportService.ReportGenerationResult result = svc.generateReports(a, root, mappingsPath);

        assertTrue(result.wroteAny(), "Expected wroteAny to be true when a report is produced");
        assertFalse(result.hadFailures(), "Expected no failures for the happy path");
        assertTrue(result.isSuccess(), "isSuccess should be true when wroteAny && !hadFailures");

        // Verify the report file was created in the student repo root and contains our title
        String expectedFileName = a.getCourseCode() + a.getAssignmentCode() + "username1" + ".html";
        Path reportPath = studentRepo.resolve(expectedFileName);
        assertTrue(Files.exists(reportPath), "Report file should exist: " + reportPath);
        String content = Files.readString(reportPath);
        assertTrue(content.contains("username1"), "Report content should contain wrapped title");
    }

    /**
     * When loadMapping returns an empty map, generateReports should fail fast and not write files.
     */
    @Test
    public void generateReports_emptyMapping_returnsFailure() throws Exception {
        AssignmentsFile af = new AssignmentsFile();

        Assignment a = new Assignment();
        a.setCourseCode("CSC101");
        a.setAssignmentCode("A1");
        a.setAssignmentName("Intro Assignment");

        Path root = Files.createTempDirectory("rs-root2");
        Files.createDirectories(root.resolve("packages"));
        Path mappingsPath = Files.createTempFile("mapping2", ".json");

        ReportService.ReportDependencies deps = new ReportService.ReportDependencies() {
            @Override public void log(String msg) { }
            @Override public Map<String, RepoMapping> loadMapping(Path mappingsPath) { return Map.of(); }
            @Override public Path resolveRepoRoot(Path mappedRepoPath) { return null; }
            @Override public CheckstyleService.CheckstyleResult buildCheckstyleResult(Path repoPath) { return null; }
            @Override public UnitTestService.UnitTestResult buildUnitTestResultMarkdown(String studentPackage, Path repoPath) { return null; }
            @Override public Map<String, Integer> loadManualDeductionsFromGradingDraft(String assignmentId, String studentPackage, Path rootPath) { return Map.of(); }
            @Override public String loadFeedbackSectionMarkdown(String assignmentId,
                                                                String studentPackage,
                                                                Path rootPath) { return ""; }
            @Override public String buildSourceCodeMarkdown(Assignment assignment, String studentPackage, Path repoPath) { return ""; }
            @Override public String buildCommitHistoryMarkdown(Path repoPath) { return ""; }
            @Override public String wrapMarkdownAsHtml(String title, String markdown) { return ""; }
        };

        ReportService svc = new ReportService(af, deps);
        ReportService.ReportGenerationResult result = svc.generateReports(a, root, mappingsPath);

        assertFalse(result.wroteAny(), "wroteAny should be false when mapping is empty");
        assertFalse(result.hadFailures(), "hadFailures should be false for this failure mode");
        assertFalse(result.isSuccess(), "isSuccess should be false when nothing was written");
    }
}

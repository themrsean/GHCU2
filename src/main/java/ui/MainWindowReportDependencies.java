package ui;

import model.Assignment;
import model.RepoMapping;
import service.CheckstyleService;
import service.GitService;
import service.GradingDraftService;
import service.MappingService;
import service.ReportHtmlWrapper;
import service.ReportService;
import service.ServiceLogger;
import service.SourceCodeService;
import service.UnitTestService;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public class MainWindowReportDependencies implements ReportService.ReportDependencies {

    private final ServiceLogger logger;
    private final MappingService mappingService;
    private final CheckstyleService checkstyleService;
    private final UnitTestService unitTestService;
    private final GradingDraftService gradingDraftService;
    private final SourceCodeService sourceCodeService;
    private final GitService gitService;
    private final ReportHtmlWrapper reportHtmlWrapper;


    private final Path selectedRootPath;
    private final boolean checkstyleEnabled;
    private final boolean missingCheckstyleRubricItem;
    private final String checkstyleUrl;

    public MainWindowReportDependencies(ServiceLogger logger,
                                        MappingService mappingService,
                                        CheckstyleService checkstyleService,
                                        UnitTestService unitTestService,
                                        GradingDraftService gradingDraftService,
                                        SourceCodeService sourceCodeService,
                                        GitService gitService,
                                        ReportHtmlWrapper reportHtmlWrapper,
                                        Path selectedRootPath,
                                        boolean checkstyleEnabled,
                                        boolean missingCheckstyleRubricItem,
                                        String checkstyleUrl) {

        this.logger = Objects.requireNonNull(logger);
        this.mappingService = Objects.requireNonNull(mappingService);
        this.checkstyleService = Objects.requireNonNull(checkstyleService);
        this.unitTestService = Objects.requireNonNull(unitTestService);
        this.gradingDraftService = Objects.requireNonNull(gradingDraftService);
        this.sourceCodeService = Objects.requireNonNull(sourceCodeService);
        this.gitService = Objects.requireNonNull(gitService);
        this.reportHtmlWrapper = Objects.requireNonNull(reportHtmlWrapper);

        this.selectedRootPath = selectedRootPath;
        this.checkstyleEnabled = checkstyleEnabled;
        this.missingCheckstyleRubricItem = missingCheckstyleRubricItem;
        this.checkstyleUrl = checkstyleUrl;
    }

    @Override
    public void log(String msg) {
        logger.log(msg);
    }

    @Override
    public Map<String, RepoMapping> loadMapping(Path mappingsPath) {
        return mappingService.loadMapping(mappingsPath);
    }

    @Override
    public Path resolveRepoRoot(Path mappedRepoPath) {
        return mappingService.resolveRepoRoot(mappedRepoPath);
    }

    @Override
    public CheckstyleService.CheckstyleResult buildCheckstyleResult(Path repoPath) {
        return checkstyleService.buildCheckstyleResult(
                repoPath,
                selectedRootPath,
                checkstyleEnabled,
                missingCheckstyleRubricItem,
                checkstyleUrl
        );
    }

    @Override
    public UnitTestService.UnitTestResult buildUnitTestResultMarkdown(String studentPackage, Path repoPath) {
        try {
            return unitTestService.buildUnitTestResultMarkdown(studentPackage, repoPath);
        } catch (Exception e) {
            logger.log("Unit tests failed: " + e.getMessage());
            return new UnitTestService.UnitTestResult("_Unit test execution failed._", 0, 0);
        }
    }

    @Override
    public Map<String, Integer> loadManualDeductionsFromGradingDraft(String assignmentId, String studentPackage, Path rootPath) {
        return gradingDraftService.loadManualDeductionsFromGradingDraft(assignmentId, studentPackage, rootPath);
    }

    @Override
    public String buildSourceCodeMarkdown(Assignment assignment, String studentPackage, Path repoPath) {
        return sourceCodeService.buildSourceCodeMarkdown(assignment, studentPackage, repoPath);
    }

    @Override
    public String buildCommitHistoryMarkdown(Path repoPath) {
        return gitService.buildCommitHistoryMarkdown(repoPath);
    }

    @Override
    public String wrapMarkdownAsHtml(String title, String markdown) {
        return reportHtmlWrapper.wrapMarkdown(title, markdown);
    }
}

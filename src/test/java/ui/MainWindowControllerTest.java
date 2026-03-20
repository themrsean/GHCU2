package ui;

import javafx.scene.control.MenuItem;
import javafx.scene.control.ButtonType;
import model.Assignment;
import model.AssignmentsFile;
import model.RubricItemDef;
import model.RubricItemRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import service.CheckstyleService;
import service.GitService;
import service.GradingDraftService;
import service.MappingService;
import service.ProcessRunner;
import service.ReportHtmlWrapper;
import service.ReportService;
import service.ServiceLogger;
import service.SourceCodeService;
import service.UnitTestService;
import service.ImportsService;
import service.steps.RunAllStep;
import service.steps.StepResult;
import service.steps.StepStatus;
import service.steps.WorkflowStep;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainWindowControllerTest {

    @Test
    void isAssignmentRubricValid_requiresExactly100Points() {
        Assignment valid = assignmentWithRubric("ri_code", 70, "ri_tests", 30);
        Assignment invalid = assignmentWithRubric("ri_code", 60, "ri_tests", 30);

        assertTrue(MainWindowController.isAssignmentRubricValid(valid));
        assertFalse(MainWindowController.isAssignmentRubricValid(invalid));
        assertFalse(MainWindowController.isAssignmentRubricValid(null));
    }

    @Test
    void isAssignmentMissingCheckstyleRubricItem_detectsCheckstyleItem() {
        Assignment assignment = assignmentWithRubric("ri_style", 20, "ri_logic", 80);

        AssignmentsFile file = new AssignmentsFile();
        HashMap<String, RubricItemDef> library = new HashMap<>();
        RubricItemDef style = new RubricItemDef();
        style.setName("Style");
        style.setCheckstyleItem(true);
        library.put("ri_style", style);
        file.setRubricItemLibrary(library);

        assertFalse(MainWindowController
                .isAssignmentMissingCheckstyleRubricItem(assignment, file));

        file.getRubricItemLibrary().get("ri_style").setCheckstyleItem(false);
        assertTrue(MainWindowController
                .isAssignmentMissingCheckstyleRubricItem(assignment, file));
    }

    @Test
    void isAssignmentMissingCheckstyleRubricItem_handlesNullsAndMissingLibraryEntries() {
        Assignment assignment = assignmentWithRubric("ri_style", 20, "ri_logic", 80);

        assertTrue(MainWindowController.isAssignmentMissingCheckstyleRubricItem(null, null));
        assertTrue(MainWindowController.isAssignmentMissingCheckstyleRubricItem(assignment, null));

        AssignmentsFile noLibrary = new AssignmentsFile();
        assertTrue(MainWindowController
                .isAssignmentMissingCheckstyleRubricItem(assignment, noLibrary));

        AssignmentsFile missingIds = new AssignmentsFile();
        missingIds.setRubricItemLibrary(new HashMap<>());
        assertTrue(MainWindowController
                .isAssignmentMissingCheckstyleRubricItem(assignment, missingIds));
    }

    @Test
    void isAssignmentMissingCheckstyleRubricItem_returnsFalseWhenAnyRubricItemIsCheckstyle() {
        Assignment assignment = assignmentWithRubric("ri_a", 50, "ri_b", 50);

        AssignmentsFile file = new AssignmentsFile();
        HashMap<String, RubricItemDef> library = new HashMap<>();
        RubricItemDef a = new RubricItemDef();
        a.setName("A");
        a.setCheckstyleItem(false);
        RubricItemDef b = new RubricItemDef();
        b.setName("B");
        b.setCheckstyleItem(true);
        library.put("ri_a", a);
        library.put("ri_b", b);
        file.setRubricItemLibrary(library);

        assertFalse(MainWindowController
                .isAssignmentMissingCheckstyleRubricItem(assignment, file));
    }

    @Test
    void evaluateUiState_disablesRunAllWhenCheckstyleEnabledButUrlMissing() {
        Assignment assignment = assignmentWithRubric("ri_code", 100);

        MainWindowController.UiState disabledRunAll = MainWindowController.evaluateUiState(
                "gh classroom clone ...",
                Path.of("/tmp/repos"),
                assignment,
                true,
                "",
                true
        );

        assertTrue(disabledRunAll.pullEnabled());
        assertFalse(disabledRunAll.runAllEnabled());
        assertFalse(disabledRunAll.reportsEnabled());
        assertTrue(disabledRunAll.extractEnabled());
        assertTrue(disabledRunAll.importsEnabled());

        MainWindowController.UiState enabledRunAll = MainWindowController.evaluateUiState(
                "gh classroom clone ...",
                Path.of("/tmp/repos"),
                assignment,
                true,
                "https://example.com/checkstyle.xml",
                true
        );

        assertTrue(enabledRunAll.runAllEnabled());
        assertTrue(enabledRunAll.reportsEnabled());
    }

    @Test
    void evaluateUiState_disablesAllAssignmentActionsWithoutAssignmentOrRoot() {
        MainWindowController.UiState state = MainWindowController.evaluateUiState(
                "gh classroom clone ...",
                null,
                null,
                false,
                "",
                false
        );

        assertTrue(state.pullEnabled());
        assertFalse(state.extractEnabled());
        assertFalse(state.importsEnabled());
        assertFalse(state.reportsEnabled());
        assertFalse(state.runAllEnabled());
        assertFalse(state.editAssignmentEnabled());
        assertFalse(state.deleteAssignmentEnabled());
        assertFalse(state.gradeAssignmentEnabled());
        assertFalse(state.exportAssignmentsEnabled());
    }

    @Test
    void evaluateUiState_disablesWorkflowWhenRubricIsNot100() {
        Assignment invalidRubricAssignment = assignmentWithRubric("ri_code", 90);

        MainWindowController.UiState state = MainWindowController.evaluateUiState(
                "gh classroom clone ...",
                Path.of("/tmp/repos"),
                invalidRubricAssignment,
                false,
                "",
                true
        );

        assertTrue(state.pullEnabled());
        assertFalse(state.extractEnabled());
        assertFalse(state.importsEnabled());
        assertFalse(state.reportsEnabled());
        assertFalse(state.runAllEnabled());
        assertTrue(state.editAssignmentEnabled());
        assertTrue(state.deleteAssignmentEnabled());
        assertTrue(state.gradeAssignmentEnabled());
        assertTrue(state.exportAssignmentsEnabled());
    }

    @Test
    void evaluateUiState_disablesPullAndRunAllWhenCloneCommandBlank() {
        Assignment assignment = assignmentWithRubric("ri_code", 100);

        MainWindowController.UiState state = MainWindowController.evaluateUiState(
                "   ",
                Path.of("/tmp/repos"),
                assignment,
                false,
                "",
                true
        );

        assertFalse(state.pullEnabled());
        assertTrue(state.extractEnabled());
        assertTrue(state.importsEnabled());
        assertTrue(state.reportsEnabled());
        assertFalse(state.runAllEnabled());
        assertTrue(state.editAssignmentEnabled());
        assertTrue(state.deleteAssignmentEnabled());
        assertTrue(state.gradeAssignmentEnabled());
        assertTrue(state.exportAssignmentsEnabled());
    }

    @Test
    void normalizedExpectedFiles_trimsBasenamesAndDeduplicates() {
        List<String> cleaned = MainWindowController.normalizedExpectedFiles(Arrays.asList(
                "src/main/java/Foo.java",
                "Foo.java",
                null,
                " view.fxml ",
                "nested/view.fxml"
        ));

        assertEquals(List.of("Foo.java", "view.fxml"), cleaned);
    }

    @Test
    void normalizedExpectedFiles_handlesEmptyNullAndWhitespaceOnlyEntries() {
        assertEquals(List.of(), MainWindowController.normalizedExpectedFiles(null));
        assertEquals(List.of(), MainWindowController.normalizedExpectedFiles(List.of()));

        List<String> cleaned = MainWindowController.normalizedExpectedFiles(Arrays.asList(
                null,
                " ",
                "\t",
                "src/main/java/",
                "src/main/java/Bar.java",
                "Bar.java"
        ));

        assertEquals(List.of("Bar.java", "java"), cleaned);
    }

    @Test
    void buildIoWorker_logsSuccessAndRunsUiUpdate() {
        AtomicReference<String> logged = new AtomicReference<>("");
        AtomicBoolean uiUpdated = new AtomicBoolean(false);
        ServiceLogger logger = logged::set;

        Runnable worker = MainWindowController.buildIoWorker(
                () -> { },
                "Extract complete.",
                "Extract failed: ",
                logger,
                () -> uiUpdated.set(true)
        );

        worker.run();

        assertEquals("Extract complete.", logged.get());
        assertTrue(uiUpdated.get());
    }

    @Test
    void buildIoWorker_logsFailureAndRunsUiUpdate() {
        AtomicReference<String> logged = new AtomicReference<>("");
        AtomicBoolean uiUpdated = new AtomicBoolean(false);
        ServiceLogger logger = logged::set;

        Runnable worker = MainWindowController.buildIoWorker(
                () -> { throw new java.io.IOException("disk offline"); },
                "Generate Imports complete.",
                "Generate Imports failed: ",
                logger,
                () -> uiUpdated.set(true)
        );

        worker.run();

        assertEquals("Generate Imports failed: disk offline", logged.get());
        assertTrue(uiUpdated.get());
    }

    @Test
    void buildIoWorker_runsUiUpdateEvenWhenLoggerThrows() {
        AtomicInteger uiUpdates = new AtomicInteger(0);
        ServiceLogger logger = _ -> { throw new RuntimeException("log failed"); };

        Runnable worker = MainWindowController.buildIoWorker(
                () -> { },
                "ok",
                "fail: ",
                logger,
                uiUpdates::incrementAndGet
        );

        try {
            worker.run();
        } catch (RuntimeException ex) {
            assertEquals("log failed", ex.getMessage());
        }

        assertEquals(1, uiUpdates.get());
    }

    @Test
    void disableRunAllMenuItems_disablesEveryWorkflowMenu() throws Exception {
        MainWindowController controller = new MainWindowController();
        MenuItem runAll = new MenuItem("runAll");
        MenuItem pull = new MenuItem("pull");
        MenuItem extract = new MenuItem("extract");
        MenuItem imports = new MenuItem("imports");
        MenuItem reports = new MenuItem("reports");
        runAll.setDisable(false);
        pull.setDisable(false);
        extract.setDisable(false);
        imports.setDisable(false);
        reports.setDisable(false);

        setField(controller, "runAllMenuItem", runAll);
        setField(controller, "pullMenuItem", pull);
        setField(controller, "extractMenuItem", extract);
        setField(controller, "importsMenuItem", imports);
        setField(controller, "reportsMenuItem", reports);

        invokePrivateNoArg(controller, "disableRunAllMenuItems");

        assertTrue(runAll.isDisable());
        assertTrue(pull.isDisable());
        assertTrue(extract.isDisable());
        assertTrue(imports.isDisable());
        assertTrue(reports.isDisable());
    }

    @Test
    void createReportDependencies_trimsUrlAndPassesCheckstyleSettings() {
        AtomicReference<Path> capturedRoot = new AtomicReference<>();
        AtomicReference<Boolean> capturedEnabled = new AtomicReference<>();
        AtomicReference<Boolean> capturedMissingRubric = new AtomicReference<>();
        AtomicReference<String> capturedUrl = new AtomicReference<>();

        ProcessRunner processRunner = new ProcessRunner();
        ServiceLogger logger = _ -> { };
        CapturingCheckstyleService checkstyle = new CapturingCheckstyleService(
                processRunner,
                logger,
                capturedRoot,
                capturedEnabled,
                capturedMissingRubric,
                capturedUrl
        );

        MainWindowReportDependencies deps = MainWindowController.createReportDependencies(
                logger,
                new MappingService(logger),
                checkstyle,
                new UnitTestService(processRunner, logger),
                new GradingDraftService(new ReportHtmlWrapper()),
                new SourceCodeService(),
                new GitService(processRunner),
                new ReportHtmlWrapper(),
                Path.of("/root/repos"),
                true,
                false,
                "  https://example.com/checkstyle.xml  "
        );

        deps.buildCheckstyleResult(Path.of("/repo/student"));

        assertEquals(Path.of("/root/repos"), capturedRoot.get());
        assertTrue(capturedEnabled.get());
        assertFalse(capturedMissingRubric.get());
        assertEquals("https://example.com/checkstyle.xml", capturedUrl.get());
    }

    @Test
    void pullAbortReason_reportsMissingCloneCommandAndRoot() {
        assertEquals(
                "Pull aborted: GitHub Classroom command is empty.",
                MainWindowController.pullAbortReason("  ", Path.of("/tmp/repos"))
        );
        assertEquals(
                "Pull aborted: Repository root is not set.",
                MainWindowController.pullAbortReason("gh classroom clone ...", null)
        );
        assertEquals(
                null,
                MainWindowController.pullAbortReason("gh classroom clone ...", Path.of("/tmp/repos"))
        );
    }

    @Test
    void extractAbortReason_reportsMissingRootOrAssignment() {
        Assignment assignment = assignmentWithRubric("ri_code", 100);
        assertEquals(
                "Extract aborted: Repository root is not set.",
                MainWindowController.extractAbortReason(null, assignment)
        );
        assertEquals(
                "Extract aborted: No assignment selected.",
                MainWindowController.extractAbortReason(Path.of("/tmp/repos"), null)
        );
        assertEquals(
                null,
                MainWindowController.extractAbortReason(Path.of("/tmp/repos"), assignment)
        );
    }

    @Test
    void importsAbortReason_reportsMissingRootOrAssignment() {
        Assignment assignment = assignmentWithRubric("ri_code", 100);
        assertEquals(
                "Generate Imports aborted: Repository root is not set.",
                MainWindowController.importsAbortReason(null, assignment)
        );
        assertEquals(
                "Generate Imports aborted: No assignment selected.",
                MainWindowController.importsAbortReason(Path.of("/tmp/repos"), null)
        );
        assertEquals(
                null,
                MainWindowController.importsAbortReason(Path.of("/tmp/repos"), assignment)
        );
    }

    @Test
    void reportsAbortReason_reportsPathAssignmentAndRubricFailures(@TempDir Path tmp) {
        Assignment assignment = assignmentWithRubric("ri_code", 100);

        assertEquals(
                "Generate Reports aborted: Repository root is not set.",
                MainWindowController.reportsAbortReason(null, assignment, true)
        );

        Path missing = tmp.resolve("missing");
        assertEquals(
                "Generate Reports aborted: Repository root does not exist or is not a directory.",
                MainWindowController.reportsAbortReason(missing, assignment, true)
        );

        assertEquals(
                "Generate Reports aborted: No assignment selected.",
                MainWindowController.reportsAbortReason(tmp, null, true)
        );

        assertEquals(
                "Generate Reports aborted: Rubric total must be exactly 100 points.",
                MainWindowController.reportsAbortReason(tmp, assignment, false)
        );

        assertEquals(null, MainWindowController.reportsAbortReason(tmp, assignment, true));
    }

    @Test
    void runAllAbortReason_reportsRootAssignmentRubricAndCloneFailures() {
        Assignment assignment = assignmentWithRubric("ri_code", 100);

        assertEquals(
                "Run All aborted: Repository root is not set.",
                MainWindowController.runAllAbortReason(null, assignment, true, "gh")
        );
        assertEquals(
                "Run All aborted: No assignment selected.",
                MainWindowController.runAllAbortReason(Path.of("/tmp/repos"), null, true, "gh")
        );
        assertEquals(
                "Run All aborted: Rubric total must be exactly 100 points.",
                MainWindowController.runAllAbortReason(
                        Path.of("/tmp/repos"),
                        assignment,
                        false,
                        "gh"
                )
        );
        assertEquals(
                "Run All aborted: GitHub Classroom command is empty.",
                MainWindowController.runAllAbortReason(
                        Path.of("/tmp/repos"),
                        assignment,
                        true,
                        "   "
                )
        );
        assertEquals(
                null,
                MainWindowController.runAllAbortReason(
                        Path.of("/tmp/repos"),
                        assignment,
                        true,
                        "gh classroom clone ..."
                )
        );
    }

    @Test
    void startReportsIfValid_returnsAbortWithoutDisablingOrStarting(@TempDir Path tmp) {
        Assignment assignment = assignmentWithRubric("ri_code", 100);
        AtomicBoolean disabled = new AtomicBoolean(false);
        AtomicBoolean started = new AtomicBoolean(false);

        String abortReason = MainWindowController.startReportsIfValid(
                null,
                assignment,
                true,
                () -> disabled.set(true),
                () -> { },
                (name, action) -> started.set(true)
        );

        assertEquals("Generate Reports aborted: Repository root is not set.", abortReason);
        assertFalse(disabled.get());
        assertFalse(started.get());

        abortReason = MainWindowController.startReportsIfValid(
                tmp,
                assignment,
                false,
                () -> disabled.set(true),
                () -> { },
                (name, action) -> started.set(true)
        );
        assertEquals("Generate Reports aborted: Rubric total must be exactly 100 points.",
                abortReason);
    }

    @Test
    void startReportsIfValid_disablesMenusAndStartsReportsWorker(@TempDir Path tmp) {
        Assignment assignment = assignmentWithRubric("ri_code", 100);
        AtomicReference<Runnable> startedAction = new AtomicReference<>();
        AtomicReference<String> startedName = new AtomicReference<>("");
        List<String> calls = new ArrayList<>();

        String abortReason = MainWindowController.startReportsIfValid(
                tmp,
                assignment,
                true,
                () -> calls.add("disable"),
                () -> calls.add("worker"),
                (name, action) -> {
                    startedName.set(name);
                    startedAction.set(action);
                }
        );

        assertNull(abortReason);
        assertEquals(List.of("disable"), calls);
        assertEquals("reports-worker", startedName.get());
        startedAction.get().run();
        assertEquals(List.of("disable", "worker"), calls);
    }

    @Test
    void mergeExpectedFilesForDrop_filtersDeduplicatesAndSorts() {
        List<String> merged = MainWindowController.mergeExpectedFilesForDrop(
                Arrays.asList("src/main/java/Existing.java", "Layout.fxml"),
                Arrays.asList("NewFile.java", "notes.txt", "Layout.fxml", "z.fxml", null)
        );

        assertEquals(List.of("Existing.java", "Layout.fxml", "NewFile.java", "z.fxml"), merged);
    }

    @Test
    void mergeExpectedFilesForDrop_handlesNullInputs() {
        assertEquals(List.of(), MainWindowController.mergeExpectedFilesForDrop(null, null));
        assertEquals(
                List.of("A.fxml"),
                MainWindowController.mergeExpectedFilesForDrop(null, List.of("A.fxml", "a.md"))
        );
    }

    @Test
    void selectedFilePath_andBrowseRootSetMessage_handleNullAndValid() {
        assertNull(MainWindowController.selectedFilePath(null));
        Path selected = MainWindowController.selectedFilePath(Path.of("/tmp/repos").toFile());
        assertEquals(Path.of("/tmp/repos"), selected);
        assertEquals("Repository root set to: /tmp/repos",
                MainWindowController.browseRootSetMessage(Path.of("/tmp/repos")));
    }

    @Test
    void exportAssignmentsAbortReason_reportsWhenAssignmentsMissing() {
        assertEquals(
                "No assignments loaded to export.",
                MainWindowController.exportAssignmentsAbortReason(null)
        );
        assertNull(MainWindowController.exportAssignmentsAbortReason(new AssignmentsFile()));
    }

    @Test
    void newEditDeleteAssignmentAbortReason_reportExpectedMessages() {
        Assignment assignment = assignmentWithRubric("ri_code", 100);
        AssignmentsFile file = new AssignmentsFile();

        assertEquals(
                "Cannot create assignment: failed to load/create assignments.json.",
                MainWindowController.newAssignmentAbortReason(null)
        );

        file.setRubricItemLibrary(new HashMap<>());
        assertEquals(
                "Cannot create assignment: rubric item library is missing/empty.",
                MainWindowController.newAssignmentAbortReason(file)
        );

        HashMap<String, RubricItemDef> rubricLibrary = new HashMap<>();
        RubricItemDef def = new RubricItemDef();
        def.setName("Code");
        rubricLibrary.put("ri_code", def);
        file.setRubricItemLibrary(rubricLibrary);
        assertNull(MainWindowController.newAssignmentAbortReason(file));

        assertEquals(
                "Cannot create assignment: failed to load/create assignments.json.",
                MainWindowController.editAssignmentAbortReason(null, assignment)
        );
        assertEquals(
                "No assignment selected.",
                MainWindowController.editAssignmentAbortReason(file, null)
        );
        assertNull(MainWindowController.editAssignmentAbortReason(file, assignment));

        assertEquals(
                "Cannot delete assignment: failed to load/create assignments.json.",
                MainWindowController.deleteAssignmentAbortReason(null, assignment)
        );
        assertEquals(
                "No assignment selected.",
                MainWindowController.deleteAssignmentAbortReason(file, null)
        );
        assertNull(MainWindowController.deleteAssignmentAbortReason(file, assignment));
    }

    @Test
    void editLibraryAbortReasons_reportExpectedMessages() {
        Assignment assignment = assignmentWithRubric("ri_code", 100);
        AssignmentsFile file = new AssignmentsFile();

        assertEquals(
                "Rubric Library edit aborted: No assignments file loaded.",
                MainWindowController.editRubricLibraryAbortReason(null)
        );
        assertNull(MainWindowController.editRubricLibraryAbortReason(file));

        assertEquals(
                "Edit Comment Library aborted: No assignments file loaded.",
                MainWindowController.editCommentLibraryAbortReason(null, assignment)
        );
        assertEquals(
                "Edit Comment Library aborted: No assignment selected.",
                MainWindowController.editCommentLibraryAbortReason(file, null)
        );
        assertNull(MainWindowController.editCommentLibraryAbortReason(file, assignment));
    }

    @Test
    void shouldDeleteAssignment_returnsTrueOnlyForOkButton() {
        assertTrue(MainWindowController.shouldDeleteAssignment(ButtonType.OK));
        assertFalse(MainWindowController.shouldDeleteAssignment(ButtonType.CANCEL));
    }

    @Test
    void invokeExit_invokesProvidedExitInvoker() {
        AtomicInteger callCount = new AtomicInteger(0);
        MainWindowController.invokeExit(callCount::incrementAndGet);
        assertEquals(1, callCount.get());
    }

    @Test
    void buildRunAllSteps_returnsExpectedRunAllOrder() {
        ServiceLogger logger = _ -> { };
        ProcessRunner processRunner = new ProcessRunner();

        ReportService reportService = new ReportService(
                new AssignmentsFile(),
                buildNoOpReportDependencies()
        );

        List<WorkflowStep> steps = MainWindowController.buildRunAllSteps(
                processRunner,
                logger,
                new MappingService(logger),
                new ImportsService(logger),
                reportService
        );

        List<RunAllStep> stepTypes = steps.stream().map(WorkflowStep::stepType).toList();
        assertEquals(
                List.of(RunAllStep.PULL, RunAllStep.EXTRACT, RunAllStep.IMPORTS, RunAllStep.REPORTS),
                stepTypes
        );
    }

    @Test
    void executeRunAllWorkflow_runsStepsThenSchedulesUiUpdate() {
        List<String> calls = new CopyOnWriteArrayList<>();
        AtomicInteger scheduledCount = new AtomicInteger(0);
        AtomicReference<Runnable> scheduledUiUpdate = new AtomicReference<>();
        AtomicBoolean uiUpdated = new AtomicBoolean(false);

        WorkflowStep pull = new WorkflowStep() {
            @Override
            public RunAllStep stepType() {
                return RunAllStep.PULL;
            }

            @Override
            public StepResult execute(service.WorkflowContext context) {
                calls.add("PULL");
                return new StepResult(StepStatus.SUCCESS, 1L, "ok");
            }
        };

        WorkflowStep imports = new WorkflowStep() {
            @Override
            public RunAllStep stepType() {
                return RunAllStep.IMPORTS;
            }

            @Override
            public StepResult execute(service.WorkflowContext context) {
                calls.add("IMPORTS");
                return new StepResult(StepStatus.SUCCESS, 1L, "ok");
            }
        };

        MainWindowController.executeRunAllWorkflow(
                "gh classroom clone ...",
                assignmentWithRubric("ri_code", 100),
                Path.of("/tmp/repos"),
                Path.of("/tmp/mappings.json"),
                List.of(pull, imports),
                _ -> { },
                () -> uiUpdated.set(true),
                runnable -> {
                    scheduledCount.incrementAndGet();
                    scheduledUiUpdate.set(runnable);
                }
        );

        assertEquals(List.of("PULL", "IMPORTS"), calls);
        assertEquals(1, scheduledCount.get());
        assertFalse(uiUpdated.get());
        scheduledUiUpdate.get().run();
        assertTrue(uiUpdated.get());
    }

    @Test
    void startRunAllIfValid_disablesMenusBeforeStartingWorkerAndPassesInputs() {
        List<String> calls = new ArrayList<>();
        AtomicReference<String> capturedClone = new AtomicReference<>();
        AtomicReference<Assignment> capturedAssignment = new AtomicReference<>();
        AtomicReference<Path> capturedRoot = new AtomicReference<>();
        AtomicReference<Runnable> startedAction = new AtomicReference<>();
        Assignment assignment = assignmentWithRubric("ri_code", 100);
        Path root = Path.of("/tmp/repos");

        String abortReason = MainWindowController.startRunAllIfValid(
                root,
                assignment,
                true,
                "gh classroom clone ...",
                () -> calls.add("disable"),
                (cloneCmd, a, r) -> {
                    calls.add("worker");
                    capturedClone.set(cloneCmd);
                    capturedAssignment.set(a);
                    capturedRoot.set(r);
                },
                (name, action) -> {
                    calls.add("start:" + name);
                    startedAction.set(action);
                }
        );

        assertEquals(null, abortReason);
        assertEquals("disable", calls.getFirst());
        assertEquals("start:runall-worker", calls.get(1));
        startedAction.get().run();
        assertEquals("worker", calls.get(2));
        assertEquals("gh classroom clone ...", capturedClone.get());
        assertEquals(assignment, capturedAssignment.get());
        assertEquals(root, capturedRoot.get());
    }

    @Test
    void startRunAllIfValid_returnsAbortWithoutDisablingOrStarting() {
        AtomicBoolean disabled = new AtomicBoolean(false);
        AtomicBoolean started = new AtomicBoolean(false);
        Assignment assignment = assignmentWithRubric("ri_code", 100);

        String abortReason = MainWindowController.startRunAllIfValid(
                null,
                assignment,
                true,
                "gh classroom clone ...",
                () -> disabled.set(true),
                (cloneCmd, a, r) -> { },
                (name, action) -> started.set(true)
        );

        assertEquals("Run All aborted: Repository root is not set.", abortReason);
        assertFalse(disabled.get());
        assertFalse(started.get());
    }

    @Test
    void parsePullArgs_delegatesToProcessRunnerTokenizer() {
        CapturingProcessRunner processRunner = new CapturingProcessRunner();
        processRunner.tokenizedToReturn = List.of("gh", "classroom", "clone");

        List<String> args = MainWindowController.parsePullArgs(
                " gh classroom clone ",
                processRunner
        );

        assertEquals("gh classroom clone", processRunner.lastTokenizeInput);
        assertEquals(List.of("gh", "classroom", "clone"), args);
    }

    @Test
    void startPullIfParsable_startsWorkerWhenCommandParses() {
        CapturingProcessRunner processRunner = new CapturingProcessRunner();
        processRunner.tokenizedToReturn = List.of("gh", "classroom", "clone");
        processRunner.exitCodeToReturn = 0;

        AtomicReference<String> startedName = new AtomicReference<>();
        AtomicReference<Runnable> startedAction = new AtomicReference<>();
        AtomicInteger exitCode = new AtomicInteger(-99);

        String abortReason = MainWindowController.startPullIfParsable(
                "gh classroom clone",
                Path.of("/tmp/repos"),
                processRunner,
                _ -> { },
                exitCode::set,
                (name, action) -> {
                    startedName.set(name);
                    startedAction.set(action);
                }
        );

        assertEquals(null, abortReason);
        assertEquals("pull-worker", startedName.get());
        startedAction.get().run();
        assertEquals(List.of("gh", "classroom", "clone"), processRunner.lastRunArgs);
        assertEquals(Path.of("/tmp/repos"), processRunner.lastRunWorkingDir);
        assertEquals(0, exitCode.get());
    }

    @Test
    void startPullIfParsable_returnsAbortWhenCommandCannotBeParsed() {
        CapturingProcessRunner processRunner = new CapturingProcessRunner();
        processRunner.tokenizedToReturn = List.of();
        AtomicBoolean started = new AtomicBoolean(false);

        String abortReason = MainWindowController.startPullIfParsable(
                "   ",
                Path.of("/tmp/repos"),
                processRunner,
                _ -> { },
                _ -> { },
                (name, action) -> started.set(true)
        );

        assertEquals("Pull aborted: Could not parse command.", abortReason);
        assertFalse(started.get());
    }

    private Assignment assignmentWithRubric(Object... idPointsPairs) {
        Assignment assignment = new Assignment();
        Assignment.Rubric rubric = new Assignment.Rubric();
        List<RubricItemRef> items = new ArrayList<>();

        for (int i = 0; i < idPointsPairs.length; i += 2) {
            RubricItemRef ref = new RubricItemRef();
            ref.setRubricItemId((String) idPointsPairs[i]);
            ref.setPoints((Integer) idPointsPairs[i + 1]);
            items.add(ref);
        }

        rubric.setItems(items);
        assignment.setRubric(rubric);
        return assignment;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void invokePrivateNoArg(Object target, String methodName) throws Exception {
        var method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static final class CapturingCheckstyleService extends CheckstyleService {
        private final AtomicReference<Path> capturedRoot;
        private final AtomicReference<Boolean> capturedEnabled;
        private final AtomicReference<Boolean> capturedMissingRubric;
        private final AtomicReference<String> capturedUrl;

        private CapturingCheckstyleService(ProcessRunner processRunner,
                                           ServiceLogger logger,
                                           AtomicReference<Path> capturedRoot,
                                           AtomicReference<Boolean> capturedEnabled,
                                           AtomicReference<Boolean> capturedMissingRubric,
                                           AtomicReference<String> capturedUrl) {
            super(processRunner, logger, Path.of("lib/checkstyle-13.2.0-all.jar"));
            this.capturedRoot = capturedRoot;
            this.capturedEnabled = capturedEnabled;
            this.capturedMissingRubric = capturedMissingRubric;
            this.capturedUrl = capturedUrl;
        }

        @Override
        public CheckstyleResult buildCheckstyleResult(Path repoPath,
                                                      Path selectedRootPath,
                                                      boolean checkstyleEnabled,
                                                      boolean missingCheckstyleRubricItem,
                                                      String checkstyleConfigUrl) {
            capturedRoot.set(selectedRootPath);
            capturedEnabled.set(checkstyleEnabled);
            capturedMissingRubric.set(missingCheckstyleRubricItem);
            capturedUrl.set(checkstyleConfigUrl);
            return new CheckstyleResult("_No checkstyle violations._", 0);
        }
    }

    private static final class CapturingProcessRunner extends ProcessRunner {
        private List<String> tokenizedToReturn = List.of();
        private String lastTokenizeInput = "";
        private List<String> lastRunArgs = List.of();
        private Path lastRunWorkingDir;
        private int exitCodeToReturn = 0;

        @Override
        public List<String> tokenizeCommand(String command) {
            lastTokenizeInput = command;
            return tokenizedToReturn;
        }

        @Override
        public int runAndLog(List<String> args, Path workingDir, LineLogger logger) {
            lastRunArgs = args;
            lastRunWorkingDir = workingDir;
            return exitCodeToReturn;
        }
    }

    private ReportService.ReportDependencies buildNoOpReportDependencies() {
        return new ReportService.ReportDependencies() {
            @Override
            public void log(String msg) {
            }

            @Override
            public HashMap<String, model.RepoMapping> loadMapping(Path mappingsPath) {
                return new HashMap<>();
            }

            @Override
            public Path resolveRepoRoot(Path mappedRepoPath) {
                return mappedRepoPath;
            }

            @Override
            public CheckstyleService.CheckstyleResult buildCheckstyleResult(Path repoPath) {
                return new CheckstyleService.CheckstyleResult("_No checkstyle violations._", 0);
            }

            @Override
            public UnitTestService.UnitTestResult buildUnitTestResultMarkdown(String studentPackage,
                                                                              Path repoPath) {
                return new UnitTestService.UnitTestResult("_No tests run._", 0, 0);
            }

            @Override
            public HashMap<String, Integer> loadManualDeductionsFromGradingDraft(String assignmentId,
                                                                                  String studentPackage,
                                                                                  Path rootPath) {
                return new HashMap<>();
            }

            @Override
            public String loadFeedbackSectionMarkdown(String assignmentId,
                                                      String studentPackage,
                                                      Path rootPath) {
                return "";
            }

            @Override
            public String buildSourceCodeMarkdown(Assignment assignment,
                                                  String studentPackage,
                                                  Path repoPath) {
                return "";
            }

            @Override
            public String buildCommitHistoryMarkdown(Path repoPath) {
                return "";
            }

            @Override
            public String wrapMarkdownAsHtml(String title, String markdown) {
                return markdown;
            }
        };
    }
}

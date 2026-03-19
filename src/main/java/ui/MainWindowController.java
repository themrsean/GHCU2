/*
 * Course: CSC-1110/1120
 * GitHub Classroom Utilities
 * Name: Sean Jones
 * Last Updated:
 */
package ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import model.Assignment;
import model.AssignmentsFile;
import model.Comments.CommentsLibrary;
import model.Comments.CommentsStore;
import model.RepoMapping;
import model.RubricItemDef;
import model.RubricItemRef;
import model.Settings;
import org.jspecify.annotations.NonNull;
import persistence.AssignmentsStore;
import persistence.SettingsStore;
import service.CheckstyleService;
import service.steps.ExtractStep;
import service.GitService;
import service.GradingDraftService;
import service.ImportsService;
import service.steps.ImportsStep;
import service.MappingService;
import service.ProcessRunner;
import service.steps.PullStep;
import service.ReportHtmlWrapper;
import service.ReportService;
import service.steps.ReportsStep;
import service.RunAllService;
import service.ServiceLogger;
import service.SourceCodeService;
import service.UnitTestService;
import service.steps.WorkflowStep;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * Main Window Controller
 */
public class MainWindowController implements Initializable {

    static final int RUBRIC_EXPECTED_TOTAL = 100;

    // ============================================================
    // FXML FIELDS
    // ============================================================

    @FXML
    private TextField cloneCommandField;
    @FXML
    private TextField rootPathField;
    @FXML
    private ComboBox<Assignment> assignmentCombo;
    @FXML
    private CheckBox checkstyleCheckBox;
    @FXML
    private TextField checkstyleUrlField;
    @FXML
    private Label assignmentTitleLabel;
    @FXML
    private ListView<String> expectedFilesListView;
    @FXML
    private ListView<String> rubricListView;
    @FXML
    private TextArea logTextArea;
    @FXML
    private MenuItem pullMenuItem;
    @FXML
    private MenuItem extractMenuItem;
    @FXML
    private MenuItem importsMenuItem;
    @FXML
    private MenuItem reportsMenuItem;
    @FXML
    private MenuItem runAllMenuItem;
    @FXML
    private MenuItem editAssignmentMenuItem;
    @FXML
    private MenuItem deleteAssignmentMenuItem;
    @FXML
    private MenuItem gradeAssignmentMenuItem;
    @FXML
    private MenuItem exportAssignmentsMenuItem;
    @FXML
    private VBox assignmentSummaryBox;

    // ============================================================
    // STORES + FILE PATHS
    // ============================================================

    private final AssignmentsStore assignmentsStore = new AssignmentsStore();
    private final SettingsStore settingsStore = new SettingsStore();
    private final CommentsStore commentsStore = new CommentsStore();

    private final Path settingsDir = ensureDir(appDataDir().resolve("settings"));
    private final Path commentsDir = ensureDir(appDataDir().resolve("comments"));
    private final Path assignmentsDir = ensureDir(appDataDir().resolve("assignments"));
    private final Path mappingsDir = ensureDir(appDataDir().resolve("mappings"));

    private final Path settingsPath = settingsDir.resolve("settings.json");
    private final Path commentsPath = commentsDir.resolve("comments.json");
    private final Path assignmentsPath = assignmentsDir.resolve("assignments.json");
    private final Path mappingsPath = mappingsDir.resolve("mappings.json");

    private final Path checkstyleJar = Path.of("lib").resolve("checkstyle-13.2.0-all.jar");

    // ============================================================
    // RUNTIME STATE
    // ============================================================

    private Settings settings;
    private AssignmentsFile assignmentsFile;
    private Path selectedRootPath;
    private CommentsLibrary commentsLibrary;
    private LogAppender logger;
    private final ProcessRunner processRunner = new ProcessRunner();
    private UnitTestService unitTestService;
    private MappingService mappingService;
    private CheckstyleService checkstyleService;
    private GradingDraftService gradingDraftService;
    private SourceCodeService sourceCodeService;
    private GitService gitService;
    private ReportHtmlWrapper reportHtmlWrapper;
    private ImportsService importsService;

    // ============================================================
    // JAVA FX LIFECYCLE
    // ============================================================

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        // 1) Initial control state
        checkstyleUrlField.setDisable(true);
        logger = new LogAppender(logTextArea);
        ServiceLogger serviceLogger = logger::log;
        unitTestService = new UnitTestService(processRunner, serviceLogger);
        checkstyleService = new CheckstyleService(processRunner, serviceLogger, checkstyleJar);
        mappingService = new MappingService(serviceLogger);
        reportHtmlWrapper = new ReportHtmlWrapper();
        gradingDraftService = new GradingDraftService(reportHtmlWrapper);
        sourceCodeService = new SourceCodeService();
        gitService = new GitService(processRunner);
        reportHtmlWrapper = new ReportHtmlWrapper();
        importsService = new ImportsService(serviceLogger);
        ensureAssignmentsLoaded();

        // 1b) Load Settings
        try {
            settings = settingsStore.load(settingsPath);
            if (settings.getCheckstyleConfigUrl() != null) {
                checkstyleUrlField.setText(settings.getCheckstyleConfigUrl());
            }
        } catch (IOException e) {
            logInfo("Failed to load settings: " + e.getMessage());
            settings = new Settings();
            settings.setSchemaVersion(1);
            settings.setCheckstyleConfigUrl("");
        }

        checkstyleUrlField.setDisable(!checkstyleCheckBox.isSelected());

        // 1c) Load Comments Library
        try {
            commentsLibrary = commentsStore.load(commentsPath);
            logInfo("Loaded comment library: " + commentsPath.toAbsolutePath());
        } catch (IOException e) {
            logInfo("Failed to load comments.json: " + e.getMessage());
            commentsLibrary = CommentsLibrary.newEmpty();
        }

        // 2) Listeners that impact UI state
        cloneCommandField.textProperty().addListener((_, _, _) -> updateUiState());
        assignmentCombo.valueProperty().addListener((_, _, _) -> {
            updateAssignmentSummary();
            updateUiState();
        });

        checkstyleCheckBox.selectedProperty().addListener((_, _, newV) -> {
            checkstyleUrlField.setDisable(!newV);
            updateUiState();
        });

        checkstyleUrlField.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                persistCheckstyleUrl(checkstyleUrlField.getText());
                updateUiState();
            }
        });

        // rootPathField is non-editable, so it changes only via onBrowseRoot()
        // but still safe to listen:
        rootPathField.textProperty().addListener((_, _, _) -> updateUiState());

        Platform.runLater(() -> {
            populateAssignmentCombo();
            installExpectedFilesDnD();
            updateUiState();
        });
    }

    // ============================================================
    // UI STATE + UI HELPERS
    // ============================================================

    private void updateUiAfterAssignmentsLoad() {
        updateUiState();
    }

    /**
     * Centralized enable/disable rules for the main window.
     * Call after any state change.
     */
    private void updateUiState() {
        Assignment selectedAssignment = assignmentCombo.getValue();
        boolean hasAssignmentsLoaded = assignmentCombo.getItems() != null
                && !assignmentCombo.getItems().isEmpty();

        UiState uiState = evaluateUiState(
                cloneCommandField.getText(),
                selectedRootPath,
                selectedAssignment,
                checkstyleCheckBox.isSelected(),
                checkstyleUrlField.getText(),
                hasAssignmentsLoaded
        );

        if (pullMenuItem != null) {
            pullMenuItem.setDisable(!uiState.pullEnabled());
        }
        if (extractMenuItem != null) {
            extractMenuItem.setDisable(!uiState.extractEnabled());
        }
        if (importsMenuItem != null) {
            importsMenuItem.setDisable(!uiState.importsEnabled());
        }
        if (reportsMenuItem != null) {
            reportsMenuItem.setDisable(!uiState.reportsEnabled());
        }
        if (runAllMenuItem != null) {
            runAllMenuItem.setDisable(!uiState.runAllEnabled());
        }
        if (editAssignmentMenuItem != null) {
            editAssignmentMenuItem.setDisable(!uiState.editAssignmentEnabled());
        }
        if (deleteAssignmentMenuItem != null) {
            deleteAssignmentMenuItem.setDisable(!uiState.deleteAssignmentEnabled());
        }
        if (gradeAssignmentMenuItem != null) {
            gradeAssignmentMenuItem.setDisable(!uiState.gradeAssignmentEnabled());
        }
        if (exportAssignmentsMenuItem != null) {
            exportAssignmentsMenuItem.setDisable(!uiState.exportAssignmentsEnabled());
        }
    }

    private void updateAssignmentSummary() {
        Assignment selected = assignmentCombo.getValue();
        if (selected == null) {
            assignmentTitleLabel.setText("(no assignment selected)");
            expectedFilesListView.getItems().clear();
            rubricListView.getItems().clear();
        } else {
            assignmentTitleLabel.setText(
                    selected.getCourseCode() + " " +
                            selected.getAssignmentCode() + " - " +
                            selected.getAssignmentName()
            );

            // Expected files: show only filename
            expectedFilesListView.getItems().clear();
            List<String> expected = selected.getExpectedFiles();
            if (expected != null) {
                for (String s : expected) {
                    if (s == null) {
                        continue;
                    }
                    String name = Path.of(s).getFileName().toString();
                    if (!name.isBlank() && !expectedFilesListView.getItems().contains(name)) {
                        expectedFilesListView.getItems().add(name);
                    }
                }
            }

            // Rubric items: "id (name): points"
            rubricListView.getItems().clear();
            int total = 0;
            if (selected.getRubric() != null && selected.getRubric().getItems() != null) {
                for (RubricItemRef ref : selected.getRubric().getItems()) {
                    total += ref.getPoints();
                    String id = ref.getRubricItemId();
                    String displayName = id;
                    boolean isCheckstyle = false;
                    if (assignmentsFile != null && assignmentsFile.getRubricItemLibrary() != null) {
                        RubricItemDef def = assignmentsFile.getRubricItemLibrary().get(id);
                        if (def != null) {
                            displayName = def.getName();
                            isCheckstyle = def.isCheckstyleItem();
                        }
                    }
                    String prefix = isCheckstyle ? "[CHECKSTYLE] " : "";
                    rubricListView.getItems().add(prefix + id + " (" + displayName + "): "
                            + ref.getPoints());
                }
            }
            rubricListView.getItems().add("TOTAL: " + total);
        }
    }

    private void installExpectedFilesDnD() {
        installExpectedFilesDnDOnNode(expectedFilesListView);
        installExpectedFilesDnDOnNode(assignmentSummaryBox);
    }

    private void installExpectedFilesDnDOnNode(Node node) {
        if (node != null) {
            node.setOnDragOver(e -> {
                if (e.getDragboard().hasFiles() && assignmentCombo.getValue() != null) {
                    e.acceptTransferModes(TransferMode.COPY);
                }
                e.consume();
            });
            node.setOnDragDropped(e -> {
                boolean ok = false;
                Assignment a = assignmentCombo.getValue();

                if (a != null && e.getDragboard().hasFiles()) {
                    if (a.getExpectedFiles() == null) {
                        a.setExpectedFiles(new ArrayList<>());
                    }
                    boolean addedAny = false;
                    for (File f : e.getDragboard().getFiles()) {
                        String name = f.getName();
                        String lower = name.toLowerCase();
                        if (!lower.endsWith(".java") && !lower.endsWith(".fxml")) {
                            continue;
                        }

                        if (!a.getExpectedFiles().contains(name)) {
                            a.getExpectedFiles().add(name);
                            addedAny = true;
                        }
                    }

                    if (addedAny) {
                        normalizeExpectedFiles(a);
                        saveAssignmentsQuietly();
                        updateAssignmentSummary();
                    }
                    ok = addedAny;
                }

                e.setDropCompleted(ok);
                e.consume();
            });
        }
    }

    private void logInfo(String msg) {
        logTextArea.appendText(msg + System.lineSeparator());
    }

    // ============================================================
    // FXML EVENT HANDLERS
    // ============================================================

    @FXML
    private void onBrowseRoot() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Empty Repository Root Folder");

        File selectedDir = chooser.showDialog(rootPathField.getScene().getWindow());
        if (selectedDir != null) {
            Path selectedPath = selectedDir.toPath();
            selectedRootPath = selectedPath;
            rootPathField.setText(selectedPath.toString());
            logInfo("Repository root set to: " + selectedPath);
            if (assignmentsFile == null) {
                ensureAssignmentsLoaded();
            }
            populateAssignmentCombo();
            updateUiAfterAssignmentsLoad();
            updateAssignmentSummary();
        }

        updateUiState();
    }

    @FXML
    private void onExit() {
        Platform.exit();
    }

    @FXML
    private void onImportAssignments() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Assignments JSON");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON Files", "*.json")
        );
        File file = chooser.showOpenDialog(assignmentCombo.getScene().getWindow());
        if (file != null) {
            Path path = file.toPath();
            try {
                assignmentsFile = assignmentsStore.load(path);
                populateAssignmentCombo();
                logInfo("Loaded assignments from: " + path);
                saveAssignmentsQuietly();
            } catch (IOException e) {
                logInfo("Failed to load assignments: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onExportAssignments() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Assignments JSON");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON Files", "*.json")
        );

        File file = chooser.showSaveDialog(assignmentCombo.getScene().getWindow());
        if (file != null) {
            Path path = file.toPath();
            try {
                if (assignmentsFile != null) {
                    assignmentsStore.save(path, assignmentsFile);
                    logInfo("Exported assignments to: " + path);
                } else {
                    logInfo("No assignments loaded to export.");
                }
            } catch (IOException e) {
                logInfo("Failed to export assignments: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onNewAssignment() {
        if (assignmentsFile == null) {
            ensureAssignmentsLoaded();
        }
        if (assignmentsFile == null) {
            logInfo("Cannot create assignment: failed to load/create assignments.json.");
        } else if (assignmentsFile.getRubricItemLibrary() == null
                || assignmentsFile.getRubricItemLibrary().isEmpty()) {
            logInfo("Cannot create assignment: rubric item library is missing/empty.");
        } else {
            Assignment created = openAssignmentEditor(null);
            if (created != null) {
                if (assignmentsFile.getAssignments() == null) {
                    assignmentsFile.setAssignments(new ArrayList<>());
                }
                assignmentsFile.getAssignments().add(created);
                normalizeExpectedFiles(created);
                saveAssignmentsQuietly();
                populateAssignmentCombo();
                assignmentCombo.getSelectionModel().select(created);
                logInfo("Created assignment: " + created.getKey());
                updateUiState();
            }
        }
    }

    @FXML
    private void onEditAssignment() {
        if (assignmentsFile == null) {
            ensureAssignmentsLoaded();
        }
        if (assignmentsFile == null) {
            logInfo("Cannot create assignment: failed to load/create assignments.json.");
        } else {
            Assignment selected = assignmentCombo.getValue();
            if (selected == null) {
                logInfo("No assignment selected.");
            } else {
                Assignment edited = openAssignmentEditor(selected);
                if (edited != null) {
                    normalizeExpectedFiles(edited);
                    if (assignmentsFile.getAssignments() != null) {
                        boolean found = false;
                        for (int i = 0; i < assignmentsFile.getAssignments().size()
                                && !found; i++) {
                            Assignment a = assignmentsFile.getAssignments().get(i);
                            if (a != null && a.getKey().equals(selected.getKey())) {
                                assignmentsFile.getAssignments().set(i, edited);
                                found = true;
                            }
                        }
                    }
                    populateAssignmentCombo();
                    assignmentCombo.getSelectionModel().select(edited);
                    logInfo("Edited assignment: " + edited.getKey());
                    updateUiState();
                    saveAssignmentsQuietly();
                }
            }
        }
    }

    @FXML
    private void onDeleteAssignment() {
        if (assignmentsFile == null) {
            ensureAssignmentsLoaded();
        }
        if (assignmentsFile == null) {
            logInfo("Cannot delete assignment: failed to load/create assignments.json.");
        } else {
            Assignment selected = assignmentCombo.getValue();
            if (selected == null) {
                logInfo("No assignment selected.");
            } else {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Delete Assignment");
                alert.setHeaderText("Delete assignment?");
                alert.setContentText(selected.getKey() + " - " + selected.getAssignmentName());
                ButtonType result = alert.showAndWait().orElse(ButtonType.CANCEL);
                if (result == ButtonType.OK) {
                    if (assignmentsFile.getAssignments() != null) {
                        assignmentsFile.getAssignments().removeIf(a ->
                                a != null && a.getKey().equals(selected.getKey()));
                        saveAssignmentsQuietly();
                    }
                    populateAssignmentCombo();
                    logInfo("Deleted assignment: " + selected.getKey());
                    updateUiState();
                }
            }
        }
    }

    @FXML
    private void onEditRubricLibrary() {
        if (assignmentsFile == null) {
            logInfo("Rubric Library edit aborted: No assignments file loaded.");
        } else {
            try {
                FXMLLoader loader = new FXMLLoader(getClass()
                        .getResource("/ui/RubricLibraryEditor.fxml"));
                Scene scene = new Scene(loader.load());
                RubricLibraryEditorController controller = loader.getController();
                controller.init(assignmentsFile);
                Stage stage = new Stage();
                stage.setTitle("Rubric Library");
                stage.initOwner(assignmentCombo.getScene().getWindow());
                stage.initModality(Modality.APPLICATION_MODAL);
                stage.setScene(scene);
                stage.showAndWait();
                updateAssignmentSummary();
                updateUiState();
                try {
                    assignmentsStore.save(assignmentsPath, assignmentsFile);
                    logInfo("Saved assignments file: " + assignmentsPath);
                } catch (IOException e) {
                    logInfo("Failed to save assignments file: " + e.getMessage());
                }
            } catch (IOException e) {
                logInfo("Failed to open Rubric Library editor: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onEditCommentLibrary() {
        if (assignmentsFile == null) {
            logInfo("Edit Comment Library aborted: No assignments file loaded.");
        } else {
            Assignment selected = assignmentCombo.getValue();
            if (selected == null) {
                logInfo("Edit Comment Library aborted: No assignment selected.");
            } else {
                if (commentsLibrary == null) {
                    commentsLibrary = CommentsLibrary.newEmpty();
                }
                openCommentLibraryEditor(selected);
                try {
                    commentsStore.save(commentsPath, commentsLibrary);
                    logInfo("Saved comment library: " + commentsPath.toAbsolutePath());
                } catch (IOException e) {
                    logInfo("Failed to save comments.json: " + e.getMessage());
                }
                updateUiState();
            }
        }
    }

    @FXML
    private void onPull() {
        String rawCommand = cloneCommandField.getText();
        String abortReason = pullAbortReason(rawCommand, selectedRootPath);
        if (abortReason != null) {
            logInfo(abortReason);
        } else {
            logInfo("Running pull command...");
            logInfo("Working directory: " + selectedRootPath);

            if (pullMenuItem != null) {
                pullMenuItem.setDisable(true);
            }
            if (runAllMenuItem != null) {
                runAllMenuItem.setDisable(true);
            }
            String parseAbortReason = startPullIfParsable(
                    rawCommand,
                    selectedRootPath,
                    processRunner,
                    logger::log,
                    finalExit -> Platform.runLater(() -> {
                        if (finalExit == 0) {
                            logInfo("Pull completed successfully.");
                        } else if (finalExit != -1) {
                            logInfo("Pull completed with exit code: " + finalExit);
                        }
                        updateUiState();
                    }),
                    MainWindowController::startDaemonThread
            );
            if (parseAbortReason != null) {
                logInfo(parseAbortReason);
                updateUiState();
            }
        }
    }

    @FXML
    private void onExtract() {
        String abortReason = extractAbortReason(selectedRootPath, assignmentCombo.getValue());
        if (abortReason != null) {
            logInfo(abortReason);
        } else {
            Runnable workerAction = buildIoWorker(
                    this::extractPackagesWorker,
                    "Extract complete.",
                    "Extract failed: ",
                    logger::log,
                    () -> Platform.runLater(this::updateUiState)
            );
            Thread worker = new Thread(workerAction, "extract-worker");

            worker.setDaemon(true);
            worker.start();
        }
    }

    @FXML
    private void onGenerateImports() {
        String abortReason = importsAbortReason(selectedRootPath, assignmentCombo.getValue());
        if (abortReason != null) {
            logInfo(abortReason);
        } else {
            Runnable workerAction = buildIoWorker(
                    this::generateImportsWorker,
                    "Generate Imports complete.",
                    "Generate Imports failed: ",
                    logger::log,
                    () -> Platform.runLater(this::updateUiState)
            );
            Thread worker = new Thread(workerAction, "imports-worker");

            worker.setDaemon(true);
            worker.start();
        }
    }

    @FXML
    private void onGenerateReports() {
        String abortReason = reportsAbortReason(
                selectedRootPath,
                assignmentCombo.getValue(),
                isSelectedAssignmentRubricValid()
        );
        if (abortReason != null) {
            logInfo(abortReason);
        } else {
            if (reportsMenuItem != null) {
                reportsMenuItem.setDisable(true);
            }
            if (runAllMenuItem != null) {
                runAllMenuItem.setDisable(true);
            }
            Assignment selected = assignmentCombo.getValue();
            Thread worker = new Thread(() -> {
                generateReportsWorker(selected);
                Platform.runLater(this::updateUiState);
            }, "reports-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    @FXML
    private void onRunAll() {
        String cloneCmd = cloneCommandField.getText();
        Assignment assignment = assignmentCombo.getValue();
        String abortReason = startRunAllIfValid(
                selectedRootPath,
                assignment,
                isSelectedAssignmentRubricValid(),
                cloneCmd,
                this::disableRunAllMenuItems,
                this::runAllWorker,
                MainWindowController::startDaemonThread
        );

        if (abortReason != null) {
            logInfo(abortReason);
        }
    }

    private void runAllWorker(String cloneCmd,
                              Assignment assignment,
                              Path root) {

        ServiceLogger serviceLogger = logger::log;

        boolean checkstyleEnabled = checkstyleCheckBox.isSelected();
        boolean missingRubricItem = selectedAssignmentMissingCheckstyleRubricItem();

        MainWindowReportDependencies reportDeps =
                buildReportDependencies(
                        serviceLogger,
                        checkstyleEnabled,
                        missingRubricItem
                );

        ReportService reportService =
                new ReportService(assignmentsFile, reportDeps);
        List<WorkflowStep> steps = buildRunAllSteps(
                processRunner,
                serviceLogger,
                mappingService,
                importsService,
                reportService
        );
        executeRunAllWorkflow(
                cloneCmd,
                assignment,
                root,
                mappingsPath,
                steps,
                serviceLogger,
                this::updateUiState,
                Platform::runLater
        );
    }

    static List<WorkflowStep> buildRunAllSteps(ProcessRunner processRunner,
                                               ServiceLogger serviceLogger,
                                               MappingService mappingService,
                                               ImportsService importsService,
                                               ReportService reportService) {
        WorkflowStep pullStep = new PullStep(processRunner, serviceLogger);
        WorkflowStep extractStep = new ExtractStep(mappingService, serviceLogger);
        WorkflowStep importsStep = new ImportsStep(importsService, serviceLogger);
        WorkflowStep reportsStep = new ReportsStep(reportService, serviceLogger);
        return List.of(pullStep, extractStep, importsStep, reportsStep);
    }

    static void executeRunAllWorkflow(String cloneCmd,
                                      Assignment assignment,
                                      Path root,
                                      Path mappingsPath,
                                      List<WorkflowStep> steps,
                                      ServiceLogger serviceLogger,
                                      Runnable uiUpdateAction,
                                      UiScheduler uiScheduler) {
        RunAllService service = new RunAllService(steps, serviceLogger);
        service.runAll(cloneCmd, assignment, root, mappingsPath);
        uiScheduler.runLater(uiUpdateAction);
    }

    private @NonNull MainWindowReportDependencies buildReportDependencies(
            ServiceLogger serviceLogger, boolean enabled, boolean missingRubricItem) {
        return createReportDependencies(
                serviceLogger,
                mappingService,
                checkstyleService,
                unitTestService,
                gradingDraftService,
                sourceCodeService,
                gitService,
                reportHtmlWrapper,
                selectedRootPath,
                enabled,
                missingRubricItem,
                checkstyleUrlField.getText()
        );
    }

    static MainWindowReportDependencies createReportDependencies(
            ServiceLogger serviceLogger,
            MappingService mappingService,
            CheckstyleService checkstyleService,
            UnitTestService unitTestService,
            GradingDraftService gradingDraftService,
            SourceCodeService sourceCodeService,
            GitService gitService,
            ReportHtmlWrapper reportHtmlWrapper,
            Path selectedRootPath,
            boolean enabled,
            boolean missingRubricItem,
            String checkstyleUrl) {
        String normalizedUrl = "";
        if (checkstyleUrl != null) {
            normalizedUrl = checkstyleUrl.trim();
        }
        return new MainWindowReportDependencies(
                serviceLogger,
                mappingService,
                checkstyleService,
                unitTestService,
                gradingDraftService,
                sourceCodeService,
                gitService,
                reportHtmlWrapper,
                selectedRootPath,
                enabled,
                missingRubricItem,
                normalizedUrl
        );
    }

    private void disableRunAllMenuItems() {
        if (runAllMenuItem != null) {
            runAllMenuItem.setDisable(true);
        }
        if (pullMenuItem != null) {
            pullMenuItem.setDisable(true);
        }
        if (extractMenuItem != null) {
            extractMenuItem.setDisable(true);
        }
        if (importsMenuItem != null) {
            importsMenuItem.setDisable(true);
        }
        if (reportsMenuItem != null) {
            reportsMenuItem.setDisable(true);
        }
    }

    @FXML
    private void onGradeAssignment() {
        if (selectedRootPath == null) {
            logInfo("Grade Assignment aborted: Repository root is not set.");
        } else if (assignmentCombo.getValue() == null) {
            logInfo("Grade Assignment aborted: No assignment selected.");
        } else {
            Assignment selected = assignmentCombo.getValue();
            try {
                FXMLLoader loader = new FXMLLoader(getClass()
                        .getResource("/ui/GradingWindow.fxml"));
                Scene scene = new Scene(loader.load());
                GradingWindowController controller = loader.getController();
                controller.init(assignmentsFile, selected, selectedRootPath, mappingsPath);
                Stage stage = new Stage();
                stage.setTitle("Grade: " + selected.getCourseCode() + " "
                        + selected.getAssignmentCode());
                stage.initOwner(assignmentCombo.getScene().getWindow());
                assignmentCombo.getScene().getStylesheets().add(
                        Objects.requireNonNull(getClass().getResource("/ui/grading.css"))
                                .toExternalForm());
                stage.initModality(Modality.NONE);
                stage.setScene(scene);
                final int width = 900;
                final int height = 600;
                stage.setMinWidth(width);
                stage.setMinHeight(height);
                stage.show();
                logInfo("Opened grading window for: " + selected.getKey());
            } catch (IOException e) {
                logInfo("Failed to open grading window: " + e.getMessage());
                Throwable t = e;
                while (t != null) {
                    logInfo("CAUSE: " + t.getClass().getName() + ": " + t.getMessage());
                    t = t.getCause();
                }
            }
        }
    }

    @FXML
    private void onValidateGh() {
    }

    @FXML
    private void onAbout() {
    }

    private void persistCheckstyleUrl(String newValue) {
        if (settings == null) {
            settings = new Settings();
            settings.setSchemaVersion(1);
        }
        String url = "";
        if (newValue != null) {
            url = newValue.trim();
        }
        settings.setCheckstyleConfigUrl(url);
        try {
            settingsStore.save(settingsPath, settings);
        } catch (IOException e) {
            logInfo("Failed to save settings: " + e.getMessage());
        }
    }

    private void saveAssignmentsQuietly() {
        if (assignmentsFile != null) {
            try {
                assignmentsStore.save(assignmentsPath, assignmentsFile);
            } catch (IOException e) {
                logInfo("Failed to save assignments.json: " + e.getMessage());
            }
        }
    }

    private void ensureAssignmentsLoaded() {
        try {
            if (Files.exists(assignmentsPath) && Files.isRegularFile(assignmentsPath)) {
                assignmentsFile = assignmentsStore.load(assignmentsPath);
                logInfo("Loaded assignments: " + assignmentsPath);
            } else {
                try (InputStream in = getClass().getResourceAsStream("/assignments.json")) {
                    if (in != null) {
                        assignmentsFile = assignmentsStore.load(in);
                        assignmentsStore.save(assignmentsPath, assignmentsFile);
                        logInfo("Created assignments from bundled default: " + assignmentsPath);
                    } else {
                        // Last resort empty
                        assignmentsFile = new AssignmentsFile();
                        assignmentsFile.setSchemaVersion(1);
                        assignmentsFile.setAssignments(new ArrayList<>());
                        assignmentsFile.setRubricItemLibrary(new HashMap<>());
                        assignmentsStore.save(assignmentsPath, assignmentsFile);
                        logInfo("Created empty assignments: " + assignmentsPath);
                    }
                }
            }
        } catch (IOException e) {
            assignmentsFile = null;
            logInfo("Failed to load/create assignments: " + e.getMessage());
        }
    }

    private void populateAssignmentCombo() {
        assignmentCombo.getItems().clear();
        if (assignmentsFile != null && assignmentsFile.getAssignments() != null) {
            for (Assignment a : assignmentsFile.getAssignments()) {
                normalizeExpectedFiles(a);
            }
            assignmentsFile.getAssignments().stream()
                    .sorted(Comparator.comparing(Assignment::getKey))
                    .forEach(a -> assignmentCombo.getItems().add(a));
        }

        if (!assignmentCombo.getItems().isEmpty()
                && assignmentCombo.getValue() == null) {
            assignmentCombo.getSelectionModel().selectFirst();
        }
        updateAssignmentSummary();
    }

    private void normalizeExpectedFiles(Assignment a) {
        if (a != null && a.getExpectedFiles() != null) {
            a.setExpectedFiles(normalizedExpectedFiles(a.getExpectedFiles()));
        }
    }

    private Assignment openAssignmentEditor(Assignment existingOrNull) {
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(getClass().getResource("/ui/AssignmentEditor.fxml"));
            Parent root = loader.load();
            AssignmentEditorController controller = loader.getController();
            controller.init(assignmentsFile, existingOrNull);
            Stage dialog = new Stage();
            dialog.setTitle(existingOrNull == null ? "New Assignment" : "Edit Assignment");
            dialog.initOwner(assignmentCombo.getScene().getWindow());
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setScene(new Scene(root));
            dialog.setResizable(false);
            dialog.showAndWait();
            if (controller.isSaved()) {
                return controller.getResult();
            }
            return null;
        } catch (IOException e) {
            logInfo("Failed to open assignment editor: " + e);
            for (StackTraceElement ste : e.getStackTrace()) {
                logInfo("  at " + ste);
            }
            Throwable cause = e.getCause();
            while (cause != null) {
                logInfo("Caused by: " + cause);
                for (StackTraceElement ste : cause.getStackTrace()) {
                    logInfo("  at " + ste);
                }
                cause = cause.getCause();
            }
            return null;
        }
    }

    private void openCommentLibraryEditor(Assignment assignment) {
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(getClass().getResource("/ui/CommentLibraryEditor.fxml"));

            Parent root = loader.load();

            CommentLibraryEditorController controller = loader.getController();
            controller.init(
                    assignment,
                    assignmentsFile.getRubricItemLibrary(),
                    commentsLibrary
            );

            Stage dialog = new Stage();
            dialog.setTitle("Comment Library - " + assignment.getKey());
            dialog.initOwner(assignmentCombo.getScene().getWindow());
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setScene(new Scene(root));
            dialog.setResizable(true);

            dialog.showAndWait();

        } catch (IOException e) {
            logInfo("Failed to open Comment Library editor: " + e.getMessage());
        }
    }

    private boolean generateReportsWorker(Assignment assignment) {
        ServiceLogger serviceLogger = logger::log;
        boolean enabled = checkstyleCheckBox.isSelected();
        boolean missingRubricItem = selectedAssignmentMissingCheckstyleRubricItem();
        MainWindowReportDependencies deps = buildReportDependencies(serviceLogger, enabled,
                missingRubricItem);

        ReportService service = new ReportService(assignmentsFile, deps);

        ReportService.ReportGenerationResult result =
                service.generateReports(assignment, selectedRootPath, mappingsPath);

        return result.isSuccess();
    }

    private void generateImportsWorker() throws IOException {
        importsService.generateImports(selectedRootPath, mappingsPath);
    }

    private boolean selectedAssignmentMissingCheckstyleRubricItem() {
        return isAssignmentMissingCheckstyleRubricItem(assignmentCombo.getValue(), assignmentsFile);
    }

    // ============================================================
    // GRADING DRAFT / RUBRIC HELPERS
    // ============================================================

    private boolean isSelectedAssignmentRubricValid() {
        return isAssignmentRubricValid(assignmentCombo.getValue());
    }

    static UiState evaluateUiState(String cloneCommand,
                                   Path rootPath,
                                   Assignment selectedAssignment,
                                   boolean checkstyleEnabled,
                                   String checkstyleUrl,
                                   boolean hasAssignmentsLoaded) {
        boolean hasCloneCommand = cloneCommand != null && !cloneCommand.trim().isEmpty();
        boolean hasRootPath = rootPath != null;
        boolean hasAssignment = selectedAssignment != null;
        boolean hasCheckstyleUrl = checkstyleUrl != null && !checkstyleUrl.trim().isEmpty();
        boolean checkstyleReady = !checkstyleEnabled || hasCheckstyleUrl;
        boolean rubricValid = isAssignmentRubricValid(selectedAssignment);

        boolean canExtract = hasRootPath && hasAssignment && rubricValid;
        boolean canReports = hasRootPath && hasAssignment && rubricValid && checkstyleReady;
        boolean canRunAll = hasCloneCommand && hasAssignment && rubricValid && checkstyleReady;

        return new UiState(
                hasCloneCommand,
                canExtract,
                canExtract,
                canReports,
                canRunAll,
                hasAssignment,
                hasAssignment,
                hasRootPath && hasAssignment,
                hasAssignmentsLoaded
        );
    }

    static List<String> normalizedExpectedFiles(List<String> rawFiles) {
        List<String> cleaned = new ArrayList<>();
        if (rawFiles == null) {
            return cleaned;
        }
        for (String rawFile : rawFiles) {
            if (rawFile == null) {
                continue;
            }
            String fileName = Path.of(rawFile).getFileName().toString().trim();
            if (!fileName.isEmpty() && !cleaned.contains(fileName)) {
                cleaned.add(fileName);
            }
        }
        cleaned.sort(String::compareTo);
        return cleaned;
    }

    static boolean isAssignmentMissingCheckstyleRubricItem(Assignment assignment,
                                                           AssignmentsFile file) {
        if (assignment == null || assignment.getRubric() == null
                || assignment.getRubric().getItems() == null) {
            return true;
        }
        if (file == null || file.getRubricItemLibrary() == null) {
            return true;
        }
        for (RubricItemRef ref : assignment.getRubric().getItems()) {
            String id = ref.getRubricItemId();
            RubricItemDef def = file.getRubricItemLibrary().get(id);
            if (def != null && def.isCheckstyleItem()) {
                return false;
            }
        }
        return true;
    }

    static boolean isAssignmentRubricValid(Assignment assignment) {
        if (assignment == null
                || assignment.getRubric() == null
                || assignment.getRubric().getItems() == null) {
            return false;
        }
        int totalPoints = 0;
        for (RubricItemRef itemRef : assignment.getRubric().getItems()) {
            totalPoints += itemRef.getPoints();
        }
        return totalPoints == RUBRIC_EXPECTED_TOTAL;
    }

    static Runnable buildIoWorker(IoAction action,
                                  String successMessage,
                                  String failurePrefix,
                                  ServiceLogger serviceLogger,
                                  Runnable uiUpdateAction) {
        return () -> {
            try {
                try {
                    action.run();
                    serviceLogger.log(successMessage);
                } catch (IOException e) {
                    serviceLogger.log(failurePrefix + e.getMessage());
                }
            } finally {
                uiUpdateAction.run();
            }
        };
    }

    static String startPullIfParsable(String rawCommand,
                                      Path rootPath,
                                      ProcessRunner processRunner,
                                      ServiceLogger outputLogger,
                                      PullExitHandler exitHandler,
                                      ThreadStarter threadStarter) {
        List<String> args = parsePullArgs(rawCommand, processRunner);
        if (args.isEmpty()) {
            return "Pull aborted: Could not parse command.";
        }
        Runnable workerAction = buildPullWorkerAction(
                args,
                rootPath,
                processRunner,
                outputLogger,
                exitHandler
        );
        threadStarter.start("pull-worker", workerAction);
        return null;
    }

    static List<String> parsePullArgs(String rawCommand, ProcessRunner processRunner) {
        return processRunner.tokenizeCommand(rawCommand.trim());
    }

    static Runnable buildPullWorkerAction(List<String> args,
                                          Path rootPath,
                                          ProcessRunner processRunner,
                                          ServiceLogger outputLogger,
                                          PullExitHandler exitHandler) {
        return () -> {
            int exitCode = processRunner.runAndLog(args, rootPath, outputLogger::log);
            exitHandler.onExit(exitCode);
        };
    }

    static String startRunAllIfValid(Path rootPath,
                                     Assignment assignment,
                                     boolean rubricValid,
                                     String cloneCommand,
                                     Runnable disableMenusAction,
                                     RunAllWorkerInvoker runAllWorkerInvoker,
                                     ThreadStarter threadStarter) {
        String abortReason = runAllAbortReason(rootPath, assignment, rubricValid, cloneCommand);
        if (abortReason != null) {
            return abortReason;
        }
        disableMenusAction.run();
        Runnable workerAction = () -> runAllWorkerInvoker.run(cloneCommand, assignment, rootPath);
        threadStarter.start("runall-worker", workerAction);
        return null;
    }

    static void startDaemonThread(String name, Runnable action) {
        Thread worker = new Thread(action, name);
        worker.setDaemon(true);
        worker.start();
    }

    static String pullAbortReason(String cloneCommand, Path rootPath) {
        if (cloneCommand == null || cloneCommand.trim().isEmpty()) {
            return "Pull aborted: GitHub Classroom command is empty.";
        }
        if (rootPath == null) {
            return "Pull aborted: Repository root is not set.";
        }
        return null;
    }

    static String extractAbortReason(Path rootPath, Assignment assignment) {
        if (rootPath == null) {
            return "Extract aborted: Repository root is not set.";
        }
        if (assignment == null) {
            return "Extract aborted: No assignment selected.";
        }
        return null;
    }

    static String importsAbortReason(Path rootPath, Assignment assignment) {
        if (rootPath == null) {
            return "Generate Imports aborted: Repository root is not set.";
        }
        if (assignment == null) {
            return "Generate Imports aborted: No assignment selected.";
        }
        return null;
    }

    static String reportsAbortReason(Path rootPath,
                                     Assignment assignment,
                                     boolean rubricValid) {
        if (rootPath == null) {
            return "Generate Reports aborted: Repository root is not set.";
        }
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            return "Generate Reports aborted: Repository root does not exist or "
                    + "is not a directory.";
        }
        if (assignment == null) {
            return "Generate Reports aborted: No assignment selected.";
        }
        if (!rubricValid) {
            return "Generate Reports aborted: Rubric total must be exactly 100 points.";
        }
        return null;
    }

    static String runAllAbortReason(Path rootPath,
                                    Assignment assignment,
                                    boolean rubricValid,
                                    String cloneCommand) {
        if (rootPath == null) {
            return "Run All aborted: Repository root is not set.";
        }
        if (assignment == null) {
            return "Run All aborted: No assignment selected.";
        }
        if (!rubricValid) {
            return "Run All aborted: Rubric total must be exactly 100 points.";
        }
        if (cloneCommand == null || cloneCommand.trim().isEmpty()) {
            return "Run All aborted: GitHub Classroom command is empty.";
        }
        return null;
    }

    record UiState(boolean pullEnabled,
                   boolean extractEnabled,
                   boolean importsEnabled,
                   boolean reportsEnabled,
                   boolean runAllEnabled,
                   boolean editAssignmentEnabled,
                   boolean deleteAssignmentEnabled,
                   boolean gradeAssignmentEnabled,
                   boolean exportAssignmentsEnabled) {
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface ThreadStarter {
        void start(String name, Runnable action);
    }

    @FunctionalInterface
    interface PullExitHandler {
        void onExit(int exitCode);
    }

    @FunctionalInterface
    interface RunAllWorkerInvoker {
        void run(String cloneCmd, Assignment assignment, Path root);
    }

    @FunctionalInterface
    interface UiScheduler extends Consumer<Runnable> {
        default void runLater(Runnable action) {
            accept(action);
        }
    }

    public UnitTestService getUnitTestService() {
        return unitTestService;
    }

    public CheckstyleService.CheckstyleResult buildCheckstyleResult(Path repoPath) {
        boolean enabled = checkstyleCheckBox.isSelected();
        boolean missingRubricItem = selectedAssignmentMissingCheckstyleRubricItem();
        String url = checkstyleUrlField.getText();

        return checkstyleService.buildCheckstyleResult(
                repoPath,
                selectedRootPath,
                enabled,
                missingRubricItem,
                url
        );
    }

    public Map<String, RepoMapping> loadMapping(Path mappingsPath) {
        return mappingService.loadMapping(mappingsPath);
    }

    public Path resolveRepoRoot(Path mappedRepoPath) {
        return mappingService.resolveRepoRoot(mappedRepoPath);
    }

    private void extractPackagesWorker() throws IOException {
        mappingService.extractPackages(selectedRootPath, mappingsPath);
    }

    private Path appDataDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = ".";
        }

        final String folderName = ".gh-classroom-utils";
        return Path.of(home).resolve(folderName);
    }

    private Path ensureDir(Path dir) {
        if (dir == null) {
            return Path.of(".");
        }

        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                logInfo("Failed to create directory: " + dir + " (" + e.getMessage() + ")");
            }
        }

        return dir;
    }
}

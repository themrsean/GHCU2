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

/**
 * Main Window Controller
 */
public class MainWindowController implements Initializable {

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
        gradingDraftService = new GradingDraftService();
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
        boolean hasCloneCommand = cloneCommandField.getText() != null
                && !cloneCommandField.getText().trim().isEmpty();
        boolean hasRootPath = selectedRootPath != null;
        boolean hasAssignment = assignmentCombo.getValue() != null;
        boolean checkstyleEnabled = checkstyleCheckBox.isSelected();
        boolean hasCheckstyleUrl = checkstyleUrlField.getText() != null
                && !checkstyleUrlField.getText().trim().isEmpty();

        boolean checkstyleReady;
        if (checkstyleEnabled) {
            checkstyleReady = hasCheckstyleUrl;
        } else {
            checkstyleReady = true;
        }

        boolean rubricValid = isSelectedAssignmentRubricValid();

        boolean canExtract = hasRootPath && hasAssignment && rubricValid;
        boolean canReports = hasRootPath && hasAssignment && rubricValid && checkstyleReady;
        boolean canRunAll = hasCloneCommand && hasAssignment && rubricValid && checkstyleReady;
        boolean canImports = hasRootPath && hasAssignment && rubricValid;

        if (pullMenuItem != null) {
            pullMenuItem.setDisable(!hasCloneCommand);
        }
        if (extractMenuItem != null) {
            extractMenuItem.setDisable(!canExtract);
        }
        if (importsMenuItem != null) {
            importsMenuItem.setDisable(!canImports);
        }
        if (reportsMenuItem != null) {
            reportsMenuItem.setDisable(!canReports);
        }
        if (runAllMenuItem != null) {
            runAllMenuItem.setDisable(!canRunAll);
        }
        if (editAssignmentMenuItem != null) {
            editAssignmentMenuItem.setDisable(!hasAssignment);
        }
        if (deleteAssignmentMenuItem != null) {
            deleteAssignmentMenuItem.setDisable(!hasAssignment);
        }
        if (gradeAssignmentMenuItem != null) {
            gradeAssignmentMenuItem.setDisable(!hasRootPath || !hasAssignment);
        }
        if (exportAssignmentsMenuItem != null) {
            boolean hasAssignmentsLoaded = assignmentCombo.getItems() != null
                    && !assignmentCombo.getItems().isEmpty();
            exportAssignmentsMenuItem.setDisable(!hasAssignmentsLoaded);
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
        if (rawCommand == null || rawCommand.trim().isEmpty()) {
            logInfo("Pull aborted: GitHub Classroom command is empty.");
        } else if (selectedRootPath == null) {
            logInfo("Pull aborted: Repository root is not set.");
        } else {
            logInfo("Running pull command...");
            logInfo("Working directory: " + selectedRootPath);

            if (pullMenuItem != null) {
                pullMenuItem.setDisable(true);
            }
            if (runAllMenuItem != null) {
                runAllMenuItem.setDisable(true);
            }
            List<String> args = processRunner.tokenizeCommand(rawCommand.trim());
            if (args.isEmpty()) {
                logInfo("Pull aborted: Could not parse command.");
                updateUiState();
            } else {
                Thread worker = new Thread(() -> {
                    final int finalExit = processRunner.runAndLog(args, selectedRootPath,
                            logger::log);
                    Platform.runLater(() -> {
                        if (finalExit == 0) {
                            logInfo("Pull completed successfully.");
                        } else if (finalExit != -1) {
                            logInfo("Pull completed with exit code: " + finalExit);
                        }
                        updateUiState();
                    });
                }, "pull-worker");

                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    @FXML
    private void onExtract() {
        if (selectedRootPath == null) {
            logInfo("Extract aborted: Repository root is not set.");
        } else if (assignmentCombo.getValue() == null) {
            logInfo("Extract aborted: No assignment selected.");
        } else {
            Thread worker = new Thread(() -> {
                try {
                    extractPackagesWorker();
                    logger.log("Extract complete.");
                } catch (IOException e) {
                    logger.log("Extract failed: " + e.getMessage());
                }
                Platform.runLater(this::updateUiState);
            }, "extract-worker");

            worker.setDaemon(true);
            worker.start();
        }
    }

    @FXML
    private void onGenerateImports() {
        if (selectedRootPath == null) {
            logInfo("Generate Imports aborted: Repository root is not set.");
        } else if (assignmentCombo.getValue() == null) {
            logInfo("Generate Imports aborted: No assignment selected.");
        } else {
            Thread worker = new Thread(() -> {
                try {
                    generateImportsWorker();
                    logger.log("Generate Imports complete.");
                } catch (IOException e) {
                    logger.log("Generate Imports failed: " + e.getMessage());
                }
                Platform.runLater(this::updateUiState);
            }, "imports-worker");

            worker.setDaemon(true);
            worker.start();
        }
    }

    @FXML
    private void onGenerateReports() {
        if (selectedRootPath == null) {
            logInfo("Generate Reports aborted: Repository root is not set.");
        } else if (!Files.exists(selectedRootPath) || !Files.isDirectory(selectedRootPath)) {
            logInfo("Generate Reports aborted: Repository root does not exist or " +
                    "is not a directory.");
        } else if (assignmentCombo.getValue() == null) {
            logInfo("Generate Reports aborted: No assignment selected.");
        } else if (!isSelectedAssignmentRubricValid()) {
            logInfo("Generate Reports aborted: Rubric total must be exactly 100 points.");
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
        boolean ok = true;

        String cloneCmd = cloneCommandField.getText();
        Assignment assignment = assignmentCombo.getValue();
        Path root = selectedRootPath;

        if (selectedRootPath == null) {
            logInfo("Run All aborted: Repository root is not set.");
            ok = false;
        } else if (assignment == null) {
            logInfo("Run All aborted: No assignment selected.");
            ok = false;
        } else if (!isSelectedAssignmentRubricValid()) {
            logInfo("Run All aborted: Rubric total must be exactly 100 points.");
            ok = false;
        } else if (cloneCmd == null || cloneCmd.trim().isEmpty()) {
            logInfo("Run All aborted: GitHub Classroom command is empty.");
            ok = false;
        }

        if (ok) {
            disableRunAllMenuItems();

            Thread worker = new Thread(
                    () -> runAllWorker(cloneCmd, assignment, root),
                    "runall-worker"
            );

            worker.setDaemon(true);
            worker.start();
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

        WorkflowStep pullStep =
                new PullStep(processRunner, serviceLogger);

        WorkflowStep extractStep =
                new ExtractStep(mappingService, serviceLogger);

        WorkflowStep importsStep =
                new ImportsStep(importsService, serviceLogger);

        WorkflowStep reportsStep =
                new ReportsStep(reportService, serviceLogger);

        List<WorkflowStep> steps = List.of(
                pullStep,
                extractStep,
                importsStep,
                reportsStep
        );

        RunAllService service =
                new RunAllService(steps, serviceLogger);

        service.runAll(cloneCmd, assignment, root, mappingsPath);

        Platform.runLater(this::updateUiState);
    }

    private @NonNull MainWindowReportDependencies buildReportDependencies(
            ServiceLogger serviceLogger, boolean enabled, boolean missingRubricItem) {
        String url = "";
        if (checkstyleUrlField.getText() != null) {
            url = checkstyleUrlField.getText().trim();
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
                url
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
            List<String> cleaned = new ArrayList<>();
            for (String s : a.getExpectedFiles()) {
                if (s == null) {
                    continue;
                }
                String name = Path.of(s).getFileName().toString().trim();
                if (!name.isEmpty() && !cleaned.contains(name)) {
                    cleaned.add(name);
                }
            }
            cleaned.sort(String::compareTo);
            a.setExpectedFiles(cleaned);
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
        Assignment assignment = assignmentCombo.getValue();
        if (assignment == null) {
            return true;
        }
        if (assignment.getRubric() == null || assignment.getRubric().getItems() == null) {
            return true;
        }
        if (assignmentsFile == null || assignmentsFile.getRubricItemLibrary() == null) {
            return true;
        }
        for (RubricItemRef ref : assignment.getRubric().getItems()) {
            String id = ref.getRubricItemId();
            RubricItemDef def = assignmentsFile.getRubricItemLibrary().get(id);
            if (def != null && def.isCheckstyleItem()) {
                return false;
            }
        }
        return true;
    }

    // ============================================================
    // GRADING DRAFT / RUBRIC HELPERS
    // ============================================================

    private boolean isSelectedAssignmentRubricValid() {
        boolean valid = false;

        Assignment selected = assignmentCombo.getValue();
        if (selected != null
                && selected.getRubric() != null
                && selected.getRubric().getItems() != null) {

            int totalPoints = 0;
            for (model.RubricItemRef itemRef : selected.getRubric().getItems()) {
                totalPoints += itemRef.getPoints();
            }

            final int expectedPoints = 100;
            if (totalPoints == expectedPoints) {
                valid = true;
            }
        }

        return valid;
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
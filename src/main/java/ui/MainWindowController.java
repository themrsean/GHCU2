/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
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
import model.Comments;
import model.Comments.CommentsLibrary;
import model.Comments.CommentsStore;
import model.RubricItemDef;
import model.RubricItemRef;
import model.RubricTableBuilder;
import model.Settings;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import persistence.AssignmentsStore;
import persistence.SettingsStore;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Main Window Controller
 */
public class MainWindowController implements Initializable {
    @FXML private TextField cloneCommandField;
    @FXML private TextField rootPathField;
    @FXML private ComboBox<Assignment> assignmentCombo;
    @FXML private CheckBox checkstyleCheckBox;
    @FXML private TextField checkstyleUrlField;
    @FXML private Label assignmentTitleLabel;
    @FXML private ListView<String> expectedFilesListView;
    @FXML private ListView<String> rubricListView;
    @FXML private TextArea logTextArea;
    @FXML private MenuItem pullMenuItem;
    @FXML private MenuItem extractMenuItem;
    @FXML private MenuItem importsMenuItem;
    @FXML private MenuItem reportsMenuItem;
    @FXML private MenuItem runAllMenuItem;
    @FXML private MenuItem editAssignmentMenuItem;
    @FXML private MenuItem deleteAssignmentMenuItem;
    @FXML private MenuItem gradeAssignmentMenuItem;
    @FXML private MenuItem exportAssignmentsMenuItem;
    @FXML private VBox assignmentSummaryBox;

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

    private Settings settings;
    private AssignmentsFile assignmentsFile;
    private Path selectedRootPath;
    private CommentsLibrary commentsLibrary;
    private LogAppender logger;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        // 1) Initial control state
        checkstyleUrlField.setDisable(true);
        logger = new LogAppender(logTextArea);
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

    private static Path appDataDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();

        Path base;
        if (os.contains("mac")) {
            base = Path.of(home, "Library", "Application Support");
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = (appData != null && !appData.isBlank()) ? Path.of(appData) :
                    Path.of(home, "AppData", "Roaming");
        } else {
            base = Path.of(home, ".local", "share");
        }
        return base.resolve("GHCU2"); // app folder name
    }

    private static Path ensureDir(Path p) {
        try {
            Files.createDirectories(p);
        } catch (IOException ignored) {
            // should not get here
        }
        return p;
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

    private void logInfo(String msg) {
        logTextArea.appendText(msg + System.lineSeparator());
    }

    @FXML private void onImportAssignments() {
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

    @FXML private void onExportAssignments() {
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

    @FXML private void onGradeAssignment() {
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
                controller.init(assignmentsFile, selected, selectedRootPath);
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

    private void updateUiAfterAssignmentsLoad() {
        updateUiState();
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

        exportAssignmentsMenuItem.setDisable(assignmentCombo.getItems().isEmpty());
    }

    @FXML private void onExit() {
        Platform.exit();
    }

    @FXML private void onNewAssignment() {
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

    @FXML private void onEditAssignment() {
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

    @FXML private void onDeleteAssignment() {
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
            List<String> args = tokenizeCommand(rawCommand.trim());
            if (args.isEmpty()) {
                logInfo("Pull aborted: Could not parse command.");
                updateUiState();
            } else {
                Thread worker = new Thread(() -> runProcess(args), "pull-worker");
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    private void runProcess(List<String> args) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(selectedRootPath.toFile());
        pb.redirectErrorStream(true);
        int exitCode = -1;
        try {
            Process p = pb.start();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                while (line != null) {
                    logger.log(line);
                    line = reader.readLine();
                }
            }
            exitCode = p.waitFor();
        } catch (IOException e) {
            logger.log("Pull failed (IO): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log("Pull interrupted.");
        }

        final int finalExit = exitCode;
        Platform.runLater(() -> {
            if (finalExit == 0) {
                logInfo("Pull completed successfully.");
            } else if (finalExit != -1) {
                logInfo("Pull completed with exit code: " + finalExit);
            }
            updateUiState();
        });
    }

    /**
     * Tokenizes a command line into argv parts, supporting double-quotes.
     * Example: gh classroom clone student-repos -a 123
     */
    private List<String> tokenizeCommand(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < command.length()) {
            char c = command.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
            i++;
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
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

    private void extractPackagesWorker() throws IOException {
        Path scanRoot = selectedRootPath;
        List<Path> topDirs = listImmediateDirectories(selectedRootPath);
        Path submissionsDir = null;
        for (Path d : topDirs) {
            String name = d.getFileName().toString().toLowerCase();
            if (Files.isDirectory(d) && name.endsWith("-submissions")) {
                submissionsDir = d;
            }
        }
        if (submissionsDir != null) {
            scanRoot = submissionsDir;
        }
        Path packagesRoot = selectedRootPath.resolve("packages");
        ensureDirectoryExists(packagesRoot);
        Map<String, RepoMapping> mapping = new java.util.TreeMap<>();
        List<Path> repoDirs = listImmediateDirectories(scanRoot);
        if (repoDirs.isEmpty()) {
            throw new IOException("No repositories found under: " + scanRoot);
        }
        logger.log("Scanning repos under: " + scanRoot);
        logger.log("Found " + repoDirs.size() + " repository folder(s).");
        for (Path repoDir : repoDirs) {
            String repoFolderName = repoDir.getFileName().toString();
            if (!repoFolderName.equalsIgnoreCase("packages")) {
                Path gitDir = repoDir.resolve(".git");
                if (!Files.exists(gitDir) || !Files.isDirectory(gitDir)) {
                    logger.log("SKIP repo " + repoFolderName + ": not a git repository.");
                } else {
                    Path studentPackageDir = findStudentPackageDir(repoDir); // updated below
                    if (studentPackageDir == null) {
                        logger.log("SKIP repo " + repoFolderName +
                                ": could not locate src/{studentPackage}.");
                    } else {
                        String packageName = studentPackageDir.getFileName().toString();
                        Path dest = packagesRoot.resolve(packageName);
                        boolean copied = copyPackageFolder(studentPackageDir,
                                dest, repoFolderName, packageName);
                        if (copied) {
                            RepoMapping m = new RepoMapping();
                            m.setRepoPath(repoDir.toAbsolutePath().toString());
                            mapping.put(packageName, m);
                        }
                    }
                    if (mapping.isEmpty()) {
                        throw new IOException("No packages extracted from: " + scanRoot);
                    }
                    saveMapping(mappingsPath, mapping);
                    logger.log("Extract complete. Packages directory: " + packagesRoot);
                }
            }
        }
    }

    private List<Path> listImmediateDirectories(Path root) {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        } catch (IOException e) {
            logger.log("Failed to list repository root: " + e.getMessage());
        }
        return dirs;
    }

    private void ensureDirectoryExists(Path dir) {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                logger.log("Failed to create directory: " + dir + " (" + e.getMessage() + ")");
            }
        }
    }

    private Path findStudentPackageDir(Path repoDir) {
        Path src = repoDir.resolve("src");
        if (!Files.isDirectory(src)) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(src)) {
            stream.filter(Files::isDirectory).forEach(candidates::add);
        } catch (IOException e) {
            logger.log("Failed to read src folder for " + repoDir.getFileName() + ": "
                    + e.getMessage());
            return null;
        }
        candidates.removeIf(p -> p.getFileName().toString().equalsIgnoreCase("test"));
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparing(p -> p.getFileName().toString()));
        if (candidates.size() > 1) {
            logger.log("WARN " + repoDir.getFileName() +
                    ": multiple non-test packages under src/; using "
                    + candidates.getFirst().getFileName());
        }
        return candidates.getFirst();
    }

    private boolean copyPackageFolder(Path sourcePackageDir, Path destPackageDir,
                                      String repoName, String studentPackage) {
        boolean ok = true;
        if (Files.exists(destPackageDir)) {
            boolean deleted = deleteDirectoryRecursively(destPackageDir);
            if (!deleted) {
                logger.log("SKIP " + repoName + ": cannot overwrite existing package folder: "
                        + destPackageDir);
                ok = false;
            }
        }
        if (ok) {
            try {
                Files.createDirectories(destPackageDir);
            } catch (IOException e) {
                logger.log("SKIP " + repoName + ": failed to create destination folder: "
                        + e.getMessage());
                ok = false;
            }
        }
        if (ok) {
            AtomicBoolean copyOk = new AtomicBoolean(true);
            try (Stream<Path> paths = Files.walk(sourcePackageDir)) {
                paths.forEach(path -> {
                    Path rel = sourcePackageDir.relativize(path);
                    Path target = destPackageDir.resolve(rel);
                    try {
                        if (Files.isDirectory(path)) {
                            if (!Files.exists(target)) {
                                Files.createDirectories(target);
                            }
                        } else {
                            Files.copy(path, target,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        copyOk.set(false);
                        logger.log("COPY ERROR " + repoName + ": " + e.getMessage());
                    }
                });
            } catch (IOException e) {
                logger.log("SKIP " + repoName + ": failed to walk package directory: "
                        + e.getMessage());
                ok = false;
            }
            if (!copyOk.get()) {
                ok = false;
            }
        }
        if (ok) {
            logger.log("OK package " + studentPackage + " extracted from repo " + repoName);
        }
        return ok;
    }

    private boolean deleteDirectoryRecursively(Path dir) {
        boolean ok = true;
        try {
            if (Files.exists(dir)) {
                AtomicBoolean deleteOk = new AtomicBoolean(true);
                try (Stream<Path> paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            deleteOk.set(false);
                            logger.log("DELETE ERROR: " + p + " (" + e.getMessage() + ")");
                        }
                    });
                    if (!deleteOk.get()) {
                        ok = false;
                    }
                }
            }
        } catch (IOException e) {
            ok = false;
            logger.log("Failed to delete directory: " + dir + " (" + e.getMessage() + ")");
        }
        return ok;
    }


    private void saveMapping(Path mappingFile, Map<String, RepoMapping> mapping) {
        ObjectMapper mapper =
                new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();

        try {
            String json = writer.writeValueAsString(mapping);
            Files.writeString(mappingFile, json);
            logger.log("Wrote mapping file: " + mappingFile);
        } catch (JsonProcessingException e) {
            logger.log("Failed to serialize mapping.json: " + e.getMessage());
        } catch (IOException e) {
            logger.log("Failed to write mapping.json: " + e.getMessage());
        }
    }

    /**
     * Simple data object representing the mapping between a student's extracted package name
     * and the original GitHub Classroom repository it came from.
     *
     * <p>This is serialized to and deserialized from {@code mapping.json} so later steps
     * (imports generation and report generation) can locate the correct repository folder
     * for each student package.</p>
     */
    public static class RepoMapping {
        private String repoPath;

        public String getRepoPath() {
            return repoPath;
        }

        public void setRepoPath(String repoPath) {
            this.repoPath = repoPath;
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

    private void generateImportsWorker() throws IOException {
        Path packagesRoot = selectedRootPath.resolve("packages");
        Path importsFile = packagesRoot.resolve("imports.txt");
        if (!Files.exists(packagesRoot) || !Files.isDirectory(packagesRoot)) {
            throw new IOException("packages folder does not exist: " + packagesRoot);
        }
        if (!Files.exists(mappingsPath)) {
            throw new IOException("mapping.json not found: " + mappingsPath +
                    " (run Extract Packages first)");
        }
        Map<String, RepoMapping> mapping = loadMapping(mappingsPath);
        if (mapping == null || mapping.isEmpty()) {
            throw new IOException("mapping.json is empty or invalid: " + mappingsPath);
        }
        List<String> packageNames = new ArrayList<>(mapping.keySet());
        packageNames.sort(String::compareTo);
        List<String> lines = new ArrayList<>();
        for (String pkg : packageNames) {
            lines.add("import " + pkg + ".*;");
        }
        Files.writeString(importsFile, String.join(System.lineSeparator(), lines)
                + System.lineSeparator());
        logger.log("Wrote imports file: " + importsFile);
        logger.log("Imported " + packageNames.size() + " package(s).");
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

    private boolean generateReportsWorker(Assignment assignment) {
        boolean wroteAny = false;
        boolean hadFailures = false;

        if (assignment == null) {
            logger.log("Generate Reports failed: assignment is null.");
            return false;
        }

        String assignmentId = assignment.getCourseCode() + assignment.getAssignmentCode();
        Path packagesRoot = selectedRootPath.resolve("packages");

        if (!Files.exists(packagesRoot) || !Files.isDirectory(packagesRoot)) {
            logger.log("Generate Reports failed: packages folder missing: " + packagesRoot);
            return false;
        }
        if (!Files.exists(mappingsPath) || !Files.isRegularFile(mappingsPath)) {
            logger.log("Generate Reports failed: mapping.json not found: " + mappingsPath);
            logger.log("Run Extract Packages first.");
            return false;
        }

        Map<String, RepoMapping> mapping = loadMapping(mappingsPath);
        if (mapping == null || mapping.isEmpty()) {
            logger.log("Generate Reports failed: mapping.json is empty or invalid.");
            return false;
        }

        List<String> packageNames = new ArrayList<>(mapping.keySet());
        packageNames.sort(String::compareTo);

        logger.log("Generating reports for " + packageNames.size() + " student package(s).");

        for (String pkg : packageNames) {
            RepoMapping repo = mapping.get(pkg);
            if (repo == null) {
                logger.log("SKIP " + pkg + ": mapping missing.");
                hadFailures = true;
                continue;
            }

            String repoPathStr = repo.getRepoPath();
            if (repoPathStr == null || repoPathStr.trim().isEmpty()) {
                logger.log("SKIP " + pkg + ": repoPath missing in mapping.");
                hadFailures = true;
                continue;
            }
            Path mappedRepoPath;
            mappedRepoPath = Path.of(repoPathStr);
            if (!Files.exists(mappedRepoPath) || !Files.isDirectory(mappedRepoPath)) {
                logger.log("SKIP " + pkg + ": repo path missing: " + mappedRepoPath);
                hadFailures = true;
                continue;
            }
            Path repoRoot = resolveRepoRoot(mappedRepoPath);
            if (repoRoot == null || !Files.isDirectory(repoRoot)) {
                logger.log("SKIP " + pkg + ": could not resolve repo root from: " + mappedRepoPath);
                hadFailures = true;
                continue;
            }
            String reportFileName = assignmentId + pkg + ".html";
            Path reportPath = repoRoot.resolve(reportFileName);
            try {
                // IMPORTANT: build report using repoRoot so source code resolves correctly
                String html = buildReportHtml(assignment, pkg, repoRoot);
                Files.deleteIfExists(reportPath);
                Files.writeString(reportPath, html);

                wroteAny = true;
                logger.log("OK " + pkg + ": wrote report " + reportFileName);
            } catch (IOException e) {
                logger.log("FAIL " + pkg + ": could not write report: " + e.getMessage());
                hadFailures = true;
            }
        }

        logger.log("Generate Reports complete.");
        return wroteAny && !hadFailures;
    }

    private Path resolveRepoRoot(Path mappedRepoPath) {
        if (mappedRepoPath == null) {
            return null;
        }
        // 1) normal: mappedRepoPath/src exists
        Path directSrc = mappedRepoPath.resolve("src");
        if (Files.isDirectory(directSrc)) {
            return mappedRepoPath;
        }

        // 2) common nested: mappedRepoPath/<something>/src exists (one level down)
        try (var stream = Files.list(mappedRepoPath)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.resolve("src"))
                    .filter(Files::isDirectory)
                    .map(Path::getParent) // back to the folder that contains src
                    .findFirst()
                    .orElse(mappedRepoPath);
        } catch (IOException ignored) {
            return mappedRepoPath;
        }
    }

    private Map<String, RepoMapping> loadMapping(Path mappingFile) {
        Map<String, RepoMapping> mapping = null;
        ObjectMapper mapper =
                new ObjectMapper();
        mapper.configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false
        );
        try {
            byte[] bytes = Files.readAllBytes(mappingFile);
            TypeReference<Map<String, RepoMapping>> type =
                    new TypeReference<>() {
                    };

            mapping = mapper.readValue(bytes, type);
        } catch (IOException e) {
            logger.log("Failed to read mapping.json: " + e.getMessage());
        }

        return mapping;
    }

    private String buildReportHtml(Assignment assignment,
                                   String studentPackage,
                                   Path repoPath) {

        String assignmentId =
                assignment.getCourseCode() + assignment.getAssignmentCode();

        // Run once only
        CheckstyleResult cs = buildCheckstyleResult(repoPath);
        UnitTestResult ut = buildUnitTestResultMarkdown(studentPackage, repoPath);
        Map<String, Integer> manualDeductions = loadManualDeductionsFromGradingDraft(assignmentId,
                studentPackage, repoPath);
        String md =
                "# "
                        + assignment.getCourseCode()
                        + " "
                        + assignment.getAssignmentCode()
                        + " - "
                        + assignment.getAssignmentName()
                        + System.lineSeparator()
                        + System.lineSeparator()

                        + "## Rubric"
                        + System.lineSeparator()
                        + System.lineSeparator()

                        + RubricTableBuilder.buildRubricMarkdown(
                        assignment,
                        assignmentsFile,
                        cs.totalViolations,
                        (double) ut.failedTests,
                        ut.totalTests,
                        manualDeductions
                )
                        + System.lineSeparator()
                        + System.lineSeparator()

                        + "## Source Code"
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + buildSourceCodeMarkdown(
                        assignment,
                        studentPackage,
                        repoPath)
                        + System.lineSeparator()
                        + System.lineSeparator()

                        + "## Checkstyle Violations"
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + cs.markdown
                        + System.lineSeparator()
                        + System.lineSeparator()

                        + "## Failed Unit Tests"
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + ut.markdown
                        + System.lineSeparator()
                        + System.lineSeparator()

                        + "## Commit History (Last 10)"
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + buildCommitHistoryMarkdown(repoPath)
                        + System.lineSeparator();

        String title = assignmentId + studentPackage;

        return wrapMarkdownAsHtml(title, md);
    }

    private String buildSourceCodeMarkdown(Assignment assignment,
                                           String studentPackage,
                                           Path repoPath) {
        StringBuilder sb = new StringBuilder();

        List<String> expected = assignment.getExpectedFiles();
        if (expected == null || expected.isEmpty()) {
            sb.append("_No expected files configured._").append(System.lineSeparator());
        } else {
            for (String rel : expected) {
                String filename = Path.of(rel).getFileName().toString();
                Path filePath = repoPath.resolve("src").resolve(studentPackage).resolve(filename);

                sb.append("### ").append(filename).append(System.lineSeparator());

                sb.append(System.lineSeparator());

                if (!Files.exists(filePath)) {
                    sb.append("_Missing file._").append(System.lineSeparator());
                    sb.append(System.lineSeparator());
                } else {
                    String lang = languageForFile(filePath);
                    sb.append("```").append(lang).append(System.lineSeparator());

                    try {
                        String code = Files.readString(filePath);
                        sb.append(code).append(System.lineSeparator());
                    } catch (IOException e) {
                        sb.append("// Failed to read file: ").append(e.getMessage())
                                .append(System.lineSeparator());
                    }

                    sb.append("```").append(System.lineSeparator());
                    sb.append(System.lineSeparator());
                }
            }
        }

        return sb.toString();
    }

    private CheckstyleResult buildCheckstyleResult(Path repoPath) {
        CheckstyleResult checkstyleResult = null;
        if (!checkstyleCheckBox.isSelected()) {
            checkstyleResult = new CheckstyleResult(
                    "_Checkstyle disabled._", 0);
        } else if (selectedAssignmentMissingCheckstyleRubricItem()) {
            checkstyleResult = new CheckstyleResult(
                    "_No checkstyle rubric item for this assignment._", 0);
        }
        String url = checkstyleUrlField.getText();
        if (url == null || url.trim().isEmpty()) {
            checkstyleResult = new CheckstyleResult(
                    "_Checkstyle enabled but URL is blank._", 0);
        } else if (!Files.exists(checkstyleJar)) {
            checkstyleResult = new CheckstyleResult(
                    "_Missing checkstyle-13.2.0-all.jar in program folder._",
                    0);
        }
        if(checkstyleResult != null) {
            return checkstyleResult;
        }
        try {
            Path configFile = downloadCheckstyleConfig(url.trim());
            List<Path> javaFiles = findJavaFiles(repoPath.resolve("src"));
            if (javaFiles.isEmpty()) {
                checkstyleResult = new CheckstyleResult("_No Java files found under src/._", 0);
            } else {
                List<String> args = new ArrayList<>();
                args.add("java");
                args.add("-jar");
                args.add(checkstyleJar.toAbsolutePath().toString());
                args.add("-c");
                args.add(configFile.toAbsolutePath().toString());
                for (Path f : javaFiles) {
                    args.add(f.toAbsolutePath().toString());
                }
                ProcessResult result = runProcessCaptureLinesWithExitCode(args, repoPath);
                if (result.exitCode() < 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("### Checkstyle Execution Failed").append(System.lineSeparator())
                            .append(System.lineSeparator())
                            .append("```").append(System.lineSeparator());
                    for (String line : result.outputLines()) {
                        sb.append(line).append(System.lineSeparator());
                    }
                    sb.append("```").append(System.lineSeparator());
                    return new CheckstyleResult(sb.toString(), 0);
                }
                CheckstyleSummary summary = parseCheckstyleOutput(result.outputLines());
                int violations = summary.getTotalViolations();
                if (violations == 0) {
                    checkstyleResult = new CheckstyleResult("_No checkstyle violations._", 0);
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("**Total Violations:** ")
                            .append(violations)
                            .append(System.lineSeparator())
                            .append(System.lineSeparator());
                    for (String file : summary.getFilesInOrder()) {
                        List<String> lines = summary.getLinesForFile(file);
                        sb.append("### ").append(file).append(System.lineSeparator())
                                .append(System.lineSeparator())
                                .append("```").append(System.lineSeparator());
                        for (String line : lines) {
                            sb.append(line).append(System.lineSeparator());
                        }
                        sb.append("```").append(System.lineSeparator())
                                .append(System.lineSeparator());
                    }
                    checkstyleResult = new CheckstyleResult(sb.toString(), violations);
                }
            }
        } catch(IOException e){
            return new CheckstyleResult("_Checkstyle failed: " + e.getMessage() + "_", 0);
        }
        return checkstyleResult;
    }

    private CheckstyleSummary parseCheckstyleOutput(List<String> lines) {
        CheckstyleSummary summary = new CheckstyleSummary();
        if (lines == null) {
            return summary;
        }

        for (String line : lines) {
            if (line == null) {
                continue;
            }

            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }

            if (t.startsWith("[ERROR]")) {
                t = t.substring("[ERROR]".length()).trim();
            }

            // Normalize slashes
            t = t.replace("\\", "/");

            // Strip everything before "/src/"
            int srcIdx = t.indexOf("/src/");
            if (srcIdx >= 0) {
                t = t.substring(srcIdx + "/src/".length());
            }

            int javaIndex = t.toLowerCase().indexOf(".java:");
            if (javaIndex < 0) {
                continue;
            }

            int endOfFile = javaIndex + ".java".length();
            String fileKey = t.substring(0, endOfFile);

            summary.add(fileKey, t);
        }

        return summary;
    }

    /**
     * Returns {@code true} if the currently selected assignment does NOT contain any rubric item
     * that is marked as a checkstyle item in the rubric item library.
     *
     * @return {@code true} if no checkstyle rubric item exists for the selected assignment,
     *         otherwise {@code false}
     */
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

    private static class CheckstyleSummary {
        private final Map<String, List<String>> byFile = new TreeMap<>();
        private int totalViolations = 0;

        public void add(String file, String line) {
            if (!byFile.containsKey(file)) {
                byFile.put(file, new ArrayList<>());
            }
            byFile.get(file).add(line);
            totalViolations++;
        }

        public int getTotalViolations() {
            return totalViolations;
        }

        public List<String> getFilesInOrder() {
            return new ArrayList<>(byFile.keySet());
        }

        public List<String> getLinesForFile(String file) {
            if (!byFile.containsKey(file)) {
                return List.of();
            }
            return byFile.get(file);
        }
    }

    private Path downloadCheckstyleConfig(String url) throws IOException {
        Path packagesRoot = selectedRootPath.resolve("packages");
        if (!Files.exists(packagesRoot)) {
            Files.createDirectories(packagesRoot);
        }
        Path configFile = packagesRoot.resolve("checkstyle.xml");
        Path urlFile = packagesRoot.resolve("checkstyle-url.txt");
        String previousUrl = "";
        if (Files.exists(urlFile)) {
            try {
                previousUrl = Files.readString(urlFile).trim();
            } catch (IOException e) {
                previousUrl = "";
            }
        }
        boolean urlChanged = !url.equals(previousUrl);
        if (Files.exists(configFile) && !urlChanged) {
            return configFile;
        }
        final int connectTimeout = 10;
        final int requestTimeout = 20;
        final int successCode = 200;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(requestTimeout))
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != successCode) {
                    throw new IOException("HTTP " + response.statusCode() + " downloading config.");
                }
                try (InputStream in = response.body()) {
                    Files.copy(in, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(urlFile, url + System.lineSeparator());
                logger.log("Downloaded checkstyle config: " + configFile);
                return configFile;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted.");
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid URL: " + url);
            }
        }
    }

    private UnitTestResult buildUnitTestResultMarkdown(String studentPackage, Path repoPath) {
        Path srcDir = repoPath.resolve("src");
        Path testDir = repoPath.resolve("test");
        Path actualTestDir = null;
        Path srcTestDir = srcDir.resolve("test");
        if (Files.exists(srcTestDir) && Files.isDirectory(srcTestDir)) {
            actualTestDir = srcTestDir;
        } else if (Files.exists(testDir) && Files.isDirectory(testDir)) {
            actualTestDir = testDir;
        }
        if (actualTestDir == null) {
            return new UnitTestResult("_No test directory found (expected src/test/)._", 0, 0);
        }
        if (!Files.exists(srcDir) || !Files.isDirectory(srcDir)) {
            return new UnitTestResult("_No src/ directory found._", 0, 0);
        }
        Path junitJar = getBundledJUnitConsoleJar();
        if (junitJar == null) {
            return new UnitTestResult("_Missing bundled JUnit jar: " +
                    "lib/junit-platform-console-standalone.jar_", 0, 0);
        }
        try {
            // ---- Compile SRC ----
            Path buildDir = repoPath.resolve("build");
            Path classesDir = buildDir.resolve("classes");
            if (Files.exists(classesDir)) {
                deleteDirectoryRecursively(classesDir);
            }
            ensureDirectoryExists(buildDir);
            ensureDirectoryExists(classesDir);
            List<Path> srcFiles = findJavaFiles(srcDir).stream()
                    .filter(p -> !p.startsWith(srcDir.resolve("test"))).toList();
            if (srcFiles.isEmpty()) {
                return new UnitTestResult("_No Java files found under src/._", 0, 0);
            }
            String cp = buildLibClasspath();
            List<String> javacSrc = new ArrayList<>();
            javacSrc.add("javac");
            javacSrc.add("-d");
            javacSrc.add(classesDir.toAbsolutePath().toString());
            javacSrc.add("-cp");
            javacSrc.add(cp);
            for (Path f : srcFiles) {
                javacSrc.add(f.toAbsolutePath().toString());
            }
            Path javafxLib = getBundledJavaFxLibDir();
            if (javafxLib != null) {
                javacSrc.add("--module-path");
                javacSrc.add(javafxLib.toAbsolutePath().toString());
                javacSrc.add("--add-modules");
                javacSrc.add("javafx.controls,javafx.fxml");
            }
            ProcessResult srcCompile = runProcessCaptureLinesWithExitCode(javacSrc, repoPath);
            if (srcCompile.exitCode() != 0) {
                return new UnitTestResult(formatCompileFailure("Source Compilation Failed",
                        srcCompile), 0, 0);
            }
            // ---- Compile TESTS ----
            Path testSuiteOriginal = actualTestDir.resolve("TestSuite.java");
            if (!Files.exists(testSuiteOriginal)) {
                return new UnitTestResult("_Missing TestSuite.java under " +
                        actualTestDir + "._", 0, 0);
            }
            // Create patched copy
            Path patchedTestSuite = preparePatchedTestSuite(
                    testSuiteOriginal,
                    buildDir,
                    studentPackage
            );
            // Compile ONLY the patched version
            String testCp = classesDir.toAbsolutePath()
                    + File.pathSeparator
                    + buildLibClasspath();
            List<String> javacTests = new ArrayList<>();
            javacTests.add("javac");
            javacTests.add("-d");
            javacTests.add(classesDir.toAbsolutePath().toString());
            javacTests.add("-cp");
            javacTests.add(testCp);
            javacTests.add(patchedTestSuite.toAbsolutePath().toString());
            if (javafxLib != null) {
                javacTests.add("--module-path");
                javacTests.add(javafxLib.toAbsolutePath().toString());
                javacTests.add("--add-modules");
                javacTests.add("javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing");
            }
            ProcessResult testCompile =
                    runProcessCaptureLinesWithExitCode(javacTests, repoPath);
            if (testCompile.exitCode() != 0) {
                return new UnitTestResult(formatCompileFailure("Teste Compilation Failed",
                        testCompile), 0, 0);
            }
            // ---- Run Tests (XML reporting mode) ----
            Path reportsDir = repoPath.resolve("build").resolve("test-reports");
            if (Files.exists(reportsDir)) {
                deleteDirectoryRecursively(reportsDir);
            }
            ensureDirectoryExists(reportsDir);
            List<String> runArgs = new ArrayList<>();
            runArgs.add("java");
            if (javafxLib != null) {
                runArgs.add("-Djava.awt.headless=true");
                runArgs.add("-Dprism.order=sw");
                runArgs.add("-Dprism.text=t2k");
                runArgs.add("-Djavafx.platform=Monocle");
                runArgs.add("-Dmonocle.platform=Headless");
                runArgs.add("-Djava.library.path=" + getBundledJavaFxLibDir());
                runArgs.add("--add-opens javafx.graphics/com.sun.javafx.util=ALL-UNNAMED");
                runArgs.add("--add-opens javafx.graphics/com.sun.javafx.application=ALL-UNNAMED");
                runArgs.add("--add-opens javafx.base/com.sun.javafx.logging=ALL-UNNAMED");
                runArgs.add("--add-opens javafx.graphics/com.sun.javafx.embed=ALL-UNNAMED");
                runArgs.add("--add-opens=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED");
                runArgs.add("--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED");
                runArgs.add("--add-exports javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED");
                runArgs.add("--add-exports javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED");
                runArgs.add("--add-exports javafx.graphics/com.sun.javafx.scene.layout=ALL-UNNAMED");
                runArgs.add("--add-exports javafx.base/com.sun.javafx.logging=ALL-UNNAMED");
                runArgs.add("--add-exports javafx.base/com.sun.javafx.event=ALL-UNNAMED");
                runArgs.add("--add-exports javafx.graphics/com.sun.javafx.embed=ALL-UNNAMED");
                runArgs.add("--module-path");
                runArgs.add(javafxLib.toAbsolutePath().toString());

                runArgs.add("--add-modules");
                runArgs.add("javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing");
            }
            runArgs.add("-jar");
            runArgs.add(junitJar.toAbsolutePath().toString());
            runArgs.add("execute");
            runArgs.add("--class-path");
            runArgs.add(classesDir.toAbsolutePath()
                    + File.pathSeparator
                    + buildLibClasspath());
            runArgs.add("--scan-class-path");
            runArgs.add("--reports-dir");
            runArgs.add(reportsDir.toAbsolutePath().toString());
            runArgs.add("--disable-ansi-colors");
            runArgs.add("--details=none");  // suppress console tree
            runProcessCaptureLinesWithExitCode(runArgs, repoPath);
            // Now parse XML instead of console output
            JUnitFailureSummary summary = parseJUnitReports(reportsDir);
            if (summary.getFailureCount() == 0) {
                return new UnitTestResult("_No failed unit tests._",
                        summary.getTotalTests(),
                        0);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("### Unit Test Results")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
            sb.append("**Failed Tests: ")
                    .append(summary.getFailureCount())
                    .append("**")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
            for (String line : summary.getFailureLines()) {
                sb.append("- ").append(line).append(System.lineSeparator());
            }
            return new UnitTestResult(sb.toString(),
                    summary.getTotalTests(),
                    summary.getFailureCount());
        } catch (IOException e) {
            return new UnitTestResult("_Unit tests failed: " + e.getMessage() + "_", 0, 0);
        }
    }

    private Path preparePatchedTestSuite(Path testSuiteFile, Path buildDir, String studentPackage)
            throws IOException {

        Path patchedDir = buildDir.resolve("patched-tests");
        ensureDirectoryExists(patchedDir);

        Path patched = patchedDir.resolve("TestSuite.java");

        String src = Files.readString(testSuiteFile);

        // Replace ANY import username.*
        src = src.replaceAll(
                "(?m)^\\s*import\\s+username\\.",
                "import " + studentPackage + "."
        );

        // Replace any fully-qualified username.* reference
        src = src.replaceAll(
                "\\busername\\.",
                studentPackage + "."
        );
        Files.writeString(patched, src);
        return patched;
    }

    private String formatCompileFailure(String title, ProcessResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(title).append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("```").append(System.lineSeparator());
        for (String line : result.outputLines()) {
            sb.append(line).append(System.lineSeparator());
        }
        sb.append("```").append(System.lineSeparator());
        return sb.toString();
    }

    private String escapeForMarkdownHtml(String s) {
        if (s == null) {
            return "";
        }
        // Normalize newlines/spaces
        s = s.replace("\r\n", "\n").replace("\r", "\n");
        s = s.replace("\n", " ");          // collapse multiline assertion output
        s = s.replaceAll("\\s+", " ").trim();

        // Escape HTML special chars (critical for < >)
        s = s.replace("&", "&amp;");
        s = s.replace("<", "&lt;");
        s = s.replace(">", "&gt;");

        return s;
    }

    private record ProcessResult(int exitCode, List<String> outputLines) {
    }

    private ProcessResult runProcessCaptureLinesWithExitCode(List<String> args, Path workingDir) {
        List<String> lines = new ArrayList<>();
        int exitCode = -1;

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();

            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                while (line != null) {
                    lines.add(line);
                    line = reader.readLine();
                }
            }

            exitCode = p.waitFor();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lines.add("Process interrupted.");
        } catch (IOException e) {
            lines.add("Process failed: " + e.getMessage());
        }

        return new ProcessResult(exitCode, lines);
    }

    private Path getBundledJUnitConsoleJar() {
        Path jar = Path.of("lib").resolve("junit-platform-console-standalone-6.0.1.jar");
        if (Files.exists(jar) && Files.isRegularFile(jar)) {
            return jar;
        }
        return null;
    }

    private Path getBundledJavaFxLibDir() {
        Path dir = Path.of("lib", "javafx-sdk", "lib");
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            return dir;
        }
        return null;
    }
    private String buildLibClasspath() {
        Path libDir = Path.of("lib");
        if (!Files.isDirectory(libDir)) {
            return "";
        }

        try (Stream<Path> stream = Files.list(libDir)) {
            return stream
                    .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                    .map(p -> p.toAbsolutePath().toString())
                    .sorted()
                    .reduce((a, b) -> a + File.pathSeparator + b)
                    .orElse("");
        } catch (IOException e) {
            return "";
        }
    }

    private JUnitFailureSummary parseJUnitReports(Path reportsDir) {
        JUnitFailureSummary summary = new JUnitFailureSummary();
        if (!Files.exists(reportsDir)) {
            return summary;
        }
        try (Stream<Path> paths = Files.walk(reportsDir)) {
            List<Path> xmlFiles = paths
                    .filter(p -> p.toString().toLowerCase().endsWith(".xml"))
                    .toList();
            for (Path xml : xmlFiles) {
                parseSingleJUnitXml(xml, summary);
            }
        } catch (IOException ignored) {
            logger.log("Failed to parse reports directory: " + reportsDir);
        }
        return summary;
    }

    private void parseSingleJUnitXml(Path xmlFile, JUnitFailureSummary summary) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(xmlFile.toFile());
            NodeList testcases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testcases.getLength(); i++) {
                summary.incrementTotalTests();
                org.w3c.dom.Node testCase = testcases.item(i);
                NodeList children = testCase.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    org.w3c.dom.Node node = children.item(j);
                    if ("failure".equals(node.getNodeName())
                            || "error".equals(node.getNodeName())) {
                        NamedNodeMap attrs = testCase.getAttributes();
                        String className = attrs.getNamedItem("classname") != null
                                ? attrs.getNamedItem("classname").getNodeValue()
                                : "";
                        String methodName = attrs.getNamedItem("name") != null
                                ? attrs.getNamedItem("name").getNodeValue()
                                : "";
                        String message = getString(node);
                        summary.addFailure(
                                simplifyClassName(className) + "." + methodName,
                                message == null ? "" : message.trim()
                        );
                    }
                }
            }
        } catch (ParserConfigurationException | IOException | SAXException e) {
            logger.log("Failed to parse xml file: " + xmlFile);
            logger.log(e.getMessage());
        }
    }

    private static @Nullable String getString(org.w3c.dom.Node node) {
        String message = "";

        var failureAttrs = node.getAttributes();
        if (failureAttrs != null
                && failureAttrs.getNamedItem("message") != null) {
            message = failureAttrs.getNamedItem("message").getNodeValue();
        }

        if (message == null || message.isBlank()) {
            message = node.getTextContent();
//            if (message != null) {
//                message = message.split("\\R", 2)[0]; // first line only
//            }
        }
        return message;
    }

    private String simplifyClassName(String className) {
        if (className == null || className.isBlank()) {
            return "";
        }
        // Strip package
        int lastDot = className.lastIndexOf('.');
        String s = lastDot >= 0 ? className.substring(lastDot + 1) : className;
        // If it's TestSuite$Something$Something, keep only the last segment
        int lastDollar = s.lastIndexOf('$');
        if (lastDollar >= 0 && lastDollar < s.length() - 1) {
            s = s.substring(lastDollar + 1);
        }
        return s;
    }

    private class JUnitFailureSummary {

        private final List<String> failureLines = new ArrayList<>();
        private int totalTests = 0;

        public void incrementTotalTests() {
            totalTests++;
        }

        public void addFailure(String testName, String message) {
            testName = escapeForMarkdownHtml(testName);
            message = escapeForMarkdownHtml(message);

            if (message.isBlank()) {
                failureLines.add("**" + testName + "**");
            } else {
                failureLines.add("**" + testName + "** — " + message);
            }
        }

        public int getFailureCount() {
            return failureLines.size();
        }

        public int getTotalTests() {
            return totalTests;
        }

        public List<String> getFailureLines() {
            return failureLines;
        }
    }

    private List<Path> findJavaFiles(Path srcRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.exists(srcRoot) || !Files.isDirectory(srcRoot)) {
            return files;
        }
        try (Stream<Path> stream = Files.walk(srcRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".java"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private String buildCommitHistoryMarkdown(Path repoPath) {
        StringBuilder sb = new StringBuilder();

        List<String> args = List.of(
                "git",
                "log",
                "-n",
                "10",
                "--pretty=format:%h %ad %an - %s",
                "--date=short"
        );

        ProcessResult result = runProcessCaptureLinesWithExitCode(args, repoPath);

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

    private String languageForFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        String lang = "";
        if (name.endsWith(".java")) {
            lang = "java";
        } else if (name.endsWith(".fxml")) {
            lang = "xml";
        }
        return lang;
    }

    private String wrapMarkdownAsHtml(String title, String markdown) {
        String safeTitle = "";
        if (title != null) {
            safeTitle = title.trim();
        }
        String md = "";
        if (markdown != null) {
            md = markdown;
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>" +
                "<title>" + escapeHtml(safeTitle) + "</title></head><body><xmp>\n" +
                md +
                "\n</xmp><script type=\"text/javascript\" " +
                "src=\"https://csse.msoe.us/gradedown.js\"></script></body></html>\n";
    }

    private String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
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
            Thread worker = new Thread(() -> runAllWorker(cloneCmd, assignment, root),
                    "runall-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void runAllWorker(String cloneCmd, Assignment assignment, Path root) {
        logger.log("=== RUN ALL START ===");
        boolean pullOk = runPullStep(cloneCmd);
        if (!pullOk) {
            logger.log("Run All stopped: Pull step failed.");
        } else {
            boolean extractOk = runExtractStep(root, assignment);
            if (!extractOk) {
                logger.log("Run All stopped: Extract step failed.");
            } else {
                boolean importsOk = runGenerateImportsStep(root, assignment);
                if (!importsOk) {
                    logger.log("Run All stopped: Generate Imports step failed.");
                } else {
                    boolean reportsOk = runGenerateReportsStep(root, assignment);
                    if (!reportsOk) {
                        logger.log("Run All stopped: Generate Reports step failed.");
                    } else {
                        logger.log("Run All completed successfully.");
                    }
                }
            }
        }
        logger.log("=== RUN ALL END ===");
        Platform.runLater(this::updateUiState);
    }

    private boolean runPullStep(String cloneCmd) {
        boolean ok = false;
        if (cloneCmd == null || cloneCmd.trim().isEmpty()) {
            logger.log("Pull step failed: GitHub Classroom command is empty.");
        } else if (selectedRootPath == null) {
            logger.log("Pull step failed: Repository root is not set.");
        } else {
            logger.log("=== STEP: PULL ===");
            logger.log("Working directory: " + selectedRootPath);

            List<String> args = tokenizeCommand(cloneCmd.trim());
            if (args.isEmpty()) {
                logger.log("Pull step failed: Could not parse command.");
            } else {
                int exit = runProcessAndLog(args, selectedRootPath);
                if (exit == 0) {
                    logger.log("Pull step complete.");
                    ok = true;
                } else {
                    logger.log("Pull step failed: exit code " + exit);
                }
            }
        }

        return ok;
    }

    private boolean runExtractStep(Path root, Assignment assignment) {
        boolean ok = false;
        if (root == null) {
            logger.log("Extract step failed: Repository root not set.");
        } else if (assignment == null) {
            logger.log("Extract step failed: No assignment selected.");
        } else {
            logger.log("=== STEP: EXTRACT PACKAGES ===");
            try {
                extractPackagesWorker();
                ok = true;
                logger.log("Extract step complete.");
            } catch (IOException e) {
                logger.log("Extract step failed: " + e.getMessage());
            }
        }

        return ok;
    }

    private boolean runGenerateImportsStep(Path root, Assignment assignment) {
        boolean ok = false;

        if (root == null) {
            logger.log("Generate Imports step failed: Repository root not set.");
        } else if (assignment == null) {
            logger.log("Generate Imports step failed: No assignment selected.");
        } else {
            logger.log("=== STEP: GENERATE IMPORTS ===");
            try {
                generateImportsWorker();
                ok = true;
                logger.log("Generate Imports step complete.");
            } catch (IOException e) {
                logger.log("Generate Imports step failed: " + e.getMessage());
            }
        }

        return ok;
    }

    private boolean runGenerateReportsStep(Path root, Assignment assignment) {
        boolean ok = false;
        if (root == null) {
            logger.log("Generate Reports step failed: Repository root not set.");
        } else if (assignment == null) {
            logger.log("Generate Reports step failed: No assignment selected.");
        } else {
            logger.log("=== STEP: GENERATE REPORTS ===");
            ok = generateReportsWorker(assignment);
            logger.log(ok ? "Generate Reports step complete." : "Generate Reports step failed.");
        }
        return ok;
    }

    private int runProcessAndLog(List<String> args, Path workingDir) {
        int exitCode = -1;

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();

            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                while (line != null) {
                    logger.log(line);
                    line = reader.readLine();
                }
            }

            exitCode = p.waitFor();
        } catch (IOException e) {
            logger.log("Process failed (IO): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log("Process interrupted.");
        }

        return exitCode;
    }

    @FXML
    private void onValidateGh() {
    }

    @FXML
    private void onAbout() {
    }

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

    private Map<String, Integer> loadManualDeductionsFromGradingDraft(String assignmentId,
                                                                      String studentPackage,
                                                                      Path rootPath) {
        Path draft = rootPath.resolve("grading").resolve(assignmentId + studentPackage + ".md");
        if (!Files.exists(draft) || !Files.isRegularFile(draft)) {
            return Map.of();
        }
        try {
            String md = Files.readString(draft, StandardCharsets.UTF_8);
            List<Comments.ParsedComment> parsed = Comments.parseInjectedComments(md);
            Map<String, Integer> lostByRubric = new HashMap<>();
            for (Comments.ParsedComment c : parsed) {
                if (c != null) {
                    String id = c.rubricItemId();
                    if (id != null && !id.isBlank()) {
                        int lost = Math.max(0, c.pointsLost());
                        lostByRubric.put(id, lostByRubric.getOrDefault(id, 0) + lost);
                    }
                }
            }
            return lostByRubric;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private record CheckstyleResult(String markdown, int totalViolations) {
    }

    private record UnitTestResult(String markdown, int totalTests, int failedTests) {
    }
}

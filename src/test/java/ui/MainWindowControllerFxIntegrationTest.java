package ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import model.Assignment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class MainWindowControllerFxIntegrationTest {

    private static final int SHORT_WAIT_MILLIS = 200;
    private static final int LONG_WAIT_MILLIS = 5000;
    private static volatile boolean javaFxAvailable;

    private String originalUserHome;

    @BeforeAll
    static void initJavaFxToolkit() {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
            javaFxAvailable = latch.await(5, TimeUnit.SECONDS);
        } catch (IllegalStateException e) {
            javaFxAvailable = true;
        } catch (Throwable t) {
            javaFxAvailable = false;
        }
    }

    @BeforeEach
    void requireJavaFx() {
        Assumptions.assumeTrue(javaFxAvailable, "JavaFX runtime unavailable in this environment");
        originalUserHome = System.getProperty("user.home");
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void onRunAll_logsAbortWhenRootPathMissing(@TempDir Path tmp) throws Exception {
        FxFixture fixture = loadFixture(tmp);

        runOnFxAndWait(() -> {
            fixture.cloneCommandField.setText("gh classroom clone student-repos -a 123");
            invokePrivateNoArg(fixture.controller, "onRunAll");
        });

        waitForLogContaining(fixture.logTextArea, "Run All aborted: Repository root is not set.");
    }

    @Test
    void onPull_logsAbortWhenCommandBlank(@TempDir Path tmp) throws Exception {
        FxFixture fixture = loadFixture(tmp);

        runOnFxAndWait(() -> {
            fixture.cloneCommandField.setText("  ");
            invokePrivateNoArg(fixture.controller, "onPull");
        });

        waitForLogContaining(fixture.logTextArea,
                "Pull aborted: GitHub Classroom command is empty.");
    }

    @Test
    void onPull_runsCommandAndLogsCompletion(@TempDir Path tmp) throws Exception {
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            fixture.cloneCommandField.setText("echo hello");
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            invokePrivateNoArg(fixture.controller, "onPull");
        });

        waitForLogContaining(fixture.logTextArea, "Pull completed successfully.");
    }

    @Test
    void initialize_invalidSettingsFileLogsFallbackAndClearsUrl(@TempDir Path tmp)
            throws Exception {
        Path settingsPath = tmp.resolve(".gh-classroom-utils")
                .resolve("settings")
                .resolve("settings.json");
        Files.createDirectories(settingsPath.getParent());
        Files.writeString(settingsPath, "{invalid");

        FxFixture fixture = loadFixture(tmp);

        waitForLogContaining(fixture.logTextArea,
                "Failed to load settings: Invalid settings JSON format.");

        AtomicReference<String> textRef = new AtomicReference<>("");
        runOnFxAndWait(() -> textRef.set(fixture.checkstyleUrlField.getText()));
        assertEquals("", textRef.get());
    }

    @Test
    void initialize_invalidCommentsFileLogsFailure(@TempDir Path tmp)
            throws Exception {
        Path commentsPath = tmp.resolve(".gh-classroom-utils")
                .resolve("comments")
                .resolve("comments.json");
        Files.createDirectories(commentsPath.getParent());
        Files.writeString(commentsPath, "{invalid");

        FxFixture fixture = loadFixture(tmp);

        waitForLogContaining(fixture.logTextArea, "Failed to load comments.json:");
    }

    @Test
    void initialize_invalidAssignmentsFileLogsFailureAndLeavesComboEmpty(@TempDir Path tmp)
            throws Exception {
        Path assignmentsPath = tmp.resolve(".gh-classroom-utils")
                .resolve("assignments")
                .resolve("assignments.json");
        Files.createDirectories(assignmentsPath.getParent());
        Files.writeString(assignmentsPath, "{invalid");

        FxFixture fixture = loadFixture(tmp);

        waitForLogContaining(fixture.logTextArea,
                "Failed to load/create assignments: Invalid assignments JSON format.");

        AtomicReference<Integer> sizeRef = new AtomicReference<>(-1);
        runOnFxAndWait(() -> sizeRef.set(fixture.assignmentCombo.getItems().size()));
        assertEquals(0, sizeRef.get());
    }

    @Test
    void initialize_validSettingsAndAssignments_populatesUrlAndSelectsFirstAssignment(
            @TempDir Path tmp
    ) throws Exception {
        writeValidSettings(tmp, "https://example.com/custom-checkstyle.xml");
        writeValidAssignments(tmp);

        FxFixture fixture = loadFixture(tmp);

        AtomicReference<String> urlRef = new AtomicReference<>("");
        AtomicReference<Integer> sizeRef = new AtomicReference<>(-1);
        AtomicReference<String> selectedRef = new AtomicReference<>("");
        runOnFxAndWait(() -> {
            urlRef.set(fixture.checkstyleUrlField.getText());
            sizeRef.set(fixture.assignmentCombo.getItems().size());
            Assignment selected = fixture.assignmentCombo.getValue();
            selectedRef.set(selected == null ? "" : selected.getKey());
        });

        assertEquals("https://example.com/custom-checkstyle.xml", urlRef.get());
        assertEquals(1, sizeRef.get());
        assertEquals("CSC-100-A1", selectedRef.get());
    }

    @Test
    void checkstyleCheckboxToggle_updatesFieldDisableAndMenuEnablement(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            fixture.cloneCommandField.setText("gh classroom clone student-repos -a 123");
            fixture.rootPathField.setText(repoRoot.toString());
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.checkstyleUrlField.setText("");
            fixture.checkstyleCheckBox.setSelected(true);
            invokePrivateNoArg(fixture.controller, "updateUiState");
        });

        AtomicReference<Boolean> fieldDisabledWhenChecked = new AtomicReference<>();
        AtomicReference<Boolean> reportsDisabledWhenChecked = new AtomicReference<>();
        AtomicReference<Boolean> runAllDisabledWhenChecked = new AtomicReference<>();
        runOnFxAndWait(() -> {
            fieldDisabledWhenChecked.set(fixture.checkstyleUrlField.isDisable());
            reportsDisabledWhenChecked.set(fixture.reportsMenuItem.isDisable());
            runAllDisabledWhenChecked.set(fixture.runAllMenuItem.isDisable());
        });

        assertFalse(fieldDisabledWhenChecked.get());
        assertTrue(reportsDisabledWhenChecked.get());
        assertTrue(runAllDisabledWhenChecked.get());

        runOnFxAndWait(() -> fixture.checkstyleCheckBox.setSelected(false));

        AtomicReference<Boolean> fieldDisabledWhenUnchecked = new AtomicReference<>();
        AtomicReference<Boolean> reportsDisabledWhenUnchecked = new AtomicReference<>();
        AtomicReference<Boolean> runAllDisabledWhenUnchecked = new AtomicReference<>();
        runOnFxAndWait(() -> {
            fieldDisabledWhenUnchecked.set(fixture.checkstyleUrlField.isDisable());
            reportsDisabledWhenUnchecked.set(fixture.reportsMenuItem.isDisable());
            runAllDisabledWhenUnchecked.set(fixture.runAllMenuItem.isDisable());
        });

        assertTrue(fieldDisabledWhenUnchecked.get());
        assertFalse(reportsDisabledWhenUnchecked.get());
        assertFalse(runAllDisabledWhenUnchecked.get());
    }

    @Test
    void persistCheckstyleUrl_trimsAndWritesSettingsFile(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);

        runOnFxAndWait(() -> invokePrivate(
                fixture.controller,
                "persistCheckstyleUrl",
                new Class<?>[] {String.class},
                "  https://example.com/trimmed.xml  "
        ));

        Path settingsPath = tmp.resolve(".gh-classroom-utils")
                .resolve("settings")
                .resolve("settings.json");
        String json = Files.readString(settingsPath);
        assertTrue(json.contains("\"checkstyleConfigUrl\" : "
                + "\"https://example.com/trimmed.xml\""));
    }

    @Test
    void initialize_missingAssignmentsFile_createsBundledDefault(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "https://example.com/custom-checkstyle.xml");
        FxFixture fixture = loadFixture(tmp);

        Path assignmentsPath = tmp.resolve(".gh-classroom-utils")
                .resolve("assignments")
                .resolve("assignments.json");

        waitForLogContaining(fixture.logTextArea, "Created assignments from bundled default:");
        assertTrue(Files.exists(assignmentsPath));

        AtomicReference<Integer> sizeRef = new AtomicReference<>(-1);
        runOnFxAndWait(() -> sizeRef.set(fixture.assignmentCombo.getItems().size()));
        assertTrue(sizeRef.get() >= 1);
    }

    @Test
    void initialize_cloneCommandListener_updatesPullAndRunAllState(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            fixture.checkstyleCheckBox.setSelected(false);
            fixture.cloneCommandField.setText("");
            invokePrivateNoArg(fixture.controller, "updateUiState");
        });

        AtomicReference<Boolean> pullDisabledBefore = new AtomicReference<>();
        AtomicReference<Boolean> runAllDisabledBefore = new AtomicReference<>();
        runOnFxAndWait(() -> {
            pullDisabledBefore.set(fixture.pullMenuItem.isDisable());
            runAllDisabledBefore.set(fixture.runAllMenuItem.isDisable());
        });
        assertTrue(pullDisabledBefore.get());
        assertTrue(runAllDisabledBefore.get());

        runOnFxAndWait(() -> fixture.cloneCommandField.setText("gh classroom clone x -a 1"));

        AtomicReference<Boolean> pullDisabledAfter = new AtomicReference<>();
        AtomicReference<Boolean> runAllDisabledAfter = new AtomicReference<>();
        runOnFxAndWait(() -> {
            pullDisabledAfter.set(fixture.pullMenuItem.isDisable());
            runAllDisabledAfter.set(fixture.runAllMenuItem.isDisable());
        });
        assertFalse(pullDisabledAfter.get());
        assertFalse(runAllDisabledAfter.get());
    }

    @Test
    void initialize_assignmentSelectionListener_updatesSummaryAndMenuState(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignmentsWithTwo(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            fixture.checkstyleCheckBox.setSelected(false);
            fixture.cloneCommandField.setText("gh classroom clone x -a 1");
            invokePrivateNoArg(fixture.controller, "updateUiState");
        });

        AtomicReference<String> initialTitle = new AtomicReference<>("");
        AtomicReference<Boolean> initialRunAllDisabled = new AtomicReference<>();
        runOnFxAndWait(() -> {
            initialTitle.set(fixture.assignmentTitleLabel.getText());
            initialRunAllDisabled.set(fixture.runAllMenuItem.isDisable());
        });
        assertTrue(initialTitle.get().contains("Assignment One"));
        assertFalse(initialRunAllDisabled.get());

        runOnFxAndWait(() -> fixture.assignmentCombo.getSelectionModel().select(1));

        AtomicReference<String> switchedTitle = new AtomicReference<>("");
        AtomicReference<Boolean> switchedRunAllDisabled = new AtomicReference<>();
        runOnFxAndWait(() -> {
            switchedTitle.set(fixture.assignmentTitleLabel.getText());
            switchedRunAllDisabled.set(fixture.runAllMenuItem.isDisable());
        });
        assertTrue(switchedTitle.get().contains("Assignment Two"));
        assertTrue(switchedRunAllDisabled.get());
    }

    @Test
    void initialize_rootPathStateChange_updatesExtractAndImportsMenus(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);

        runOnFxAndWait(() -> {
            fixture.checkstyleCheckBox.setSelected(false);
            fixture.cloneCommandField.setText("gh classroom clone x -a 1");
            invokePrivateNoArg(fixture.controller, "updateUiState");
        });

        AtomicReference<Boolean> extractBefore = new AtomicReference<>();
        AtomicReference<Boolean> importsBefore = new AtomicReference<>();
        runOnFxAndWait(() -> {
            extractBefore.set(fixture.extractMenuItem.isDisable());
            importsBefore.set(fixture.importsMenuItem.isDisable());
        });
        assertTrue(extractBefore.get());
        assertTrue(importsBefore.get());

        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);
        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
        });

        AtomicReference<Boolean> extractAfter = new AtomicReference<>();
        AtomicReference<Boolean> importsAfter = new AtomicReference<>();
        runOnFxAndWait(() -> {
            extractAfter.set(fixture.extractMenuItem.isDisable());
            importsAfter.set(fixture.importsMenuItem.isDisable());
        });
        assertFalse(extractAfter.get());
        assertFalse(importsAfter.get());
    }

    @Test
    void initialize_populateAssignmentCombo_runLater_effect_isApplied(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);

        waitUntilTrue(() -> fixture.assignmentCombo.getItems().size() == 1);
        waitUntilTrue(() -> fixture.assignmentCombo.getValue() != null);
    }

    @Test
    void onPull_afterWorkerCompletes_restoresMenuState(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            fixture.checkstyleCheckBox.setSelected(false);
            fixture.cloneCommandField.setText("sleep 1");
            invokePrivateNoArg(fixture.controller, "updateUiState");
            invokePrivateNoArg(fixture.controller, "onPull");
        });

        AtomicReference<Boolean> pullDisabledImmediate = new AtomicReference<>();
        AtomicReference<Boolean> runAllDisabledImmediate = new AtomicReference<>();
        runOnFxAndWait(() -> {
            pullDisabledImmediate.set(fixture.pullMenuItem.isDisable());
            runAllDisabledImmediate.set(fixture.runAllMenuItem.isDisable());
        });
        assertTrue(pullDisabledImmediate.get());
        assertTrue(runAllDisabledImmediate.get());

        waitForLogContaining(fixture.logTextArea, "Pull completed successfully.");
        waitUntilTrue(() -> !fixture.pullMenuItem.isDisable() && !fixture.runAllMenuItem.isDisable());
        assertMenusMatchExpected(fixture, "sleep 1", repoRoot, fixture.assignmentCombo.getValue(),
                false, "", true);
    }

    @Test
    void onExtract_afterWorkerCompletes_restoresMenuState(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            fixture.checkstyleCheckBox.setSelected(false);
            fixture.cloneCommandField.setText("echo ok");
            invokePrivateNoArg(fixture.controller, "updateUiState");
            invokePrivateNoArg(fixture.controller, "onExtract");
        });

        waitForLogContainingAny(fixture.logTextArea, "Extract complete.", "Extract failed:");
        waitUntilTrue(() -> !fixture.extractMenuItem.isDisable());
        assertMenusMatchExpected(fixture, "echo ok", repoRoot, fixture.assignmentCombo.getValue(),
                false, "", true);
    }

    @Test
    void onGenerateImports_afterWorkerCompletes_restoresMenuState(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            fixture.checkstyleCheckBox.setSelected(false);
            fixture.cloneCommandField.setText("echo ok");
            invokePrivateNoArg(fixture.controller, "updateUiState");
            invokePrivateNoArg(fixture.controller, "onGenerateImports");
        });

        waitForLogContainingAny(fixture.logTextArea,
                "Generate Imports complete.",
                "Generate Imports failed:");
        waitUntilTrue(() -> !fixture.importsMenuItem.isDisable());
        assertMenusMatchExpected(fixture, "echo ok", repoRoot, fixture.assignmentCombo.getValue(),
                false, "", true);
    }

    @Test
    void onGenerateReports_afterWorkerCompletes_restoresMenuState(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            fixture.checkstyleCheckBox.setSelected(false);
            fixture.cloneCommandField.setText("echo ok");
            invokePrivateNoArg(fixture.controller, "updateUiState");
            invokePrivateNoArg(fixture.controller, "onGenerateReports");
        });

        AtomicReference<Boolean> reportsDisabledImmediate = new AtomicReference<>();
        AtomicReference<Boolean> runAllDisabledImmediate = new AtomicReference<>();
        runOnFxAndWait(() -> {
            reportsDisabledImmediate.set(fixture.reportsMenuItem.isDisable());
            runAllDisabledImmediate.set(fixture.runAllMenuItem.isDisable());
        });
        assertTrue(reportsDisabledImmediate.get());
        assertTrue(runAllDisabledImmediate.get());

        waitForLogContainingAny(
                fixture.logTextArea,
                "Generate Reports complete.",
                "Generate Reports failed:"
        );
        waitUntilTrue(() -> !fixture.reportsMenuItem.isDisable() && !fixture.runAllMenuItem.isDisable());
        assertMenusMatchExpected(fixture, "echo ok", repoRoot, fixture.assignmentCombo.getValue(),
                false, "", true);
    }

    @Test
    void onRunAll_afterWorkerCompletes_restoresAllRunMenus(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            fixture.checkstyleCheckBox.setSelected(false);
            fixture.cloneCommandField.setText("echo ok");
            invokePrivateNoArg(fixture.controller, "updateUiState");
            invokePrivateNoArg(fixture.controller, "onRunAll");
        });

        AtomicReference<Boolean> pullDisabledImmediate = new AtomicReference<>();
        AtomicReference<Boolean> extractDisabledImmediate = new AtomicReference<>();
        AtomicReference<Boolean> importsDisabledImmediate = new AtomicReference<>();
        AtomicReference<Boolean> reportsDisabledImmediate = new AtomicReference<>();
        AtomicReference<Boolean> runAllDisabledImmediate = new AtomicReference<>();
        runOnFxAndWait(() -> {
            pullDisabledImmediate.set(fixture.pullMenuItem.isDisable());
            extractDisabledImmediate.set(fixture.extractMenuItem.isDisable());
            importsDisabledImmediate.set(fixture.importsMenuItem.isDisable());
            reportsDisabledImmediate.set(fixture.reportsMenuItem.isDisable());
            runAllDisabledImmediate.set(fixture.runAllMenuItem.isDisable());
        });
        assertTrue(pullDisabledImmediate.get());
        assertTrue(extractDisabledImmediate.get());
        assertTrue(importsDisabledImmediate.get());
        assertTrue(reportsDisabledImmediate.get());
        assertTrue(runAllDisabledImmediate.get());

        waitForLogContaining(fixture.logTextArea, "=== RUN ALL END ===");
        waitUntilTrue(() -> !fixture.pullMenuItem.isDisable()
                && !fixture.extractMenuItem.isDisable()
                && !fixture.importsMenuItem.isDisable()
                && !fixture.reportsMenuItem.isDisable()
                && !fixture.runAllMenuItem.isDisable());
        assertMenusMatchExpected(fixture, "echo ok", repoRoot, fixture.assignmentCombo.getValue(),
                false, "", true);
    }

    @Test
    void onGradeAssignment_logsAbortWhenRootPathMissing(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);

        runOnFxAndWait(() -> {
            fixture.assignmentCombo.getSelectionModel().selectFirst();
            invokePrivateNoArg(fixture.controller, "onGradeAssignment");
        });

        waitForLogContaining(
                fixture.logTextArea,
                "Grade Assignment aborted: Repository root is not set."
        );
    }

    @Test
    void onGradeAssignment_logsAbortWhenAssignmentMissing(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            fixture.assignmentCombo.getSelectionModel().clearSelection();
            fixture.assignmentCombo.setValue(null);
            invokePrivateNoArg(fixture.controller, "onGradeAssignment");
        });

        waitForLogContaining(
                fixture.logTextArea,
                "Grade Assignment aborted: No assignment selected."
        );
    }

    @Test
    void onGradeAssignment_logsOpenWhenGradeWindowOpenerSucceeds(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            fixture.assignmentCombo.getSelectionModel().selectFirst();
            setField(
                    fixture.controller,
                    "gradeWindowOpener",
                    (MainWindowController.GradeWindowOpener) assignment -> {
                    }
            );
            invokePrivateNoArg(fixture.controller, "onGradeAssignment");
        });

        String assignmentKey = Objects.requireNonNull(fixture.assignmentCombo.getValue()).getKey();
        waitForLogContaining(
                fixture.logTextArea,
                "Opened grading window for: " + assignmentKey
        );
    }

    @Test
    void onGradeAssignment_logsFailureAndCauseWhenGradeWindowOpenFails(@TempDir Path tmp)
            throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path repoRoot = tmp.resolve("repos");
        Files.createDirectories(repoRoot);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "selectedRootPath", repoRoot);
            fixture.rootPathField.setText(repoRoot.toString());
            fixture.assignmentCombo.getSelectionModel().selectFirst();
            setField(
                    fixture.controller,
                    "gradeWindowOpener",
                    (MainWindowController.GradeWindowOpener) assignment -> {
                        throw new java.io.IOException(
                                "outer-open-failure",
                                new java.io.IOException("inner-cause")
                        );
                    }
            );
            invokePrivateNoArg(fixture.controller, "onGradeAssignment");
        });

        waitForLogContaining(
                fixture.logTextArea,
                "Failed to open grading window: outer-open-failure"
        );
        waitForLogContaining(
                fixture.logTextArea,
                "CAUSE: java.io.IOException: inner-cause"
        );
    }

    @Test
    void onBrowseRoot_select_updatesRootFieldAndLogs(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path selectedRoot = tmp.resolve("selected-root");
        Files.createDirectories(selectedRoot);

        runOnFxAndWait(() -> {
            setField(
                    fixture.controller,
                    "directoryDialogOpener",
                    (MainWindowController.DirectoryDialogOpener) (chooser, owner) ->
                            selectedRoot.toFile()
            );
            invokePrivateNoArg(fixture.controller, "onBrowseRoot");
        });

        waitForLogContaining(
                fixture.logTextArea,
                "Repository root set to: " + selectedRoot
        );

        AtomicReference<String> rootFieldTextRef = new AtomicReference<>("");
        AtomicReference<Path> selectedRootRef = new AtomicReference<>();
        runOnFxAndWait(() -> {
            rootFieldTextRef.set(fixture.rootPathField.getText());
            selectedRootRef.set((Path) getField(fixture.controller, "selectedRootPath"));
        });
        assertEquals(selectedRoot.toString(), rootFieldTextRef.get());
        assertEquals(selectedRoot, selectedRootRef.get());
    }

    @Test
    void onBrowseRoot_cancel_doesNotChangeRoot(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);

        runOnFxAndWait(() -> {
            setField(
                    fixture.controller,
                    "directoryDialogOpener",
                    (MainWindowController.DirectoryDialogOpener) (chooser, owner) -> null
            );
            invokePrivateNoArg(fixture.controller, "onBrowseRoot");
        });

        AtomicReference<String> rootFieldTextRef = new AtomicReference<>("not-empty");
        AtomicReference<Path> selectedRootRef = new AtomicReference<>(Path.of("/tmp/non-null"));
        runOnFxAndWait(() -> {
            rootFieldTextRef.set(fixture.rootPathField.getText());
            selectedRootRef.set((Path) getField(fixture.controller, "selectedRootPath"));
        });
        assertEquals("", rootFieldTextRef.get());
        assertNull(selectedRootRef.get());
    }

    @Test
    void onImportAssignments_success_replacesLoadedAssignments(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        Path importPath = tmp.resolve("import.json");
        writeValidImportAssignments(importPath);
        FxFixture fixture = loadFixture(tmp);

        runOnFxAndWait(() -> {
            setField(
                    fixture.controller,
                    "fileOpenDialogOpener",
                    (MainWindowController.FileOpenDialogOpener) (chooser, owner) ->
                            importPath.toFile()
            );
            invokePrivateNoArg(fixture.controller, "onImportAssignments");
        });

        waitForLogContaining(fixture.logTextArea, "Loaded assignments from: " + importPath);

        AtomicReference<Integer> sizeRef = new AtomicReference<>(-1);
        AtomicReference<String> keyRef = new AtomicReference<>("");
        runOnFxAndWait(() -> {
            sizeRef.set(fixture.assignmentCombo.getItems().size());
            Assignment selected = fixture.assignmentCombo.getValue();
            keyRef.set(selected == null ? "" : selected.getKey());
        });
        assertEquals(1, sizeRef.get());
        assertEquals("CSC-200-A9", keyRef.get());
    }

    @Test
    void onImportAssignments_invalidJson_logsFailure(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        Path importPath = tmp.resolve("import-invalid.json");
        Files.writeString(importPath, "{invalid");
        FxFixture fixture = loadFixture(tmp);

        runOnFxAndWait(() -> {
            setField(
                    fixture.controller,
                    "fileOpenDialogOpener",
                    (MainWindowController.FileOpenDialogOpener) (chooser, owner) ->
                            importPath.toFile()
            );
            invokePrivateNoArg(fixture.controller, "onImportAssignments");
        });

        waitForLogContaining(fixture.logTextArea, "Failed to load assignments:");
    }

    @Test
    void onImportAssignments_cancel_keepsExistingSelection(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);

        AtomicReference<String> beforeKeyRef = new AtomicReference<>("");
        runOnFxAndWait(() -> {
            Assignment selected = fixture.assignmentCombo.getValue();
            beforeKeyRef.set(selected == null ? "" : selected.getKey());
            setField(
                    fixture.controller,
                    "fileOpenDialogOpener",
                    (MainWindowController.FileOpenDialogOpener) (chooser, owner) -> null
            );
            invokePrivateNoArg(fixture.controller, "onImportAssignments");
        });

        AtomicReference<String> afterKeyRef = new AtomicReference<>("");
        runOnFxAndWait(() -> {
            Assignment selected = fixture.assignmentCombo.getValue();
            afterKeyRef.set(selected == null ? "" : selected.getKey());
        });
        assertEquals(beforeKeyRef.get(), afterKeyRef.get());
    }

    @Test
    void onExportAssignments_success_writesJsonFile(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path exportPath = tmp.resolve("exported-assignments.json");

        runOnFxAndWait(() -> {
            setField(
                    fixture.controller,
                    "fileSaveDialogOpener",
                    (MainWindowController.FileSaveDialogOpener) (chooser, owner) ->
                            exportPath.toFile()
            );
            invokePrivateNoArg(fixture.controller, "onExportAssignments");
        });

        waitForLogContaining(fixture.logTextArea, "Exported assignments to: " + exportPath);
        assertTrue(Files.exists(exportPath));
        assertTrue(Files.readString(exportPath).contains("\"assignments\""));
    }

    @Test
    void onExportAssignments_noAssignments_logsAbort(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path exportPath = tmp.resolve("should-not-write.json");

        runOnFxAndWait(() -> {
            setField(fixture.controller, "assignmentsFile", null);
            setField(
                    fixture.controller,
                    "fileSaveDialogOpener",
                    (MainWindowController.FileSaveDialogOpener) (chooser, owner) ->
                            exportPath.toFile()
            );
            invokePrivateNoArg(fixture.controller, "onExportAssignments");
        });

        waitForLogContaining(fixture.logTextArea, "No assignments loaded to export.");
        assertFalse(Files.exists(exportPath));
    }

    @Test
    void onExportAssignments_cancel_doesNotWriteFile(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path exportPath = tmp.resolve("cancel-export.json");

        runOnFxAndWait(() -> {
            setField(
                    fixture.controller,
                    "fileSaveDialogOpener",
                    (MainWindowController.FileSaveDialogOpener) (chooser, owner) -> null
            );
            invokePrivateNoArg(fixture.controller, "onExportAssignments");
        });

        assertFalse(Files.exists(exportPath));
    }

    @Test
    void expectedFilesDropFlow_addsAllowedBasenamesOnly(@TempDir Path tmp) throws Exception {
        writeValidSettings(tmp, "");
        writeValidAssignments(tmp);
        FxFixture fixture = loadFixture(tmp);
        Path droppedJava = tmp.resolve("NewFile.java");
        Path droppedText = tmp.resolve("notes.txt");
        Files.writeString(droppedJava, "class NewFile {}");
        Files.writeString(droppedText, "ignore");

        runOnFxAndWait(() -> {
            Assignment selected = fixture.assignmentCombo.getValue();
            setField(
                    fixture.controller,
                    "droppedFileNameExtractor",
                    (MainWindowController.DroppedFileNameExtractor) files ->
                            files.stream().map(java.io.File::getName).toList()
            );
            @SuppressWarnings("unchecked")
            List<String> droppedNames = ((MainWindowController.DroppedFileNameExtractor) getField(
                    fixture.controller,
                    "droppedFileNameExtractor"
            )).fileNamesFor(List.of(droppedJava.toFile(), droppedText.toFile()));
            List<String> merged = MainWindowController.mergeExpectedFilesForDrop(
                    selected == null ? null : selected.getExpectedFiles(),
                    droppedNames
            );
            if (selected != null) {
                selected.setExpectedFiles(merged);
            }
            invokePrivateNoArg(fixture.controller, "updateAssignmentSummary");
        });

        AtomicReference<List<String>> expectedItemsRef = new AtomicReference<>();
        runOnFxAndWait(() ->
                expectedItemsRef.set(List.copyOf(fixture.expectedFilesListView.getItems()))
        );
        assertTrue(expectedItemsRef.get().contains("Main.java"));
        assertTrue(expectedItemsRef.get().contains("NewFile.java"));
        assertFalse(expectedItemsRef.get().contains("notes.txt"));
    }

    private FxFixture loadFixture(Path tempHome) throws Exception {
        System.setProperty("user.home", tempHome.toString());

        AtomicReference<FxFixture> ref = new AtomicReference<>();
        runOnFxAndWait(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        MainWindowController.class.getResource("/ui/MainWindow.fxml")
                );
                Parent root = loader.load();
                new Scene(root);
                MainWindowController controller = loader.getController();
                TextField cloneCommandField =
                        (TextField) getField(controller, "cloneCommandField");
                TextField rootPathField =
                        (TextField) getField(controller, "rootPathField");
                TextField checkstyleUrlField =
                        (TextField) getField(controller, "checkstyleUrlField");
                CheckBox checkstyleCheckBox =
                        (CheckBox) getField(controller, "checkstyleCheckBox");
                Label assignmentTitleLabel =
                        (Label) getField(controller, "assignmentTitleLabel");
                @SuppressWarnings("unchecked")
                ListView<String> expectedFilesListView =
                        (ListView<String>) getField(controller, "expectedFilesListView");
                TextArea logTextArea =
                        (TextArea) getField(controller, "logTextArea");
                MenuItem pullMenuItem =
                        (MenuItem) getField(controller, "pullMenuItem");
                MenuItem extractMenuItem =
                        (MenuItem) getField(controller, "extractMenuItem");
                MenuItem importsMenuItem =
                        (MenuItem) getField(controller, "importsMenuItem");
                MenuItem reportsMenuItem =
                        (MenuItem) getField(controller, "reportsMenuItem");
                MenuItem runAllMenuItem =
                        (MenuItem) getField(controller, "runAllMenuItem");
                @SuppressWarnings("unchecked")
                ComboBox<Assignment> assignmentCombo =
                        (ComboBox<Assignment>) getField(controller, "assignmentCombo");

                ref.set(new FxFixture(
                        controller,
                        cloneCommandField,
                        rootPathField,
                        checkstyleUrlField,
                        checkstyleCheckBox,
                        assignmentTitleLabel,
                        expectedFilesListView,
                        logTextArea,
                        assignmentCombo,
                        pullMenuItem,
                        extractMenuItem,
                        importsMenuItem,
                        reportsMenuItem,
                        runAllMenuItem
                ));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return ref.get();
    }

    private void writeValidSettings(Path homePath, String checkstyleUrl) throws Exception {
        Path settingsPath = homePath.resolve(".gh-classroom-utils")
                .resolve("settings")
                .resolve("settings.json");
        Files.createDirectories(Objects.requireNonNull(settingsPath.getParent()));
        String json = """
                {
                  "schemaVersion": 1,
                  "checkstyleConfigUrl": "%s"
                }
                """.formatted(checkstyleUrl);
        Files.writeString(settingsPath, json);
    }

    private void writeValidAssignments(Path homePath) throws Exception {
        Path assignmentsPath = homePath.resolve(".gh-classroom-utils")
                .resolve("assignments")
                .resolve("assignments.json");
        Files.createDirectories(Objects.requireNonNull(assignmentsPath.getParent()));
        String json = """
                {
                  "schemaVersion": 1,
                  "rubricItemLibrary": {
                    "ri_code": {
                      "name": "Code Quality",
                      "defaultPoints": 100,
                      "isCheckstyleItem": false
                    }
                  },
                  "assignments": [
                    {
                      "courseCode": "CSC-100",
                      "assignmentCode": "A1",
                      "assignmentName": "Assignment One",
                      "expectedFiles": ["Main.java"],
                      "rubric": {
                        "items": [
                          {"rubricItemId": "ri_code", "points": 100}
                        ]
                      }
                    }
                  ]
                }
                """;
        Files.writeString(assignmentsPath, json);
    }

    private void writeValidAssignmentsWithTwo(Path homePath) throws Exception {
        Path assignmentsPath = homePath.resolve(".gh-classroom-utils")
                .resolve("assignments")
                .resolve("assignments.json");
        Files.createDirectories(Objects.requireNonNull(assignmentsPath.getParent()));
        String json = """
                {
                  "schemaVersion": 1,
                  "rubricItemLibrary": {
                    "ri_code": {
                      "name": "Code Quality",
                      "defaultPoints": 100,
                      "isCheckstyleItem": false
                    }
                  },
                  "assignments": [
                    {
                      "courseCode": "CSC-100",
                      "assignmentCode": "A1",
                      "assignmentName": "Assignment One",
                      "expectedFiles": ["Main.java"],
                      "rubric": {
                        "items": [
                          {"rubricItemId": "ri_code", "points": 100}
                        ]
                      }
                    },
                    {
                      "courseCode": "CSC-100",
                      "assignmentCode": "A2",
                      "assignmentName": "Assignment Two",
                      "expectedFiles": ["Main.java"],
                      "rubric": {
                        "items": [
                          {"rubricItemId": "ri_code", "points": 90}
                        ]
                      }
                    }
                  ]
                }
                """;
        Files.writeString(assignmentsPath, json);
    }

    private void writeValidImportAssignments(Path path) throws Exception {
        Files.createDirectories(Objects.requireNonNull(path.getParent()));
        String json = """
                {
                  "schemaVersion": 1,
                  "rubricItemLibrary": {
                    "ri_code": {
                      "name": "Code Quality",
                      "defaultPoints": 100,
                      "isCheckstyleItem": false
                    }
                  },
                  "assignments": [
                    {
                      "courseCode": "CSC-200",
                      "assignmentCode": "A9",
                      "assignmentName": "Imported Assignment",
                      "expectedFiles": ["ImportedMain.java"],
                      "rubric": {
                        "items": [
                          {"rubricItemId": "ri_code", "points": 100}
                        ]
                      }
                    }
                  ]
                }
                """;
        Files.writeString(path, json);
    }

    private void waitForLogContaining(TextArea logTextArea, String expectedText) throws Exception {
        long deadline = System.currentTimeMillis() + LONG_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<String> textRef = new AtomicReference<>("");
            runOnFxAndWait(() -> textRef.set(logTextArea.getText()));
            if (textRef.get().contains(expectedText)) {
                return;
            }
            Thread.sleep(SHORT_WAIT_MILLIS);
        }
        AtomicReference<String> finalTextRef = new AtomicReference<>("");
        runOnFxAndWait(() -> finalTextRef.set(logTextArea.getText()));
        assertTrue(finalTextRef.get().contains(expectedText),
                "Expected log to contain: " + expectedText + " but was:\n" + finalTextRef.get());
    }

    private void waitForLogContainingAny(TextArea logTextArea, String first, String second)
            throws Exception {
        long deadline = System.currentTimeMillis() + LONG_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<String> textRef = new AtomicReference<>("");
            runOnFxAndWait(() -> textRef.set(logTextArea.getText()));
            String text = textRef.get();
            if (text.contains(first) || text.contains(second)) {
                return;
            }
            Thread.sleep(SHORT_WAIT_MILLIS);
        }
        AtomicReference<String> finalTextRef = new AtomicReference<>("");
        runOnFxAndWait(() -> finalTextRef.set(logTextArea.getText()));
        String finalText = finalTextRef.get();
        assertTrue(finalText.contains(first) || finalText.contains(second),
                "Expected log to contain one of [" + first + "] or [" + second + "] but was:\n"
                        + finalText);
    }

    private void assertMenusMatchExpected(FxFixture fixture,
                                          String cloneCommand,
                                          Path rootPath,
                                          Assignment assignment,
                                          boolean checkstyleEnabled,
                                          String checkstyleUrl,
                                          boolean hasAssignmentsLoaded)
            throws Exception {
        MainWindowController.UiState expected = MainWindowController.evaluateUiState(
                cloneCommand,
                rootPath,
                assignment,
                checkstyleEnabled,
                checkstyleUrl,
                hasAssignmentsLoaded
        );

        AtomicReference<Boolean> pullDisabled = new AtomicReference<>();
        AtomicReference<Boolean> extractDisabled = new AtomicReference<>();
        AtomicReference<Boolean> importsDisabled = new AtomicReference<>();
        AtomicReference<Boolean> reportsDisabled = new AtomicReference<>();
        AtomicReference<Boolean> runAllDisabled = new AtomicReference<>();
        runOnFxAndWait(() -> {
            pullDisabled.set(fixture.pullMenuItem.isDisable());
            extractDisabled.set(fixture.extractMenuItem.isDisable());
            importsDisabled.set(fixture.importsMenuItem.isDisable());
            reportsDisabled.set(fixture.reportsMenuItem.isDisable());
            runAllDisabled.set(fixture.runAllMenuItem.isDisable());
        });

        assertEquals(!expected.pullEnabled(), pullDisabled.get());
        assertEquals(!expected.extractEnabled(), extractDisabled.get());
        assertEquals(!expected.importsEnabled(), importsDisabled.get());
        assertEquals(!expected.reportsEnabled(), reportsDisabled.get());
        assertEquals(!expected.runAllEnabled(), runAllDisabled.get());
    }

    private void waitUntilTrue(FxBooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + LONG_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<Boolean> conditionRef = new AtomicReference<>(false);
            runOnFxAndWait(() -> conditionRef.set(condition.getAsBoolean()));
            if (conditionRef.get()) {
                return;
            }
            Thread.sleep(SHORT_WAIT_MILLIS);
        }
        AtomicReference<Boolean> finalRef = new AtomicReference<>(false);
        runOnFxAndWait(() -> finalRef.set(condition.getAsBoolean()));
        assertTrue(finalRef.get(), "Timed out waiting for condition.");
    }

    private void runOnFxAndWait(FxTask task) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timed out waiting for JavaFX task.");
        }
        if (errorRef.get() != null) {
            throw new RuntimeException(errorRef.get());
        }
    }

    private void invokePrivateNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Object invokePrivate(Object target,
                                 String methodName,
                                 Class<?>[] parameterTypes,
                                 Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Object getField(Object target, String fieldName) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private record FxFixture(MainWindowController controller,
                             TextField cloneCommandField,
                             TextField rootPathField,
                             TextField checkstyleUrlField,
                             CheckBox checkstyleCheckBox,
                             Label assignmentTitleLabel,
                             ListView<String> expectedFilesListView,
                             TextArea logTextArea,
                             ComboBox<Assignment> assignmentCombo,
                             MenuItem pullMenuItem,
                             MenuItem extractMenuItem,
                             MenuItem importsMenuItem,
                             MenuItem reportsMenuItem,
                             MenuItem runAllMenuItem) {
    }

    @FunctionalInterface
    private interface FxTask {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FxBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}

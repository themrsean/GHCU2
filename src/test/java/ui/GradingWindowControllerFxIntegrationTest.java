package ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import javafx.stage.Window;
import model.Assignment;
import model.AssignmentsFile;
import model.RubricItemDef;
import model.RubricItemRef;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradingWindowControllerFxIntegrationTest {

    private static final int SHORT_WAIT_MILLIS = 200;
    private static final int LONG_WAIT_MILLIS = 10000;
    private static final int MODAL_ACTION_POLL_MILLIS = 100;
    private static volatile boolean javaFxAvailable;

    private String originalUserHome;
    private final List<Stage> openedStages = new ArrayList<>();

    @BeforeAll
    static void initJavaFxToolkit() {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
            javaFxAvailable = latch.await(5, TimeUnit.SECONDS);
            if (javaFxAvailable) {
                // Keep JavaFX runtime alive after closing test stages between test methods.
                Platform.setImplicitExit(false);
            }
        } catch (IllegalStateException e) {
            javaFxAvailable = true;
            Platform.setImplicitExit(false);
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
    void cleanup() throws Exception {
        runOnFxAndWait(() -> {
            for (Stage stage : openedStages) {
                if (stage != null) {
                    stage.close();
                }
            }
            openedStages.clear();
            List<Window> snapshot = new ArrayList<>(Window.getWindows());
            for (Window window : snapshot) {
                if (window instanceof Stage stage && stage.isShowing()) {
                    stage.close();
                }
            }
        });
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void onSaveAndExport_success_disablesThenReenablesUi_andReportsPushSummary(
            @TempDir Path tmp
    ) throws Exception {
        Path repoDir = createRepoWithUpstream(tmp.resolve("repo-success"), tmp.resolve("remote.git"));
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("# Feedback");
            invokePrivateNoArg(fixture.controller, "onSaveAndExport");
        });

        waitUntilTrue(() -> fixture.saveAndExportButton.isDisable());
        waitUntilTrue(() -> !fixture.saveAndExportButton.isDisable());
        waitUntilStatusContains(fixture.statusLabel, "Save/push complete: pushed 1, skipped 0, failed 0.");
    }

    @Test
    void onSaveAndExport_missingRepo_reportsFailureAndDoesNotPush(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-initial");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        writeMappingFile(mappingsPath, Map.of("pkgOther", repoDir.toString()));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("# Feedback");
            invokePrivateNoArg(fixture.controller, "onSaveAndExport");
        });

        waitUntilStatusContains(fixture.statusLabel, "Could not find repo for pkg1");
        waitUntilTrue(() -> !fixture.saveAndExportButton.isDisable());
    }

    @Test
    void onSaveAndExport_multiStudentMixedOutcome_reportsDetailSummary(@TempDir Path tmp)
            throws Exception {
        Path repoGood = createRepoWithUpstream(tmp.resolve("repo-good"), tmp.resolve("remote-good.git"));
        Path repoNonGit = tmp.resolve("repo-non-git");
        Files.createDirectories(repoNonGit);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(
                mappingsPath,
                Map.of("pkg1", repoGood.toString(), "pkg2", repoNonGit.toString())
        );

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("# Mixed push");
            invokePrivateNoArg(fixture.controller, "onSaveAndExport");
        });

        waitUntilTrue(() -> !fixture.saveAndExportButton.isDisable());
        waitUntilStatusContains(fixture.statusLabel, "Save/push complete: pushed 1, skipped 1, failed 0.");
        waitUntilStatusContains(fixture.statusLabel, "pkg2: unable to determine current branch");
    }

    @Test
    void onSaveAndExport_pushAsyncException_reenablesUi_andShowsFailureStatus(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-push-async-ex");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));
        AtomicBoolean observedDisabledState = new AtomicBoolean(false);

        runOnFxAndWait(() -> {
            Class<?> pushWorkerType = Class.forName("ui.GradingWindowController$PushAllWorker");
            Object throwingPushWorker = Proxy.newProxyInstance(
                    pushWorkerType.getClassLoader(),
                    new Class<?>[] {pushWorkerType},
                    (_, _, _) -> {
                        throw new RuntimeException("boom-push");
                    }
            );
            setField(
                    fixture.controller,
                    "pushAllWorker",
                    throwingPushWorker
            );
            fixture.saveAndExportButton.disableProperty().addListener((_, _, isDisabled) -> {
                if (Boolean.TRUE.equals(isDisabled)) {
                    observedDisabledState.set(true);
                }
            });
            fixture.reportEditor.replaceText("# Push exception");
            invokePrivateNoArg(fixture.controller, "onSaveAndExport");
        });

        waitUntilStatusContains(fixture.statusLabel, "Save/push failed: boom-push");
        waitUntilTrue(() -> !fixture.saveAndExportButton.isDisable());
        waitUntilTrue(() -> !fixture.reportEditor.isDisable());
        assertTrue(observedDisabledState.get());
    }

    @Test
    void studentSwitch_persistsOldDraft_andLoadsNewDraft(@TempDir Path tmp)
            throws Exception {
        Path repoA = tmp.resolve("repo-a");
        Path repoB = tmp.resolve("repo-b");
        Files.createDirectories(repoA);
        Files.createDirectories(repoB);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkgA", repoA.toString(), "pkgB", repoB.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkgA".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("alpha-draft");
            fixture.reportEditor.moveTo(5);
            setDraftState(fixture.controller, "pkgB", "beta-draft", 2, true);
            fixture.studentList.getSelectionModel().select("pkgB");
        });

        waitUntilTrue(() -> "pkgB".equals(readCurrentStudent(fixture.controller)));
        waitUntilTrue(() -> fixture.reportEditor.getText().contains("beta-draft"));

        assertEquals("alpha-draft", readDraftMarkdown(fixture.controller, "pkgA"));
    }

    @Test
    void studentSwitch_selectingSameStudent_doesNotReloadEditorState(@TempDir Path tmp)
            throws Exception {
        Path repoA = tmp.resolve("repo-a-same");
        Path repoB = tmp.resolve("repo-b-same");
        Files.createDirectories(repoA);
        Files.createDirectories(repoB);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkgA", repoA.toString(), "pkgB", repoB.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkgA".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("live-edit");
            fixture.reportEditor.moveTo(4);
            setDraftState(fixture.controller, "pkgA", "persisted-draft", 0, true);
            fixture.studentList.getSelectionModel().select("pkgA");
        });

        waitUntilTrue(() -> "pkgA".equals(readCurrentStudent(fixture.controller)));
        assertEquals("live-edit", runOnFxAndWaitResult(() -> fixture.reportEditor.getText()));
        assertEquals(4, (int) runOnFxAndWaitResult(() -> fixture.reportEditor.getCaretPosition()));
    }

    @Test
    void onRebuildSummary_isIdempotentForSameInjectedCommentSet(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-rebuild");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> fixture.reportEditor.replaceText(
                """
                # Assignment

                <!-- RUBRIC_TABLE_BEGIN -->
                >> | Earned | Possible | Criteria |
                >> | --- | --- | --- |
                >> | 10 | 10 | Implementation |
                >> | 10 | 10 | TOTAL |
                <!-- RUBRIC_TABLE_END -->

                <!-- COMMENTS_SUMMARY_BEGIN -->
                _No comments selected._
                <!-- COMMENTS_SUMMARY_END -->

                <a id="cmt_x"></a>
                ```
                > #### Title
                > * -3 points (ri_impl)
                ```
                """
        ));

        runOnFxAndWait(() -> invokePrivateNoArg(fixture.controller, "onRebuildSummary"));
        String first = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());

        runOnFxAndWait(() -> invokePrivateNoArg(fixture.controller, "onRebuildSummary"));
        String second = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());

        assertEquals(first.replaceAll(" +", " "), second.replaceAll(" +", " "));
    }

    @Test
    void onPreviewDraft_withoutSelectedStudent_reportsFailure(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-preview");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "currentStudent", null);
            invokePrivateNoArg(fixture.controller, "onPreviewDraft");
        });

        waitUntilStatusContains(fixture.statusLabel, "Preview failed: no student selected.");
    }

    @Test
    void onSaveDrafts_whenSaveAlreadyInProgress_reportsGuardMessage(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-guard");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);

        runOnFxAndWait(() -> {
            setField(fixture.controller, "saveInProgress", true);
            invokePrivateNoArg(fixture.controller, "onSaveDrafts");
        });

        waitUntilStatusContains(fixture.statusLabel, "Save already in progress.");
    }

    @Test
    void onSaveDrafts_success_disablesThenReenablesUi_andSavesReport(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-save-success");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));
        AtomicBoolean observedDisabledState = new AtomicBoolean(false);

        runOnFxAndWait(() -> {
            fixture.reportEditor.disableProperty().addListener((_, _, isDisabled) -> {
                if (Boolean.TRUE.equals(isDisabled)) {
                    observedDisabledState.set(true);
                }
            });
            fixture.reportEditor.replaceText("# Save success");
            invokePrivateNoArg(fixture.controller, "onSaveDrafts");
        });

        waitUntilTrue(() -> !fixture.reportEditor.isDisable());
        assertTrue(observedDisabledState.get());
        waitUntilStatusContains(
                fixture.statusLabel,
                "Saved 1 HTML report(s) to student repositories."
        );
        assertTrue(Files.exists(repoDir.resolve("A1pkg1.html")));
    }

    @Test
    void onSaveDrafts_missingRepo_reportsFailure_andReenablesUi(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-save-failure");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        writeMappingFile(mappingsPath, Map.of("pkgOther", repoDir.toString()));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("# Save failure");
            invokePrivateNoArg(fixture.controller, "onSaveDrafts");
        });

        waitUntilStatusContains(fixture.statusLabel, "Could not find repo for pkg1");
        waitUntilTrue(() -> !fixture.reportEditor.isDisable());
    }

    @Test
    void onSaveDrafts_asyncException_reenablesUi_andClearsSaveInProgress(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-save-async-ex");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));
        AtomicBoolean observedDisabledState = new AtomicBoolean(false);

        runOnFxAndWait(() -> {
            Class<?> saveWorkerType = Class.forName("ui.GradingWindowController$SaveDraftWorker");
            Object throwingSaveWorker = Proxy.newProxyInstance(
                    saveWorkerType.getClassLoader(),
                    new Class<?>[] {saveWorkerType},
                    (_, _, _) -> {
                        throw new RuntimeException("boom-save");
                    }
            );
            setField(
                    fixture.controller,
                    "saveDraftWorker",
                    throwingSaveWorker
            );
            fixture.reportEditor.disableProperty().addListener((_, _, isDisabled) -> {
                if (Boolean.TRUE.equals(isDisabled)) {
                    observedDisabledState.set(true);
                }
            });
            fixture.reportEditor.replaceText("# Save exception");
            invokePrivateNoArg(fixture.controller, "onSaveDrafts");
        });

        waitUntilStatusContains(fixture.statusLabel, "Save failed: boom-save");
        waitUntilTrue(() -> !fixture.reportEditor.isDisable());
        assertTrue(observedDisabledState.get());
        assertFalse((Boolean) getField(fixture.controller, "saveInProgress"));
    }

    @Test
    void onExportGradedHtml_success_disablesThenReenablesUi_andSavesReport(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-export-success");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));
        AtomicBoolean observedDisabledState = new AtomicBoolean(false);

        runOnFxAndWait(() -> {
            fixture.reportEditor.disableProperty().addListener((_, _, isDisabled) -> {
                if (Boolean.TRUE.equals(isDisabled)) {
                    observedDisabledState.set(true);
                }
            });
            fixture.reportEditor.replaceText("# Export success");
            invokePrivateNoArg(fixture.controller, "onExportGradedHtml");
        });

        waitUntilTrue(() -> !fixture.reportEditor.isDisable());
        assertTrue(observedDisabledState.get());
        waitUntilStatusContains(
                fixture.statusLabel,
                "Saved 1 HTML report(s) to student repositories."
        );
        assertTrue(Files.exists(repoDir.resolve("A1pkg1.html")));
        Path feedbackCopy = tmp.resolve("root")
                .resolve("feedback")
                .resolve("A1pkg1.html");
        assertTrue(Files.exists(feedbackCopy));
    }

    @Test
    void onExportGradedHtml_missingRepo_reportsFailure_andReenablesUi(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-export-initial");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        writeMappingFile(mappingsPath, Map.of("pkgOther", repoDir.toString()));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("# Export failure");
            invokePrivateNoArg(fixture.controller, "onExportGradedHtml");
        });

        waitUntilStatusContains(fixture.statusLabel, "Could not find repo for pkg1");
        waitUntilTrue(() -> !fixture.reportEditor.isDisable());
    }

    @Test
    void criticalFxFlows_areRepeatableAcrossMultipleIterations(@TempDir Path tmp)
            throws Exception {
        final int iterations = 3;
        for (int i = 0; i < iterations; i++) {
            final int iteration = i;
            Path iterationRoot = tmp.resolve("iter-" + i);
            Files.createDirectories(iterationRoot);
            Path repoDir = iterationRoot.resolve("repo");
            Files.createDirectories(repoDir);
            Path mappingsPath = iterationRoot.resolve("mappings.json");
            writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));
            writeCommentsJson(iterationRoot, 1);

            FxFixture fixture = loadFixture(iterationRoot, mappingsPath);
            waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

            runOnFxAndWait(() -> {
                setField(fixture.controller, "saveInProgress", true);
                invokePrivateNoArg(fixture.controller, "onSaveDrafts");
            });
            waitUntilStatusContains(fixture.statusLabel, "Save already in progress.");

            runOnFxAndWait(() -> fixture.reportEditor.replaceText("# Iteration " + iteration + "\n"));
            scheduleFxModalAction(() -> clickButtonInTopModal("Apply"));
            invokePrivateNoArgAsync(fixture.controller, "onInsertComment");
            waitUntilTrue(() -> fixture.reportEditor.getText().contains("<a id=\"cmt_c1\"></a>"));

            runOnFxAndWait(() -> fixture.stage.close());
        }
    }

    @Test
    void onInsertComment_modalApply_insertsSelectedCommentBlock(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-insert");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));
        writeCommentsJson(tmp, 1);

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> fixture.reportEditor.replaceText("# Base\n"));
        scheduleFxModalAction(() -> clickButtonInTopModal("Apply"));
        invokePrivateNoArgAsync(fixture.controller, "onInsertComment");

        waitUntilTrue(() -> fixture.reportEditor.getText().contains("<a id=\"cmt_c1\"></a>"));
        String text = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());
        assertTrue(text.contains("<a id=\"cmt_c1\"></a>"));
        assertTrue(text.contains("> #### -2 Style"));
    }

    @Test
    void onInsertComment_modalApply_insertsAtCaret_andPreservesCaret_andRebuildsRubric(
            @TempDir Path tmp
    ) throws Exception {
        Path repoDir = tmp.resolve("repo-insert-caret");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));
        writeCommentsJson(tmp, 1);

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> fixture.reportEditor.replaceText(
                """
                # Assignment

                <!-- RUBRIC_TABLE_BEGIN -->
                >> | Earned | Possible | Criteria |
                >> | --- | --- | --- |
                >> | 10 | 10 | Implementation |
                >> | 10 | 10 | TOTAL |
                <!-- RUBRIC_TABLE_END -->

                > # Feedback
                > * Start

                ABCD
                """
        ));
        runOnFxAndWait(() -> {
            int caret = fixture.reportEditor.getText().indexOf("ABCD") + 2;
            fixture.reportEditor.moveTo(caret);
        });

        scheduleFxModalAction(() -> clickButtonInTopModal("Apply"));
        invokePrivateNoArgAsync(fixture.controller, "onInsertComment");

        waitUntilTrue(() -> fixture.reportEditor.getText().contains("<a id=\"cmt_c1\"></a>"));
        runOnFxAndWait(() -> invokePrivateNoArg(fixture.controller, "onRebuildSummary"));
        String text = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());
        int caretAfter = runOnFxAndWaitResult(() -> fixture.reportEditor.getCaretPosition());
        int anchorIndex = text.indexOf("<a id=\"cmt_c1\"></a>");
        int abIndex = text.indexOf("AB");
        int cdIndex = text.indexOf("CD");

        assertTrue(abIndex >= 0);
        assertTrue(cdIndex > abIndex);
        assertTrue(text.contains("ABCD"));
        assertFalse(text.contains("AB<a id=\"cmt_c1\"></a>CD"));
        assertTrue(anchorIndex > cdIndex);
        assertTrue(text.matches(
                "(?s).*(?:>>\\s*)?\\|\\s*8(?:\\.0+)?\\s*\\|\\s*10(?:\\.0+)?\\s*\\|\\s*Implementation\\b.*"
        ));
        assertTrue(anchorIndex >= 0);
        int openingFence = text.indexOf("```", anchorIndex);
        int closingFence = openingFence >= 0 ? text.indexOf("```", openingFence + 3) : -1;
        assertTrue(closingFence >= 0);
        assertTrue(caretAfter > closingFence);
    }

    @Test
    void studentSwitch_preservesSelectionAcrossReports(@TempDir Path tmp)
            throws Exception {
        Path repoA = tmp.resolve("repo-a-selection");
        Path repoB = tmp.resolve("repo-b-selection");
        Files.createDirectories(repoA);
        Files.createDirectories(repoB);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkgA", repoA.toString(), "pkgB", repoB.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkgA".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("ABCDE");
            fixture.reportEditor.selectRange(1, 4);
            setDraftState(fixture.controller, "pkgB", "beta-draft", 2, true);
            fixture.studentList.getSelectionModel().select("pkgB");
        });

        waitUntilTrue(() -> "pkgB".equals(readCurrentStudent(fixture.controller)));
        runOnFxAndWait(() -> fixture.studentList.getSelectionModel().select("pkgA"));
        waitUntilTrue(() -> "pkgA".equals(readCurrentStudent(fixture.controller)));

        String selected = runOnFxAndWaitResult(() -> fixture.reportEditor.getSelectedText());
        assertEquals("BCD", selected);
    }

    @Test
    void onInsertComment_modalCancel_leavesEditorUnchanged(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-insert-cancel");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));
        writeCommentsJson(tmp, 1);

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> fixture.reportEditor.replaceText("# Base unchanged\n"));
        String before = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());

        scheduleFxModalAction(() -> clickButtonInTopModal("Cancel"));
        invokePrivateNoArgAsync(fixture.controller, "onInsertComment");
        waitUntilTrue(() -> findTopShowingModal() == null);

        String after = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());
        assertEquals(before, after);
        assertFalse(after.contains("<a id=\"cmt_c1\"></a>"));
    }

    @Test
    void onInsertComment_modalApply_cancelsWhenStudentChangesDuringModal(@TempDir Path tmp)
            throws Exception {
        Path repoA = tmp.resolve("repo-a");
        Path repoB = tmp.resolve("repo-b");
        Files.createDirectories(repoA);
        Files.createDirectories(repoB);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoA.toString(), "pkg2", repoB.toString()));
        writeCommentsJson(tmp, 1);

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> {
            String student = readCurrentStudent(fixture.controller);
            return student != null && !student.isBlank();
        });
        String initialStudent = readCurrentStudent(fixture.controller);
        String otherStudent = "pkg1".equals(initialStudent) ? "pkg2" : "pkg1";

        runOnFxAndWait(() -> fixture.reportEditor.replaceText("# Base\n"));
        scheduleFxModalAction(() -> {
            setField(fixture.controller, "currentStudent", otherStudent);
            return clickButtonInTopModal("Apply");
        });
        invokePrivateNoArgAsync(fixture.controller, "onInsertComment");

        waitUntilStatusContains(
                fixture.statusLabel,
                "Insert Comment cancelled: active student changed while picker was open."
        );
        String text = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());
        assertFalse(text.contains("<a id=\"cmt_c1\"></a>"));
    }

    @Test
    void onInsertComment_modalApply_cancelsWhenReportChangesDuringModal(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-insert-mutation");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));
        writeCommentsJson(tmp, 1);

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> fixture.reportEditor.replaceText("# Base\n"));
        scheduleFxModalAction(() -> {
            fixture.reportEditor.replaceText("# Changed while modal open\n");
            return clickButtonInTopModal("Apply");
        });
        invokePrivateNoArgAsync(fixture.controller, "onInsertComment");

        waitUntilStatusContains(
                fixture.statusLabel,
                "Insert Comment cancelled: report changed while picker was open."
        );
        String text = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());
        assertFalse(text.contains("<a id=\"cmt_c1\"></a>"));
        assertTrue(text.contains("# Changed while modal open"));
    }

    @Test
    void onRemoveComment_modalRemove_deletesSelectedInjectedComment(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-remove");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));
        writeCommentsJson(tmp, 1);

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> fixture.reportEditor.replaceText(
                """
                # Assignment
                <a id="cmt_c1"></a>
                ```
                > #### Style
                > * -2 points (ri_impl)
                ```
                """
        ));

        scheduleFxModalAction(() -> clickButtonInTopModal("Remove"));
        invokePrivateNoArgAsync(fixture.controller, "onRemoveComment");

        waitUntilTrue(() -> !fixture.reportEditor.getText().contains("<a id=\"cmt_c1\"></a>"));
        String text = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());
        assertFalse(text.contains("<a id=\"cmt_c1\"></a>"));
        assertTrue(text.contains("# Assignment"));
    }

    @Test
    void onRemoveComment_modalCancel_keepsInjectedCommentBlock(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-remove-cancel");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));
        writeCommentsJson(tmp, 1);

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> fixture.reportEditor.replaceText(
                """
                # Assignment
                <a id="cmt_c1"></a>
                ```
                > #### Style
                > * -2 points (ri_impl)
                ```
                """
        ));
        String before = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());

        scheduleFxModalAction(() -> clickButtonInTopModal("Cancel"));
        invokePrivateNoArgAsync(fixture.controller, "onRemoveComment");
        waitUntilTrue(() -> findTopShowingModal() == null);

        String after = runOnFxAndWaitResult(() -> fixture.reportEditor.getText());
        assertEquals(before, after);
        assertTrue(after.contains("<a id=\"cmt_c1\"></a>"));
    }

    @Test
    void onManageComments_modalClose_reloadsUpdatedCommentsFile(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-manage");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));
        writeCommentsJson(tmp, 1);

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        scheduleFxModalAction(() -> {
            try {
                writeCommentsJson(tmp, 2);
                return closeTopModal();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            // unreachable
        });
        invokePrivateNoArgAsync(fixture.controller, "onManageComments");

        waitUntilTrue(() -> {
            Object library = getField(fixture.controller, "commentLibrary");
            Method getComments = library.getClass().getDeclaredMethod("getComments");
            @SuppressWarnings("unchecked")
            List<Object> comments = (List<Object>) getComments.invoke(library);
            return comments.size() == 2;
        });

        Object library = getField(fixture.controller, "commentLibrary");
        Method getComments = library.getClass().getDeclaredMethod("getComments");
        @SuppressWarnings("unchecked")
        List<Object> comments = (List<Object>) getComments.invoke(library);
        assertEquals(2, comments.size());
    }

    @Test
    void onManageComments_modalCloseWithoutChanges_preservesLoadedLibrary(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-manage-nochange");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));
        writeCommentsJson(tmp, 1);

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        Object libraryBefore = getField(fixture.controller, "commentLibrary");
        Method getComments = libraryBefore.getClass().getDeclaredMethod("getComments");
        @SuppressWarnings("unchecked")
        List<Object> beforeComments = (List<Object>) getComments.invoke(libraryBefore);
        int beforeCount = beforeComments.size();

        scheduleFxModalAction(this::closeTopModal);
        invokePrivateNoArgAsync(fixture.controller, "onManageComments");

        waitUntilTrue(() -> {
            Object library = getField(fixture.controller, "commentLibrary");
            Method localGetComments = library.getClass().getDeclaredMethod("getComments");
            @SuppressWarnings("unchecked")
            List<Object> comments = (List<Object>) localGetComments.invoke(library);
            return comments.size() == beforeCount;
        });

        Object libraryAfter = getField(fixture.controller, "commentLibrary");
        @SuppressWarnings("unchecked")
        List<Object> afterComments = (List<Object>) getComments.invoke(libraryAfter);
        assertEquals(beforeCount, afterComments.size());
    }

    @Test
    void onCloseAndSaveAll_closesWindowAfterSuccessfulSave(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-close");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> fixture.reportEditor.replaceText("# Close-save content"));
        runOnFxAndWait(() -> invokePrivateNoArg(fixture.controller, "onCloseAndSaveAll"));

        waitUntilTrue(() -> !fixture.stage.isShowing());
        Path savedReport = repoDir.resolve("A1pkg1.html");
        assertTrue(Files.exists(savedReport));
    }

    @Test
    void onCloseAndSaveAll_failedSave_keepsWindowOpen(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-close-failure");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));
        writeMappingFile(mappingsPath, Map.of("pkgOther", repoDir.toString()));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("# Close-failure content");
            invokePrivateNoArg(fixture.controller, "onCloseAndSaveAll");
        });

        waitUntilStatusContains(fixture.statusLabel, "Could not find repo for pkg1");
        waitUntilTrue(() -> fixture.stage.isShowing());
    }

    @Test
    void onPreviewDraft_refreshButton_rewritesPreviewAndPreservesCaret(@TempDir Path tmp)
            throws Exception {
        Path repoDir = tmp.resolve("repo-preview-refresh");
        Files.createDirectories(repoDir);
        Path mappingsPath = tmp.resolve("mappings.json");
        writeMappingFile(mappingsPath, Map.of("pkg1", repoDir.toString()));

        FxFixture fixture = loadFixture(tmp, mappingsPath);
        waitUntilTrue(() -> "pkg1".equals(readCurrentStudent(fixture.controller)));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("# First content");
            fixture.reportEditor.moveTo(2);
            invokePrivateNoArg(fixture.controller, "onPreviewDraft");
        });

        waitUntilTrue(() -> findShowingStageByTitle("Preview: Current Draft") != null);
        Path previewFile = expectedPreviewFile(tmp, "A1pkg1");
        waitUntilTrue(() -> Files.exists(previewFile));

        runOnFxAndWait(() -> {
            fixture.reportEditor.replaceText("# Second content");
            fixture.reportEditor.moveTo(3);
        });

        runOnFxAndWait(() -> clickButtonInStage("Preview: Current Draft", "Refresh"));
        waitUntilTrue(() -> Files.readString(previewFile).contains("Second content"));
        waitUntilTrue(() -> fixture.reportEditor.getCaretPosition() == 3);
    }

    private FxFixture loadFixture(Path tempHome, Path mappingsPath) throws Exception {
        System.setProperty("user.home", tempHome.toString());
        Path rootPath = tempHome.resolve("root");
        Files.createDirectories(rootPath);

        AtomicReference<FxFixture> ref = new AtomicReference<>();
        runOnFxAndWait(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        GradingWindowController.class.getResource("/ui/GradingWindow.fxml")
                );
                Parent root = loader.load();
                Scene scene = new Scene(root, 1200, 720);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                openedStages.add(stage);

                GradingWindowController controller = loader.getController();
                AssignmentsFile assignmentsFile = buildAssignmentsFile();
                Assignment assignment = buildAssignment();
                controller.init(assignmentsFile, assignment, rootPath, mappingsPath);

                ListView<String> studentList = (ListView<String>) getField(controller, "studentList");
                CodeArea reportEditor = (CodeArea) getField(controller, "reportEditor");
                Label statusLabel = (Label) getField(controller, "statusLabel");
                Button saveAndExportButton = (Button) getField(controller, "saveAndExportButton");

                ref.set(new FxFixture(controller, stage, studentList, reportEditor, statusLabel,
                        saveAndExportButton));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return ref.get();
    }

    private Assignment buildAssignment() {
        Assignment assignment = new Assignment();
        assignment.setCourseCode("CSC");
        assignment.setAssignmentCode("A1");
        assignment.setAssignmentName("Assignment One");

        RubricItemRef ref = new RubricItemRef();
        ref.setRubricItemId("ri_impl");
        ref.setPoints(10);
        Assignment.Rubric rubric = new Assignment.Rubric();
        rubric.setItems(List.of(ref));
        assignment.setRubric(rubric);
        return assignment;
    }

    private AssignmentsFile buildAssignmentsFile() {
        AssignmentsFile file = new AssignmentsFile();
        RubricItemDef def = new RubricItemDef();
        def.setId("ri_impl");
        def.setName("Implementation");
        HashMap<String, RubricItemDef> library = new HashMap<>();
        library.put("ri_impl", def);
        file.setRubricItemLibrary(library);
        file.setAssignments(List.of());
        return file;
    }

    private void waitUntilStatusContains(Label statusLabel, String expected) throws Exception {
        boolean satisfied = waitForFxCondition(() -> {
            String text = statusLabel.getText();
            return text != null && text.contains(expected);
        }, LONG_WAIT_MILLIS, SHORT_WAIT_MILLIS);
        if (satisfied) {
            return;
        }
        String finalText = runOnFxAndWaitResult(statusLabel::getText);
        assertTrue(finalText != null && finalText.contains(expected),
                "Expected status containing [" + expected + "] but was [" + finalText + "]");
    }

    private void waitUntilTrue(FxBooleanSupplier condition) throws Exception {
        assertTrue(waitForFxCondition(condition, LONG_WAIT_MILLIS, SHORT_WAIT_MILLIS),
                "Timed out waiting for condition.");
    }

    private void writeMappingFile(Path mappingFile,
                                  Map<String, String> mappings) throws Exception {
        Map<String, Map<String, String>> json = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            json.put(entry.getKey(), Map.of("repoPath", entry.getValue()));
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(mappingFile.toFile(), json);
    }

    private void writeCommentsJson(Path tempHome, int count) throws Exception {
        Path commentsPath = tempHome.resolve("Library")
                .resolve("Application Support")
                .resolve("GHCU2")
                .resolve("comments.json");
        Files.createDirectories(commentsPath.getParent());
        String json = count == 1
                ? """
                {
                  "schemaVersion": 1,
                  "comments": [
                    {
                      "commentId": "c1",
                      "assignmentKey": "CSC-A1",
                      "rubricItemId": "ri_impl",
                      "title": "Style",
                      "bodyMarkdown": "Fix style",
                      "pointsDeducted": 2
                    }
                  ]
                }
                """
                : """
                {
                  "schemaVersion": 1,
                  "comments": [
                    {
                      "commentId": "c1",
                      "assignmentKey": "CSC-A1",
                      "rubricItemId": "ri_impl",
                      "title": "Style",
                      "bodyMarkdown": "Fix style",
                      "pointsDeducted": 2
                    },
                    {
                      "commentId": "c2",
                      "assignmentKey": "CSC-A1",
                      "rubricItemId": "ri_impl",
                      "title": "Naming",
                      "bodyMarkdown": "Fix names",
                      "pointsDeducted": 1
                    }
                  ]
                }
                """;
        Files.writeString(commentsPath, json);
    }

    private void scheduleFxModalAction(FxBooleanSupplier action) {
        Thread thread = new Thread(() -> {
            try {
                waitForFxCondition(action, LONG_WAIT_MILLIS, MODAL_ACTION_POLL_MILLIS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // test helper best-effort only
            }
        }, "fx-modal-driver");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean waitForFxCondition(FxBooleanSupplier condition,
                                       long timeoutMillis,
                                       long pollIntervalMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Boolean ok = runOnFxAndWaitResult(condition::getAsBoolean);
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
            pausePolling(pollIntervalMillis);
        }
        return Boolean.TRUE.equals(runOnFxAndWaitResult(condition::getAsBoolean));
    }

    private void pausePolling(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private void clickButtonInStage(String stageTitle, String buttonText) {
        Stage stage = findShowingStageByTitle(stageTitle);
        if (stage == null || stage.getScene() == null) {
            return;
        }
        Node node = findButtonByText(stage.getScene().getRoot(), buttonText);
        if (node instanceof Button button) {
            button.fire();
        }
    }

    private boolean clickButtonInTopModal(String buttonText) {
        Stage top = findTopShowingModal();
        if (top == null || top.getScene() == null) {
            return false;
        }
        Node node = findButtonByText(top.getScene().getRoot(), buttonText);
        if (node instanceof Button button) {
            button.fire();
            return true;
        }
        return false;
    }

    private boolean closeTopModal() {
        Stage top = findTopShowingModal();
        if (top != null) {
            top.close();
            return true;
        }
        return false;
    }

    private Stage findTopShowingModal() {
        Stage top = null;
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && stage.isShowing()) {
                if (stage.getModality() != null && stage.getModality().name().contains("MODAL")) {
                    top = stage;
                }
            }
        }
        return top;
    }

    private Stage findShowingStageByTitle(String title) {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && stage.isShowing()
                    && title.equals(stage.getTitle())) {
                return stage;
            }
        }
        return null;
    }

    private Path expectedPreviewFile(Path tempHome, String previewTitle) {
        return tempHome.resolve("Library")
                .resolve("Application Support")
                .resolve("GHCU2")
                .resolve("preview")
                .resolve(previewTitle + "_preview.html");
    }

    private Node findButtonByText(Node root, String text) {
        if (root instanceof Button button && text.equals(button.getText())) {
            return button;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findButtonByText(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Path createRepoWithUpstream(Path repoDir, Path remoteDir) throws Exception {
        Files.createDirectories(remoteDir);
        runGit(remoteDir, "init", "--bare");
        createRepoWithCommit(repoDir);
        runGit(repoDir, "remote", "add", "origin", remoteDir.toString());
        runGit(repoDir, "push", "-u", "origin", "main");
        return repoDir;
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

    private void runGit(Path workingDir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, output);
    }

    private String readCurrentStudent(GradingWindowController controller) throws Exception {
        return (String) getField(controller, "currentStudent");
    }

    private void setDraftState(GradingWindowController controller,
                               String studentPackage,
                               String markdown,
                               int caretPosition,
                               boolean loadedFromDisk) throws Exception {
        Object session = getField(controller, "draftSessionService");
        Method saveEditorState = session.getClass().getDeclaredMethod(
                "saveEditorState",
                String.class,
                String.class,
                int.class
        );
        saveEditorState.setAccessible(true);
        saveEditorState.invoke(session, studentPackage, markdown, caretPosition);
        Method setLoadedFromDisk = session.getClass().getDeclaredMethod(
                "setLoadedFromDisk",
                String.class,
                boolean.class
        );
        setLoadedFromDisk.setAccessible(true);
        setLoadedFromDisk.invoke(session, studentPackage, loadedFromDisk);
    }

    private String readDraftMarkdown(GradingWindowController controller,
                                     String studentPackage) throws Exception {
        Object session = getField(controller, "draftSessionService");
        Method getMarkdown = session.getClass().getDeclaredMethod("getMarkdown", String.class);
        getMarkdown.setAccessible(true);
        return (String) getMarkdown.invoke(session, studentPackage);
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

    private void invokePrivateNoArgAsync(Object target, String methodName) {
        Platform.runLater(() -> invokePrivateNoArg(target, methodName));
    }

    private Object getField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
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

        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timed out waiting for JavaFX task.");
        }
        if (errorRef.get() != null) {
            throw new RuntimeException(errorRef.get());
        }
    }

    private <T> T runOnFxAndWaitResult(FxSupplier<T> supplier) throws Exception {
        AtomicReference<T> valueRef = new AtomicReference<>();
        runOnFxAndWait(() -> valueRef.set(supplier.get()));
        return valueRef.get();
    }

    private record FxFixture(
            GradingWindowController controller,
            Stage stage,
            ListView<String> studentList,
            CodeArea reportEditor,
            Label statusLabel,
            Button saveAndExportButton
    ) { }

    @FunctionalInterface
    private interface FxTask {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface FxBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}

package ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
                TextArea logTextArea =
                        (TextArea) getField(controller, "logTextArea");
                @SuppressWarnings("unchecked")
                ComboBox<Assignment> assignmentCombo =
                        (ComboBox<Assignment>) getField(controller, "assignmentCombo");

                ref.set(new FxFixture(
                        controller,
                        cloneCommandField,
                        rootPathField,
                        checkstyleUrlField,
                        logTextArea,
                        assignmentCombo
                ));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return ref.get();
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
                             TextArea logTextArea,
                             ComboBox<Assignment> assignmentCombo) {
    }

    @FunctionalInterface
    private interface FxTask {
        void run() throws Exception;
    }
}

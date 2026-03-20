package ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import model.Assignment;
import model.AssignmentsFile;
import model.Comments.CommentDef;
import model.Comments.CommentsLibrary;
import model.Comments.CommentsStore;
import model.RubricItemDef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentManagerControllerTest {

    private static final long fxTimeoutMillis = 5000L;
    private static boolean fxRuntimeAvailable = false;

    @BeforeAll
    static void initJavaFxRuntime() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
            fxRuntimeAvailable = true;
        } catch (IllegalStateException alreadyStarted) {
            fxRuntimeAvailable = true;
            latch.countDown();
        } catch (Throwable startupFailure) {
            fxRuntimeAvailable = false;
            latch.countDown();
        }
        assertTrue(latch.await(fxTimeoutMillis, TimeUnit.MILLISECONDS));
    }

    @Test
    void init_saveButtonBinding_requiresIdAndRubric(@TempDir Path tmp) throws Exception {
        requireFxRuntime();
        FxFixture fixture = createFixture(tmp, emptyCommentsLibrary());

        runOnFxAndWait(() -> fixture.idField.setText("c1"));
        assertTrue(runOnFxAndWaitResult(() -> fixture.saveButton.isDisable()));

        runOnFxAndWait(() -> fixture.rubricCombo.setValue("ri_code"));
        assertFalse(runOnFxAndWaitResult(() -> fixture.saveButton.isDisable()));
    }

    @Test
    void onSave_newComment_addsToLibrary_andPersists(@TempDir Path tmp) throws Exception {
        requireFxRuntime();
        CommentsLibrary library = emptyCommentsLibrary();
        FxFixture fixture = createFixture(tmp, library);

        runOnFxAndWait(() -> {
            fixture.idField.setText("c001");
            fixture.titleField.setText("Title");
            fixture.rubricCombo.setValue("ri_code");
            fixture.pointsSpinner.getValueFactory().setValue(7);
            fixture.bodyArea.setText("Body markdown");
            invokeNoArg(fixture.controller, "onSave");
        });

        assertEquals(1, library.getComments().size());
        CommentDef saved = library.getComments().getFirst();
        assertEquals("c001", saved.getCommentId());
        assertEquals("CSC-A1", saved.getAssignmentKey());
        assertEquals("ri_code", saved.getRubricItemId());
        assertEquals("Title", saved.getTitle());
        assertEquals("Body markdown", saved.getBodyMarkdown());
        assertEquals(7, saved.getPointsDeducted());
        assertEquals(1, fixture.store.saveCalls);
        assertEquals(fixture.commentsPath, fixture.store.lastPath);
    }

    @Test
    void onSave_editExisting_updatesSameComment_andPersists(@TempDir Path tmp) throws Exception {
        requireFxRuntime();
        CommentsLibrary library = emptyCommentsLibrary();
        CommentDef existing = new CommentDef();
        existing.setCommentId("c001");
        existing.setAssignmentKey("A1");
        existing.setRubricItemId("ri_code");
        existing.setTitle("Old title");
        existing.setBodyMarkdown("Old body");
        existing.setPointsDeducted(2);
        library.getComments().add(existing);

        FxFixture fixture = createFixture(tmp, library);

        runOnFxAndWait(() -> {
            fixture.commentList.getSelectionModel().select(existing);
            fixture.titleField.setText("Updated title");
            fixture.bodyArea.setText("Updated body");
            fixture.pointsSpinner.getValueFactory().setValue(4);
            invokeNoArg(fixture.controller, "onSave");
        });

        assertEquals(1, library.getComments().size());
        assertEquals("Updated title", existing.getTitle());
        assertEquals("Updated body", existing.getBodyMarkdown());
        assertEquals(4, existing.getPointsDeducted());
        assertEquals(1, fixture.store.saveCalls);
    }

    @Test
    void onDelete_selectedComment_removesAndPersists(@TempDir Path tmp) throws Exception {
        requireFxRuntime();
        CommentsLibrary library = emptyCommentsLibrary();
        CommentDef existing = new CommentDef();
        existing.setCommentId("c001");
        existing.setAssignmentKey("A1");
        existing.setRubricItemId("ri_code");
        existing.setTitle("Title");
        existing.setBodyMarkdown("Body");
        existing.setPointsDeducted(3);
        library.getComments().add(existing);

        FxFixture fixture = createFixture(tmp, library);

        runOnFxAndWait(() -> {
            fixture.commentList.getSelectionModel().select(existing);
            invokeNoArg(fixture.controller, "onDelete");
        });

        assertTrue(library.getComments().isEmpty());
        assertEquals(1, fixture.store.saveCalls);
    }

    @Test
    void onDelete_noSelection_doesNothing(@TempDir Path tmp) throws Exception {
        requireFxRuntime();
        FxFixture fixture = createFixture(tmp, emptyCommentsLibrary());

        runOnFxAndWait(() -> invokeNoArg(fixture.controller, "onDelete"));

        assertEquals(0, fixture.store.saveCalls);
    }

    private FxFixture createFixture(Path tmp, CommentsLibrary library) throws Exception {
        CommentManagerController controller = new CommentManagerController();
        SpyCommentsStore store = new SpyCommentsStore();
        Path commentsPath = tmp.resolve("comments.json");

        Assignment assignment = new Assignment();
        assignment.setCourseCode("CSC");
        assignment.setAssignmentCode("A1");
        assignment.setAssignmentName("Assignment");

        AssignmentsFile assignmentsFile = new AssignmentsFile();
        assignmentsFile.setAssignments(new ArrayList<>());
        HashMap<String, RubricItemDef> rubricLibrary = new HashMap<>();
        RubricItemDef rubric = new RubricItemDef();
        rubric.setName("Code");
        rubricLibrary.put("ri_code", rubric);
        assignmentsFile.setRubricItemLibrary(rubricLibrary);

        TextField searchField = new TextField();
        ListView<CommentDef> commentList = new ListView<>();
        TextField idField = new TextField();
        TextField titleField = new TextField();
        ComboBox<String> rubricCombo = new ComboBox<>();
        Spinner<Integer> pointsSpinner = new Spinner<>();
        TextArea bodyArea = new TextArea();
        Button saveButton = new Button();

        runOnFxAndWait(() -> {
            setField(controller, "searchField", searchField);
            setField(controller, "commentList", commentList);
            setField(controller, "idField", idField);
            setField(controller, "titleField", titleField);
            setField(controller, "rubricCombo", rubricCombo);
            setField(controller, "pointsSpinner", pointsSpinner);
            setField(controller, "bodyArea", bodyArea);
            setField(controller, "saveButton", saveButton);
            controller.init(assignment, library, assignmentsFile, store, commentsPath);
        });

        return new FxFixture(
                controller,
                store,
                commentsPath,
                commentList,
                idField,
                titleField,
                rubricCombo,
                pointsSpinner,
                bodyArea,
                saveButton
        );
    }

    private CommentsLibrary emptyCommentsLibrary() {
        CommentsLibrary library = new CommentsLibrary();
        library.setSchemaVersion(1);
        library.setComments(new ArrayList<>());
        return library;
    }

    private void requireFxRuntime() {
        Assumptions.assumeTrue(fxRuntimeAvailable, "JavaFX runtime is unavailable");
    }

    private static void runOnFxAndWait(FxRunnable runnable) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(fxTimeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("Timed out waiting for JavaFX task.");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }

    private static <T> T runOnFxAndWaitResult(FxSupplier<T> supplier) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFxAndWait(() -> result.set(supplier.get()));
        return result.get();
    }

    private void invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    private interface FxRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }

    private static class SpyCommentsStore extends CommentsStore {
        private int saveCalls = 0;
        private Path lastPath;

        @Override
        public void save(Path path, CommentsLibrary file) {
            saveCalls++;
            lastPath = path;
            assertNotNull(file);
        }
    }

    private record FxFixture(CommentManagerController controller,
                             SpyCommentsStore store,
                             Path commentsPath,
                             ListView<CommentDef> commentList,
                             TextField idField,
                             TextField titleField,
                             ComboBox<String> rubricCombo,
                             Spinner<Integer> pointsSpinner,
                             TextArea bodyArea,
                             Button saveButton) {
    }
}

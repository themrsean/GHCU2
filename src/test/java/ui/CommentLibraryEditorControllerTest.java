package ui;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import model.Assignment;
import model.Comments.CommentDef;
import model.Comments.CommentsLibrary;
import model.RubricItemDef;
import model.RubricItemRef;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentLibraryEditorControllerTest {

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
    void init_loadsOnlyRowsForCurrentAssignment() throws Exception {
        requireFxRuntime();
        CommentsLibrary library = commentsLibrary(
                comment("c1", "CSC-A1", "ri_code", "A1 Title", "A1 Text", 2),
                comment("c2", "CSC-A2", "ri_code", "A2 Title", "A2 Text", 3)
        );
        FxFixture fixture = createFixture(assignment("CSC", "A1"), rubricLibrary(), library);

        assertEquals(1, fixture.rows.size());
        assertEquals("c1", fixture.rows.getFirst().getId());
    }

    @Test
    void onClose_validationFailsForDuplicateCommentId() throws Exception {
        requireFxRuntime();
        CommentsLibrary library = commentsLibrary();
        FxFixture fixture = createFixture(assignment("CSC", "A1"), rubricLibrary(), library);

        runOnFxAndWait(() -> {
            fixture.rows.clear();
            fixture.rows.add(row("dup", "CSC-A1", "ri_code", 1, "Title 1", "Body 1"));
            fixture.rows.add(row("dup", "CSC-A1", "ri_tests", 2, "Title 2", "Body 2"));
            invokeNoArg(fixture.controller, "onClose");
        });

        assertEquals("Duplicate comment ID: dup", fixture.errorLabel.getText());
        assertEquals(0, library.getComments().size());
    }

    @Test
    void onClose_validationFailsForRubricOutsideAssignment() throws Exception {
        requireFxRuntime();
        CommentsLibrary library = commentsLibrary();
        FxFixture fixture = createFixture(assignment("CSC", "A1"), rubricLibrary(), library);

        runOnFxAndWait(() -> {
            fixture.rows.clear();
            fixture.rows.add(row(
                    "c1",
                    "CSC-A1",
                    "ri_not_in_assignment",
                    1,
                    "Title",
                    "Body"
            ));
            invokeNoArg(fixture.controller, "onClose");
        });

        assertTrue(fixture.errorLabel.getText().contains("Rubric item not in this assignment"));
        assertEquals(0, library.getComments().size());
    }

    @Test
    void applyToModel_replacesOnlyCurrentAssignmentComments() throws Exception {
        requireFxRuntime();
        CommentsLibrary library = commentsLibrary(
                comment("keep", "CSC-A2", "ri_code", "Keep", "Keep body", 5),
                comment("replace", "CSC-A1", "ri_code", "Old", "Old body", 1)
        );
        FxFixture fixture = createFixture(assignment("CSC", "A1"), rubricLibrary(), library);

        runOnFxAndWait(() -> {
            fixture.rows.clear();
            fixture.rows.add(row("new1", "CSC-A1", "ri_code", 4, "New 1", "Body 1"));
            fixture.rows.add(row("new2", "CSC-A1", "ri_tests", 2, "New 2", "Body 2"));
            invokeNoArg(fixture.controller, "applyToModel");
        });

        assertEquals(3, library.getComments().size());
        Map<String, CommentDef> byId = new HashMap<>();
        for (CommentDef def : library.getComments()) {
            byId.put(def.getCommentId(), def);
        }
        assertTrue(byId.containsKey("keep"));
        assertTrue(byId.containsKey("new1"));
        assertTrue(byId.containsKey("new2"));
        assertEquals("CSC-A2", byId.get("keep").getAssignmentKey());
        assertEquals("CSC-A1", byId.get("new1").getAssignmentKey());
        assertEquals("CSC-A1", byId.get("new2").getAssignmentKey());
    }

    @Test
    void applyToModel_sortsOutputByAssignmentRubricAndCommentId() throws Exception {
        requireFxRuntime();
        CommentsLibrary library = commentsLibrary();
        FxFixture fixture = createFixture(assignment("CSC", "A1"), rubricLibrary(), library);

        runOnFxAndWait(() -> {
            fixture.rows.clear();
            fixture.rows.add(row("b", "CSC-A1", "ri_tests", 1, "T2", "B2"));
            fixture.rows.add(row("a", "CSC-A1", "ri_code", 1, "T1", "B1"));
            fixture.rows.add(row("c", "CSC-A1", "ri_code", 1, "T3", "B3"));
            invokeNoArg(fixture.controller, "applyToModel");
        });

        List<String> ids = new ArrayList<>();
        for (CommentDef def : library.getComments()) {
            ids.add(def.getCommentId());
        }
        assertEquals(List.of("a", "c", "b"), ids);
    }

    private FxFixture createFixture(Assignment assignment,
                                    Map<String, RubricItemDef> rubricLibrary,
                                    CommentsLibrary commentsLibrary) throws Exception {
        CommentLibraryEditorController controller = new CommentLibraryEditorController();
        Label titleLabel = new Label();
        TableView<CommentLibraryEditorController.CommentRow> commentTable = new TableView<>();
        TableColumn<CommentLibraryEditorController.CommentRow, String> rubricItemCol =
                new TableColumn<>();
        TableColumn<CommentLibraryEditorController.CommentRow, Integer> pointsCol =
                new TableColumn<>();
        TableColumn<CommentLibraryEditorController.CommentRow, String> titleCol = new TableColumn<>();
        TableColumn<CommentLibraryEditorController.CommentRow, String> textCol = new TableColumn<>();
        Label errorLabel = new Label();

        runOnFxAndWait(() -> {
            setField(controller, "titleLabel", titleLabel);
            setField(controller, "commentTable", commentTable);
            setField(controller, "rubricItemCol", rubricItemCol);
            setField(controller, "pointsCol", pointsCol);
            setField(controller, "titleCol", titleCol);
            setField(controller, "textCol", textCol);
            setField(controller, "errorLabel", errorLabel);
            controller.init(assignment, rubricLibrary, commentsLibrary);
        });

        @SuppressWarnings("unchecked")
        ObservableList<CommentLibraryEditorController.CommentRow> rows =
                (ObservableList<CommentLibraryEditorController.CommentRow>) getField(
                        controller,
                        "rows"
                );

        return new FxFixture(controller, errorLabel, rows);
    }

    private Assignment assignment(String courseCode, String assignmentCode) {
        Assignment assignment = new Assignment();
        assignment.setCourseCode(courseCode);
        assignment.setAssignmentCode(assignmentCode);
        assignment.setAssignmentName("Assignment");
        Assignment.Rubric rubric = new Assignment.Rubric();
        ArrayList<RubricItemRef> items = new ArrayList<>();
        RubricItemRef code = new RubricItemRef();
        code.setRubricItemId("ri_code");
        code.setPoints(70);
        items.add(code);
        RubricItemRef tests = new RubricItemRef();
        tests.setRubricItemId("ri_tests");
        tests.setPoints(30);
        items.add(tests);
        rubric.setItems(items);
        assignment.setRubric(rubric);
        return assignment;
    }

    private Map<String, RubricItemDef> rubricLibrary() {
        Map<String, RubricItemDef> map = new HashMap<>();
        RubricItemDef code = new RubricItemDef();
        code.setName("Code");
        map.put("ri_code", code);
        RubricItemDef tests = new RubricItemDef();
        tests.setName("Tests");
        map.put("ri_tests", tests);
        return map;
    }

    private CommentsLibrary commentsLibrary(CommentDef... defs) {
        CommentsLibrary library = new CommentsLibrary();
        library.setSchemaVersion(1);
        ArrayList<CommentDef> comments = new ArrayList<>();
        for (CommentDef def : defs) {
            comments.add(def);
        }
        library.setComments(comments);
        return library;
    }

    private CommentDef comment(String id,
                               String assignmentKey,
                               String rubricItemId,
                               String title,
                               String body,
                               int points) {
        CommentDef def = new CommentDef();
        def.setCommentId(id);
        def.setAssignmentKey(assignmentKey);
        def.setRubricItemId(rubricItemId);
        def.setTitle(title);
        def.setBodyMarkdown(body);
        def.setPointsDeducted(points);
        return def;
    }

    private CommentLibraryEditorController.CommentRow row(String id,
                                                          String assignmentKey,
                                                          String rubricItemId,
                                                          int points,
                                                          String title,
                                                          String text) {
        CommentLibraryEditorController.CommentRow row =
                new CommentLibraryEditorController.CommentRow();
        row.setId(id);
        row.setAssignmentKey(assignmentKey);
        row.setRubricItemId(rubricItemId);
        row.setPoints(points);
        row.setTitle(title);
        row.setText(text);
        return row;
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

    private Object getField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    @FunctionalInterface
    private interface FxRunnable {
        void run() throws Exception;
    }

    private record FxFixture(CommentLibraryEditorController controller,
                             Label errorLabel,
                             ObservableList<CommentLibraryEditorController.CommentRow> rows) {
    }
}

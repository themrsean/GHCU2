package ui;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import model.Assignment;
import model.Comments.CommentDef;
import model.Comments.CommentsLibrary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentPickersControllerTest {

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
    void commentPicker_init_filtersToAssignmentAndSortsByCommentId() throws Exception {
        requireFxRuntime();
        CommentPickerController controller = new CommentPickerController();
        ListView<CommentDef> commentList = new ListView<>();
        FxCommentPickerFixture fixture = createCommentPickerFixture(controller, commentList);

        CommentsLibrary library = new CommentsLibrary();
        library.setSchemaVersion(1);
        ArrayList<CommentDef> defs = new ArrayList<>();
        defs.add(comment("z2", "CSC-A1", "ri_code", "Last", "Body"));
        defs.add(comment("a1", "CSC-A1", "ri_code", "First", "Body"));
        defs.add(comment("x9", "CSC-A2", "ri_code", "Other Assignment", "Body"));
        library.setComments(defs);

        runOnFxAndWait(() -> controller.init(assignment("CSC", "A1"), library));

        List<String> ids = runOnFxAndWaitResult(() -> {
            List<String> out = new ArrayList<>();
            for (CommentDef def : commentList.getItems()) {
                out.add(def.getCommentId());
            }
            return out;
        });

        assertEquals(List.of("a1", "z2"), ids);
        assertEquals("a1", runOnFxAndWaitResult(() ->
                commentList.getSelectionModel().getSelectedItem().getCommentId()));
        assertEquals("", fixture.errorLabel.getText());
    }

    @Test
    void commentPicker_onApply_withoutSelection_setsError() throws Exception {
        requireFxRuntime();
        CommentPickerController controller = new CommentPickerController();
        ListView<CommentDef> commentList = new ListView<>();
        FxCommentPickerFixture fixture = createCommentPickerFixture(controller, commentList);

        CommentsLibrary library = new CommentsLibrary();
        library.setSchemaVersion(1);
        library.setComments(new ArrayList<>());

        runOnFxAndWait(() -> {
            controller.init(assignment("CSC", "A1"), library);
            invokeNoArg(controller, "onApply");
        });

        assertFalse(controller.isApplied());
        assertEquals("Select a comment.", fixture.errorLabel.getText());
    }

    @Test
    void removeCommentPicker_init_sortsByTitleAndSelectsFirst() throws Exception {
        requireFxRuntime();
        RemoveCommentPickerController controller = new RemoveCommentPickerController();
        ListView<RemoveCommentPickerController.InjectedCommentRef> commentList = new ListView<>();
        createRemovePickerFixture(controller, commentList);

        List<RemoveCommentPickerController.InjectedCommentRef> injected = List.of(
                new RemoveCommentPickerController.InjectedCommentRef("a2", "Zulu", "ri_code", 2),
                new RemoveCommentPickerController.InjectedCommentRef("a1", "Alpha", "ri_tests", 1)
        );

        runOnFxAndWait(() -> controller.init(injected));

        List<String> titles = runOnFxAndWaitResult(() -> {
            List<String> out = new ArrayList<>();
            for (RemoveCommentPickerController.InjectedCommentRef ref : commentList.getItems()) {
                out.add(ref.getTitle());
            }
            return out;
        });

        assertEquals(List.of("Alpha", "Zulu"), titles);
        assertEquals("Alpha", runOnFxAndWaitResult(() ->
                commentList.getSelectionModel().getSelectedItem().getTitle()));
    }

    @Test
    void removeCommentPicker_onRemove_withoutSelection_setsError() throws Exception {
        requireFxRuntime();
        RemoveCommentPickerController controller = new RemoveCommentPickerController();
        ListView<RemoveCommentPickerController.InjectedCommentRef> commentList = new ListView<>();
        FxRemovePickerFixture fixture = createRemovePickerFixture(controller, commentList);

        runOnFxAndWait(() -> {
            controller.init(new ArrayList<>());
            invokeNoArg(controller, "onRemove");
        });

        assertFalse(controller.isRemoved());
        assertEquals("Select a comment.", fixture.errorLabel.getText());
    }

    private FxCommentPickerFixture createCommentPickerFixture(CommentPickerController controller,
                                                              ListView<CommentDef> commentList)
            throws Exception {
        Label assignmentLabel = new Label();
        TextField searchField = new TextField();
        TextArea previewArea = new TextArea();
        Label errorLabel = new Label();

        runOnFxAndWait(() -> {
            setField(controller, "assignmentLabel", assignmentLabel);
            setField(controller, "searchField", searchField);
            setField(controller, "commentList", commentList);
            setField(controller, "previewArea", previewArea);
            setField(controller, "errorLabel", errorLabel);
        });

        return new FxCommentPickerFixture(errorLabel);
    }

    private FxRemovePickerFixture createRemovePickerFixture(
            RemoveCommentPickerController controller,
            ListView<RemoveCommentPickerController.InjectedCommentRef> commentList
    ) throws Exception {
        TextField searchField = new TextField();
        TextArea previewArea = new TextArea();
        Label errorLabel = new Label();

        runOnFxAndWait(() -> {
            setField(controller, "searchField", searchField);
            setField(controller, "commentList", commentList);
            setField(controller, "previewArea", previewArea);
            setField(controller, "errorLabel", errorLabel);
        });

        return new FxRemovePickerFixture(errorLabel);
    }

    private Assignment assignment(String courseCode, String assignmentCode) {
        Assignment assignment = new Assignment();
        assignment.setCourseCode(courseCode);
        assignment.setAssignmentCode(assignmentCode);
        assignment.setAssignmentName("Assignment");
        return assignment;
    }

    private CommentDef comment(String id,
                               String assignmentKey,
                               String rubricItemId,
                               String title,
                               String body) {
        CommentDef def = new CommentDef();
        def.setCommentId(id);
        def.setAssignmentKey(assignmentKey);
        def.setRubricItemId(rubricItemId);
        def.setTitle(title);
        def.setBodyMarkdown(body);
        def.setPointsDeducted(1);
        return def;
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

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }

    private record FxCommentPickerFixture(Label errorLabel) {
    }

    private record FxRemovePickerFixture(Label errorLabel) {
    }
}

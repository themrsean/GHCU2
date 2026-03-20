package ui;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import model.Assignment;
import model.AssignmentsFile;
import model.RubricItemDef;
import model.RubricItemRef;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RubricLibraryEditorControllerTest {

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
    void onDeleteSelected_referencedRubricItem_blocksDeleteAndSetsError() throws Exception {
        requireFxRuntime();
        AssignmentsFile assignmentsFile = buildAssignmentsFileWithReference();
        FxFixture fixture = createFixture(assignmentsFile);

        runOnFxAndWait(() -> {
            fixture.rubricTable.getSelectionModel().select(findRowById(fixture.rows, "ri_code"));
            invokeNoArg(fixture.controller, "onDeleteSelected");
        });

        assertEquals(2, fixture.rows.size());
        assertTrue(fixture.errorLabel.getText().contains("Cannot delete rubric item"));
    }

    @Test
    void onDeleteSelected_unreferencedRubricItem_removesRow() throws Exception {
        requireFxRuntime();
        AssignmentsFile assignmentsFile = buildAssignmentsFileWithReference();
        FxFixture fixture = createFixture(assignmentsFile);

        runOnFxAndWait(() -> {
            fixture.rubricTable.getSelectionModel().select(findRowById(fixture.rows, "ri_style"));
            invokeNoArg(fixture.controller, "onDeleteSelected");
        });

        assertEquals(1, fixture.rows.size());
        assertEquals("ri_code", fixture.rows.getFirst().getId());
    }

    @Test
    void onClose_duplicateRubricId_setsValidationError() throws Exception {
        requireFxRuntime();
        AssignmentsFile assignmentsFile = buildAssignmentsFileWithoutReferences();
        FxFixture fixture = createFixture(assignmentsFile);

        runOnFxAndWait(() -> fixture.rows.get(1).setId("ri_code"));
        runOnFxAndWait(() -> invokeNoArg(fixture.controller, "onClose"));

        assertEquals("Duplicate rubric item ID: ri_code", fixture.errorLabel.getText());
    }

    @Test
    void onClose_multipleCheckstyleItems_setsValidationError() throws Exception {
        requireFxRuntime();
        AssignmentsFile assignmentsFile = buildAssignmentsFileWithoutReferences();
        FxFixture fixture = createFixture(assignmentsFile);

        runOnFxAndWait(() -> {
            fixture.rows.get(0).setCheckstyleItem(true);
            fixture.rows.get(1).setCheckstyleItem(true);
            invokeNoArg(fixture.controller, "onClose");
        });

        assertEquals("Only one rubric item may be marked as Checkstyle.",
                fixture.errorLabel.getText());
    }

    @Test
    void applyToModel_writesEditedRowsBackIntoLibrary() throws Exception {
        requireFxRuntime();
        AssignmentsFile assignmentsFile = buildAssignmentsFileWithoutReferences();
        FxFixture fixture = createFixture(assignmentsFile);

        runOnFxAndWait(() -> {
            fixture.rows.get(0).setName("Code Quality");
            fixture.rows.get(0).setCheckstyleItem(true);
        });

        runOnFxAndWait(() -> invokeNoArg(fixture.controller, "applyToModel"));

        Map<String, RubricItemDef> out = assignmentsFile.getRubricItemLibrary();
        assertEquals(2, out.size());
        assertEquals("Code Quality", out.get("ri_code").getName());
        assertTrue(out.get("ri_code").isCheckstyleItem());
    }

    private FxFixture createFixture(AssignmentsFile assignmentsFile) throws Exception {
        RubricLibraryEditorController controller = new RubricLibraryEditorController();
        TableView<RubricLibraryEditorController.RubricRow> rubricTable = new TableView<>();
        TableColumn<RubricLibraryEditorController.RubricRow, String> idCol = new TableColumn<>();
        TableColumn<RubricLibraryEditorController.RubricRow, String> nameCol = new TableColumn<>();
        TableColumn<RubricLibraryEditorController.RubricRow, Boolean> checkstyleCol =
                new TableColumn<>();
        Label errorLabel = new Label();

        runOnFxAndWait(() -> {
            setField(controller, "rubricTable", rubricTable);
            setField(controller, "idCol", idCol);
            setField(controller, "nameCol", nameCol);
            setField(controller, "checkstyleCol", checkstyleCol);
            setField(controller, "errorLabel", errorLabel);
            controller.init(assignmentsFile);
        });

        @SuppressWarnings("unchecked")
        ObservableList<RubricLibraryEditorController.RubricRow> rows =
                (ObservableList<RubricLibraryEditorController.RubricRow>) getField(
                        controller,
                        "rows"
                );

        return new FxFixture(controller, rubricTable, errorLabel, rows);
    }

    private AssignmentsFile buildAssignmentsFileWithReference() {
        AssignmentsFile file = buildAssignmentsFileWithoutReferences();

        Assignment assignment = new Assignment();
        assignment.setCourseCode("CSC");
        assignment.setAssignmentCode("A1");
        assignment.setAssignmentName("Assignment");
        Assignment.Rubric rubric = new Assignment.Rubric();
        ArrayList<RubricItemRef> items = new ArrayList<>();
        RubricItemRef ref = new RubricItemRef();
        ref.setRubricItemId("ri_code");
        ref.setPoints(100);
        items.add(ref);
        rubric.setItems(items);
        assignment.setRubric(rubric);

        ArrayList<Assignment> assignments = new ArrayList<>();
        assignments.add(assignment);
        file.setAssignments(assignments);

        return file;
    }

    private AssignmentsFile buildAssignmentsFileWithoutReferences() {
        AssignmentsFile file = new AssignmentsFile();
        file.setAssignments(new ArrayList<>());

        HashMap<String, RubricItemDef> lib = new HashMap<>();
        RubricItemDef code = new RubricItemDef();
        code.setName("Code");
        code.setCheckstyleItem(false);
        lib.put("ri_code", code);

        RubricItemDef style = new RubricItemDef();
        style.setName("Style");
        style.setCheckstyleItem(false);
        lib.put("ri_style", style);

        file.setRubricItemLibrary(lib);
        return file;
    }

    private RubricLibraryEditorController.RubricRow findRowById(
            ObservableList<RubricLibraryEditorController.RubricRow> rows,
            String id
    ) {
        for (RubricLibraryEditorController.RubricRow row : rows) {
            if (id.equals(row.getId())) {
                return row;
            }
        }
        return null;
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

    private record FxFixture(RubricLibraryEditorController controller,
                             TableView<RubricLibraryEditorController.RubricRow> rubricTable,
                             Label errorLabel,
                             ObservableList<RubricLibraryEditorController.RubricRow> rows) {
    }
}

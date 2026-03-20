package ui;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentEditorControllerTest {

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
    void validate_rejectsRubricTotalNot100() throws Exception {
        requireFxRuntime();
        FxFixture fixture = createFixture(buildAssignmentsFile(), null);

        runOnFxAndWait(() -> {
            fixture.courseCodeField.setText("CSC");
            fixture.assignmentCodeField.setText("A1");
            fixture.assignmentNameField.setText("Assignment");
            fixture.rubricItems.clear();
            fixture.rubricItems.add(rubric("ri_code", 60));
            fixture.rubricItems.add(rubric("ri_tests", 30));
        });

        boolean valid = runOnFxAndWaitResult(() ->
                (Boolean) invokeNoArg(fixture.controller, "validate"));

        assertEquals(false, valid);
        assertTrue(fixture.errorLabel.getText().contains("Rubric total must equal exactly 100"));
    }

    @Test
    void validate_rejectsDuplicateRubricIds() throws Exception {
        requireFxRuntime();
        FxFixture fixture = createFixture(buildAssignmentsFile(), null);

        runOnFxAndWait(() -> {
            fixture.courseCodeField.setText("CSC");
            fixture.assignmentCodeField.setText("A1");
            fixture.assignmentNameField.setText("Assignment");
            fixture.rubricItems.clear();
            fixture.rubricItems.add(rubric("ri_code", 50));
            fixture.rubricItems.add(rubric("ri_code", 50));
        });

        boolean valid = runOnFxAndWaitResult(() ->
                (Boolean) invokeNoArg(fixture.controller, "validate"));

        assertEquals(false, valid);
        assertEquals("Duplicate rubric item id: ri_code", fixture.errorLabel.getText());
    }

    @Test
    void validate_acceptsKnownRubricIdsAtTotal100() throws Exception {
        requireFxRuntime();
        FxFixture fixture = createFixture(buildAssignmentsFile(), null);

        runOnFxAndWait(() -> {
            fixture.courseCodeField.setText("CSC");
            fixture.assignmentCodeField.setText("A1");
            fixture.assignmentNameField.setText("Assignment");
            fixture.rubricItems.clear();
            fixture.rubricItems.add(rubric("ri_code", 70));
            fixture.rubricItems.add(rubric("ri_tests", 30));
        });

        boolean valid = runOnFxAndWaitResult(() ->
                (Boolean) invokeNoArg(fixture.controller, "validate"));

        assertEquals(true, valid);
        assertEquals("", fixture.errorLabel.getText());
    }

    @Test
    void init_existingAssignment_normalizesExpectedFilesToBasenamesAndSorts() throws Exception {
        requireFxRuntime();
        Assignment existing = new Assignment();
        existing.setCourseCode("CSC");
        existing.setAssignmentCode("A1");
        existing.setAssignmentName("Assignment");
        existing.setExpectedFiles(new ArrayList<>(List.of(
                "src/main/java/Foo.java",
                "Foo.java",
                "z/View.fxml",
                "View.fxml"
        )));
        Assignment.Rubric rubric = new Assignment.Rubric();
        rubric.setItems(new ArrayList<>(List.of(rubric("ri_code", 100))));
        existing.setRubric(rubric);

        FxFixture fixture = createFixture(buildAssignmentsFile(), existing);

        List<String> items = runOnFxAndWaitResult(() ->
                new ArrayList<>(fixture.expectedFilesListView.getItems()));
        assertEquals(List.of("Foo.java", "View.fxml"), items);
    }

    @Test
    void saveFromUi_trimsFieldsAndSortsExpectedAndRubricEntries() throws Exception {
        requireFxRuntime();
        FxFixture fixture = createFixture(buildAssignmentsFile(), null);

        runOnFxAndWait(() -> {
            fixture.courseCodeField.setText(" CSC ");
            fixture.assignmentCodeField.setText(" A1 ");
            fixture.assignmentNameField.setText(" Name ");
            fixture.expectedFiles.clear();
            fixture.expectedFiles.add(" Zeta.java ");
            fixture.expectedFiles.add("Alpha.java");
            fixture.rubricItems.clear();
            fixture.rubricItems.add(rubric(" ri_tests ", 30));
            fixture.rubricItems.add(rubric("ri_code", 70));
            fixture.rubricItems.add(rubric(" ", 99));
        });

        runOnFxAndWait(() -> invokeNoArg(fixture.controller, "saveFromUi"));

        Assignment result = fixture.controller.getResult();
        assertEquals("CSC", result.getCourseCode());
        assertEquals("A1", result.getAssignmentCode());
        assertEquals("Name", result.getAssignmentName());
        assertEquals(List.of("Alpha.java", "Zeta.java"), result.getExpectedFiles());
        assertEquals(2, result.getRubric().getItems().size());
        assertEquals("ri_code", result.getRubric().getItems().get(0).getRubricItemId());
        assertEquals("ri_tests", result.getRubric().getItems().get(1).getRubricItemId());
    }

    private FxFixture createFixture(AssignmentsFile assignmentsFile,
                                    Assignment existingOrNull) throws Exception {
        AssignmentEditorController controller = new AssignmentEditorController();

        TextField courseCodeField = new TextField();
        TextField assignmentCodeField = new TextField();
        TextField assignmentNameField = new TextField();
        ListView<String> expectedFilesListView = new ListView<>();
        TableView<RubricItemRef> rubricTable = new TableView<>();
        TableColumn<RubricItemRef, String> rubricItemIdCol = new TableColumn<>();
        TableColumn<RubricItemRef, Integer> pointsCol = new TableColumn<>();
        Button addRubricRowButton = new Button();
        Button removeRubricRowButton = new Button();
        Label totalLabel = new Label();
        Label errorLabel = new Label();

        runOnFxAndWait(() -> {
            setField(controller, "courseCodeField", courseCodeField);
            setField(controller, "assignmentCodeField", assignmentCodeField);
            setField(controller, "assignmentNameField", assignmentNameField);
            setField(controller, "expectedFilesListView", expectedFilesListView);
            setField(controller, "rubricTable", rubricTable);
            setField(controller, "rubricItemIdCol", rubricItemIdCol);
            setField(controller, "pointsCol", pointsCol);
            setField(controller, "addRubricRowButton", addRubricRowButton);
            setField(controller, "removeRubricRowButton", removeRubricRowButton);
            setField(controller, "totalLabel", totalLabel);
            setField(controller, "errorLabel", errorLabel);
            controller.init(assignmentsFile, existingOrNull);
        });

        @SuppressWarnings("unchecked")
        ObservableList<String> expectedFiles =
                (ObservableList<String>) getField(controller, "expectedFiles");
        @SuppressWarnings("unchecked")
        ObservableList<RubricItemRef> rubricItems =
                (ObservableList<RubricItemRef>) getField(controller, "rubricItems");

        return new FxFixture(
                controller,
                courseCodeField,
                assignmentCodeField,
                assignmentNameField,
                expectedFilesListView,
                errorLabel,
                expectedFiles,
                rubricItems
        );
    }

    private AssignmentsFile buildAssignmentsFile() {
        AssignmentsFile file = new AssignmentsFile();
        file.setAssignments(new ArrayList<>());
        HashMap<String, RubricItemDef> library = new HashMap<>();
        RubricItemDef code = new RubricItemDef();
        code.setName("Code");
        library.put("ri_code", code);
        RubricItemDef tests = new RubricItemDef();
        tests.setName("Tests");
        library.put("ri_tests", tests);
        file.setRubricItemLibrary(library);
        return file;
    }

    private RubricItemRef rubric(String id, int points) {
        RubricItemRef ref = new RubricItemRef();
        ref.setRubricItemId(id);
        ref.setPoints(points);
        return ref;
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

    private Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
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

    @FunctionalInterface
    private interface FxRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }

    private record FxFixture(AssignmentEditorController controller,
                             TextField courseCodeField,
                             TextField assignmentCodeField,
                             TextField assignmentNameField,
                             ListView<String> expectedFilesListView,
                             Label errorLabel,
                             ObservableList<String> expectedFiles,
                             ObservableList<RubricItemRef> rubricItems) {
    }
}

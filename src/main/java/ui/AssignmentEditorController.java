/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package ui;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import javafx.util.converter.IntegerStringConverter;
import model.Assignment;
import model.AssignmentsFile;
import model.RubricItemRef;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AssignmentEditorController {

    @FXML private TextField courseCodeField;
    @FXML private TextField assignmentCodeField;
    @FXML private TextField assignmentNameField;

    @FXML private ListView<String> expectedFilesListView;
    private final ObservableList<String> expectedFiles = FXCollections.observableArrayList();

    @FXML private TableView<RubricItemRef> rubricTable;
    @FXML private TableColumn<RubricItemRef, String> rubricItemIdCol;
    @FXML private TableColumn<RubricItemRef, Integer> pointsCol;

    @FXML private Button addRubricRowButton;
    @FXML private Button removeRubricRowButton;

    @FXML private Label totalLabel;
    @FXML private Label errorLabel;

    private final ObservableList<RubricItemRef> rubricItems =
            FXCollections.observableArrayList();

    private AssignmentsFile assignmentsFile;
    private Assignment workingCopy;
    private boolean saved = false;

    public boolean isSaved() {
        return saved;
    }

    public Assignment getResult() {
        return workingCopy;
    }

    /**
     * Call immediately after FXMLLoader.load().
     */
    public void init(AssignmentsFile assignmentsFile, Assignment existingOrNull) {
        this.assignmentsFile = assignmentsFile;

        if (existingOrNull == null) {
            workingCopy = new Assignment();
            workingCopy.setCourseCode("");
            workingCopy.setAssignmentCode("");
            workingCopy.setAssignmentName("");
            workingCopy.setExpectedFiles(new ArrayList<>());
            workingCopy.setRubric(new Assignment.Rubric());
            workingCopy.getRubric().setItems(new ArrayList<>());
        } else {
            workingCopy = deepCopy(existingOrNull);
        }

        setupTable();
        setupExpectedFilesList();
        loadIntoUi();
        updateTotalLabel();
    }

    private void setupTable() {
        rubricTable.setItems(rubricItems);
        rubricTable.setEditable(true);

        // ---- Rubric item ID column (ComboBox editor) ----
        rubricItemIdCol.setCellValueFactory(cd -> {
            String id = cd.getValue().getRubricItemId();
            return new SimpleStringProperty(id);
        });

        List<String> rubricIds = new ArrayList<>();
        if (assignmentsFile != null
                && assignmentsFile.getRubricItemLibrary() != null) {
            rubricIds.addAll(assignmentsFile.getRubricItemLibrary().keySet());
        }
        rubricIds.sort(String::compareTo);

        ObservableList<String> rubricChoices =
                FXCollections.observableArrayList(rubricIds);

        rubricItemIdCol.setCellFactory(ComboBoxTableCell.forTableColumn(
                new StringConverter<>() {
                    @Override public String toString(String s) {
                        return s == null ? "" : s;
                    }
                    @Override public String fromString(String s) {
                        return s;
                    }
                },
                rubricChoices
        ));

        rubricItemIdCol.setOnEditCommit(evt -> {
            RubricItemRef ref = evt.getRowValue();
            ref.setRubricItemId(evt.getNewValue());
            updateTotalLabel();
        });

        // ---- Points column (integer editable) ----
        pointsCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>(cd.getValue().getPoints()));

        pointsCol.setCellFactory(TextFieldTableCell.forTableColumn(
                new IntegerStringConverter()
        ));

        pointsCol.setOnEditCommit(evt -> {
            RubricItemRef ref = evt.getRowValue();
            int newValue = evt.getNewValue() == null ? 0 : evt.getNewValue();
            if (newValue < 0) newValue = 0;
            ref.setPoints(newValue);
            updateTotalLabel();
        });

        rubricItems.addListener((ListChangeListener<RubricItemRef>) _ -> updateTotalLabel());
    }

    private void setupExpectedFilesList() {
        expectedFilesListView.setItems(expectedFiles);

        expectedFilesListView.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            e.consume();
        });

        expectedFilesListView.setOnDragDropped(e -> {
            boolean addedAny = false;

            if (e.getDragboard().hasFiles()) {
                for (File f : e.getDragboard().getFiles()) {
                    String name = f.getName();
                    String lower = name.toLowerCase();
                    if (!lower.endsWith(".java") && !lower.endsWith(".fxml")) {
                        continue;
                    }
                    if (!expectedFiles.contains(name)) {
                        expectedFiles.add(name);
                        addedAny = true;
                    }
                }

                if (addedAny) {
                    expectedFiles.sort(String::compareTo);
                }
            }

            e.setDropCompleted(addedAny);
            e.consume();
        });

        // optional: delete key removes selected
        expectedFilesListView.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.DELETE) {
                String sel = expectedFilesListView.getSelectionModel().getSelectedItem();
                if (sel != null) expectedFiles.remove(sel);
            }
        });
    }

    private void loadIntoUi() {
        courseCodeField.setText(nullToEmpty(workingCopy.getCourseCode()));
        assignmentCodeField.setText(nullToEmpty(workingCopy.getAssignmentCode()));
        assignmentNameField.setText(nullToEmpty(workingCopy.getAssignmentName()));

        // expected files (one per line)
        expectedFiles.clear();
        if (workingCopy.getExpectedFiles() != null) {
            for (String s : workingCopy.getExpectedFiles()) {
                if (s == null) continue;
                String name = Path.of(s).getFileName().toString();
                if (!name.isBlank() && !expectedFiles.contains(name)) {
                    expectedFiles.add(name);
                }
            }
        }
        expectedFiles.sort(String::compareTo);


        // rubric list
        rubricItems.clear();
        if (workingCopy.getRubric() != null
                && workingCopy.getRubric().getItems() != null) {
            rubricItems.addAll(workingCopy.getRubric().getItems());
        }
    }

    private void saveFromUi() {
        workingCopy.setCourseCode(courseCodeField.getText() == null ? "" : courseCodeField.getText().trim());
        workingCopy.setAssignmentCode(assignmentCodeField.getText() == null ? "" : assignmentCodeField.getText().trim());
        workingCopy.setAssignmentName(assignmentNameField.getText() == null ? "" : assignmentNameField.getText().trim());

        // expected files
        List<String> expected = new ArrayList<>();
        for (String name : expectedFiles) {
            if (name != null) {
                String t = name.trim();
                if (!t.isEmpty()) {
                    expected.add(t);
                }
            }
        }
        expected.sort(String::compareTo);
        workingCopy.setExpectedFiles(expected);


        // rubric items
        if (workingCopy.getRubric() == null) {
            workingCopy.setRubric(new Assignment.Rubric());
        }

        List<RubricItemRef> out = new ArrayList<>();
        for (RubricItemRef ref : rubricItems) {
            if (ref == null) {
                continue;
            }
            String id = ref.getRubricItemId();
            if (id == null || id.trim().isEmpty()) {
                continue;
            }
            RubricItemRef clean = new RubricItemRef();
            clean.setRubricItemId(id.trim());
            clean.setPoints(ref.getPoints());
            out.add(clean);
        }

        out.sort(Comparator.comparing(RubricItemRef::getRubricItemId));
        workingCopy.getRubric().setItems(out);
    }

    private boolean validate() {
        errorLabel.setText("");
        String course = courseCodeField.getText() == null ? "" :
                courseCodeField.getText().trim();
        String code = assignmentCodeField.getText() == null ? "" :
                assignmentCodeField.getText().trim();
        String name = assignmentNameField.getText() == null ? "" :
                assignmentNameField.getText().trim();

        if (course.isEmpty()) {
            errorLabel.setText("Course code is required.");
            return false;
        }
        if (code.isEmpty()) {
            errorLabel.setText("Assignment code is required.");
            return false;
        }
        if (name.isEmpty()) {
            errorLabel.setText("Assignment name is required.");
            return false;
        }

        // rubric items must exist
        if (rubricItems.isEmpty()) {
            errorLabel.setText("Rubric must contain at least 1 item.");
            return false;
        }

        // rubric IDs must exist in library
        Map<String, ?> lib = null;
        if (assignmentsFile != null) {
            lib = assignmentsFile.getRubricItemLibrary();
        }

        Set<String> seen = new HashSet<>();
        for (RubricItemRef ref : rubricItems) {
            String idRaw = ref.getRubricItemId();
            String id = idRaw == null ? "" : idRaw.trim();
            if (!seen.add(id)) {
                errorLabel.setText("Duplicate rubric item id: " + id);
                return false;
            }
            if (id.isEmpty()) {
                errorLabel.setText("Rubric item id cannot be blank.");
                return false;
            }
            if (lib == null || !lib.containsKey(id)) {
                errorLabel.setText("Rubric item id not found in library: " + id);
                return false;
            }
        }

        // total must be 100
        int total = computeTotal();
        if (total != 100) {
            errorLabel.setText("Rubric total must equal exactly 100. Current total: " + total);
            return false;
        }

        return true;
    }

    private int computeTotal() {
        int total = 0;
        for (RubricItemRef ref : rubricItems) {
            total += ref.getPoints();
        }
        return total;
    }

    private void updateTotalLabel() {
        totalLabel.setText("Total: " + computeTotal());
    }

    @FXML
    private void onAddRubricRow() {
        RubricItemRef ref = new RubricItemRef();
        ref.setRubricItemId("");
        ref.setPoints(0);
        rubricItems.add(ref);
        rubricTable.getSelectionModel().select(ref);
        updateTotalLabel();
    }

    @FXML
    private void onRemoveRubricRow() {
        RubricItemRef selected = rubricTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            rubricItems.remove(selected);
            updateTotalLabel();
        }
    }

    @FXML
    private void onSave() {
        rubricTable.edit(-1, null);
        saveFromUi();

        if (!validate()) {
            return;
        }

        saved = true;

        Stage stage = (Stage) courseCodeField.getScene().getWindow();
        stage.close();
    }

    @FXML
    private void onCancel() {
        saved = false;
        Stage stage = (Stage) courseCodeField.getScene().getWindow();
        stage.close();
    }

    private Assignment deepCopy(Assignment src) {
        Assignment a = new Assignment();
        a.setCourseCode(src.getCourseCode());
        a.setAssignmentCode(src.getAssignmentCode());
        a.setAssignmentName(src.getAssignmentName());

        if (src.getExpectedFiles() != null) {
            a.setExpectedFiles(new ArrayList<>(src.getExpectedFiles()));
        } else {
            a.setExpectedFiles(new ArrayList<>());
        }

        Assignment.Rubric r = new Assignment.Rubric();
        List<RubricItemRef> items = new ArrayList<>();
        if (src.getRubric() != null && src.getRubric().getItems() != null) {
            for (RubricItemRef ref : src.getRubric().getItems()) {
                RubricItemRef copy = new RubricItemRef();
                copy.setRubricItemId(ref.getRubricItemId());
                copy.setPoints(ref.getPoints());
                items.add(copy);
            }
        }
        r.setItems(items);
        a.setRubric(r);

        return a;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

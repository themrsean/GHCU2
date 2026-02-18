/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package ui;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.stage.Stage;
import model.Assignment;
import model.AssignmentsFile;
import model.RubricItemDef;
import model.RubricItemRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RubricLibraryEditorController {

    @FXML private TableView<RubricRow> rubricTable;
    @FXML private TableColumn<RubricRow, String> idCol;
    @FXML private TableColumn<RubricRow, String> nameCol;
    @FXML private TableColumn<RubricRow, Boolean> checkstyleCol;

    @FXML private Label errorLabel;

    private final ObservableList<RubricRow> rows =
            FXCollections.observableArrayList();

    private AssignmentsFile assignmentsFile;

    /**
     * Call immediately after FXMLLoader.load().
     */
    public void init(AssignmentsFile assignmentsFile) {
        this.assignmentsFile = assignmentsFile;

        setupTable();
        loadFromModel();
    }

    private void setupTable() {
        rubricTable.setItems(rows);
        rubricTable.setEditable(true);

        // ---- ID column ----
        idCol.setCellValueFactory(cd -> cd.getValue().idProperty());
        idCol.setCellFactory(TextFieldTableCell.forTableColumn());
        idCol.setOnEditCommit(evt -> {
            RubricRow row = evt.getRowValue();
            String newId = evt.getNewValue() == null ? "" : evt.getNewValue().trim();

            if (newId.isEmpty()) {
                error("Rubric item ID cannot be blank.");
                rubricTable.refresh();
                return;
            }

            String oldId = row.getId();

            if (newId.equals(oldId)) {
                clearError();
                return;
            }

            // Block changing IDs if referenced by any assignment
            if (isRubricIdReferenced(oldId)) {
                error("Cannot change rubric item ID because it is referenced by an assignment: " + oldId);
                rubricTable.refresh();
                return;
            }

            // Must be unique
            for (RubricRow r : rows) {
                if (r != row && newId.equals(r.getId())) {
                    error("Duplicate rubric item ID: " + newId);
                    rubricTable.refresh();
                    return;
                }
            }

            row.setId(newId);
            clearError();
            sortRows();
            rubricTable.refresh();
        });

        // ---- Name column ----
        nameCol.setCellValueFactory(cd -> cd.getValue().nameProperty());

        nameCol.setCellFactory(TextFieldTableCell.forTableColumn());

        nameCol.setOnEditCommit(evt -> {
            RubricRow row = evt.getRowValue();
            String newName = evt.getNewValue() == null ? "" : evt.getNewValue().trim();
            row.setName(newName);
            clearError();
            rubricTable.refresh();
        });

        // ---- Checkstyle column ----
        checkstyleCol.setCellValueFactory(cd ->
                cd.getValue().checkstyleItemProperty());

        checkstyleCol.setCellFactory(CheckBoxTableCell.forTableColumn(checkstyleCol));
    }

    private void loadFromModel() {
        rows.clear();

        if (assignmentsFile == null || assignmentsFile.getRubricItemLibrary() == null) {
            return;
        }

        Map<String, RubricItemDef> lib = assignmentsFile.getRubricItemLibrary();

        List<String> ids = new ArrayList<>(lib.keySet());
        ids.sort(String::compareTo);

        for (String id : ids) {
            RubricItemDef def = lib.get(id);
            if (def == null) {
                continue;
            }

            RubricRow row = new RubricRow();
            row.setId(id);
            row.setName(def.getName() == null ? "" : def.getName());
            row.setCheckstyleItem(def.isCheckstyleItem());

            rows.add(row);
        }

        sortRows();
    }

    @FXML
    private void onAdd() {
        clearError();

        String base = "ri_new";
        String id = base;
        int i = 1;

        Set<String> used = new HashSet<>();
        for (RubricRow r : rows) {
            used.add(r.getId());
        }

        while (used.contains(id)) {
            id = base + i;
            i++;
        }

        RubricRow row = new RubricRow();
        row.setId(id);
        row.setName("New Rubric Item");
        row.setCheckstyleItem(false);

        rows.add(row);
        sortRows();

        rubricTable.getSelectionModel().select(row);
        rubricTable.scrollTo(row);
    }

    @FXML
    private void onDeleteSelected() {
        clearError();

        RubricRow selected = rubricTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }

        String id = selected.getId();
        if (id == null || id.trim().isEmpty()) {
            rows.remove(selected);
            return;
        }

        if (isRubricIdReferenced(id)) {
            error("Cannot delete rubric item because it is referenced by an assignment: " + id);
            return;
        }

        rows.remove(selected);
    }

    @FXML
    private void onClose() {
        clearError();

        if (!validateRows()) {
            return;
        }
        int count = 0;
        for (RubricRow row : rows) {
            if (row.isCheckstyleItem()) {
                count++;
            }
        }
        if (count > 1) {
            error("Only one rubric item may be marked as Checkstyle.");
            return;
        }

        applyToModel();
        closeWindow();
    }

    private void applyToModel() {
        if (assignmentsFile == null) {
            return;
        }

        if (assignmentsFile.getRubricItemLibrary() == null) {
            assignmentsFile.setRubricItemLibrary(new HashMap<>());
        }

        Map<String, RubricItemDef> lib = new HashMap<>();

        for (RubricRow row : rows) {
            String id = row.getId() == null ? "" : row.getId().trim();
            if (id.isEmpty()) {
                continue;
            }

            RubricItemDef def = new RubricItemDef();
            def.setName(row.getName() == null ? "" : row.getName().trim());
            def.setCheckstyleItem(row.isCheckstyleItem());

            lib.put(id, def);
        }

        assignmentsFile.setRubricItemLibrary(lib);
    }

    private boolean validateRows() {
        Set<String> ids = new HashSet<>();

        for (RubricRow row : rows) {
            String id = row.getId() == null ? "" : row.getId().trim();
            String name = row.getName() == null ? "" : row.getName().trim();

            if (id.isEmpty()) {
                error("Rubric item ID cannot be blank.");
                return false;
            }

            if (ids.contains(id)) {
                error("Duplicate rubric item ID: " + id);
                return false;
            }

            if (name.isEmpty()) {
                error("Rubric item name cannot be blank. (ID: " + id + ")");
                return false;
            }

            ids.add(id);
        }

        return true;
    }

    private boolean isRubricIdReferenced(String id) {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        if (assignmentsFile == null || assignmentsFile.getAssignments() == null) {
            return false;
        }

        for (Assignment a : assignmentsFile.getAssignments()) {
            if (a == null || a.getRubric() == null || a.getRubric().getItems() == null) {
                continue;
            }
            for (RubricItemRef ref : a.getRubric().getItems()) {
                if (ref != null && id.equals(ref.getRubricItemId())) {
                    return true;
                }
            }
        }

        return false;
    }

    private void sortRows() {
        rows.sort(Comparator.comparing(RubricRow::getId));
    }

    private void closeWindow() {
        Stage stage = (Stage) rubricTable.getScene().getWindow();
        stage.close();
    }

    private void error(String msg) {
        if (errorLabel != null) {
            errorLabel.setText(msg);
        }
    }

    private void clearError() {
        if (errorLabel != null) {
            errorLabel.setText("");
        }
    }

    /**
     * Local editable row model for the TableView.
     */
    public static class RubricRow {
        private final SimpleStringProperty id = new SimpleStringProperty("");
        private final SimpleStringProperty name = new SimpleStringProperty("");
        private final SimpleBooleanProperty checkstyleItem = new SimpleBooleanProperty(false);

        public RubricRow() {
        }

        public String getId() {
            return id.get();
        }

        public void setId(String id) {
            this.id.set(id);
        }

        public SimpleStringProperty idProperty() {
            return id;
        }

        public String getName() {
            return name.get();
        }

        public void setName(String name) {
            this.name.set(name);
        }

        public SimpleStringProperty nameProperty() {
            return name;
        }

        public boolean isCheckstyleItem() {
            return checkstyleItem.get();
        }

        public void setCheckstyleItem(boolean checkstyleItem) {
            this.checkstyleItem.set(checkstyleItem);
        }

        public SimpleBooleanProperty checkstyleItemProperty() {
            return checkstyleItem;
        }
    }

}

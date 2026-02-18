/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package ui;

import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.stage.Stage;
import javafx.util.converter.IntegerStringConverter;
import model.Assignment;
import model.Comments.CommentDef;
import model.Comments.CommentsLibrary;
import model.RubricItemDef;
import model.RubricItemRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CommentLibraryEditorController {
    @FXML private Label titleLabel;
    @FXML private TableView<CommentRow> commentTable;
    @FXML private TableColumn<CommentRow, String> rubricItemCol;
    @FXML private TableColumn<CommentRow, Integer> pointsCol;
    @FXML private TableColumn<CommentRow, String> titleCol;
    @FXML private TableColumn<CommentRow, String> textCol;
    @FXML private Label errorLabel;

    private final ObservableList<CommentRow> rows =
            FXCollections.observableArrayList();

    private Assignment assignment;
    private CommentsLibrary commentsLibrary;
    private Map<String, RubricItemDef> rubricLibrary;

    /**
     * Call immediately after FXMLLoader.load()
     */
    public void init(Assignment assignment,
                     Map<String, RubricItemDef> rubricLibrary,
                     CommentsLibrary commentsLibrary) {

        this.assignment = assignment;
        this.commentsLibrary = commentsLibrary;
        this.rubricLibrary = rubricLibrary;

        setupTable();
        loadFromModel();
        updateTitle();
    }

    private void updateTitle() {
        String key = assignment == null ? "" : assignment.getKey();
        if (titleLabel != null) {
            titleLabel.setText("Comment Library - " + key);
        }
    }

    private void setupTable() {
        commentTable.setItems(rows);
        commentTable.setEditable(true);

        // ----- Rubric item column (ComboBox) -----
        rubricItemCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getRubricItemId()));

        List<String> rubricIds = getRubricIdsForAssignment();
        ObservableList<String> choices = FXCollections.observableArrayList(rubricIds);

        rubricItemCol.setCellFactory(ComboBoxTableCell.forTableColumn(choices));

        rubricItemCol.setOnEditCommit(evt -> {
            CommentRow row = evt.getRowValue();
            String newId = evt.getNewValue() == null ? "" : evt.getNewValue().trim();
            row.setRubricItemId(newId);
            clearError();
            commentTable.refresh();
        });

        // ----- Points column -----
        pointsCol.setCellValueFactory(cd ->
                new SimpleIntegerProperty(cd.getValue().getPoints()).asObject());

        pointsCol.setCellFactory(TextFieldTableCell.forTableColumn(
                new IntegerStringConverter()
        ));

        pointsCol.setOnEditCommit(evt -> {
            CommentRow row = evt.getRowValue();
            Integer v = evt.getNewValue();
            row.setPoints(v == null ? 0 : v);
            clearError();
            commentTable.refresh();
        });

        // ----- Title column -----
        titleCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getTitle()));

        titleCol.setCellFactory(TextFieldTableCell.forTableColumn());

        titleCol.setOnEditCommit(evt -> {
            CommentRow row = evt.getRowValue();
            String v = evt.getNewValue() == null ? "" : evt.getNewValue().trim();
            row.setTitle(v);
            clearError();
            commentTable.refresh();
        });

        // ----- Text column -----
        textCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getText()));

        textCol.setCellFactory(TextFieldTableCell.forTableColumn());

        textCol.setOnEditCommit(evt -> {
            CommentRow row = evt.getRowValue();
            String v = evt.getNewValue() == null ? "" : evt.getNewValue().trim();
            row.setText(v);
            clearError();
            commentTable.refresh();
        });
    }

    private List<String> getRubricIdsForAssignment() {
        List<String> ids = new ArrayList<>();

        if (assignment == null || assignment.getRubric() == null) {
            return ids;
        }
        if (assignment.getRubric().getItems() == null) {
            return ids;
        }

        for (RubricItemRef ref : assignment.getRubric().getItems()) {
            if (ref == null) {
                continue;
            }
            String id = ref.getRubricItemId();
            if (id == null || id.trim().isEmpty()) {
                continue;
            }
            ids.add(id.trim());
        }

        ids.sort(String::compareTo);
        return ids;
    }

    private void loadFromModel() {
        rows.clear();

        if (commentsLibrary == null) {
            commentsLibrary = new CommentsLibrary();
            commentsLibrary.setSchemaVersion(1);
            commentsLibrary.setComments(new ArrayList<>());
        }

        if (commentsLibrary.getComments() == null) {
            commentsLibrary.setComments(new ArrayList<>());
        }

        String assignmentKey = assignment == null ? "" : assignment.getKey();

        for (CommentDef def : commentsLibrary.getComments()) {
            if (def == null) {
                continue;
            }

            if (!assignmentKey.equals(def.getAssignmentKey())) {
                continue;
            }

            CommentRow row = new CommentRow();
            row.setId(def.getCommentId());
            row.setAssignmentKey(def.getAssignmentKey());
            row.setRubricItemId(def.getRubricItemId());
            row.setPoints(def.getPointsDeducted());
            row.setTitle(def.getTitle());
            row.setText(def.getBodyMarkdown());

            rows.add(row);
        }

        sortRows();
    }

    @FXML
    private void onAdd() {
        clearError();

        if (assignment == null) {
            error("No assignment selected.");
            return;
        }

        String assignmentKey = assignment.getKey();
        String defaultRubricId = getRubricIdsForAssignment().isEmpty()
                ? ""
                : getRubricIdsForAssignment().getFirst();

        String id = generateUniqueId(assignmentKey);

        CommentRow row = new CommentRow();
        row.setId(id);
        row.setAssignmentKey(assignmentKey);
        row.setRubricItemId(defaultRubricId);
        row.setPoints(-1);
        row.setTitle("New Comment");
        row.setText("Write feedback here.");

        rows.add(row);
        sortRows();

        commentTable.getSelectionModel().select(row);
        commentTable.scrollTo(row);
    }

    private String generateUniqueId(String assignmentKey) {
        // Example: hw1_001, hw1_002, etc.
        String prefix = assignmentKey.toLowerCase().replaceAll("[^a-z0-9]+", "");
        if (prefix.isEmpty()) {
            prefix = "c";
        }

        Set<String> used = new HashSet<>();
        for (CommentRow r : rows) {
            if (r.getId() != null) {
                used.add(r.getId());
            }
        }

        int i = 1;
        while (true) {
            String id = prefix + "_" + String.format("%03d", i);
            if (!used.contains(id)) {
                return id;
            }
            i++;
        }
    }

    @FXML
    private void onDeleteSelected() {
        clearError();

        CommentRow selected = commentTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
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

        applyToModel();
        closeWindow();
    }

    private boolean validateRows() {
        if (assignment == null) {
            error("No assignment selected.");
            return false;
        }

        String assignmentKey = assignment.getKey();
        List<String> allowedRubricIds = getRubricIdsForAssignment();

        Set<String> ids = new HashSet<>();

        for (CommentRow row : rows) {
            String id = row.getId() == null ? "" : row.getId().trim();
            String aKey = row.getAssignmentKey() == null ? "" : row.getAssignmentKey().trim();
            String rubricId = row.getRubricItemId() == null ? "" : row.getRubricItemId().trim();
            String title = row.getTitle() == null ? "" : row.getTitle().trim();
            String text = row.getText() == null ? "" : row.getText().trim();

            if (id.isEmpty()) {
                error("Comment ID cannot be blank.");
                return false;
            }
            if (ids.contains(id)) {
                error("Duplicate comment ID: " + id);
                return false;
            }
            ids.add(id);

            if (!assignmentKey.equals(aKey)) {
                error("Comment has wrong assignment key: " + id);
                return false;
            }

            if (rubricId.isEmpty()) {
                error("Rubric item is required. (Comment: " + id + ")");
                return false;
            }

            if (!allowedRubricIds.contains(rubricId)) {
                error("Rubric item not in this assignment rubric: " + rubricId +
                        " (Comment: " + id + ")");
                return false;
            }

            if (title.isEmpty()) {
                error("Title cannot be blank. (Comment: " + id + ")");
                return false;
            }

            if (text.isEmpty()) {
                error("Comment text cannot be blank. (Comment: " + id + ")");
                return false;
            }
        }

        return true;
    }

    private void applyToModel() {
        if (commentsLibrary == null) {
            return;
        }

        if (commentsLibrary.getComments() == null) {
            commentsLibrary.setComments(new ArrayList<>());
        }

        String assignmentKey = assignment.getKey();

        // Remove all existing comments for this assignment
        commentsLibrary.getComments().removeIf(def ->
                def != null && assignmentKey.equals(def.getAssignmentKey()));

        // Add rows back in
        List<CommentDef> out = new ArrayList<>();
        for (CommentRow row : rows) {
            CommentDef def = new CommentDef();
            def.setCommentId(row.getId().trim());
            def.setAssignmentKey(row.getAssignmentKey().trim());
            def.setRubricItemId(row.getRubricItemId().trim());
            def.setPointsDeducted(row.getPoints());
            def.setTitle(row.getTitle() == null ? "" : row.getTitle().trim());
            def.setBodyMarkdown(row.getText() == null ? "" : row.getText().trim());
            out.add(def);
        }

        // Keep deterministic ordering in JSON
        out.sort(Comparator
                .comparing(CommentDef::getAssignmentKey)
                .thenComparing(CommentDef::getRubricItemId)
                .thenComparing(CommentDef::getCommentId));

        commentsLibrary.getComments().addAll(out);
    }

    private void sortRows() {
        rows.sort(Comparator
                .comparing(CommentRow::getRubricItemId, Comparator.nullsLast(String::compareTo))
                .thenComparing(CommentRow::getId, Comparator.nullsLast(String::compareTo)));
    }

    private void closeWindow() {
        Stage stage = (Stage) commentTable.getScene().getWindow();
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
    public static class CommentRow {
        private String id;
        private String assignmentKey;
        private String rubricItemId;
        private int points;
        private String title;
        private String text;

        public CommentRow() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getAssignmentKey() {
            return assignmentKey;
        }

        public void setAssignmentKey(String assignmentKey) {
            this.assignmentKey = assignmentKey;
        }

        public String getRubricItemId() {
            return rubricItemId;
        }

        public void setRubricItemId(String rubricItemId) {
            this.rubricItemId = rubricItemId;
        }

        public int getPoints() {
            return points;
        }

        public void setPoints(int points) {
            this.points = points;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}

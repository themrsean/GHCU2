/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import model.Assignment;
import model.AssignmentsFile;
import model.Comments.CommentDef;
import model.Comments.CommentsLibrary;
import model.Comments.CommentsStore;

import java.io.IOException;
import java.nio.file.Path;

public class CommentManagerController {

    @FXML private ListView<CommentDef> commentList;
    @FXML private TextField idField;
    @FXML private TextField titleField;
    @FXML private ComboBox<String> rubricCombo;
    @FXML private Spinner<Integer> pointsSpinner;
    @FXML private TextArea bodyArea;
    @FXML private Button saveButton;
    @FXML private TextField searchField;

    private CommentsLibrary library;
    private AssignmentsFile assignmentsFile;
    private CommentsStore store;
    private Path commentsPath;
    private FilteredList<CommentDef> filteredComments;
    private ObservableList<CommentDef> master;
    private Assignment assignment;
    private boolean suppressSelection = false;

    public void init(Assignment assignment,
                     CommentsLibrary library,
                     AssignmentsFile assignmentsFile,
                     CommentsStore store,
                     Path commentsPath) {

        this.assignment =  assignment;
        this.library = library;
        this.assignmentsFile = assignmentsFile;
        this.store = store;
        this.commentsPath = commentsPath;

        // ----- Master list -----
        master = FXCollections.observableArrayList(library.getComments());
        filteredComments = new FilteredList<>(master, c -> true);
        commentList.setItems(filteredComments);

        // ----- Rubric combo -----
        rubricCombo.getItems().setAll(
                assignmentsFile.getRubricItemLibrary().keySet()
        );

        pointsSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 100, 0)
        );

        // ----- Selection listener -----
        commentList.getSelectionModel().selectedItemProperty().addListener((_, _, c) -> {
            if(!suppressSelection) {
                if (c != null) {
                    loadComment(c);
                }
            }
        });

        // ----- Disable save if required fields empty -----
        saveButton.disableProperty().bind(
                idField.textProperty().isEmpty()
                        .or(rubricCombo.valueProperty().isNull())
        );

        // ----- Search filter -----
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String filter = newVal == null ? "" : newVal.toLowerCase().trim();

            filteredComments.setPredicate(comment -> {

                if (filter.isEmpty()) return true;

                String id = safe(comment.getCommentId());
                String title = safe(comment.getTitle());
                String rubric = safe(comment.getRubricItemId());

                return id.contains(filter)
                        || title.contains(filter)
                        || rubric.contains(filter);
            });
        });
    }

    private void loadComment(CommentDef c) {
        idField.setText(c.getCommentId());
        titleField.setText(c.getTitle());
        rubricCombo.setValue(c.getRubricItemId());
        pointsSpinner.getValueFactory().setValue(c.getPointsDeducted());
        bodyArea.setText(c.getBodyMarkdown());
    }

    @FXML
    private void onNew() {
        commentList.getSelectionModel().clearSelection();

        idField.clear();
        titleField.clear();
        rubricCombo.setValue(null);
        pointsSpinner.getValueFactory().setValue(0);
        bodyArea.clear();

        idField.requestFocus();
    }

    @FXML
    private void onSave() {

        String id = idField.getText() == null ? "" : idField.getText().trim();
        String title = titleField.getText() == null ? "" : titleField.getText().trim();
        String rubric = rubricCombo.getValue();
        Integer points = pointsSpinner.getValue();
        String body = bodyArea.getText() == null ? "" : bodyArea.getText();

        if (id.isBlank()) {
            showError("Comment ID cannot be empty.");
            return;
        }

        if (rubric == null || rubric.isBlank()) {
            showError("A rubric item must be selected.");
            return;
        }

        if (!assignmentsFile.getRubricItemLibrary().containsKey(rubric)) {
            showError("Invalid rubric item selected.");
            return;
        }

        if (points == null || points < 0) {
            showError("Points deducted must be 0 or greater.");
            return;
        }

        CommentDef selected = commentList.getSelectionModel().getSelectedItem();

        // -------- NEW COMMENT --------
        if (selected == null) {

            if (!isUniqueId(id, null)) {
                showError("Duplicate Comment ID: '" + id + "'");
                return;
            }

            CommentDef newComment = new CommentDef();
            newComment.setCommentId(id);
            newComment.setTitle(title);
            newComment.setRubricItemId(rubric);
            newComment.setPointsDeducted(points);
            newComment.setBodyMarkdown(body);
            newComment.setAssignmentKey(assignment.getKey());

            library.getComments().add(newComment);
            master.add(newComment);

            searchField.clear();
            filteredComments.setPredicate(c -> true);

            commentList.getSelectionModel().select(newComment);
        }

        // -------- EDIT EXISTING --------
        else {

            if (!isUniqueId(id, selected)) {
                showError("Duplicate Comment ID: '" + id + "'");
                return;
            }

            selected.setCommentId(id);
            selected.setTitle(title);
            selected.setRubricItemId(rubric);
            selected.setPointsDeducted(points);
            selected.setBodyMarkdown(body);
        }

        try {
            store.save(commentsPath, library);
        } catch (IOException e) {
            showError("Failed to save comments.json:\n" + e.getMessage());
            return;
        }

        commentList.refresh();
    }

    private boolean isUniqueId(String id, CommentDef current) {
        return library.getComments().stream()
                .filter(c -> c != current)
                .noneMatch(c -> id.equals(c.getCommentId()));
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Validation Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    @FXML
    private void onDelete() {

        CommentDef c = commentList.getSelectionModel().getSelectedItem();
        if (c == null) return;

        suppressSelection = true;

        try {
            // Clear selection FIRST to prevent selection churn
            commentList.getSelectionModel().clearSelection();

            library.getComments().remove(c);
            master.remove(c);

            store.save(commentsPath, library);

            // Clear fields after delete
            idField.clear();
            titleField.clear();
            rubricCombo.setValue(null);
            pointsSpinner.getValueFactory().setValue(0);
            bodyArea.clear();

        } catch (IOException e) {
            showError("Failed to save comments.json:\n" + e.getMessage());
        } finally {
            suppressSelection = false;
        }
    }

    private String safe(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}


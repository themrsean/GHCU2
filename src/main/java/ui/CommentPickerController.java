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
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import model.Assignment;
import model.Comments.CommentDef;
import model.Comments.CommentsLibrary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Modal comment picker.
 *
 * Result:
 * - selected CommentDef (or null)
 */
public class CommentPickerController {

    @FXML private Label assignmentLabel;

    @FXML private TextField searchField;

    @FXML private ListView<CommentDef> commentList;
    @FXML private TextArea previewArea;

    @FXML private Label errorLabel;

    private Assignment assignment;
    private CommentsLibrary commentsLibrary;

    private final ObservableList<CommentDef> backing =
            FXCollections.observableArrayList();

    private FilteredList<CommentDef> filtered;

    private CommentDef selectedResult = null;
    private boolean applied = false;

    public boolean isApplied() {
        return applied;
    }

    public CommentDef getSelectedResult() {
        return selectedResult;
    }

    public void init(Assignment assignment, CommentsLibrary commentsLibrary) {
        this.assignment = assignment;
        this.commentsLibrary = commentsLibrary;

        setupUi();
        loadList();
    }


    private void setupUi() {
        error("");

        String title = assignment.getCourseCode() + " " + assignment.getAssignmentCode()
                + " - " + assignment.getAssignmentName();
        assignmentLabel.setText(title);

        filtered = new FilteredList<>(backing, _ -> true);
        commentList.setItems(filtered);

        commentList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            updatePreview(newV);
        });

        searchField.textProperty().addListener((obs, oldV, newV) -> {
            String q = newV == null ? "" : newV.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(def -> matchesQuery(def, q));
        });
    }

    private boolean matchesQuery(CommentDef def, String q) {
        if (q.isEmpty()) {
            return true;
        }
        if (def == null) {
            return false;
        }

        String id = safe(def.getCommentId()).toLowerCase(Locale.ROOT);
        String rubric = safe(def.getRubricItemId()).toLowerCase(Locale.ROOT);
        String title = safe(def.getTitle()).toLowerCase(Locale.ROOT);
        String body = safe(def.getBodyMarkdown()).toLowerCase(Locale.ROOT);

        return id.contains(q) || rubric.contains(q) || title.contains(q) || body.contains(q);
    }

    private void loadList() {
        backing.clear();

        if (commentsLibrary == null || commentsLibrary.getComments() == null) {
            return;
        }

        String assignmentKey = assignment.getKey();

        List<CommentDef> list = new ArrayList<>();
        for (CommentDef def : commentsLibrary.getComments()) {
            if (def == null) {
                continue;
            }
            if (assignmentKey.equals(def.getAssignmentKey())) {
                list.add(def);
            }
        }

        list.sort(Comparator.comparing(CommentDef::getCommentId));
        backing.setAll(list);

        if (!backing.isEmpty()) {
            commentList.getSelectionModel().select(0);
        }
    }

    private void updatePreview(CommentDef def) {
        if (def == null) {
            previewArea.setText("");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ID: ").append(safe(def.getCommentId())).append(System.lineSeparator());
        sb.append("Rubric: ").append(safe(def.getRubricItemId())).append(System.lineSeparator());
        sb.append("Points: -").append(def.getPointsDeducted()).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        sb.append("### ").append(safe(def.getTitle())).append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append(safe(def.getBodyMarkdown())).append(System.lineSeparator());

        previewArea.setText(sb.toString());
    }

    @FXML
    private void onApply() {
        error("");

        CommentDef selected = commentList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            error("Select a comment.");
            return;
        }

        // sanity: must match this assignment
        if (!assignment.getKey().equals(selected.getAssignmentKey())) {
            error("Selected comment does not belong to this assignment.");
            return;
        }

        applied = true;
        selectedResult = selected;

        Stage stage = (Stage) commentList.getScene().getWindow();
        stage.close();
    }

    @FXML
    private void onCancel() {
        applied = false;
        selectedResult = null;

        Stage stage = (Stage) commentList.getScene().getWindow();
        stage.close();
    }

    private void error(String msg) {
        if (errorLabel != null) {
            errorLabel.setText(msg == null ? "" : msg);
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}

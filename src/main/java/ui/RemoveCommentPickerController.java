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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class RemoveCommentPickerController {

    public static class InjectedCommentRef {
        private final String anchorId;
        private final String title;
        private final String rubricItemId;
        private final int pointsLost;

        public InjectedCommentRef(String anchorId, String title, String rubricItemId, int pointsLost) {
            this.anchorId = anchorId;
            this.title = title;
            this.rubricItemId = rubricItemId;
            this.pointsLost = pointsLost;
        }

        public String getAnchorId() { return anchorId; }
        public String getTitle() { return title; }
        public String getRubricItemId() { return rubricItemId; }
        public int getPointsLost() { return pointsLost; }

        @Override
        public String toString() {
            String t = title == null ? "" : title.trim();
            String r = rubricItemId == null ? "" : rubricItemId.trim();
            return t + "  (-" + pointsLost + " " + r + ")";
        }
    }

    @FXML private TextField searchField;
    @FXML private ListView<InjectedCommentRef> commentList;
    @FXML private TextArea previewArea;
    @FXML private Label errorLabel;

    private final ObservableList<InjectedCommentRef> backing =
            FXCollections.observableArrayList();

    private FilteredList<InjectedCommentRef> filtered;

    private boolean removed = false;
    private InjectedCommentRef selectedResult = null;

    public boolean isRemoved() {
        return removed;
    }

    public InjectedCommentRef getSelectedResult() {
        return selectedResult;
    }

    public void init(List<InjectedCommentRef> injectedComments) {

        backing.clear();
        if (injectedComments != null) {
            backing.setAll(injectedComments);
        }

        backing.sort(Comparator.comparing(InjectedCommentRef::getTitle, String::compareToIgnoreCase));

        filtered = new FilteredList<>(backing, _ -> true);
        commentList.setItems(filtered);

        commentList.getSelectionModel().selectedItemProperty().addListener((_, _, c) -> {
            updatePreview(c);
        });

        searchField.textProperty().addListener((_, _, v) -> {
            String q = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(c -> matchesQuery(c, q));
        });

        if (!backing.isEmpty()) {
            commentList.getSelectionModel().select(0);
        }
    }

    private boolean matchesQuery(InjectedCommentRef c, String q) {
        if (q.isEmpty()) return true;
        if (c == null) return false;

        String title = safe(c.getTitle()).toLowerCase(Locale.ROOT);
        String rubric = safe(c.getRubricItemId()).toLowerCase(Locale.ROOT);
        String anchor = safe(c.getAnchorId()).toLowerCase(Locale.ROOT);

        return title.contains(q) || rubric.contains(q) || anchor.contains(q);
    }

    private void updatePreview(InjectedCommentRef c) {
        if (c == null) {
            previewArea.setText("");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Anchor: ").append(safe(c.getAnchorId())).append(System.lineSeparator());
        sb.append("Rubric: ").append(safe(c.getRubricItemId())).append(System.lineSeparator());
        sb.append("Points: -").append(c.getPointsLost()).append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("Title: ").append(safe(c.getTitle())).append(System.lineSeparator());

        previewArea.setText(sb.toString());
    }

    @FXML
    private void onRemove() {
        error("");

        InjectedCommentRef selected = commentList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            error("Select a comment.");
            return;
        }

        removed = true;
        selectedResult = selected;

        Stage stage = (Stage) commentList.getScene().getWindow();
        stage.close();
    }

    @FXML
    private void onCancel() {
        removed = false;
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

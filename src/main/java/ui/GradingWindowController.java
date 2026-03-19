/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import model.Assignment;
import model.AssignmentsFile;
import model.Comments;
import model.RubricItemRef;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import model.Comments.CommentDef;
import model.Comments.CommentsLibrary;
import model.Comments.CommentsStore;
import model.Comments.ParsedComment;
import service.GradingDraftService;
import service.GradingDraftSessionService;
import service.GradingMappingsService;
import service.GradingReportEditorService;
import service.GradingSyncService;
import service.ReportHtmlWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grading window:
 * - Left: student packages list
 * - Right: markdown editor for the selected student's report
 * Switching students:
 * - saves editor text + caret position to a per-student draft
 * - loads the next student's draft + restores caret position
 */
public class GradingWindowController {
    private static final String COMMENT_ANCHOR_PREFIX = "cmt_";
    // --- Syntax highlighting (Java) ---
    private static final String[] KEYWORDS = new String[] {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record",
            "sealed", "permits", "non-sealed"
    };

    private static final String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";
    private static final String PAREN_PATTERN = "[()]";
    private static final String BRACE_PATTERN = "[{}]";
    private static final String BRACKET_PATTERN = "[\\[\\]]";
    private static final String SEMICOLON_PATTERN = ";";
    private static final String STRING_PATTERN = "\"([^\"\\\\]|\\\\.)*\"";
    private static final String CHAR_PATTERN = "'([^'\\\\]|\\\\.)*'";
    private static final String COMMENT_PATTERN = "//[^\n]*" + "|" + "/\\*(.|\\R)*?\\*/";

    private static final Pattern JAVA_PATTERN = Pattern.compile(
            "(?<COMMENT>" + COMMENT_PATTERN + ")"
                    + "|(?<STRING>" + STRING_PATTERN + ")"
                    + "|(?<CHAR>" + CHAR_PATTERN + ")"
                    + "|(?<KEYWORD>" + KEYWORD_PATTERN + ")"
                    + "|(?<PAREN>" + PAREN_PATTERN + ")"
                    + "|(?<BRACE>" + BRACE_PATTERN + ")"
                    + "|(?<BRACKET>" + BRACKET_PATTERN + ")"
                    + "|(?<SEMICOLON>" + SEMICOLON_PATTERN + ")"
    );


    private final ExecutorService highlightExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "syntax-highlighter");
        t.setDaemon(true);
        return t;
    });

    @FXML
    private ListView<String> studentList;
    @FXML
    private CodeArea reportEditor;
    @FXML
    private Label statusLabel;
    @FXML
    private Button saveAndExportButton;

    private final CommentsStore commentsStore = new CommentsStore();
    private CommentsLibrary commentLibrary;
    private final Path commentsPath = appDataDir().resolve("comments.json");
    private final ReportHtmlWrapper reportHtmlWrapper = new ReportHtmlWrapper();
    private final GradingDraftService gradingDraftService =
            new GradingDraftService(reportHtmlWrapper);
    private final GradingMappingsService gradingMappingsService =
            new GradingMappingsService(this::status);
    private GradingReportEditorService gradingReportEditorService;
    private final GradingSyncService gradingSyncService = new GradingSyncService();
    private final GradingDraftSessionService draftSessionService =
            new GradingDraftSessionService();

    private AssignmentsFile assignmentsFile;
    private Assignment assignment;
    private Path rootPath;      // selectedRootPath from main window
    private Path mappingsPath;

    private String assignmentId;

    private final ObservableList<String> studentPackages =
            FXCollections.observableArrayList();

    private String currentStudent = null;
    private boolean suppressCaretEvents = false;
    private boolean suppressHighlighting = false;
    private boolean suppressTextListener = false;
    private boolean isLoadingStudent = false;
    private boolean applyingHighlight = false;
    private boolean saveInProgress = false;

    public void init(AssignmentsFile assignmentsFile, Assignment assignment, Path rootPath,
                     Path mappingsPath) {
        System.out.println("GradingWindowController INIT running");
        this.assignmentsFile = assignmentsFile;
        this.assignment = assignment;
        this.rootPath = rootPath;
        this.mappingsPath = mappingsPath;
        // rootPath/packages
        this.assignmentId = assignment.getCourseCode() + assignment.getAssignmentCode();
        this.gradingReportEditorService =
                new GradingReportEditorService(assignment, assignmentsFile);
        status("Assignment ID = [" + assignmentId + "]");
        setupUi();
        loadComments();
        loadStudentPackages();
        Platform.runLater(() -> {
            if (!studentPackages.isEmpty()) {
                studentList.getSelectionModel().select(0);
            }
        });
        Platform.runLater(() -> {
            Scene scene = reportEditor.getScene();
            if (scene != null) {
                scene.getStylesheets().add(
                        Objects.requireNonNull(getClass().getResource("/ui/grading.css"))
                                .toExternalForm()
                );
            }
        });

    }

    private void setupUi() {
        studentList.setItems(studentPackages);
        studentList.getSelectionModel().selectedItemProperty()
                .addListener((_, oldV, newV) -> onStudentSelectionChanged(oldV, newV));
        reportEditor.caretPositionProperty().addListener((_, _, newV) -> {
            if (!suppressCaretEvents && currentStudent != null) {
                draftSessionService.updateCaretIfPresent(currentStudent, newV);
            }
        });
        reportEditor.textProperty().addListener((_, _, newText) -> {
            if (!suppressTextListener && currentStudent != null) {
                draftSessionService.updateMarkdownIfPresent(currentStudent, newText);
            }
        });
        status("");
        installUndoRedoShortcuts();
        installJavaSyntaxHighlighting();
        Platform.runLater(() -> {
            Stage stage = (Stage) studentList.getScene().getWindow();
            stage.setOnHidden(_ -> highlightExecutor.shutdownNow());
        });
    }

    private void loadComments() {
        try {
            commentLibrary = commentsStore.load(commentsPath);
        } catch (IOException e) {
            commentLibrary = new Comments.CommentsLibrary();
            commentLibrary.setSchemaVersion(1);
            commentLibrary.setComments(new ArrayList<>());
        }
    }

    private void loadStudentPackages() {
        studentPackages.clear();
        Map<String, String> mapping = loadMappingsForUse();
        List<String> keys = new ArrayList<>(mapping.keySet());
        keys.sort(String::compareToIgnoreCase);
        studentPackages.setAll(keys);
        status("Student package count = " + studentPackages.size());
        status("Loaded " + keys.size() + " submissions.");
    }

    private void onStudentSelectionChanged(String oldStudent, String newStudent) {
        if (!isLoadingStudent && newStudent != null) {
            isLoadingStudent = true;
            try {
                if (oldStudent != null) {
                    saveCurrentEditorToDraft(oldStudent);
                }
                loadDraftIntoEditor(newStudent);
            } finally {
                isLoadingStudent = false;
            }
        }
    }

    private void installUndoRedoShortcuts() {
        KeyCombination undo = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
        KeyCombination redo1 = new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN);
        KeyCombination redo2 = new KeyCodeCombination(KeyCode.Z,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

        reportEditor.setOnKeyPressed(e -> {
            if (undo.match(e)) {
                reportEditor.undo();
                e.consume();
            } else if (redo1.match(e) || redo2.match(e)) {
                reportEditor.redo();
                e.consume();
            }
        });
    }

    private void installJavaSyntaxHighlighting() {
        final int waitTime = 120;
        reportEditor.multiPlainChanges()
                .successionEnds(java.time.Duration.ofMillis(waitTime))
                .subscribe(ignore -> {
                    if (!suppressHighlighting) {
                        runHighlightNow();
                    }
                });
        runHighlightNow(); // initial pass
    }

    private void runHighlightNow() {
        if (applyingHighlight) {
            return;
        }

        final String text = reportEditor.getText() == null ? "" : reportEditor.getText();

        CompletableFuture
                .supplyAsync(() -> computeJavaHighlighting(text), highlightExecutor)
                .thenAccept(spans -> Platform.runLater(() -> {

                    if (!text.equals(reportEditor.getText())) {
                        return;
                    }

                    applyingHighlight = true;
                    try {
                        reportEditor.setStyleSpans(0, spans);
                    } finally {
                        applyingHighlight = false;
                    }
                }));
    }

    private StyleSpans<Collection<String>> computeJavaHighlighting(String text) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        if (text == null || text.isEmpty()) {
            spans.add(Collections.emptyList(), 0);
            return spans.create();
        }
        String[] lines = text.split("\\R", -1);
        boolean insideJavaFence = false;
        for (String line : lines) {
            String trimmed = line.trim();
            // Detect fence start
            if (trimmed.startsWith("```")) {
                insideJavaFence = !insideJavaFence;
                spans.add(Collections.emptyList(), line.length() + 1);
                continue;
            }
            if (!insideJavaFence) {
                // Outside code block → no styling
                spans.add(Collections.emptyList(), line.length() + 1);
            } else {
                // Inside code block → apply Java regex
                Matcher matcher = JAVA_PATTERN.matcher(line);
                int lastEnd = 0;

                while (matcher.find()) {
                    spans.add(Collections.emptyList(), matcher.start() - lastEnd);

                    String styleClass =
                            matcher.group("KEYWORD") != null ? "kw" :
                                    matcher.group("PAREN") != null ? "paren" :
                                            matcher.group("BRACE") != null ? "brace" :
                                                    matcher.group("BRACKET") != null ? "bracket" :
                                                            matcher.group("SEMICOLON") != null ? "semi" :
                                                                    matcher.group("STRING") != null ? "str" :
                                                                            matcher.group("CHAR") != null ? "chr" :
                                                                                    matcher.group("COMMENT") != null ? "cmt" :
                                                                                            null;

                    spans.add(
                            styleClass == null
                                    ? Collections.emptyList()
                                    : Collections.singleton(styleClass),
                            matcher.end() - matcher.start()
                    );

                    lastEnd = matcher.end();
                }

                spans.add(Collections.emptyList(), line.length() - lastEnd);

                // Add newline
                spans.add(Collections.emptyList(), 1);
            }
        }
        return spans.create();
    }

    private void saveCurrentEditorToDraft(String studentPackage) {
        int caret = reportEditor.getCaretPosition();
        String text = reportEditor.getText() == null ? "" : reportEditor.getText();
        draftSessionService.saveEditorState(studentPackage, text, caret);
    }

    private void restoreCaretAfterUpdate(String studentPackage, int caretToRestore) {
        Platform.runLater(() -> {
            int caret = Math.max(0, Math.min(caretToRestore, reportEditor.getLength()));

            suppressCaretEvents = true;
            reportEditor.moveTo(caret);

            // Center the caret in the viewport
            centerCaretInViewport();

            suppressCaretEvents = false;

            draftSessionService.updateCaretIfPresent(studentPackage, caret);
        });
    }

    private void loadDraftIntoEditor(String studentPackage) {
        currentStudent = studentPackage;
        boolean needsReload = draftSessionService.needsReload(studentPackage);
        if (needsReload) {
            String md = loadInitialMarkdownForStudent(studentPackage);
            md = normalizeRubricAndSummaryBlocks(md, studentPackage);
            draftSessionService.setMarkdown(studentPackage, md);
            draftSessionService.setLoadedFromDisk(studentPackage, true);
        }
        int desiredCaret = draftSessionService.getCaretPosition(studentPackage);
        setEditorTextPreservingCaret(
                currentStudent,
                draftSessionService.getMarkdown(studentPackage),
                desiredCaret,
                false
        );

        // rebuild modifies text; it must also restore caret again AFTER it finishes
        rebuildRubricAndSummaryInEditor(desiredCaret, false);
        status("Editing: " + studentPackage);
    }

    private String normalizeRubricAndSummaryBlocks(String md, String studentPackage) {
        return gradingReportEditorService.normalizeRubricAndSummaryBlocks(md);
    }

    /**
     * Load order:
     * 1) repo report: {assignmentId}{pkg}.html (extract <xmp/>)
     * 2) fallback skeleton
     */
    private String loadInitialMarkdownForStudent(String studentPackage) {
        Path repoDir = findRepoDirForStudentPackage(studentPackage);

        if (repoDir != null) {
            String markdown = gradingDraftService.loadReportMarkdown(
                    assignmentId,
                    studentPackage,
                    repoDir
            );

            if (!markdown.isBlank()) {
                return ensurePatchCSectionsExist(markdown, studentPackage);
            }
        }

        return buildFreshReportSkeleton(studentPackage);
    }

    private String buildFreshReportSkeleton(String studentPackage) {
        return gradingReportEditorService.buildFreshReportSkeleton(studentPackage);
    }

    private String ensurePatchCSectionsExist(String md, String studentPackage) {
        return gradingReportEditorService.ensurePatchSectionsExist(md, studentPackage);
    }

    @FXML
    private void onInsertComment() {
        if (assignment == null) {
            status("Insert Comment failed: assignment is null.");
        } else if (commentLibrary == null) {
            status("Insert Comment failed: comment library not loaded.");
        } else if (currentStudent == null) {
            status("Insert Comment failed: no student selected.");
        } else {
            CommentDef selected = openCommentPicker();
            if (selected != null) {
                applyCommentToEditor(selected);
            }
        }
    }

    @FXML
    private void onRemoveComment() {
        if (currentStudent == null) {
            status("Remove Comment failed: no student selected.");
        } else {
            String text = reportEditor.getText() == null ? "" : reportEditor.getText();
            List<ParsedComment> parsed = Comments.parseInjectedComments(text);
            if (parsed.isEmpty()) {
                status("No injected comments found.");
            } else {
                // Convert to picker refs
                List<RemoveCommentPickerController.InjectedCommentRef> refs = new ArrayList<>();
                for (ParsedComment c : parsed) {
                    refs.add(new RemoveCommentPickerController.InjectedCommentRef(
                            c.anchorId(),
                            c.title(),
                            c.rubricItemId(),
                            c.pointsLost()
                    ));
                }
                RemoveCommentPickerController.InjectedCommentRef selected
                        = openRemoveCommentPicker(refs);
                if (selected != null) {
                    removeInjectedCommentByAnchor(selected.getAnchorId());
                }
            }
        }
    }

    private void removeInjectedCommentByAnchor(String anchorId) {
        if (anchorId == null || anchorId.isBlank()) {
            return;
        }

        int caret = reportEditor.getCaretPosition();
        String text = reportEditor.getText() == null ? "" : reportEditor.getText();

        String anchorLine = "<a id=\"" + anchorId + "\"></a>";
        int start = text.indexOf(anchorLine);

        if (start < 0) {
            status("Could not find comment anchor: " + anchorId);
            return;
        }

        int cursor = start;

        // move to end of anchor line
        int lineEnd = text.indexOf("\n", cursor);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        cursor = Math.min(text.length(), lineEnd + 1);

        // scan forward until next comment anchor or end
        boolean foundNextAnchor = false;
        while (cursor < text.length() && !foundNextAnchor) {
            if (text.startsWith("<a id=\"cmt_", cursor)) {
                foundNextAnchor = true;
            } else {
                int nextLineEnd = text.indexOf("\n", cursor);
                if (nextLineEnd < 0) {
                    nextLineEnd = text.length();
                }

                String line = text.substring(cursor, nextLineEnd).trim();

                // Comment body lines are blockquote lines or blank lines
                if (line.isEmpty() || line.startsWith(">")) {
                    cursor = Math.min(text.length(), nextLineEnd + 1);
                } else {
                    // hit real content -> stop deleting
                    break;
                }
            }
        }

        String updated = text.substring(0, start) + text.substring(cursor);

        // compute caret after delete
        int desiredCaret = caret;
        if (caret > start && caret < cursor) {
            desiredCaret = start;
        }
        desiredCaret = Math.max(0, Math.min(desiredCaret, updated.length()));

        setEditorTextPreservingCaret(currentStudent, updated, desiredCaret, false);

        // rebuild once (NO centering)
        rebuildRubricAndSummaryInEditor(desiredCaret, false);

        status("Removed comment.");
    }

    @FXML
    private void onRebuildSummary() {
        rebuildRubricAndSummaryInEditor(reportEditor.getCaretPosition(), false);
        status("Rebuilt rubric + comment summary.");
    }

    private void rebuildRubricAndSummaryInEditor(int caretToRestore, boolean centerCaret) {
        String text = reportEditor.getText() == null ? "" : reportEditor.getText();
        List<ParsedComment> comments = Comments.parseInjectedComments(text);
        String updated = gradingReportEditorService.rebuildRubricAndSummary(text, comments);
        if (!updated.equals(text)) {
            setEditorTextPreservingCaret(currentStudent, updated, caretToRestore, centerCaret);
        } else {
            // Even if no text changed, still restore caret reliably.
            Platform.runLater(() -> restoreCaretAfterUpdate(currentStudent, caretToRestore));
        }
    }

    private CommentDef openCommentPicker() {
        try {
            loadComments();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/ui/CommentPicker.fxml"));
            Scene scene = new Scene(loader.load());
            CommentPickerController controller = loader.getController();
            controller.init(assignment, commentLibrary);
            Stage stage = new Stage();
            stage.setTitle("Insert Comment");
            stage.initOwner(studentList.getScene().getWindow());
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setScene(scene);
            final int stageWidth = 650;
            final int stageHeight = 450;
            stage.setMinWidth(stageWidth);
            stage.setMinHeight(stageHeight);
            stage.showAndWait();
            if (controller.isApplied()) {
                return controller.getSelectedResult();
            }
            return null;

        } catch (IOException e) {
            status("Failed to open comment picker: " + e.getMessage());
            return null;
        }
    }

    private void applyCommentToEditor(CommentDef def) {
        String rubricId = def.getRubricItemId();
        int requestedLoss = Math.max(0, def.getPointsDeducted());

        int maxForRubric = getMaxPointsForRubricItem(rubricId);

        int alreadyLost = computePointsLostInEditorForRubric(rubricId);
        int remaining = Math.max(0, maxForRubric - alreadyLost);

        int appliedLoss = Math.min(requestedLoss, remaining);

        String anchorId = makeAnchorIdFromComment(def);

        StringBuilder injected = new StringBuilder();

        injected.append("<a id=\"").append(anchorId).append("\"></a>")
                .append(System.lineSeparator());

        injected.append("```").append(System.lineSeparator());

        injected.append("> #### ")
                .append(def.getTitle() == null ? "" : def.getTitle().trim())
                .append(System.lineSeparator());

        injected.append("> * -")
                .append(appliedLoss)
                .append(" points (")
                .append(rubricId)
                .append(")")
                .append(System.lineSeparator());

        String body = def.getBodyMarkdown() == null ? "" : def.getBodyMarkdown().trim();
        if (!body.isEmpty()) {
            for (String line : body.split("\\R")) {
                injected.append("> ").append(line).append(System.lineSeparator());
            }
        }

        injected.append("```").append(System.lineSeparator());
        injected.append(System.lineSeparator());


        int caret = reportEditor.getCaretPosition();
        String text = reportEditor.getText() == null ? "" : reportEditor.getText();

        caret = Math.max(0, Math.min(caret, text.length()));

        String out = text.substring(0, caret) + injected + text.substring(caret);

        int desiredCaret = Math.min(caret + injected.length(), out.length());

        setEditorTextPreservingCaret(currentStudent, out, desiredCaret, false);

        // IMPORTANT: rebuild must preserve caret
        rebuildRubricAndSummaryInEditor(desiredCaret, false);

        status("Inserted comment: " + def.getCommentId() + " (-" + appliedLoss + ")");
    }

    private String makeAnchorIdFromComment(CommentDef def) {
        String id = def.getCommentId() == null ? "" : def.getCommentId().trim();

        // fallback if missing
        if (id.isEmpty()) {
            return COMMENT_ANCHOR_PREFIX + System.currentTimeMillis();
        }

        // make safe for HTML id
        String safe = id.replaceAll("[^a-zA-Z0-9_-]", "_");
        return COMMENT_ANCHOR_PREFIX + safe;
    }

    private int getMaxPointsForRubricItem(String rubricItemId) {
        if (rubricItemId == null || rubricItemId.trim().isEmpty()) {
            return 0;
        }
        if (assignment == null || assignment.getRubric() == null
                || assignment.getRubric().getItems() == null) {
            return 0;
        }
        for (RubricItemRef ref : assignment.getRubric().getItems()) {
            if (ref != null && rubricItemId.equals(ref.getRubricItemId())) {
                return ref.getPoints();
            }
        }
        return 0;
    }

    private int computePointsLostInEditorForRubric(String rubricItemId) {
        if (rubricItemId == null || rubricItemId.trim().isEmpty()) {
            return 0;
        }

        String text = reportEditor.getText();
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int total = 0;

        // matches:
        // > * -10 points (ri_commits)
        // > * -1 points (ri_style)
        String[] lines = text.split("\\R");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String t = line.trim();

            if (!t.startsWith(">")) {
                continue;
            }
            if (!t.contains("(" + rubricItemId + ")")) {
                continue;
            }

            // Find "-N points"
            int dash = t.indexOf("-");
            int pointsWord = t.indexOf("points", dash);

            if (dash < 0 || pointsWord < 0) {
                continue;
            }

            String between = t.substring(dash + 1, pointsWord).trim(); // "10"
            try {
                int n = Integer.parseInt(between);
                if (n > 0) {
                    total += n;
                }
            } catch (NumberFormatException ignored) {
                // Should do nothing - will fix later
            }
        }
        return total;
    }

    @FXML
    private void onSaveDrafts() {
        beginSaveDrafts(null);
    }

    private void beginSaveDrafts(Runnable onComplete) {
        if (saveInProgress) {
            status("Save already in progress.");
            return;
        }

        saveInProgress = true;
        prepareCurrentDraftForSave();
        setSaveUiDisabled(true);

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "save-drafts");
            t.setDaemon(true);
            return t;
        });

        CompletableFuture
                .supplyAsync(this::saveDraftsWorker, exec)
                .thenAccept(result -> Platform.runLater(() ->
                        handleSaveDraftResult(result, onComplete)))
                .whenComplete((_, _) -> exec.shutdown());
    }

    private void handleSaveDraftResult(SaveDraftResult result,
                                       Runnable onComplete) {
        saveInProgress = false;
        setSaveUiDisabled(false);
        status(result.message());

        if (result.success() && onComplete != null) {
            onComplete.run();
        }
    }

    private void prepareCurrentDraftForSave() {
        int caret = reportEditor.getCaretPosition();
        rebuildRubricAndSummaryInEditor(caret, false);

        if (currentStudent != null) {
            String markdown = reportEditor.getText() == null ? "" : reportEditor.getText();
            draftSessionService.saveEditorState(currentStudent, markdown, caret);
        }
    }

    private SaveDraftResult saveDraftsWorker() {
        GradingSyncService.SaveDraftResult result = gradingSyncService.saveDrafts(
                new ArrayList<>(studentPackages),
                buildDraftAccess(),
                currentStudent,
                this::loadInitialMarkdownForStudent,
                this::findRepoDirForStudentPackage,
                gradingDraftService,
                assignmentId
        );
        return new SaveDraftResult(result.success(), result.message());
    }

    private void setSaveUiDisabled(boolean disabled) {
        if (saveAndExportButton != null) {
            saveAndExportButton.setDisable(disabled);
        }

        if (studentList != null) {
            studentList.setDisable(disabled);
        }

        if (reportEditor != null) {
            reportEditor.setDisable(disabled);
        }
    }

    @FXML
    private void onExportGradedHtml() {
        beginSaveDrafts(null);
    }

    private Path findRepoDirForStudentPackage(String studentPackage) {
        return gradingMappingsService.findRepoDirForStudentPackage(
                studentPackage,
                mappingsPath,
                assignmentId,
                rootPath,
                appDataDir()
        );
    }

    private String wrapMarkdownAsHtml(String title, String markdown) {
        return reportHtmlWrapper.wrapMarkdownAsHtml(title, markdown);
    }

    @FXML
    private void onSaveAndExport() {
        beginSaveDrafts(this::beginPushAllRepos);
    }

    private void beginPushAllRepos() {
        setSaveUiDisabled(true);

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "push-feedback");
            t.setDaemon(true);
            return t;
        });

        CompletableFuture.runAsync(() -> {
            PushResult r = pushAllRepos();
            Platform.runLater(() -> {
                String message = "Save/push complete: pushed " + r.pushed +
                        ", skipped " + r.skipped + ", failed " + r.failed + ".";

                if (!r.detailSummary.isBlank()) {
                    message = message + " " + r.detailSummary;
                }

                status(message);
                setSaveUiDisabled(false);
            });
        }, exec).whenComplete((_, _) -> exec.shutdown());
    }

    private PushResult pushAllRepos() {
        GradingSyncService.PushResult result = gradingSyncService.pushAllRepos(
                new ArrayList<>(studentPackages),
                this::findRepoDirForStudentPackage,
                assignmentId
        );
        return new PushResult(
                result.pushed(),
                result.skipped(),
                result.failed(),
                result.detailSummary()
        );
    }

    private PreflightResult preflightPush(Path repoDir,
                                          String reportFileName)
            throws IOException, InterruptedException {
        GradingSyncService.PreflightResult result =
                gradingSyncService.preflightPush(repoDir, reportFileName);
        return new PreflightResult(result.allowed(), result.message());
    }

    private String summarizeDetails(List<String> details) {
        return gradingSyncService.summarizeDetails(details);
    }

    @FXML
    private void onManageComments() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/ui/CommentManager.fxml"));
            Scene scene = new Scene(loader.load());

            CommentManagerController controller = loader.getController();
            controller.init(assignment, commentLibrary, assignmentsFile, commentsStore, commentsPath);

            Stage stage = new Stage();
            stage.setTitle("Manage Comments");
            stage.initOwner(studentList.getScene().getWindow());
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setScene(scene);
            stage.setMinWidth(800);
            stage.setMinHeight(600);

            stage.showAndWait();

            // Reload in case changed
            loadComments();

        } catch (IOException e) {
            status("Failed to open comment manager: " + e.getMessage());
        }
    }

    private static class PushResult {
        final int pushed;
        final int skipped;
        final int failed;
        final String detailSummary;

        PushResult(int pushed, int skipped, int failed, String detailSummary) {
            this.pushed = pushed;
            this.skipped = skipped;
            this.failed = failed;
            this.detailSummary = detailSummary;
        }
    }

    private record PreflightResult(boolean allowed,
                                   String message) {
    }

    private record SaveDraftResult(boolean success,
                                   String message) {
    }

    private GradingSyncService.DraftAccess buildDraftAccess() {
        return new GradingSyncService.DraftAccess() {
            @Override
            public boolean isLoadedFromDisk(String studentPackage) {
                return draftSessionService.isLoadedFromDisk(studentPackage);
            }

            @Override
            public String getMarkdown(String studentPackage) {
                return draftSessionService.getMarkdown(studentPackage);
            }

            @Override
            public void setMarkdown(String studentPackage, String markdown) {
                draftSessionService.setMarkdown(studentPackage, markdown);
            }

            @Override
            public void setLoadedFromDisk(String studentPackage,
                                          boolean loadedFromDisk) {
                draftSessionService.setLoadedFromDisk(studentPackage, loadedFromDisk);
            }

            @Override
            public void setCaretPosition(String studentPackage,
                                         int caretPosition) {
                draftSessionService.setCaretPosition(studentPackage, caretPosition);
            }
        };
    }


    @FXML
    private void onCloseAndSaveAll() {
        beginSaveDrafts(() -> {
            highlightExecutor.shutdownNow();
            Stage stage = (Stage) studentList.getScene().getWindow();
            stage.close();
        });
    }


    private void status(String msg) {
        if (statusLabel != null) {
            statusLabel.setText(msg == null ? "" : msg);
        }
    }

    private static Path appDataDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();

        Path base;
        if (os.contains("mac")) {
            base = Path.of(home, "Library", "Application Support");
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = (appData != null && !appData.isBlank())
                    ? Path.of(appData)
                    : Path.of(home, "AppData", "Roaming");
        } else {
            base = Path.of(home, ".local", "share");
        }

        return base.resolve("GHCU2");
    }

    private RemoveCommentPickerController.InjectedCommentRef
    openRemoveCommentPicker(List<RemoveCommentPickerController.InjectedCommentRef> refs) {

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/ui/RemoveCommentPicker.fxml"));
            Scene scene = new Scene(loader.load());

            RemoveCommentPickerController controller = loader.getController();
            controller.init(refs);

            Stage stage = new Stage();
            stage.setTitle("Remove Comment");
            stage.initOwner(studentList.getScene().getWindow());
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setScene(scene);
            stage.setMinWidth(900);
            stage.setMinHeight(550);

            stage.showAndWait();

            if (controller.isRemoved()) {
                return controller.getSelectedResult();
            }
            return null;

        } catch (IOException e) {
            status("Failed to open remove comment picker: " + e.getMessage());
            return null;
        }
    }

    private void centerCaretInViewport() {
        int para = reportEditor.getCurrentParagraph();
        reportEditor.showParagraphAtCenter(para);
    }

    private void setEditorTextPreservingCaret(String studentPackage,
                                              String newText,
                                              int caretToRestore,
                                              boolean centerCaret) {

        String safeText = newText == null ? "" : newText;

        suppressCaretEvents = true;
        suppressHighlighting = true;
        suppressTextListener = true;

        reportEditor.replaceText(safeText);

        suppressTextListener = false;
        suppressHighlighting = false;
        suppressCaretEvents = false;

        Platform.runLater(() -> {
            int caret = Math.max(0, Math.min(caretToRestore, reportEditor.getLength()));

            suppressCaretEvents = true;
            reportEditor.moveTo(caret);

            if (centerCaret) {
                centerCaretInViewport();
            }

            suppressCaretEvents = false;

            if (studentPackage != null) {
                draftSessionService.updateFromEditorIfPresent(
                        studentPackage,
                        reportEditor.getText(),
                        caret
                );
            }
        });
    }

    @FXML
    private void onPreviewDraft() {
        if (currentStudent == null) {
            status("Preview failed: no student selected.");
            return;
        }

        try {
            int caret = reportEditor.getCaretPosition();
            rebuildRubricAndSummaryInEditor(caret, false);

            String md = reportEditor.getText() == null ? "" : reportEditor.getText();
            String title = assignmentId + currentStudent;
            String html = wrapMarkdownAsHtml(title, md);

            Path previewDir = appDataDir().resolve("preview");
            Files.createDirectories(previewDir);

            Path out = previewDir.resolve(title + "_preview.html");
            Files.writeString(out, html, StandardCharsets.UTF_8);

            // MUST show window on FX thread
            Platform.runLater(() -> {
                try {
                    openPreviewWindow(out);
                    status("Preview opened: " + out.getFileName());
                } catch (Throwable t) {
                    t.printStackTrace();
                    status("Preview failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    showErrorDialog("Preview failed", t);
                }
            });

        } catch (Throwable t) {
            t.printStackTrace();
            status("Preview failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            showErrorDialog("Preview failed", t);
        }
    }

    private void showErrorDialog(String title, Throwable t) {
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setHeaderText(t.getClass().getName());
        a.setContentText(t.getMessage() == null ? "(no message)" : t.getMessage());
        a.showAndWait();
    }

    private void openPreviewWindow(Path htmlPath) {
        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();

        Label pathLabel = new Label(htmlPath.getFileName().toString());

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> {
            // Rebuild and re-write current editor state, then reload
            int caret = reportEditor.getCaretPosition();
            rebuildRubricAndSummaryInEditor(caret, false);

            String md = reportEditor.getText() == null ? "" : reportEditor.getText();
            String title = assignmentId + currentStudent;
            String html = wrapMarkdownAsHtml(title, md);

            try {
                Files.writeString(htmlPath, html, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                status("Preview refresh failed: " + ex.getMessage());
                return;
            }

            engine.reload();

            // keep caret centered after the rebuild
            restoreCaretAfterUpdate(currentStudent, caret);
        });

        ToolBar bar = new ToolBar(pathLabel, refreshBtn);

        BorderPane root = new BorderPane(webView);
        root.setTop(bar);
        BorderPane.setMargin(webView, new Insets(6));

        Scene scene = new Scene(root, 1000, 700);

        Stage stage = new Stage();
        stage.setTitle("Preview: Current Draft");
        stage.initOwner(studentList.getScene().getWindow());
        stage.initModality(Modality.NONE);
        stage.setScene(scene);
        // Load the file into the WebView
        engine.load(htmlPath.toUri().toString());
        stage.show();
    }

    private Path loadOrReconstructMappings() {
        Map<String, String> mapping = loadMappingsForUse();
        if (mapping.isEmpty()) {
            return null;
        }

        return resolveMappingFile();
    }

    private Map<String, String> loadMappingsForUse() {
        return gradingMappingsService.loadMappingsForUse(
                mappingsPath,
                assignmentId,
                rootPath,
                appDataDir()
        );
    }

    private Path resolveMappingFile() {
        return gradingMappingsService.resolveMappingFile(
                mappingsPath,
                assignmentId,
                appDataDir()
        );
    }
}

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
import javafx.scene.input.KeyEvent;
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
import service.GradingMarkdownSections;
import service.GradingMappingsService;
import service.GradingReportEditorService;
import service.GradingSyncService;
import service.ReportHtmlWrapper;
import util.AppDataUtil;

import java.io.IOException;
import java.awt.Desktop;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
    private static final String COMMENTS_SUMMARY_BEGIN = "<!-- COMMENTS_SUMMARY_BEGIN -->";
    private static final String COMMENTS_SUMMARY_END = "<!-- COMMENTS_SUMMARY_END -->";
    private static final String RUBRIC_TABLE_BEGIN = "<!-- RUBRIC_TABLE_BEGIN -->";
    private static final String RUBRIC_TABLE_END = "<!-- RUBRIC_TABLE_END -->";
    private static final String FEEDBACK_HEADER = "> # Feedback";
    private static final int MAX_LEGACY_NORMALIZE_PASSES = 5;
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
    @FXML
    private Button pushReportsButton;

    private final CommentsStore commentsStore = new CommentsStore();
    private CommentsLibrary commentLibrary;
    private final Path commentsPath = appDataDir().resolve("comments").resolve("comments.json");
    private final ReportHtmlWrapper reportHtmlWrapper = new ReportHtmlWrapper();
    private final GradingDraftService gradingDraftService =
            new GradingDraftService(reportHtmlWrapper);
    private final GradingMappingsService gradingMappingsService =
            new GradingMappingsService(this::status);
    private GradingReportEditorService gradingReportEditorService;
    private final GradingSyncService gradingSyncService = new GradingSyncService();
    private final GradingDraftSessionService draftSessionService =
            new GradingDraftSessionService();
    private final Set<String> reportLoadFailureStudents = Collections.synchronizedSet(
            new HashSet<>()
    );

    private AssignmentsFile assignmentsFile;
    private Assignment assignment;
    private Path rootPath;      // selectedRootPath from main window
    private Path mappingsPath;

    private String assignmentId;
    private String reportFilePrefix;

    private final ObservableList<String> studentPackages =
            FXCollections.observableArrayList();

    private String currentStudent = null;
    private boolean suppressCaretEvents = false;
    private boolean suppressHighlighting = false;
    private boolean suppressTextListener = false;
    private boolean isLoadingStudent = false;
    private boolean applyingHighlight = false;
    private boolean saveInProgress = false;
    private SaveDraftWorker saveDraftWorker = this::saveDraftsWorker;
    private PushAllWorker pushAllWorker = this::pushAllRepos;

    public void init(AssignmentsFile assignmentsFile, Assignment assignment, Path rootPath,
                     Path mappingsPath) {
        this.assignmentsFile = assignmentsFile;
        this.assignment = assignment;
        this.rootPath = rootPath;
        this.mappingsPath = mappingsPath;
        // rootPath/packages
        this.assignmentId = assignment.getCourseCode() + assignment.getAssignmentCode();
        this.reportFilePrefix = assignment.getAssignmentCode();
        if (reportFilePrefix == null || reportFilePrefix.isBlank()) {
            reportFilePrefix = assignmentId;
        }
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
        reportEditor.selectionProperty().addListener((_, _, newV) -> {
            if (!suppressCaretEvents && currentStudent != null && newV != null) {
                draftSessionService.updateSelectionIfPresent(
                        currentStudent,
                        newV.getStart(),
                        newV.getEnd()
                );
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
        Path loadPath = commentsPath;
        if (!Files.exists(loadPath)) {
            Path legacyComments = legacyCommentsPath();
            if (legacyComments != null && Files.exists(legacyComments)) {
                loadPath = legacyComments;
            }
        }
        try {
            commentLibrary = commentsStore.load(loadPath);
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

        reportEditor.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (undo.match(e)) {
                reportEditor.undo();
                e.consume();
            } else if (redo1.match(e) || redo2.match(e)) {
                reportEditor.redo();
                e.consume();
            } else if (handleNavigationKey(e)) {
                e.consume();
            }
        });
    }

    private boolean handleNavigationKey(KeyEvent event) {
        if (event == null) {
            return false;
        }
        if (currentStudent == null || reportEditor == null) {
            return false;
        }

        String text = reportEditor.getText() == null ? "" : reportEditor.getText();
        int caret = clampCaret(reportEditor.getCaretPosition(), text.length());
        KeyCode code = event.getCode();

        return switch (code) {
            case HOME -> {
                int target = event.isShortcutDown()
                        ? 0
                        : lineStartOffset(text, caret);
                reportEditor.moveTo(target);
                yield true;
            }
            case END -> {
                int target = event.isShortcutDown()
                        ? text.length()
                        : lineEndOffset(text, caret);
                reportEditor.moveTo(target);
                yield true;
            }
            case PAGE_UP -> {
                final int pageLineCount = 30;
                int target = moveCaretByLines(text, caret, -pageLineCount);
                reportEditor.moveTo(target);
                yield true;
            }
            case PAGE_DOWN -> {
                final int pageLineCount = 30;
                int target = moveCaretByLines(text, caret, pageLineCount);
                reportEditor.moveTo(target);
                yield true;
            }
            default -> false;
        };
    }

    private int moveCaretByLines(String text, int caret, int lineDelta) {
        String safeText = text == null ? "" : text;
        int safeCaret = clampCaret(caret, safeText.length());
        int currentLineStart = lineStartOffset(safeText, safeCaret);
        int column = safeCaret - currentLineStart;
        int targetLineStart = currentLineStart;
        int steps = Math.abs(lineDelta);
        boolean movingUp = lineDelta < 0;

        for (int i = 0; i < steps; i++) {
            if (movingUp) {
                if (targetLineStart <= 0) {
                    break;
                }
                targetLineStart = previousLineStart(safeText, targetLineStart);
            } else {
                if (targetLineStart >= safeText.length()) {
                    break;
                }
                int next = nextLineStart(safeText, targetLineStart);
                if (next == targetLineStart) {
                    break;
                }
                targetLineStart = next;
            }
        }

        int targetLineEnd = lineEndOffset(safeText, targetLineStart);
        int targetColumn = Math.min(column, Math.max(0, targetLineEnd - targetLineStart));
        return targetLineStart + targetColumn;
    }

    private int previousLineStart(String text, int fromLineStart) {
        int cursor = clampCaret(fromLineStart, text.length());
        while (cursor > 0 && isNewlineChar(text.charAt(cursor - 1))) {
            cursor--;
        }
        while (cursor > 0 && !isNewlineChar(text.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    private int nextLineStart(String text, int fromLineStart) {
        int safeStart = clampCaret(fromLineStart, text.length());
        int end = lineEndOffset(text, safeStart);
        int cursor = end;
        while (cursor < text.length() && isNewlineChar(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int lineStartOffset(String text, int caret) {
        int cursor = clampCaret(caret, text.length());
        while (cursor > 0 && !isNewlineChar(text.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    private int lineEndOffset(String text, int caret) {
        int cursor = clampCaret(caret, text.length());
        while (cursor < text.length() && !isNewlineChar(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int clampCaret(int caret, int length) {
        return Math.max(0, Math.min(caret, length));
    }

    private boolean isNewlineChar(char ch) {
        return ch == '\n' || ch == '\r';
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

        try {
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
        } catch (RejectedExecutionException ignored) {
            // Stage may be closing and the highlighter executor already shut down.
        }
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
        int liveSelectionStart = reportEditor.getSelection().getStart();
        int liveSelectionEnd = reportEditor.getSelection().getEnd();
        int selectionStart = liveSelectionStart;
        int selectionEnd = liveSelectionEnd;
        if (liveSelectionStart == liveSelectionEnd) {
            int savedSelectionStart = draftSessionService.getSelectionStart(studentPackage);
            int savedSelectionEnd = draftSessionService.getSelectionEnd(studentPackage);
            if (savedSelectionStart != savedSelectionEnd) {
                selectionStart = savedSelectionStart;
                selectionEnd = savedSelectionEnd;
            }
        }
        String text = reportEditor.getText() == null ? "" : reportEditor.getText();
        draftSessionService.saveEditorState(
                studentPackage,
                text,
                selectionStart,
                selectionEnd
        );
    }

    private void restoreCaretAfterUpdate(String studentPackage, int caretToRestore) {
        restoreSelectionAfterUpdate(
                studentPackage,
                caretToRestore,
                caretToRestore,
                true
        );
    }

    private void restoreSelectionAfterUpdate(String studentPackage,
                                             int selectionStartToRestore,
                                             int selectionEndToRestore,
                                             boolean centerCaret) {
        Runnable restoreTask = () -> {
            if (studentPackage != null && !Objects.equals(currentStudent, studentPackage)) {
                return;
            }
            int selectionStart = Math.max(
                    0,
                    Math.min(selectionStartToRestore, reportEditor.getLength())
            );
            int selectionEnd = Math.max(
                    0,
                    Math.min(selectionEndToRestore, reportEditor.getLength())
            );
            if (selectionEnd < selectionStart) {
                int tmp = selectionStart;
                selectionStart = selectionEnd;
                selectionEnd = tmp;
            }

            suppressCaretEvents = true;
            if (selectionStart == selectionEnd) {
                reportEditor.moveTo(selectionEnd);
            } else {
                reportEditor.selectRange(selectionStart, selectionEnd);
            }

            if (centerCaret) {
                centerCaretInViewport();
            }

            suppressCaretEvents = false;

            if (studentPackage != null) {
                draftSessionService.updateFromEditorIfPresent(
                        studentPackage,
                        reportEditor.getText(),
                        reportEditor.getSelection().getStart(),
                        reportEditor.getSelection().getEnd()
                );
            }
        };
        if (Platform.isFxApplicationThread()) {
            restoreTask.run();
        } else {
            Platform.runLater(restoreTask);
        }
    }

    private void loadDraftIntoEditor(String studentPackage) {
        currentStudent = studentPackage;
        boolean needsReload = draftSessionService.needsReload(studentPackage);
        if (needsReload) {
            String md = loadInitialMarkdownForStudent(studentPackage);
            md = normalizeForLegacyEditorView(md);
            draftSessionService.setMarkdown(studentPackage, md);
            draftSessionService.setLoadedFromDisk(studentPackage, true);
            // First load should always start at top of report.
            draftSessionService.setCaretPosition(studentPackage, 0);
        }
        int desiredCaret = draftSessionService.getCaretPosition(studentPackage);
        int selectionStart = draftSessionService.getSelectionStart(studentPackage);
        int selectionEnd = draftSessionService.getSelectionEnd(studentPackage);
        setEditorTextPreservingCaret(
                currentStudent,
                draftSessionService.getMarkdown(studentPackage),
                desiredCaret,
                selectionStart,
                selectionEnd,
                true
        );

        // rebuild modifies text; it must also restore caret again AFTER it finishes
        rebuildRubricAndSummaryInEditor(desiredCaret, true);
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
            GradingDraftService.LoadReportResult loadResult = gradingDraftService
                    .loadReportMarkdownResult(
                    effectiveReportFilePrefix(),
                    studentPackage,
                    repoDir
            );
            if (!loadResult.readOk()) {
                reportLoadFailureStudents.add(studentPackage);
                String message = loadResult.message();
                if (message == null || message.isBlank()) {
                    message = "unknown error";
                }
                status("Failed to load report for " + studentPackage + ": " + message);
                return buildFreshReportSkeleton(studentPackage);
            }

            reportLoadFailureStudents.remove(studentPackage);
            String markdown = loadResult.markdown();

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
            InsertCommentTransaction insertTransaction = captureInsertCommentTransaction();
            CommentDef selected = openCommentPicker();
            if (selected != null) {
                applyCommentToEditor(selected, insertTransaction);
            }
        }
    }

    private InsertCommentTransaction captureInsertCommentTransaction() {
        String studentAtClick = currentStudent;
        int caretAtClick = reportEditor.getCaretPosition();
        String baseText = reportEditor.getText() == null ? "" : reportEditor.getText();
        int baseTextHash = baseText.hashCode();
        return new InsertCommentTransaction(studentAtClick, caretAtClick, baseText, baseTextHash);
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
        List<ParsedComment> previousComments = Comments.parseInjectedComments(text);
        RemovalResult result = removeInjectedCommentBlock(text, anchorId);
        if (!result.found()) {
            status("Could not find comment anchor: " + anchorId);
            return;
        }

        String updated = result.updatedText();
        int start = result.startIndex();
        int cursor = result.endIndexExclusive();

        // compute caret after delete
        int desiredCaret = caret;
        if (caret > start && caret < cursor) {
            desiredCaret = start;
        }
        desiredCaret = Math.max(0, Math.min(desiredCaret, updated.length()));

        setEditorTextPreservingCaret(currentStudent, updated, desiredCaret, false);

        // rebuild once (NO centering)
        rebuildRubricAndSummaryInEditor(desiredCaret, false, previousComments);

        status("Removed comment.");
    }

    static RemovalResult removeInjectedCommentBlock(String text, String anchorId) {
        if (text == null || anchorId == null || anchorId.isBlank()) {
            return new RemovalResult(text == null ? "" : text, false, -1, -1);
        }

        String anchorLine = "<a id=\"" + anchorId + "\"></a>";
        int start = text.indexOf(anchorLine);
        if (start < 0) {
            return new RemovalResult(text, false, -1, -1);
        }

        int cursor = start;
        int lineEnd = text.indexOf("\n", cursor);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        cursor = Math.min(text.length(), lineEnd + 1);

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
                if (line.isEmpty() || line.startsWith(">") || line.startsWith("```")) {
                    cursor = Math.min(text.length(), nextLineEnd + 1);
                } else {
                    break;
                }
            }
        }

        String updated = text.substring(0, start) + text.substring(cursor);
        return new RemovalResult(updated, true, start, cursor);
    }

    @FXML
    private void onRebuildSummary() {
        rebuildRubricAndSummaryInEditor(reportEditor.getCaretPosition(), false);
        status("Rebuilt rubric + comment summary.");
    }

    private void rebuildRubricAndSummaryInEditor(int caretToRestore, boolean centerCaret) {
        String text = reportEditor.getText() == null ? "" : reportEditor.getText();
        List<ParsedComment> previousComments = Comments.parseInjectedComments(text);
        rebuildRubricAndSummaryInEditor(caretToRestore, centerCaret, previousComments);
    }

    private void rebuildRubricAndSummaryInEditor(int caretToRestore,
                                                 boolean centerCaret,
                                                 List<ParsedComment> previousComments) {
        String text = reportEditor.getText() == null ? "" : reportEditor.getText();
        int selectionStartToRestore = currentStudent == null
                ? reportEditor.getSelection().getStart()
                : draftSessionService.getSelectionStart(currentStudent);
        int selectionEndToRestore = currentStudent == null
                ? reportEditor.getSelection().getEnd()
                : draftSessionService.getSelectionEnd(currentStudent);
        String canonical = normalizeRubricAndSummaryBlocks(text, currentStudent);
        String updated = rebuildRubricAndSummaryText(
                canonical,
                previousComments,
                gradingReportEditorService
        );
        updated = normalizeForLegacyEditorViewToFixedPoint(updated);
        if (!updated.equals(text)) {
            int selectionStartForUpdated = selectionStartToRestore;
            int selectionEndForUpdated = selectionEndToRestore;
            if (selectionEndToRestore > selectionStartToRestore
                    && selectionStartToRestore >= 0
                    && selectionEndToRestore <= text.length()) {
                String selectedText = text.substring(selectionStartToRestore, selectionEndToRestore);
                if (!selectedText.isEmpty()) {
                    int relocatedStart = updated.indexOf(selectedText);
                    if (relocatedStart >= 0) {
                        selectionStartForUpdated = relocatedStart;
                        selectionEndForUpdated = relocatedStart + selectedText.length();
                    }
                }
            }
            setEditorTextPreservingCaret(
                    currentStudent,
                    updated,
                    caretToRestore,
                    selectionStartForUpdated,
                    selectionEndForUpdated,
                    centerCaret
            );
        } else {
            // Even if no text changed, still restore editor position reliably.
            restoreSelectionAfterUpdate(
                    currentStudent,
                    selectionStartToRestore,
                    selectionEndToRestore,
                    centerCaret
            );
        }
    }

    static String rebuildRubricAndSummaryText(String text,
                                              GradingReportEditorService editorService) {
        String safeText = text == null ? "" : text;
        List<ParsedComment> comments = Comments.parseInjectedComments(safeText);
        return editorService.rebuildRubricAndSummary(safeText, comments);
    }

    static String rebuildRubricAndSummaryText(String text,
                                              List<ParsedComment> previousComments,
                                              GradingReportEditorService editorService) {
        String safeText = text == null ? "" : text;
        String withSummary = ensureSummaryBlockForRebuild(safeText, previousComments);
        List<ParsedComment> comments = Comments.parseInjectedComments(safeText);
        return editorService.rebuildRubricAndSummary(withSummary, comments);
    }

    private static String ensureSummaryBlockForRebuild(String text,
                                                       List<ParsedComment> previousComments) {
        String safeText = text == null ? "" : text;
        String withoutSummary = GradingMarkdownSections.removeAllBlocks(
                safeText,
                COMMENTS_SUMMARY_BEGIN,
                COMMENTS_SUMMARY_END
        );
        String newline = System.lineSeparator();
        StringBuilder summary = new StringBuilder();
        summary.append(">> # Comments").append(newline);
        summary.append(">>").append(newline);
        if (previousComments == null || previousComments.isEmpty()) {
            summary.append(">> * _No comments._").append(newline);
        } else {
            for (ParsedComment comment : previousComments) {
                if (comment == null) {
                    continue;
                }
                String title = comment.title() == null ? "" : comment.title();
                String anchor = comment.anchorId() == null ? "" : comment.anchorId();
                String rubric = comment.rubricItemId() == null ? "" : comment.rubricItemId();
                int pointsLost = Math.max(0, comment.pointsLost());
                summary.append(">> * [")
                        .append(title)
                        .append("](#")
                        .append(anchor)
                        .append(") (-")
                        .append(pointsLost)
                        .append(" ")
                        .append(rubric)
                        .append(")")
                        .append(newline);
            }
        }

        String block = COMMENTS_SUMMARY_BEGIN + newline
                + summary.toString().trim()
                + newline
                + COMMENTS_SUMMARY_END;

        String prefix = withoutSummary.endsWith(newline) ? withoutSummary : withoutSummary + newline;
        return prefix + newline + block + newline;
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

    private void applyCommentToEditor(CommentDef def, InsertCommentTransaction insertTransaction) {
        if (insertTransaction == null) {
            status("Insert Comment failed: missing insertion context.");
            return;
        }
        if (!Objects.equals(currentStudent, insertTransaction.studentPackage())) {
            status("Insert Comment cancelled: active student changed while picker was open.");
            return;
        }

        String currentEditorText = reportEditor.getText() == null ? "" : reportEditor.getText();
        if (currentEditorText.hashCode() != insertTransaction.baseTextHash()
                || !currentEditorText.equals(insertTransaction.baseText())) {
            status("Insert Comment cancelled: report changed while picker was open.");
            return;
        }

        String rubricId = def.getRubricItemId();
        int requestedLoss = Math.max(0, def.getPointsDeducted());

        int maxForRubric = getMaxPointsForRubricItem(rubricId);

        int alreadyLost = computePointsLostInTextForRubric(insertTransaction.baseText(), rubricId);
        int appliedLoss = computeAppliedLoss(requestedLoss, maxForRubric, alreadyLost);

        String anchorId = makeAnchorIdFromComment(def);
        String injected = buildInjectedCommentMarkdown(def, anchorId, appliedLoss);


        int caret = insertTransaction.caretPosition();
        String text = insertTransaction.baseText();
        List<ParsedComment> previousComments = Comments.parseInjectedComments(text);

        caret = Math.max(0, Math.min(caret, text.length()));
        InsertBlockResult insertResult = insertCanonicalCommentBlock(text, injected, caret);
        String out = insertResult.updatedText();
        int desiredCaret = insertResult.caretAfterBlock();

        setEditorTextPreservingCaret(currentStudent, out, desiredCaret, false);

        // IMPORTANT: rebuild must preserve caret
        rebuildRubricAndSummaryInEditor(desiredCaret, false, previousComments);
        restoreCaretToInsertedAnchor(anchorId, desiredCaret);

        status("Inserted comment: " + def.getCommentId() + " (-" + appliedLoss + ")");
    }

    private void restoreCaretToInsertedAnchor(String anchorId, int fallbackCaret) {
        Platform.runLater(() -> Platform.runLater(() -> {
            restoreCaretAfterUpdate(currentStudent, fallbackCaret);
        }));
    }

    static InsertBlockResult insertCanonicalCommentBlock(String text,
                                                         String block,
                                                         int caret) {
        String safeText = text == null ? "" : text;
        String safeBlock = block == null ? "" : block;
        int safeCaret = Math.max(0, Math.min(caret, safeText.length()));
        int insertAt = normalizeInsertionPositionForCommentBlock(safeText, safeCaret);

        String before = safeText.substring(0, insertAt);
        String after = safeText.substring(insertAt);

        String newline = System.lineSeparator();
        String prefixSeparator = (before.isEmpty() || endsWithLineBreak(before)) ? "" : newline;
        String suffixSeparator = (after.isEmpty() || startsWithLineBreak(after)) ? "" : newline;

        StringBuilder out = new StringBuilder(
                safeText.length()
                        + safeBlock.length()
                        + prefixSeparator.length()
                        + suffixSeparator.length()
        );
        out.append(before).append(prefixSeparator).append(safeBlock);
        int caretAfterBlock = out.length();
        out.append(suffixSeparator).append(after);
        return new InsertBlockResult(out.toString(), caretAfterBlock);
    }

    static int normalizeInsertionPositionForCommentBlock(String text, int caret) {
        String safeText = text == null ? "" : text;
        int safeCaret = Math.max(0, Math.min(caret, safeText.length()));
        if (safeText.isEmpty()) {
            return 0;
        }

        int lineStart = safeText.lastIndexOf('\n', Math.max(0, safeCaret - 1));
        lineStart = lineStart < 0 ? 0 : lineStart + 1;

        int lineEnd = safeText.indexOf('\n', safeCaret);
        lineEnd = lineEnd < 0 ? safeText.length() : lineEnd;

        if (safeCaret == lineStart || safeCaret == lineEnd) {
            return safeCaret;
        }

        String line = safeText.substring(lineStart, lineEnd);
        if (line.trim().isEmpty()) {
            return lineStart;
        }
        return lineEnd;
    }

    private static boolean endsWithLineBreak(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char last = value.charAt(value.length() - 1);
        return last == '\n' || last == '\r';
    }

    private static boolean startsWithLineBreak(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '\n' || first == '\r';
    }

    private record InsertCommentTransaction(String studentPackage,
                                            int caretPosition,
                                            String baseText,
                                            int baseTextHash) {
    }

    record InsertBlockResult(String updatedText,
                             int caretAfterBlock) {
    }

    static int computeAppliedLoss(int requestedLoss,
                                  int maxForRubric,
                                  int alreadyLost) {
        int clampedRequested = Math.max(0, requestedLoss);
        int remaining = Math.max(0, maxForRubric - alreadyLost);
        return Math.min(clampedRequested, remaining);
    }

    static String buildInjectedCommentMarkdown(CommentDef def,
                                               String anchorId,
                                               int appliedLoss) {
        String rubricId = def.getRubricItemId();
        StringBuilder injected = new StringBuilder();

        injected.append("<a id=\"").append(anchorId).append("\"></a>")
                .append(System.lineSeparator());
        injected.append("<!-- cmt-meta rubric:")
                .append(rubricId == null ? "" : rubricId)
                .append(" -->")
                .append(System.lineSeparator());
        injected.append("```").append(System.lineSeparator());
        injected.append("> #### ")
                .append(formatCommentHeaderPrefix(appliedLoss))
                .append(def.getTitle() == null ? "" : def.getTitle().trim())
                .append(System.lineSeparator());

        String body = def.getBodyMarkdown() == null ? "" : def.getBodyMarkdown().trim();
        if (!body.isEmpty()) {
            for (String line : body.split("\\R")) {
                injected.append("> ").append(line).append(System.lineSeparator());
            }
        }

        injected.append(System.lineSeparator());
        injected.append("```").append(System.lineSeparator());
        injected.append(System.lineSeparator());
        return injected.toString();
    }

    private static String formatCommentHeaderPrefix(int appliedLoss) {
        if (appliedLoss <= 0) {
            return "";
        }
        return "-" + appliedLoss + " ";
    }

    private String makeAnchorIdFromComment(CommentDef def) {
        String id = def.getCommentId() == null ? "" : def.getCommentId().trim();

        // fallback if missing
        if (id.isEmpty()) {
            return COMMENT_ANCHOR_PREFIX + "generated";
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
        String text = reportEditor.getText();
        return computePointsLostInTextForRubric(text, rubricItemId);
    }

    static int computePointsLostInTextForRubric(String text, String rubricItemId) {
        if (rubricItemId == null || rubricItemId.trim().isEmpty()) {
            return 0;
        }
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int parsedTotal = 0;
        List<ParsedComment> parsedComments = Comments.parseInjectedComments(text);
        for (ParsedComment comment : parsedComments) {
            if (comment == null) {
                continue;
            }
            if (!rubricItemId.equals(comment.rubricItemId())) {
                continue;
            }
            parsedTotal += Math.max(0, comment.pointsLost());
        }
        if (parsedTotal > 0) {
            return parsedTotal;
        }

        // Legacy fallback for older drafts that only have points lines.
        int total = 0;
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

            int dash = t.indexOf("-");
            int pointsWord = t.indexOf("points", dash);
            if (dash < 0 || pointsWord < 0) {
                continue;
            }

            String between = t.substring(dash + 1, pointsWord).trim();
            try {
                int n = Integer.parseInt(between);
                if (n > 0) {
                    total += n;
                }
            } catch (NumberFormatException ignored) {
                // malformed points are ignored
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
                .supplyAsync(saveDraftWorker::run, exec)
                .whenComplete((result, throwable) -> Platform.runLater(() -> {
                    if (throwable != null) {
                        handleSaveDraftFailure(throwable);
                    } else {
                        handleSaveDraftResult(result, onComplete);
                    }
                }))
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

    private void handleSaveDraftFailure(Throwable throwable) {
        saveInProgress = false;
        setSaveUiDisabled(false);
        status("Save failed: " + rootCauseMessage(throwable));
    }

    private void prepareCurrentDraftForSave() {
        int caret = reportEditor.getCaretPosition();
        rebuildRubricAndSummaryInEditor(caret, false);

        if (currentStudent != null) {
            String markdown = reportEditor.getText() == null ? "" : reportEditor.getText();
            draftSessionService.saveEditorState(
                    currentStudent,
                    markdown,
                    reportEditor.getSelection().getStart(),
                    reportEditor.getSelection().getEnd()
            );
        }
    }

    private SaveDraftResult saveDraftsWorker() {
        List<String> allStudents = new ArrayList<>(studentPackages);
        Set<String> failedLoads;
        synchronized (reportLoadFailureStudents) {
            failedLoads = new HashSet<>(reportLoadFailureStudents);
        }
        List<String> studentsToSave = new ArrayList<>();
        for (String studentPackage : allStudents) {
            if (!failedLoads.contains(studentPackage)) {
                studentsToSave.add(studentPackage);
            }
        }
        int skippedForLoadFailure = allStudents.size() - studentsToSave.size();

        GradingSyncService.SaveDraftResult result = gradingSyncService.saveDrafts(
                studentsToSave,
                buildDraftAccess(),
                currentStudent,
                this::loadInitialMarkdownForStudent,
                this::findRepoDirForStudentPackage,
                gradingDraftService,
                effectiveReportFilePrefix(),
                rootPath
        );
        String message = result.message();
        if (skippedForLoadFailure > 0) {
            String skippedMessage = "Skipped " + skippedForLoadFailure
                    + " report(s) due to load errors.";
            if (message == null || message.isBlank()) {
                message = skippedMessage;
            } else {
                message = message + " " + skippedMessage;
            }
        }
        boolean success = result.success() && skippedForLoadFailure == 0;
        return new SaveDraftResult(success, message);
    }

    private void setSaveUiDisabled(boolean disabled) {
        if (saveAndExportButton != null) {
            saveAndExportButton.setDisable(disabled);
        }
        if (pushReportsButton != null) {
            pushReportsButton.setDisable(disabled);
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
                effectiveReportFilePrefix(),
                rootPath,
                appDataDir()
        );
    }

    private String wrapMarkdownAsHtml(String title, String markdown) {
        String safeMarkdown = sanitizePreviewMarkdown(markdown);
        return reportHtmlWrapper.wrapMarkdownAsHtml(title, safeMarkdown);
    }

    static String sanitizePreviewMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown == null ? "" : markdown;
        }

        String[] lines = markdown.split("\\R", -1);
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        boolean inFence = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                out.append(line);
            } else if (inFence) {
                out.append(escapePipeAndAngleBrackets(line));
            } else {
                out.append(line);
            }

            if (i < lines.length - 1) {
                out.append(System.lineSeparator());
            }
        }

        return out.toString();
    }

    private static String escapePipeAndAngleBrackets(String line) {
        if (line == null || line.isEmpty()) {
            return line == null ? "" : line;
        }

        String escaped = line.replace("&", "&amp;");
        escaped = escaped.replace("<", "&lt;");
        escaped = escaped.replace(">", "&gt;");
        return escaped.replace("|", "&#124;");
    }

    @FXML
    private void onSaveAndExport() {
        beginSaveDrafts(this::beginPushAllRepos);
    }

    @FXML
    private void onPushReports() {
        if (saveInProgress) {
            status("Save already in progress.");
            return;
        }
        beginPushAllRepos();
    }

    private void beginPushAllRepos() {
        setSaveUiDisabled(true);

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "push-feedback");
            t.setDaemon(true);
            return t;
        });

        CompletableFuture
                .supplyAsync(pushAllWorker::run, exec)
                .whenComplete((result, throwable) -> Platform.runLater(() -> {
                    if (throwable != null) {
                        status("Save/push failed: " + rootCauseMessage(throwable));
                    } else {
                        status(formatPushCompletionMessage(
                                result.pushed,
                                result.skipped,
                                result.failed,
                                result.detailSummary
                        ));
                    }
                    setSaveUiDisabled(false);
                }))
                .whenComplete((_, _) -> exec.shutdown());
    }

    static String formatPushCompletionMessage(int pushed,
                                              int skipped,
                                              int failed,
                                              String detailSummary) {
        String message = "Save/push complete: pushed " + pushed
                + ", skipped " + skipped + ", failed " + failed + ".";

        if (detailSummary != null && !detailSummary.isBlank()) {
            message = message + " " + detailSummary;
        }
        return message;
    }

    private PushResult pushAllRepos() {
        GradingSyncService.PushResult result = gradingSyncService.pushAllRepos(
                new ArrayList<>(studentPackages),
                this::findRepoDirForStudentPackage,
                effectiveReportFilePrefix()
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

    record RemovalResult(String updatedText,
                         boolean found,
                         int startIndex,
                         int endIndexExclusive) {
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
        if (statusLabel == null) {
            return;
        }
        String safeMsg = msg == null ? "" : msg;
        if (Platform.isFxApplicationThread()) {
            statusLabel.setText(safeMsg);
        } else {
            Platform.runLater(() -> statusLabel.setText(safeMsg));
        }
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        if (message == null || message.isBlank()) {
            return current == null ? "unknown failure" : current.getClass().getSimpleName();
        }
        return message;
    }

    private static Path appDataDir() {
        return AppDataUtil.appDataDir();
    }

    private static Path legacyCommentsPath() {
        return AppDataUtil.legacyGhcu2AppDataDir().resolve("comments.json");
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
        setEditorTextPreservingCaret(
                studentPackage,
                newText,
                caretToRestore,
                caretToRestore,
                caretToRestore,
                centerCaret
        );
    }

    private void setEditorTextPreservingCaret(String studentPackage,
                                              String newText,
                                              int caretToRestore,
                                              int selectionStartToRestore,
                                              int selectionEndToRestore,
                                              boolean centerCaret) {

        String safeText = newText == null ? "" : newText;

        suppressCaretEvents = true;
        suppressHighlighting = true;
        suppressTextListener = true;

        reportEditor.replaceText(safeText);

        suppressTextListener = false;
        suppressHighlighting = false;
        suppressCaretEvents = false;

        int resolvedSelectionStart = selectionStartToRestore;
        int resolvedSelectionEnd = selectionEndToRestore;
        if (resolvedSelectionStart == resolvedSelectionEnd) {
            resolvedSelectionStart = caretToRestore;
            resolvedSelectionEnd = caretToRestore;
        }
        restoreSelectionAfterUpdate(
                studentPackage,
                resolvedSelectionStart,
                resolvedSelectionEnd,
                centerCaret
        );
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
            String title = previewTitle(effectiveReportFilePrefix(), currentStudent);
            String html = wrapMarkdownAsHtml(title, md);

            Path previewDir = appDataDir().resolve("preview");
            Files.createDirectories(previewDir);

            Path out = previewDir.resolve(previewFileName(title));
            Files.writeString(out, html, StandardCharsets.UTF_8);
            writeLegacyPreviewCopy(title, html, out);

            // MUST show window on FX thread
            Platform.runLater(() -> {
                try {
                    openPreviewWindow(out);
                    status("Preview opened: " + out.getFileName());
                } catch (Throwable t) {
                    if (tryOpenPreviewInSystemBrowser(out, t)) {
                        return;
                    }
                    status("Preview failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    showErrorDialog("Preview failed", t);
                }
            });

        } catch (Throwable t) {
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
            String title = previewTitle(effectiveReportFilePrefix(), currentStudent);
            String html = wrapMarkdownAsHtml(title, md);

            try {
                Files.writeString(htmlPath, html, StandardCharsets.UTF_8);
                writeLegacyPreviewCopy(title, html, htmlPath);
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

    private boolean tryOpenPreviewInSystemBrowser(Path htmlPath, Throwable cause) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return false;
            }
            desktop.browse(htmlPath.toUri());
            status("Preview opened in system browser: " + htmlPath.getFileName()
                    + " (embedded preview unavailable: "
                    + cause.getClass().getSimpleName() + ")");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String previewTitle(String assignmentId, String currentStudent) {
        String safeAssignment = assignmentId == null ? "" : assignmentId;
        String safeStudent = currentStudent == null ? "" : currentStudent;
        return safeAssignment + safeStudent;
    }

    static String previewFileName(String title) {
        return title + "_preview.html";
    }

    private void writeLegacyPreviewCopy(String title,
                                        String html,
                                        Path canonicalPath) throws IOException {
        Path legacyPreviewDir = AppDataUtil.legacyGhcu2AppDataDir().resolve("preview");
        Files.createDirectories(legacyPreviewDir);
        Path legacyPath = legacyPreviewDir.resolve(previewFileName(title));
        if (!legacyPath.equals(canonicalPath)) {
            Files.writeString(legacyPath, html, StandardCharsets.UTF_8);
        }
    }

    private String normalizeForLegacyEditorView(String markdown) {
        String out = markdown == null ? "" : markdown;
        int preambleBoundary = findPreambleBoundary(out);
        String preamble = out.substring(0, preambleBoundary);
        String remainder = out.substring(preambleBoundary);

        preamble = replaceRubricPatchBlockWithRawTable(preamble);
        preamble = GradingMarkdownSections.removeAllBlocks(
                preamble,
                COMMENTS_SUMMARY_BEGIN,
                COMMENTS_SUMMARY_END
        );
        preamble = removeTotalRowFromRubricTable(preamble);
        preamble = ensureRubricTableBeforeFeedback(preamble);
        preamble = normalizeSpacingBeforeRubricTable(preamble);
        preamble = normalizeSpacingBetweenRubricAndFeedback(preamble);
        preamble = normalizeSpacingAfterFeedbackBlock(preamble);

        out = preamble + remainder;
        return out.replaceFirst("(?s)\\R+\\z", "");
    }

    private int findPreambleBoundary(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return 0;
        }
        Pattern majorSectionPattern = Pattern.compile(
                "(?m)^##\\s+(Source Code|Checkstyle Violations|Failed Unit Tests|Commit History\\b).*"
        );
        Matcher matcher = majorSectionPattern.matcher(markdown);
        if (matcher.find()) {
            return matcher.start();
        }
        return markdown.length();
    }

    private String normalizeForLegacyEditorViewToFixedPoint(String markdown) {
        String current = normalizeForLegacyEditorView(markdown);
        for (int i = 0; i < MAX_LEGACY_NORMALIZE_PASSES; i++) {
            String next = normalizeForLegacyEditorView(current);
            if (next.equals(current)) {
                return next;
            }
            current = next;
        }
        return current;
    }

    private String normalizeSpacingBetweenRubricAndFeedback(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }

        final Pattern tableHeaderPattern = Pattern.compile(
                "(?m)^[ \\t]*>>[ \\t]*\\|[ \\t]*Earned[ \\t]*\\|[ \\t]*Possible"
                        + "[ \\t]*\\|[ \\t]*Criteria.*$"
        );
        final Pattern feedbackHeaderPattern = Pattern.compile(
                "(?m)^[ \\t]*>[ \\t]*#[ \\t]*Feedback\\b.*$"
        );

        Matcher tableMatcher = tableHeaderPattern.matcher(markdown);
        Matcher feedbackMatcher = feedbackHeaderPattern.matcher(markdown);
        if (!tableMatcher.find() || !feedbackMatcher.find()) {
            return markdown;
        }

        int tableStart = tableMatcher.start();
        int feedbackStart = feedbackMatcher.start();
        if (tableStart >= feedbackStart) {
            return markdown;
        }

        int lineStart = markdown.lastIndexOf('\n', tableStart);
        lineStart = lineStart < 0 ? 0 : lineStart + 1;

        int cursor = lineStart;
        while (cursor < markdown.length()) {
            int nextLineBreak = markdown.indexOf('\n', cursor);
            int lineEnd = nextLineBreak < 0 ? markdown.length() : nextLineBreak;
            String line = markdown.substring(cursor, lineEnd).trim();
            if (!line.startsWith(">> |")) {
                break;
            }
            cursor = nextLineBreak < 0 ? markdown.length() : nextLineBreak + 1;
        }
        int tableEnd = cursor;
        if (tableEnd >= feedbackStart) {
            return markdown;
        }

        String before = markdown.substring(0, tableEnd).replaceFirst("(?s)\\R*\\z", "");
        String after = markdown.substring(feedbackStart);
        return before
                + System.lineSeparator()
                + ">"
                + System.lineSeparator()
                + ">"
                + System.lineSeparator()
                + after;
    }

    private String normalizeSpacingBeforeRubricTable(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }

        final Pattern tableHeaderPattern = Pattern.compile(
                "(?m)^[ \\t]*>>[ \\t]*\\|[ \\t]*Earned[ \\t]*\\|[ \\t]*Possible"
                        + "[ \\t]*\\|[ \\t]*Criteria.*$"
        );
        Matcher tableMatcher = tableHeaderPattern.matcher(markdown);
        if (!tableMatcher.find()) {
            return markdown;
        }

        int tableLineStart = markdown.lastIndexOf('\n', tableMatcher.start());
        tableLineStart = tableLineStart < 0 ? 0 : tableLineStart + 1;
        String before = markdown.substring(0, tableLineStart).replaceFirst("(?s)\\R+\\z", "");
        String after = markdown.substring(tableLineStart);

        return before + System.lineSeparator() + System.lineSeparator() + after;
    }

    private String normalizeSpacingAfterFeedbackBlock(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }

        final Pattern feedbackBlockPattern = Pattern.compile(
                "(?m)(^[ \\t]*>[ \\t]*#[ \\t]*Feedback[^\\r\\n]*\\R"
                        + "(?:^[ \\t]*>[^\\r\\n]*\\R)*)(\\R{3,})(?=\\S)"
        );
        Matcher matcher = feedbackBlockPattern.matcher(markdown);
        if (!matcher.find()) {
            return markdown;
        }

        String replacement = matcher.group(1)
                + System.lineSeparator()
                + System.lineSeparator();
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private String replaceRubricPatchBlockWithRawTable(String text) {
        String rubric = GradingMarkdownSections.extractBlockContentsOrNull(
                text,
                RUBRIC_TABLE_BEGIN,
                RUBRIC_TABLE_END
        );
        if (rubric == null || rubric.isBlank()) {
            return text;
        }

        int beginIndex = text.indexOf(RUBRIC_TABLE_BEGIN);
        if (beginIndex < 0) {
            return text;
        }
        int endIndex = text.indexOf(RUBRIC_TABLE_END, beginIndex + RUBRIC_TABLE_BEGIN.length());
        if (endIndex < 0) {
            return text;
        }
        int afterEnd = endIndex + RUBRIC_TABLE_END.length();
        return text.substring(0, beginIndex)
                + rubric.trim()
                + text.substring(afterEnd);
    }

    private String removeTotalRowFromRubricTable(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] lines = text.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith(">>") && trimmed.contains("| TOTAL |")) {
                continue;
            }
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString().replaceFirst("(?s)\\R\\z", "");
    }

    private String ensureRubricTableBeforeFeedback(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }

        int tableStart = markdown.indexOf(">> | Earned | Possible | Criteria");
        int feedbackStart = markdown.indexOf(FEEDBACK_HEADER);
        if (tableStart < 0 || feedbackStart < 0 || tableStart < feedbackStart) {
            return markdown;
        }

        int lineStart = markdown.lastIndexOf('\n', tableStart);
        lineStart = lineStart < 0 ? 0 : lineStart + 1;

        int cursor = lineStart;
        while (cursor < markdown.length()) {
            int nextLineBreak = markdown.indexOf('\n', cursor);
            int lineEnd = nextLineBreak < 0 ? markdown.length() : nextLineBreak;
            String line = markdown.substring(cursor, lineEnd).trim();
            if (!line.startsWith(">> |")) {
                break;
            }
            cursor = nextLineBreak < 0 ? markdown.length() : nextLineBreak + 1;
        }
        int tableEnd = cursor;

        String tableBlock = markdown.substring(lineStart, tableEnd).trim();
        if (tableBlock.isBlank()) {
            return markdown;
        }

        String withoutTable = markdown.substring(0, lineStart) + markdown.substring(tableEnd);

        int headingEnd = withoutTable.indexOf('\n');
        if (headingEnd < 0) {
            return markdown;
        }
        int insertPos = headingEnd + 1;
        while (insertPos < withoutTable.length()
                && (withoutTable.charAt(insertPos) == '\n'
                || withoutTable.charAt(insertPos) == '\r')) {
            insertPos++;
        }

        return withoutTable.substring(0, insertPos)
                + System.lineSeparator()
                + tableBlock
                + System.lineSeparator()
                + System.lineSeparator()
                + withoutTable.substring(insertPos);
    }

    private String effectiveReportFilePrefix() {
        if (reportFilePrefix != null && !reportFilePrefix.isBlank()) {
            return reportFilePrefix;
        }
        return assignmentId == null ? "" : assignmentId;
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
                effectiveReportFilePrefix(),
                rootPath,
                appDataDir()
        );
    }

    private Path resolveMappingFile() {
        return gradingMappingsService.resolveMappingFile(
                mappingsPath,
                effectiveReportFilePrefix(),
                appDataDir()
        );
    }

    @FunctionalInterface
    interface SaveDraftWorker {
        SaveDraftResult run();
    }

    @FunctionalInterface
    interface PushAllWorker {
        PushResult run();
    }
}

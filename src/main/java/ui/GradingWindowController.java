/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import model.RubricItemDef;
import model.RubricItemRef;
import model.RubricTableBuilder;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import model.Comments.CommentDef;
import model.Comments.CommentsLibrary;
import model.Comments.CommentsStore;
import model.Comments.ParsedComment;
import service.GradingDraftService;
import service.ReportHtmlWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private static final String COMMENTS_SUMMARY_BEGIN = "<!-- COMMENTS_SUMMARY_BEGIN -->";
    private static final String COMMENTS_SUMMARY_END = "<!-- COMMENTS_SUMMARY_END -->";
    private static final String RUBRIC_TABLE_BEGIN = "<!-- RUBRIC_TABLE_BEGIN -->";
    private static final String RUBRIC_TABLE_END = "<!-- RUBRIC_TABLE_END -->";
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

    private AssignmentsFile assignmentsFile;
    private Assignment assignment;
    private Path rootPath;      // selectedRootPath from main window
    private Path mappingsPath;

    private String assignmentId;

    private final ObservableList<String> studentPackages =
            FXCollections.observableArrayList();

    private final Map<String, StudentDraft> drafts = new HashMap<>();

    private String currentStudent = null;
    private boolean suppressCaretEvents = false;
    private boolean suppressHighlighting = false;
    private boolean suppressTextListener = false;
    private boolean isLoadingStudent = false;
    private boolean applyingHighlight = false;

    public void init(AssignmentsFile assignmentsFile, Assignment assignment, Path rootPath,
                     Path mappingsPath) {
        System.out.println("GradingWindowController INIT running");
        this.assignmentsFile = assignmentsFile;
        this.assignment = assignment;
        this.rootPath = rootPath;
        this.mappingsPath = mappingsPath;
        // rootPath/packages
        this.assignmentId = assignment.getCourseCode() + assignment.getAssignmentCode();
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
                StudentDraft d = drafts.get(currentStudent);
                if (d != null) {
                    d.setCaretPosition(newV);
                }
            }
        });
        reportEditor.textProperty().addListener((_, _, newText) -> {
            if (!suppressTextListener && currentStudent != null) {
                StudentDraft d = drafts.get(currentStudent);
                if (d != null) {
                    d.setMarkdown(newText);
                }
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
        Path mappingFile = loadOrReconstructMappings();
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Map<String, String>> mapping =
                    mapper.readValue(
                            Files.readAllBytes(Objects.requireNonNull(mappingFile)),
                            new TypeReference<>() {
                                // should remain blank
                            }
                    );
            System.out.println("Mapping size = " + mapping.size());
            List<String> keys = new ArrayList<>(mapping.keySet());
            keys.sort(String::compareToIgnoreCase);
            studentPackages.setAll(keys);
            status("Student package count = " + studentPackages.size());
            status("Loaded " + keys.size() + " submissions.");
        } catch (IOException e) {
            e.printStackTrace();
        }
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
        StudentDraft d = drafts.computeIfAbsent(studentPackage, StudentDraft::new);

        int caret = reportEditor.getCaretPosition();
        d.setCaretPosition(caret);
        String text = reportEditor.getText() == null ? "" : reportEditor.getText();
        d.setMarkdown(text);
        d.setLoadedFromDisk(true);
    }

    private void restoreCaretAfterUpdate(String studentPackage, int caretToRestore) {
        Platform.runLater(() -> {
            int caret = Math.max(0, Math.min(caretToRestore, reportEditor.getLength()));

            suppressCaretEvents = true;
            reportEditor.moveTo(caret);

            // Center the caret in the viewport
            centerCaretInViewport();

            suppressCaretEvents = false;

            StudentDraft d = drafts.get(studentPackage);
            if (d != null) {
                d.setCaretPosition(caret);
            }
        });
    }

    private void loadDraftIntoEditor(String studentPackage) {
        currentStudent = studentPackage;
        StudentDraft d = drafts.computeIfAbsent(studentPackage, StudentDraft::new);
        boolean needsReload = !d.isLoadedFromDisk()
                || d.getMarkdown() == null
                || d.getMarkdown().trim().isEmpty();
        if (needsReload) {
            String md = loadInitialMarkdownForStudent(studentPackage);
            md = normalizeRubricAndSummaryBlocks(md, studentPackage);
            d.setMarkdown(md);
            d.setLoadedFromDisk(true);
            if (d.getCaretPosition() < 0) {
                d.setCaretPosition(0);
            }
        }
        int desiredCaret = d.getCaretPosition();
        setEditorTextPreservingCaret(currentStudent, d.getMarkdown(), desiredCaret, false);

        // rebuild modifies text; it must also restore caret again AFTER it finishes
        rebuildRubricAndSummaryInEditor(desiredCaret, false);
        status("Editing: " + studentPackage);
    }

    private String normalizeRubricAndSummaryBlocks(String md, String studentPackage) {
        String text = md == null ? "" : md;
        String existingRubric =
                extractBlockContents(text, RUBRIC_TABLE_BEGIN, RUBRIC_TABLE_END);

        String rawRubric = null;
        if (existingRubric == null || existingRubric.isBlank()) {
            rawRubric = extractRawRubricTable(text);
        }
        if (existingRubric == null || existingRubric.isBlank()) {
            existingRubric = extractFirstRawRubricTable(text);
        }
        String existingSummary =
                extractBlockContents(text, COMMENTS_SUMMARY_BEGIN, COMMENTS_SUMMARY_END);

        // If the report came from the repo (gradedown export), it often has a blockquote rubric table.
        // Capture it BEFORE removing it, so we can preserve checkstyle/unit-test deductions.
        String rawRubricTable = extractRawRubricTableBlockquote(text);

        text = cleanBrokenTitleLine(text);

        text = removeAllBlocks(text, RUBRIC_TABLE_BEGIN, RUBRIC_TABLE_END);
        text = removeAllBlocks(text, COMMENTS_SUMMARY_BEGIN, COMMENTS_SUMMARY_END);

        // Remove heading and the old raw rubric table so we don't duplicate anything.
        text = removeStandaloneRubricHeadings(text);
        text = removeRawRubricTables(text);

        String rubricInside;
        if (rawRubricTable != null && !rawRubricTable.isBlank()) {
            rubricInside = rawRubricTable.trim();
        } else if (existingRubric != null && !existingRubric.isBlank()) {
            rubricInside = existingRubric.trim();
        } else {
            rubricInside = buildRubricGradeTableMarkdown(new ArrayList<>());
        }

        String summaryInside;
        if (existingSummary != null && !existingSummary.isBlank()) {
            summaryInside = existingSummary.trim();
        } else {
            summaryInside = buildCommentSummaryMarkdown(new ArrayList<>());
        }

        String rubricBlock =
                RUBRIC_TABLE_BEGIN + System.lineSeparator()
                        + rubricInside + System.lineSeparator()
                        + RUBRIC_TABLE_END;

        String summaryBlock =
                COMMENTS_SUMMARY_BEGIN + System.lineSeparator()
                        + summaryInside + System.lineSeparator()
                        + COMMENTS_SUMMARY_END;

        // Insert right after the first heading paragraph (same logic as before, but no extra "## Rubric")
        int insertPos = 0;
        String[] lines = text.split("\\R", -1);
        int running = 0;
        boolean foundHeading = false;
        boolean foundInsert = false;

        for (int i = 0; i < lines.length && !foundInsert; i++) {
            String line = lines[i];
            String t = line == null ? "" : line.trim();
            running += (line == null ? 0 : line.length()) + 1;

            if (!foundHeading && t.startsWith("#")) {
                foundHeading = true;
            } else if (foundHeading && t.isEmpty()) {
                insertPos = running;
                foundInsert = true;
            }
        }

        if (insertPos <= 0 || insertPos > text.length()) {
            insertPos = 0;
        }

        while (insertPos < text.length() && (text.charAt(insertPos) == '\n' || text.charAt(insertPos) == '\r')) {
            insertPos++;
        }

        StringBuilder out = new StringBuilder();
        out.append(text, 0, insertPos);

        // Make spacing predictable: exactly one blank line before blocks (unless at file start)
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append(System.lineSeparator());
        }
        out.append(System.lineSeparator());

        out.append(rubricBlock)
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(summaryBlock)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        out.append(text.substring(insertPos));

        // Trim leading blank lines at top to avoid “white space at the top”
        String normalized = out.toString();
        normalized = normalized.replaceFirst("^(\\s*\\R)+", "");

        return normalized;
    }

    private String extractRawRubricTable(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        final Pattern header = Pattern.compile(
                "(?m)^>>\\s*\\|\\s*Earned\\s*\\|\\s*Possible\\s*\\|\\s*Criteria.*$"
        );

        Matcher m = header.matcher(text);
        if (!m.find()) {
            return null;
        }

        final int start = m.start();

        // Collect until a blank line after the table begins
        String after = text.substring(start);
        String[] lines = after.split("\\R", -1);

        StringBuilder sb = new StringBuilder();

        final int minLinesToCountAsTable = 3;
        int tableLines = 0;
        boolean done = false;

        for (int i = 0; i < lines.length && !done; i++) {
            String line = lines[i];

            if (line.trim().isEmpty()) {
                if (tableLines >= minLinesToCountAsTable) {
                    done = true;
                }
            } else {
                sb.append(line).append(System.lineSeparator());
                tableLines++;
            }
        }

        String out = sb.toString().trim();
        if (out.isEmpty()) {
            return null;
        }
        return out;
    }

    private String extractRawRubricTableBlockquote(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String[] lines = text.split("\\R", -1);

        boolean inTable = false;
        StringBuilder sb = new StringBuilder();

        for (String line : lines) {
            String t = line == null ? "" : line.trim();

            boolean isHeader =
                    t.startsWith(">>")
                            && t.contains("|")
                            && t.toLowerCase().contains("earned")
                            && t.toLowerCase().contains("possible")
                            && t.toLowerCase().contains("criteria");

            if (!inTable) {
                if (isHeader) {
                    inTable = true;
                    sb.append(line).append(System.lineSeparator());
                }
            } else {
                boolean isTableLine = t.startsWith(">>") && t.contains("|");
                boolean isBlank = t.isEmpty();

                if (isBlank) {
                    // stop at the first blank line after the table
                    return sb.toString().trim();
                }

                if (isTableLine) {
                    sb.append(line).append(System.lineSeparator());
                } else {
                    // stop when we hit non-table content
                    return sb.toString().trim();
                }
            }
        }

        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private String removeAllBlocks(String text, String begin, String end) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String out = text;

        while (true) {
            int b = out.indexOf(begin);
            if (b < 0) {
                break;
            }

            int e = out.indexOf(end, b + begin.length());
            if (e < 0) {
                // broken block: remove the begin marker and continue
                out = out.substring(0, b) + out.substring(b + begin.length());
                continue;
            }

            int afterEnd = e + end.length();
            out = out.substring(0, b) + out.substring(afterEnd);
        }

        return out;
    }

    private String removeStandaloneRubricHeadings(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String[] lines = text.split("\\R", -1);
        List<String> kept = new ArrayList<>();

        for (String line : lines) {
            String t = line.trim();
            if (t.equals("## Rubric")) {
                continue;
            }
            kept.add(line);
        }

        return String.join(System.lineSeparator(), kept);
    }

    private String cleanBrokenTitleLine(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length == 0) {
            return text;
        }
        String first = lines[0];
        // If the first line contains these headings glued on, strip them off
        first = first.replace("## Rubric", "");
        first = first.replace("## Source Code", "");
        // if "##" is glued on without a space, remove it anyway
        first = first.replace("##", "");
        // normalize spaces
        first = first.replaceAll("\\s{2,}", " ").trim();
        // Also fix accidental no-space joins
        first = first.replaceAll("\\s{2,}", " ").trim();
        lines[0] = first;
        return String.join(System.lineSeparator(), lines);
    }

    private String removeRawRubricTables(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // Allow leading whitespace before >>
        final String prefix = "(?ms)^\\s*>>\\s*\\|";
        // Full rubric table (header + rows)
        text = text.replaceAll(
                prefix + "\\s*Earned\\s*\\|\\s*Possible\\s*\\|\\s*Criteria.*?\\R" +
                        "(^\\s*>>\\s*\\|[- ]+\\|[- ]+\\|[- ]+\\|\\R)?" +
                        "(^\\s*>>\\s*\\|\\s*\\d+\\s*\\|\\s*\\d+\\s*\\|.*\\|\\R)+\\R?",
                ""
        );
        // Row-only rubric table (2+ numeric rows)
        text = text.replaceAll(
                "(?ms)(^\\s*>>\\s*\\|\\s*\\d+\\s*\\|\\s*\\d+\\s*\\|.*\\|\\R){2,}\\R?",
                ""
        );
        return text;
    }

    private String extractFirstRawRubricTable(String text) {
        String out = null;

        if (text != null && !text.isBlank()) {

            final String pattern =
                    "(?ms)^\\s*>>\\s*\\|\\s*Earned\\s*\\|\\s*Possible\\s*\\|\\s*Criteria\\s*\\|.*\\R" +
                            "^\\s*>>\\s*\\|\\s*[- ]+\\s*\\|\\s*[- ]+\\s*\\|\\s*[- ]+\\s*\\|.*\\R" +
                            "(^\\s*>>\\s*\\|.*\\|\\s*\\R)+";

            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(text);

            if (m.find()) {
                out = m.group().trim();
            }
        }

        return out;
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
        return "# " + assignment.getAssignmentName() + System.lineSeparator() +
                System.lineSeparator() +

                // rubric table placeholder
                RUBRIC_TABLE_BEGIN + System.lineSeparator() +
                buildRubricGradeTableMarkdown(new ArrayList<>()) + System.lineSeparator() +
                RUBRIC_TABLE_END + System.lineSeparator() +
                System.lineSeparator() +

                // comments summary placeholder
                COMMENTS_SUMMARY_BEGIN + System.lineSeparator() +
                buildCommentSummaryMarkdown(new ArrayList<>()) + System.lineSeparator() +
                COMMENTS_SUMMARY_END + System.lineSeparator() +
                System.lineSeparator() +
                "> # Feedback" + System.lineSeparator() +
                "> * " + System.lineSeparator() +
                System.lineSeparator() +
                "_No report found for " + studentPackage + "._" +
                System.lineSeparator();
    }

    private String ensurePatchCSectionsExist(String md, String studentPackage) {
        String text = md == null ? "" : md;
        boolean hasRubric = text.contains(RUBRIC_TABLE_BEGIN) && text.contains(RUBRIC_TABLE_END);
        boolean hasSummary = text.contains(COMMENTS_SUMMARY_BEGIN)
                && text.contains(COMMENTS_SUMMARY_END);
        if (hasRubric && hasSummary) {
            return text;
        }
        // Build a skeleton and then append the rest below it.
        String skeleton = buildFreshReportSkeleton(studentPackage);
        // If the extracted markdown already starts with "#", don't duplicate title.
        // Just insert missing blocks after the first heading.
        if (text.trim().startsWith("#")) {
            // keep extracted report but inject blocks after the first heading section
            return injectMissingBlocksIntoExisting(text);
        }
        // fallback: skeleton + extracted report
        return skeleton + System.lineSeparator() + text;
    }

    private String injectMissingBlocksIntoExisting(String md) {
        String text = md == null ? "" : md;
        boolean hasRubric = text.contains(RUBRIC_TABLE_BEGIN) && text.contains(RUBRIC_TABLE_END);
        boolean hasSummary = text.contains(COMMENTS_SUMMARY_BEGIN)
                && text.contains(COMMENTS_SUMMARY_END);
        if (hasRubric && hasSummary) {
            return text;
        }
        String[] lines = text.split("\\R", -1);
        // find the first blank line after the first heading
        int insertAtLine = -1;
        boolean sawHeading = false;
        boolean done = false;
        for (int i = 0; i < lines.length && !done; i++) {
            String t = lines[i].trim();
            if (!sawHeading && t.startsWith("#")) {
                sawHeading = true;
            } else if (sawHeading && t.isEmpty()) {
                insertAtLine = i + 1;
                done = true;
            }
        }

        if (insertAtLine < 0) {
            insertAtLine = 1;
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]).append(System.lineSeparator());

            if (i == insertAtLine - 1) {
                if (!hasRubric) {
                    sb.append(System.lineSeparator());
                    sb.append(RUBRIC_TABLE_BEGIN).append(System.lineSeparator());
                    // Leave empty on purpose: normalizeRubricAndSummaryBlocks will preserve
                    // the rubric already present in the report (including checkstyle/unit-test deductions).
                    sb.append(System.lineSeparator());
                    sb.append(RUBRIC_TABLE_END).append(System.lineSeparator());
                    sb.append(System.lineSeparator());
                }
                if (!hasSummary) {
                    sb.append(System.lineSeparator());
                    sb.append(COMMENTS_SUMMARY_BEGIN).append(System.lineSeparator());
                    sb.append(buildCommentSummaryMarkdown(new ArrayList<>()))
                            .append(System.lineSeparator());
                    sb.append(COMMENTS_SUMMARY_END).append(System.lineSeparator());
                    sb.append(System.lineSeparator());
                }
            }
        }

        return sb.toString();
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
        String newSummary = buildCommentSummaryMarkdown(comments);
        text = text.replace("\r\n", "\n");
        String updated = replaceBlock(text, COMMENTS_SUMMARY_BEGIN, COMMENTS_SUMMARY_END, newSummary);
        String updatedRubric = buildUpdatedRubricBlockFromExisting(updated, comments);
        updated = replaceBlock(updated, RUBRIC_TABLE_BEGIN, RUBRIC_TABLE_END, updatedRubric);
        if (!updated.equals(text)) {
            setEditorTextPreservingCaret(currentStudent, updated, caretToRestore, centerCaret);
        } else {
            // Even if no text changed, still restore caret reliably.
            Platform.runLater(() -> restoreCaretAfterUpdate(currentStudent, caretToRestore));
        }
    }

    private String buildUpdatedRubricBlockFromExisting(String fullText, List<ParsedComment> comments) {

        String safe = fullText == null ? "" : fullText;

        String existingBlock = extractBlockContents(safe, RUBRIC_TABLE_BEGIN, RUBRIC_TABLE_END);
        if (existingBlock == null || existingBlock.isBlank()) {
            return buildRubricGradeTableMarkdown(comments);
        }

        Map<String, Integer> manualLostByRubricId = computeManualDeductionsByRubricId(comments);

        Map<String, String> rubricIdToCriteriaName = buildRubricIdToCriteriaNameMap();

        List<RubricRow> rows = parseRubricRows(existingBlock);

        if (rows.isEmpty()) {
            return existingBlock;
        }

        applyManualDeductions(rows, manualLostByRubricId, rubricIdToCriteriaName);

        recomputeTotalRow(rows);

        return renderRubricRows(rows, existingBlock);
    }

    private String extractBlockContents(String text, String begin, String end) {
        if (text == null) {
            return null;
        }

        int b = text.indexOf(begin);
        int e = text.indexOf(end);

        if (b < 0 || e < 0 || e < b) {
            return null;
        }

        int start = b + begin.length();

        String inside = text.substring(start, e);

        return inside.trim();
    }

    private Map<String, Integer> computeManualDeductionsByRubricId(List<ParsedComment> comments) {
        Map<String, Integer> manual = new LinkedHashMap<>();

        if (comments != null) {
            for (ParsedComment c : comments) {
                if (c == null) {
                    continue;
                }
                String id = c.rubricItemId();
                if (id == null || id.isBlank()) {
                    continue;
                }

                int lost = Math.max(0, c.pointsLost());
                manual.put(id, manual.getOrDefault(id, 0) + lost);
            }
        }

        return manual;
    }

    private Map<String, String> buildRubricIdToCriteriaNameMap() {
        Map<String, String> map = new LinkedHashMap<>();

        if (assignment != null
                && assignment.getRubric() != null
                && assignment.getRubric().getItems() != null
                && assignmentsFile != null
                && assignmentsFile.getRubricItemLibrary() != null) {

            for (RubricItemRef ref : assignment.getRubric().getItems()) {
                if (ref == null) {
                    continue;
                }

                String id = ref.getRubricItemId();
                if (id == null || id.isBlank()) {
                    continue;
                }

                RubricItemDef def = assignmentsFile.getRubricItemLibrary().get(id);

                String criteriaName = id;
                if (def != null && def.getName() != null && !def.getName().isBlank()) {
                    criteriaName = def.getName().trim();
                }

                map.put(id, criteriaName);
            }
        }

        return map;
    }

    private List<RubricRow> parseRubricRows(String rubricBlock) {
        List<RubricRow> rows = new ArrayList<>();

        if (rubricBlock == null || rubricBlock.isBlank()) {
            return rows;
        }

        String[] lines = rubricBlock.split("\\R", -1);

        for (String line : lines) {
            if (line == null) {
                continue;
            }

            String trimmed = line.trim();

            boolean isRow = trimmed.startsWith(">> |");
            boolean isSeparator = trimmed.contains("---");
            boolean isHeader = trimmed.toLowerCase().contains("| earned |")
                    && trimmed.toLowerCase().contains("| possible |");

            if (isRow && !isSeparator && !isHeader) {
                RubricRow row = tryParseRubricRowLine(trimmed);
                if (row != null) {
                    rows.add(row);
                }
            }
        }

        return rows;
    }

    private RubricRow tryParseRubricRowLine(String trimmed) {
        final String prefix = ">>";
        String s = trimmed;

        if (s.startsWith(prefix)) {
            s = s.substring(prefix.length()).trim();
        }

        String[] parts = s.split("\\|", -1);

        final int earnedIndex = 1;
        final int possibleIndex = 2;
        final int criteriaIndex = 3;
        final int minParts = 5;

        RubricRow row = null;

        if (parts.length >= minParts) {
            String earnedStr = parts[earnedIndex].trim();
            String possibleStr = parts[possibleIndex].trim();
            String criteriaStr = parts[criteriaIndex].trim();

            Double earned = tryParseDouble(earnedStr);
            Integer possible = tryParseInt(possibleStr);

            if (earned != null && possible != null && !criteriaStr.isBlank()) {
                row = new RubricRow(earned, possible, criteriaStr, trimmed);
            }
        }

        return row;
    }

    private Integer tryParseInt(String s) {
        Integer out = null;

        if (s != null) {
            try {
                out = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                out = null;
            }
        }

        return out;
    }

    private Double tryParseDouble(String s) {
        Double out = null;

        if (s != null) {
            try {
                out = Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                out = null;
            }
        }

        return out;
    }

    private void applyManualDeductions(List<RubricRow> rows,
                                       Map<String, Integer> manualLostByRubricId,
                                       Map<String, String> rubricIdToCriteriaName) {

        if (rows == null || rows.isEmpty() || manualLostByRubricId == null || manualLostByRubricId.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Integer> entry : manualLostByRubricId.entrySet()) {
            String rubricId = entry.getKey();
            int lost = entry.getValue() == null ? 0 : entry.getValue();

            String criteriaName = rubricIdToCriteriaName.get(rubricId);
            if (criteriaName == null || criteriaName.isBlank()) {
                criteriaName = rubricId;
            }

            RubricRow matching = findRowByCriteria(rows, criteriaName);

            if (matching != null) {
                double newEarned = matching.earned - Math.max(0, lost);
                newEarned = Math.max(0, Math.min(newEarned, (double) matching.possible));
                matching.earned = newEarned;
            }
        }
    }

    private RubricRow findRowByCriteria(List<RubricRow> rows, String criteriaName) {
        RubricRow found = null;

        if (rows != null && criteriaName != null) {
            String target = normalizeCriteria(criteriaName);

            for (RubricRow r : rows) {
                if (r != null) {
                    String actual = normalizeCriteria(r.criteria);

                    boolean matches = actual.equalsIgnoreCase(target);
                    if (matches) {
                        found = r;
                    }
                }
            }
        }

        return found;
    }

    private String normalizeCriteria(String s) {
        String out = s == null ? "" : s.trim();
        out = out.replaceAll("\\s+", " ");
        return out;
    }

    private void recomputeTotalRow(List<RubricRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        final String totalLabel = "TOTAL";
        RubricRow totalRow = null;
        double earnedSum = 0.0;
        int possibleSum = 0;
        for (RubricRow r : rows) {
            if (r == null) {
                continue;
            }
            boolean isTotal = r.criteria != null && r.criteria.trim().equalsIgnoreCase(totalLabel);
            if (isTotal) {
                totalRow = r;
            } else {
                earnedSum += Math.max(0, r.earned);
                possibleSum += Math.max(0, r.possible);
            }
        }

        if (totalRow != null) {
            totalRow.earned = earnedSum;
            totalRow.possible = possibleSum;
        }
    }

    private String renderRubricRows(List<RubricRow> parsedRows, String existingBlock) {

        if (existingBlock == null) {
            existingBlock = "";
        }

        String[] lines = existingBlock.split("\\R", -1);

        Map<String, RubricRow> criteriaToRow = new LinkedHashMap<>();
        for (RubricRow r : parsedRows) {
            if (r != null) {
                criteriaToRow.put(normalizeCriteria(r.criteria).toLowerCase(), r);
            }
        }

        StringBuilder out = new StringBuilder();

        for (String line : lines) {
            String original = line == null ? "" : line;
            String trimmed = original.trim();

            boolean isRow = trimmed.startsWith(">> |");
            boolean isSeparator = trimmed.contains("---");
            boolean isHeader = trimmed.toLowerCase().contains("| earned |")
                    && trimmed.toLowerCase().contains("| possible |");

            String toWrite = original;

            if (isRow && !isSeparator && !isHeader) {
                RubricRow maybe = tryParseRubricRowLine(trimmed);
                if (maybe != null) {
                    String key = normalizeCriteria(maybe.criteria).toLowerCase();
                    RubricRow updated = criteriaToRow.get(key);
                    if (updated != null) {
                        toWrite = formatRowLikeOriginal(updated, original);
                    }
                }
            }

            out.append(toWrite).append(System.lineSeparator());
        }

        return out.toString().trim();
    }

    private String formatRubricPoints(double pts) {
        final double delta = 0.00001;
        if (Math.abs(pts - Math.round(pts)) < delta) {
            return Integer.toString((int) Math.round(pts));
        }
        return String.format(java.util.Locale.US, "%.2f", pts);
    }

    private String formatRowLikeOriginal(RubricRow updated, String originalLine) {
        final String prefix = ">> |";
        final String pipe = "|";
        String criteria = updated.criteria == null ? "" : updated.criteria;
        String earnedStr = formatRubricPoints(updated.earned);
        String possibleStr = Integer.toString(updated.possible);
        final int earnedPad = 6;
        final int possiblePad = 8;
        return prefix + " " + padLeft(earnedStr, earnedPad) + " " + pipe + " "
                + padLeft(possibleStr, possiblePad) + " " + pipe + " "
                + criteria + " " + pipe;
    }

    private String padLeft(String s, int width) {
        String text = s == null ? "" : s;

        StringBuilder sb = new StringBuilder();

        int spaces = Math.max(0, width - text.length());
        for (int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
        sb.append(text);

        return sb.toString();
    }

    private static final class RubricRow {
        private double earned;
        private int possible;
        private final String criteria;
        private final String originalLine;

        private RubricRow(double earned, int possible, String criteria, String originalLine) {
            this.earned = earned;
            this.possible = possible;
            this.criteria = criteria;
            this.originalLine = originalLine;
        }
    }

    private String replaceBlock(String text,
                                String beginMarker,
                                String endMarker,
                                String newContent) {

        String src = text == null ? "" : text;

        int b = src.indexOf(beginMarker);
        int e = src.indexOf(endMarker);

        if (b < 0 || e < 0 || e < b) {
            return src;
        }

        int start = b + beginMarker.length();
        String before = src.substring(0, start);
        String current = src.substring(start, e);
        String after = src.substring(e);

        String normalizedCurrent = current == null ? "" : current.trim();
        String normalizedNew = newContent == null ? "" : newContent.trim();

        if (normalizedCurrent.equals(normalizedNew)) {
            return src;
        }

        return before
                + System.lineSeparator()
                + normalizedNew
                + System.lineSeparator()
                + after;
    }

    private String buildCommentSummaryMarkdown(List<ParsedComment> comments) {
        StringBuilder sb = new StringBuilder();

        sb.append(">> # Comments").append(System.lineSeparator());
        sb.append(">>").append(System.lineSeparator());

        if (comments == null || comments.isEmpty()) {
            sb.append(">> * _No comments._").append(System.lineSeparator());
            return sb.toString();
        }

        for (ParsedComment c : comments) {
            sb.append(">> * [")
                    .append(escapeMdText(c.title()))
                    .append("](")
                    .append("#")
                    .append(c.anchorId())
                    .append(") ");

            sb.append("(-")
                    .append(c.pointsLost())
                    .append(" ")
                    .append(c.rubricItemId())
                    .append(")");

            sb.append(System.lineSeparator());
        }

        return sb.toString();
    }

    private String escapeMdText(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    private String buildRubricGradeTableMarkdown(List<ParsedComment> comments) {
        // Compute manual deductions by rubric item id
        Map<String, Integer> manualDeductions = new LinkedHashMap<>();
        if (comments != null) {
            for (ParsedComment c : comments) {
                if (c == null) {
                    continue;
                }
                String id = c.rubricItemId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                int lost = Math.max(0, c.pointsLost());
                manualDeductions.put(
                        id,
                        manualDeductions.getOrDefault(id, 0) + lost
                );
            }
        }

        return RubricTableBuilder.buildRubricMarkdown(
                assignment,
                assignmentsFile,
                null,   // checkstyle violations (not used here)
                null,   // unit test failures (not used here)
                null,   // total unit tests (not used here)
                manualDeductions
        );
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
        boolean success = true;
        int caret = reportEditor.getCaretPosition();

        rebuildRubricAndSummaryInEditor(caret, false);

        if (currentStudent != null) {
            StudentDraft d = drafts.computeIfAbsent(currentStudent, StudentDraft::new);
            d.setMarkdown(reportEditor.getText() == null ? "" : reportEditor.getText());
            d.setCaretPosition(caret);
            d.setLoadedFromDisk(true);
        }

        int wrote = 0;

        for (String pkg : studentPackages) {
            StudentDraft d = drafts.computeIfAbsent(pkg, StudentDraft::new);

            if (!d.isLoadedFromDisk()
                    || d.getMarkdown() == null
                    || d.getMarkdown().trim().isEmpty()) {

                d.setMarkdown(loadInitialMarkdownForStudent(pkg));
                d.setLoadedFromDisk(true);

                if (!pkg.equals(currentStudent)) {
                    d.setCaretPosition(0);
                }
            }

            Path repoDir = findRepoDirForStudentPackage(pkg);
            if (repoDir == null) {
                status("Could not find repo for " + pkg);
                success = false;
            } else {
                try {
                    String markdown = d.getMarkdown() == null ? "" : d.getMarkdown();

                    gradingDraftService.saveReportMarkdown(
                            assignmentId,
                            pkg,
                            repoDir,
                            markdown
                    );

                    wrote++;
                } catch (IOException e) {
                    status("Failed writing report for " + pkg + ": " + e.getMessage());
                    success = false;
                }
            }
        }

        if (success) {
            status("Saved " + wrote + " HTML report(s) to student repositories.");
        }
    }

    @FXML
    private void onExportGradedHtml() {
        onSaveDrafts();
    }

    private Path findRepoDirForStudentPackage(String studentPackage) {
        Path mappingFile = loadOrReconstructMappings();
        if (mappingFile == null || !Files.isRegularFile(mappingFile)) {
            return null;
        }
        try {
            // We intentionally parse only what we need.
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Map<String, String>> mapping =
                    mapper.readValue(
                            Files.readAllBytes(mappingFile),
                            new TypeReference<>() {
                            }
                    );
            Map<String, String> entry = mapping.get(studentPackage);
            if (entry == null) {
                return null;
            }
            String repoPath = entry.get("repoPath");
            if (repoPath == null || repoPath.isBlank()) {
                return null;
            }

            Path repoDir = Path.of(repoPath);

            if (!Files.isDirectory(repoDir)) {
                return null;
            }

            return repoDir;
        } catch (IOException e) {
            return null;
        }
    }

    private String wrapMarkdownAsHtml(String title, String markdown) {
        return reportHtmlWrapper.wrapMarkdownAsHtml(title, markdown);
    }

    @FXML
    private void onSaveAndExport() {
        if (saveAndExportButton != null) {
            saveAndExportButton.setDisable(true);
        }

        if (currentStudent != null) {
            saveCurrentEditorToDraft(currentStudent);
        }

        try {
            onSaveDrafts();
        } catch (Exception e) {
            status("Save failed: " + e.getMessage());
            if (saveAndExportButton != null) {
                saveAndExportButton.setDisable(false);
            }
            return;
        }

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "push-feedback");
            t.setDaemon(true);
            return t;
        });

        CompletableFuture.runAsync(() -> {
            PushResult r = pushAllRepos();
            Platform.runLater(() -> {
                status("Save/push complete: pushed " + r.pushed +
                        ", skipped " + r.skipped + ", failed " + r.failed + ".");
                if (saveAndExportButton != null) {
                    saveAndExportButton.setDisable(false);
                }
            });
        }, exec).whenComplete((_, _) -> exec.shutdown());
    }

    private PushResult pushAllRepos() {
        int pushed = 0;
        int skipped = 0;
        int failed = 0;

        for (String pkg : studentPackages) {
            Path repoDir = findRepoDirForStudentPackage(pkg);
            if (repoDir == null) {
                failed++;
                continue;
            }

            String baseName = assignmentId + pkg;
            Path outHtml = repoDir.resolve(baseName + ".html");

            if (!Files.exists(outHtml)) {
                skipped++;
                continue;
            }

            try {
                int add = runGit(repoDir, "add", outHtml.getFileName().toString());

                if (add != 0) {
                    failed++;
                } else {
                    int commit = runGit(repoDir, "commit", "-m", "Add feedback for " + assignmentId);

                    if (commit != 0) {
                        skipped++;
                    } else {
                        int push = runGit(repoDir, "push");

                        if (push != 0) {
                            failed++;
                        } else {
                            pushed++;
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                failed++;
            }
        }

        return new PushResult(pushed, skipped, failed);
    }

    private int runGit(Path repoDir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        Collections.addAll(cmd, args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir.toFile());
        pb.redirectErrorStream(true);

        Process p = pb.start();
        // Drain output so git won't block on full buffer
        try (var in = p.getInputStream()) {
            in.readAllBytes();
        }
        return p.waitFor();
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

        PushResult(int pushed, int skipped, int failed) {
            this.pushed = pushed;
            this.skipped = skipped;
            this.failed = failed;
        }
    }


    @FXML
    private void onCloseAndSaveAll() {
        if (currentStudent != null) {
            saveCurrentEditorToDraft(currentStudent);
        }

        onSaveDrafts();
        onExportGradedHtml();
        highlightExecutor.shutdownNow();
        Stage stage = (Stage) studentList.getScene().getWindow();
        stage.close();
    }


    private void status(String msg) {
        if (statusLabel != null) {
            statusLabel.setText(msg == null ? "" : msg);
        }
    }

    /**
     * In-memory per-student report state.
     */
    private static class StudentDraft {
        private final String studentPackage;
        private String markdown = "";
        private int caretPosition = 0;
        private boolean loadedFromDisk = false;

        public StudentDraft(String studentPackage) {
            this.studentPackage = studentPackage;
        }

        public String getStudentPackage() {
            return studentPackage;
        }

        public String getMarkdown() {
            return markdown;
        }

        public void setMarkdown(String markdown) {
            this.markdown = markdown == null ? "" : markdown;
        }

        public int getCaretPosition() {
            return caretPosition;
        }

        public void setCaretPosition(int caretPosition) {
            this.caretPosition = Math.max(0, caretPosition);
        }

        public boolean isLoadedFromDisk() {
            return loadedFromDisk;
        }

        public void setLoadedFromDisk(boolean loadedFromDisk) {
            this.loadedFromDisk = loadedFromDisk;
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
                StudentDraft d = drafts.get(studentPackage);
                if (d != null) {
                    d.setCaretPosition(caret);
                    d.setMarkdown(reportEditor.getText());
                    d.setLoadedFromDisk(true);
                }
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

        Path mappingFile = getAssignmentMappingsFile();
        Path mappingsDir = mappingFile != null ? mappingFile.getParent() : null;

        if (mappingFile == null || mappingsDir == null) {
            return null;
        }

        if (Files.isRegularFile(mappingFile)) {

            boolean valid = mappingsFileHasValidRepoPaths(mappingFile);

            if (valid) {
                return mappingFile;
            }

            status("Existing mappings invalid on this machine. Reconstructing...");
        }

        Map<String, Map<String, String>> reconstructed =
                reconstructMappingsFromRoot();

        if (reconstructed.isEmpty()) {
            status("No repositories found for reconstruction.");
            return null;
        }

        try {
            Files.createDirectories(mappingsDir);

            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(mappingFile.toFile(), reconstructed);

            status("Reconstructed mappings.json from root folder.");

            return mappingFile;

        } catch (IOException e) {
            status("Failed to write reconstructed mappings.json: " + e.getMessage());
            return null;
        }
    }

    private boolean mappingsFileHasValidRepoPaths(Path mappingFile) {

        if (mappingFile == null || !Files.isRegularFile(mappingFile)) {
            return false;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();

            Map<String, Map<String, String>> mapping =
                    mapper.readValue(
                            Files.readAllBytes(mappingFile),
                            new TypeReference<>() {
                            }
                    );

            if (mapping == null || mapping.isEmpty()) {
                return false;
            }

            for (Map<String, String> entry : mapping.values()) {

                if (entry == null) {
                    return false;
                }

                String repoPath = entry.get("repoPath");

                if (repoPath == null || repoPath.isBlank()) {
                    return false;
                }

                Path repoDir = Path.of(repoPath);

                if (!Files.isDirectory(repoDir)) {
                    return false;
                }
            }

            return true;

        } catch (IOException e) {
            return false;
        }
    }

    private Map<String, Map<String, String>> reconstructMappingsFromRoot() {
        status("Root path = " + rootPath);
        if (rootPath == null) {
            status("Root path is NULL");
        }
        Map<String, Map<String, String>> mapping = new LinkedHashMap<>();

        if (rootPath == null || !Files.isDirectory(rootPath)) {
            return mapping;
        }

        Path reposContainer = null;

        try (var stream = Files.list(rootPath)) {

            for (Path child : stream.toList()) {

                if (!Files.isDirectory(child)) {
                    continue;
                }

                if (child.getFileName().toString().equalsIgnoreCase("packages")) {
                    continue;
                }

                // Check if this directory actually contains repos
                boolean containsAssignmentReports = false;

                try (var sub = Files.list(child)) {
                    for (Path maybeRepo : sub.toList()) {

                        if (!Files.isDirectory(maybeRepo)) {
                            continue;
                        }

                        try (var files = Files.list(maybeRepo)) {
                            for (Path f : files.toList()) {

                                String name = f.getFileName().toString();

                                if (name.startsWith(assignmentId)
                                        && name.endsWith(".html")) {
                                    containsAssignmentReports = true;
                                }
                            }
                        }
                    }
                }

                if (containsAssignmentReports) {
                    reposContainer = child;
                }
            }

        } catch (IOException e) {
            status("Failed scanning root: " + e.getMessage());
            return mapping;
        }

        if (reposContainer == null || !Files.isDirectory(reposContainer)) {
            return mapping;
        }

        try (var repos = Files.list(reposContainer)) {

            for (Path repoDir : repos.toList()) {

                if (!Files.isDirectory(repoDir)) {
                    continue;
                }

                try (var files = Files.list(repoDir)) {

                    for (Path file : files.toList()) {

                        String fileName = file.getFileName().toString();

                        if (fileName.startsWith(assignmentId)
                                && fileName.endsWith(".html")) {

                            String studentPackage =
                                    fileName.substring(
                                            assignmentId.length(),
                                            fileName.length() - ".html".length()
                                    );

                            Map<String, String> entry = new LinkedHashMap<>();
                            entry.put("repoPath", repoDir.toAbsolutePath().toString());

                            mapping.put(studentPackage, entry);
                        }
                    }

                }
            }

        } catch (IOException e) {
            status("Failed scanning repos: " + e.getMessage());
        }
        status("Reconstructed mapping count: " + mapping.size());
        return mapping;
    }

    private Path getAssignmentMappingsFile() {

        if (assignmentId == null || assignmentId.isBlank()) {
            return null;
        }

        Path mappingsDir = appDataDir().resolve("mappings");

        final String fileNamePrefix = "mappings-";
        final String extension = ".json";

        String fileName = fileNamePrefix + assignmentId + extension;

        return mappingsDir.resolve(fileName);
    }
}
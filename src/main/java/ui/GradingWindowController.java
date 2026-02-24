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
import model.RubricItemRef;
import model.RubricTableBuilder;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import model.Comments.CommentDef;
import model.Comments.CommentsLibrary;
import model.Comments.CommentsStore;
import model.Comments.ParsedComment;
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
        this.assignmentsFile = assignmentsFile;
        this.assignment = assignment;
        this.rootPath = rootPath;
        this.mappingsPath = mappingsPath;
        // rootPath/packages
        this.assignmentId = assignment.getCourseCode() + assignment.getAssignmentCode();

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

        Path mappingFile = resolveMappingsPath(mappingsPath, rootPath);
        if (!Files.exists(mappingFile)) {
            status("mappings.json not found.");
        } else {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Map<String, String>> mapping =
                        mapper.readValue(
                                Files.readAllBytes(mappingFile),
                                new TypeReference<>() {
                                }
                        );

                List<String> keys = new ArrayList<>(mapping.keySet());
                keys.sort(String::compareTo);
                studentPackages.setAll(keys);

                status("Loaded " + keys.size() + " submissions.");
            } catch (IOException e) {
                status("Failed to load mapping.json: " + e.getMessage());
            }
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
            md = ensurePatchCSectionsExist(md, studentPackage);
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

        String existingSummary =
                extractBlockContents(text, COMMENTS_SUMMARY_BEGIN, COMMENTS_SUMMARY_END);

        text = cleanBrokenTitleLine(text);

        text = removeAllBlocks(text, RUBRIC_TABLE_BEGIN, RUBRIC_TABLE_END);
        text = removeAllBlocks(text, COMMENTS_SUMMARY_BEGIN, COMMENTS_SUMMARY_END);
        text = removeStandaloneRubricHeadings(text);
        text = removeRawRubricTables(text);

        String rubricInside;
        if (existingRubric != null && !existingRubric.isBlank()) {
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

        int insertPos = 0;
        String[] lines = text.split("\\R", -1);
        int running = 0;
        boolean foundHeading = false;
        boolean foundInsert = false;

        for (int i = 0; i < lines.length && !foundInsert; i++) {
            String line = lines[i];
            String t = line.trim();
            running += line.length() + 1;

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

        while (insertPos < text.length() && text.charAt(insertPos) == '\n') {
            insertPos++;
        }

        StringBuilder out = new StringBuilder();
        out.append(text, 0, insertPos);

        boolean hasRubricHeading =
                text.contains(System.lineSeparator() + "## Rubric")
                        || text.startsWith("## Rubric");

        if (!hasRubricHeading) {
            out.append(System.lineSeparator())
                    .append("## Rubric")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        } else {
            out.append(System.lineSeparator());
        }

        out.append(rubricBlock)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        out.append(summaryBlock)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        out.append(text.substring(insertPos));

        return out.toString();
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

        // Remove any blockquote rubric table that appears OUTSIDE the rubric markers.
        // This handles both:
        // 1) Full tables with header
        // 2) "row-only" tables (like the duplicate you're seeing)

        // Full rubric table (with header)
        text = text.replaceAll(
                "(?ms)^>> \\| Earned \\| Possible \\| Criteria.*?\\R" +
                        "(^>> \\|[- ]+\\|[- ]+\\|[- ]+\\|\\R)?" +
                        "(^>> \\|\\s*\\d+\\s*\\|\\s*\\d+\\s*\\|.*\\|\\R)+\\R?",
                ""
        );

        // Row-only rubric table (no header)
        text = text.replaceAll(
                "(?ms)(^>> \\|\\s*\\d+\\s*\\|\\s*\\d+\\s*\\|.*\\|\\R){2,}\\R?",
                ""
        );

        return text;
    }

    /**
     * Load order:
     * 1) repo report: {assignmentId}{pkg}.html (extract <xmp/>)
     * 2) grading/{assignmentId}{pkg}.md (drafts)
     * 3) fallback skeleton
     */
    private String loadInitialMarkdownForStudent(String studentPackage) {
        // ---- (1) generated html in the mapped repo ----
        try {
            Path repoDir = findRepoDirForStudentPackage(studentPackage);

            if (repoDir != null) {
                String fileName = assignmentId + studentPackage + ".html";
                Path htmlPath = repoDir.resolve(fileName);

                if (Files.exists(htmlPath) && Files.isRegularFile(htmlPath)) {
                    String html = Files.readString(htmlPath, StandardCharsets.UTF_8);
                    String md = extractXmpMarkdown(html);

                    if (md != null && !md.isBlank()) {
                        return ensurePatchCSectionsExist(md, studentPackage);
                    }
                }
            }
        } catch(IOException e) {
            status("Could not load student package " + studentPackage);
        }


        // ---- (2) markdown draft in repo ----
        Path repoDir = findRepoDirForStudentPackage(studentPackage);
        if (repoDir != null) {
            Path draftMd = repoDir.resolve(assignmentId + studentPackage + ".md");

            if (Files.exists(draftMd) && Files.isRegularFile(draftMd)) {
                try {
                    String draft = Files.readString(draftMd, StandardCharsets.UTF_8);
                    if (!draft.trim().isEmpty()) {
                        return draft;
                    }
                } catch (IOException e) {
                    return "_Failed to read draft markdown: " + e.getMessage() + "_";
                }
            }
        }

        // ---- (3) fallback skeleton ----
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
                    sb.append(buildRubricGradeTableMarkdown(new ArrayList<>()))
                            .append(System.lineSeparator());
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

    /*
     * Extracts the markdown between xmp tags
     */
    private String extractXmpMarkdown(String html) {
        if (html == null) {
            return null;
        }

        String lower = html.toLowerCase();

        int startTag = lower.indexOf("<xmp>");
        if (startTag < 0) {
            return null;
        }
        int start = startTag + "<xmp>".length();

        int end = lower.indexOf("</xmp>", start);
        if (end < 0) {
            return null;
        }

        return html.substring(start, end).trim();
    }

    @FXML private void onInsertComment() {
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

    @FXML private void onRemoveComment() {
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

                var def = assignmentsFile.getRubricItemLibrary().get(id);

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

            Integer earned = tryParseInt(earnedStr);
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
                int newEarned = matching.earned - Math.max(0, lost);
                newEarned = Math.max(0, Math.min(newEarned, matching.possible));
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

        int earnedSum = 0;
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

    private String formatRowLikeOriginal(RubricRow updated, String originalLine) {

        final String prefix = ">> |";
        final String pipe = "|";

        String original = originalLine == null ? "" : originalLine;

        // Keep the same prefix style. We’ll reformat values but preserve the “>> | … | … | … |” structure.
        String criteria = updated.criteria == null ? "" : updated.criteria;

        String earnedStr = String.valueOf(updated.earned);
        String possibleStr = String.valueOf(updated.possible);

        // Minimal spacing—doesn’t need perfect column alignment to be readable.
        return prefix + " " + padLeft(earnedStr, 6) + " " + pipe + " "
                + padLeft(possibleStr, 8) + " " + pipe + " "
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
        private int earned;
        private int possible;
        private final String criteria;
        private final String originalLine;

        private RubricRow(int earned, int possible, String criteria, String originalLine) {
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
        // Rebuild rubric + summary, but preserve caret
        boolean success = true;
        int caret = reportEditor.getCaretPosition();
        rebuildRubricAndSummaryInEditor(caret, false);
        // Persist the current editor state (text + caret) into the current student's draft
        if (currentStudent != null) {
            StudentDraft d = drafts.computeIfAbsent(currentStudent, StudentDraft::new);
            d.setMarkdown(reportEditor.getText() == null ? "" : reportEditor.getText());
            d.setCaretPosition(caret);
            d.setLoadedFromDisk(true);
        }


        if(success) {
            int wrote = 0;
            for (String pkg : studentPackages) {
                StudentDraft d = drafts.computeIfAbsent(pkg, StudentDraft::new);

                // Ensure we have content for non-current students too
                if (!d.isLoadedFromDisk()
                        || d.getMarkdown() == null
                        || d.getMarkdown().trim().isEmpty()) {

                    d.setMarkdown(loadInitialMarkdownForStudent(pkg));
                    d.setLoadedFromDisk(true);

                    // Only reset caret for drafts that have never been touched
                    if (!pkg.equals(currentStudent)) {
                        d.setCaretPosition(0);
                    }
                }
                Path repoDir = findRepoDirForStudentPackage(pkg);
                if (repoDir == null) {
                    status("Could not find repo for " + pkg);
                    success = false;
                } else {

                    Path out = repoDir.resolve(assignmentId + pkg + ".md");

                    try {
                        Files.writeString(out,
                                d.getMarkdown() == null ? "" : d.getMarkdown(),
                                StandardCharsets.UTF_8);
                        wrote++;
                    } catch (IOException e) {
                        status("Failed writing " + out.getFileName() + ": " + e.getMessage());
                        success = false;
                    }
                }
            }
            if(success) {
                status("Saved " + wrote + " draft(s) to student repositories.");
            }
        }
    }

    @FXML
    private void onExportGradedHtml() {
        int wrote = 0;
        int failed = 0;
        for (String pkg : studentPackages) {
            Path repoDir = findRepoDirForStudentPackage(pkg);
            if (repoDir == null) {
                failed++;
            } else {
                Path draftMd = repoDir.resolve(assignmentId + pkg + ".md");
                if (Files.exists(draftMd) && Files.isRegularFile(draftMd)) {
                    String md = null;
                    boolean readSuccess = true;
                    try {
                        md = Files.readString(draftMd, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        failed++;
                        readSuccess = false;
                    }
                    if (readSuccess) {
                        String title = assignmentId + pkg;
                        String html = wrapMarkdownAsHtml(title, md);
                        Path outHtml = repoDir.resolve(title + ".html");
                        Path outMd = repoDir.resolve(title + ".md");
                        try {
                            Files.writeString(outHtml, html, StandardCharsets.UTF_8);
                            Files.writeString(outMd, md, StandardCharsets.UTF_8);
                            wrote++;
                        } catch (IOException e) {
                            failed++;
                        }
                    }
                } else {
                    failed++;
                }
            }
        }

        status("Export complete: wrote " + wrote +
                " repo report(s) (.html + .md), failed " + failed + ".");
    }


    private Path findRepoDirForStudentPackage(String studentPackage) {
        Path mappingFile = resolveMappingsPath(mappingsPath, rootPath);
        if (!Files.exists(mappingFile)) {
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
        String safeTitle = title == null ? "" : title.trim();
        String md = markdown == null ? "" : markdown;

        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>" +
                "<title>" + escapeHtml(safeTitle) + "</title></head><body><xmp>\n" +
                md +
                "\n</xmp><script type=\"text/javascript\" " +
                "src=\"https://csse.msoe.us/gradedown.js\"></script></body></html>\n";
    }

    private String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    @FXML
    private void onSaveAndExport() {
        if (saveAndExportButton != null) {
            saveAndExportButton.setDisable(true);
        }

        // Make sure the current editor contents are captured (not just caret listener)
        if (currentStudent != null) {
            saveCurrentEditorToDraft(currentStudent);
        }

        // Save + export on UI thread (touches CodeArea / status label)
        try {
            onSaveDrafts();       // rebuild + save to grading/
            onExportGradedHtml(); // write .html + .md into mapped repos
        } catch (Exception e) {
            status("Save/export failed: " + e.getMessage());
            if (saveAndExportButton != null) {
                saveAndExportButton.setDisable(false);
            }
            return;
        }

        // Push in background
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "push-feedback");
            t.setDaemon(true);
            return t;
        });

        CompletableFuture.runAsync(() -> {
            PushResult r = pushAllRepos();
            Platform.runLater(() -> {
                status("Save/export/push complete: pushed " + r.pushed +
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
            Path outMd = repoDir.resolve(baseName + ".md");

            // Only attempt push if exported artifacts exist in the mapped repo
            if (!Files.exists(outHtml) || !Files.exists(outMd)) {
                skipped++;
                continue;
            }

            try {
                int add = runGit(repoDir, "add",
                        outHtml.getFileName().toString(),
                        outMd.getFileName().toString());

                if (add != 0) {
                    failed++;
                    continue;
                }

                // Commit will exit non-zero if nothing changed; treat as skipped
                int commit = runGit(repoDir, "commit", "-m", "Add feedback for " + assignmentId);
                if (commit != 0) {
                    skipped++;
                    continue;
                }

                int push = runGit(repoDir, "push");
                if (push != 0) {
                    failed++;
                    continue;
                }

                pushed++;

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

    private Path resolveMappingsPath(Path preferred, Path root) {
        Path resolved = preferred;

        if (root == null) {
            return resolved != null && Files.isRegularFile(resolved) ? resolved : null;
        }
        if (resolved == null || !Files.isRegularFile(resolved)) {

            Path rootCandidate = root.resolve("mappings.json");
            if (Files.isRegularFile(rootCandidate)) {
                resolved = rootCandidate;
            } else {
                Path dirCandidate = root.resolve("mappings").resolve("mappings.json");
                if (Files.isRegularFile(dirCandidate)) {
                    resolved = dirCandidate;
                } else {
                    resolved = null;
                }
            }
        }

        return resolved;
    }
}

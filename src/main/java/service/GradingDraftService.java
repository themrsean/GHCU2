/*
 * Course: CSC-1110/1120
 * GitHub Classroom Utilities
 */
package service;

import model.Comments;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradingDraftService {

    private static final String HTML_EXTENSION = ".html";
    private static final String FEEDBACK_FOLDER_NAME = "feedback";
    private static final String COMMENTS_SUMMARY_BEGIN = "<!-- COMMENTS_SUMMARY_BEGIN -->";
    private static final String COMMENTS_SUMMARY_END = "<!-- COMMENTS_SUMMARY_END -->";
    private static final String RUBRIC_TABLE_BEGIN = "<!-- RUBRIC_TABLE_BEGIN -->";
    private static final String RUBRIC_TABLE_END = "<!-- RUBRIC_TABLE_END -->";
    private static final String FEEDBACK_HEADER = "> # Feedback";
    private static final String DEFAULT_FEEDBACK_MARKDOWN = "> * Nice work";

    private final ReportHtmlWrapper htmlWrapper;
    private final ReportFileWriter reportFileWriter;

    public GradingDraftService(ReportHtmlWrapper htmlWrapper) {
        this(htmlWrapper, null);
    }

    GradingDraftService(ReportHtmlWrapper htmlWrapper,
                        ReportFileWriter reportFileWriter) {
        this.htmlWrapper = Objects.requireNonNull(htmlWrapper);
        this.reportFileWriter = reportFileWriter == null
                ? this::writeReportFileAtomically
                : reportFileWriter;
    }

    public Map<String, Integer> loadManualDeductionsFromGradingDraft(String assignmentId,
                                                                     String studentPackage,
                                                                     Path rootPath) {

        Objects.requireNonNull(assignmentId);
        Objects.requireNonNull(studentPackage);
        Objects.requireNonNull(rootPath);

        String markdown = loadReportMarkdown(assignmentId, studentPackage, rootPath);
        return parseManualDeductions(markdown);
    }

    public String loadFeedbackSectionMarkdown(String assignmentId,
                                              String studentPackage,
                                              Path rootPath) {

        Objects.requireNonNull(assignmentId);
        Objects.requireNonNull(studentPackage);
        Objects.requireNonNull(rootPath);

        String markdown = loadReportMarkdown(assignmentId, studentPackage, rootPath);
        String extractedFeedback = extractFeedbackSectionMarkdown(markdown);

        if (extractedFeedback.isBlank()) {
            return DEFAULT_FEEDBACK_MARKDOWN;
        }

        return extractedFeedback;
    }

    public String loadReportMarkdown(String assignmentId,
                                     String studentPackage,
                                     Path rootPath) {
        return loadReportMarkdownResult(assignmentId, studentPackage, rootPath).markdown();
    }

    public LoadReportResult loadReportMarkdownResult(String assignmentId,
                                                     String studentPackage,
                                                     Path rootPath) {

        Objects.requireNonNull(assignmentId);
        Objects.requireNonNull(studentPackage);
        Objects.requireNonNull(rootPath);

        Path reportPath = buildReportPath(assignmentId, studentPackage, rootPath);

        if (!Files.exists(reportPath) || !Files.isRegularFile(reportPath)) {
            return new LoadReportResult(true, false, "", "");
        }

        try {
            String html = Files.readString(reportPath);
            return new LoadReportResult(true, true, htmlWrapper.extractMarkdown(html), "");
        } catch (IOException e) {
            return new LoadReportResult(false, true, "", e.getMessage());
        }
    }

    public void saveReportMarkdown(String assignmentId,
                                   String studentPackage,
                                   Path rootPath,
                                   String markdown) throws IOException {
        saveReportMarkdown(assignmentId, studentPackage, rootPath, markdown, null);
    }

    public void saveReportMarkdown(String assignmentId,
                                   String studentPackage,
                                   Path rootPath,
                                   String markdown,
                                   Path feedbackRootOverride) throws IOException {

        Objects.requireNonNull(assignmentId);
        Objects.requireNonNull(studentPackage);
        Objects.requireNonNull(rootPath);
        Objects.requireNonNull(markdown);

        Path reportPath = buildReportPath(assignmentId, studentPackage, rootPath);
        String title = studentPackage == null ? "" : studentPackage;
        String exportedMarkdown = normalizeForSavedReport(markdown);
        String html = htmlWrapper.wrapMarkdownAsHtml(title, exportedMarkdown);

        reportFileWriter.write(reportPath, html);
        writeFeedbackCopy(
                rootPath,
                reportPath.getFileName().toString(),
                html,
                feedbackRootOverride
        );
    }

    private String normalizeForSavedReport(String markdown) {
        String out = markdown == null ? "" : markdown;
        out = replaceRubricPatchBlockWithRawTable(out);
        out = GradingMarkdownSections.removeAllBlocks(
                out,
                COMMENTS_SUMMARY_BEGIN,
                COMMENTS_SUMMARY_END
        );
        out = removeTotalRowFromRubricTable(out);
        return out;
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

    private void writeReportFileAtomically(Path reportPath,
                                           String html) throws IOException {
        Path parent = reportPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String prefix = reportPath.getFileName().toString();
        Path tempFile = Files.createTempFile(parent, prefix, ".tmp");
        boolean moved = false;

        try {
            Files.writeString(tempFile, html);
            try {
                Files.move(
                        tempFile,
                        reportPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, reportPath, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private void writeFeedbackCopy(Path rootPath,
                                   String reportFileName,
                                   String html,
                                   Path feedbackRootOverride) throws IOException {
        Path feedbackRoot = feedbackRootOverride == null
                ? resolveFeedbackRoot(rootPath)
                : feedbackRootOverride;
        Path feedbackDir = feedbackRoot.resolve(FEEDBACK_FOLDER_NAME);
        Path feedbackReportPath = feedbackDir.resolve(reportFileName);
        writeReportFileAtomically(feedbackReportPath, html);
    }

    private Path resolveFeedbackRoot(Path rootPath) {
        Path current = rootPath;
        while (current != null) {
            Path name = current.getFileName();
            if (name != null && "packages".equals(name.toString())) {
                Path parent = current.getParent();
                if (parent != null) {
                    return parent;
                }
                break;
            }
            current = current.getParent();
        }
        return rootPath;
    }

    private Path buildReportPath(String assignmentId,
                                 String studentPackage,
                                 Path rootPath) {

        final String reportFileName = assignmentId + studentPackage + HTML_EXTENSION;
        return rootPath.resolve(reportFileName);
    }

    private Map<String, Integer> parseManualDeductions(String markdown) {
        Map<String, Integer> deductions = parseInjectedCommentDeductions(markdown);

        if (!deductions.isEmpty()) {
            return deductions;
        }

        return parseLegacyManualDeductions(markdown);
    }

    private Map<String, Integer> parseInjectedCommentDeductions(String markdown) {
        Map<String, Integer> deductions = new LinkedHashMap<>();

        List<Comments.ParsedComment> parsedComments =
                Comments.parseInjectedComments(markdown);

        for (Comments.ParsedComment comment : parsedComments) {
            String rubricItemId = comment.rubricItemId();
            if (rubricItemId == null || rubricItemId.isBlank()) {
                continue;
            }

            int pointsLost = Math.max(0, comment.pointsLost());
            int updatedPoints = deductions.getOrDefault(rubricItemId, 0) + pointsLost;
            deductions.put(rubricItemId, updatedPoints);
        }

        return deductions;
    }

    private Map<String, Integer> parseLegacyManualDeductions(String markdown) {
        Map<String, Integer> deductions = new LinkedHashMap<>();

        if (markdown == null || markdown.isBlank()) {
            return deductions;
        }

        final String deductionPatternText =
                "(?m)^>\\s*####\\s*-([0-9]+)\\s+(.+?)\\s*$";
        Pattern deductionPattern = Pattern.compile(deductionPatternText);
        Matcher deductionMatcher = deductionPattern.matcher(markdown);

        while (deductionMatcher.find()) {
            String pointsText = deductionMatcher.group(1);
            String rubricItemName = deductionMatcher.group(2).trim();
            int points = Integer.parseInt(pointsText);
            deductions.put(rubricItemName, points);
        }

        return deductions;
    }

    private String extractFeedbackSectionMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        String withoutSummaryBlocks = GradingMarkdownSections.removeAllBlocks(
                markdown,
                COMMENTS_SUMMARY_BEGIN,
                COMMENTS_SUMMARY_END
        );

        String fromFeedbackSection = extractFromFeedbackHeader(withoutSummaryBlocks);
        if (!fromFeedbackSection.isBlank()) {
            return fromFeedbackSection;
        }

        String blockContents = GradingMarkdownSections.extractBlockContentsOrEmpty(
                markdown,
                COMMENTS_SUMMARY_BEGIN,
                COMMENTS_SUMMARY_END
        );
        if (!blockContents.isBlank()) {
            return stripFeedbackHeader(blockContents);
        }

        return "";
    }

    private String extractFromFeedbackHeader(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        String[] lines = markdown.split("\\R", -1);
        int feedbackHeaderLine = -1;

        for (int i = 0; i < lines.length; i++) {
            if (FEEDBACK_HEADER.equals(lines[i].trim())) {
                feedbackHeaderLine = i;
                break;
            }
        }

        if (feedbackHeaderLine < 0) {
            return "";
        }

        StringBuilder feedbackBuilder = new StringBuilder();
        boolean sawFeedbackContent = false;

        for (int i = feedbackHeaderLine + 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line == null ? "" : line.trim();
            boolean isSectionHeading = isTopLevelSectionHeading(trimmed);
            boolean isGeneratedSourceHeading = isLikelyGeneratedSourceHeading(lines, i);

            if (isSectionHeading || isGeneratedSourceHeading) {
                break;
            }

            if (!sawFeedbackContent && trimmed.isEmpty()) {
                continue;
            }

            feedbackBuilder.append(line).append(System.lineSeparator());
            sawFeedbackContent = true;
        }

        return trimSurroundingBlankLines(feedbackBuilder.toString());
    }

    private boolean isLikelyGeneratedSourceHeading(String[] lines, int index) {
        if (lines == null || index < 0 || index >= lines.length) {
            return false;
        }

        String line = lines[index];
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isBlank() || trimmed.startsWith(">") || !trimmed.startsWith("### ")) {
            return false;
        }

        int maxLookahead = Math.min(lines.length - 1, index + 3);
        for (int j = index + 1; j <= maxLookahead; j++) {
            String next = lines[j];
            String nextTrimmed = next == null ? "" : next.trim();
            if (nextTrimmed.isEmpty()) {
                continue;
            }
            return nextTrimmed.startsWith("```");
        }

        return false;
    }

    private boolean isTopLevelSectionHeading(String trimmedLine) {
        if (trimmedLine == null || trimmedLine.isBlank()) {
            return false;
        }
        if (trimmedLine.startsWith(">")) {
            return false;
        }
        return trimmedLine.startsWith("## ");
    }

    private String stripFeedbackHeader(String blockContents) {
        String trimmed = blockContents.trim();

        if (!trimmed.startsWith(FEEDBACK_HEADER)) {
            return trimmed;
        }

        String withoutHeader = trimmed.substring(FEEDBACK_HEADER.length());
        String normalized = withoutHeader.replaceFirst("^\\R+", "");
        return normalized.trim();
    }

    private String trimSurroundingBlankLines(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String[] lines = text.split("\\R", -1);
        int first = 0;
        int last = lines.length - 1;

        while (first <= last && lines[first].trim().isEmpty()) {
            first++;
        }
        while (last >= first && lines[last].trim().isEmpty()) {
            last--;
        }

        StringBuilder out = new StringBuilder();
        for (int i = first; i <= last; i++) {
            out.append(lines[i]);
            if (i < last) {
                out.append(System.lineSeparator());
            }
        }
        return out.toString();
    }

    @FunctionalInterface
    interface ReportFileWriter {
        void write(Path reportPath, String html) throws IOException;
    }

    public record LoadReportResult(boolean readOk,
                                   boolean reportExists,
                                   String markdown,
                                   String message) {
    }
}

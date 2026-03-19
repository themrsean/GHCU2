/*
 * Course: CSC-1110/1120
 * GitHub Classroom Utilities
 */
package service;

import model.Comments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradingDraftService {

    private static final String HTML_EXTENSION = ".html";
    private static final String COMMENTS_SUMMARY_BEGIN = "<!-- COMMENTS_SUMMARY_BEGIN -->";
    private static final String COMMENTS_SUMMARY_END = "<!-- COMMENTS_SUMMARY_END -->";
    private static final String FEEDBACK_HEADER = "> # Feedback";
    private static final String DEFAULT_FEEDBACK_MARKDOWN = "> * No feedback provided";

    private final ReportHtmlWrapper htmlWrapper;

    public GradingDraftService(ReportHtmlWrapper htmlWrapper) {
        this.htmlWrapper = Objects.requireNonNull(htmlWrapper);
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

        Objects.requireNonNull(assignmentId);
        Objects.requireNonNull(studentPackage);
        Objects.requireNonNull(rootPath);

        Path reportPath = buildReportPath(assignmentId, studentPackage, rootPath);

        if (!Files.exists(reportPath) || !Files.isRegularFile(reportPath)) {
            return "";
        }

        try {
            String html = Files.readString(reportPath);
            return htmlWrapper.extractMarkdown(html);
        } catch (IOException e) {
            return "";
        }
    }

    public void saveReportMarkdown(String assignmentId,
                                   String studentPackage,
                                   Path rootPath,
                                   String markdown) throws IOException {

        Objects.requireNonNull(assignmentId);
        Objects.requireNonNull(studentPackage);
        Objects.requireNonNull(rootPath);
        Objects.requireNonNull(markdown);

        Path reportPath = buildReportPath(assignmentId, studentPackage, rootPath);
        String title = assignmentId + studentPackage;
        String html = htmlWrapper.wrapMarkdownAsHtml(title, markdown);

        Files.deleteIfExists(reportPath);
        Files.writeString(reportPath, html);
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
            boolean isSectionHeading = trimmed.startsWith("## ");

            if (isSectionHeading) {
                if (sawFeedbackContent) {
                    break;
                }
                continue;
            }

            if (!sawFeedbackContent && trimmed.isEmpty()) {
                continue;
            }

            feedbackBuilder.append(line).append(System.lineSeparator());
            sawFeedbackContent = true;
        }

        return trimSurroundingBlankLines(feedbackBuilder.toString());
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
}

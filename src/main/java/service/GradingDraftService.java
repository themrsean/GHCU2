/*
 * Course: CSC-1110/1120
 * GitHub Classroom Utilities
 */
package service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradingDraftService {

    private static final String HTML_EXTENSION = ".html";
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

        int feedbackHeaderIndex = markdown.indexOf(FEEDBACK_HEADER);
        if (feedbackHeaderIndex < 0) {
            return "";
        }

        int feedbackBodyStartIndex = feedbackHeaderIndex + FEEDBACK_HEADER.length();
        String remainingMarkdown = markdown.substring(feedbackBodyStartIndex);

        String[] lines = remainingMarkdown.split("\\R", -1);
        StringBuilder feedbackBuilder = new StringBuilder();
        boolean sawFeedbackContent = false;

        for (String line : lines) {
            boolean lineStartsBlockQuote = line.startsWith(">");
            boolean lineIsBlank = line.isBlank();

            if (!sawFeedbackContent) {
                if (lineIsBlank) {
                    continue;
                }

                if (lineStartsBlockQuote) {
                    feedbackBuilder.append(line).append(System.lineSeparator());
                    sawFeedbackContent = true;
                } else {
                    return "";
                }
            } else {
                if (lineStartsBlockQuote) {
                    feedbackBuilder.append(line).append(System.lineSeparator());
                } else if (lineIsBlank) {
                    break;
                } else {
                    break;
                }
            }
        }

        return feedbackBuilder.toString().trim();
    }
}
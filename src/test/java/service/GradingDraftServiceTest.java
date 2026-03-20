package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GradingDraftServiceTest {

    @Test
    public void loadManualDeductions_emptyWhenNoFile(@TempDir Path tmp) {
        GradingDraftService service =
                new GradingDraftService(new ReportHtmlWrapper());

        Map<String, Integer> result =
                service.loadManualDeductionsFromGradingDraft("A1", "smith", tmp);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void loadManualDeductions_parsesInjectedCommentsFromHtmlReport(
            @TempDir Path tmp) throws Exception {
        GradingDraftService service =
                new GradingDraftService(new ReportHtmlWrapper());

        String markdown = String.join(
                System.lineSeparator(),
                "# Sample",
                "",
                "<!-- COMMENTS_SUMMARY_BEGIN -->",
                "> # Feedback",
                "<a id=\"cmt_1\"></a>",
                "```",
                "> #### Missing semicolon",
                "> * -10 points (ri_impl)",
                "```",
                "",
                "<a id=\"cmt_2\"></a>",
                "```",
                "> #### Wrong variable",
                "> * -5 points (ri_extra)",
                "```",
                "",
                "<a id=\"cmt_3\"></a>",
                "```",
                "> #### Another issue",
                "> * -3 points (ri_impl)",
                "```",
                "<!-- COMMENTS_SUMMARY_END -->"
        );

        Path report = tmp.resolve("A1smith.html");
        Files.writeString(report, new ReportHtmlWrapper().wrapMarkdownAsHtml("A1smith", markdown));

        Map<String, Integer> result =
                service.loadManualDeductionsFromGradingDraft("A1", "smith", tmp);

        assertNotNull(result);
        assertEquals(13, result.getOrDefault("ri_impl", -1).intValue());
        assertEquals(5, result.getOrDefault("ri_extra", -1).intValue());
        assertEquals(2, result.size());
    }

    @Test
    public void loadFeedbackSectionMarkdown_prefersFeedbackHeaderOverSummaryBlock(
            @TempDir Path tmp) throws Exception {
        GradingDraftService service =
                new GradingDraftService(new ReportHtmlWrapper());

        String markdown = String.join(
                System.lineSeparator(),
                "# Sample",
                "",
                "<!-- COMMENTS_SUMMARY_BEGIN -->",
                "> # Feedback",
                ">> # Comments",
                ">>",
                ">> * [Missing semicolon](#cmt_1) (-10 ri_impl)",
                "<!-- COMMENTS_SUMMARY_END -->",
                "",
                "> # Feedback",
                "<a id=\"cmt_1\"></a>",
                "```",
                "> #### Missing semicolon",
                "> * -10 points (ri_impl)",
                "> Fix the statement terminator.",
                "```",
                "",
                "## Source Code"
        );

        Path report = tmp.resolve("A1smith.html");
        Files.writeString(report, new ReportHtmlWrapper().wrapMarkdownAsHtml("A1smith", markdown));

        String feedback =
                service.loadFeedbackSectionMarkdown("A1", "smith", tmp);

        assertEquals(
                String.join(
                        System.lineSeparator(),
                        "<a id=\"cmt_1\"></a>",
                        "```",
                        "> #### Missing semicolon",
                        "> * -10 points (ri_impl)",
                        "> Fix the statement terminator.",
                        "```"
                ),
                feedback
        );
    }

    @Test
    public void loadFeedbackSectionMarkdown_fallsBackToSummaryBlockWhenHeaderMissing(
            @TempDir Path tmp) throws Exception {
        GradingDraftService service =
                new GradingDraftService(new ReportHtmlWrapper());

        String markdown = String.join(
                System.lineSeparator(),
                "# Sample",
                "",
                "<!-- COMMENTS_SUMMARY_BEGIN -->",
                "> # Feedback",
                "<a id=\"cmt_1\"></a>",
                "```",
                "> #### Missing semicolon",
                "> * -10 points (ri_impl)",
                "> Fix the statement terminator.",
                "```",
                "",
                "<!-- COMMENTS_SUMMARY_END -->",
                "",
                "## Source Code"
        );

        Path report = tmp.resolve("A1smith.html");
        Files.writeString(report, new ReportHtmlWrapper().wrapMarkdownAsHtml("A1smith", markdown));

        String feedback =
                service.loadFeedbackSectionMarkdown("A1", "smith", tmp);

        assertEquals(
                String.join(
                        System.lineSeparator(),
                        "<a id=\"cmt_1\"></a>",
                        "```",
                        "> #### Missing semicolon",
                        "> * -10 points (ri_impl)",
                        "> Fix the statement terminator.",
                        "```"
                ),
                feedback
        );
    }

    @Test
    public void loadReportMarkdown_returnsEmptyForMalformedWrappedHtml(
            @TempDir Path tmp) throws Exception {
        GradingDraftService service =
                new GradingDraftService(new ReportHtmlWrapper());

        Path report = tmp.resolve("A1smith.html");
        Files.writeString(report, "<html><body># Not wrapped</body></html>");

        String markdown = service.loadReportMarkdown("A1", "smith", tmp);

        assertEquals("", markdown);
    }

    @Test
    public void saveReportMarkdown_overwritesExistingReportOnSuccess(
            @TempDir Path tmp) throws Exception {
        ReportHtmlWrapper wrapper = new ReportHtmlWrapper();
        GradingDraftService service = new GradingDraftService(wrapper);
        Path report = tmp.resolve("A1smith.html");

        String oldMarkdown = "# Old";
        String newMarkdown = "# New";
        Files.writeString(report, wrapper.wrapMarkdownAsHtml("A1smith", oldMarkdown));

        service.saveReportMarkdown("A1", "smith", tmp, newMarkdown);

        String loaded = service.loadReportMarkdown("A1", "smith", tmp);
        assertEquals(newMarkdown, loaded.trim());
    }

    @Test
    public void saveReportMarkdown_usesStudentPackageAsHtmlTitle(@TempDir Path tmp)
            throws Exception {
        ReportHtmlWrapper wrapper = new ReportHtmlWrapper();
        GradingDraftService service = new GradingDraftService(wrapper);
        Path report = tmp.resolve("A1smith.html");

        service.saveReportMarkdown("A1", "smith", tmp, "# New");

        String html = Files.readString(report);
        assertTrue(html.contains("<title>smith</title>"));
    }

    @Test
    public void saveReportMarkdown_stripsInternalPatchBlocks_andOmitsTotalRow(@TempDir Path tmp)
            throws Exception {
        ReportHtmlWrapper wrapper = new ReportHtmlWrapper();
        GradingDraftService service = new GradingDraftService(wrapper);

        String markdown = String.join(
                System.lineSeparator(),
                "# Lab Assignment 1 - Image Displayer 3000",
                "",
                "<!-- RUBRIC_TABLE_BEGIN -->",
                ">> | Earned | Possible | Criteria                                          |",
                ">> | ------ | -------- | ------------------------------------------------- |",
                ">> |   10   |     10   | Intermediate Commits                              |",
                ">> |   60   |     60   | Coding Implementation and Structure               |",
                ">> |   70   |     70   | TOTAL                                             |",
                "<!-- RUBRIC_TABLE_END -->",
                "",
                "<!-- COMMENTS_SUMMARY_BEGIN -->",
                ">> # Comments",
                ">>",
                ">> * _No comments._",
                "<!-- COMMENTS_SUMMARY_END -->",
                "",
                "> # Feedback",
                "> * Nice work!"
        );

        service.saveReportMarkdown("L1", "ahlere", tmp, markdown);

        String saved = service.loadReportMarkdown("L1", "ahlere", tmp);
        assertTrue(saved.contains(">> | Earned | Possible | Criteria"));
        assertTrue(saved.contains("> # Feedback"));
        assertTrue(saved.contains("> * Nice work!"));
        assertFalse(saved.contains("<!-- RUBRIC_TABLE_BEGIN -->"));
        assertFalse(saved.contains("<!-- COMMENTS_SUMMARY_BEGIN -->"));
        assertFalse(saved.contains("| TOTAL |"));
    }

    @Test
    public void saveReportMarkdown_writeFailure_preservesExistingReport(
            @TempDir Path tmp) throws Exception {
        ReportHtmlWrapper wrapper = new ReportHtmlWrapper();
        Path report = tmp.resolve("A1smith.html");
        String oldMarkdown = "# Existing Instructor Feedback";
        String oldHtml = wrapper.wrapMarkdownAsHtml("A1smith", oldMarkdown);
        Files.writeString(report, oldHtml);

        GradingDraftService service = new GradingDraftService(
                wrapper,
                (GradingDraftService.ReportFileWriter) (reportPath, html) -> {
                    Path parent = reportPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Path staged = Files.createTempFile(parent, "staged-fail-", ".tmp");
                    Files.writeString(staged, html);
                    throw new java.io.IOException("simulated write failure");
                }
        );

        java.io.IOException error = assertThrows(
                java.io.IOException.class,
                () -> service.saveReportMarkdown("A1", "smith", tmp, "# New Content")
        );
        assertEquals("simulated write failure", error.getMessage());
        assertEquals(oldHtml, Files.readString(report));
        assertEquals(oldMarkdown, service.loadReportMarkdown("A1", "smith", tmp).trim());
    }
}

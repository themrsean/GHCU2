package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}

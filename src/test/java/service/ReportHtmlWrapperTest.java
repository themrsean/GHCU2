package service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReportHtmlWrapperTest {

    @Test
    public void wrapsTitleAndMarkdown_whenProvided() {
        ReportHtmlWrapper w = new ReportHtmlWrapper();

        String title = "  Course101 A1  ";
        String md = "# Heading\nSome **bold** text <tag>";

        String html = w.wrapMarkdownAsHtml(title, md);

        assertTrue(html.contains("<title>  Course101 A1  </title>"));
        assertTrue(html.contains("# Heading"));
        assertTrue(html.contains("Some **bold** text <tag>"));
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("<script type=\"text/javascript\""));
    }

    @Test
    public void extractMarkdown_roundTripsWrappedMarkdown() {
        ReportHtmlWrapper w = new ReportHtmlWrapper();

        String markdown = "# Title\n\n> Feedback";
        String html = w.wrapMarkdownAsHtml("A1smith", markdown);

        String extracted = w.extractMarkdown(html);

        assertEquals(markdown, extracted);
    }

    @Test
    public void extractMarkdown_returnsEmptyWhenWrapperIsMalformed() {
        ReportHtmlWrapper w = new ReportHtmlWrapper();

        assertEquals("", w.extractMarkdown("<html><body># Not wrapped</body></html>"));
        assertEquals("", w.extractMarkdown("<xmp># Missing close"));
        assertEquals("", w.extractMarkdown("</xmp><xmp>bad order"));
    }

    @Test
    public void wrapMarkdownAsHtml_nullsThrow() {
        ReportHtmlWrapper w = new ReportHtmlWrapper();

        assertThrows(NullPointerException.class,
                () -> w.wrapMarkdownAsHtml(null, "md"));
        assertThrows(NullPointerException.class,
                () -> w.wrapMarkdownAsHtml("title", null));
    }
}

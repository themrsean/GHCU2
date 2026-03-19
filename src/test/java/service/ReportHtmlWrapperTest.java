package service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ReportHtmlWrapperTest {

    @Test
    public void wrapsTitleAndMarkdown_whenProvided() {
        ReportHtmlWrapper w = new ReportHtmlWrapper();

        String title = "  Course101 A1  ";
        String md = "# Heading\nSome **bold** text <tag>";

        String html = w.wrapMarkdown(title, md);

        // title should be trimmed
        assertTrue(html.contains("<title>Course101 A1</title>"), "title should be trimmed and placed in title tag");

        // markdown should be present inside the xmp block
        assertTrue(html.contains("# Heading"), "markdown heading should be present");
        assertTrue(html.contains("Some **bold** text <tag>"), "markdown content should be preserved inside output");

        // basic surrounding HTML elements should exist
        assertTrue(html.contains("<!DOCTYPE html>"), "should contain doctype");
        assertTrue(html.contains("<script type=\"text/javascript\""), "script include should be present");
    }

    @Test
    public void handlesNulls_gracefully() {
        ReportHtmlWrapper w = new ReportHtmlWrapper();

        String html = w.wrapMarkdown(null, null);

        // title should be empty
        assertTrue(html.contains("<title></title>"), "null title should produce empty title tag");

        // body should be empty inside xmp (allow whitespace)
        int start = html.indexOf("<xmp>");
        int end = html.indexOf("</xmp>");
        assertTrue(start >= 0 && end > start, "xmp block should be present");
        String inner = html.substring(start + "<xmp>".length(), end);
        assertTrue(inner.trim().isEmpty(), "xmp inner content should be empty or whitespace when markdown is null");
    }
}

/*
 * Course: CSC-1110/1120
 * GitHub Classroom Utilities
 */
package service;

import java.util.Objects;

public class ReportHtmlWrapper {

    private static final String OPEN_XMP_TAG = "<xmp>";
    private static final String CLOSE_XMP_TAG = "</xmp>";

    public String wrapMarkdownAsHtml(String title, String markdown) {

        Objects.requireNonNull(title);
        Objects.requireNonNull(markdown);

        return """
                <!DOCTYPE html><html><head><meta charset="utf-8"/><title>%s</title></head><body><xmp>
                %s
                </xmp><script type="text/javascript" src="https://csse.msoe.us/gradedown.js"></script></body></html>
                """.formatted(title, markdown);
    }

    public String extractMarkdown(String html) {

        if (html == null || html.isBlank()) {
            return "";
        }

        int startIndex = html.indexOf(OPEN_XMP_TAG);
        int endIndex = html.indexOf(CLOSE_XMP_TAG);

        if (startIndex < 0 || endIndex < 0 || endIndex < startIndex) {
            return "";
        }

        int contentStartIndex = startIndex + OPEN_XMP_TAG.length();
        return html.substring(contentStartIndex, endIndex).trim();
    }
}

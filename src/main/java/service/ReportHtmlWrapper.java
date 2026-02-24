/*
 * Course: CSC-1120
 * Assignment name
 * File name
 * Name: Sean Jones
 * Last Updated:
 */
package service;

public class ReportHtmlWrapper {

    public String wrapMarkdown(String title, String markdown) {

        String safeTitle = "";
        if (title != null) {
            safeTitle = title.trim();
        }

        String body = "";
        if (markdown != null) {
            body = markdown;
        }

        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8"/>
                  <title>%s</title>
                </head>
                <body>
                <xmp>
                %s
                </xmp>
                <script type="text/javascript"
                        src="https://csse.msoe.us/gradedown.js"></script>
                </body>
                </html>
                """.formatted(safeTitle, body);
    }
}

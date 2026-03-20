/*
 * Course: CSC-1120
 * GitHub Classroom Utilities
 */
package service;

public final class GradingMarkdownSections {

    private GradingMarkdownSections() {
        // utility class
    }

    public static String extractBlockContentsOrEmpty(String text,
                                                     String beginMarker,
                                                     String endMarker) {
        String block = extractBlockContentsOrNull(text, beginMarker, endMarker);
        return block == null ? "" : block;
    }

    public static String extractBlockContentsOrNull(String text,
                                                    String beginMarker,
                                                    String endMarker) {
        if (text == null) {
            return null;
        }

        int beginIndex = text.indexOf(beginMarker);
        int endIndex = text.indexOf(endMarker);

        if (beginIndex < 0 || endIndex < 0 || endIndex < beginIndex) {
            return null;
        }

        int start = beginIndex + beginMarker.length();
        return text.substring(start, endIndex).trim();
    }

    public static String removeAllBlocks(String text,
                                         String beginMarker,
                                         String endMarker) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String out = text;
        while (true) {
            int beginIndex = out.indexOf(beginMarker);
            if (beginIndex < 0) {
                break;
            }

            int endIndex = out.indexOf(endMarker, beginIndex + beginMarker.length());
            if (endIndex < 0) {
                out = out.substring(0, beginIndex)
                        + out.substring(beginIndex + beginMarker.length());
                continue;
            }

            int afterEnd = endIndex + endMarker.length();
            out = out.substring(0, beginIndex) + out.substring(afterEnd);
        }

        return out;
    }
}

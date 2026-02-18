/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Comments {
    public static class AppliedComment {
        private String commentId;      // references CommentDef.commentId
        private String filePath;       // relative path in repo (ex: src/ahlere/Color.java)
        private int lineNumber;        // 1-based
        private int columnNumber;      // optional; 1-based; can be 0 if unused
        private String rubricItemId;   // which rubric item it deducts from
        private String assignmentKey;  // The assignment this comment applies to

        public AppliedComment() {
        }

        public String getCommentId() {
            return commentId;
        }

        public void setCommentId(String commentId) {
            this.commentId = commentId;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public void setLineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
        }

        public int getColumnNumber() {
            return columnNumber;
        }

        public void setColumnNumber(int columnNumber) {
            this.columnNumber = columnNumber;
        }

        public String getAssignmentKey() {
            return assignmentKey;
        }

        public void setAssignmentKey(String assignmentKey) {
            this.assignmentKey = assignmentKey;
        }

        public String getRubricItemId() {
            return rubricItemId;
        }

        public void setRubricItemId(String rubricItemId) {
            this.rubricItemId = rubricItemId;
        }
    }

    /**
     * Reusable comment definition, tied to a specific assignment and rubric item.
     * Stored in comments.json.
     */
    public static class CommentDef {
        private String commentId;
        private String assignmentKey;
        private String rubricItemId;
        private String title;
        private String bodyMarkdown;
        private int pointsDeducted;

        public CommentDef() {
            // Required for JSON
        }

        public String getCommentId() {
            return commentId;
        }

        public void setCommentId(String commentId) {
            this.commentId = commentId;
        }

        public String getAssignmentKey() {
            return assignmentKey;
        }

        public void setAssignmentKey(String assignmentKey) {
            this.assignmentKey = assignmentKey;
        }

        public String getRubricItemId() {
            return rubricItemId;
        }

        public void setRubricItemId(String rubricItemId) {
            this.rubricItemId = rubricItemId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getBodyMarkdown() {
            return bodyMarkdown;
        }

        public void setBodyMarkdown(String bodyMarkdown) {
            this.bodyMarkdown = bodyMarkdown;
        }

        public int getPointsDeducted() {
            return pointsDeducted;
        }

        public void setPointsDeducted(int pointsDeducted) {
            this.pointsDeducted = pointsDeducted;
        }

        @Override
        public String toString() {
            String id = commentId == null ? "" : commentId;
            String title = this.title == null ? "" : this.title;
            return id + " — " + title;
        }

    }

    /**
     * Root JSON container for comment definitions.
     */
    public static class CommentsLibrary {
        private int schemaVersion;
        private List<CommentDef> comments;

        public CommentsLibrary() {
            // Required for JSON
        }

        public int getSchemaVersion() {
            return schemaVersion;
        }

        public void setSchemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
        }

        public List<CommentDef> getComments() {
            return comments;
        }

        public void setComments(List<CommentDef> comments) {
            this.comments = comments;
        }

        public static CommentsLibrary newEmpty() {
            CommentsLibrary f = new CommentsLibrary();
            f.setSchemaVersion(1);
            f.setComments(new ArrayList<>());
            return f;
        }
    }

    public record ParsedComment(String anchorId,
                                String rubricItemId,
                                int pointsLost,
                                String title) {
    }

    public static class CommentsStore {

        private final ObjectMapper mapper;

        public CommentsStore() {
            mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }

        public CommentsLibrary load(Path path) throws IOException {
            if (path == null) {
                throw new IOException("Comments path is null.");
            }

            if (!Files.exists(path)) {
                return CommentsLibrary.newEmpty();
            }

            byte[] bytes = Files.readAllBytes(path);
            CommentsLibrary file = mapper.readValue(bytes, CommentsLibrary.class);

            if (file == null) {
                return CommentsLibrary.newEmpty();
            }
            if (file.getComments() == null) {
                file.setComments(new java.util.ArrayList<>());
            }
            if (file.getSchemaVersion() <= 0) {
                file.setSchemaVersion(1);
            }

            return file;
        }

        public void save(Path path, CommentsLibrary file) throws IOException {
            if (path == null) {
                throw new IOException("Comments path is null.");
            }
            if (file == null) {
                throw new IOException("Comments file is null.");
            }

            if (file.getSchemaVersion() <= 0) {
                file.setSchemaVersion(1);
            }
            if (file.getComments() == null) {
                file.setComments(new java.util.ArrayList<>());
            }

            ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
            String json = writer.writeValueAsString(file);

            Files.writeString(path, json);
        }
    }

    public static List<ParsedComment> parseInjectedComments(String markdown) {
        List<ParsedComment> out = new ArrayList<>();

        if (markdown == null || markdown.isEmpty()) {
            return out;
        }

        String[] lines = markdown.split("\\R", -1);

        String lastAnchor = null;
        String lastTitle = null;
        String lastRubric = null;
        Integer lastLoss = null;

        for (String line : lines) {
            if (line == null) {
                continue;
            }

            String t = line.trim();

            // anchor line:
            // <a id="cmt_..."></a>
            if (t.startsWith("<a") && t.contains("id=\"") && t.contains("\"></a>")) {
                int idIndex = t.indexOf("id=\"");
                if (idIndex >= 0) {
                    int start = idIndex + 4;
                    int end = t.indexOf("\"", start);
                    if (end > start) {
                        lastAnchor = t.substring(start, end);
                        lastTitle = null;
                        lastRubric = null;
                        lastLoss = null;
                    }
                }
                continue;
            }

            // title line:
            // > #### Something
            if (t.startsWith("> ####")) {
                lastTitle = t.substring("> ####".length()).trim();
                continue;
            }

            // points line:
            // > * -10 points (ri_impl)
            if (t.startsWith("> *") && t.contains("points") && t.contains("(") && t.contains(")")) {
                int open = t.lastIndexOf("(");
                int close = t.lastIndexOf(")");
                if (open >= 0 && close > open) {
                    String rubric = t.substring(open + 1, close).trim();

                    int dash = t.indexOf("-");
                    int pointsWord = t.indexOf("points", dash);

                    if (dash >= 0 && pointsWord > dash) {
                        String between = t.substring(dash + 1, pointsWord).trim();
                        try {
                            int n = Integer.parseInt(between);
                            lastRubric = rubric;
                            lastLoss = n;
                        } catch (NumberFormatException ignored) {
                            // Should do nothing here - will fix eventually
                        }
                    }
                }
            }

            // finalize a comment once we have anchor + title + rubric + loss
            if (lastAnchor != null && lastTitle != null && lastRubric != null && lastLoss != null) {
                out.add(new ParsedComment(lastAnchor, lastRubric, lastLoss, lastTitle));

                // reset so we don't double-add
                lastAnchor = null;
                lastTitle = null;
                lastRubric = null;
                lastLoss = null;
            }
        }

        return out;
    }
}

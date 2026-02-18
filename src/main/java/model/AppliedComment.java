/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package model;

public class AppliedComment {
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

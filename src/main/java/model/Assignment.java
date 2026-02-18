/*
 * Course: CSC-1120
 * ASSIGNMENT
 * FILE
 * Name: Sean Jones
 * Last Updated:
 */
package model;

import java.util.List;

/**
 * One assignment definition.
 */
public class Assignment {
    private String courseCode;
    private String assignmentCode;
    private String assignmentName;

    /**
     * Relative file paths, typically containing {studentPackage}.
     * Example: "src/{studentPackage}/Color.java"
     */
    private List<String> expectedFiles;

    private Rubric rubric;

    public Assignment() {
        // Required for JSON deserialization
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getAssignmentCode() {
        return assignmentCode;
    }

    public void setAssignmentCode(String assignmentCode) {
        this.assignmentCode = assignmentCode;
    }

    public String getAssignmentName() {
        return assignmentName;
    }

    public void setAssignmentName(String assignmentName) {
        this.assignmentName = assignmentName;
    }

    public List<String> getExpectedFiles() {
        return expectedFiles;
    }

    public void setExpectedFiles(List<String> expectedFiles) {
        this.expectedFiles = expectedFiles;
    }

    public Rubric getRubric() {
        return rubric;
    }

    public void setRubric(Rubric rubric) {
        this.rubric = rubric;
    }

    public String getKey() {
        return getCourseCode() + "-" + getAssignmentCode();
    }

    @Override
    public String toString() {
        return courseCode + " " + assignmentCode + " - " + assignmentName;
    }

    /**
     * Rubric container.
     * <p>
     * JSON shape:
     * "rubric": { "items": [ { "rubricItemId": "...", "points": 10 }, ... ] }
     */
    public static class Rubric {
        private List<RubricItemRef> items;

        public Rubric() {
            // Required for JSON deserialization
        }

        public List<RubricItemRef> getItems() {
            return items;
        }

        public void setItems(List<RubricItemRef> items) {
            this.items = items;
        }

    }
}

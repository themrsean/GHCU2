/*
 * Course: CSC-1120
 * ASSIGNMENT
 * FILE
 * Name: Sean Jones
 * Last Updated:
 */
package model;

/**
 * Reference to a rubric item definition, with points overridden for a specific assignment.
 *
 * JSON shape:
 * { "rubricItemId": "ri_commits", "points": 10 }
 */
public class RubricItemRef {
    private String rubricItemId;
    private int points;

    public RubricItemRef() {
        // Required for JSON deserialization
    }

    public String getRubricItemId() {
        return rubricItemId;
    }

    public void setRubricItemId(String rubricItemId) {
        this.rubricItemId = rubricItemId;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }
}

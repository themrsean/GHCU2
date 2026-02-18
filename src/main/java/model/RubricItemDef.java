/*
 * Course: CSC-1120
 * ASSIGNMENT
 * FILE
 * Name: Sean Jones
 * Last Updated:
 */
package model;

/**
 * Rubric item definition stored in the global rubric item library.
 *
 * JSON shape (example):
 * "ri_commits": {
 *   "id": "ri_commits",
 *   "name": "Intermediate Commits",
 *   "description": "...",
 *   "defaultPoints": 10,
 *   "isCheckstyleItem": false
 * }
 */
public class RubricItemDef {
    private String id;
    private String name;
    private String description;
    private int defaultPoints;
    private boolean isCheckstyleItem;

    public RubricItemDef() {
        // Required for JSON deserialization
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getDefaultPoints() {
        return defaultPoints;
    }

    public void setDefaultPoints(int defaultPoints) {
        this.defaultPoints = defaultPoints;
    }

    public boolean isCheckstyleItem() {
        return isCheckstyleItem;
    }

    public void setCheckstyleItem(boolean checkstyleItem) {
        isCheckstyleItem = checkstyleItem;
    }
}

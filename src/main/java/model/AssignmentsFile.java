/*
 * Course: CSC-1120
 * ASSIGNMENT
 * FILE
 * Name: Sean Jones
 * Last Updated:
 */
package model;

import java.util.List;
import java.util.Map;

/**
 * Root object for assignments.json.
 * Expected JSON shape:
 * {
 *   "schemaVersion": 1,
 *   "rubricItemLibrary": { "ri_x": { ... }, ... },
 *   "assignments": [ { ... }, ... ]
 * }
 */
public class AssignmentsFile {
    private int schemaVersion;
    private Map<String, RubricItemDef> rubricItemLibrary;
    private List<Assignment> assignments;

    public AssignmentsFile() {
        // Required for JSON deserialization libraries (e.g., Jackson)
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Map<String, RubricItemDef> getRubricItemLibrary() {
        return rubricItemLibrary;
    }

    public void setRubricItemLibrary(Map<String, RubricItemDef> rubricItemLibrary) {
        this.rubricItemLibrary = rubricItemLibrary;
    }

    public List<Assignment> getAssignments() {
        return assignments;
    }

    public void setAssignments(List<Assignment> assignments) {
        this.assignments = assignments;
    }
}

/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package model;

import java.util.ArrayList;
import java.util.List;

public class GradeReport {
    private String courseCode;
    private String assignmentCode;
    private String studentPackage;

    private List<AppliedComment> appliedComments = new ArrayList<>();

    public GradeReport() {
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

    public String getStudentPackage() {
        return studentPackage;
    }

    public void setStudentPackage(String studentPackage) {
        this.studentPackage = studentPackage;
    }

    public List<AppliedComment> getAppliedComments() {
        return appliedComments;
    }

    public void setAppliedComments(List<AppliedComment> appliedComments) {
        this.appliedComments = appliedComments;
    }
}

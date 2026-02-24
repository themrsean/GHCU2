/*
 * Course: CSC1110/1120
 */
package model;
/**
 * Simple data object representing the mapping between a student's extracted package name
 * and the original GitHub Classroom repository it came from.
 *
 * <p>This is serialized to and deserialized from {@code mapping.json} so later steps
 * (imports generation and report generation) can locate the correct repository folder
 * for each student package.</p>
 */
public class RepoMapping {
    private String repoPath;

    public String getRepoPath() {
        return repoPath;
    }

    public void setRepoPath(String repoPath) {
        this.repoPath = repoPath;
    }
}
package service;

import model.Assignment;

import java.nio.file.Path;

public class WorkflowContext {

    private final String cloneCmd;
    private final Assignment assignment;
    private final Path root;
    private final Path mappingsPath;

    public WorkflowContext(String cloneCmd,
                           Assignment assignment,
                           Path root,
                           Path mappingsPath) {

        this.cloneCmd = cloneCmd;
        this.assignment = assignment;
        this.root = root;
        this.mappingsPath = mappingsPath;
    }

    public String cloneCmd() { return cloneCmd; }
    public Assignment assignment() { return assignment; }
    public Path root() { return root; }
    public Path mappingsPath() { return mappingsPath; }
}
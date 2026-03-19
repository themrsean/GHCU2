package service;

import model.Assignment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class WorkflowContextTest {

    // Tests the constructor and getter accessors of service.WorkflowContext
    // Verified production class: src/main/java/service/WorkflowContext.java
    // Methods exercised:
    // - public WorkflowContext(String cloneCmd, Assignment assignment, Path root, Path mappingsPath)
    // - public String cloneCmd()
    // - public Assignment assignment()
    // - public Path root()
    // - public Path mappingsPath()

    @Test
    public void gettersReturnConstructorValues() {
        String cmd = "git clone https://example.com/repo.git";
        Assignment assignment = new Assignment();
        assignment.setCourseCode("CSC-0001");
        Path root = Path.of("/tmp/workflow/root");
        Path mappings = Path.of("/tmp/mappings.json");

        WorkflowContext ctx = new WorkflowContext(cmd, assignment, root, mappings);

        assertEquals(cmd, ctx.cloneCmd());
        assertSame(assignment, ctx.assignment());
        assertEquals(root, ctx.root());
        assertEquals(mappings, ctx.mappingsPath());
    }
}

package service.steps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import service.ImportsService;
import service.ServiceLogger;
import service.WorkflowContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for service.steps.ImportsStep
 *
 * Verified production sources read before writing these tests:
 * - src/main/java/service/steps/ImportsStep.java
 * - src/main/java/service/WorkflowContext.java
 * - src/main/java/service/ImportsService.java
 */
public class ImportsStepTest {

    @Test
    public void stepType_isImports() {
        ServiceLogger logger = s -> {};
        ImportsService importsService = new ImportsService(logger) {
            @Override
            public void generateImports(Path root, Path mappingsPath) throws IOException { /* no-op */ }
        };

        ImportsStep step = new ImportsStep(importsService, logger);
        assertEquals(RunAllStep.IMPORTS, step.stepType());
    }

    @Test
    public void execute_success_returnsSuccess(@TempDir Path tmp) throws IOException {
        AtomicReference<String> lastLog = new AtomicReference<>();
        ServiceLogger logger = lastLog::set;

        ImportsService importsService = new ImportsService(logger) {
            @Override
            public void generateImports(Path root, Path mappingsPath) throws IOException {
                // create packages dir and a package to satisfy the implementation if called
                Path packages = root.resolve("packages");
                Files.createDirectories(packages.resolve("astudent"));
                // write a dummy imports.txt so callers can inspect if desired
                Files.writeString(root.resolve("imports.txt"), "// import astudent.*;" + System.lineSeparator());
            }
        };

        ImportsStep step = new ImportsStep(importsService, logger);
        WorkflowContext ctx = new WorkflowContext("cmd", null, tmp, tmp.resolve("mappings.json"));

        StepResult result = step.execute(ctx);

        assertTrue(result.isSuccess());
        assertFalse(result.isFailed());
        assertEquals("", result.message());

        Path output = tmp.resolve("imports.txt");
        assertTrue(Files.exists(output), "imports.txt should be created by our stubbed ImportsService");
    }

    @Test
    public void execute_failure_logsAndReturnsFailed(@TempDir Path tmp) {
        AtomicReference<String> lastLog = new AtomicReference<>();
        ServiceLogger logger = lastLog::set;

        ImportsService importsService = new ImportsService(logger) {
            @Override
            public void generateImports(Path root, Path mappingsPath) throws IOException {
                throw new IOException("bad things");
            }
        };

        ImportsStep step = new ImportsStep(importsService, logger);
        WorkflowContext ctx = new WorkflowContext("cmd", null, tmp, tmp.resolve("mappings.json"));

        StepResult result = step.execute(ctx);

        assertTrue(result.isFailed());
        assertFalse(result.isSuccess());
        assertEquals("bad things", result.message());

        assertNotNull(lastLog.get());
        assertTrue(lastLog.get().contains("Generate Imports failed"));
        assertTrue(lastLog.get().contains("bad things"));
    }
}

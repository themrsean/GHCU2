package service.steps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import service.MappingService;
import service.ServiceLogger;
import service.WorkflowContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for service.steps.ExtractStep
 *
 * Verified production sources read before writing these tests:
 * - src/main/java/service/steps/ExtractStep.java
 *     public StepResult execute(WorkflowContext context)
 *     public RunAllStep stepType()
 * - src/main/java/service/WorkflowContext.java
 */
public class ExtractStepTest {

    @Test
    public void stepType_isExtract() {
        ServiceLogger logger = s -> {};
        MappingService mappingService = new MappingService(logger) {
            @Override
            public void extractPackages(Path selectedRootPath, Path mappingsPath) throws IOException { /* no-op */ }
        };

        ExtractStep step = new ExtractStep(mappingService, logger);
        assertEquals(RunAllStep.EXTRACT, step.stepType());
    }

    @Test
    public void execute_success_returnsSuccess(@TempDir Path tmp) {
        AtomicReference<String> lastLog = new AtomicReference<>();
        ServiceLogger logger = lastLog::set;

        MappingService mappingService = new MappingService(logger) {
            @Override
            public void extractPackages(Path selectedRootPath, Path mappingsPath) throws IOException {
                // simulate successful extraction (no-op)
            }
        };

        ExtractStep step = new ExtractStep(mappingService, logger);
        WorkflowContext ctx = new WorkflowContext("cmd", null, tmp, tmp.resolve("mappings.json"));

        StepResult result = step.execute(ctx);

        assertTrue(result.isSuccess(), "Expected success when mappingService does not throw");
        assertFalse(result.isFailed());
        assertEquals("", result.message(), "Expected empty message on success");
    }

    @Test
    public void execute_failure_logsAndReturnsFailed(@TempDir Path tmp) {
        AtomicReference<String> lastLog = new AtomicReference<>();
        ServiceLogger logger = lastLog::set;

        MappingService mappingService = new MappingService(logger) {
            @Override
            public void extractPackages(Path selectedRootPath, Path mappingsPath) throws IOException {
                throw new IOException("boom");
            }
        };

        ExtractStep step = new ExtractStep(mappingService, logger);
        WorkflowContext ctx = new WorkflowContext("cmd", null, tmp, tmp.resolve("mappings.json"));

        StepResult result = step.execute(ctx);

        assertTrue(result.isFailed(), "Expected failed when mappingService throws");
        assertFalse(result.isSuccess());
        assertEquals("boom", result.message());

        assertNotNull(lastLog.get());
        assertTrue(lastLog.get().contains("Extract failed"));
        assertTrue(lastLog.get().contains("boom"));
    }
}

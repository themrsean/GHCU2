package service.steps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import service.ProcessRunner;
import service.ServiceLogger;
import service.WorkflowContext;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for service.steps.PullStep
 *
 * Verified production sources read before writing these tests:
 * - src/main/java/service/steps/PullStep.java
 *     public StepResult execute(WorkflowContext context)
 *     public RunAllStep stepType()
 * - src/main/java/service/ProcessRunner.java
 *     public List<String> tokenizeCommand(String command)
 *     public int runAndLog(List<String> args, Path workingDir, LineLogger logger)
 * - src/main/java/service/WorkflowContext.java
 */
public class PullStepTest {

    @Test
    public void stepType_isPull() {
        ServiceLogger logger = s -> {};
        ProcessRunner runner = new ProcessRunner();

        PullStep step = new PullStep(runner, logger);
        assertEquals(RunAllStep.PULL, step.stepType());
    }

    @Test
    public void execute_nullCloneCmd_returnsFailed(@TempDir Path tmp) {
        AtomicReference<String> lastLog = new AtomicReference<>();
        ServiceLogger logger = lastLog::set;

        ProcessRunner runner = new ProcessRunner() { /* use defaults */ };

        PullStep step = new PullStep(runner, logger);

        WorkflowContext ctx = new WorkflowContext(null, null, tmp, tmp.resolve("mappings.json"));

        StepResult result = step.execute(ctx);

        assertTrue(result.isFailed());
        assertFalse(result.isSuccess());
        assertEquals("", result.message());
    }

    @Test
    public void execute_emptyCommandAfterTokenize_returnsFailed(@TempDir Path tmp) {
        AtomicReference<String> lastLog = new AtomicReference<>();
        ServiceLogger logger = lastLog::set;

        // use real tokenizer: blank command -> no tokens -> no run
        ProcessRunner runner = new ProcessRunner();

        PullStep step = new PullStep(runner, logger);
        WorkflowContext ctx = new WorkflowContext("   ", null, tmp, tmp.resolve("mappings.json"));

        StepResult result = step.execute(ctx);

        assertTrue(result.isFailed());
        assertFalse(result.isSuccess());
    }

    @Test
    public void execute_runAndLogExitZero_returnsSuccessAndLogs(@TempDir Path tmp) {
        AtomicReference<String> lastLog = new AtomicReference<>();
        ServiceLogger logger = lastLog::set;

        // stub runner to capture the LineLogger calls and return success
        ProcessRunner runner = new ProcessRunner() {
            @Override
            public int runAndLog(List<String> args, Path workingDir, LineLogger logger) {
                // simulate process output lines
                logger.log("line1");
                logger.log("line2");
                return 0; // success
            }
        };

        PullStep step = new PullStep(runner, logger);

        WorkflowContext ctx = new WorkflowContext("echo hello", null, tmp, tmp.resolve("mappings.json"));

        StepResult result = step.execute(ctx);

        assertTrue(result.isSuccess(), "Expected success when runAndLog returns 0");
        assertFalse(result.isFailed());
        assertEquals("", result.message());

        // last logged line should be from our stubbed runner
        assertEquals("line2", lastLog.get());
    }
}

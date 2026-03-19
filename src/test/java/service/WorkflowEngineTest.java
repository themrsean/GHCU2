package service;

import org.junit.jupiter.api.Test;
import service.steps.RunAllStep;
import service.steps.StepResult;
import service.steps.StepStatus;
import service.steps.WorkflowStep;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class WorkflowEngineTest {

    // The tests below exercise the following production symbols (verified in source):
    // - service.WorkflowEngine#run(service.WorkflowContext)
    //   (see src/main/java/service/WorkflowEngine.java)
    // - service.steps.WorkflowStep#stepType() and #execute(service.WorkflowContext)
    //   (see src/main/java/service/steps/WorkflowStep.java)
    // - service.steps.StepResult and StepStatus (see src/main/java/service/steps/StepResult.java and StepStatus.java)

    @Test
    public void run_allStepsExecuted_andLogged() {
        List<String> logs = new ArrayList<>();
        ServiceLogger logger = logs::add;

        AtomicInteger executed = new AtomicInteger(0);

        WorkflowStep s1 = new WorkflowStep() {
            @Override
            public RunAllStep stepType() { return RunAllStep.PULL; }

            @Override
            public StepResult execute(WorkflowContext context) {
                executed.incrementAndGet();
                return new StepResult(StepStatus.SUCCESS, 10L, "ok");
            }
        };

        WorkflowStep s2 = new WorkflowStep() {
            @Override
            public RunAllStep stepType() { return RunAllStep.EXTRACT; }

            @Override
            public StepResult execute(WorkflowContext context) {
                executed.incrementAndGet();
                return new StepResult(StepStatus.SUCCESS, 5L, "ok2");
            }
        };

        WorkflowEngine engine = new WorkflowEngine(List.of(s1, s2), logger);

        var result = engine.run(new WorkflowContext("c", new model.Assignment(), Path.of("."), Path.of("m")));

        assertNotNull(result);
        assertEquals(2, result.results().size());
        assertTrue(result.results().get(RunAllStep.PULL).isSuccess());
        assertTrue(result.results().get(RunAllStep.EXTRACT).isSuccess());
        assertEquals(2, executed.get(), "Both steps should have been executed");

        assertTrue(logs.stream().anyMatch(l -> l.contains("STEP PULL")), "Logger should contain PULL step line");
        assertTrue(logs.stream().anyMatch(l -> l.contains("STEP EXTRACT")), "Logger should contain EXTRACT step line");
    }

    @Test
    public void run_failureSkipsRemaining_stepsNotExecuted_butLoggedAsSkipped() {
        List<String> logs = new ArrayList<>();
        ServiceLogger logger = logs::add;

        AtomicInteger executed = new AtomicInteger(0);

        WorkflowStep failing = new WorkflowStep() {
            @Override
            public RunAllStep stepType() { return RunAllStep.PULL; }

            @Override
            public StepResult execute(WorkflowContext context) {
                executed.incrementAndGet();
                return new StepResult(StepStatus.FAILED, 1L, "boom");
            }
        };

        WorkflowStep shouldBeSkipped = new WorkflowStep() {
            @Override
            public RunAllStep stepType() { return RunAllStep.REPORTS; }

            @Override
            public StepResult execute(WorkflowContext context) {
                // If this were executed, we'd increment — engine should skip executing this.
                executed.incrementAndGet();
                return new StepResult(StepStatus.SUCCESS, 2L, "later");
            }
        };

        WorkflowEngine engine = new WorkflowEngine(List.of(failing, shouldBeSkipped), logger);

        var result = engine.run(new WorkflowContext("c", new model.Assignment(), Path.of("."), Path.of("m")));

        assertFalse(result.overallSuccess());
        assertEquals(2, result.results().size());
        assertTrue(result.results().get(RunAllStep.PULL).isFailed());
        assertTrue(result.results().get(RunAllStep.REPORTS).isSkipped());

        // Only the failing step's execute() should have been called
        assertEquals(1, executed.get(), "Only the first (failing) step should have been executed");

        // Engine should still log both steps (REPORTS should be logged as SKIPPED)
        assertTrue(logs.stream().anyMatch(l -> l.contains("STEP PULL")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("STEP REPORTS") && l.contains("SKIPPED")));
    }
}

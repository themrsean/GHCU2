package service;

import org.junit.jupiter.api.Test;
import service.steps.RunAllStep;
import service.steps.StepResult;
import service.steps.StepStatus;
import service.steps.WorkflowStep;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RunAllServiceTest {

    // We exercise the following production methods (verified in source):
    // - service.RunAllService#runAll(String, model.Assignment, java.nio.file.Path, java.nio.file.Path)
    // - service.WorkflowEngine#run(service.WorkflowContext)
    // Also use service.steps.WorkflowStep#stepType() and #execute(...)

    @Test
    public void runAll_allSuccess_logsAndReturnsSuccess() {
        List<String> logs = new ArrayList<>();
        ServiceLogger logger = logs::add;

        WorkflowStep s1 = new WorkflowStep() {
            @Override
            public RunAllStep stepType() { return RunAllStep.PULL; }

            @Override
            public StepResult execute(WorkflowContext context) {
                return new StepResult(StepStatus.SUCCESS, 10L, "ok");
            }
        };

        WorkflowStep s2 = new WorkflowStep() {
            @Override
            public RunAllStep stepType() { return RunAllStep.EXTRACT; }

            @Override
            public StepResult execute(WorkflowContext context) {
                return new StepResult(StepStatus.SUCCESS, 5L, "ok2");
            }
        };

        RunAllService svc = new RunAllService(List.of(s1, s2), logger);

        var result = svc.runAll("clone", new model.Assignment(), Path.of("."), Path.of("mappings"));

        // verify logging contained start/end markers
        assertTrue(logs.stream().anyMatch(l -> l.contains("=== RUN ALL START ===")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("=== RUN ALL END ===")));

        // Verify step results and overall success
        assertNotNull(result);
        assertEquals(2, result.results().size());
        assertTrue(result.results().get(RunAllStep.PULL).isSuccess());
        assertTrue(result.results().get(RunAllStep.EXTRACT).isSuccess());
        assertTrue(result.overallSuccess());
    }

    @Test
    public void runAll_firstFails_secondSkipped_overallFailure() {
        List<String> logs = new ArrayList<>();
        ServiceLogger logger = logs::add;

        WorkflowStep failing = new WorkflowStep() {
            @Override
            public RunAllStep stepType() { return RunAllStep.PULL; }

            @Override
            public StepResult execute(WorkflowContext context) {
                return new StepResult(StepStatus.FAILED, 1L, "boom");
            }
        };

        WorkflowStep maybe = new WorkflowStep() {
            @Override
            public RunAllStep stepType() { return RunAllStep.REPORTS; }

            @Override
            public StepResult execute(WorkflowContext context) {
                // If executed would succeed, but engine should skip it
                return new StepResult(StepStatus.SUCCESS, 2L, "later");
            }
        };

        RunAllService svc = new RunAllService(List.of(failing, maybe), logger);

        var result = svc.runAll("clone", new model.Assignment(), Path.of("."), Path.of("mappings"));

        assertFalse(result.overallSuccess());
        assertEquals(2, result.results().size());
        // first is failed
        assertTrue(result.results().get(RunAllStep.PULL).isFailed());
        // second should be marked skipped by WorkflowEngine
        assertTrue(result.results().get(RunAllStep.REPORTS).isSkipped());
        // logger should contain step log lines
        assertTrue(logs.stream().anyMatch(l -> l.contains("STEP PULL")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("STEP REPORTS")));
    }
}

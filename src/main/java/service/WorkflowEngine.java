package service;

import service.steps.RunAllStep;
import service.steps.StepResult;
import service.steps.StepStatus;
import service.steps.WorkflowStep;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorkflowEngine {

    private final List<WorkflowStep> steps;
    private final ServiceLogger logger;

    public WorkflowEngine(List<WorkflowStep> steps,
                          ServiceLogger logger) {

        this.steps = steps;
        this.logger = logger;
    }

    public RunAllService.RunAllResult run(WorkflowContext context) {

        Map<RunAllStep, StepResult> results =
                new LinkedHashMap<>();

        boolean previousFailed = false;

        for (WorkflowStep step : steps) {

            StepResult result;

            if (previousFailed) {
                result = new StepResult(
                        StepStatus.SKIPPED,
                        0L,
                        "Skipped due to previous failure."
                );
            } else {
                result = step.execute(context);
            }

            results.put(step.stepType(), result);

            logger.log("STEP "
                    + step.stepType()
                    + " → "
                    + result.status()
                    + " ("
                    + result.durationMillis()
                    + " ms)");

            if (result.isFailed()) {
                previousFailed = true;
            }
        }

        return new RunAllService.RunAllResult(results);
    }
}
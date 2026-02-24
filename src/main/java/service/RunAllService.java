package service;

import model.Assignment;
import service.steps.RunAllStep;
import service.steps.StepResult;
import service.steps.WorkflowStep;

import java.nio.file.Path;
import java.util.List;

public class RunAllService {

    private final WorkflowEngine engine;
    private final ServiceLogger logger;

    public RunAllService(List<WorkflowStep> steps,
                         ServiceLogger logger) {

        this.engine = new WorkflowEngine(steps, logger);
        this.logger = logger;
    }

    public RunAllResult runAll(String cloneCmd,
                               Assignment assignment,
                               Path root,
                               Path mappingsPath) {

        logger.log("=== RUN ALL START ===");

        WorkflowContext context =
                new WorkflowContext(
                        cloneCmd,
                        assignment,
                        root,
                        mappingsPath
                );

        RunAllResult result = engine.run(context);

        logger.log(result.overallSuccess()
                ? "Run All completed successfully."
                : "Run All finished with errors.");

        logger.log("=== RUN ALL END ===");

        return result;
    }

    public record RunAllResult(
            java.util.Map<RunAllStep, StepResult> results) {

        public boolean overallSuccess() {

            for (StepResult r : results.values()) {
                if (!r.isSuccess()) {
                    return false;
                }
            }

            return true;
        }
    }
}
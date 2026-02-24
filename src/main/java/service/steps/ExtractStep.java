package service.steps;

import service.MappingService;
import service.ServiceLogger;
import service.WorkflowContext;

import java.io.IOException;

public class ExtractStep implements WorkflowStep {

    private final MappingService mappingService;
    private final ServiceLogger logger;

    public ExtractStep(MappingService mappingService,
                       ServiceLogger logger) {

        this.mappingService = mappingService;
        this.logger = logger;
    }

    @Override
    public RunAllStep stepType() {
        return RunAllStep.EXTRACT;
    }

    @Override
    public StepResult execute(WorkflowContext context) {

        final long startTimeMillis = System.currentTimeMillis();

        boolean success = true;
        String message = "";

        try {
            mappingService.extractPackages(
                    context.root(),
                    context.mappingsPath()
            );
        } catch (IOException e) {
            logger.log("Extract failed: " + e.getMessage());
            success = false;
            message = e.getMessage();
        }

        final long durationMillis =
                System.currentTimeMillis() - startTimeMillis;

        return new StepResult(
                success ? StepStatus.SUCCESS : StepStatus.FAILED,
                durationMillis,
                message
        );
    }
}
package service.steps;

import service.ImportsService;
import service.ServiceLogger;
import service.WorkflowContext;

import java.io.IOException;

public class ImportsStep implements WorkflowStep {

    private final ImportsService importsService;
    private final ServiceLogger logger;

    public ImportsStep(ImportsService importsService,
                       ServiceLogger logger) {

        this.importsService = importsService;
        this.logger = logger;
    }

    @Override
    public RunAllStep stepType() {
        return RunAllStep.IMPORTS;
    }

    @Override
    public StepResult execute(WorkflowContext context) {

        final long startTimeMillis = System.currentTimeMillis();

        boolean success = true;
        String message = "";

        try {
            importsService.generateImports(
                    context.root(),
                    context.mappingsPath()
            );
        } catch (IOException e) {
            logger.log("Generate Imports failed: " + e.getMessage());
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
package service.steps;

import service.ReportService;
import service.ServiceLogger;
import service.WorkflowContext;

public class ReportsStep implements WorkflowStep {

    private final ReportService reportService;
    private final ServiceLogger logger;

    public ReportsStep(ReportService reportService,
                       ServiceLogger logger) {

        this.reportService = reportService;
        this.logger = logger;
    }

    @Override
    public RunAllStep stepType() {
        return RunAllStep.REPORTS;
    }

    @Override
    public StepResult execute(WorkflowContext context) {

        final long startTimeMillis = System.currentTimeMillis();

        boolean success;
        String message = "";

        ReportService.ReportGenerationResult result =
                reportService.generateReports(
                        context.assignment(),
                        context.root(),
                        context.mappingsPath()
                );

        success = result.isSuccess();

        if (!success) {
            message = "Report generation failed.";
            logger.log(message);
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
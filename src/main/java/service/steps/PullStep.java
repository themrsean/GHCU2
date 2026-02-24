package service.steps;

import service.ProcessRunner;
import service.ServiceLogger;
import service.WorkflowContext;

import java.util.List;

public class PullStep implements WorkflowStep {

    private final ProcessRunner processRunner;
    private final ServiceLogger logger;

    public PullStep(ProcessRunner processRunner,
                    ServiceLogger logger) {

        this.processRunner = processRunner;
        this.logger = logger;
    }

    @Override
    public RunAllStep stepType() {
        return RunAllStep.PULL;
    }

    @Override
    public StepResult execute(WorkflowContext context) {

        final long start = System.currentTimeMillis();

        boolean success = false;

        String cmd = context.cloneCmd();

        if (cmd != null) {
            List<String> args = processRunner.tokenizeCommand(cmd.trim());
            if (!args.isEmpty()) {
                int exit =
                        processRunner.runAndLog(
                                args,
                                context.root(),
                                logger::log
                        );
                success = exit == 0;
            }
        }

        final long duration =
                System.currentTimeMillis() - start;

        return new StepResult(
                success ? StepStatus.SUCCESS : StepStatus.FAILED,
                duration,
                ""
        );
    }
}
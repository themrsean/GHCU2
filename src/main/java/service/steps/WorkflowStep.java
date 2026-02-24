package service.steps;

import service.WorkflowContext;

public interface WorkflowStep {

    RunAllStep stepType();

    StepResult execute(WorkflowContext context);

}
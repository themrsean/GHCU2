package service;

import model.Assignment;
import service.steps.StepResult;

import java.nio.file.Path;

@FunctionalInterface
public interface StepExecutor {

    StepResult execute(String cloneCmd,
                       Assignment assignment,
                       Path root,
                       Path mappingsPath);
}
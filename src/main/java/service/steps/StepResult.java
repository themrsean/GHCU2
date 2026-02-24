package service.steps;

public record StepResult(
        StepStatus status,
        long durationMillis,
        String message
) {

    public boolean isSuccess() {
        return status == StepStatus.SUCCESS;
    }

    public boolean isFailed() {
        return status == StepStatus.FAILED;
    }

    public boolean isSkipped() {
        return status == StepStatus.SKIPPED;
    }
}
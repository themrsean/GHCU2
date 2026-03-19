package service.steps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for service.steps.StepResult
 *
 * Verified production sources read before writing these tests:
 * - src/main/java/service/steps/StepResult.java
 *     public record StepResult(StepStatus status, long durationMillis, String message) { ... }
 * - src/main/java/service/steps/StepStatus.java
 */
public class StepResultTest {

    @Test
    public void constructor_and_accessors() {
        StepResult r = new StepResult(StepStatus.SUCCESS, 123L, "ok");

        assertEquals(StepStatus.SUCCESS, r.status());
        assertEquals(123L, r.durationMillis());
        assertEquals("ok", r.message());

        assertTrue(r.isSuccess());
        assertFalse(r.isFailed());
        assertFalse(r.isSkipped());
    }

    @Test
    public void status_flags_for_failed_and_skipped() {
        StepResult failed = new StepResult(StepStatus.FAILED, 1L, "boom");
        assertTrue(failed.isFailed());
        assertFalse(failed.isSuccess());
        assertFalse(failed.isSkipped());

        StepResult skipped = new StepResult(StepStatus.SKIPPED, 0L, "skipped");
        assertTrue(skipped.isSkipped());
        assertFalse(skipped.isSuccess());
        assertFalse(skipped.isFailed());
    }

    @Test
    public void equals_and_hashcode_contract() {
        StepResult a = new StepResult(StepStatus.SUCCESS, 10L, "ok");
        StepResult b = new StepResult(StepStatus.SUCCESS, 10L, "ok");
        StepResult c = new StepResult(StepStatus.SUCCESS, 10L, "different");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        assertNotEquals(a, c);
    }
}

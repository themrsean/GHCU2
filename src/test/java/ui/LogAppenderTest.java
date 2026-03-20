package ui;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogAppenderTest {

    private static final long fxTimeoutMillis = 5000L;
    private static boolean fxRuntimeAvailable = false;

    @BeforeAll
    static void initJavaFxRuntime() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
            fxRuntimeAvailable = true;
        } catch (IllegalStateException alreadyStarted) {
            fxRuntimeAvailable = true;
            latch.countDown();
        } catch (Throwable startupFailure) {
            fxRuntimeAvailable = false;
            latch.countDown();
        }
        assertTrue(latch.await(fxTimeoutMillis, TimeUnit.MILLISECONDS));
    }

    @Test
    void log_nullMessage_doesNothing() throws Exception {
        requireFxRuntime();
        TextArea textArea = runOnFxAndWaitResult(TextArea::new);
        LogAppender appender = new LogAppender(textArea);

        appender.log(null);
        flushFxQueue();

        assertEquals("", runOnFxAndWaitResult(textArea::getText));
    }

    @Test
    void log_singleMessage_appendsLineSeparator() throws Exception {
        requireFxRuntime();
        TextArea textArea = runOnFxAndWaitResult(TextArea::new);
        LogAppender appender = new LogAppender(textArea);

        appender.log("hello");
        flushFxQueue();

        assertEquals("hello" + System.lineSeparator(), runOnFxAndWaitResult(textArea::getText));
    }

    @Test
    void log_multipleMessages_preservesOrder() throws Exception {
        requireFxRuntime();
        TextArea textArea = runOnFxAndWaitResult(TextArea::new);
        LogAppender appender = new LogAppender(textArea);

        appender.log("one");
        appender.log("two");
        appender.log("three");
        flushFxQueue();

        assertEquals(
                "one" + System.lineSeparator()
                        + "two" + System.lineSeparator()
                        + "three" + System.lineSeparator(),
                runOnFxAndWaitResult(textArea::getText)
        );
    }

    private void requireFxRuntime() {
        Assumptions.assumeTrue(fxRuntimeAvailable, "JavaFX runtime is unavailable");
    }

    private static void flushFxQueue() throws Exception {
        runOnFxAndWait(() -> {
            // no-op, used to wait until prior runLater tasks complete
        });
    }

    private static void runOnFxAndWait(FxRunnable runnable) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(fxTimeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("Timed out waiting for JavaFX task.");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }

    private static <T> T runOnFxAndWaitResult(FxSupplier<T> supplier) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        runOnFxAndWait(() -> result.set(supplier.get()));
        return result.get();
    }

    @FunctionalInterface
    private interface FxRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }
}

package service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ProcessRunnerTest {

    @Test
    public void tokenizeCommand_variousCases() {
        ProcessRunner pr = new ProcessRunner();

        assertEquals(List.of(), pr.tokenizeCommand(null));
        assertEquals(List.of(), pr.tokenizeCommand(""));
        assertEquals(List.of(), pr.tokenizeCommand("   \t  \n  "));

        List<String> parts = pr.tokenizeCommand("gh classroom clone student-repos -a 123");
        assertEquals(List.of("gh", "classroom", "clone", "student-repos", "-a", "123"), parts);

        parts = pr.tokenizeCommand("echo \"hello world\" test");
        assertEquals(List.of("echo", "hello world", "test"), parts);

        parts = pr.tokenizeCommand("a \"b c d");
        assertEquals(List.of("a", "b c d"), parts);
    }

    @Test
    public void runAndLog_handlesNullLogger() {
        ProcessRunner pr = new ProcessRunner();

        // runAndLog requires a non-null logger; verify it throws NPE when null
        assertThrows(NullPointerException.class, () -> pr.runAndLog(List.of("echo", "hi"), Path.of("."), null));
    }
}

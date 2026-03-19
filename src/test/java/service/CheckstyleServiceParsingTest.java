package service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CheckstyleServiceParsingTest {

    private CheckstyleService createStub() {
        ProcessRunner pr = new ProcessRunner() { };
        ServiceLogger logger = s -> { };
        return new CheckstyleService(pr, logger, Path.of("/does/not/exist/checkstyle.jar"));
    }

    @Test
    public void parseCheckstyleOutput_variousLines() throws Exception {
        CheckstyleService svc = createStub();

        Method m = CheckstyleService.class.getDeclaredMethod("parseCheckstyleOutput", List.class);
        m.setAccessible(true);

        List<String> input = List.of(
                "[ERROR] /home/user/repos/src/com/example/Foo.java:10: Missing semicolon",
                "[ERROR] C:\\projects\\repos\\src\\com\\example\\Bar.java:20: Some message",
                "INFO Something else",
                "[ERROR] /src/com/example/Baz.java:30: Another"
        );

        Object summary = m.invoke(svc, input);

        Method getTotal = summary.getClass().getMethod("getTotalViolations");
        Method getFiles = summary.getClass().getMethod("getFilesInOrder");
        Method getLines = summary.getClass().getMethod("getLinesForFile", String.class);

        int total = (Integer) getTotal.invoke(summary);
        @SuppressWarnings("unchecked")
        java.util.List<String> files = (List<String>) getFiles.invoke(summary);

        assertEquals(3, total);
        assertTrue(files.contains("com/example/Foo.java"));
        assertTrue(files.contains("com/example/Bar.java"));
        assertTrue(files.contains("com/example/Baz.java"));

        @SuppressWarnings("unchecked")
        List<String> linesFoo = (List<String>) getLines.invoke(summary, "com/example/Foo.java");
        assertEquals(1, linesFoo.size());
    }

    @Test
    public void buildExecutionFailedMarkdown_handlesNullList() throws Exception {
        CheckstyleService svc = createStub();

        Method m = CheckstyleService.class.getDeclaredMethod("buildExecutionFailedMarkdown", List.class);
        m.setAccessible(true);

        String out = (String) m.invoke(svc, new Object[]{null});
        assertTrue(out.contains("Checkstyle Execution Failed"));
    }
}

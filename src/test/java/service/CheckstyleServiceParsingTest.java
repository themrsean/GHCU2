package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
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

    @Test
    public void buildCheckstyleResult_disabled_returnsDisabledMarkdown(@TempDir Path tmp) {
        CheckstyleService service = createService(new ProcessRunner() { }, tmp.resolve("checkstyle.jar"));

        CheckstyleService.CheckstyleResult result = service.buildCheckstyleResult(
                tmp.resolve("repo"),
                tmp.resolve("root"),
                false,
                false,
                "https://example.com/checkstyle.xml"
        );

        assertEquals("_Checkstyle disabled._", result.markdown());
        assertEquals(0, result.totalViolations());
    }

    @Test
    public void buildCheckstyleResult_blankUrl_returnsGuidance(@TempDir Path tmp) {
        CheckstyleService service = createService(new ProcessRunner() { }, tmp.resolve("checkstyle.jar"));

        CheckstyleService.CheckstyleResult result = service.buildCheckstyleResult(
                tmp.resolve("repo"),
                tmp.resolve("root"),
                true,
                false,
                "   "
        );

        assertEquals("_Checkstyle enabled but URL is blank._", result.markdown());
        assertEquals(0, result.totalViolations());
    }

    @Test
    public void buildCheckstyleResult_missingJar_returnsGuidance(@TempDir Path tmp) {
        CheckstyleService service = createService(new ProcessRunner() { }, tmp.resolve("missing.jar"));

        CheckstyleService.CheckstyleResult result = service.buildCheckstyleResult(
                tmp.resolve("repo"),
                tmp.resolve("root"),
                true,
                false,
                "https://example.com/checkstyle.xml"
        );

        assertEquals("_Missing checkstyle jar in program folder._", result.markdown());
        assertEquals(0, result.totalViolations());
    }

    @Test
    public void buildCheckstyleResult_noJavaFiles_returnsNoFilesMessage(@TempDir Path tmp)
            throws Exception {
        Path root = tmp.resolve("root");
        Path repo = tmp.resolve("repo");
        Files.createDirectories(root.resolve("packages"));
        Files.createDirectories(repo.resolve("src"));
        Path jar = createJarFile(tmp.resolve("checkstyle.jar"));

        String url = "https://example.com/checkstyle.xml";
        seedCachedConfig(root, url);

        CheckstyleService service = createService(new ProcessRunner() { }, jar);

        CheckstyleService.CheckstyleResult result = service.buildCheckstyleResult(
                repo,
                root,
                true,
                false,
                url
        );

        assertEquals("_No Java files found under src/._", result.markdown());
        assertEquals(0, result.totalViolations());
    }

    @Test
    public void buildCheckstyleResult_processStartFailure_returnsExecutionFailedMarkdown(
            @TempDir Path tmp
    ) throws Exception {
        Path root = tmp.resolve("root");
        Path repo = tmp.resolve("repo");
        Files.createDirectories(root.resolve("packages"));
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src").resolve("Student.java"), "class Student {}");
        Path jar = createJarFile(tmp.resolve("checkstyle.jar"));

        String url = "https://example.com/checkstyle.xml";
        seedCachedConfig(root, url);

        ProcessRunner failingRunner = new ProcessRunner() {
            @Override
            public ProcessResult runCaptureLinesWithExitCode(List<String> args, Path workingDir) {
                return new ProcessResult(-1, List.of("runner failed"));
            }
        };
        CheckstyleService service = createService(failingRunner, jar);

        CheckstyleService.CheckstyleResult result = service.buildCheckstyleResult(
                repo,
                root,
                true,
                false,
                url
        );

        assertTrue(result.markdown().contains("### Checkstyle Execution Failed"));
        assertTrue(result.markdown().contains("runner failed"));
        assertEquals(0, result.totalViolations());
    }

    @Test
    public void buildCheckstyleResult_detectsViolations_andBuildsGroupedMarkdown(
            @TempDir Path tmp
    ) throws Exception {
        Path root = tmp.resolve("root");
        Path repo = tmp.resolve("repo");
        Files.createDirectories(root.resolve("packages"));
        Files.createDirectories(repo.resolve("src").resolve("demo"));
        Files.writeString(repo.resolve("src").resolve("demo").resolve("Student.java"), "class Student {}");
        Path jar = createJarFile(tmp.resolve("checkstyle.jar"));

        String url = "https://example.com/checkstyle.xml";
        seedCachedConfig(root, url);

        ProcessRunner runner = new ProcessRunner() {
            @Override
            public ProcessResult runCaptureLinesWithExitCode(List<String> args, Path workingDir) {
                String line = "[ERROR] " + repo.resolve("src").resolve("demo")
                        .resolve("Student.java").toString() + ":10: Missing semicolon";
                return new ProcessResult(1, List.of(line));
            }
        };
        CheckstyleService service = createService(runner, jar);

        CheckstyleService.CheckstyleResult result = service.buildCheckstyleResult(
                repo,
                root,
                true,
                false,
                url
        );

        assertEquals(1, result.totalViolations());
        assertTrue(result.markdown().contains("**Total Violations:** 1"));
        assertTrue(result.markdown().contains("### demo/Student.java"));
    }

    private CheckstyleService createService(ProcessRunner runner, Path jarPath) {
        ServiceLogger logger = _ -> { };
        return new CheckstyleService(runner, logger, jarPath);
    }

    private Path createJarFile(Path jarPath) throws IOException {
        Files.createDirectories(jarPath.getParent());
        return Files.writeString(jarPath, "jar placeholder");
    }

    private void seedCachedConfig(Path selectedRootPath, String url) throws IOException {
        Path packagesDir = selectedRootPath.resolve("packages");
        Files.createDirectories(packagesDir);
        Files.writeString(packagesDir.resolve("checkstyle.xml"), "<module name=\"Checker\"/>");
        Files.writeString(packagesDir.resolve("checkstyle-url.txt"), url + System.lineSeparator());
    }
}

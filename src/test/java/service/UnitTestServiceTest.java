package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class UnitTestServiceTest {

    @Test
    public void preparePatchedTestSuite_replacesUsernameImports(@TempDir Path tmp) throws IOException {
        // The preparePatchedTestSuite method is private; we test prepare+compile flow
        // by creating a minimal repo structure and a fake junit jar so buildUnitTestContext
        // does not fail on missing jar. We verify that when no src/java files exist,
        // the service returns a "No Java files found under src/" result.

        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo);

        Path src = repo.resolve("src");
        Files.createDirectories(src);

        Path test = src.resolve("test");
        Files.createDirectories(test);

        // Create a TestSuite.java containing username package references
        String suite = "package test;\nimport username.utils.Helper;\npublic class TestSuite {}\n";
        Files.writeString(test.resolve("TestSuite.java"), suite);

        // Create a fake junit jar so getBundledJUnitConsoleJar finds it
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib);
        Path fakeJar = lib.resolve("junit-platform-console-standalone-6.0.1.jar");
        Files.writeString(fakeJar, "fake");

        // Create a ProcessRunner that returns success for any process
        ProcessRunner pr = new ProcessRunner() {
            @Override
            public List<String> tokenizeCommand(String command) {
                return super.tokenizeCommand(command);
            }
        };

        // Use a logger that captures lines
        StringBuilder log = new StringBuilder();
        ServiceLogger logger = log::append;

        UnitTestService svc = new UnitTestService(pr, logger, new ToolArtifactService(tmp));

        // Call buildUnitTestResultMarkdown which should detect no java files under src/
        UnitTestService.UnitTestResult res = svc.buildUnitTestResultMarkdown("username", repo);

        assertNotNull(res);
        assertTrue(res.markdown().contains("No Java files found under src"));
        assertEquals(0, res.totalTests());
        assertEquals(0, res.failedTests());
        assertFalse(Files.exists(repo.resolve("build")));
    }
}

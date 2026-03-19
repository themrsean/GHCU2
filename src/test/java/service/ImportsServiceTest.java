package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for service.ImportsService
 *
 * The tests exercise:
 * - service.ImportsService#generateImports(java.nio.file.Path, java.nio.file.Path)
 * - service.ServiceLogger#log(String) (used to capture the written file message)
 *
 * Verified production sources read before writing these tests:
 * - src/main/java/service/ImportsService.java
 * - src/main/java/service/ServiceLogger.java
 */
public class ImportsServiceTest {

    @Test
    public void generateImports_writesSortedImportsAndLogs(@TempDir Path tmp) throws IOException {
        AtomicReference<String> lastLog = new AtomicReference<>();
        ServiceLogger logger = lastLog::set;

        ImportsService svc = new ImportsService(logger);

        // create packages folder with two student package dirs out of order to exercise sorting
        Path packages = tmp.resolve("packages");
        Files.createDirectories(packages.resolve("zstudent"));
        Files.createDirectories(packages.resolve("astudent"));

        svc.generateImports(tmp, null);

        Path output = tmp.resolve("imports.txt");
        assertTrue(Files.exists(output), "imports.txt should be created");

        String content = Files.readString(output);

        // Expect sorted order: astudent then zstudent, each on its own line with the exact prefix/suffix
        String expected = "// import astudent.*;" + System.lineSeparator()
                + "// import zstudent.*;" + System.lineSeparator();

        assertEquals(expected, content);

        // logger should have been called with the output path
        assertNotNull(lastLog.get(), "logger should be called");
        assertTrue(lastLog.get().contains("Wrote imports file:"), "log message should indicate wrote imports file");
        assertTrue(lastLog.get().contains(output.toString()), "log message should contain the output file path");
    }

    @Test
    public void generateImports_missingPackages_throws(@TempDir Path tmp) {
        ServiceLogger logger = s -> { /* no-op */ };
        ImportsService svc = new ImportsService(logger);

        IOException ex = assertThrows(IOException.class, () -> svc.generateImports(tmp, null));
        assertTrue(ex.getMessage().contains("Packages directory missing"));
    }

    @Test
    public void generateImports_emptyPackages_throws(@TempDir Path tmp) throws IOException {
        ServiceLogger logger = s -> { /* no-op */ };
        ImportsService svc = new ImportsService(logger);

        // create the packages directory but no student subdirectories
        Files.createDirectories(tmp.resolve("packages"));

        IOException ex = assertThrows(IOException.class, () -> svc.generateImports(tmp, null));
        assertTrue(ex.getMessage().contains("No student packages found under"));
    }
}

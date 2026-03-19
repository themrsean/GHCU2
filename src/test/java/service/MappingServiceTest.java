package service;

import model.RepoMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MappingServiceTest {

    // We exercise the following production methods (verified in source):
    // - service.MappingService#saveMapping(java.nio.file.Path, java.util.Map)
    // - service.MappingService#loadMapping(java.nio.file.Path)
    // - service.MappingService#resolveRepoRoot(java.nio.file.Path)
    // Also use model.RepoMapping#getRepoPath() and setRepoPath(...)

    private final MappingService svc = new MappingService(msg -> { /* no-op logger */ });

    @Test
    public void saveAndLoadMapping_roundTrip(@TempDir Path tmp) throws IOException {
        Path mappingFile = tmp.resolve("mappings.json");

        RepoMapping rm = new RepoMapping();
        rm.setRepoPath("/some/path");

        svc.saveMapping(mappingFile, Map.of("student", rm));

        assertTrue(Files.exists(mappingFile), "mapping file should be created");

        Map<String, RepoMapping> loaded = svc.loadMapping(mappingFile);
        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        assertTrue(loaded.containsKey("student"));
        assertEquals("/some/path", loaded.get("student").getRepoPath());
    }

    @Test
    public void loadMapping_nonExistent_returnsEmpty(@TempDir Path tmp) {
        Path mappingFile = tmp.resolve("does-not-exist.json");
        Map<String, RepoMapping> loaded = svc.loadMapping(mappingFile);
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void resolveRepoRoot_directAndNested(@TempDir Path tmp) throws IOException {
        // direct src
        Path repo1 = tmp.resolve("repo1");
        Files.createDirectories(repo1.resolve("src"));
        Path r1 = svc.resolveRepoRoot(repo1);
        assertEquals(repo1.toAbsolutePath().normalize(), r1.toAbsolutePath().normalize());

        // nested student folder containing src
        Path repo2 = tmp.resolve("repo2");
        Path student = repo2.resolve("studentA");
        Files.createDirectories(student.resolve("src"));
        Path r2 = svc.resolveRepoRoot(repo2);
        assertEquals(student.toAbsolutePath().normalize(), r2.toAbsolutePath().normalize());

        // null input returns null
        assertNull(svc.resolveRepoRoot(null));
    }
}

package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class GradingMappingsServiceTest {

    @Test
    public void resolveMappingFile_prefersProvidedMappingsPath(@TempDir Path tmp) {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path provided = tmp.resolve("provided-mappings.json");

        Path resolved = service.resolveMappingFile(provided, "A1", tmp.resolve("app-data"));

        assertEquals(provided, resolved);
    }

    @Test
    public void resolveMappingFile_fallsBackToAssignmentMappingName(@TempDir Path tmp) {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });

        Path resolved = service.resolveMappingFile(null, "CSC101A1", tmp.resolve("app-data"));

        assertEquals("mappings-CSC101A1.json", resolved.getFileName().toString());
    }

    @Test
    public void loadMappingsForUse_keepsValidMappingsWhenOneRepoPathIsInvalid(@TempDir Path tmp)
            throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path validRepo = tmp.resolve("repo-valid");
        Path invalidRepo = tmp.resolve("repo-missing");
        Files.createDirectories(validRepo);

        Path mappingFile = tmp.resolve("mappings.json");
        writeMappingFile(
                mappingFile,
                Map.of(
                        "pkgValid", validRepo.toString(),
                        "pkgMissing", invalidRepo.toString()
                )
        );

        Map<String, String> mappings =
                service.loadMappingsForUse(mappingFile, "A1", tmp, tmp.resolve("app-data"));

        assertEquals(Map.of("pkgValid", validRepo.toString()), mappings);
        assertEquals(
                validRepo,
                service.findRepoDirForStudentPackage(
                        "pkgValid",
                        mappingFile,
                        "A1",
                        tmp,
                        tmp.resolve("app-data")
                )
        );
        assertNull(service.findRepoDirForStudentPackage(
                "pkgMissing",
                mappingFile,
                "A1",
                tmp,
                tmp.resolve("app-data")
        ));
    }

    @Test
    public void loadMappingsForUse_contextChangeInvalidatesCachedMappings(@TempDir Path tmp)
            throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path repoA = tmp.resolve("repo-a");
        Path repoB = tmp.resolve("repo-b");
        Files.createDirectories(repoA);
        Files.createDirectories(repoB);

        Path mappingA = tmp.resolve("mappings-a.json");
        Path mappingB = tmp.resolve("mappings-b.json");
        writeMappingFile(mappingA, Map.of("pkgA", repoA.toString()));
        writeMappingFile(mappingB, Map.of("pkgB", repoB.toString()));

        Map<String, String> first =
                service.loadMappingsForUse(mappingA, "A1", tmp, tmp.resolve("app-data"));
        Map<String, String> second =
                service.loadMappingsForUse(mappingB, "A1", tmp, tmp.resolve("app-data"));

        assertEquals(Map.of("pkgA", repoA.toString()), first);
        assertEquals(Map.of("pkgB", repoB.toString()), second);
    }

    @Test
    public void loadMappingsForUse_revalidatesCachedMappingsAfterRepoDeletion(@TempDir Path tmp)
            throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path repo = tmp.resolve("repo-live");
        Files.createDirectories(repo);
        Path mappingFile = tmp.resolve("mappings-live.json");
        writeMappingFile(mappingFile, Map.of("pkgLive", repo.toString()));

        Map<String, String> initial =
                service.loadMappingsForUse(mappingFile, "A1", tmp, tmp.resolve("app-data"));
        assertEquals(Map.of("pkgLive", repo.toString()), initial);

        Files.delete(repo);

        Map<String, String> afterDelete =
                service.loadMappingsForUse(mappingFile, "A1", tmp, tmp.resolve("app-data"));

        assertEquals(Map.of(), afterDelete);
        assertNull(service.findRepoDirForStudentPackage(
                "pkgLive",
                mappingFile,
                "A1",
                tmp,
                tmp.resolve("app-data")
        ));
    }

    @Test
    public void loadMappingsForUse_refreshesCacheWhenMappingFileUpdates(@TempDir Path tmp)
            throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path repoOld = tmp.resolve("repo-old");
        Path repoNew = tmp.resolve("repo-new");
        Files.createDirectories(repoOld);
        Files.createDirectories(repoNew);
        Path mappingFile = tmp.resolve("mappings-refresh.json");
        writeMappingFile(mappingFile, Map.of("pkg", repoOld.toString()));
        Files.setLastModifiedTime(mappingFile, FileTime.fromMillis(System.currentTimeMillis()));

        Map<String, String> first =
                service.loadMappingsForUse(mappingFile, "A1", tmp, tmp.resolve("app-data"));
        assertEquals(Map.of("pkg", repoOld.toString()), first);

        writeMappingFile(mappingFile, Map.of("pkg", repoNew.toString()));
        Files.setLastModifiedTime(
                mappingFile,
                FileTime.fromMillis(System.currentTimeMillis() + 2000)
        );

        Map<String, String> second =
                service.loadMappingsForUse(mappingFile, "A1", tmp, tmp.resolve("app-data"));
        assertEquals(Map.of("pkg", repoNew.toString()), second);
    }

    private void writeMappingFile(Path mappingFile,
                                  Map<String, String> mappings) throws Exception {
        Map<String, Map<String, String>> json = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            json.put(entry.getKey(), Map.of("repoPath", entry.getValue()));
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(mappingFile.toFile(), json);
    }
}

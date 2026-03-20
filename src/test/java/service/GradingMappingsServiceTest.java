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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    public void loadMappingsForUse_reconstructsFromSingleValidContainer(@TempDir Path tmp)
            throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path root = tmp.resolve("root");
        Files.createDirectories(root);

        Path submissionsContainer = root.resolve("active-submissions");
        Path repoA = submissionsContainer.resolve("student-a");
        Path repoB = submissionsContainer.resolve("student-b");
        Files.createDirectories(repoA);
        Files.createDirectories(repoB);
        Files.writeString(repoA.resolve("A1pkgA.html"), "report-a");
        Files.writeString(repoB.resolve("A1pkgB.html"), "report-b");

        Path mappingFile = tmp.resolve("reconstructed-mappings.json");
        Map<String, String> mappings =
                service.loadMappingsForUse(mappingFile, "A1", root, tmp.resolve("app-data"));

        assertEquals(2, mappings.size());
        assertEquals(repoA.toString(), mappings.get("pkgA"));
        assertEquals(repoB.toString(), mappings.get("pkgB"));
    }

    @Test
    public void loadMappingsForUse_reconstructionWithMultipleContainers_usesLastDetectedContainer(
            @TempDir Path tmp
    ) throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path root = tmp.resolve("root-mixed");
        Files.createDirectories(root);

        // Intentionally create two candidate containers; implementation currently keeps the
        // last detected one while scanning root children.
        Path oldContainer = root.resolve("old-submissions");
        Path newContainer = root.resolve("new-submissions");
        Files.createDirectories(oldContainer.resolve("old-repo"));
        Files.createDirectories(newContainer.resolve("new-repo"));
        Files.writeString(oldContainer.resolve("old-repo").resolve("A1pkgOld.html"), "old");
        Files.writeString(newContainer.resolve("new-repo").resolve("A1pkgNew.html"), "new");

        Path mappingFile = tmp.resolve("mixed-mappings.json");
        Map<String, String> mappings =
                service.loadMappingsForUse(mappingFile, "A1", root, tmp.resolve("app-data"));

        assertEquals(1, mappings.size());
        assertEquals(newContainer.resolve("new-repo").toString(), mappings.get("pkgNew"));
    }

    @Test
    public void loadMappingsForUse_reconstruction_filtersOutOtherAssignmentReports(
            @TempDir Path tmp
    ) throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path root = tmp.resolve("root-assignment-filter");
        Files.createDirectories(root);

        Path submissionsContainer = root.resolve("submissions");
        Path repoA = submissionsContainer.resolve("repo-a");
        Path repoB = submissionsContainer.resolve("repo-b");
        Files.createDirectories(repoA);
        Files.createDirectories(repoB);

        Files.writeString(repoA.resolve("A1pkgA.html"), "a1-report");
        Files.writeString(repoA.resolve("A2pkgA.html"), "a2-report");
        Files.writeString(repoB.resolve("A2pkgB.html"), "a2-only-report");

        Path mappingFile = tmp.resolve("assignment-filter-mappings.json");
        Map<String, String> mappings =
                service.loadMappingsForUse(mappingFile, "A1", root, tmp.resolve("app-data"));

        assertEquals(1, mappings.size());
        assertEquals(repoA.toString(), mappings.get("pkgA"));
    }

    @Test
    public void loadMappingsForUse_reconstruction_ignoresPackagesFolder(@TempDir Path tmp)
            throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path root = tmp.resolve("root-ignore-packages");
        Files.createDirectories(root);

        Path packagesFolder = root.resolve("packages");
        Path packagedRepo = packagesFolder.resolve("repo-in-packages");
        Files.createDirectories(packagedRepo);
        Files.writeString(packagedRepo.resolve("A1pkgIgnored.html"), "ignored-report");

        Path submissionsContainer = root.resolve("submissions");
        Path validRepo = submissionsContainer.resolve("repo-valid");
        Files.createDirectories(validRepo);
        Files.writeString(validRepo.resolve("A1pkgReal.html"), "real-report");

        Path mappingFile = tmp.resolve("ignore-packages-mappings.json");
        Map<String, String> mappings =
                service.loadMappingsForUse(mappingFile, "A1", root, tmp.resolve("app-data"));

        assertEquals(1, mappings.size());
        assertEquals(validRepo.toString(), mappings.get("pkgReal"));
        assertNull(mappings.get("pkgIgnored"));
    }

    @Test
    public void loadMappingsForUse_reconstruction_selectsOnlyContainerWithAssignmentReports(
            @TempDir Path tmp
    ) throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path root = tmp.resolve("root-container-selection");
        Files.createDirectories(root);

        Path staleContainer = root.resolve("stale-submissions");
        Path staleRepo = staleContainer.resolve("repo-stale");
        Files.createDirectories(staleRepo);
        Files.writeString(staleRepo.resolve("A2pkgStale.html"), "old-assignment");

        Path currentContainer = root.resolve("current-submissions");
        Path currentRepo = currentContainer.resolve("repo-current");
        Files.createDirectories(currentRepo);
        Files.writeString(currentRepo.resolve("A1pkgCurrent.html"), "current-assignment");

        Path mappingFile = tmp.resolve("container-selection-mappings.json");
        Map<String, String> mappings =
                service.loadMappingsForUse(mappingFile, "A1", root, tmp.resolve("app-data"));

        assertEquals(1, mappings.size());
        assertEquals(currentRepo.toString(), mappings.get("pkgCurrent"));
        assertNull(mappings.get("pkgStale"));
    }

    @Test
    public void loadMappingsForUse_reconstruction_duplicatePackageAcrossContainers_returnsSingleWinner(
            @TempDir Path tmp
    ) throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path root = tmp.resolve("root-duplicate-container-package");
        Files.createDirectories(root);

        Path olderContainer = root.resolve("older-submissions");
        Path newerContainer = root.resolve("newer-submissions");
        Path oldRepo = olderContainer.resolve("repo-old");
        Path newRepo = newerContainer.resolve("repo-new");
        Files.createDirectories(oldRepo);
        Files.createDirectories(newRepo);

        Files.writeString(oldRepo.resolve("A1pkgShared.html"), "old");
        Files.writeString(newRepo.resolve("A1pkgShared.html"), "new");

        Path mappingFile = tmp.resolve("duplicate-container-package-mappings.json");
        Map<String, String> mappings =
                service.loadMappingsForUse(mappingFile, "A1", root, tmp.resolve("app-data"));

        assertEquals(1, mappings.size());
        String chosenPath = mappings.get("pkgShared");
        assertTrue(
                oldRepo.toString().equals(chosenPath) || newRepo.toString().equals(chosenPath)
        );

        Map<String, String> secondRead =
                service.loadMappingsForUse(mappingFile, "A1", root, tmp.resolve("app-data"));
        assertEquals(mappings, secondRead);
    }

    @Test
    public void loadMappingsForUse_reconstruction_duplicatePackageWithinContainer_returnsSingleWinner(
            @TempDir Path tmp
    ) throws Exception {
        GradingMappingsService service = new GradingMappingsService(_ -> {
            // no-op
        });
        Path root = tmp.resolve("root-duplicate-within-container");
        Files.createDirectories(root);

        Path submissionsContainer = root.resolve("submissions");
        Path repoA = submissionsContainer.resolve("repo-a");
        Path repoB = submissionsContainer.resolve("repo-b");
        Files.createDirectories(repoA);
        Files.createDirectories(repoB);
        Files.writeString(repoA.resolve("A1pkgShared.html"), "a");
        Files.writeString(repoB.resolve("A1pkgShared.html"), "b");

        Path mappingFile = tmp.resolve("duplicate-within-container-mappings.json");
        Map<String, String> mappings =
                service.loadMappingsForUse(mappingFile, "A1", root, tmp.resolve("app-data"));

        assertEquals(1, mappings.size());
        String chosenPath = mappings.get("pkgShared");
        assertTrue(
                repoA.toString().equals(chosenPath) || repoB.toString().equals(chosenPath)
        );

        Map<String, String> secondRead =
                service.loadMappingsForUse(mappingFile, "A1", root, tmp.resolve("app-data"));
        assertEquals(mappings, secondRead);
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

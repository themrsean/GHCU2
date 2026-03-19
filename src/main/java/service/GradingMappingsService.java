/*
 * Course: CSC-1120
 * GitHub Classroom Utilities
 */
package service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class GradingMappingsService {
    private static final long UNSET_TIMESTAMP = Long.MIN_VALUE;

    private final Consumer<String> statusConsumer;
    private final Map<String, String> repoPathByStudentPackage = new LinkedHashMap<>();
    private String cachedMappingContextKey = "";
    private long cachedMappingFileLastModified = UNSET_TIMESTAMP;

    public GradingMappingsService(Consumer<String> statusConsumer) {
        this.statusConsumer = statusConsumer == null ? msg -> {
            // do nothing
        } : statusConsumer;
    }

    public Path resolveMappingFile(Path mappingsPath,
                                   String assignmentId,
                                   Path appDataDir) {
        Path mappingFile = mappingsPath;

        if (mappingFile == null) {
            mappingFile = getAssignmentMappingsFile(assignmentId, appDataDir);
        }

        return mappingFile;
    }

    public Map<String, String> loadMappingsForUse(Path mappingsPath,
                                                  String assignmentId,
                                                  Path rootPath,
                                                  Path appDataDir) {
        String contextKey = buildMappingContextKey(
                mappingsPath,
                assignmentId,
                rootPath,
                appDataDir
        );
        if (!contextKey.equals(cachedMappingContextKey)) {
            repoPathByStudentPackage.clear();
            cachedMappingContextKey = contextKey;
            cachedMappingFileLastModified = UNSET_TIMESTAMP;
        }

        Path mappingFile = resolveMappingFile(mappingsPath, assignmentId, appDataDir);
        Path mappingsDir = mappingFile != null ? mappingFile.getParent() : null;

        if (hasMappingFileTimestampChanged(mappingFile)) {
            repoPathByStudentPackage.clear();
        }

        revalidateCachedMappings();
        if (!repoPathByStudentPackage.isEmpty()) {
            return new LinkedHashMap<>(repoPathByStudentPackage);
        }

        if (mappingFile == null || mappingsDir == null) {
            return Map.of();
        }

        if (Files.isRegularFile(mappingFile)) {
            Map<String, String> validMappings = readValidMappings(mappingFile);

            if (!validMappings.isEmpty()) {
                repoPathByStudentPackage.clear();
                repoPathByStudentPackage.putAll(validMappings);
            }

            boolean valid = mappingsFileHasValidRepoPaths(mappingFile);

            if (valid) {
                cachedMappingFileLastModified = readLastModifiedMillis(mappingFile);
                return new LinkedHashMap<>(repoPathByStudentPackage);
            }

            status("Existing mappings invalid on this machine. Reconstructing...");
        }

        Map<String, Map<String, String>> reconstructed =
                reconstructMappingsFromRoot(rootPath, assignmentId);

        if (reconstructed.isEmpty()) {
            if (repoPathByStudentPackage.isEmpty()) {
                status("No repositories found for reconstruction.");
            }
            return new LinkedHashMap<>(repoPathByStudentPackage);
        }

        try {
            Files.createDirectories(mappingsDir);

            Map<String, Map<String, String>> mergedMappings = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : repoPathByStudentPackage.entrySet()) {
                Map<String, String> repoEntry = new LinkedHashMap<>();
                repoEntry.put("repoPath", entry.getValue());
                mergedMappings.put(entry.getKey(), repoEntry);
            }
            mergedMappings.putAll(reconstructed);

            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(mappingFile.toFile(), mergedMappings);

            repoPathByStudentPackage.clear();
            repoPathByStudentPackage.putAll(flattenMappings(mergedMappings));
            cachedMappingFileLastModified = readLastModifiedMillis(mappingFile);

            status("Reconstructed mappings.json from root folder.");

            return new LinkedHashMap<>(repoPathByStudentPackage);

        } catch (IOException e) {
            status("Failed to write reconstructed mappings.json: " + e.getMessage());
            return new LinkedHashMap<>(repoPathByStudentPackage);
        }
    }

    private boolean hasMappingFileTimestampChanged(Path mappingFile) {
        if (cachedMappingFileLastModified == UNSET_TIMESTAMP) {
            cachedMappingFileLastModified = readLastModifiedMillis(mappingFile);
            return false;
        }

        long currentLastModified = readLastModifiedMillis(mappingFile);
        if (currentLastModified != cachedMappingFileLastModified) {
            cachedMappingFileLastModified = currentLastModified;
            return true;
        }

        return false;
    }

    private String buildMappingContextKey(Path mappingsPath,
                                          String assignmentId,
                                          Path rootPath,
                                          Path appDataDir) {
        Path mappingFile = resolveMappingFile(mappingsPath, assignmentId, appDataDir);
        String mappingFilePart = mappingFile == null
                ? "null"
                : mappingFile.toAbsolutePath().normalize().toString();
        String rootPart = rootPath == null
                ? "null"
                : rootPath.toAbsolutePath().normalize().toString();
        String assignmentPart = assignmentId == null ? "" : assignmentId;

        return mappingFilePart + "|" + assignmentPart + "|" + rootPart;
    }

    private void revalidateCachedMappings() {
        if (repoPathByStudentPackage.isEmpty()) {
            return;
        }

        Map<String, String> valid = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : repoPathByStudentPackage.entrySet()) {
            String studentPackage = entry.getKey();
            String repoPath = entry.getValue();

            if (studentPackage == null || studentPackage.isBlank()) {
                continue;
            }
            if (repoPath == null || repoPath.isBlank()) {
                continue;
            }

            Path repoDir = Path.of(repoPath);
            if (Files.isDirectory(repoDir)) {
                valid.put(studentPackage, repoDir.toString());
            }
        }

        repoPathByStudentPackage.clear();
        repoPathByStudentPackage.putAll(valid);
    }

    private long readLastModifiedMillis(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return -1L;
        }

        try {
            FileTime lastModified = Files.getLastModifiedTime(file);
            return lastModified.toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    public Path findRepoDirForStudentPackage(String studentPackage,
                                             Path mappingsPath,
                                             String assignmentId,
                                             Path rootPath,
                                             Path appDataDir) {
        Map<String, String> mapping =
                loadMappingsForUse(mappingsPath, assignmentId, rootPath, appDataDir);

        if (mapping.isEmpty()) {
            return null;
        }

        String repoPath = mapping.get(studentPackage);
        if (repoPath == null || repoPath.isBlank()) {
            return null;
        }

        Path repoDir = Path.of(repoPath);

        if (!Files.isDirectory(repoDir)) {
            return null;
        }

        return repoDir;
    }

    private Path getAssignmentMappingsFile(String assignmentId,
                                           Path appDataDir) {
        if (assignmentId == null || assignmentId.isBlank()) {
            return null;
        }

        Path mappingsDir = appDataDir.resolve("mappings");

        final String fileNamePrefix = "mappings-";
        final String extension = ".json";

        String fileName = fileNamePrefix + assignmentId + extension;

        return mappingsDir.resolve(fileName);
    }

    private boolean mappingsFileHasValidRepoPaths(Path mappingFile) {
        if (mappingFile == null || !Files.isRegularFile(mappingFile)) {
            return false;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();

            Map<String, Map<String, String>> mapping =
                    mapper.readValue(
                            Files.readAllBytes(mappingFile),
                            new TypeReference<>() {
                            }
                    );

            if (mapping == null || mapping.isEmpty()) {
                return false;
            }

            return flattenMappings(mapping).size() == mapping.size();

        } catch (IOException e) {
            return false;
        }
    }

    private Map<String, String> readValidMappings(Path mappingFile) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Map<String, String>> mapping =
                    mapper.readValue(
                            Files.readAllBytes(mappingFile),
                            new TypeReference<>() {
                            }
                    );

            return flattenMappings(mapping);
        } catch (IOException e) {
            return Map.of();
        }
    }

    private Map<String, String> flattenMappings(Map<String, Map<String, String>> mapping) {
        Map<String, String> flattened = new LinkedHashMap<>();

        if (mapping == null || mapping.isEmpty()) {
            return flattened;
        }

        for (Map.Entry<String, Map<String, String>> entry : mapping.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }

            Map<String, String> repoEntry = entry.getValue();
            if (repoEntry == null) {
                continue;
            }

            String repoPath = repoEntry.get("repoPath");
            if (repoPath == null || repoPath.isBlank()) {
                continue;
            }

            Path repoDir = Path.of(repoPath);
            if (!Files.isDirectory(repoDir)) {
                continue;
            }

            flattened.put(entry.getKey(), repoDir.toString());
        }

        return flattened;
    }

    private Map<String, Map<String, String>> reconstructMappingsFromRoot(Path rootPath,
                                                                         String assignmentId) {
        status("Root path = " + rootPath);
        if (rootPath == null) {
            status("Root path is NULL");
        }
        Map<String, Map<String, String>> mapping = new LinkedHashMap<>();

        if (rootPath == null || !Files.isDirectory(rootPath)) {
            return mapping;
        }

        Path reposContainer = null;

        try (var stream = Files.list(rootPath)) {

            for (Path child : stream.toList()) {

                if (!Files.isDirectory(child)) {
                    continue;
                }

                if (child.getFileName().toString().equalsIgnoreCase("packages")) {
                    continue;
                }

                boolean containsAssignmentReports = false;

                try (var sub = Files.list(child)) {
                    for (Path maybeRepo : sub.toList()) {

                        if (!Files.isDirectory(maybeRepo)) {
                            continue;
                        }

                        try (var files = Files.list(maybeRepo)) {
                            for (Path f : files.toList()) {

                                String name = f.getFileName().toString();

                                if (name.startsWith(assignmentId)
                                        && name.endsWith(".html")) {
                                    containsAssignmentReports = true;
                                }
                            }
                        }
                    }
                }

                if (containsAssignmentReports) {
                    reposContainer = child;
                }
            }

        } catch (IOException e) {
            status("Failed scanning root: " + e.getMessage());
            return mapping;
        }

        if (reposContainer == null || !Files.isDirectory(reposContainer)) {
            return mapping;
        }

        try (var repos = Files.list(reposContainer)) {

            for (Path repoDir : repos.toList()) {

                if (!Files.isDirectory(repoDir)) {
                    continue;
                }

                try (var files = Files.list(repoDir)) {

                    for (Path file : files.toList()) {

                        String fileName = file.getFileName().toString();

                        if (fileName.startsWith(assignmentId)
                                && fileName.endsWith(".html")) {

                            String studentPackage =
                                    fileName.substring(
                                            assignmentId.length(),
                                            fileName.length() - ".html".length()
                                    );

                            Map<String, String> entry = new LinkedHashMap<>();
                            entry.put("repoPath", repoDir.toAbsolutePath().toString());

                            mapping.put(studentPackage, entry);
                        }
                    }

                }
            }

        } catch (IOException e) {
            status("Failed scanning repos: " + e.getMessage());
        }
        status("Reconstructed mapping count: " + mapping.size());
        return mapping;
    }

    private void status(String msg) {
        statusConsumer.accept(msg);
    }
}

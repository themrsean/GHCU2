/*
 * Course: CSC-1120
 * GitHub Classroom Utilities
 */
package service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import model.RepoMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

public class MappingService {

    private final ServiceLogger logger;

    public MappingService(ServiceLogger logger) {
        this.logger = Objects.requireNonNull(logger);
    }

    public void saveMapping(Path mappingFile, Map<String, RepoMapping> mapping) {
        Objects.requireNonNull(mappingFile);
        Objects.requireNonNull(mapping);

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();

        try {
            String json = writer.writeValueAsString(mapping);
            Files.writeString(mappingFile, json);
            logger.log("Wrote mapping file: " + mappingFile);
        } catch (JsonProcessingException e) {
            logger.log("Failed to serialize mappings.json: " + e.getMessage());
        } catch (IOException e) {
            logger.log("Failed to write mappings.json: " + e.getMessage());
        }
    }

    public Map<String, RepoMapping> loadMapping(Path mappingFile) {
        Objects.requireNonNull(mappingFile);

        Map<String, RepoMapping> mapping = null;

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        try {
            byte[] bytes = Files.readAllBytes(mappingFile);
            TypeReference<Map<String, RepoMapping>> type = new TypeReference<>() {
            };
            mapping = mapper.readValue(bytes, type);
        } catch (IOException e) {
            logger.log("Failed to read mappings.json: " + e.getMessage());
            return Map.of();
        }

        return mapping;
    }

    public void extractPackages(Path selectedRootPath, Path mappingsPath) throws IOException {
        Objects.requireNonNull(selectedRootPath);
        Objects.requireNonNull(mappingsPath);

        Path scanRoot = selectedRootPath;

        List<Path> topDirs = listImmediateDirectories(selectedRootPath);
        Path submissionsDir = null;

        for (Path d : topDirs) {
            String name = d.getFileName().toString().toLowerCase();
            if (Files.isDirectory(d) && name.endsWith("-submissions")) {
                submissionsDir = d;
            }
        }

        if (submissionsDir != null) {
            scanRoot = submissionsDir;
        }

        Path packagesRoot = selectedRootPath.resolve("packages");
        ensureDirectoryExists(packagesRoot);

        Map<String, RepoMapping> mapping = new TreeMap<>();

        List<Path> repoDirs = listImmediateDirectories(scanRoot);
        if (repoDirs.isEmpty()) {
            throw new IOException("No repositories found under: " + scanRoot);
        }

        logger.log("Scanning repos under: " + scanRoot);
        logger.log("Found " + repoDirs.size() + " repository folder(s).");

        for (Path repoDir : repoDirs) {
            String repoFolderName = repoDir.getFileName().toString();
            if (repoFolderName.equalsIgnoreCase("packages")) {
                continue;
            }

            Path gitDir = repoDir.resolve(".git");
            if (!Files.exists(gitDir) || !Files.isDirectory(gitDir)) {
                logger.log("SKIP repo " + repoFolderName + ": not a git repository.");
                continue;
            }

            Path studentPackageDir = findStudentPackageDir(repoDir);
            if (studentPackageDir == null) {
                logger.log("SKIP repo " + repoFolderName
                        + ": could not locate src/{studentPackage}.");
                continue;
            }

            String packageName = studentPackageDir.getFileName().toString();
            Path dest = packagesRoot.resolve(packageName);

            boolean copied = copyPackageFolder(
                    studentPackageDir,
                    dest,
                    repoFolderName,
                    packageName
            );

            if (copied) {
                RepoMapping m = new RepoMapping();
                m.setRepoPath(repoDir.toAbsolutePath().toString());
                mapping.put(packageName, m);
            }
        }

        saveMapping(mappingsPath, mapping);
        logger.log("Wrote " + mapping.size() + " mapping(s).");
    }

    public Path resolveRepoRoot(Path mappedRepoPath) {
        if (mappedRepoPath == null) {
            return null;
        }

        Path directSrc = mappedRepoPath.resolve("src");
        if (Files.isDirectory(directSrc)) {
            return mappedRepoPath;
        }

        try (Stream<Path> stream = Files.list(mappedRepoPath)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.resolve("src"))
                    .filter(Files::isDirectory)
                    .map(Path::getParent)
                    .findFirst()
                    .orElse(mappedRepoPath);
        } catch (IOException ignored) {
            return mappedRepoPath;
        }
    }

    private void ensureDirectoryExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    private List<Path> listImmediateDirectories(Path root) {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        } catch (IOException e) {
            logger.log("Failed to list repository root: " + e.getMessage());
        }
        return dirs;
    }

    private Path findStudentPackageDir(Path repoDir) {
        Path src = repoDir.resolve("src");
        if (!Files.isDirectory(src)) {
            return null;
        }

        List<Path> candidates = new ArrayList<>();

        try (Stream<Path> stream = Files.list(src)) {
            stream.filter(Files::isDirectory).forEach(candidates::add);
        } catch (IOException e) {
            logger.log("Failed to read src folder for " + repoDir.getFileName() + ": "
                    + e.getMessage());
            return null;
        }

        candidates.removeIf(p -> p.getFileName().toString().equalsIgnoreCase("test"));
        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparing(p -> p.getFileName().toString()));

        final int warnThreshold = 1;
        if (candidates.size() > warnThreshold) {
            logger.log("WARN " + repoDir.getFileName()
                    + ": multiple non-test packages under src/; using "
                    + candidates.getFirst().getFileName());
        }

        return candidates.getFirst();
    }

    private boolean copyPackageFolder(Path sourcePackageDir,
                                      Path destPackageDir,
                                      String repoName,
                                      String studentPackage) {

        final class CopyStatus {
            private boolean ok = true;
        }

        CopyStatus status = new CopyStatus();

        if (sourcePackageDir == null) {
            logger.log("SKIP " + repoName + ": sourcePackageDir is null.");
            status.ok = false;
        } else if (destPackageDir == null) {
            logger.log("SKIP " + repoName + ": destPackageDir is null.");
            status.ok = false;
        } else if (!Files.exists(sourcePackageDir) || !Files.isDirectory(sourcePackageDir)) {
            logger.log("SKIP " + repoName + ": source package folder missing: " + sourcePackageDir);
            status.ok = false;
        }

        if (status.ok && Files.exists(destPackageDir)) {
            boolean deleted = deleteDirectoryRecursively(destPackageDir);
            if (!deleted) {
                logger.log("SKIP " + repoName
                        + ": cannot overwrite existing package folder: "
                        + destPackageDir);
                status.ok = false;
            }
        }

        if (status.ok) {
            try {
                Files.createDirectories(destPackageDir);
            } catch (IOException e) {
                logger.log("SKIP " + repoName
                        + ": failed to create destination folder: "
                        + e.getMessage());
                status.ok = false;
            }
        }

        if (status.ok) {
            try {
                Files.walkFileTree(sourcePackageDir, new java.nio.file.SimpleFileVisitor<>() {

                    @Override
                    public java.nio.file.FileVisitResult preVisitDirectory(
                            Path dir,
                            java.nio.file.attribute.BasicFileAttributes attrs) {

                        Path rel = sourcePackageDir.relativize(dir);
                        Path targetDir = destPackageDir.resolve(rel);

                        try {
                            Files.createDirectories(targetDir);
                        } catch (IOException e) {
                            logger.log("COPY ERROR " + repoName
                                    + ": failed to create directory " + targetDir
                                    + ": " + e.getMessage());
                            status.ok = false;
                        }

                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFile(
                            Path file,
                            java.nio.file.attribute.BasicFileAttributes attrs) {

                        Path rel = sourcePackageDir.relativize(file);
                        Path targetFile = destPackageDir.resolve(rel);

                        try {
                            Files.copy(
                                    file,
                                    targetFile,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                            );
                        } catch (IOException e) {
                            logger.log("COPY ERROR " + repoName
                                    + ": failed to copy file " + file
                                    + ": " + e.getMessage());
                            status.ok = false;
                        }

                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFileFailed(
                            Path file,
                            IOException exc) {

                        if (exc != null) {
                            logger.log("COPY ERROR " + repoName
                                    + ": failed accessing " + file
                                    + ": " + exc.getMessage());
                            status.ok = false;
                        }

                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });

            } catch (IOException e) {
                logger.log("SKIP " + repoName
                        + ": failed to walk package directory: "
                        + e.getMessage());
                status.ok = false;
            }
        }

        if (status.ok) {
            logger.log("OK package " + studentPackage
                    + " extracted from repo " + repoName);
        }

        return status.ok;
    }


    private boolean deleteDirectoryRecursively(Path dir) {

        final class DeleteStatus {
            private boolean ok = true;
        }

        DeleteStatus status = new DeleteStatus();

        if (dir == null) {
            status.ok = false;
        } else if (!Files.exists(dir)) {
            status.ok = true;
        } else {
            try {
                Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {

                    @Override
                    public java.nio.file.FileVisitResult visitFile(
                            Path file,
                            java.nio.file.attribute.BasicFileAttributes attrs) {

                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            logger.log("Failed to delete file " + file + ": " + e.getMessage());
                            status.ok = false;
                        }

                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult postVisitDirectory(
                            Path directory,
                            IOException exc) {

                        if (exc != null) {
                            logger.log("Failed walking directory " + directory + ": " + exc.getMessage());
                            status.ok = false;
                        }

                        try {
                            Files.deleteIfExists(directory);
                        } catch (IOException e) {
                            logger.log("Failed to delete directory " + directory + ": " + e.getMessage());
                            status.ok = false;
                        }

                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFileFailed(
                            Path file,
                            IOException exc) {

                        if (exc != null) {
                            logger.log("Failed accessing " + file + ": " + exc.getMessage());
                            status.ok = false;
                        }

                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                logger.log("Failed to delete directory tree: " + e.getMessage());
                status.ok = false;
            }
        }

        return status.ok;
    }

}
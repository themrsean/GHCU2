/*
 * Course: CSC-1110/1120
 * GitHub Classroom Utilities
 */
package service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

public class ToolArtifactService {

    private final Path appDataRoot;

    public ToolArtifactService(Path appDataRoot) {
        this.appDataRoot = Objects.requireNonNull(appDataRoot);
    }

    public Path checkstyleCacheRoot() throws IOException {
        Path cacheRoot = appDataRoot
                .resolve("tool-artifacts")
                .resolve("checkstyle-cache");
        Files.createDirectories(cacheRoot);
        return cacheRoot;
    }

    public Path createRunArtifactsRoot() throws IOException {
        Path runsRoot = appDataRoot
                .resolve("tool-artifacts")
                .resolve("runs");
        Files.createDirectories(runsRoot);
        return Files.createTempDirectory(runsRoot, "run-");
    }

    public Path repoArtifactsRoot(Path runArtifactsRoot,
                                  Path repoPath) throws IOException {
        Objects.requireNonNull(runArtifactsRoot);
        Objects.requireNonNull(repoPath);

        String repoName = repoPath.getFileName() == null
                ? "repo"
                : repoPath.getFileName().toString();

        String repoId = Integer.toHexString(repoPath.toAbsolutePath().normalize().hashCode());
        String folderName = repoName + "-" + repoId;
        Path repoArtifactsRoot = runArtifactsRoot.resolve(folderName);
        Files.createDirectories(repoArtifactsRoot);
        return repoArtifactsRoot;
    }

    public void cleanupTree(Path root,
                            ServiceLogger logger) {
        if (root == null || !Files.exists(root)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            stream
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            if (logger != null) {
                                logger.log("Failed to delete artifact path "
                                        + path
                                        + ": "
                                        + e.getMessage());
                            }
                        }
                    });
        } catch (IOException e) {
            if (logger != null) {
                logger.log("Failed cleaning artifact tree "
                        + root
                        + ": "
                        + e.getMessage());
            }
        }
    }
}

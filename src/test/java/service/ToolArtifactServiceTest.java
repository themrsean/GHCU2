package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ToolArtifactServiceTest {

    @Test
    public void createRunAndRepoArtifactRoots_thenCleanup_removesArtifacts(@TempDir Path tempDir)
            throws Exception {
        ToolArtifactService service = new ToolArtifactService(tempDir);

        Path runRoot = service.createRunArtifactsRoot();
        assertTrue(Files.isDirectory(runRoot));

        Path repoPath = tempDir.resolve("repo-a");
        Files.createDirectories(repoPath);

        Path repoArtifacts = service.repoArtifactsRoot(runRoot, repoPath);
        assertTrue(Files.isDirectory(repoArtifacts));

        Path file = repoArtifacts.resolve("artifact.txt");
        Files.writeString(file, "x");
        assertTrue(Files.exists(file));

        service.cleanupTree(runRoot, msg -> { });

        assertFalse(Files.exists(runRoot));
    }

    @Test
    public void checkstyleCacheRoot_createsStableDirectory(@TempDir Path tempDir) throws Exception {
        ToolArtifactService service = new ToolArtifactService(tempDir);

        Path first = service.checkstyleCacheRoot();
        Path second = service.checkstyleCacheRoot();

        assertEquals(first, second);
        assertTrue(Files.isDirectory(first));
    }
}

package model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for model.RepoMapping
 *
 * Verified targets (from src/main/java/model/RepoMapping.java):
 * - public String getRepoPath()
 * - public void setRepoPath(String repoPath)
 */
final class RepoMappingTest {

    @Test
    void defaultRepoPathIsNull() {
        RepoMapping m = new RepoMapping();
        assertNull(m.getRepoPath());
    }

    @Test
    void setAndGetRepoPath() {
        RepoMapping m = new RepoMapping();
        m.setRepoPath("studentA/repo");
        assertEquals("studentA/repo", m.getRepoPath());
    }

    @Test
    void overwriteRepoPath() {
        RepoMapping m = new RepoMapping();
        m.setRepoPath("first");
        m.setRepoPath("second");
        assertEquals("second", m.getRepoPath());
    }

    @Test
    void allowNullRepoPathAfterSet() {
        RepoMapping m = new RepoMapping();
        m.setRepoPath("x");
        m.setRepoPath(null);
        assertNull(m.getRepoPath());
    }

    @Test
    void independentInstancesDoNotShareState() {
        RepoMapping a = new RepoMapping();
        RepoMapping b = new RepoMapping();
        a.setRepoPath("p");
        assertNull(b.getRepoPath());
        b.setRepoPath("p");
        assertNotSame(a, b);
        assertEquals(a.getRepoPath(), b.getRepoPath());
    }
}

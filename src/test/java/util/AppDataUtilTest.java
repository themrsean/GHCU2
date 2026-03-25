package util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for util.AppDataUtil
 *
 * Verified target (from src/main/java/util/AppDataUtil.java):
 * - public static Path appDataDir()
 */
final class AppDataUtilTest {

    @Test
    void appDataDir_usesHomeDotGhClassroomUtils_onMac() {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", "/Users/testhome");

            Path p = AppDataUtil.appDataDir();

            assertNotNull(p);
            assertEquals(Path.of("/Users/testhome", ".gh-classroom-utils"), p);
        } finally {
            if (origHome != null) {
                System.setProperty("user.home", origHome);
            } else {
                System.clearProperty("user.home");
            }
        }
    }

    @Test
    void appDataDir_usesHomeDotGhClassroomUtils_onLinux() {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", "/home/testuser");

            Path p = AppDataUtil.appDataDir();
            assertNotNull(p);
            assertEquals(Path.of("/home/testuser", ".gh-classroom-utils"), p);
        } finally {
            if (origHome != null) {
                System.setProperty("user.home", origHome);
            } else {
                System.clearProperty("user.home");
            }
        }
    }

    @Test
    void appDataDir_usesHomeDotGhClassroomUtils_onWindows() {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", "C:\\Users\\tester");

            Path p = AppDataUtil.appDataDir();
            assertNotNull(p);
            assertEquals(Path.of("C:\\Users\\tester", ".gh-classroom-utils"), p);
        } finally {
            if (origHome != null) {
                System.setProperty("user.home", origHome);
            } else {
                System.clearProperty("user.home");
            }
        }
    }

    @Test
    void legacyGhcu2AppDataDir_usesPlatformLocation() {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("user.home", "/Users/testhome");

            Path p = AppDataUtil.legacyGhcu2AppDataDir();
            assertEquals(Path.of("/Users/testhome", "Library", "Application Support", "GHCU2"), p);
        } finally {
            if (origOs != null) {
                System.setProperty("os.name", origOs);
            } else {
                System.clearProperty("os.name");
            }
            if (origHome != null) {
                System.setProperty("user.home", origHome);
            } else {
                System.clearProperty("user.home");
            }
        }
    }
}

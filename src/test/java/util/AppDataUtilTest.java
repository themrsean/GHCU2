package util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for util.AppDataUtil
 *
 * Verified target (from src/main/java/util/AppDataUtil.java):
 * - private static Path appDataDir()
 */
final class AppDataUtilTest {

    private Path invokeAppDataDir() throws Exception {
        Method m = AppDataUtil.class.getDeclaredMethod("appDataDir");
        m.setAccessible(true);
        return (Path) m.invoke(null);
    }

    @Test
    void macOS_usesLibraryApplicationSupport() throws Exception {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("user.home", "/Users/testhome");

            Path p = invokeAppDataDir();

            assertNotNull(p);
            assertTrue(p.endsWith(Path.of("Library", "Application Support", "GHCU2")),
                    "Mac path should end with Library/Application Support/GHCU2 -> " + p);
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

    @Test
    void linuxOrOther_usesDotLocalShare() throws Exception {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("user.home", "/home/testuser");

            Path p = invokeAppDataDir();
            assertNotNull(p);
            assertTrue(p.endsWith(Path.of(".local", "share", "GHCU2")),
                    "Linux path should end with .local/share/GHCU2 -> " + p);
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

    @Test
    void windows_usesAppDataEnvWhenPresent_orFallbackToHomeAppDataRoaming() throws Exception {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Windows 10");
            // set a custom home; if APPDATA environment variable is set in the environment
            // the method should prefer APPDATA; otherwise it should fall back to home/AppData/Roaming
            System.setProperty("user.home", "C:\\Users\\tester");

            String appDataEnv = System.getenv("APPDATA");

            Path p = invokeAppDataDir();
            assertNotNull(p);

            if (appDataEnv != null && !appDataEnv.isBlank()) {
                assertEquals(Path.of(appDataEnv).resolve("GHCU2"), p,
                        () -> "When APPDATA is set the app data dir should be APPDATA/GHCU2: APPDATA=" + appDataEnv + " got=" + p);
            } else {
                assertEquals(Path.of("C:\\Users\\tester", "AppData", "Roaming", "GHCU2"), p,
                        () -> "When APPDATA is not set the app data dir should be user.home/AppData/Roaming/GHCU2 -> " + p);
            }
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

/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package persistence;

import model.Settings;
import org.junit.jupiter.api.Test;
import persistence.SettingsStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class SettingsStoreTest {

    private static final int EXPECTED_SCHEMA_VERSION = 1;
    private static final String EXPECTED_CHECKSTYLE_URL = "https://csse.msoe.us/csc1110/MSOE_checkStyle.xml";
    private static final String INVALID_JSON = "{ this is not valid JSON }";
    private static final String SETTINGS_FILENAME = "settings.json";

    @Test
    void load_whenFileDoesNotExist_returnsDefaultSettings() throws Exception {
        final SettingsStore store = new SettingsStore();

        final Path tempDir = Files.createTempDirectory("ghcu2v2-settingsstore");
        final Path missingFile = tempDir.resolve("does-not-exist.json");

        final Settings settings = store.load(missingFile);

        assertNotNull(settings);
        assertEquals(EXPECTED_SCHEMA_VERSION, settings.getSchemaVersion());
        assertEquals(EXPECTED_CHECKSTYLE_URL, settings.getCheckstyleConfigUrl());
    }

    @Test
    void save_thenLoad_roundTripsValuesAndCreatesParentDirectories() throws Exception {
        final SettingsStore store = new SettingsStore();

        final Path tempDir = Files.createTempDirectory("ghcu2v2-settingsstore");
        final Path nestedDir = tempDir.resolve("nested").resolve("config");
        final Path settingsPath = nestedDir.resolve(SETTINGS_FILENAME);

        final Settings original = new Settings();
        original.setSchemaVersion(EXPECTED_SCHEMA_VERSION);
        original.setCheckstyleConfigUrl(EXPECTED_CHECKSTYLE_URL);

        store.save(settingsPath, original);

        assertTrue(Files.exists(nestedDir));
        assertTrue(Files.exists(settingsPath));

        final Settings loaded = store.load(settingsPath);

        assertNotNull(loaded);
        assertEquals(original.getSchemaVersion(), loaded.getSchemaVersion());
        assertEquals(original.getCheckstyleConfigUrl(), loaded.getCheckstyleConfigUrl());
    }

    @Test
    void load_whenJsonIsInvalid_throwsIOException() throws Exception {
        final SettingsStore store = new SettingsStore();

        final Path tempDir = Files.createTempDirectory("ghcu2v2-settingsstore");
        final Path settingsPath = tempDir.resolve(SETTINGS_FILENAME);

        Files.writeString(settingsPath, INVALID_JSON);

        final IOException ex = assertThrows(IOException.class, () -> store.load(settingsPath));
        assertTrue(ex.getMessage().contains("Invalid settings JSON format."));
    }
}

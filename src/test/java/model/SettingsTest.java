package model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for model.Settings
 *
 * Verified targets (from src/main/java/model/Settings.java):
 * - public int getSchemaVersion()
 * - public void setSchemaVersion(int schemaVersion)
 * - public String getCheckstyleConfigUrl()
 * - public void setCheckstyleConfigUrl(String checkstyleConfigUrl)
 */
final class SettingsTest {

    @Test
    void defaults_areZeroAndNull() {
        Settings s = new Settings();
        assertEquals(0, s.getSchemaVersion());
        assertNull(s.getCheckstyleConfigUrl());
    }

    @Test
    void settersAndGetters_roundtrip() {
        Settings s = new Settings();
        s.setSchemaVersion(2);
        s.setCheckstyleConfigUrl("http://example.com/checkstyle.xml");

        assertEquals(2, s.getSchemaVersion());
        assertEquals("http://example.com/checkstyle.xml", s.getCheckstyleConfigUrl());
    }
}

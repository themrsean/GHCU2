package persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import model.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsStore {
    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    public SettingsStore() {
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        writer = mapper.writerWithDefaultPrettyPrinter();
    }

    public Settings load(Path filePath) throws IOException {
        Settings settings;
        if (!Files.exists(filePath)) {
            settings = defaultSettings();
        } else {
            byte[] bytes = Files.readAllBytes(filePath);
            try {
                settings = mapper.readValue(bytes, Settings.class);
            } catch (JsonProcessingException e) {
                throw new IOException("Invalid settings JSON format.", e);
            }
            if (settings == null) {
                settings = defaultSettings();
            }
        }
        return settings;
    }

    public void save(Path filePath, Settings settings) throws IOException {
        Path parent = filePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        String json;
        try {
            json = writer.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to serialize settings.", e);
        }

        Files.writeString(filePath, json);
    }

    private Settings defaultSettings() {
        Settings s = new Settings();
        s.setSchemaVersion(1);
        s.setCheckstyleConfigUrl("https://csse.msoe.us/csc1110/MSOE_checkStyle.xml");
        return s;
    }
}

/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import model.AssignmentsFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and saves assignments.json.
 */
public class AssignmentsStore {
    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    public AssignmentsStore() {
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        writer = mapper.writerWithDefaultPrettyPrinter();
    }

    /**
     * Loads assignments data from a JSON file.
     *
     * @param filePath path to the JSON file
     * @return loaded AssignmentsFile
     * @throws IOException if file cannot be read or parsed
     */
    public AssignmentsFile load(Path filePath) throws IOException {
        AssignmentsFile assignmentsFile;
        byte[] bytes = Files.readAllBytes(filePath);
        try {
            assignmentsFile = mapper.readValue(bytes, AssignmentsFile.class);
        } catch (JsonProcessingException e) {
            throw new IOException("Invalid assignments JSON format.", e);
        }
        return assignmentsFile;
    }

    public AssignmentsFile load(InputStream in) throws IOException {
        try {
            return mapper.readValue(in, AssignmentsFile.class);
        } catch (JsonProcessingException e) {
            throw new IOException("Invalid assignments JSON format.", e);
        }
    }

    /**
     * Saves assignments data to a JSON file (pretty-printed).
     *
     * @param filePath destination JSON file path
     * @param data     assignments data to save
     * @throws IOException if file cannot be written
     */
    public void save(Path filePath, AssignmentsFile data) throws IOException {
        Path parent = filePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        String json;
        try {
            json = writer.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to serialize assignments data.", e);
        }

        Files.writeString(filePath, json);
    }
}

package persistence;

import model.AssignmentsFile;
import model.Assignment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class AssignmentsStoreTest {
    @Test
    void load_whenFileDoesNotExist_throwsIOException(@TempDir Path tempDir) {
        final AssignmentsStore store = new AssignmentsStore();
        final Path missing = tempDir.resolve("does-not-exist.json");
        assertThrows(IOException.class, () -> store.load(missing));
    }

    @Test
    void save_thenLoad_createsParentDirectoriesAndRoundTripsValues(@TempDir Path tempDir) throws Exception {
        final AssignmentsStore store = new AssignmentsStore();
        final Path nestedDir = tempDir.resolve("nested").resolve("data");
        final Path filePath = nestedDir.resolve("assignments.json");
        final AssignmentsFile original = new AssignmentsFile();
        original.setSchemaVersion(1);
        final Assignment a = new Assignment();
        a.setCourseCode("CSC-1120");
        a.setAssignmentCode("A1");
        a.setAssignmentName("Test Assignment");
        a.setExpectedFiles(List.of("src/{studentPackage}/Main.java"));
        original.setAssignments(Collections.singletonList(a));
        // Should create parent directories and write file
        store.save(filePath, original);

        assertTrue(Files.exists(nestedDir), "Parent directory should have been created");
        assertTrue(Files.exists(filePath), "Assignments file should have been created");

        final String content = Files.readString(filePath);
        assertNotNull(content);
        assertTrue(content.contains("schemaVersion"), "Written JSON should contain schemaVersion");

        final AssignmentsFile loaded = store.load(filePath);
        assertNotNull(loaded);
        assertEquals(original.getSchemaVersion(), loaded.getSchemaVersion());
        assertNotNull(loaded.getAssignments());
        assertEquals(1, loaded.getAssignments().size());
        final Assignment loadedA = loaded.getAssignments().getFirst();
        assertEquals(a.getCourseCode(), loadedA.getCourseCode());
        assertEquals(a.getAssignmentCode(), loadedA.getAssignmentCode());
        assertEquals(a.getAssignmentName(), loadedA.getAssignmentName());
    }

    @Test
    void load_inputStream_ignoresUnknownProperties(@TempDir Path tempDir) throws Exception {
        final AssignmentsStore store = new AssignmentsStore();
        final String jsonWithExtra = """
                {
                  "schemaVersion": 1,
                  "someUnknownProperty": { "x": 5 },
                  "assignments": [ {
                    "courseCode": "CSC-1120",
                    "assignmentCode": "A2",
                    "assignmentName": "Extra Props"
                  } ]
                }""";
        try (ByteArrayInputStream in = new ByteArrayInputStream(jsonWithExtra.getBytes())) {
            final AssignmentsFile loaded = store.load(in);
            assertNotNull(loaded);
            assertEquals(1, loaded.getSchemaVersion());
            assertNotNull(loaded.getAssignments());
            assertEquals(1, loaded.getAssignments().size());
            assertEquals("A2", loaded.getAssignments().getFirst().getAssignmentCode());
        }
    }
}
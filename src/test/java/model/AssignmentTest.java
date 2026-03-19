package model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class AssignmentTest {

    @Test
    void gettersSettersKeyAndToString_roundTripValues() {
        Assignment a = new Assignment();
        a.setCourseCode("CSC-1120");
        a.setAssignmentCode("A1");
        a.setAssignmentName("Test Assignment");
        a.setExpectedFiles(List.of("src/{studentPackage}/Main.java"));

        Assignment.Rubric rubric = new Assignment.Rubric();
        RubricItemRef ref = new RubricItemRef();
        ref.setRubricItemId("ri_commits");
        ref.setPoints(10);
        rubric.setItems(List.of(ref));
        a.setRubric(rubric);

        assertEquals("CSC-1120", a.getCourseCode());
        assertEquals("A1", a.getAssignmentCode());
        assertEquals("Test Assignment", a.getAssignmentName());

        assertNotNull(a.getExpectedFiles());
        assertEquals(1, a.getExpectedFiles().size());
        assertEquals("src/{studentPackage}/Main.java", a.getExpectedFiles().getFirst());

        assertNotNull(a.getRubric());
        assertNotNull(a.getRubric().getItems());
        assertEquals(1, a.getRubric().getItems().size());
        RubricItemRef got = a.getRubric().getItems().getFirst();
        assertEquals("ri_commits", got.getRubricItemId());
        assertEquals(10, got.getPoints());

        assertEquals("CSC-1120-A1", a.getKey());
        assertEquals("CSC-1120 A1 - Test Assignment", a.toString());
    }

    @Test
    void nullFieldsProduceNullStrings() {
        Assignment a = new Assignment();
        // By default nothing is set
        assertNull(a.getCourseCode());
        assertNull(a.getAssignmentCode());
        assertNull(a.getAssignmentName());
        assertNull(a.getExpectedFiles());
        assertNull(a.getRubric());

        // getKey and toString will concatenate nulls as strings
        assertEquals("null-null", a.getKey());
        assertEquals("null null - null", a.toString());
    }

    @Test
    void rubricContainer_gettersAndSetters() {
        Assignment.Rubric r = new Assignment.Rubric();
        assertNull(r.getItems());
        RubricItemRef ref1 = new RubricItemRef();
        ref1.setRubricItemId("ri1");
        ref1.setPoints(5);
        r.setItems(List.of(ref1));
        assertNotNull(r.getItems());
        assertEquals(1, r.getItems().size());
        assertEquals("ri1", r.getItems().getFirst().getRubricItemId());
    }
}

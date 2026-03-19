package model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AppliedCommentTest {

    @Test
    void defaultValues_areNullOrZero() {
        AppliedComment ac = new AppliedComment();
        // Strings should be null by default
        assertNull(ac.getCommentId());
        assertNull(ac.getFilePath());
        assertNull(ac.getRubricItemId());
        assertNull(ac.getAssignmentKey());
        // ints should default to 0
        assertEquals(0, ac.getLineNumber());
        assertEquals(0, ac.getColumnNumber());
    }

    @Test
    void gettersSetters_roundTripValues() {
        AppliedComment ac = new AppliedComment();
        ac.setCommentId("c123");
        ac.setFilePath("src/example/Main.java");
        ac.setLineNumber(10);
        ac.setColumnNumber(5);
        ac.setRubricItemId("ri_style");
        ac.setAssignmentKey("CSC-1120-A1");

        assertEquals("c123", ac.getCommentId());
        assertEquals("src/example/Main.java", ac.getFilePath());
        assertEquals(10, ac.getLineNumber());
        assertEquals(5, ac.getColumnNumber());
        assertEquals("ri_style", ac.getRubricItemId());
        assertEquals("CSC-1120-A1", ac.getAssignmentKey());
    }

    @Test
    void numericFields_acceptEdgeValues() {
        AppliedComment ac = new AppliedComment();
        // column can be zero to indicate unused
        ac.setColumnNumber(0);
        assertEquals(0, ac.getColumnNumber());

        // negative values are not prevented by model; ensure round-trip
        ac.setLineNumber(-1);
        ac.setColumnNumber(-10);
        assertEquals(-1, ac.getLineNumber());
        assertEquals(-10, ac.getColumnNumber());
    }

    @Test
    void setters_acceptAndReturnNullForStringFields() {
        AppliedComment ac = new AppliedComment();

        // Ensure setting null values doesn't throw and round-trips as null
        ac.setCommentId(null);
        ac.setFilePath(null);
        ac.setRubricItemId(null);
        ac.setAssignmentKey(null);

        assertNull(ac.getCommentId());
        assertNull(ac.getFilePath());
        assertNull(ac.getRubricItemId());
        assertNull(ac.getAssignmentKey());
    }

    @Test
    void numericSetters_acceptIntegerBounds() {
        AppliedComment ac = new AppliedComment();

        ac.setLineNumber(Integer.MAX_VALUE);
        ac.setColumnNumber(Integer.MIN_VALUE);

        assertEquals(Integer.MAX_VALUE, ac.getLineNumber());
        assertEquals(Integer.MIN_VALUE, ac.getColumnNumber());
    }

    @Test
    void multipleInstances_areIndependent() {
        AppliedComment a1 = new AppliedComment();
        AppliedComment a2 = new AppliedComment();

        a1.setCommentId("c1");
        a1.setFilePath("path/One.java");
        a1.setLineNumber(1);
        a1.setColumnNumber(1);
        a1.setRubricItemId("r1");
        a1.setAssignmentKey("A1");

        a2.setCommentId("c2");
        a2.setFilePath("path/Two.java");
        a2.setLineNumber(2);
        a2.setColumnNumber(2);
        a2.setRubricItemId("r2");
        a2.setAssignmentKey("A2");

        // Verify values for a1
        assertEquals("c1", a1.getCommentId());
        assertEquals("path/One.java", a1.getFilePath());
        assertEquals(1, a1.getLineNumber());
        assertEquals(1, a1.getColumnNumber());
        assertEquals("r1", a1.getRubricItemId());
        assertEquals("A1", a1.getAssignmentKey());

        // Verify values for a2 remained distinct
        assertEquals("c2", a2.getCommentId());
        assertEquals("path/Two.java", a2.getFilePath());
        assertEquals(2, a2.getLineNumber());
        assertEquals(2, a2.getColumnNumber());
        assertEquals("r2", a2.getRubricItemId());
        assertEquals("A2", a2.getAssignmentKey());
    }

    @Test
    void setters_allowRepeatedMutations() {
        AppliedComment ac = new AppliedComment();

        ac.setCommentId("initial");
        assertEquals("initial", ac.getCommentId());

        ac.setCommentId("updated");
        assertEquals("updated", ac.getCommentId());

        ac.setLineNumber(100);
        assertEquals(100, ac.getLineNumber());

        ac.setLineNumber(200);
        assertEquals(200, ac.getLineNumber());
    }
}

package model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GradeReportTest {

    @Test
    void defaultValues_courseAndAssignmentAndStudentPackageAreNull_appliedCommentsEmptyList() {
        GradeReport gr = new GradeReport();

        // Strings should default to null
        assertNull(gr.getCourseCode());
        assertNull(gr.getAssignmentCode());
        assertNull(gr.getStudentPackage());

        // appliedComments should be non-null and empty by default (constructed in class)
        List<AppliedComment> acList = gr.getAppliedComments();
        assertNotNull(acList);
        assertTrue(acList.isEmpty());
    }

    @Test
    void gettersAndSetters_roundTripValues() {
        GradeReport gr = new GradeReport();
        gr.setCourseCode("CSC-1120");
        gr.setAssignmentCode("A1");
        gr.setStudentPackage("student123.zip");

        assertEquals("CSC-1120", gr.getCourseCode());
        assertEquals("A1", gr.getAssignmentCode());
        assertEquals("student123.zip", gr.getStudentPackage());
    }

    @Test
    void appliedComments_mutableViaGetter_and_setterReplacesReference() {
        GradeReport gr = new GradeReport();

        // Add via getter's returned list
        AppliedComment ac = new AppliedComment();
        ac.setCommentId("c1");
        gr.getAppliedComments().add(ac);

        assertEquals(1, gr.getAppliedComments().size());
        assertEquals("c1", gr.getAppliedComments().get(0).getCommentId());

        // Replace with a new list via setter and ensure reference equality
        List<AppliedComment> newList = new ArrayList<>();
        AppliedComment ac2 = new AppliedComment();
        ac2.setCommentId("c2");
        newList.add(ac2);

        gr.setAppliedComments(newList);
        assertSame(newList, gr.getAppliedComments());
        assertEquals(1, gr.getAppliedComments().size());
        assertEquals("c2", gr.getAppliedComments().get(0).getCommentId());
    }

    @Test
    void setAppliedComments_allowsNull_and_canBeReplaced() {
        GradeReport gr = new GradeReport();

        // set to null
        gr.setAppliedComments(null);
        assertNull(gr.getAppliedComments());

        // set back to a list
        List<AppliedComment> list = new ArrayList<>();
        gr.setAppliedComments(list);
        assertNotNull(gr.getAppliedComments());
        assertSame(list, gr.getAppliedComments());
    }

    @Test
    void multipleInstances_independentAppliedCommentLists() {
        GradeReport g1 = new GradeReport();
        GradeReport g2 = new GradeReport();

        AppliedComment a1 = new AppliedComment();
        a1.setCommentId("x");
        g1.getAppliedComments().add(a1);

        // g2 should remain empty
        assertNotNull(g2.getAppliedComments());
        assertTrue(g2.getAppliedComments().isEmpty());

        // Mutating a list passed into setter affects the GradeReport (reference)
        List<AppliedComment> shared = new ArrayList<>();
        AppliedComment s = new AppliedComment();
        s.setCommentId("shared");
        shared.add(s);

        g2.setAppliedComments(shared);
        assertSame(shared, g2.getAppliedComments());

        // Mutate original shared list and verify g2 sees change
        AppliedComment added = new AppliedComment();
        added.setCommentId("added");
        shared.add(added);
        assertEquals(2, g2.getAppliedComments().size());
    }
}

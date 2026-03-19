package service;

import model.Assignment;
import model.AssignmentsFile;
import model.Comments.ParsedComment;
import model.RubricItemDef;
import model.RubricItemRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GradingReportEditorServiceTest {

    @Test
    public void buildFreshReportSkeleton_includesMarkersAndFeedbackSection() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );

        String skeleton = service.buildFreshReportSkeleton("smith");

        assertTrue(skeleton.contains("<!-- RUBRIC_TABLE_BEGIN -->"));
        assertTrue(skeleton.contains("<!-- COMMENTS_SUMMARY_BEGIN -->"));
        assertTrue(skeleton.contains("> # Feedback"));
        assertTrue(skeleton.contains("_No report found for smith._"));
    }

    @Test
    public void ensurePatchSectionsExist_injectsMissingBlocksIntoHeadingMarkdown() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );
        String markdown = "# Intro A1\n\n> # Feedback\n> * Existing";

        String updated = service.ensurePatchSectionsExist(markdown, "smith");

        assertTrue(updated.contains("<!-- RUBRIC_TABLE_BEGIN -->"));
        assertTrue(updated.contains("<!-- COMMENTS_SUMMARY_BEGIN -->"));
        assertTrue(updated.contains("> # Feedback"));
        assertTrue(updated.contains("> * Existing"));
    }

    @Test
    public void rebuildRubricAndSummary_appliesParsedCommentToSummaryAndRubric() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );
        String initial = service.buildFreshReportSkeleton("smith");
        List<ParsedComment> comments = List.of(
                new ParsedComment("cmt_1", "ri_impl", 3, "Missing semicolon")
        );

        String updated = service.rebuildRubricAndSummary(initial, comments);
        String compact = updated.replaceAll(" +", " ");

        assertTrue(updated.contains("[Missing semicolon](#cmt_1) (-3 ri_impl)"));
        assertTrue(compact.contains(">> | 7 | 10 | Implementation"));
        assertTrue(compact.contains(">> | 7 | 10 | TOTAL"));
    }

    @Test
    public void rebuildRubricAndSummary_isIdempotentForUnchangedComments() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );
        String initial = service.buildFreshReportSkeleton("smith");
        List<ParsedComment> comments = List.of(
                new ParsedComment("cmt_1", "ri_impl", 3, "Missing semicolon")
        );

        String once = service.rebuildRubricAndSummary(initial, comments);
        String twice = service.rebuildRubricAndSummary(once, comments);
        String compactOnce = once.replaceAll(" +", " ");
        String compactTwice = twice.replaceAll(" +", " ");

        assertTrue(compactOnce.contains(">> | 7 | 10 | Implementation"));
        assertTrue(compactTwice.contains(">> | 7 | 10 | Implementation"));
        assertEquals(compactOnce, compactTwice);
    }

    @Test
    public void rebuildRubricAndSummary_ignoresEditedSummaryDeductionValuesWhenAnchorsMatch() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );
        List<ParsedComment> comments = List.of(
                new ParsedComment("cmt_1", "ri_impl", 3, "Missing semicolon")
        );
        String initial = service.buildFreshReportSkeleton("smith");
        String rebuilt = service.rebuildRubricAndSummary(initial, comments);
        String tampered = rebuilt.replace("(-3 ri_impl)", "(-9 ri_impl)");

        String updated = service.rebuildRubricAndSummary(tampered, comments);
        String compact = updated.replaceAll(" +", " ");

        assertTrue(compact.contains(">> | 7 | 10 | Implementation"));
        assertTrue(compact.contains(">> | 7 | 10 | TOTAL"));
    }

    private Assignment buildAssignment() {
        Assignment assignment = new Assignment();
        assignment.setAssignmentName("Intro A1");

        RubricItemRef ref = new RubricItemRef();
        ref.setRubricItemId("ri_impl");
        ref.setPoints(10);

        Assignment.Rubric rubric = new Assignment.Rubric();
        rubric.setItems(List.of(ref));
        assignment.setRubric(rubric);
        return assignment;
    }

    private AssignmentsFile buildAssignmentsFile() {
        RubricItemDef def = new RubricItemDef();
        def.setId("ri_impl");
        def.setName("Implementation");

        AssignmentsFile file = new AssignmentsFile();
        file.setRubricItemLibrary(Map.of("ri_impl", def));
        file.setAssignments(List.of());

        assertEquals("Implementation", file.getRubricItemLibrary().get("ri_impl").getName());
        return file;
    }
}

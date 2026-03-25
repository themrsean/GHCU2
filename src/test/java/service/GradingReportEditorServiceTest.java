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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    public void normalizeRubricAndSummaryBlocks_deduplicatesMarkers_evenWithMalformedBlocks() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );
        String markdown = """
                # Intro A1

                <!-- RUBRIC_TABLE_BEGIN -->
                >> | Earned | Possible | Criteria |
                >> | --- | --- | --- |
                >> | 10 | 10 | Implementation |
                >> | 10 | 10 | TOTAL |
                <!-- RUBRIC_TABLE_END -->

                <!-- RUBRIC_TABLE_BEGIN -->
                stale duplicate block with no end marker should be dropped

                <!-- COMMENTS_SUMMARY_BEGIN -->
                >> # Comments
                >> * stale summary line
                <!-- COMMENTS_SUMMARY_END -->

                <!-- COMMENTS_SUMMARY_BEGIN -->
                stale malformed summary begin only

                > # Feedback
                > * Keep this
                """;

        String normalized = service.normalizeRubricAndSummaryBlocks(markdown);

        assertEquals(1, countOccurrences(normalized, "<!-- RUBRIC_TABLE_BEGIN -->"));
        assertEquals(1, countOccurrences(normalized, "<!-- RUBRIC_TABLE_END -->"));
        assertEquals(1, countOccurrences(normalized, "<!-- COMMENTS_SUMMARY_BEGIN -->"));
        assertEquals(1, countOccurrences(normalized, "<!-- COMMENTS_SUMMARY_END -->"));
        assertTrue(normalized.contains("> # Feedback"));
        assertTrue(normalized.contains("> * Keep this"));
    }

    @Test
    public void rebuildRubricAndSummary_preservesRubricLikeTableOutsideManagedBlock() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );
        String markdown = """
                # Intro A1

                <!-- RUBRIC_TABLE_BEGIN -->
                >> | Earned | Possible | Criteria |
                >> | --- | --- | --- |
                >> | 10 | 10 | Implementation |
                >> | 10 | 10 | TOTAL |
                <!-- RUBRIC_TABLE_END -->

                <!-- COMMENTS_SUMMARY_BEGIN -->
                >> # Comments
                >> * _No comments._
                <!-- COMMENTS_SUMMARY_END -->

                ## Notes
                >> | Earned | Possible | Criteria |
                >> | --- | --- | --- |
                >> | 1 | 1 | This table is an instructor note |
                """;

        List<ParsedComment> comments = List.of(
                new ParsedComment("cmt_1", "ri_impl", 2, "Minor issue")
        );
        String updated = service.rebuildRubricAndSummary(markdown, comments);

        assertTrue(updated.contains("## Notes"));
        assertTrue(updated.contains("This table is an instructor note"));
        assertTrue(updated.contains("[Minor issue](#cmt_1) (-2 ri_impl)"));
    }

    @Test
    public void rebuildRubricAndSummary_escapesSpecialCharsInCommentTitleAndAnchorLink() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );
        String initial = service.buildFreshReportSkeleton("smith");
        List<ParsedComment> comments = List.of(
                new ParsedComment("cmt_x-1", "ri_impl", 1, "Array [index] (bounds)")
        );

        String updated = service.rebuildRubricAndSummary(initial, comments);

        assertTrue(updated.contains("[Array \\[index\\] \\(bounds\\)](#cmt_x-1) (-1 ri_impl)"));
        assertTrue(updated.contains(">> # Comments"));
    }

    @Test
    public void normalizeRubricAndSummaryBlocks_doesNotRewriteSourceOrFailedTestSections() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );

        String markdown = """
                # Lab Assignment 7 - Program Stack

                >> | Earned | Possible | Criteria |
                >> | ------ | -------- | -------- |
                >> |      8 |       10 | Implementation |
                >> |      8 |       10 | TOTAL |

                > # Feedback
                > * Nice work

                ## Source Code

                ```java
                String box = \"\"\"
                        |          |
                        |----------|
                        +----------+
                        \"\"\";
                ```

                ## Failed Unit Tests

                ```
                expected: <| | |----------| +----------+ > but was: <null>
                ```
                """;

        String normalized = service.normalizeRubricAndSummaryBlocks(markdown);

        int rubricIndex = normalized.indexOf("<!-- RUBRIC_TABLE_BEGIN -->");
        int sourceIndex = normalized.indexOf("## Source Code");
        int failedIndex = normalized.indexOf("## Failed Unit Tests");

        assertTrue(rubricIndex >= 0);
        assertTrue(sourceIndex > rubricIndex);
        assertTrue(failedIndex > sourceIndex);
        assertTrue(normalized.contains("|----------|"));
        assertTrue(normalized.contains("expected: <| | |----------| +----------+ > but was: <null>"));
    }

    @Test
    public void normalizeRubricAndSummaryBlocks_recoversWhenRawRubricIsMisplacedInBody() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );

        String markdown = """
                # Lab Assignment 7 - Program Stack

                ```java
                String headerAndFooter =
                        \"\"\"
                                |          |
                                |----------|
                                +----------+
                                \"\"\";
                ```

                >> | Earned | Possible | Criteria |
                >> | ------ | -------- | -------- |
                >> |      8 |       10 | Implementation |
                >> |      8 |       10 | TOTAL |

                ## Source Code

                ### ProgramStack.java

                ```java
                class ProgramStack {}
                ```
                """;

        String normalized = service.normalizeRubricAndSummaryBlocks(markdown);

        int rubricMarker = normalized.indexOf("<!-- RUBRIC_TABLE_BEGIN -->");
        int sourceHeading = normalized.indexOf("## Source Code");
        int rawRubricHeader = normalized.indexOf(">> | Earned | Possible | Criteria |");

        assertTrue(rubricMarker >= 0);
        assertTrue(sourceHeading > rubricMarker);
        assertTrue(rawRubricHeader > rubricMarker);
        assertTrue(rawRubricHeader < sourceHeading);
        assertTrue(normalized.contains("|----------|"));
    }

    @Test
    public void normalizeRubricAndSummaryBlocks_isIdempotentForLeadingSpacingNearRubric() {
        GradingReportEditorService service = new GradingReportEditorService(
                buildAssignment(),
                buildAssignmentsFile()
        );

        String markdown = """
                # Lab Assignment 7 - Program Stack




                >> | Earned | Possible | Criteria |
                >> | ------ | -------- | -------- |
                >> |      8 |       10 | Implementation |
                >> |      8 |       10 | TOTAL |

                > # Feedback
                > * Nice work

                ## Source Code

                ```java
                String box = \"\"\"
                        |          |
                        |----------|
                        +----------+
                        \"\"\";
                ```
                """;

        String once = service.normalizeRubricAndSummaryBlocks(markdown);
        String twice = service.normalizeRubricAndSummaryBlocks(once);
        String thrice = service.normalizeRubricAndSummaryBlocks(twice);

        assertEquals(twice, thrice);
    }

    private int countOccurrences(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while (index >= 0) {
            index = text.indexOf(token, index);
            if (index >= 0) {
                count++;
                index += token.length();
            }
        }
        return count;
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

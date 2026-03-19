package model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for model.RubricTableBuilder
 *
 * Verified targets (from src/main/java/model/RubricTableBuilder.java):
 * - public static String buildRubricMarkdown(Assignment assignment,
 *                                           AssignmentsFile assignmentsFile,
 *                                           Integer checkstyleViolationsOrNull,
 *                                           Double unitTestFailuresOrNull,
 *                                           Integer totalUnitTestsOrNull,
 *                                           Map<String, Integer> manualDeductionsByRubricItemId)
 */
final class RubricTableBuilderTest {

    // Helper to extract earned/possible/criteria from a line for a given criteria substring
    private static String[] extractRowValues(String markdown, String criteriaContains) {
        String[] lines = markdown.split(System.lineSeparator());
        for (String line : lines) {
            if (line.contains(criteriaContains)) {
                String[] cols = line.split("\\|");
                // expected format: [">> ", ' Earned ', ' Possible ', ' Criteria    ', ''] -> indices shift
                // After split: cols[1] -> earned (padded), cols[2] -> possible, cols[3] -> criteria
                if (cols.length >= 4) {
                    String earned = cols[1].trim();
                    String possible = cols[2].trim();
                    String criteria = cols[3].trim();
                    return new String[]{earned, possible, criteria};
                }
            }
        }
        return null;
    }

    @Test
    void nullAssignment_returnsNoRubricItemsRow() {
        String md = RubricTableBuilder.buildRubricMarkdown(null, null, null, null, null, null);
        assertNotNull(md);
        assertTrue(md.contains("_No rubric items._"), "Should indicate no rubric items: " + md);
        // There should be a data row with zeros
        assertTrue(md.contains(" 0 ") || md.contains("| 0 |"), "Should contain zeros for earned/possible -> " + md);
    }

    @Test
    void singleItem_basicEarnedEqualsPossible_andTotalRow() {
        Assignment a = new Assignment();
        Assignment.Rubric r = new Assignment.Rubric();
        RubricItemRef ref = new RubricItemRef();
        ref.setRubricItemId("ri_a");
        ref.setPoints(5);
        r.setItems(java.util.List.of(ref));
        a.setRubric(r);

        AssignmentsFile file = new AssignmentsFile();
        Map<String, RubricItemDef> lib = new HashMap<>();
        RubricItemDef def = new RubricItemDef();
        def.setName("Attendance");
        lib.put("ri_a", def);
        file.setRubricItemLibrary(lib);

        String md = RubricTableBuilder.buildRubricMarkdown(a, file, null, null, null, null);
        assertNotNull(md);
        // Item row
        String[] item = extractRowValues(md, "Attendance");
        assertNotNull(item);
        assertEquals("5", item[0]);
        assertEquals("5", item[1]);

        // Total row
        String[] total = extractRowValues(md, "TOTAL");
        assertNotNull(total);
        assertEquals("5", total[0]);
        assertEquals("5", total[1]);
    }

    @Test
    void checkstyleItem_cappingAndNegativeViolations() {
        Assignment a = new Assignment();
        Assignment.Rubric r = new Assignment.Rubric();
        RubricItemRef ref = new RubricItemRef();
        ref.setRubricItemId("ri_cs");
        ref.setPoints(4);
        r.setItems(java.util.List.of(ref));
        a.setRubric(r);

        RubricItemDef def = new RubricItemDef();
        def.setName("Style issues");
        def.setCheckstyleItem(true);

        AssignmentsFile file = new AssignmentsFile();
        Map<String, RubricItemDef> lib = new HashMap<>();
        lib.put("ri_cs", def);
        file.setRubricItemLibrary(lib);

        // 2 violations -> earned = 2
        String md2 = RubricTableBuilder.buildRubricMarkdown(a, file, 2, null, null, null);
        String[] row2 = extractRowValues(md2, "Style issues");
        assertNotNull(row2);
        assertEquals("2", row2[0]);
        assertEquals("4", row2[1]);

        // 10 violations -> capped to possible -> earned = 0
        String md10 = RubricTableBuilder.buildRubricMarkdown(a, file, 10, null, null, null);
        String[] row10 = extractRowValues(md10, "Style issues");
        assertNotNull(row10);
        assertEquals("0", row10[0]);

        // negative violations -> treated as 0 -> earned = possible
        String mdNeg = RubricTableBuilder.buildRubricMarkdown(a, file, -5, null, null, null);
        String[] rowNeg = extractRowValues(mdNeg, "Style issues");
        assertNotNull(rowNeg);
        assertEquals("4", rowNeg[0]);
    }

    @Test
    void unitTestScaling_andFractionalFormatting() {
        Assignment a = new Assignment();
        Assignment.Rubric r = new Assignment.Rubric();
        RubricItemRef ref = new RubricItemRef();
        ref.setRubricItemId("ri_tests");
        ref.setPoints(10);
        r.setItems(java.util.List.of(ref));
        a.setRubric(r);

        RubricItemDef def = new RubricItemDef();
        def.setName("Unit Tests");

        AssignmentsFile file = new AssignmentsFile();
        Map<String, RubricItemDef> lib = new HashMap<>();
        lib.put("ri_tests", def);
        file.setRubricItemLibrary(lib);

        // 1 failure out of 3 tests -> deduction = 10/3 ~ 3.3333 -> earned ~ 6.6667 -> formatted 6.67
        String md = RubricTableBuilder.buildRubricMarkdown(a, file, null, 1.0, 3, null);
        String[] row = extractRowValues(md, "Unit Tests");
        assertNotNull(row);
        assertEquals("6.67", row[0]);
        assertEquals("10", row[1]);
    }

    @Test
    void manualDeductions_applyAndFloorAtZero_and_totalsSum() {
        Assignment a = new Assignment();
        Assignment.Rubric r = new Assignment.Rubric();

        RubricItemRef r1 = new RubricItemRef();
        r1.setRubricItemId("ri1");
        r1.setPoints(5);

        RubricItemRef r2 = new RubricItemRef();
        r2.setRubricItemId("ri2");
        r2.setPoints(3);

        r.setItems(java.util.List.of(r1, r2));
        a.setRubric(r);

        RubricItemDef d1 = new RubricItemDef();
        d1.setName("Part A");

        RubricItemDef d2 = new RubricItemDef();
        d2.setName("Part B");

        AssignmentsFile file = new AssignmentsFile();
        Map<String, RubricItemDef> lib = new HashMap<>();
        lib.put("ri1", d1);
        lib.put("ri2", d2);
        file.setRubricItemLibrary(lib);

        Map<String, Integer> manual = new HashMap<>();
        manual.put("ri1", 3); // reduces 5 -> 2
        manual.put("ri2", 10); // exceeds possible -> floor to 0

        String md = RubricTableBuilder.buildRubricMarkdown(a, file, null, null, null, manual);
        String[] row1 = extractRowValues(md, "Part A");
        String[] row2 = extractRowValues(md, "Part B");
        assertNotNull(row1);
        assertNotNull(row2);
        assertEquals("2", row1[0]);
        assertEquals("5", row1[1]);
        assertEquals("0", row2[0]);
        assertEquals("3", row2[1]);

        // Total should be 2 + 0 = 2 earned, possible 8
        String[] total = extractRowValues(md, "TOTAL");
        assertNotNull(total);
        assertEquals("2", total[0]);
        assertEquals("8", total[1]);
    }
}

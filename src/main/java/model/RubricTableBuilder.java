/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package model;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility for building the rubric table markdown used by both the grading window
 * and the report generator.
 */
public final class RubricTableBuilder {

    private RubricTableBuilder() {
        // utility class
    }

    /**
     * Builds the rubric table markdown for an assignment.
     *
     * <p>The output is formatted as a blockquote markdown table so it renders correctly
     * inside the gradedown viewer.</p>
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>Checkstyle deductions are capped by the rubric item max points.</li>
     *   <li>Unit test failures are scaled by (possible / totalTests).</li>
     *   <li>Manual deductions are applied per rubric item id.</li>
     * </ul>
     *
     * @param assignment the assignment being graded
     * @param assignmentsFile the assignments file containing the rubric item library
     * @param checkstyleViolationsOrNull number of checkstyle violations (nullable)
     * @param unitTestFailuresOrNull number of failed unit tests (nullable)
     * @param totalUnitTestsOrNull total number of unit tests (nullable)
     * @param manualDeductionsByRubricItemId points lost by rubric item id (nullable)
     * @return the rubric markdown block (including RUBRIC_TABLE_BEGIN/END markers)
     */
    public static String buildRubricMarkdown(Assignment assignment,
                                             AssignmentsFile assignmentsFile,
                                             Integer checkstyleViolationsOrNull,
                                             Double unitTestFailuresOrNull,
                                             Integer totalUnitTestsOrNull,
                                             Map<String, Integer> manualDeductionsByRubricItemId) {

        StringBuilder sb = new StringBuilder();
        final int earnedWidth = 6;
        final int possibleWidth = 8;
        final int criteriaWidth = 49;

        sb.append(">> | Earned | Possible | Criteria                                          |")
                .append(System.lineSeparator());
        sb.append(">> | ------ | -------- | ------------------------------------------------- |")
                .append(System.lineSeparator());

        if (assignment == null
                || assignment.getRubric() == null
                || assignment.getRubric().getItems() == null
                || assignment.getRubric().getItems().isEmpty()) {

            sb.append(">> | ")
                    .append(padLeft("0", earnedWidth))
                    .append(" | ")
                    .append(padLeft("0", possibleWidth))
                    .append(" | ")
                    .append(padRight("_No rubric items._", criteriaWidth))
                    .append(" |")
                    .append(System.lineSeparator());
            return sb.toString();
        }

        // Avoid null map checks everywhere
        Map<String, Integer> manual = manualDeductionsByRubricItemId;
        if (manual == null) {
            manual = new HashMap<>();
        }

        int totalPossible = 0;
        double totalEarned = 0.0;

        for (RubricItemRef ref : assignment.getRubric().getItems()) {

            String id = ref.getRubricItemId();
            int possible = ref.getPoints();
            totalPossible += possible;

            RubricItemDef def = null;
            if (assignmentsFile != null && assignmentsFile.getRubricItemLibrary() != null) {
                def = assignmentsFile.getRubricItemLibrary().get(id);
            }

            String criteria = (def != null && def.getName() != null && !def.getName().isBlank())
                    ? def.getName().trim()
                    : id;

            boolean isCheckstyle = def != null && def.isCheckstyleItem();
            boolean isUnitTests = isUnitTestRubricItem(def, id);

            double earned = possible;

            // --- Checkstyle (capped) ---
            if (isCheckstyle && checkstyleViolationsOrNull != null) {
                int v = Math.max(0, checkstyleViolationsOrNull);
                int deduction = Math.min(v, possible);
                earned = possible - deduction;
            }

            // --- Unit Tests (scaled) ---
            if (isUnitTests
                    && unitTestFailuresOrNull != null
                    && totalUnitTestsOrNull != null
                    && totalUnitTestsOrNull > 0) {

                double failures = Math.max(0.0, unitTestFailuresOrNull);
                double perFailure = (double) possible / (double) totalUnitTestsOrNull;
                double deduction = failures * perFailure;
                earned = Math.max(0.0, possible - deduction);
            }

            // --- Manual deductions ---
            if (manual.containsKey(id)) {
                int manualDeduct = Math.max(0, manual.get(id));
                earned = Math.max(0.0, earned - manualDeduct);
            }

            totalEarned += earned;

            sb.append(">> | ")
                    .append(padLeft(formatRubricPoints(earned), earnedWidth))
                    .append(" | ")
                    .append(padLeft(Integer.toString(possible), possibleWidth))
                    .append(" | ")
                    .append(padRight(criteria, criteriaWidth))
                    .append(" |")
                    .append(System.lineSeparator());
        }

        sb.append(">> | ")
                .append(padLeft(formatRubricPoints(totalEarned), earnedWidth))
                .append(" | ")
                .append(padLeft(Integer.toString(totalPossible), possibleWidth))
                .append(" | ")
                .append(padRight("TOTAL", criteriaWidth))
                .append(" |")
                .append(System.lineSeparator());
        return sb.toString();
    }

    private static String formatRubricPoints(double pts) {
        final double delta = 0.00001;
        if (Math.abs(pts - Math.round(pts)) < delta) {
            return Integer.toString((int) Math.round(pts));
        }
        return String.format(java.util.Locale.US, "%.2f", pts);
    }

    private static String padLeft(String s, int width) {
        String t = s == null ? "" : s;
        if (t.length() >= width) {
            return t;
        }
        return " ".repeat(width - t.length()) + t;
    }

    private static String padRight(String s, int width) {
        String t = s == null ? "" : s;
        if (t.length() >= width) {
            return t;
        }
        return t + " ".repeat(width - t.length());
    }

    private static boolean isUnitTestRubricItem(RubricItemDef def, String id) {
        String name = "";
        if (def != null && def.getName() != null) {
            name = def.getName().toLowerCase();
        }
        String lowId = id == null ? "" : id.toLowerCase();

        return name.contains("unit test")
                || name.contains("tests")
                || lowId.contains("test");
    }
}

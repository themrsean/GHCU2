/*
 * Course: CSC-1120
 * GitHub Classroom Utilities
 */
package service;

import model.Assignment;
import model.AssignmentsFile;
import model.Comments.ParsedComment;
import model.RubricItemDef;
import model.RubricItemRef;
import model.RubricTableBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradingReportEditorService {

    private static final String COMMENTS_SUMMARY_BEGIN = "<!-- COMMENTS_SUMMARY_BEGIN -->";
    private static final String COMMENTS_SUMMARY_END = "<!-- COMMENTS_SUMMARY_END -->";
    private static final String RUBRIC_TABLE_BEGIN = "<!-- RUBRIC_TABLE_BEGIN -->";
    private static final String RUBRIC_TABLE_END = "<!-- RUBRIC_TABLE_END -->";

    private final Assignment assignment;
    private final AssignmentsFile assignmentsFile;

    public GradingReportEditorService(Assignment assignment,
                                      AssignmentsFile assignmentsFile) {
        this.assignment = assignment;
        this.assignmentsFile = assignmentsFile;
    }

    public String normalizeRubricAndSummaryBlocks(String markdown) {
        String text = markdown == null ? "" : markdown;
        String existingRubric =
                extractBlockContents(text, RUBRIC_TABLE_BEGIN, RUBRIC_TABLE_END);
        String existingSummary =
                extractBlockContents(text, COMMENTS_SUMMARY_BEGIN, COMMENTS_SUMMARY_END);

        RawRubricTableMatch displacedRawTable = null;
        if (existingRubric == null || existingRubric.isBlank()) {
            displacedRawTable = findFirstRawRubricTable(text);
            if (displacedRawTable != null) {
                existingRubric = displacedRawTable.tableMarkdown();
            }
        }

        String working = text;
        if (displacedRawTable != null) {
            working = removeRawRubricTableAt(
                    working,
                    displacedRawTable.startIndex(),
                    displacedRawTable.endIndexExclusive()
            );
        }
        working = removeAllBlocks(working, RUBRIC_TABLE_BEGIN, RUBRIC_TABLE_END);
        working = removeAllBlocks(working, COMMENTS_SUMMARY_BEGIN, COMMENTS_SUMMARY_END);

        int preambleBoundary = findPreambleBoundary(working);
        String preamble = working.substring(0, preambleBoundary);
        String remainder = working.substring(preambleBoundary);

        if ((existingRubric == null || existingRubric.isBlank())) {
            extractRawRubricTable(preamble);
            existingRubric = extractFirstRawRubricTable(preamble);
        }
        if (existingSummary == null) {
            existingSummary = "";
        }

        String rawRubricTable = extractRawRubricTableBlockquote(preamble);

        preamble = cleanBrokenTitleLine(preamble);
        preamble = removeAllBlocks(preamble, RUBRIC_TABLE_BEGIN, RUBRIC_TABLE_END);
        preamble = removeAllBlocks(preamble, COMMENTS_SUMMARY_BEGIN, COMMENTS_SUMMARY_END);
        preamble = removeStandaloneRubricHeadings(preamble);
        preamble = removeRawRubricTables(preamble);

        String rubricInside;
        if (rawRubricTable != null && !rawRubricTable.isBlank()) {
            rubricInside = rawRubricTable.trim();
        } else if (existingRubric != null && !existingRubric.isBlank()) {
            rubricInside = existingRubric.trim();
        } else {
            rubricInside = buildRubricGradeTableMarkdown(new ArrayList<>());
        }

        String summaryInside;
        if (existingSummary != null && !existingSummary.isBlank()) {
            summaryInside = existingSummary.trim();
        } else {
            summaryInside = buildCommentSummaryMarkdown(new ArrayList<>());
        }

        String rubricBlock =
                RUBRIC_TABLE_BEGIN + System.lineSeparator()
                        + rubricInside + System.lineSeparator()
                        + RUBRIC_TABLE_END;

        String summaryBlock =
                COMMENTS_SUMMARY_BEGIN + System.lineSeparator()
                        + summaryInside + System.lineSeparator()
                        + COMMENTS_SUMMARY_END;

        int insertPos = findInsertPosition(preamble);
        String before = preamble.substring(0, insertPos).replaceFirst("(?s)\\R*\\z", "");
        String after = preamble.substring(insertPos).replaceFirst("(?s)^\\R*", "");
        String newline = System.lineSeparator();

        StringBuilder out = new StringBuilder();
        out.append(before)
                .append(newline)
                .append(newline)
                .append(rubricBlock)
                .append(newline)
                .append(newline)
                .append(summaryBlock)
                .append(newline)
                .append(newline)
                .append(after);
        out.append(remainder);

        String normalized = out.toString();
        return normalized.replaceFirst("^(\\s*\\R)+", "");
    }

    private String removeRawRubricTableAt(String text,
                                          int startIndex,
                                          int endIndexExclusive) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int safeStart = Math.max(0, Math.min(startIndex, text.length()));
        int safeEnd = Math.max(safeStart, Math.min(endIndexExclusive, text.length()));
        return text.substring(0, safeStart) + text.substring(safeEnd);
    }

    private RawRubricTableMatch findFirstRawRubricTable(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        final String pattern =
                "(?m)^\\s*>>\\s*\\|\\s*Earned\\s*\\|\\s*Possible\\s*\\|\\s*Criteria\\s*\\|[^\\r\\n]*\\R"
                        + "^\\s*>>\\s*\\|\\s*[- ]+\\s*\\|\\s*[- ]+\\s*\\|\\s*[- ]+\\s*\\|[^\\r\\n]*\\R"
                        + "(^\\s*>>\\s*\\|[^\\r\\n]*\\|\\s*\\R)+";

        Pattern compiled = Pattern.compile(pattern);
        Matcher matcher = compiled.matcher(text);
        if (!matcher.find()) {
            return null;
        }

        String table = matcher.group();
        return new RawRubricTableMatch(matcher.start(), matcher.end(), table.trim());
    }

    private record RawRubricTableMatch(int startIndex,
                                       int endIndexExclusive,
                                       String tableMarkdown) {
    }

    private int findPreambleBoundary(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Pattern majorSectionPattern = Pattern.compile(
                "(?m)^##\\s+(Source Code|Checkstyle Violations|Failed Unit Tests|Commit History\\b).*"
        );
        Matcher matcher = majorSectionPattern.matcher(text);
        if (matcher.find()) {
            return matcher.start();
        }
        return text.length();
    }

    public String buildFreshReportSkeleton(String studentPackage) {
        return "# " + assignment.getAssignmentName() + System.lineSeparator()
                + System.lineSeparator()
                + RUBRIC_TABLE_BEGIN + System.lineSeparator()
                + buildRubricGradeTableMarkdown(new ArrayList<>()) + System.lineSeparator()
                + RUBRIC_TABLE_END + System.lineSeparator()
                + System.lineSeparator()
                + COMMENTS_SUMMARY_BEGIN + System.lineSeparator()
                + buildCommentSummaryMarkdown(new ArrayList<>()) + System.lineSeparator()
                + COMMENTS_SUMMARY_END + System.lineSeparator()
                + System.lineSeparator()
                + "> # Feedback" + System.lineSeparator()
                + "> * " + System.lineSeparator()
                + System.lineSeparator()
                + "_No report found for " + studentPackage + "._"
                + System.lineSeparator();
    }

    public String ensurePatchSectionsExist(String markdown,
                                           String studentPackage) {
        String text = markdown == null ? "" : markdown;
        boolean hasRubric = text.contains(RUBRIC_TABLE_BEGIN) && text.contains(RUBRIC_TABLE_END);
        boolean hasSummary = text.contains(COMMENTS_SUMMARY_BEGIN)
                && text.contains(COMMENTS_SUMMARY_END);
        if (hasRubric && hasSummary) {
            return text;
        }
        String skeleton = buildFreshReportSkeleton(studentPackage);
        if (text.trim().startsWith("#")) {
            return injectMissingBlocksIntoExisting(text);
        }
        return skeleton + System.lineSeparator() + text;
    }

    public String rebuildRubricAndSummary(String text,
                                          List<ParsedComment> comments) {
        String safeText = text == null ? "" : text;
        String normalized = safeText.replace("\r\n", "\n");
        String existingSummary = extractBlockContents(
                normalized,
                COMMENTS_SUMMARY_BEGIN,
                COMMENTS_SUMMARY_END
        );
        Map<String, Integer> previousManualDeductions =
                computePreviousManualDeductions(existingSummary, comments);
        Map<String, Integer> currentManualDeductions =
                computeManualDeductionsByRubricId(comments);
        String newSummary = buildCommentSummaryMarkdown(comments);
        String updated = replaceBlock(
                normalized,
                COMMENTS_SUMMARY_BEGIN,
                COMMENTS_SUMMARY_END,
                newSummary
        );
        String updatedRubric = buildUpdatedRubricBlockFromExisting(
                updated,
                previousManualDeductions,
                currentManualDeductions
        );
        return replaceBlock(updated, RUBRIC_TABLE_BEGIN, RUBRIC_TABLE_END, updatedRubric);
    }

    private int findInsertPosition(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        Pattern headingPattern = Pattern.compile("(?m)^\\s*#\\s+.*$");
        Matcher headingMatcher = headingPattern.matcher(text);
        if (!headingMatcher.find()) {
            return 0;
        }

        int insertPos = headingMatcher.end();
        while (insertPos < text.length()) {
            char ch = text.charAt(insertPos);
            if (ch == '\n' || ch == '\r') {
                insertPos++;
                continue;
            }
            break;
        }

        return Math.max(0, Math.min(insertPos, text.length()));
    }

    private String extractRawRubricTable(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        final Pattern header = Pattern.compile(
                "(?m)^>>\\s*\\|\\s*Earned\\s*\\|\\s*Possible\\s*\\|\\s*Criteria.*$"
        );

        Matcher matcher = header.matcher(text);
        if (!matcher.find()) {
            return null;
        }

        String after = text.substring(matcher.start());
        String[] lines = after.split("\\R", -1);
        StringBuilder sb = new StringBuilder();

        final int minLinesToCountAsTable = 3;
        int tableLines = 0;
        boolean done = false;

        for (int i = 0; i < lines.length && !done; i++) {
            String line = lines[i];

            if (line.trim().isEmpty()) {
                if (tableLines >= minLinesToCountAsTable) {
                    done = true;
                }
            } else {
                sb.append(line).append(System.lineSeparator());
                tableLines++;
            }
        }

        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private String extractRawRubricTableBlockquote(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String[] lines = text.split("\\R", -1);
        boolean inTable = false;
        StringBuilder sb = new StringBuilder();

        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();

            boolean isHeader =
                    trimmed.startsWith(">>")
                            && trimmed.contains("|")
                            && trimmed.toLowerCase().contains("earned")
                            && trimmed.toLowerCase().contains("possible")
                            && trimmed.toLowerCase().contains("criteria");

            if (!inTable) {
                if (isHeader) {
                    inTable = true;
                    sb.append(line).append(System.lineSeparator());
                }
            } else {
                boolean isTableLine = trimmed.startsWith(">>") && trimmed.contains("|");
                if (trimmed.isEmpty()) {
                    return sb.toString().trim();
                }
                if (isTableLine) {
                    sb.append(line).append(System.lineSeparator());
                } else {
                    return sb.toString().trim();
                }
            }
        }

        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private String removeAllBlocks(String text, String begin, String end) {
        return GradingMarkdownSections.removeAllBlocks(text, begin, end);
    }

    private String removeStandaloneRubricHeadings(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String[] lines = text.split("\\R", -1);
        List<String> kept = new ArrayList<>();

        for (String line : lines) {
            if (!"## Rubric".equals(line.trim())) {
                kept.add(line);
            }
        }

        return String.join(System.lineSeparator(), kept);
    }

    private String cleanBrokenTitleLine(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length == 0) {
            return text;
        }
        String first = lines[0];
        first = first.replace("## Rubric", "");
        first = first.replace("## Source Code", "");
        first = first.replace("##", "");
        first = first.replaceAll("\\s{2,}", " ").trim();
        first = first.replaceAll("\\s{2,}", " ").trim();
        lines[0] = first;
        return String.join(System.lineSeparator(), lines);
    }

    private String removeRawRubricTables(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        final String prefix = "(?ms)^\\s*>>\\s*\\|";
        String updated = text.replaceAll(
                prefix + "\\s*Earned\\s*\\|\\s*Possible\\s*\\|\\s*Criteria.*?\\R"
                        + "(^\\s*>>\\s*\\|[- ]+\\|[- ]+\\|[- ]+\\|\\R)?"
                        + "(^\\s*>>\\s*\\|\\s*\\d+\\s*\\|\\s*\\d+\\s*\\|.*\\|\\R)+\\R?",
                ""
        );
        return updated.replaceAll(
                "(?ms)(^\\s*>>\\s*\\|\\s*\\d+\\s*\\|\\s*\\d+\\s*\\|.*\\|\\R){2,}\\R?",
                ""
        );
    }

    private String extractFirstRawRubricTable(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        final String pattern =
                "(?m)^\\s*>>\\s*\\|\\s*Earned\\s*\\|\\s*Possible\\s*\\|\\s*Criteria\\s*\\|[^\\r\\n]*\\R"
                        + "^\\s*>>\\s*\\|\\s*[- ]+\\s*\\|\\s*[- ]+\\s*\\|\\s*[- ]+\\s*\\|[^\\r\\n]*\\R"
                        + "(^\\s*>>\\s*\\|[^\\r\\n]*\\|\\s*\\R)+";

        Pattern compiled = Pattern.compile(pattern);
        Matcher matcher = compiled.matcher(text);

        if (matcher.find()) {
            return matcher.group().trim();
        }

        return null;
    }

    private String injectMissingBlocksIntoExisting(String markdown) {
        String text = markdown == null ? "" : markdown;
        boolean hasRubric = text.contains(RUBRIC_TABLE_BEGIN) && text.contains(RUBRIC_TABLE_END);
        boolean hasSummary = text.contains(COMMENTS_SUMMARY_BEGIN)
                && text.contains(COMMENTS_SUMMARY_END);
        if (hasRubric && hasSummary) {
            return text;
        }
        String[] lines = text.split("\\R", -1);
        int insertAtLine = -1;
        boolean sawHeading = false;
        boolean done = false;
        for (int i = 0; i < lines.length && !done; i++) {
            String trimmed = lines[i].trim();
            if (!sawHeading && trimmed.startsWith("#")) {
                sawHeading = true;
            } else if (sawHeading && trimmed.isEmpty()) {
                insertAtLine = i + 1;
                done = true;
            }
        }

        if (insertAtLine < 0) {
            insertAtLine = 1;
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]).append(System.lineSeparator());

            if (i == insertAtLine - 1) {
                if (!hasRubric) {
                    sb.append(System.lineSeparator());
                    sb.append(RUBRIC_TABLE_BEGIN).append(System.lineSeparator());
                    sb.append(System.lineSeparator());
                    sb.append(RUBRIC_TABLE_END).append(System.lineSeparator());
                    sb.append(System.lineSeparator());
                }
                if (!hasSummary) {
                    sb.append(System.lineSeparator());
                    sb.append(COMMENTS_SUMMARY_BEGIN).append(System.lineSeparator());
                    sb.append(buildCommentSummaryMarkdown(new ArrayList<>()))
                            .append(System.lineSeparator());
                    sb.append(COMMENTS_SUMMARY_END).append(System.lineSeparator());
                    sb.append(System.lineSeparator());
                }
            }
        }

        return sb.toString();
    }

    private String buildUpdatedRubricBlockFromExisting(String fullText,
                                                       Map<String, Integer> previousManualDeductions,
                                                       Map<String, Integer> currentManualDeductions) {
        String safe = fullText == null ? "" : fullText;
        String existingBlock = extractBlockContents(safe, RUBRIC_TABLE_BEGIN, RUBRIC_TABLE_END);
        if (existingBlock == null || existingBlock.isBlank()) {
            return buildRubricGradeTableMarkdownFromDeductions(currentManualDeductions);
        }

        Map<String, String> rubricIdToCriteriaName = buildRubricIdToCriteriaNameMap();
        List<RubricRow> rows = parseRubricRows(existingBlock);

        if (rows.isEmpty()) {
            return existingBlock;
        }

        applyManualDeductions(
                rows,
                previousManualDeductions,
                currentManualDeductions,
                rubricIdToCriteriaName
        );
        recomputeTotalRow(rows);
        return renderRubricRows(rows, existingBlock);
    }

    private String extractBlockContents(String text, String begin, String end) {
        return GradingMarkdownSections.extractBlockContentsOrNull(text, begin, end);
    }

    private Map<String, Integer> computeManualDeductionsByRubricId(List<ParsedComment> comments) {
        Map<String, Integer> manual = new LinkedHashMap<>();

        if (comments != null) {
            for (ParsedComment comment : comments) {
                if (comment == null) {
                    continue;
                }
                String id = comment.rubricItemId();
                if (id == null || id.isBlank()) {
                    continue;
                }

                int lost = Math.max(0, comment.pointsLost());
                manual.put(id, manual.getOrDefault(id, 0) + lost);
            }
        }

        return manual;
    }

    private Map<String, Integer> computePreviousManualDeductions(String summaryBlockContents,
                                                                 List<ParsedComment> currentComments) {
        Map<String, Integer> deductions = new LinkedHashMap<>();
        Map<String, ParsedComment> currentByAnchor = mapCommentsByAnchor(currentComments);

        if (summaryBlockContents == null || summaryBlockContents.isBlank()) {
            return deductions;
        }

        final Pattern deductionPattern = Pattern.compile(
                "\\]\\(#([^\\)]+)\\)\\s*\\(-([0-9]+)\\s+([^\\s\\)]+)\\)"
        );
        Matcher matcher = deductionPattern.matcher(summaryBlockContents);

        while (matcher.find()) {
            String anchorId = matcher.group(1) == null ? "" : matcher.group(1).trim();
            int lostFromSummary = Integer.parseInt(matcher.group(2));
            String rubricFromSummary = matcher.group(3) == null ? "" : matcher.group(3).trim();

            int lost = Math.max(0, lostFromSummary);
            String rubricId = rubricFromSummary;

            ParsedComment current = currentByAnchor.get(anchorId);
            if (current != null) {
                lost = Math.max(0, current.pointsLost());
                String currentRubricId = current.rubricItemId();
                rubricId = currentRubricId == null ? "" : currentRubricId.trim();
            }

            if (rubricId.isBlank()) {
                continue;
            }

            int updated = deductions.getOrDefault(rubricId, 0) + lost;
            deductions.put(rubricId, updated);
        }

        return deductions;
    }

    private Map<String, ParsedComment> mapCommentsByAnchor(List<ParsedComment> comments) {
        Map<String, ParsedComment> byAnchor = new LinkedHashMap<>();

        if (comments == null || comments.isEmpty()) {
            return byAnchor;
        }

        for (ParsedComment comment : comments) {
            if (comment == null) {
                continue;
            }

            String anchorId = comment.anchorId();
            if (anchorId == null || anchorId.isBlank()) {
                continue;
            }

            byAnchor.put(anchorId.trim(), comment);
        }

        return byAnchor;
    }

    private Map<String, String> buildRubricIdToCriteriaNameMap() {
        Map<String, String> map = new LinkedHashMap<>();

        if (assignment != null
                && assignment.getRubric() != null
                && assignment.getRubric().getItems() != null
                && assignmentsFile != null
                && assignmentsFile.getRubricItemLibrary() != null) {

            for (RubricItemRef ref : assignment.getRubric().getItems()) {
                if (ref == null) {
                    continue;
                }

                String id = ref.getRubricItemId();
                if (id == null || id.isBlank()) {
                    continue;
                }

                RubricItemDef def = assignmentsFile.getRubricItemLibrary().get(id);
                String criteriaName = id;
                if (def != null && def.getName() != null && !def.getName().isBlank()) {
                    criteriaName = def.getName().trim();
                }

                map.put(id, criteriaName);
            }
        }

        return map;
    }

    private List<RubricRow> parseRubricRows(String rubricBlock) {
        List<RubricRow> rows = new ArrayList<>();

        if (rubricBlock == null || rubricBlock.isBlank()) {
            return rows;
        }

        String[] lines = rubricBlock.split("\\R", -1);
        for (String line : lines) {
            if (line == null) {
                continue;
            }

            String trimmed = line.trim();
            boolean isRow = trimmed.startsWith(">> |");
            boolean isSeparator = trimmed.contains("---");
            boolean isHeader = trimmed.toLowerCase().contains("| earned |")
                    && trimmed.toLowerCase().contains("| possible |");

            if (isRow && !isSeparator && !isHeader) {
                RubricRow row = tryParseRubricRowLine(trimmed);
                if (row != null) {
                    rows.add(row);
                }
            }
        }

        return rows;
    }

    private RubricRow tryParseRubricRowLine(String trimmed) {
        final String prefix = ">>";
        String text = trimmed;

        if (text.startsWith(prefix)) {
            text = text.substring(prefix.length()).trim();
        }

        String[] parts = text.split("\\|", -1);
        final int earnedIndex = 1;
        final int possibleIndex = 2;
        final int criteriaIndex = 3;
        final int minParts = 5;

        if (parts.length >= minParts) {
            Double earned = tryParseDouble(parts[earnedIndex].trim());
            Integer possible = tryParseInt(parts[possibleIndex].trim());
            String criteria = parts[criteriaIndex].trim();

            if (earned != null && possible != null && !criteria.isBlank()) {
                return new RubricRow(earned, possible, criteria);
            }
        }

        return null;
    }

    private Integer tryParseInt(String s) {
        try {
            return s == null ? null : Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double tryParseDouble(String s) {
        try {
            return s == null ? null : Double.parseDouble(s.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void applyManualDeductions(List<RubricRow> rows,
                                       Map<String, Integer> previousManualDeductions,
                                       Map<String, Integer> currentManualDeductions,
                                       Map<String, String> rubricIdToCriteriaName) {
        if (rows == null
                || rows.isEmpty()
                || rubricIdToCriteriaName == null
                || rubricIdToCriteriaName.isEmpty()) {
            return;
        }

        Map<String, Integer> previous = previousManualDeductions == null
                ? Map.of()
                : previousManualDeductions;
        Map<String, Integer> current = currentManualDeductions == null
                ? Map.of()
                : currentManualDeductions;

        for (Map.Entry<String, String> entry : rubricIdToCriteriaName.entrySet()) {
            String rubricId = entry.getKey();
            int previousLost = Math.max(0, previous.getOrDefault(rubricId, 0));
            int currentLost = Math.max(0, current.getOrDefault(rubricId, 0));

            String criteriaName = entry.getValue();
            if (criteriaName == null || criteriaName.isBlank()) {
                criteriaName = rubricId;
            }

            RubricRow matching = findRowByCriteria(rows, criteriaName);
            if (matching != null) {
                double baselineEarned = matching.earned + previousLost;
                baselineEarned = Math.max(0, Math.min(baselineEarned, (double) matching.possible));

                double newEarned = baselineEarned - currentLost;
                newEarned = Math.max(0, Math.min(newEarned, (double) matching.possible));
                matching.earned = newEarned;
            }
        }
    }

    private RubricRow findRowByCriteria(List<RubricRow> rows, String criteriaName) {
        if (rows != null && criteriaName != null) {
            String target = normalizeCriteria(criteriaName);

            for (RubricRow row : rows) {
                if (row != null) {
                    String actual = normalizeCriteria(row.criteria);
                    if (actual.equalsIgnoreCase(target)) {
                        return row;
                    }
                }
            }
        }

        return null;
    }

    private String normalizeCriteria(String s) {
        String out = s == null ? "" : s.trim();
        return out.replaceAll("\\s+", " ");
    }

    private void recomputeTotalRow(List<RubricRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        final String totalLabel = "TOTAL";
        RubricRow totalRow = null;
        double earnedSum = 0.0;
        int possibleSum = 0;

        for (RubricRow row : rows) {
            if (row == null) {
                continue;
            }
            boolean isTotal =
                    row.criteria != null && row.criteria.trim().equalsIgnoreCase(totalLabel);
            if (isTotal) {
                totalRow = row;
            } else {
                earnedSum += Math.max(0, row.earned);
                possibleSum += Math.max(0, row.possible);
            }
        }

        if (totalRow != null) {
            totalRow.earned = earnedSum;
            totalRow.possible = possibleSum;
        }
    }

    private String renderRubricRows(List<RubricRow> parsedRows,
                                    String existingBlock) {
        String safeExistingBlock = existingBlock == null ? "" : existingBlock;
        String[] lines = safeExistingBlock.split("\\R", -1);

        Map<String, RubricRow> criteriaToRow = new LinkedHashMap<>();
        for (RubricRow row : parsedRows) {
            if (row != null) {
                criteriaToRow.put(normalizeCriteria(row.criteria).toLowerCase(), row);
            }
        }

        StringBuilder out = new StringBuilder();

        for (String line : lines) {
            String original = line == null ? "" : line;
            String trimmed = original.trim();

            boolean isRow = trimmed.startsWith(">> |");
            boolean isSeparator = trimmed.contains("---");
            boolean isHeader = trimmed.toLowerCase().contains("| earned |")
                    && trimmed.toLowerCase().contains("| possible |");

            String toWrite = original;

            if (isRow && !isSeparator && !isHeader) {
                RubricRow maybe = tryParseRubricRowLine(trimmed);
                if (maybe != null) {
                    String key = normalizeCriteria(maybe.criteria).toLowerCase();
                    RubricRow updated = criteriaToRow.get(key);
                    if (updated != null) {
                        toWrite = formatRowLikeOriginal(updated);
                    }
                }
            }

            out.append(toWrite).append(System.lineSeparator());
        }

        return out.toString().trim();
    }

    private String formatRowLikeOriginal(RubricRow updated) {
        final String prefix = ">> |";
        final String pipe = "|";
        final int earnedPad = 6;
        final int possiblePad = 8;
        String criteria = updated.criteria == null ? "" : updated.criteria;
        String earnedStr = formatRubricPoints(updated.earned);
        String possibleStr = Integer.toString(updated.possible);
        return prefix + " " + padLeft(earnedStr, earnedPad) + " " + pipe + " "
                + padLeft(possibleStr, possiblePad) + " " + pipe + " "
                + criteria + " " + pipe;
    }

    private String formatRubricPoints(double pts) {
        final double delta = 0.00001;
        if (Math.abs(pts - Math.round(pts)) < delta) {
            return Integer.toString((int) Math.round(pts));
        }
        return String.format(java.util.Locale.US, "%.2f", pts);
    }

    private String padLeft(String s, int width) {
        String text = s == null ? "" : s;
        StringBuilder sb = new StringBuilder();
        int spaces = Math.max(0, width - text.length());
        for (int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
        sb.append(text);
        return sb.toString();
    }

    private String replaceBlock(String text,
                                String beginMarker,
                                String endMarker,
                                String newContent) {
        String src = text == null ? "" : text;
        int beginIndex = src.indexOf(beginMarker);
        int endIndex = src.indexOf(endMarker);

        if (beginIndex < 0 || endIndex < 0 || endIndex < beginIndex) {
            return src;
        }

        int start = beginIndex + beginMarker.length();
        String before = src.substring(0, start);
        String current = src.substring(start, endIndex);
        String after = src.substring(endIndex);

        String normalizedCurrent = current == null ? "" : current.trim();
        String normalizedNew = newContent == null ? "" : newContent.trim();

        if (normalizedCurrent.equals(normalizedNew)) {
            return src;
        }

        return before
                + System.lineSeparator()
                + normalizedNew
                + System.lineSeparator()
                + after;
    }

    private String buildCommentSummaryMarkdown(List<ParsedComment> comments) {
        StringBuilder sb = new StringBuilder();

        sb.append(">> # Comments").append(System.lineSeparator());
        sb.append(">>").append(System.lineSeparator());

        if (comments == null || comments.isEmpty()) {
            sb.append(">> * _No comments._").append(System.lineSeparator());
            return sb.toString();
        }

        for (ParsedComment comment : comments) {
            sb.append(">> * [")
                    .append(escapeMdText(comment.title()))
                    .append("](#")
                    .append(comment.anchorId())
                    .append(") (-")
                    .append(comment.pointsLost())
                    .append(" ")
                    .append(comment.rubricItemId())
                    .append(")")
                    .append(System.lineSeparator());
        }

        return sb.toString();
    }

    private String escapeMdText(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    private String buildRubricGradeTableMarkdown(List<ParsedComment> comments) {
        Map<String, Integer> manualDeductions = new LinkedHashMap<>();
        if (comments != null) {
            for (ParsedComment comment : comments) {
                if (comment == null) {
                    continue;
                }
                String id = comment.rubricItemId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                int lost = Math.max(0, comment.pointsLost());
                manualDeductions.put(id, manualDeductions.getOrDefault(id, 0) + lost);
            }
        }

        return RubricTableBuilder.buildRubricMarkdown(
                assignment,
                assignmentsFile,
                null,
                null,
                null,
                manualDeductions
        );
    }

    private String buildRubricGradeTableMarkdownFromDeductions(Map<String, Integer> manualDeductions) {
        Map<String, Integer> deductions = manualDeductions == null
                ? Map.of()
                : manualDeductions;

        return RubricTableBuilder.buildRubricMarkdown(
                assignment,
                assignmentsFile,
                null,
                null,
                null,
                deductions
        );
    }

    private static final class RubricRow {
        private double earned;
        private int possible;
        private final String criteria;

        private RubricRow(double earned, int possible, String criteria) {
            this.earned = earned;
            this.possible = possible;
            this.criteria = criteria;
        }
    }
}

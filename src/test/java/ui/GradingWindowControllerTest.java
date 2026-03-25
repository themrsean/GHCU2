package ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import model.Comments.CommentDef;
import model.Assignment;
import model.AssignmentsFile;
import model.RubricItemDef;
import model.RubricItemRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import service.GradingReportEditorService;
import service.GradingDraftService;
import service.ReportHtmlWrapper;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GradingWindowControllerTest {

    @Test
    public void resolveMappingFile_prefersProvidedMappingsPath(@TempDir Path tmp)
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path provided = tmp.resolve("provided-mappings.json");

        setField(controller, "mappingsPath", provided);

        Path resolved = (Path) invokeMethod(controller, "resolveMappingFile");

        assertEquals(provided, resolved);
    }

    @Test
    public void resolveMappingFile_fallsBackToAssignmentMappingName() throws Exception {
        GradingWindowController controller = new GradingWindowController();

        setField(controller, "mappingsPath", null);
        setField(controller, "assignmentId", "CSC101A1");

        Path resolved = (Path) invokeMethod(controller, "resolveMappingFile");

        assertEquals("mappings-CSC101A1.json", resolved.getFileName().toString());
    }

    @Test
    public void preflightPush_rejectsDetachedHead(@TempDir Path tmp) throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path repoDir = createRepoWithCommit(tmp.resolve("repo-detached"));

        runGit(repoDir, "checkout", "--detach");

        Object result = invokeMethod(
                controller,
                "preflightPush",
                new Class<?>[] {Path.class, String.class},
                repoDir,
                "report.html"
        );

        assertFalse(readBoolean(result, "allowed"));
        assertEquals("repository is on a detached HEAD", readString(result, "message"));
    }

    @Test
    public void preflightPush_rejectsBranchWithoutUpstream(@TempDir Path tmp) throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path repoDir = createRepoWithCommit(tmp.resolve("repo-no-upstream"));

        Object result = invokeMethod(
                controller,
                "preflightPush",
                new Class<?>[] {Path.class, String.class},
                repoDir,
                "report.html"
        );

        assertFalse(readBoolean(result, "allowed"));
        assertEquals("branch has no upstream", readString(result, "message"));
    }

    @Test
    public void preflightPush_allowsUnrelatedChanges(@TempDir Path tmp) throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path repoDir = createRepoWithUpstream(tmp);

        Files.writeString(repoDir.resolve("notes.txt"), "local change");

        Object result = invokeMethod(
                controller,
                "preflightPush",
                new Class<?>[] {Path.class, String.class},
                repoDir,
                "report.html"
        );

        assertTrue(readBoolean(result, "allowed"));
        assertEquals("", readString(result, "message"));
    }

    @Test
    public void preflightPush_allowsReportFileChange(@TempDir Path tmp) throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path repoDir = createRepoWithUpstream(tmp);

        Files.writeString(repoDir.resolve("report.html"), "feedback");

        Object result = invokeMethod(
                controller,
                "preflightPush",
                new Class<?>[] {Path.class, String.class},
                repoDir,
                "report.html"
        );

        assertTrue(readBoolean(result, "allowed"));
        assertEquals("", readString(result, "message"));
    }

    @Test
    public void saveDraftsWorker_writesReportAndReturnsSuccess(@TempDir Path tmp)
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path repoDir = tmp.resolve("repo-save-success");
        Files.createDirectories(repoDir);

        Path mappingFile = tmp.resolve("mappings.json");
        writeMappingFile(mappingFile, Map.of("smith", repoDir.toString()));

        setField(controller, "assignmentId", "A1");
        setField(controller, "mappingsPath", mappingFile);

        addStudentPackage(controller, "smith");
        putDraft(controller, "smith", "# Feedback", true);

        Object result = invokeMethod(controller, "saveDraftsWorker");

        assertTrue(readBoolean(result, "success"));
        assertEquals("Saved 1 HTML report(s) to student repositories.",
                readString(result, "message"));
        assertTrue(Files.exists(repoDir.resolve("A1smith.html")));
    }

    @Test
    public void saveDraftsWorker_missingRepoReturnsFailureMessage(@TempDir Path tmp)
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path mappingFile = tmp.resolve("mappings.json");
        Path missingRepo = tmp.resolve("missing-repo");

        writeMappingFile(mappingFile, Map.of("smith", missingRepo.toString()));

        setField(controller, "assignmentId", "A1");
        setField(controller, "mappingsPath", mappingFile);

        addStudentPackage(controller, "smith");
        putDraft(controller, "smith", "# Feedback", true);

        Object result = invokeMethod(controller, "saveDraftsWorker");

        assertFalse(readBoolean(result, "success"));
        assertEquals("Could not find repo for smith", readString(result, "message"));
    }

    @Test
    public void handleSaveDraftResult_runsCompletionOnlyAfterSuccessfulSave()
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        AtomicInteger completions = new AtomicInteger(0);

        Object failedResult = newSaveDraftResult(false, "save failed");
        invokeMethod(
                controller,
                "handleSaveDraftResult",
                new Class<?>[] {failedResult.getClass(), Runnable.class},
                failedResult,
                (Runnable) completions::incrementAndGet
        );

        Object successResult = newSaveDraftResult(true, "saved");
        invokeMethod(
                controller,
                "handleSaveDraftResult",
                new Class<?>[] {successResult.getClass(), Runnable.class},
                successResult,
                (Runnable) completions::incrementAndGet
        );

        assertEquals(1, completions.get());
    }

    @Test
    public void loadStudentPackages_keepsValidMappingsWhenOneRepoPathIsInvalid(@TempDir Path tmp)
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path validRepo = tmp.resolve("repo-valid");
        Path invalidRepo = tmp.resolve("repo-missing");
        Files.createDirectories(validRepo);

        Path mappingFile = tmp.resolve("mappings.json");
        writeMappingFile(
                mappingFile,
                Map.of(
                        "pkgValid", validRepo.toString(),
                        "pkgMissing", invalidRepo.toString()
                )
        );

        setField(controller, "mappingsPath", mappingFile);
        setField(controller, "rootPath", tmp);
        setField(controller, "assignmentId", "A1");

        invokeMethod(controller, "loadStudentPackages");

        assertEquals(List.of("pkgValid"), readStudentPackages(controller));
        assertEquals(validRepo, invokeMethod(
                controller,
                "findRepoDirForStudentPackage",
                new Class<?>[] {String.class},
                "pkgValid"
        ));
        assertEquals(null, invokeMethod(
                controller,
                "findRepoDirForStudentPackage",
                new Class<?>[] {String.class},
                "pkgMissing"
        ));
    }

    @Test
    public void pushAllRepos_collectsDetailSummaryForReportedFailures(@TempDir Path tmp)
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path repoNotGit = tmp.resolve("repo-not-git");
        Path repoMissingReport = tmp.resolve("repo-missing-report");
        Path repoNoUpstream = createRepoWithCommit(tmp.resolve("repo-no-upstream-push"));

        Files.createDirectories(repoNotGit);
        Files.createDirectories(repoMissingReport);
        Files.writeString(repoNotGit.resolve("A1pkg1.html"), "feedback");
        Files.writeString(repoNoUpstream.resolve("A1pkg3.html"), "feedback");

        Path mappingFile = tmp.resolve("mappings.json");
        writeMappingFile(
                mappingFile,
                Map.of(
                        "pkg1", repoNotGit.toString(),
                        "pkg2", repoMissingReport.toString(),
                        "pkg3", repoNoUpstream.toString()
                )
        );

        setField(controller, "assignmentId", "A1");
        setField(controller, "mappingsPath", mappingFile);

        addStudentPackage(controller, "pkg1");
        addStudentPackage(controller, "pkg2");
        addStudentPackage(controller, "pkg3");

        Object result = invokeMethod(controller, "pushAllRepos");

        assertEquals(0, readIntField(result, "pushed"));
        assertEquals(3, readIntField(result, "skipped"));
        assertEquals(0, readIntField(result, "failed"));
        assertEquals(
                "pkg1: unable to determine current branch; pkg2: report file missing; "
                        + "pkg3: branch has no upstream",
                readStringField(result, "detailSummary")
        );
    }

    @Test
    public void summarizeDetails_limitsSummaryToFirstThreeEntries() throws Exception {
        GradingWindowController controller = new GradingWindowController();

        @SuppressWarnings("unchecked")
        String summary = (String) invokeMethod(
                controller,
                "summarizeDetails",
                new Class<?>[] {List.class},
                List.of("one", "two", "three", "four")
        );

        assertEquals("one; two; three", summary);
    }

    @Test
    public void saveAndExportPath_saveThenPush_reportsSingleSuccessfulPush(@TempDir Path tmp)
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path repoDir = createRepoWithUpstream(tmp);
        Path mappingFile = tmp.resolve("mappings.json");
        writeMappingFile(mappingFile, Map.of("pkg1", repoDir.toString()));

        setField(controller, "assignmentId", "A1");
        setField(controller, "mappingsPath", mappingFile);

        addStudentPackage(controller, "pkg1");
        putDraft(controller, "pkg1", "# Feedback", true);

        Object saveResult = invokeMethod(controller, "saveDraftsWorker");
        assertTrue(readBoolean(saveResult, "success"));
        assertEquals(
                "Saved 1 HTML report(s) to student repositories.",
                readString(saveResult, "message")
        );

        Object pushResult = invokeMethod(controller, "pushAllRepos");
        assertEquals(1, readIntField(pushResult, "pushed"));
        assertEquals(0, readIntField(pushResult, "skipped"));
        assertEquals(0, readIntField(pushResult, "failed"));
        assertEquals("", readStringField(pushResult, "detailSummary"));
    }

    @Test
    public void saveDraftsWorker_currentStudentMissingRepo_reportsFailure(@TempDir Path tmp)
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path validRepo = tmp.resolve("repo-valid");
        Files.createDirectories(validRepo);
        Path mappingFile = tmp.resolve("mappings.json");
        writeMappingFile(mappingFile, Map.of("pkgOther", validRepo.toString()));

        setField(controller, "assignmentId", "A1");
        setField(controller, "mappingsPath", mappingFile);
        setField(controller, "currentStudent", "pkgMissing");

        addStudentPackage(controller, "pkgMissing");
        putDraft(controller, "pkgMissing", "# Missing", true);

        Object result = invokeMethod(controller, "saveDraftsWorker");

        assertFalse(readBoolean(result, "success"));
        assertEquals("Could not find repo for pkgMissing", readString(result, "message"));
    }

    @Test
    public void saveDraftsWorker_skipsStudentsWithReportLoadFailures(@TempDir Path tmp)
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path repoDir = tmp.resolve("repo-save-skip");
        Files.createDirectories(repoDir);
        Path mappingFile = tmp.resolve("mappings.json");
        writeMappingFile(mappingFile, Map.of("pkg1", repoDir.toString()));

        setField(controller, "assignmentId", "A1");
        setField(controller, "mappingsPath", mappingFile);
        setField(controller, "currentStudent", "pkg1");

        addStudentPackage(controller, "pkg1");
        putDraft(controller, "pkg1", "# Feedback", true);
        Field failedLoadsField = controller.getClass().getDeclaredField("reportLoadFailureStudents");
        failedLoadsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> failedLoads = (Set<String>) failedLoadsField.get(controller);
        failedLoads.add("pkg1");

        Object result = invokeMethod(controller, "saveDraftsWorker");

        assertFalse(readBoolean(result, "success"));
        assertTrue(readString(result, "message").contains("Skipped 1 report(s) due to load errors."));
        assertFalse(Files.exists(repoDir.resolve("A1pkg1.html")));
    }

    @Test
    public void formatPushCompletionMessage_includesDetailOnlyWhenPresent() {
        String withDetails = GradingWindowController.formatPushCompletionMessage(
                1,
                2,
                3,
                "pkg1: report file missing"
        );
        assertEquals(
                "Save/push complete: pushed 1, skipped 2, failed 3. pkg1: report file missing",
                withDetails
        );

        String withoutDetails = GradingWindowController.formatPushCompletionMessage(
                1,
                0,
                0,
                ""
        );
        assertEquals("Save/push complete: pushed 1, skipped 0, failed 0.", withoutDetails);
    }

    @Test
    public void computeAppliedLoss_clampsToRemainingRubricPoints() {
        assertEquals(3, GradingWindowController.computeAppliedLoss(3, 10, 2));
        assertEquals(8, GradingWindowController.computeAppliedLoss(12, 10, 2));
        assertEquals(0, GradingWindowController.computeAppliedLoss(-5, 10, 2));
        assertEquals(0, GradingWindowController.computeAppliedLoss(5, 4, 7));
    }

    @Test
    public void buildInjectedCommentMarkdown_formatsAnchorHeaderDeductionAndBody() {
        CommentDef def = new CommentDef();
        def.setRubricItemId("ri_style");
        def.setTitle("Style issue");
        def.setBodyMarkdown("Line one\nLine two");

        String markdown = GradingWindowController.buildInjectedCommentMarkdown(def, "cmt_x", 2);

        assertTrue(markdown.contains("<a id=\"cmt_x\"></a>"));
        assertTrue(markdown.contains("<!-- cmt-meta rubric:ri_style -->"));
        assertTrue(markdown.contains("> #### -2 Style issue"));
        assertFalse(markdown.contains("> * -2 points (ri_style)"));
        assertTrue(markdown.contains("> Line one"));
        assertTrue(markdown.contains("> Line two"));
        assertTrue(markdown.endsWith(System.lineSeparator() + System.lineSeparator()));
    }

    @Test
    public void makeAnchorIdFromComment_sameCommentId_remainsStable() throws Exception {
        GradingWindowController controller = new GradingWindowController();
        CommentDef def = new CommentDef();
        def.setCommentId("late-submission");

        String first = (String) invokeMethod(
                controller,
                "makeAnchorIdFromComment",
                new Class<?>[] {CommentDef.class},
                def
        );
        String second = (String) invokeMethod(
                controller,
                "makeAnchorIdFromComment",
                new Class<?>[] {CommentDef.class},
                def
        );

        assertEquals("cmt_late-submission", first);
        assertEquals("cmt_late-submission", second);
    }

    @Test
    public void insertCanonicalCommentBlock_midLineMovesBlockToStandaloneLine() {
        String base = "ABCD\nTail";
        String block = "<a id=\"cmt_x\"></a>\n"
                + "<!-- cmt-meta rubric:ri -->\n"
                + "```\n"
                + "> #### -1 Title\n"
                + ">\n"
                + "```\n\n";

        GradingWindowController.InsertBlockResult result =
                GradingWindowController.insertCanonicalCommentBlock(base, block, 2);

        assertTrue(result.updatedText().contains("ABCD" + System.lineSeparator() + "<a id=\"cmt_x\"></a>"));
        assertFalse(result.updatedText().contains("AB<a id=\"cmt_x\"></a>CD"));
        assertTrue(result.updatedText().contains("```"));
        assertTrue(result.caretAfterBlock() > result.updatedText().indexOf("<a id=\"cmt_x\"></a>"));
    }

    @Test
    public void removeInjectedCommentBlock_removesOnlyTargetAnchorBlock() {
        String text = """
                Intro
                <a id="cmt_one"></a>
                ```
                > #### One
                > * -2 points (ri_a)
                > body
                ```

                <a id="cmt_two"></a>
                ```
                > #### Two
                > * -1 points (ri_b)
                ```

                Tail
                """;

        GradingWindowController.RemovalResult result =
                GradingWindowController.removeInjectedCommentBlock(text, "cmt_one");

        assertTrue(result.found());
        assertFalse(result.updatedText().contains("<a id=\"cmt_one\"></a>"));
        assertTrue(result.updatedText().contains("<a id=\"cmt_two\"></a>"));
        assertTrue(result.updatedText().contains("Tail"));
    }

    @Test
    public void removeInjectedCommentBlock_returnsNotFoundWhenAnchorMissing() {
        String text = "No anchors here";
        GradingWindowController.RemovalResult result =
                GradingWindowController.removeInjectedCommentBlock(text, "cmt_missing");

        assertFalse(result.found());
        assertEquals(text, result.updatedText());
        assertEquals(-1, result.startIndex());
        assertEquals(-1, result.endIndexExclusive());
    }

    @Test
    public void rebuildRubricAndSummaryText_isIdempotentForUnchangedComments() {
        GradingReportEditorService editor = new GradingReportEditorService(
                buildSingleRubricAssignment(),
                buildSingleRubricAssignmentsFile()
        );
        String initial = editor.buildFreshReportSkeleton("smith")
                + "<a id=\"cmt_1\"></a>\n"
                + "```\n"
                + "> #### Missing semicolon\n"
                + "> * -3 points (ri_impl)\n"
                + "```\n";

        String once = GradingWindowController.rebuildRubricAndSummaryText(initial, editor);
        String twice = GradingWindowController.rebuildRubricAndSummaryText(once, editor);

        assertEquals(once.replaceAll(" +", " "), twice.replaceAll(" +", " "));
    }

    @Test
    public void rebuildRubricAndSummaryText_mixedLegacyInlineAndCanonicalComments_appliesCombinedDeduction() {
        GradingReportEditorService editor = new GradingReportEditorService(
                buildSingleRubricAssignment(),
                buildSingleRubricAssignmentsFile()
        );
        String initial = editor.buildFreshReportSkeleton("smith")
                + "AB<a id=\"cmt_inline\"></a>CD\n"
                + "```\n"
                + "> #### Inline legacy\n"
                + "> * -2 points (ri_impl)\n"
                + "```\n"
                + "\n"
                + "<a id=\"cmt_canonical\"></a>\n"
                + "```\n"
                + "> #### Canonical\n"
                + "> * -1 points (ri_impl)\n"
                + "```\n";

        String rebuilt = GradingWindowController.rebuildRubricAndSummaryText(initial, editor);

        assertTrue(rebuilt.matches(
                "(?s).*\\|\\s*7(?:\\.0+)?\\s*\\|\\s*10(?:\\.0+)?\\s*\\|\\s*Implementation\\b.*"
        ));
    }

    @Test
    public void normalizeForLegacyEditorView_doesNotAccumulateBlankLinesNearFeedback()
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        String input = """
                # Lab Assignment 1 - Sample

                <!-- RUBRIC_TABLE_BEGIN -->
                >> | Earned | Possible | Criteria                                          |
                >> | ------ | -------- | ------------------------------------------------- |
                >> |   10   |     10   | Intermediate Commits                              |
                <!-- RUBRIC_TABLE_END -->

                <!-- COMMENTS_SUMMARY_BEGIN -->
                >> # Comments
                >>
                >> * _No comments._
                <!-- COMMENTS_SUMMARY_END -->

                > # Feedback
                > * Nice work!
                """;

        String once = (String) invokeMethod(
                controller,
                "normalizeForLegacyEditorView",
                new Class<?>[] {String.class},
                input
        );
        String twice = (String) invokeMethod(
                controller,
                "normalizeForLegacyEditorView",
                new Class<?>[] {String.class},
                once
        );

        assertEquals(once, twice);
        String canonicalGap = System.lineSeparator()
                + ">"
                + System.lineSeparator()
                + ">"
                + System.lineSeparator()
                + "> # Feedback";
        assertTrue(once.contains(canonicalGap));
    }

    @Test
    public void rebuildCycle_doesNotAccumulateQuotedBlankLinesBeforeFeedback()
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        GradingReportEditorService editor = new GradingReportEditorService(
                buildSingleRubricAssignment(),
                buildSingleRubricAssignmentsFile()
        );
        setField(controller, "gradingReportEditorService", editor);

        String text = """
                # Lab Assignment 6 - AutoCompleter
                >
                >
                > # Feedback
                > * No feedback provided


                >> | Earned | Possible | Criteria                                          |
                >> | ------ | -------- | ------------------------------------------------- |
                >> |     15 |       15 | CheckStyle Errors |
                >> |     10 |       10 | Intermediate Commits |
                >> |     55 |       55 | Code Implementation and Structure |
                >> |  18.82 |       20 | Unit Tests |
                >> |  98.82 |      100 | TOTAL |



                ### AutoCompleter.java

                ```java
                class AutoCompleter {}
                ```
                """;

        String previous = null;
        for (int i = 0; i < 4; i++) {
            String canonical = (String) invokeMethod(
                    controller,
                    "normalizeRubricAndSummaryBlocks",
                    new Class<?>[] {String.class, String.class},
                    text,
                    "pkg1"
            );
            String rebuilt = GradingWindowController.rebuildRubricAndSummaryText(canonical, editor);
            text = (String) invokeMethod(
                    controller,
                    "normalizeForLegacyEditorView",
                    new Class<?>[] {String.class},
                    rebuilt
            );
            if (previous != null && i >= 2) {
                assertEquals(previous, text);
            }
            previous = text;
        }
    }

    @Test
    public void rebuildCycle_sparseDraftWithoutFeedbackHeader_isIdempotentAfterFirstPass()
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        GradingReportEditorService editor = new GradingReportEditorService(
                buildSingleRubricAssignment(),
                buildSingleRubricAssignmentsFile()
        );
        setField(controller, "gradingReportEditorService", editor);

        String text = """
                # Assignment

                <!-- RUBRIC_TABLE_BEGIN -->
                >> | Earned | Possible | Criteria |
                >> | --- | --- | --- |
                >> | 10 | 10 | Implementation |
                >> | 10 | 10 | TOTAL |
                <!-- RUBRIC_TABLE_END -->

                <!-- COMMENTS_SUMMARY_BEGIN -->
                _No comments selected._
                <!-- COMMENTS_SUMMARY_END -->

                <a id="cmt_x"></a>
                ```
                > #### Title
                > * -3 points (ri_impl)
                ```
                """;

        List<model.Comments.ParsedComment> previousFirst = model.Comments.parseInjectedComments(text);
        String canonicalFirst = (String) invokeMethod(
                controller,
                "normalizeRubricAndSummaryBlocks",
                new Class<?>[] {String.class, String.class},
                text,
                "pkg1"
        );
        String rebuiltFirst = GradingWindowController.rebuildRubricAndSummaryText(
                canonicalFirst,
                previousFirst,
                editor
        );
        String once = (String) invokeMethod(
                controller,
                "normalizeForLegacyEditorView",
                new Class<?>[] {String.class},
                rebuiltFirst
        );
        once = (String) invokeMethod(
                controller,
                "normalizeForLegacyEditorView",
                new Class<?>[] {String.class},
                once
        );

        List<model.Comments.ParsedComment> previousSecond = model.Comments.parseInjectedComments(once);
        String canonicalSecond = (String) invokeMethod(
                controller,
                "normalizeRubricAndSummaryBlocks",
                new Class<?>[] {String.class, String.class},
                once,
                "pkg1"
        );
        String rebuiltSecond = GradingWindowController.rebuildRubricAndSummaryText(
                canonicalSecond,
                previousSecond,
                editor
        );
        String twice = (String) invokeMethod(
                controller,
                "normalizeForLegacyEditorView",
                new Class<?>[] {String.class},
                rebuiltSecond
        );
        twice = (String) invokeMethod(
                controller,
                "normalizeForLegacyEditorView",
                new Class<?>[] {String.class},
                twice
        );

        assertEquals(once.replaceAll(" +", " "), twice.replaceAll(" +", " "));
    }

    @Test
    public void normalizeForLegacyEditorView_keepsRubricNormalizationOutOfSourceAndTestSections()
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        String input = """
                # Lab Assignment 7 - Program Stack

                >> | Earned | Possible | Criteria                                          |
                >> | ------ | -------- | ------------------------------------------------- |
                >> |      0 |       15 | CheckStyle Errors |
                >> |     10 |       10 | Intermediate Commits |
                >> |     60 |       60 | Code Implementation and Structure |
                >> |  11.60 |       15 | Unit Tests |
                >> |  81.60 |      100 | TOTAL |

                >
                >
                > # Feedback
                > * Nice work

                ## Source Code

                ### IntStack.java

                ```java
                String headerAndFooter =
                        \"\"\"
                                |          |
                                |----------|
                                +----------+
                                \"\"\";
                ```

                ## Failed Unit Tests

                ```
                - **ReturnFromMethodTests.returnFromMethodEmptiesSingleFrame()** — expected: <| | |----------| +----------+ > but was: <null>
                ```
                """;

        String normalized = (String) invokeMethod(
                controller,
                "normalizeForLegacyEditorView",
                new Class<?>[] {String.class},
                input
        );

        int rubricIndex = normalized.indexOf(">> | Earned | Possible | Criteria");
        int sourceIndex = normalized.indexOf("## Source Code");
        int boxLineIndex = normalized.indexOf("|----------|");
        int failedTestsIndex = normalized.indexOf("## Failed Unit Tests");

        assertTrue(rubricIndex >= 0);
        assertTrue(sourceIndex >= 0);
        assertTrue(failedTestsIndex >= 0);
        assertTrue(rubricIndex < sourceIndex);
        assertTrue(boxLineIndex > sourceIndex);
        assertTrue(normalized.contains("expected: <| | |----------| +----------+ >"));
        assertFalse(normalized.contains(">> |  81.60 |      100 | TOTAL |"));
    }

    @Test
    public void loadInitialMarkdownForStudent_usesRepoReportAndEnsuresPatchSections(@TempDir Path tmp)
            throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path repoDir = tmp.resolve("repo-existing");
        Files.createDirectories(repoDir);
        Path mappingFile = tmp.resolve("mappings.json");
        writeMappingFile(mappingFile, Map.of("pkg1", repoDir.toString()));

        setField(controller, "assignmentId", "A1");
        setField(controller, "mappingsPath", mappingFile);
        setField(controller, "rootPath", tmp);
        setField(controller, "assignment", buildSingleRubricAssignment());
        setField(controller, "assignmentsFile", buildSingleRubricAssignmentsFile());
        setField(
                controller,
                "gradingReportEditorService",
                new GradingReportEditorService(
                        buildSingleRubricAssignment(),
                        buildSingleRubricAssignmentsFile()
                )
        );

        GradingDraftService draftService = new GradingDraftService(new ReportHtmlWrapper());
        draftService.saveReportMarkdown("A1", "pkg1", repoDir, "# Existing\n\n> # Feedback\n> * hi");

        String loaded = (String) invokeMethod(
                controller,
                "loadInitialMarkdownForStudent",
                new Class<?>[] {String.class},
                "pkg1"
        );

        assertTrue(loaded.contains("# Existing"));
        assertTrue(loaded.contains("<!-- RUBRIC_TABLE_BEGIN -->"));
        assertTrue(loaded.contains("<!-- COMMENTS_SUMMARY_BEGIN -->"));
    }

    @Test
    public void loadInitialMarkdownForStudent_fallsBackToFreshSkeletonWhenReportMissing(
            @TempDir Path tmp
    ) throws Exception {
        GradingWindowController controller = new GradingWindowController();
        Path repoDir = tmp.resolve("repo-empty");
        Files.createDirectories(repoDir);
        Path mappingFile = tmp.resolve("mappings.json");
        writeMappingFile(mappingFile, Map.of("pkg1", repoDir.toString()));

        setField(controller, "assignmentId", "A1");
        setField(controller, "mappingsPath", mappingFile);
        setField(controller, "rootPath", tmp);
        setField(controller, "assignment", buildSingleRubricAssignment());
        setField(controller, "assignmentsFile", buildSingleRubricAssignmentsFile());
        setField(
                controller,
                "gradingReportEditorService",
                new GradingReportEditorService(
                        buildSingleRubricAssignment(),
                        buildSingleRubricAssignmentsFile()
                )
        );

        String loaded = (String) invokeMethod(
                controller,
                "loadInitialMarkdownForStudent",
                new Class<?>[] {String.class},
                "pkg1"
        );

        assertTrue(loaded.contains("_No report found for pkg1._"));
        assertTrue(loaded.contains("<!-- RUBRIC_TABLE_BEGIN -->"));
        assertTrue(loaded.contains("<!-- COMMENTS_SUMMARY_BEGIN -->"));
    }

    @Test
    public void previewHelpers_buildExpectedTitleAndFileName() {
        assertEquals("A1pkg1", GradingWindowController.previewTitle("A1", "pkg1"));
        assertEquals("pkg1", GradingWindowController.previewTitle(null, "pkg1"));
        assertEquals("A1", GradingWindowController.previewTitle("A1", null));
        assertEquals("A1pkg1_preview.html", GradingWindowController.previewFileName("A1pkg1"));
    }

    @Test
    public void editorNavigationHelpers_computeHomeEndAndPageMoves() throws Exception {
        GradingWindowController controller = new GradingWindowController();
        String text = String.join(
                System.lineSeparator(),
                "alpha",
                "beta",
                "gamma",
                "delta",
                "epsilon"
        );

        int lineStart = (int) invokeMethod(
                controller,
                "lineStartOffset",
                new Class<?>[] {String.class, int.class},
                text,
                8
        );
        int lineEnd = (int) invokeMethod(
                controller,
                "lineEndOffset",
                new Class<?>[] {String.class, int.class},
                text,
                8
        );

        assertEquals(6, lineStart);
        assertEquals(10, lineEnd);

        int pageUp = (int) invokeMethod(
                controller,
                "moveCaretByLines",
                new Class<?>[] {String.class, int.class, int.class},
                text,
                text.length(),
                -30
        );
        int pageDown = (int) invokeMethod(
                controller,
                "moveCaretByLines",
                new Class<?>[] {String.class, int.class, int.class},
                text,
                0,
                30
        );

        assertEquals(5, pageUp);
        assertEquals(text.length(), pageDown);
    }

    @Test
    public void sanitizePreviewMarkdown_escapesPipeAndAngleContentInsideFences_only() {
        String markdown = String.join(
                System.lineSeparator(),
                "# Title",
                "",
                ">> | Earned | Possible | Criteria |",
                ">> | ------ | -------- | -------- |",
                "",
                "```java",
                "String box = \"\"\"",
                "        |          |",
                "        |----------|",
                "        +----------+",
                "        \"\"\";",
                "```",
                "",
                "Outside | stays | plain |"
        );

        String sanitized = GradingWindowController.sanitizePreviewMarkdown(markdown);

        assertTrue(sanitized.contains(">> | Earned | Possible | Criteria |"));
        assertTrue(sanitized.contains("Outside | stays | plain |"));
        assertTrue(sanitized.contains("&#124;----------&#124;"));
        assertTrue(sanitized.contains("&quot;") || sanitized.contains("\"\"\""));
        assertTrue(sanitized.contains("```java"));
        assertTrue(sanitized.contains("```"));
    }

    @Test
    public void sanitizePreviewMarkdown_handlesTildeFences() {
        String markdown = String.join(
                System.lineSeparator(),
                "~~~",
                "|----------|",
                "~~~"
        );

        String sanitized = GradingWindowController.sanitizePreviewMarkdown(markdown);

        assertTrue(sanitized.contains("~~~"));
        assertTrue(sanitized.contains("&#124;----------&#124;"));
    }

    @Test
    public void loadComments_invalidJson_fallsBackToEmptyLibrary(@TempDir Path tmp)
            throws Exception {
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            Path commentsPath = tmp.resolve(".gh-classroom-utils")
                    .resolve("comments")
                    .resolve("comments.json");
            Files.createDirectories(commentsPath.getParent());
            Files.writeString(commentsPath, "{invalid");

            GradingWindowController controller = new GradingWindowController();
            invokeMethod(controller, "loadComments");

            Field libraryField = controller.getClass().getDeclaredField("commentLibrary");
            libraryField.setAccessible(true);
            Object library = libraryField.get(controller);
            Method getComments = library.getClass().getDeclaredMethod("getComments");
            @SuppressWarnings("unchecked")
            List<Object> comments = (List<Object>) getComments.invoke(library);
            assertEquals(0, comments.size());
        } finally {
            if (originalHome != null) {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    @Test
    public void loadComments_validJson_loadsCommentLibrary(@TempDir Path tmp)
            throws Exception {
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            Path commentsPath = tmp.resolve(".gh-classroom-utils")
                    .resolve("comments")
                    .resolve("comments.json");
            Files.createDirectories(commentsPath.getParent());
            Files.writeString(
                    commentsPath,
                    """
                    {
                      "schemaVersion": 1,
                      "comments": [
                        {
                          "commentId": "c1",
                          "assignmentKey": "A1",
                          "rubricItemId": "ri_impl",
                          "title": "T",
                          "bodyMarkdown": "B",
                          "pointsDeducted": 1
                        }
                      ]
                    }
                    """
            );

            GradingWindowController controller = new GradingWindowController();
            invokeMethod(controller, "loadComments");

            Field libraryField = controller.getClass().getDeclaredField("commentLibrary");
            libraryField.setAccessible(true);
            Object library = libraryField.get(controller);
            Method getComments = library.getClass().getDeclaredMethod("getComments");
            @SuppressWarnings("unchecked")
            List<Object> comments = (List<Object>) getComments.invoke(library);
            assertEquals(1, comments.size());
        } finally {
            if (originalHome != null) {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    @Test
    public void loadComments_usesLegacyPathWhenCanonicalMissing(@TempDir Path tmp)
            throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty("user.home", tmp.toString());
            System.setProperty("os.name", "Mac OS X");
            Path legacyCommentsPath = tmp.resolve("Library")
                    .resolve("Application Support")
                    .resolve("GHCU2")
                    .resolve("comments.json");
            Files.createDirectories(legacyCommentsPath.getParent());
            Files.writeString(
                    legacyCommentsPath,
                    """
                    {
                      "schemaVersion": 1,
                      "comments": [
                        {
                          "commentId": "cLegacy",
                          "assignmentKey": "A1",
                          "rubricItemId": "ri_impl",
                          "title": "Legacy",
                          "bodyMarkdown": "From legacy path",
                          "pointsDeducted": 1
                        }
                      ]
                    }
                    """
            );

            GradingWindowController controller = new GradingWindowController();
            invokeMethod(controller, "loadComments");

            Field libraryField = controller.getClass().getDeclaredField("commentLibrary");
            libraryField.setAccessible(true);
            Object library = libraryField.get(controller);
            Method getComments = library.getClass().getDeclaredMethod("getComments");
            @SuppressWarnings("unchecked")
            List<Object> comments = (List<Object>) getComments.invoke(library);
            assertEquals(1, comments.size());
        } finally {
            if (originalHome != null) {
                System.setProperty("user.home", originalHome);
            }
            if (originalOs != null) {
                System.setProperty("os.name", originalOs);
            }
        }
    }

    @Test
    public void computePointsLostInTextForRubric_sumsValidAndIgnoresMalformedLines() {
        String text = """
                > * -2 points (ri_impl)
                > * -3 points (ri_impl)
                > * -x points (ri_impl)
                > * -1 points (ri_other)
                plain line
                """;

        int lost = GradingWindowController.computePointsLostInTextForRubric(text, "ri_impl");
        assertEquals(5, lost);
    }

    @Test
    public void findRepoDirForStudentPackage_reconstructsUsingReportFilePrefix(@TempDir Path tmp)
            throws Exception {
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());

            Path root = tmp.resolve("root");
            Path reposContainer = root.resolve("class-submissions");
            Path repoDir = reposContainer.resolve("student-repo-1");
            Files.createDirectories(repoDir);
            Files.writeString(repoDir.resolve("A1pkg1.html"), "feedback");

            GradingWindowController controller = new GradingWindowController();
            setField(controller, "mappingsPath", null);
            setField(controller, "assignmentId", "CSC1120A1");
            setField(controller, "reportFilePrefix", "A1");
            setField(controller, "rootPath", root);

            Path resolved = (Path) invokeMethod(
                    controller,
                    "findRepoDirForStudentPackage",
                    new Class<?>[] {String.class},
                    "pkg1"
            );

            assertEquals(repoDir, resolved);
            Path expectedMappingsFile = tmp.resolve(".gh-classroom-utils")
                    .resolve("mappings")
                    .resolve("mappings-A1.json");
            assertTrue(Files.exists(expectedMappingsFile));
        } finally {
            if (originalHome != null) {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    @Test
    public void getMaxPointsForRubricItem_returnsAssignmentRubricPoints() throws Exception {
        GradingWindowController controller = new GradingWindowController();
        setField(controller, "assignment", buildSingleRubricAssignment());

        int max = (int) invokeMethod(
                controller,
                "getMaxPointsForRubricItem",
                new Class<?>[] {String.class},
                "ri_impl"
        );
        int missing = (int) invokeMethod(
                controller,
                "getMaxPointsForRubricItem",
                new Class<?>[] {String.class},
                "ri_missing"
        );

        assertEquals(10, max);
        assertEquals(0, missing);
    }

    @Test
    public void handleSaveDraftResult_clearsInProgressFlag() throws Exception {
        GradingWindowController controller = new GradingWindowController();
        setField(controller, "saveInProgress", true);
        Object result = newSaveDraftResult(false, "failed");

        invokeMethod(
                controller,
                "handleSaveDraftResult",
                new Class<?>[] {result.getClass(), Runnable.class},
                result,
                null
        );

        Field saveInProgressField = controller.getClass().getDeclaredField("saveInProgress");
        saveInProgressField.setAccessible(true);
        assertFalse((boolean) saveInProgressField.get(controller));
    }

    private Path createRepoWithCommit(Path repoDir) throws Exception {
        Files.createDirectories(repoDir);
        runGit(repoDir, "init");
        runGit(repoDir, "config", "user.email", "test@example.com");
        runGit(repoDir, "config", "user.name", "Test User");
        Files.writeString(repoDir.resolve("README.md"), "init");
        runGit(repoDir, "add", "README.md");
        runGit(repoDir, "commit", "-m", "init");
        runGit(repoDir, "branch", "-M", "main");
        return repoDir;
    }

    private Path createRepoWithUpstream(Path tmp) throws Exception {
        Path remoteDir = tmp.resolve("remote.git");
        Path repoDir = tmp.resolve("repo-with-upstream");

        Files.createDirectories(remoteDir);
        runGit(remoteDir, "init", "--bare");

        createRepoWithCommit(repoDir);
        runGit(repoDir, "remote", "add", "origin", remoteDir.toString());
        runGit(repoDir, "push", "-u", "origin", "main");

        return repoDir;
    }

    private void runGit(Path workingDir,
                        String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(args));
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
    }

    private void writeMappingFile(Path mappingFile,
                                  Map<String, String> mappings) throws Exception {
        Map<String, Map<String, String>> json = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            json.put(entry.getKey(), Map.of("repoPath", entry.getValue()));
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(mappingFile.toFile(), json);
    }

    private Object newSaveDraftResult(boolean success,
                                      String message) throws Exception {
        Class<?> saveDraftResultClass =
                Class.forName("ui.GradingWindowController$SaveDraftResult");
        Constructor<?> ctor =
                saveDraftResultClass.getDeclaredConstructor(boolean.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(success, message);
    }

    @SuppressWarnings("unchecked")
    private void addStudentPackage(GradingWindowController controller,
                                   String studentPackage) throws Exception {
        Field field = controller.getClass().getDeclaredField("studentPackages");
        field.setAccessible(true);
        Object packages = field.get(controller);
        Method addMethod = packages.getClass().getMethod("add", Object.class);
        addMethod.invoke(packages, studentPackage);
    }

    private List<String> readStudentPackages(GradingWindowController controller)
            throws Exception {
        Field field = controller.getClass().getDeclaredField("studentPackages");
        field.setAccessible(true);
        Object packages = field.get(controller);

        List<String> values = new ArrayList<>();
        Method iteratorMethod = packages.getClass().getMethod("iterator");
        @SuppressWarnings("unchecked")
        java.util.Iterator<String> iterator =
                (java.util.Iterator<String>) iteratorMethod.invoke(packages);

        while (iterator.hasNext()) {
            values.add(iterator.next());
        }

        return values;
    }

    @SuppressWarnings("unchecked")
    private void putDraft(GradingWindowController controller,
                          String studentPackage,
                          String markdown,
                          boolean loadedFromDisk) throws Exception {
        Field sessionField = controller.getClass().getDeclaredField("draftSessionService");
        sessionField.setAccessible(true);
        Object session = sessionField.get(controller);

        Method saveEditorState = session.getClass().getDeclaredMethod(
                "saveEditorState",
                String.class,
                String.class,
                int.class
        );
        saveEditorState.setAccessible(true);
        saveEditorState.invoke(session, studentPackage, markdown, 0);

        Method setLoadedFromDisk = session.getClass().getDeclaredMethod(
                "setLoadedFromDisk",
                String.class,
                boolean.class
        );
        setLoadedFromDisk.setAccessible(true);
        setLoadedFromDisk.invoke(session, studentPackage, loadedFromDisk);
    }

    private List<String> buildCommand(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }

    private Assignment buildSingleRubricAssignment() {
        Assignment assignment = new Assignment();
        assignment.setCourseCode("CSC");
        assignment.setAssignmentCode("A1");
        assignment.setAssignmentName("Assignment");

        RubricItemRef ref = new RubricItemRef();
        ref.setRubricItemId("ri_impl");
        ref.setPoints(10);
        Assignment.Rubric rubric = new Assignment.Rubric();
        rubric.setItems(List.of(ref));
        assignment.setRubric(rubric);
        return assignment;
    }

    private AssignmentsFile buildSingleRubricAssignmentsFile() {
        RubricItemDef def = new RubricItemDef();
        def.setId("ri_impl");
        def.setName("Implementation");
        AssignmentsFile file = new AssignmentsFile();
        HashMap<String, RubricItemDef> library = new HashMap<>();
        library.put("ri_impl", def);
        file.setRubricItemLibrary(library);
        return file;
    }

    private void setField(Object target,
                          String fieldName,
                          Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object invokeMethod(Object target,
                                String methodName,
                                Class<?>[] parameterTypes,
                                Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Object invokeMethod(Object target,
                                String methodName) throws Exception {
        return invokeMethod(target, methodName, new Class<?>[0]);
    }

    private boolean readBoolean(Object target,
                                String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (boolean) method.invoke(target);
    }

    private int readIntField(Object target,
                             String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (int) field.get(target);
    }

    private String readString(Object target,
                              String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (String) method.invoke(target);
    }

    private String readStringField(Object target,
                                   String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(target);
    }
}

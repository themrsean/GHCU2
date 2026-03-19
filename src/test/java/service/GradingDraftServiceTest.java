package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for service.GradingDraftService
 *
 * Verified production symbols used:
 * - src/main/java/service/GradingDraftService.java : public Map<String, Integer> loadManualDeductionsFromGradingDraft(String, String, Path)
 * - src/main/java/model/Comments.java : public static java.util.List<ParsedComment> parseInjectedComments(String)
 */
public class GradingDraftServiceTest {

    @Test
    public void loadManualDeductions_emptyWhenNoFile(@TempDir Path tmp) {
        GradingDraftService svc = new GradingDraftService();

        Map<String, Integer> result = svc.loadManualDeductionsFromGradingDraft("A1", "smith", tmp);

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Expected empty map when grading draft file does not exist");
    }

    @Test
    public void loadManualDeductions_parsesAndSumsRubricIds(@TempDir Path tmp) throws Exception {
        // create grading directory and file expected by GradingDraftService
        Path gradingDir = tmp.resolve("grading");
        Files.createDirectories(gradingDir);

        // filename is assignmentId + studentPackage + .md
        Path draft = gradingDir.resolve("A1smith.md");

        // Compose markdown with three comments:
        // - one for ri_impl with -10 points
        // - one for ri_extra with -5 points
        // - another for ri_impl with a negative parsed value ("- -5 points") which should be clamped to 0
        String md = String.join(System.lineSeparator(),
                "<a id=\"cmt_1\"></a>",
                "> #### Missing semicolon",
                "> * -10 points (ri_impl)",
                "",
                "<a id=\"cmt_2\"></a>",
                "> #### Wrong variable",
                "> * -5 points (ri_extra)",
                "",
                "<a id=\"cmt_3\"></a>",
                "> #### Weird negative",
                "> * - -5 points (ri_impl)"
        );

        Files.writeString(draft, md, StandardCharsets.UTF_8);

        GradingDraftService svc = new GradingDraftService();

        Map<String, Integer> result = svc.loadManualDeductionsFromGradingDraft("A1", "smith", tmp);

        assertNotNull(result);
        // ri_impl: first contributes 10, second negative -5 is clamped to 0 -> total 10
        assertEquals(10, result.getOrDefault("ri_impl", -1).intValue());
        // ri_extra: contributes 5
        assertEquals(5, result.getOrDefault("ri_extra", -1).intValue());
        // no other keys
        assertEquals(2, result.size());
    }
}

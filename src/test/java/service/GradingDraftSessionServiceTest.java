package service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GradingDraftSessionServiceTest {

    @Test
    public void saveEditorState_persistsMarkdownCaretAndLoadedFlag() {
        GradingDraftSessionService service = new GradingDraftSessionService();

        service.saveEditorState("pkg1", "# Feedback", 12);

        assertTrue(service.isLoadedFromDisk("pkg1"));
        assertEquals("# Feedback", service.getMarkdown("pkg1"));
        assertEquals(12, service.getCaretPosition("pkg1"));
    }

    @Test
    public void saveEditorState_withSelection_persistsSelectionBounds() {
        GradingDraftSessionService service = new GradingDraftSessionService();

        service.saveEditorState("pkg1", "# Feedback", 3, 9);

        assertEquals(3, service.getSelectionStart("pkg1"));
        assertEquals(9, service.getSelectionEnd("pkg1"));
        assertEquals(9, service.getCaretPosition("pkg1"));
    }

    @Test
    public void updateFromEditorIfPresent_updatesExistingDraftOnly() {
        GradingDraftSessionService service = new GradingDraftSessionService();
        service.saveEditorState("pkg1", "initial", 1);

        service.updateFromEditorIfPresent("pkg1", "updated", 5);
        service.updateFromEditorIfPresent("pkg2", "ignored", 7);

        assertEquals("updated", service.getMarkdown("pkg1"));
        assertEquals(5, service.getCaretPosition("pkg1"));
        assertFalse(service.isLoadedFromDisk("pkg2"));
        assertEquals("", service.getMarkdown("pkg2"));
    }

    @Test
    public void updateSelectionIfPresent_ignoresMissingDraft_andNormalizesOrder() {
        GradingDraftSessionService service = new GradingDraftSessionService();
        service.saveEditorState("pkg1", "initial", 0);

        service.updateSelectionIfPresent("pkg1", 8, 2);
        service.updateSelectionIfPresent("pkg2", 5, 7);

        assertEquals(2, service.getSelectionStart("pkg1"));
        assertEquals(8, service.getSelectionEnd("pkg1"));
        assertFalse(service.isLoadedFromDisk("pkg2"));
    }

    @Test
    public void needsReload_trueUntilLoadedMarkdownIsNonBlank() {
        GradingDraftSessionService service = new GradingDraftSessionService();

        assertTrue(service.needsReload("pkg1"));
        service.setLoadedFromDisk("pkg1", true);
        assertTrue(service.needsReload("pkg1"));
        service.setMarkdown("pkg1", " ");
        assertTrue(service.needsReload("pkg1"));
        service.setMarkdown("pkg1", "# Existing");
        assertFalse(service.needsReload("pkg1"));
    }

    @Test
    public void caretPosition_isClampedToZeroWhenNegative() {
        GradingDraftSessionService service = new GradingDraftSessionService();

        service.setCaretPosition("pkg1", -10);

        assertEquals(0, service.getCaretPosition("pkg1"));
    }
}

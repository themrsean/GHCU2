/*
 * Course: CSC-1120
 * GitHub Classroom Utilities
 */
package service;

import java.util.HashMap;
import java.util.Map;

public class GradingDraftSessionService {

    private final Map<String, DraftState> draftsByStudent = new HashMap<>();

    public void updateCaretIfPresent(String studentPackage,
                                     int caretPosition) {
        DraftState draft = draftsByStudent.get(studentPackage);
        if (draft != null) {
            draft.setCaretPosition(caretPosition);
        }
    }

    public void updateMarkdownIfPresent(String studentPackage,
                                        String markdown) {
        DraftState draft = draftsByStudent.get(studentPackage);
        if (draft != null) {
            draft.setMarkdown(markdown);
        }
    }

    public void saveEditorState(String studentPackage,
                                String markdown,
                                int caretPosition) {
        DraftState draft = draftFor(studentPackage);
        draft.setMarkdown(markdown);
        draft.setCaretPosition(caretPosition);
        draft.setLoadedFromDisk(true);
    }

    public boolean needsReload(String studentPackage) {
        DraftState draft = draftFor(studentPackage);
        return !draft.isLoadedFromDisk()
                || draft.getMarkdown() == null
                || draft.getMarkdown().trim().isEmpty();
    }

    public String getMarkdown(String studentPackage) {
        return draftFor(studentPackage).getMarkdown();
    }

    public void setMarkdown(String studentPackage,
                            String markdown) {
        draftFor(studentPackage).setMarkdown(markdown);
    }

    public int getCaretPosition(String studentPackage) {
        return draftFor(studentPackage).getCaretPosition();
    }

    public void setCaretPosition(String studentPackage,
                                 int caretPosition) {
        draftFor(studentPackage).setCaretPosition(caretPosition);
    }

    public void updateFromEditorIfPresent(String studentPackage,
                                          String markdown,
                                          int caretPosition) {
        DraftState draft = draftsByStudent.get(studentPackage);
        if (draft != null) {
            draft.setCaretPosition(caretPosition);
            draft.setMarkdown(markdown);
            draft.setLoadedFromDisk(true);
        }
    }

    public boolean isLoadedFromDisk(String studentPackage) {
        return draftFor(studentPackage).isLoadedFromDisk();
    }

    public void setLoadedFromDisk(String studentPackage,
                                  boolean loadedFromDisk) {
        draftFor(studentPackage).setLoadedFromDisk(loadedFromDisk);
    }

    private DraftState draftFor(String studentPackage) {
        return draftsByStudent.computeIfAbsent(studentPackage, _ -> new DraftState());
    }

    private static class DraftState {
        private String markdown = "";
        private int caretPosition = 0;
        private boolean loadedFromDisk = false;

        public String getMarkdown() {
            return markdown;
        }

        public void setMarkdown(String markdown) {
            this.markdown = markdown == null ? "" : markdown;
        }

        public int getCaretPosition() {
            return caretPosition;
        }

        public void setCaretPosition(int caretPosition) {
            this.caretPosition = Math.max(0, caretPosition);
        }

        public boolean isLoadedFromDisk() {
            return loadedFromDisk;
        }

        public void setLoadedFromDisk(boolean loadedFromDisk) {
            this.loadedFromDisk = loadedFromDisk;
        }
    }
}

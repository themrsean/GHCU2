/*
 * Course: CSC-1120
 * Assignment name
 * File name
 * Name: Sean Jones
 * Last Updated:
 */
package service;

import model.Comments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GradingDraftService {
    public Map<String, Integer> loadManualDeductionsFromGradingDraft(String assignmentId,
                                                                      String studentPackage,
                                                                      Path rootPath) {
        Path draft = rootPath.resolve("grading").resolve(assignmentId + studentPackage + ".md");
        if (!Files.exists(draft) || !Files.isRegularFile(draft)) {
            return Map.of();
        }
        try {
            String md = Files.readString(draft, StandardCharsets.UTF_8);
            List<Comments.ParsedComment> parsed = Comments.parseInjectedComments(md);
            Map<String, Integer> lostByRubric = new HashMap<>();
            for (Comments.ParsedComment c : parsed) {
                if (c != null) {
                    String id = c.rubricItemId();
                    if (id != null && !id.isBlank()) {
                        int lost = Math.max(0, c.pointsLost());
                        lostByRubric.put(id, lostByRubric.getOrDefault(id, 0) + lost);
                    }
                }
            }
            return lostByRubric;
        } catch (IOException e) {
            return Map.of();
        }
    }
}

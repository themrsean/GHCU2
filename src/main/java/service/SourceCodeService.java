/*
 * Course: CSC-1120
 * Assignment name
 * File name
 * Name: Sean Jones
 * Last Updated:
 */
package service;

import model.Assignment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SourceCodeService {
    public String buildSourceCodeMarkdown(Assignment assignment,
                                           String studentPackage,
                                           Path repoPath) {
        StringBuilder sb = new StringBuilder();

        List<String> expected = assignment.getExpectedFiles();
        if (expected == null || expected.isEmpty()) {
            sb.append("_No expected files configured._").append(System.lineSeparator());
        } else {
            for (String rel : expected) {
                String filename = Path.of(rel).getFileName().toString();
                Path filePath = repoPath.resolve("src").resolve(studentPackage).resolve(filename);

                sb.append("### ").append(filename).append(System.lineSeparator());

                sb.append(System.lineSeparator());

                if (!Files.exists(filePath)) {
                    sb.append("_Missing file._").append(System.lineSeparator());
                    sb.append(System.lineSeparator());
                } else {
                    String lang = languageForFile(filePath);
                    sb.append("```").append(lang).append(System.lineSeparator());

                    try {
                        String code = Files.readString(filePath);
                        sb.append(code).append(System.lineSeparator());
                    } catch (IOException e) {
                        sb.append("// Failed to read file: ").append(e.getMessage())
                                .append(System.lineSeparator());
                    }

                    sb.append("```").append(System.lineSeparator());
                    sb.append(System.lineSeparator());
                }
            }
        }

        return sb.toString();
    }

    private String languageForFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        String lang = "";
        if (name.endsWith(".java")) {
            lang = "java";
        } else if (name.endsWith(".fxml")) {
            lang = "xml";
        }
        return lang;
    }



}

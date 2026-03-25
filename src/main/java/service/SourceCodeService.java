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
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SourceCodeService {
    private static final String STUDENT_PACKAGE_PLACEHOLDER = "{studentPackage}";

    public String buildSourceCodeMarkdown(Assignment assignment,
                                          String studentPackage,
                                          Path repoPath) {
        StringBuilder sb = new StringBuilder();

        List<String> expected = assignment.getExpectedFiles();
        if (expected == null || expected.isEmpty()) {
            sb.append("_No expected files configured._").append(System.lineSeparator());
        } else {
            Set<Path> emittedPaths = new LinkedHashSet<>();
            for (String rel : expected) {
                String filename = resolveDisplayFileName(rel);
                Path filePath = resolveExpectedFilePath(repoPath, studentPackage, rel);
                Path dedupeKey = filePath.normalize().toAbsolutePath();

                boolean alreadyEmitted = Files.exists(filePath) && emittedPaths.contains(dedupeKey);
                if (!alreadyEmitted) {
                    sb.append("### ").append(filename).append(System.lineSeparator());

                    sb.append(System.lineSeparator());

                    if (!Files.exists(filePath)) {
                        sb.append("_Missing file._").append(System.lineSeparator());
                        sb.append(System.lineSeparator());
                    } else {
                        String lang = languageForFile(filePath);
                        String code = "";
                        String readError = null;
                        try {
                            code = Files.readString(filePath);
                        } catch (IOException e) {
                            readError = e.getMessage();
                        }

                        if (readError != null) {
                            sb.append("// Failed to read file: ")
                                    .append(readError)
                                    .append(System.lineSeparator());
                            sb.append(System.lineSeparator());
                        } else {
                            emittedPaths.add(dedupeKey);
                            String fence = buildFenceForContent(code);
                            sb.append(fence).append(lang).append(System.lineSeparator());

                            sb.append(code).append(System.lineSeparator());

                            sb.append(fence).append(System.lineSeparator());
                            sb.append(System.lineSeparator());
                        }
                    }
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

    private Path resolveExpectedFilePath(Path repoPath,
                                         String studentPackage,
                                         String expectedPath) {
        Path configuredPath = resolveConfiguredPath(repoPath, studentPackage, expectedPath);
        if (Files.exists(configuredPath)) {
            return configuredPath;
        }

        Path baseNamePath = resolveLegacyBaseNamePath(repoPath, studentPackage, expectedPath);
        if (Files.exists(baseNamePath)) {
            return baseNamePath;
        }

        return configuredPath;
    }

    private Path resolveConfiguredPath(Path repoPath,
                                       String studentPackage,
                                       String expectedPath) {
        String raw = expectedPath == null ? "" : expectedPath.trim();
        String normalized = raw.replace("\\", "/");
        String packageName = studentPackage == null ? "" : studentPackage.trim();
        String withPackage = normalized.replace(STUDENT_PACKAGE_PLACEHOLDER, packageName);

        try {
            Path candidate = Path.of(withPackage);
            if (candidate.isAbsolute()) {
                return candidate.normalize();
            }
            return repoPath.resolve(candidate).normalize();
        } catch (InvalidPathException e) {
            return repoPath.resolve(withPackage).normalize();
        }
    }

    private Path resolveLegacyBaseNamePath(Path repoPath,
                                           String studentPackage,
                                           String expectedPath) {
        String fileName = resolveDisplayFileName(expectedPath);
        String packageName = studentPackage == null ? "" : studentPackage.trim();
        return repoPath.resolve("src").resolve(packageName).resolve(fileName).normalize();
    }

    private String resolveDisplayFileName(String expectedPath) {
        String safe = expectedPath == null ? "" : expectedPath.trim();
        try {
            Path asPath = Path.of(safe);
            Path fileName = asPath.getFileName();
            if (fileName != null) {
                return fileName.toString();
            }
        } catch (InvalidPathException e) {
            // Fall through and use raw text.
        }
        return safe;
    }

    private String buildFenceForContent(String content) {
        int longestRun = longestBacktickRun(content);
        int minFenceLength = 3;
        int fenceLength = Math.max(minFenceLength, longestRun + 1);
        return "`".repeat(fenceLength);
    }

    private int longestBacktickRun(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        int best = 0;
        int current = 0;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '`') {
                current++;
                best = Math.max(best, current);
            } else {
                current = 0;
            }
        }
        return best;
    }

}

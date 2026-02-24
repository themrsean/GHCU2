/*
 * Course: CSC-1110/1120
 * GitHub Classroom Utilities
 */
package service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;
import java.util.stream.Stream;

public class CheckstyleService {

    private final ProcessRunner processRunner;
    private final ServiceLogger logger;
    private final Path checkstyleJar;

    public CheckstyleService(ProcessRunner processRunner,
                             ServiceLogger logger,
                             Path checkstyleJar) {

        this.processRunner = Objects.requireNonNull(processRunner);
        this.logger = Objects.requireNonNull(logger);
        this.checkstyleJar = Objects.requireNonNull(checkstyleJar);
    }

    public CheckstyleResult buildCheckstyleResult(Path repoPath,
                                                  Path selectedRootPath,
                                                  boolean checkstyleEnabled,
                                                  boolean missingCheckstyleRubricItem,
                                                  String checkstyleUrl) {

        final int zeroViolations = 0;

        if (!checkstyleEnabled) {
            return new CheckstyleResult("_Checkstyle disabled._", zeroViolations);
        }

        if (missingCheckstyleRubricItem) {
            return new CheckstyleResult("_No checkstyle rubric item for this assignment._",
                    zeroViolations);
        }

        String url = "";
        if (checkstyleUrl != null) {
            url = checkstyleUrl.trim();
        }

        if (url.isEmpty()) {
            return new CheckstyleResult("_Checkstyle enabled but URL is blank._",
                    zeroViolations);
        }

        if (!Files.exists(checkstyleJar)) {
            return new CheckstyleResult("_Missing checkstyle jar in program folder._",
                    zeroViolations);
        }

        if (repoPath == null) {
            return new CheckstyleResult("_Checkstyle failed: repoPath is null._",
                    zeroViolations);
        }

        if (selectedRootPath == null) {
            return new CheckstyleResult("_Checkstyle failed: selectedRootPath is null._",
                    zeroViolations);
        }

        try {
            Path configFile = downloadCheckstyleConfig(selectedRootPath, url);
            List<Path> javaFiles = findJavaFiles(repoPath.resolve("src"));

            if (javaFiles.isEmpty()) {
                return new CheckstyleResult("_No Java files found under src/._",
                        zeroViolations);
            }

            List<String> args = buildCheckstyleArgs(configFile, javaFiles);

            ProcessResult result = processRunner.runCaptureLinesWithExitCode(args, repoPath);

            if (result.exitCode() < 0) {
                return new CheckstyleResult(buildExecutionFailedMarkdown(result.outputLines()),
                        zeroViolations);
            }

            CheckstyleSummary summary = parseCheckstyleOutput(result.outputLines());
            int violations = summary.getTotalViolations();

            if (violations == 0) {
                return new CheckstyleResult("_No checkstyle violations._",
                        zeroViolations);
            }

            return new CheckstyleResult(buildViolationsMarkdown(summary, violations),
                    violations);

        } catch (IOException e) {
            return new CheckstyleResult("_Checkstyle failed: " + e.getMessage() + "_",
                    zeroViolations);
        }
    }

    private List<String> buildCheckstyleArgs(Path configFile, List<Path> javaFiles) {
        List<String> args = new ArrayList<>();
        args.add("java");
        args.add("-jar");
        args.add(checkstyleJar.toAbsolutePath().toString());
        args.add("-c");
        args.add(configFile.toAbsolutePath().toString());

        for (Path f : javaFiles) {
            args.add(f.toAbsolutePath().toString());
        }
        return args;
    }

    private String buildExecutionFailedMarkdown(List<String> outputLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Checkstyle Execution Failed").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("```").append(System.lineSeparator());

        if (outputLines != null) {
            for (String line : outputLines) {
                sb.append(line).append(System.lineSeparator());
            }
        }

        sb.append("```").append(System.lineSeparator());
        return sb.toString();
    }

    private String buildViolationsMarkdown(CheckstyleSummary summary, int violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Total Violations:** ")
                .append(violations)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        for (String file : summary.getFilesInOrder()) {
            List<String> lines = summary.getLinesForFile(file);
            sb.append("### ").append(file).append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("```").append(System.lineSeparator());

            for (String line : lines) {
                sb.append(line).append(System.lineSeparator());
            }

            sb.append("```").append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        return sb.toString();
    }

    private CheckstyleSummary parseCheckstyleOutput(List<String> lines) {
        CheckstyleSummary summary = new CheckstyleSummary();
        if (lines == null) {
            return summary;
        }

        for (String line : lines) {
            if (line == null) {
                continue;
            }

            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }

            final String errorPrefix = "[ERROR]";
            if (t.startsWith(errorPrefix)) {
                t = t.substring(errorPrefix.length()).trim();
            }

            t = t.replace("\\", "/");

            final String srcMarker = "/src/";
            int srcIdx = t.indexOf(srcMarker);
            if (srcIdx >= 0) {
                t = t.substring(srcIdx + srcMarker.length());
            }

            final String javaMarker = ".java:";
            int javaIndex = t.toLowerCase().indexOf(javaMarker);
            if (javaIndex < 0) {
                continue;
            }

            int endOfFile = javaIndex + ".java".length();
            String fileKey = t.substring(0, endOfFile);

            summary.add(fileKey, t);
        }

        return summary;
    }

    private Path downloadCheckstyleConfig(Path selectedRootPath, String url) throws IOException {
        Path packagesRoot = selectedRootPath.resolve("packages");
        if (!Files.exists(packagesRoot)) {
            Files.createDirectories(packagesRoot);
        }

        Path configFile = packagesRoot.resolve("checkstyle.xml");
        Path urlFile = packagesRoot.resolve("checkstyle-url.txt");

        String previousUrl = "";
        if (Files.exists(urlFile)) {
            try {
                previousUrl = Files.readString(urlFile).trim();
            } catch (IOException ignored) {
                previousUrl = "";
            }
        }

        boolean urlChanged = !url.equals(previousUrl);
        if (Files.exists(configFile) && !urlChanged) {
            return configFile;
        }

        final int connectTimeoutSeconds = 10;
        final int requestTimeoutSeconds = 20;
        final int httpOk = 200;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != httpOk) {
                throw new IOException("HTTP " + response.statusCode() + " downloading config.");
            }

            try (InputStream in = response.body()) {
                Files.copy(in, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            Files.writeString(urlFile, url + System.lineSeparator());
            logger.log("Downloaded checkstyle config: " + configFile);

            return configFile;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted.");
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + url);
        }
    }

    private List<Path> findJavaFiles(Path srcRoot) throws IOException {
        List<Path> files = new ArrayList<>();

        if (!Files.exists(srcRoot) || !Files.isDirectory(srcRoot)) {
            return files;
        }

        try (Stream<Path> stream = Files.walk(srcRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".java"))
                    .forEach(files::add);
        }

        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    public record CheckstyleResult(String markdown, int totalViolations) { }

    private static class CheckstyleSummary {
        private final Map<String, List<String>> byFile = new TreeMap<>();
        private int totalViolations = 0;

        public void add(String file, String line) {
            if (!byFile.containsKey(file)) {
                byFile.put(file, new ArrayList<>());
            }
            byFile.get(file).add(line);
            totalViolations++;
        }

        public int getTotalViolations() {
            return totalViolations;
        }

        public List<String> getFilesInOrder() {
            return new ArrayList<>(byFile.keySet());
        }

        public List<String> getLinesForFile(String file) {
            if (!byFile.containsKey(file)) {
                return List.of();
            }
            return byFile.get(file);
        }
    }
}
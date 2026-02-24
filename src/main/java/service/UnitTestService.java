/*
 * Course: CSC-1120
 * Assignment name
 * File name
 * Name: Sean Jones
 * Last Updated:
 */
package service;

import org.jspecify.annotations.NonNull;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class UnitTestService {
    private final ProcessRunner processRunner;
    private final ServiceLogger logger;

    public UnitTestService(ProcessRunner processRunner,
                           ServiceLogger logger) {
        this.processRunner = Objects.requireNonNull(processRunner);
        this.logger = Objects.requireNonNull(logger);
    }

    public UnitTestResult buildUnitTestResultMarkdown(String studentPackage, Path repoPath)
            throws IOException {
        UnitTestContext ctx = buildUnitTestContext(studentPackage, repoPath);
        if (!ctx.isValid()) {
            return ctx.failureResult();
        }
        UnitTestResult compileResult = compileSourceFiles(ctx);
        if (compileResult != null) {
            return compileResult;
        }
        UnitTestResult testCompileResult = compilePatchedTestSuite(ctx);
        if (testCompileResult != null) {
            return testCompileResult;
        }
        return runTestsAndBuildResult(ctx);

    }

    private Path preparePatchedTestSuite(Path testSuiteFile, Path buildDir, String studentPackage)
            throws IOException {

        Path patchedDir = buildDir.resolve("patched-tests");
        ensureDirectoryExists(patchedDir);

        Path patched = patchedDir.resolve("TestSuite.java");

        String src = Files.readString(testSuiteFile);

        // Replace ANY import username.*
        src = src.replaceAll(
                "(?m)^\\s*import\\s+username\\.",
                "import " + studentPackage + "."
        );

        // Replace any fully-qualified username.* reference
        src = src.replaceAll(
                "\\busername\\.",
                studentPackage + "."
        );
        Files.writeString(patched, src);
        return patched;
    }

    private JUnitFailureSummary parseJUnitReports(Path reportsDir) {
        JUnitFailureSummary summary = new JUnitFailureSummary();
        if (!Files.exists(reportsDir)) {
            return summary;
        }
        try (Stream<Path> paths = Files.walk(reportsDir)) {
            List<Path> xmlFiles = paths
                    .filter(p -> p.toString().toLowerCase().endsWith(".xml"))
                    .toList();
            for (Path xml : xmlFiles) {
                parseSingleJUnitXml(xml, summary);
            }
        } catch (IOException ignored) {
            logger.log("Failed to parse reports directory: " + reportsDir);
        }
        return summary;
    }

    private void parseSingleJUnitXml(Path xmlFile, JUnitFailureSummary summary) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(xmlFile.toFile());
            NodeList testcases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testcases.getLength(); i++) {
                summary.incrementTotalTests();
                org.w3c.dom.Node testCase = testcases.item(i);
                NodeList children = testCase.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    org.w3c.dom.Node node = children.item(j);
                    if ("failure".equals(node.getNodeName())
                            || "error".equals(node.getNodeName())) {
                        NamedNodeMap attrs = testCase.getAttributes();
                        String className = attrs.getNamedItem("classname") != null
                                ? attrs.getNamedItem("classname").getNodeValue()
                                : "";
                        String methodName = attrs.getNamedItem("name") != null
                                ? attrs.getNamedItem("name").getNodeValue()
                                : "";
                        String message = getString(node);
                        summary.addFailure(
                                simplifyClassName(className) + "." + methodName,
                                message == null ? "" : message.trim()
                        );
                    }
                }
            }
        } catch (ParserConfigurationException | IOException | SAXException e) {
            logger.log("Failed to parse xml file: " + xmlFile);
            logger.log(e.getMessage());
        }
    }

    private String simplifyClassName(String className) {
        if (className == null || className.isBlank()) {
            return "";
        }
        // Strip package
        int lastDot = className.lastIndexOf('.');
        String s = lastDot >= 0 ? className.substring(lastDot + 1) : className;
        // If it's TestSuite$Something$Something, keep only the last segment
        int lastDollar = s.lastIndexOf('$');
        if (lastDollar >= 0 && lastDollar < s.length() - 1) {
            s = s.substring(lastDollar + 1);
        }
        return s;
    }

    private String escapeForMarkdownHtml(String s) {
        if (s == null) {
            return "";
        }
        // Normalize newlines/spaces
        s = s.replace("\r\n", "\n").replace("\r", "\n");
        s = s.replace("\n", " ");          // collapse multiline assertion output
        s = s.replaceAll("\\s+", " ").trim();

        // Escape HTML special chars (critical for < >)
        s = s.replace("&", "&amp;");
        s = s.replace("<", "&lt;");
        s = s.replace(">", "&gt;");

        return s;
    }

    private UnitTestContext buildUnitTestContext(String studentPackage, Path repoPath)
            throws IOException {
        UnitTestContext ctx = new UnitTestContext(studentPackage, repoPath);
        Path srcDir = repoPath.resolve("src");
        ctx.setSrcDir(srcDir);
        Path actualTestDir = resolveTestDir(repoPath, srcDir);
        ctx.setTestDir(actualTestDir);
        if (actualTestDir == null) {
            ctx.fail("_No test directory found (expected src/test/)._", 0, 0);
        }
        if (ctx.isValid() && (!Files.exists(srcDir) || !Files.isDirectory(srcDir))) {
            ctx.fail("_No src/ directory found._", 0, 0);
        }
        if (ctx.isValid()) {
            Path junitJar = getBundledJUnitConsoleJar();
            if (junitJar == null) {
                ctx.fail("_Missing bundled JUnit jar: " +
                        "lib/junit-platform-console-standalone-6.0.1.jar_", 0, 0
                );
            } else {
                ctx.setJunitJar(junitJar);
            }
        }
        if (ctx.isValid()) {
            Path buildDir = repoPath.resolve("build");
            Path classesDir = buildDir.resolve("classes");

            ctx.setBuildDir(buildDir);
            ctx.setClassesDir(classesDir);

            ensureDirectoryExists(buildDir);
            ensureDirectoryExists(classesDir);
        }
        return ctx;
    }

    private UnitTestResult compileSourceFiles(UnitTestContext ctx) throws IOException {

        if (Files.exists(ctx.getClassesDir())) {
            deleteDirectoryRecursively(ctx.getClassesDir());
        }

        ensureDirectoryExists(ctx.getBuildDir());
        ensureDirectoryExists(ctx.getClassesDir());

        List<Path> srcFiles = findJavaFiles(ctx.getSrcDir()).stream()
                .filter(p -> !p.startsWith(ctx.getSrcDir().resolve("test")))
                .toList();

        if (srcFiles.isEmpty()) {
            return new UnitTestResult("_No Java files found under src/._", 0, 0);
        }

        List<String> javacArgs = new ArrayList<>();
        javacArgs.add("javac");
        javacArgs.add("-d");
        javacArgs.add(ctx.getClassesDir().toAbsolutePath().toString());
        javacArgs.add("-cp");
        javacArgs.add(buildLibClasspath());

        for (Path f : srcFiles) {
            javacArgs.add(f.toAbsolutePath().toString());
        }

        Path javafxLib = getBundledJavaFxLibDir();
        if (javafxLib != null) {
            javacArgs.add("--module-path");
            javacArgs.add(javafxLib.toAbsolutePath().toString());
            javacArgs.add("--add-modules");
            javacArgs.add("javafx.controls,javafx.fxml");
        }

        ProcessResult srcCompile = processRunner.runCaptureLinesWithExitCode(javacArgs,
                ctx.getRepoPath());
        if (srcCompile.exitCode() != 0) {
            logger.log("JUnit process exited with code: " + srcCompile.exitCode());
        }

        if (srcCompile.exitCode() != 0) {
            return new UnitTestResult(
                    formatCompileFailure("Source Compilation Failed", srcCompile),
                    0,
                    0
            );
        }

        return null;
    }

    private UnitTestResult compilePatchedTestSuite(UnitTestContext ctx) throws IOException {

        final String testSuiteFileName = "TestSuite.java";

        Path testSuiteOriginal = ctx.getTestDir().resolve(testSuiteFileName);

        if (!Files.exists(testSuiteOriginal)) {
            return new UnitTestResult(
                    "_Missing " + testSuiteFileName + " under " + ctx.getTestDir() + "._",
                    0,
                    0
            );
        }

        Path patchedTestSuite = preparePatchedTestSuite(
                testSuiteOriginal,
                ctx.getBuildDir(),
                ctx.getStudentPackage()
        );

        List<Path> testFiles = findJavaFiles(ctx.getTestDir());
        List<Path> compileFiles = new ArrayList<>();

        for (Path p : testFiles) {
            Path fileName = p.getFileName();
            boolean isTestSuite = fileName != null
                    && fileName.toString().equalsIgnoreCase(testSuiteFileName);

            if (isTestSuite) {
                compileFiles.add(patchedTestSuite);
            } else {
                compileFiles.add(p);
            }
        }

        if (compileFiles.isEmpty()) {
            return new UnitTestResult("_No Java files found under test directory._", 0, 0);
        }

        String testCp = ctx.getClassesDir().toAbsolutePath()
                + File.pathSeparator
                + buildLibClasspath();

        List<String> javacTests = new ArrayList<>();
        javacTests.add("javac");
        javacTests.add("-encoding");
        javacTests.add("UTF-8");
        javacTests.add("-d");
        javacTests.add(ctx.getClassesDir().toAbsolutePath().toString());
        javacTests.add("-cp");
        javacTests.add(testCp);

        for (Path f : compileFiles) {
            javacTests.add(f.toAbsolutePath().toString());
        }

        Path javafxLib = getBundledJavaFxLibDir();
        if (javafxLib != null) {
            javacTests.add("--module-path");
            javacTests.add(javafxLib.toAbsolutePath().toString());
            javacTests.add("--add-modules");
            javacTests.add("javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing");
        }

        ProcessResult testCompile = processRunner.runCaptureLinesWithExitCode(javacTests, ctx.getRepoPath());

        if (testCompile.exitCode() != 0) {
            logger.log("JUnit process exited with code: " + testCompile.exitCode());
            return new UnitTestResult(
                    formatCompileFailure("Test Compilation Failed", testCompile),
                    0,
                    0
            );
        }

        return null;
    }

    private @NonNull List<String> getArgs(UnitTestContext ctx, String testCp, Path patchedTestSuite) {
        List<String> javacTests = new ArrayList<>();
        javacTests.add("javac");
        javacTests.add("-d");
        javacTests.add(ctx.getClassesDir().toAbsolutePath().toString());
        javacTests.add("-cp");
        javacTests.add(testCp);
        javacTests.add(patchedTestSuite.toAbsolutePath().toString());

        Path javafxLib = getBundledJavaFxLibDir();
        if (javafxLib != null) {
            javacTests.add("--module-path");
            javacTests.add(javafxLib.toAbsolutePath().toString());
            javacTests.add("--add-modules");
            javacTests.add(
                    "javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing"
            );
        }
        return javacTests;
    }

    private UnitTestResult runTestsAndBuildResult(UnitTestContext ctx) throws IOException {

        final String reportsFolderName = "test-reports";
        final String xmlExtension = ".xml";

        Path reportsDir = ctx.getBuildDir().resolve(reportsFolderName);

        if (Files.exists(reportsDir)) {
            deleteDirectoryRecursively(reportsDir);
        }

        ensureDirectoryExists(reportsDir);

        List<String> runArgs = buildJUnitRunArgs(ctx, reportsDir);

        ProcessResult runResult =
                processRunner.runCaptureLinesWithExitCode(runArgs, ctx.getRepoPath());

        boolean junitExitedOk = runResult.exitCode() == 0;

        boolean anyXmlReports = false;
        try (Stream<Path> paths = Files.walk(reportsDir)) {
            anyXmlReports = paths.anyMatch(p -> p.toString().toLowerCase().endsWith(xmlExtension));
        }

        JUnitFailureSummary summary = parseJUnitReports(reportsDir);

        boolean noTestsReported = summary.getTotalTests() == 0;

        if (!junitExitedOk && (!anyXmlReports || noTestsReported)) {
            return new UnitTestResult(
                    formatCompileFailure("Unit Test Run Failed", runResult),
                    0,
                    0
            );
        }

        if (summary.getFailureCount() == 0) {
            return new UnitTestResult(
                    "_No failed unit tests._",
                    summary.getTotalTests(),
                    0
            );
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### Unit Test Results")
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        sb.append("**Failed Tests: ")
                .append(summary.getFailureCount())
                .append("**")
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        for (String line : summary.getFailureLines()) {
            sb.append("- ").append(line).append(System.lineSeparator());
        }

        return new UnitTestResult(
                sb.toString(),
                summary.getTotalTests(),
                summary.getFailureCount()
        );
    }

    private List<String> buildJUnitRunArgs(UnitTestContext ctx, Path reportsDir) {

        List<String> runArgs = new ArrayList<>();
        runArgs.add("java");

        Path javafxLib = getBundledJavaFxLibDir();
        if (javafxLib != null) {
            runArgs.add("-Djava.awt.headless=true");
            runArgs.add("-Dprism.order=sw");
            runArgs.add("-Dprism.text=t2k");
            runArgs.add("-Djavafx.platform=Monocle");
            runArgs.add("-Dmonocle.platform=Headless");
            runArgs.add("-Djava.library.path=" + getBundledJavaFxLibDir());

            runArgs.add("--add-opens=javafx.graphics/com.sun.javafx.util=ALL-UNNAMED");
            runArgs.add("--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED");
            runArgs.add("--add-opens=javax.base/com.sun.javafx.logging=ALL-UNNAMED");
            runArgs.add("--add-opens=javafx.graphics/com.sun.javafx.embed=ALL-UNNAMED");
            runArgs.add("--add-opens=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED");

            runArgs.add("--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED");
            runArgs.add("--add-exports=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED");
            runArgs.add("--add-exports=javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED");
            runArgs.add("--add-exports=javafx.graphics/com.sun.javafx.scene.layout=ALL-UNNAMED");
            runArgs.add("--add-exports=javafx.base/com.sun.javafx.logging=ALL-UNNAMED");
            runArgs.add("--add-exports=javafx.base/com.sun.javafx.event=ALL-UNNAMED");
            runArgs.add("--add-exports=javafx.graphics/com.sun.javafx.embed=ALL-UNNAMED");

            runArgs.add("--module-path");
            runArgs.add(javafxLib.toAbsolutePath().toString());

            runArgs.add("--add-modules");
            runArgs.add("javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing");
        }

        runArgs.add("-jar");
        runArgs.add(ctx.getJunitJar().toAbsolutePath().toString());
        runArgs.add("execute");

        runArgs.add("--class-path");
        runArgs.add(ctx.getClassesDir().toAbsolutePath()
                + File.pathSeparator
                + buildLibClasspath());

        runArgs.add("--scan-class-path");

        runArgs.add("--reports-dir");
        runArgs.add(reportsDir.toAbsolutePath().toString());

        runArgs.add("--disable-ansi-colors");
        runArgs.add("--details=none");

        return runArgs;
    }

    private Path resolveTestDir(Path repoPath, Path srcDir) {

        Path testDir = repoPath.resolve("test");
        Path srcTestDir = srcDir.resolve("test");

        if (Files.exists(srcTestDir) && Files.isDirectory(srcTestDir)) {
            return srcTestDir;
        }

        if (Files.exists(testDir) && Files.isDirectory(testDir)) {
            return testDir;
        }

        return null;
    }

    private String formatCompileFailure(String title, ProcessResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(title).append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("```").append(System.lineSeparator());
        for (String line : result.outputLines()) {
            sb.append(line).append(System.lineSeparator());
        }
        sb.append("```").append(System.lineSeparator());
        return sb.toString();
    }

    private Path getBundledJUnitConsoleJar() {
        Path jar = Path.of("lib").resolve("junit-platform-console-standalone-6.0.1.jar");
        if (Files.exists(jar) && Files.isRegularFile(jar)) {
            return jar;
        }
        return null;
    }

    private Path getBundledJavaFxLibDir() {
        Path dir = Path.of("lib", "javafx-sdk", "lib");
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            return dir;
        }
        return null;
    }

    private String buildLibClasspath() {
        Path libDir = Path.of("lib");
        if (!Files.isDirectory(libDir)) {
            return "";
        }

        try (Stream<Path> stream = Files.list(libDir)) {
            return stream
                    .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                    .map(p -> p.toAbsolutePath().toString())
                    .sorted()
                    .reduce((a, b) -> a + File.pathSeparator + b)
                    .orElse("");
        } catch (IOException e) {
            return "";
        }
    }

    private void ensureDirectoryExists(Path dir) throws IOException {
        if (dir == null) {
            throw new IOException("Directory path is null.");
        }
        Files.createDirectories(dir);
    }

    private void deleteDirectoryRecursively(Path dir) throws IOException {
        if (dir != null && Files.exists(dir)) {
            try (Stream<Path> stream = Files.walk(dir)) {
                List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
                for (Path p : paths) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private List<Path> findJavaFiles(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> p.toString().toLowerCase().endsWith(".java"))
                    .toList();
        }
    }

    private static String getString(Node node) {
        String message = "";

        var failureAttrs = node.getAttributes();
        if (failureAttrs != null && failureAttrs.getNamedItem("message") != null) {
            message = failureAttrs.getNamedItem("message").getNodeValue();
        }

        if (message == null || message.isBlank()) {
            message = node.getTextContent();
        }
        return message;
    }

    private class JUnitFailureSummary {

        private final List<String> failureLines = new ArrayList<>();
        private int totalTests = 0;

        public void incrementTotalTests() {
            totalTests++;
        }

        public void addFailure(String testName, String message) {
            testName = escapeForMarkdownHtml(testName);
            message = escapeForMarkdownHtml(message);

            if (message.isBlank()) {
                failureLines.add("**" + testName + "**");
            } else {
                failureLines.add("**" + testName + "** — " + message);
            }
        }

        public int getFailureCount() {
            return failureLines.size();
        }

        public int getTotalTests() {
            return totalTests;
        }

        public List<String> getFailureLines() {
            return failureLines;
        }
    }

    private static class UnitTestContext {

        private final String studentPackage;
        private final Path repoPath;

        private Path srcDir;
        private Path testDir;
        private Path junitJar;
        private Path buildDir;
        private Path classesDir;

        private boolean valid = true;
        private UnitTestResult failureResult;

        private UnitTestContext(String studentPackage, Path repoPath) {
            this.studentPackage = studentPackage;
            this.repoPath = repoPath;
        }

        public boolean isValid() {
            return valid;
        }

        public UnitTestResult failureResult() {
            return failureResult;
        }

        public void fail(String msg, int totalTests, int failedTests) {
            this.valid = false;
            this.failureResult = new UnitTestResult(msg, totalTests, failedTests);
        }

        public String getStudentPackage() {
            return studentPackage;
        }

        public Path getRepoPath() {
            return repoPath;
        }

        public Path getSrcDir() {
            return srcDir;
        }

        public void setSrcDir(Path srcDir) {
            this.srcDir = srcDir;
        }

        public Path getTestDir() {
            return testDir;
        }

        public void setTestDir(Path testDir) {
            this.testDir = testDir;
        }

        public Path getJunitJar() {
            return junitJar;
        }

        public void setJunitJar(Path junitJar) {
            this.junitJar = junitJar;
        }

        public Path getBuildDir() {
            return buildDir;
        }

        public void setBuildDir(Path buildDir) {
            this.buildDir = buildDir;
        }

        public Path getClassesDir() {
            return classesDir;
        }

        public void setClassesDir(Path classesDir) {
            this.classesDir = classesDir;
        }
    }

    public record UnitTestResult(String markdown, int totalTests, int failedTests) {
    }
}

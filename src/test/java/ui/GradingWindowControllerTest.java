package ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    public void preflightPush_rejectsUnrelatedChanges(@TempDir Path tmp) throws Exception {
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

        assertFalse(readBoolean(result, "allowed"));
        assertTrue(readString(result, "message").contains("repository has unrelated changes"));
    }

    @Test
    public void preflightPush_allowsOnlyReportFileChange(@TempDir Path tmp) throws Exception {
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

package service;

import model.Assignment;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SourceCodeServiceTest {

    @Test
    public void handlesNoExpectedFiles() {
        SourceCodeService svc = new SourceCodeService();
        Assignment a = new Assignment();

        // expectedFiles defaults to null
        String md = svc.buildSourceCodeMarkdown(a, "edu.foo", Path.of("/does/not/matter"));

        assertTrue(md.contains("_No expected files configured._"), "should indicate no expected files configured");
    }

    @Test
    public void listsExistingAndMissingFiles_andIncludesCode() throws Exception {
        SourceCodeService svc = new SourceCodeService();
        Assignment a = new Assignment();

        // two expected files: one present, one missing
        a.setExpectedFiles(List.of("src/{studentPackage}/Hello.java", "src/{studentPackage}/Missing.java"));

        Path temp = Files.createTempDirectory("scs-test");

        Path srcDir = temp.resolve("src").resolve("edu.foo");
        Files.createDirectories(srcDir);

        Path hello = srcDir.resolve("Hello.java");
        String code = "public class Hello { }" + System.lineSeparator();
        Files.writeString(hello, code);

        String md = svc.buildSourceCodeMarkdown(a, "edu.foo", temp);

        // present file: heading, language fence, and code
        assertTrue(md.contains("### Hello.java"), "should include heading for Hello.java");
        assertTrue(md.contains("```java"), "should include java code fence");
        assertTrue(md.contains("public class Hello"), "should include file contents");

        // missing file: should mention missing
        assertTrue(md.contains("Missing file") || md.contains("_Missing file._"), "should indicate missing file for Missing.java");
    }

    @Test
    public void resolvesConfiguredNestedExpectedPaths_withoutBasenameCollision() throws Exception {
        SourceCodeService svc = new SourceCodeService();
        Assignment a = new Assignment();
        a.setExpectedFiles(List.of(
                "src/{studentPackage}/impl/Main.java",
                "src/{studentPackage}/ui/Main.java"
        ));

        Path temp = Files.createTempDirectory("scs-collision");
        Path implDir = temp.resolve("src").resolve("edu.foo").resolve("impl");
        Path uiDir = temp.resolve("src").resolve("edu.foo").resolve("ui");
        Files.createDirectories(implDir);
        Files.createDirectories(uiDir);

        Files.writeString(
                implDir.resolve("Main.java"),
                "package edu.foo.impl; class Main { int impl = 1; }"
        );
        Files.writeString(
                uiDir.resolve("Main.java"),
                "package edu.foo.ui; class Main { int ui = 2; }"
        );

        String md = svc.buildSourceCodeMarkdown(a, "edu.foo", temp);

        assertTrue(md.contains("int impl = 1;"));
        assertTrue(md.contains("int ui = 2;"));
    }

    @Test
    public void fallsBackToLegacyBasenameExpectedPath() throws Exception {
        SourceCodeService svc = new SourceCodeService();
        Assignment a = new Assignment();
        a.setExpectedFiles(List.of("Hello.java"));

        Path temp = Files.createTempDirectory("scs-legacy");
        Path srcDir = temp.resolve("src").resolve("edu.foo");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Hello.java"), "class Hello { }");

        String md = svc.buildSourceCodeMarkdown(a, "edu.foo", temp);

        assertTrue(md.contains("### Hello.java"));
        assertTrue(md.contains("class Hello"));
        assertFalse(md.contains("_Missing file._"));
    }

    @Test
    public void usesLongerFenceWhenCodeContainsTripleBackticks() throws Exception {
        SourceCodeService svc = new SourceCodeService();
        Assignment a = new Assignment();
        a.setExpectedFiles(List.of("src/{studentPackage}/Main.java"));

        Path temp = Files.createTempDirectory("scs-fence");
        Path srcDir = temp.resolve("src").resolve("edu.foo");
        Files.createDirectories(srcDir);
        Files.writeString(
                srcDir.resolve("Main.java"),
                """
                class Main {
                    String demo() {
                        return \"\"\" 
                                |          |
                                |----------|
                                ```inside-code-fence-marker```
                                \"\"\";
                    }
                }
                """
        );

        String md = svc.buildSourceCodeMarkdown(a, "edu.foo", temp);

        assertTrue(md.contains("````java"));
        assertTrue(md.contains("|----------|"));
        assertTrue(md.contains("```inside-code-fence-marker```"));
        assertTrue(md.contains("````"));
    }

    @Test
    public void skipsExpectedEntriesThatResolveToSameSourceFile() throws Exception {
        SourceCodeService svc = new SourceCodeService();
        Assignment a = new Assignment();
        a.setExpectedFiles(List.of(
                "src/{studentPackage}/Main.java",
                "Main.java",
                "src/edu.foo/Main.java"
        ));

        Path temp = Files.createTempDirectory("scs-dedupe");
        Path srcDir = temp.resolve("src").resolve("edu.foo");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), "class Main { int n = 1; }");

        String md = svc.buildSourceCodeMarkdown(a, "edu.foo", temp);

        assertEquals(1, countOccurrences(md, "### Main.java"));
        assertEquals(1, countOccurrences(md, "class Main { int n = 1; }"));
    }

    private int countOccurrences(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while (index >= 0) {
            index = text.indexOf(token, index);
            if (index >= 0) {
                count++;
                index += token.length();
            }
        }
        return count;
    }
}

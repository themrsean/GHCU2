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
}

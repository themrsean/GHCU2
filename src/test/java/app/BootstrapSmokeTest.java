package app;

import javafx.application.Application;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapSmokeTest {

    @Test
    void launcherMain_delegatesToGitHubClassroomUtilitiesMain_inSource() throws Exception {
        Path launcherSource = Path.of("src/main/java/app/Launcher.java");
        String source = Files.readString(launcherSource);

        assertTrue(source.contains("GitHubClassroomUtilities.main(args);"));
    }

    @Test
    void githubClassroomUtilities_hasExpectedApplicationContract() throws Exception {
        assertTrue(Application.class.isAssignableFrom(GitHubClassroomUtilities.class));

        Method main = GitHubClassroomUtilities.class.getDeclaredMethod("main", String[].class);
        assertTrue(Modifier.isStatic(main.getModifiers()));
        assertEquals(void.class, main.getReturnType());

        Method start = GitHubClassroomUtilities.class.getDeclaredMethod(
                "start",
                javafx.stage.Stage.class
        );
        assertEquals(void.class, start.getReturnType());
    }

    @Test
    void mainWindowFxml_resourceExistsOnClasspath() {
        assertNotNull(GitHubClassroomUtilities.class.getResource("/ui/MainWindow.fxml"));
    }
}

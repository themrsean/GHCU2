/*
 * Course: CSC-1120
 * Assignment name
 * File name
 * Name: Sean Jones
 * Last Updated:
 */
package util;

import java.nio.file.Path;

public class AppDataUtil {
    private static Path appDataDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();

        Path base;
        if (os.contains("mac")) {
            base = Path.of(home, "Library", "Application Support");
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = (appData != null && !appData.isBlank()) ? Path.of(appData) :
                    Path.of(home, "AppData", "Roaming");
        } else {
            base = Path.of(home, ".local", "share");
        }
        return base.resolve("GHCU2"); // app folder name
    }


}

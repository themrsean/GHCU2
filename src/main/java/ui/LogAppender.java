/*
 * Course: CSC-1120
 * ASSIGNMENT
 * CLASS
 * Name: Sean Jones
 * Last Updated:
 */
package ui;

import javafx.application.Platform;
import javafx.scene.control.TextArea;

public class LogAppender {

    private final TextArea output;

    public LogAppender(TextArea output) {
        this.output = output;
    }

    public void log(String message) {
        if (message == null) {
            return;
        }
        Platform.runLater(() -> output.appendText(message + System.lineSeparator()));
    }
}

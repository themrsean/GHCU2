/*
 * Course: CSC-1110/1120
 * GitHub Classroom Utilities
 * Name: Sean Jones
 * Last Updated:
 */
package service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs external processes and captures output line-by-line.
 */
public class ProcessRunner {

    private static final int FAILED_TO_START_EXIT_CODE = -1;

    /**
     * Runs a command and returns all output lines plus the exit code.
     *
     * @param args argv list
     * @param workingDir directory to run in
     * @return a ProcessResult containing output lines and exit code
     */
    public ProcessResult runCaptureLinesWithExitCode(List<String> args, Path workingDir) {

        List<String> lines = new ArrayList<>();
        int exitCode = FAILED_TO_START_EXIT_CODE;

        if (args == null || args.isEmpty()) {
            lines.add("ProcessRunner: command args are empty.");
        } else if (workingDir == null) {
            lines.add("ProcessRunner: working directory is null.");
        } else {

            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);

            try {
                Process p = pb.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {

                    String line = reader.readLine();
                    while (line != null) {
                        lines.add(line);
                        line = reader.readLine();
                    }
                }

                exitCode = p.waitFor();

            } catch (IOException e) {
                lines.add("ProcessRunner: failed to start process: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lines.add("ProcessRunner: interrupted while waiting for process.");
            }
        }

        return new ProcessResult(exitCode, lines);
    }

    /**
     * Runs a command and streams each output line into the provided logger.
     *
     * @param args argv list
     * @param workingDir directory to run in
     * @param logger callback for output lines (must not be null)
     * @return exit code, or -1 if process failed to start
     */
    public int runAndLog(List<String> args, Path workingDir, LineLogger logger) {
        Objects.requireNonNull(logger);

        ProcessResult result = runCaptureLinesWithExitCode(args, workingDir);

        for (String line : result.outputLines()) {
            logger.log(line);
        }

        return result.exitCode();
    }

    /**
     * Tokenizes a command line into argv parts, supporting double-quotes.
     * <p>
     * Example:
     * gh classroom clone student-repos -a 123
     * </p>
     * @param command terminal command to tokenize
     * @return tokenized argv list
     */
    public List<String> tokenizeCommand(String command) {
        List<String> parts = new ArrayList<>();

        if (command != null) {
            String trimmed = command.trim();
            if (!trimmed.isEmpty()) {

                StringBuilder current = new StringBuilder();
                boolean inQuotes = false;

                int i = 0;
                while (i < trimmed.length()) {
                    char c = trimmed.charAt(i);

                    if (c == '"') {
                        inQuotes = !inQuotes;
                    } else if (Character.isWhitespace(c) && !inQuotes) {
                        if (!current.isEmpty()) {
                            parts.add(current.toString());
                            current.setLength(0);
                        }
                    } else {
                        current.append(c);
                    }

                    i++;
                }

                if (!current.isEmpty()) {
                    parts.add(current.toString());
                }
            }
        }

        return parts;
    }

    /**
     * Simple logger callback.
     */
    @FunctionalInterface
    public interface LineLogger {
        /**
         * Logs the message to the main logger
         * @param line the line to log
         */
        void log(String line);
    }
}

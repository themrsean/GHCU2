package service;

import java.util.List;

public record ProcessResult(int exitCode, List<String> outputLines) {
}

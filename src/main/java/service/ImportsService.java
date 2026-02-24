package service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ImportsService {
    private static final String PACKAGES_FOLDER_NAME = "packages";
    private static final String OUTPUT_FILE_NAME = "imports.txt";
    private static final String IMPORT_PREFIX = "// import ";
    private static final String IMPORT_SUFFIX = ".*;";

    private final ServiceLogger logger;

    public ImportsService(ServiceLogger logger) {
        this.logger = Objects.requireNonNull(logger);
    }

    public void generateImports(Path root,
                                Path mappingsPath) throws IOException {

        Objects.requireNonNull(root);

        Path packagesRoot = root.resolve(PACKAGES_FOLDER_NAME);

        if (!Files.exists(packagesRoot) || !Files.isDirectory(packagesRoot)) {
            throw new IOException("Packages directory missing: " + packagesRoot);
        }

        List<String> packageNames = new ArrayList<>();

        Files.list(packagesRoot)
                .filter(Files::isDirectory)
                .forEach(path ->
                        packageNames.add(path.getFileName().toString())
                );

        if (packageNames.isEmpty()) {
            throw new IOException("No student packages found under: " + packagesRoot);
        }

        Collections.sort(packageNames);

        StringBuilder content = new StringBuilder();

        for (String pkg : packageNames) {
            content.append(IMPORT_PREFIX)
                    .append(pkg)
                    .append(IMPORT_SUFFIX)
                    .append(System.lineSeparator());
        }

        Path outputFile = root.resolve(OUTPUT_FILE_NAME);

        Files.writeString(outputFile, content.toString());

        logger.log("Wrote imports file: " + outputFile);
    }
}
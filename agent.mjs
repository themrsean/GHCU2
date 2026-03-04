import fs from "fs";
import path from "path";
import {execFile} from "child_process";
import dotenv from "dotenv";
import OpenAI from "openai";

dotenv.config();

const execFileAsync = (file, args, options) => {
    const resolvedOptions = {
        ...options,
        encoding: "utf8",
    };

    return new Promise((resolve, reject) => {
        execFile(file, args, resolvedOptions, (error, stdout, stderr) => {
            const stdoutText = typeof stdout === "string" ? stdout : String(stdout ?? "");
            const stderrText = typeof stderr === "string" ? stderr : String(stderr ?? "");

            if (error) {
                error.stdout = stdoutText;
                error.stderr = stderrText;
                reject(error);
            } else {
                resolve({ stdout: stdoutText, stderr: stderrText });
            }
        });
    });
};

const MODEL_NAME = "gpt-5-mini";
const MAX_TOOL_TURNS = 10;
const MAX_FILE_BYTES = 200_000;
const MAX_PROCESS_BUFFER_BYTES = 10_000_000;
const MAX_SEARCH_RESULTS = 50;

const MODE_EXPLAIN = "explain";
const MODE_PLAN = "plan";
const MODE_DEBUG = "debug";
const MODE_TEST = "test";
const MODE_REFACTOR = "refactor";

const DEFAULT_MODE = MODE_DEBUG;
const REPO_ROOT = process.cwd();
const ALLOWED_ROOT = REPO_ROOT;

const TEST_OUTPUT_DIR = "out/test/GHCU2v2";
const PROD_OUTPUT_DIR = "out/production/GHCU2v2";
const JUNIT_JAR = "lib/junit-platform-console-standalone-6.0.1.jar";
const LIB_DIR = "lib";
const JAVAFX_LIB_DIR = "lib/javafx-sdk/lib";
const MAX_JAVA_FILES = 20000;
const MAX_WRITE_BYTES = 200_000;
const MAX_PATCH_BYTES = 200_000;
const WRITE_ALLOWED_PREFIXES_TEST = [
    "src/test/java/",
    "src/test/resources/"
];
const WRITE_ALLOWED_PREFIXES_REFACTOR = [
    ...WRITE_ALLOWED_PREFIXES_TEST,
    "src/main/java/"
];
const TOOL_LIST_FILES = "list_files";
const TOOL_READ_FILE = "read_file";
const TOOL_SEARCH_REPO = "search_repo";
const TOOL_COMPILE_PROD = "compile_prod";
const TOOL_COMPILE_TESTS = "compile_tests";
const TOOL_RUN_TESTS = "run_tests";
const TOOL_WRITE_FILE = "write_file";
const TOOL_APPLY_PATCH = "apply_patch";
const TOOL_CHOICE_NONE = "none";
const ROLE_USER = "user";
const ROLE_TOOL = "tool";

const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

const buildClassPath = () => {
    const pathSeparator = path.delimiter;

    const prodAbs = path.resolve(REPO_ROOT, PROD_OUTPUT_DIR);
    const testAbs = path.resolve(REPO_ROOT, TEST_OUTPUT_DIR);
    const libAbs = path.resolve(REPO_ROOT, LIB_DIR);
    const javafxLibAbs = path.resolve(REPO_ROOT, JAVAFX_LIB_DIR);

    const parts = [
        prodAbs,
        testAbs,
        `${libAbs}/*`,
        `${javafxLibAbs}/*`,
    ];

    return parts.join(pathSeparator);
};

const normalizeRelPath = (p) => p.replaceAll("\\", "/");

const isAbsoluteLike = (p) => path.isAbsolute(p);

const assertRepoRelative = (p) => {
    if (isAbsoluteLike(p)) {
        throw new Error(`Path must be repo-relative, got absolute path: ${p}`);
    }
    const normalized = normalizeRelPath(p);
    if (normalized.startsWith("../") || normalized.includes("/../")) {
        throw new Error(`Path must not escape repo root: ${p}`);
    }
};

const isWriteAllowed = (mode, relativePath) => {
    const normalized = normalizeRelPath(relativePath);

    const allowed =
        mode === MODE_REFACTOR
            ? WRITE_ALLOWED_PREFIXES_REFACTOR
            : WRITE_ALLOWED_PREFIXES_TEST;

    return allowed.some((prefix) => normalized.startsWith(prefix));
};

const buildProdCompileClassPath = () => {
    const pathSeparator = path.delimiter;

    const prodAbs = path.resolve(REPO_ROOT, PROD_OUTPUT_DIR);
    const libAbs = path.resolve(REPO_ROOT, LIB_DIR);
    const javafxLibAbs = path.resolve(REPO_ROOT, JAVAFX_LIB_DIR);

    const parts = [
        prodAbs,
        `${libAbs}/*`,
        `${javafxLibAbs}/*`,
    ];

    return parts.join(pathSeparator);
};

const normalizeAndValidatePath = (inputPath) => {
    const resolved = path.resolve(ALLOWED_ROOT, inputPath);
    const isInside = resolved === ALLOWED_ROOT || resolved.startsWith(ALLOWED_ROOT + path.sep);
    if (!isInside) {
        throw new Error(`Path not allowed: ${inputPath}`);
    }
    return resolved;
};

const safeReadFile = (filePath) => {
    const resolved = normalizeAndValidatePath(filePath);
    const stat = fs.statSync(resolved);
    if (!stat.isFile()) {
        throw new Error(`Not a file: ${filePath}`);
    }
    if (stat.size > MAX_FILE_BYTES) {
        throw new Error(`File too large (${stat.size} bytes). Max is ${MAX_FILE_BYTES}.`);
    }
    return fs.readFileSync(resolved, "utf-8");
};

const safeWriteFile = (mode, filePath, content) => {
    assertRepoRelative(filePath);
    const resolved = normalizeAndValidatePath(filePath);

    const isAllowed = isWriteAllowed(mode, filePath);
    if (!isAllowed) {
        throw new Error(`Write not allowed in ${mode} mode: ${filePath}`);
    }

    const byteLength = Buffer.byteLength(content, "utf-8");
    if (byteLength > MAX_WRITE_BYTES) {
        throw new Error(`Write too large (${byteLength} bytes). Max is ${MAX_WRITE_BYTES}.`);
    }

    fs.mkdirSync(path.dirname(resolved), { recursive: true });
    fs.writeFileSync(resolved, content, "utf-8");

    return { path: filePath, bytes_written: byteLength };
};

const assertWriteAllowed = (mode, relativePath) => {
    assertRepoRelative(relativePath);

    const isAllowed = isWriteAllowed(mode, relativePath);
    if (!isAllowed) {
        throw new Error(`Write not allowed in ${mode} mode: ${relativePath}`);
    }
};

const listFilesRecursive = (dirPath, maxCount) => {
    const resolvedDir = normalizeAndValidatePath(dirPath);
    const results = [];
    const stack = [resolvedDir];

    while (stack.length > 0 && results.length < maxCount) {
        const current = stack.pop();
        const entries = fs.readdirSync(current, { withFileTypes: true });

        for (const entry of entries) {
            const full = path.join(current, entry.name);

            // Basic ignore patterns (extend later)
            const name = entry.name;
            const isIgnored =
                name === ".git" ||
                name === "node_modules" ||
                name === "build" ||
                name === "target" ||
                name === ".gradle" ||
                name === ".idea";

            if (!isIgnored) {
                if (entry.isDirectory()) {
                    stack.push(full);
                } else if (entry.isFile()) {
                    const rel = path.relative(REPO_ROOT, full);
                    results.push(rel);
                }
            }

            if (results.length >= maxCount) {
                // no break (per your standard): use guard in loop conditions
            }
        }
    }

    return results;
};

const listFilesRecursiveFromAbsoluteRoot = (absoluteRoot, maxCount) => {
    const results = [];
    const stack = [absoluteRoot];

    while (stack.length > 0 && results.length < maxCount) {
        const current = stack.pop();
        const entries = fs.readdirSync(current, { withFileTypes: true });

        for (const entry of entries) {
            const full = path.join(current, entry.name);

            if (entry.isDirectory()) {
                stack.push(full);
            } else if (entry.isFile()) {
                results.push(full);
            }
        }
    }

    return results;
};

const searchRepoRipgrep = async (query, maxResults, isRegex) => {
    const rgArgs = [
        "--no-heading",
        "--line-number",
        "--column",
        "--hidden",
        "--glob",
        "!.git/*",
        "--glob",
        "!node_modules/*",
        "--glob",
        "!target/*",
        "--glob",
        "!build/*",
        "--max-count",
        String(maxResults),
    ];

    const useRegex = isRegex === true;
    if (!useRegex) {
        rgArgs.push("--fixed-strings");
    }

    rgArgs.push("--", query, ".");

    try {
        const { stdout } = await execFileAsync("rg", rgArgs, { cwd: REPO_ROOT, maxBuffer: MAX_PROCESS_BUFFER_BYTES });
        const lines = stdout.split("\n").filter((l) => l.trim().length > 0);

        return lines.map((line) => {
            const first = line.indexOf(":");
            const second = line.indexOf(":", first + 1);
            const third = line.indexOf(":", second + 1);

            const file = line.slice(0, first);
            const lineNum = Number(line.slice(first + 1, second));
            const colNum = Number(line.slice(second + 1, third));
            const text = line.slice(third + 1);

            return { file, line: lineNum, column: colNum, text };
        });
    } catch (err) {
        const hasNoMatches = typeof err?.code === "number" && err.code === 1;
        if (hasNoMatches) {
            return [];
        }
        throw err;
    }
};

const listJarFiles = (absoluteDir) => {
    const exists = fs.existsSync(absoluteDir);
    if (!exists) {
        return [];
    }

    const entries = fs.readdirSync(absoluteDir, { withFileTypes: true });
    return entries
        .filter((e) => e.isFile() && e.name.endsWith(".jar"))
        .map((e) => path.join(absoluteDir, e.name));
};

const buildResolvedRuntimeClassPath = () => {
    const pathSeparator = path.delimiter;

    const prodAbs = path.resolve(REPO_ROOT, PROD_OUTPUT_DIR);
    const testAbs = path.resolve(REPO_ROOT, TEST_OUTPUT_DIR);
    const libAbs = path.resolve(REPO_ROOT, LIB_DIR);
    const javafxLibAbs = path.resolve(REPO_ROOT, JAVAFX_LIB_DIR);

    const libJars = listJarFiles(libAbs);
    const javafxJars = listJarFiles(javafxLibAbs);

    const parts = [
        prodAbs,
        testAbs,
        ...libJars,
        ...javafxJars,
    ];

    return parts.join(pathSeparator);
};

const ensureSingleFileDiff = (diffText) => {
    const plusPlusCount = diffText.split("\n").filter(l => l.startsWith("+++ ")).length;
    if (plusPlusCount > 1) {
        throw new Error("apply_patch only supports single-file diffs.");
    }
};

const applyUnifiedDiffToText = (originalText, diffText) => {
    const originalLines = originalText.split("\n");
    const diffLines = diffText.split("\n");

    const outputLines = [];
    let originalIndex = 0;

    const HUNK_HEADER_PREFIX = "@@";
    const DIFF_FILE_PREFIX_1 = "---";
    const DIFF_FILE_PREFIX_2 = "+++";
    const SPACE_PREFIX = " ";
    const ADD_PREFIX = "+";
    const REMOVE_PREFIX = "-";

    const parseHunkHeader = (line) => {
        // @@ -start,count +start,count @@
        const header = line.trim();
        const firstSpace = header.indexOf(" ");
        const secondSpace = header.indexOf(" ", firstSpace + 1);

        const left = header.slice(firstSpace + 1, secondSpace);   // -a,b
        const rightStart = header.indexOf("+", secondSpace);
        const rightEnd = header.indexOf(" @@", rightStart);
        const right = header.slice(rightStart, rightEnd);         // +c,d

        const leftParts = left.slice(1).split(",");
        const rightParts = right.slice(1).split(",");

        const leftStart = Number(leftParts[0]);
        const leftCount = leftParts.length > 1 ? Number(leftParts[1]) : 1;
        const rightStartNum = Number(rightParts[0]);
        const rightCount = rightParts.length > 1 ? Number(rightParts[1]) : 1;

        if (!Number.isFinite(leftStart) || !Number.isFinite(leftCount) || !Number.isFinite(rightStartNum) || !Number.isFinite(rightCount)) {
            throw new Error(`Invalid hunk header: ${line}`);
        }

        return {
            leftStart,
            leftCount,
            rightStart: rightStartNum,
            rightCount
        };
    };

    let i = 0;
    while (i < diffLines.length) {
        const line = diffLines[i];

        const isFileHeader1 = line.startsWith(DIFF_FILE_PREFIX_1);
        const isFileHeader2 = line.startsWith(DIFF_FILE_PREFIX_2);

        if (isFileHeader1 || isFileHeader2) {
            i += 1;
        } else if (line.startsWith(HUNK_HEADER_PREFIX)) {
            const hunk = parseHunkHeader(line);

            // Copy unchanged lines before hunk (leftStart is 1-based)
            const targetOriginalIndex = hunk.leftStart - 1;
            while (originalIndex < targetOriginalIndex) {
                outputLines.push(originalLines[originalIndex]);
                originalIndex += 1;
            }

            i += 1;

            // Apply hunk lines
            while (i < diffLines.length) {
                const hunkLine = diffLines[i];

                const isNextHunk = hunkLine.startsWith(HUNK_HEADER_PREFIX);
                const isEndOfFile = hunkLine.startsWith(DIFF_FILE_PREFIX_1) || hunkLine.startsWith(DIFF_FILE_PREFIX_2);

                if (isNextHunk || isEndOfFile) {
                    break;
                }

                const prefix = hunkLine.slice(0, 1);
                const content = hunkLine.slice(1);

                const isContext = prefix === SPACE_PREFIX;
                const isAdd = prefix === ADD_PREFIX;
                const isRemove = prefix === REMOVE_PREFIX;
                const isNoNewlineMarker = hunkLine.startsWith("\\ No newline at end of file");

                if (isNoNewlineMarker) {
                    i += 1;
                } else if (isContext) {
                    const originalLine = originalLines[originalIndex];
                    if (originalLine !== content) {
                        throw new Error(`Patch context mismatch at line ${originalIndex + 1}. Expected: "${content}" Got: "${originalLine ?? ""}"`);
                    }
                    outputLines.push(originalLine);
                    originalIndex += 1;
                    i += 1;
                } else if (isRemove) {
                    const originalLine = originalLines[originalIndex];
                    if (originalLine !== content) {
                        throw new Error(`Patch removal mismatch at line ${originalIndex + 1}. Expected: "${content}" Got: "${originalLine ?? ""}"`);
                    }
                    originalIndex += 1;
                    i += 1;
                } else if (isAdd) {
                    outputLines.push(content);
                    i += 1;
                } else {
                    throw new Error(`Unsupported patch line: ${hunkLine}`);
                }
            }
        } else if (line.trim().length === 0) {
            // allow trailing empty lines in diff
            i += 1;
        } else {
            throw new Error(`Unexpected diff content: ${line}`);
        }
    }

    // Copy the rest of the original
    while (originalIndex < originalLines.length) {
        outputLines.push(originalLines[originalIndex]);
        originalIndex += 1;
    }

    return outputLines.join("\n");
};

const parseMode = (rawPrompt) => {
    const trimmed = rawPrompt.trimStart();
    const match = trimmed.match(/^mode=([a-zA-Z]+)/i);

    if (!match) {
        return DEFAULT_MODE;
    }

    const mode = match[1].toLowerCase();

    const isKnown =
        mode === MODE_EXPLAIN ||
        mode === MODE_PLAN ||
        mode === MODE_DEBUG ||
        mode === MODE_TEST ||
        mode === MODE_REFACTOR;

    return isKnown ? mode : DEFAULT_MODE;
};

const stripModePrefix = (rawPrompt) => {
    const trimmed = rawPrompt.trimStart();
    const match = trimmed.match(/^mode=[a-zA-Z]+\s*/i);
    if (!match) {
        return rawPrompt;
    }
    return trimmed.slice(match[0].length).trim();
};

const tools = [
    {
        type: "function",
        function: {
            name: "list_files",
            description: "List files under a directory relative to repo root (read-only).",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {
                    dir: { type: "string", description: "Directory path relative to repo root, e.g. '.' or 'src'." },
                    max_count: { type: "integer", description: "Maximum number of files to return." },
                },
                required: ["dir"],
            },
        },
    },
    {
        type: "function",
        function: {
            name: "read_file",
            description: "Read a UTF-8 text file from the repo (read-only).",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {
                    path: { type: "string", description: "File path relative to repo root." },
                },
                required: ["path"],
            },
        },
    },
    {
        type: "function",
        function: {
            name: "search_repo",
            description: "Search the repo using ripgrep and return matching lines with file/line/column.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {
                    query: { type: "string", description: "Search query (plain text by default; regex if is_regex=true)." },
                    max_results: { type: "integer", description: "Maximum number of matches to return." },
                    is_regex: { type: "boolean", description: "If true, treat query as regex. Default false (fixed-string search)." }
                },
                required: ["query"],
            },
        },
    },
    {
        type: "function",
        function: {
            name: "compile_prod",
            description: "Compile Java production sources under src/main/java into the production output directory.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {},
            },
        },
    },
    {
        type: "function",
        function: {
            name: "compile_tests",
            description: "Compile Java test sources into the test output directory.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {},
            },
        },
    },
    {
        type: "function",
        function: {
            name: "run_tests",
            description: "Run JUnit tests using the JUnit console launcher.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {},
            },
        },
    },
    {
        type: "function",
        function: {
            name: "write_file",
            description: "Create or overwrite a UTF-8 text file within allowed write paths.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {
                    path: { type: "string", description: "File path relative to repo root." },
                    content: { type: "string", description: "Full file contents (UTF-8)." }
                },
                required: ["path", "content"]
            }
        }
    },
    {
        type: "function",
        function: {
            name: "apply_patch",
            description: "Apply a unified diff patch to a single file under src/test/java or src/test/resources.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {
                    path: { type: "string", description: "Target file path relative to repo root." },
                    patch: { type: "string", description: "Unified diff content (single-file patch)." }
                },
                required: ["path", "patch"]
            }
        }
    },
];

const toolsForMode = (mode, allTools) => {
    const allowedNames = [];

    if (mode === MODE_EXPLAIN || mode === MODE_PLAN) {
        allowedNames.push(TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_REPO);
    } else if (mode === MODE_DEBUG) {
        allowedNames.push(
            TOOL_LIST_FILES,
            TOOL_READ_FILE,
            TOOL_SEARCH_REPO,
            TOOL_COMPILE_PROD,
            TOOL_COMPILE_TESTS,
            TOOL_RUN_TESTS
        );
    } else if (mode === MODE_TEST) {
        allowedNames.push(
            TOOL_LIST_FILES,
            TOOL_READ_FILE,
            TOOL_SEARCH_REPO,
            TOOL_COMPILE_PROD,
            TOOL_COMPILE_TESTS,
            TOOL_RUN_TESTS,
            TOOL_WRITE_FILE,
            TOOL_APPLY_PATCH
        );
    } else if (mode === MODE_REFACTOR) {
        allowedNames.push(
            TOOL_LIST_FILES,
            TOOL_READ_FILE,
            TOOL_SEARCH_REPO,
            TOOL_COMPILE_PROD,
            TOOL_COMPILE_TESTS,
            TOOL_RUN_TESTS,
            TOOL_WRITE_FILE,
            TOOL_APPLY_PATCH
        );
    } else {
        allowedNames.push(TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_REPO);
    }

    return allTools.filter((t) => allowedNames.includes(t.function?.name));
};

const callLocalTool = async (mode, toolName, args) => {
    if (toolName === TOOL_READ_FILE) {
        const content = safeReadFile(args.path);
        return { path: args.path, content };
    }

    if (toolName === TOOL_LIST_FILES) {
        const maxCount = Number.isInteger(args.max_count) ? args.max_count : MAX_SEARCH_RESULTS;
        const files = listFilesRecursive(args.dir, maxCount);
        return { dir: args.dir, files };
    }

    if (toolName === TOOL_SEARCH_REPO) {
        const maxResults = Number.isInteger(args.max_results) ? args.max_results : MAX_SEARCH_RESULTS;
        const isRegex = args.is_regex === true;
        const matches = await searchRepoRipgrep(args.query, maxResults, isRegex);
        return { query: args.query, is_regex: isRegex, matches };
    }

    if (toolName === TOOL_COMPILE_TESTS) {
        const testSourceRoot = path.resolve(REPO_ROOT, "src/test/java");
        const outDirAbs = path.resolve(REPO_ROOT, TEST_OUTPUT_DIR);

        if (!fs.existsSync(testSourceRoot)) {
            return { status: "no_tests_dir", message: "src/test/java does not exist." };
        }

        fs.mkdirSync(outDirAbs, { recursive: true });

        const allAbsFiles = listFilesRecursiveFromAbsoluteRoot(testSourceRoot, MAX_JAVA_FILES);
        const javaAbsFiles = allAbsFiles.filter((f) => f.endsWith(".java"));

        if (javaAbsFiles.length === 0) {
            return { status: "no_test_files", message: "No .java files under src/test/java." };
        }

        const javaFilesRel = javaAbsFiles.map((absPath) => {
            const rel = path.relative(testSourceRoot, absPath);
            const prefix = `java${path.sep}`;
            return rel.startsWith(prefix) ? rel.slice(prefix.length) : rel;
        });

        const classPath = buildClassPath();

        const javacArgs = [
            "-cp",
            classPath,
            "-d",
            outDirAbs,
            ...javaFilesRel,
        ];

        const command = ["javac", ...javacArgs];
        const cwd = testSourceRoot;

        const { stdout, stderr } = await execFileAsync(command[0], command.slice(1), {
            cwd,
            maxBuffer: MAX_PROCESS_BUFFER_BYTES
        });

        return {
            status: "compiled",
            cwd,
            command,
            files_compiled: javaFilesRel.length,
            stdout,
            stderr
        };
    }

    if (toolName === TOOL_RUN_TESTS) {
        const testAbs = path.resolve(REPO_ROOT, TEST_OUTPUT_DIR);
        const classPath = buildResolvedRuntimeClassPath();

        const javaArgs = [
            "-jar", JUNIT_JAR,
            "execute",
            "--class-path",
            classPath,
            "--scan-classpath",
            testAbs,
        ];

        const command = ["java", ...javaArgs];
        const cwd = REPO_ROOT;

        const { stdout, stderr } = await execFileAsync(command[0], command.slice(1), {
            cwd,
            maxBuffer: MAX_PROCESS_BUFFER_BYTES
        });

        return {
            status: "ran",
            cwd,
            command,
            stdout,
            stderr
        };
    }

    if (toolName === TOOL_COMPILE_PROD) {
        const prodSourceDir = "src/main/java";
        const prodRootAbs = path.resolve(REPO_ROOT, prodSourceDir);
        const outDirAbs = path.resolve(REPO_ROOT, PROD_OUTPUT_DIR);

        if (!fs.existsSync(prodRootAbs)) {
            return { status: "no_prod_dir", message: "src/main/java does not exist." };
        }

        fs.mkdirSync(outDirAbs, { recursive: true });

        const allAbsFiles = listFilesRecursiveFromAbsoluteRoot(prodRootAbs, MAX_JAVA_FILES);
        const javaAbsFiles = allAbsFiles.filter((f) => f.endsWith(".java"));

        if (javaAbsFiles.length === 0) {
            return { status: "no_prod_files", message: "No .java files under src/main/java." };
        }

        const javaFilesRelToProdRoot = javaAbsFiles.map((f) => path.relative(prodRootAbs, f));
        const classPath = buildProdCompileClassPath();

        const javacArgs = [
            "-cp",
            classPath,
            "-d",
            outDirAbs,
            ...javaFilesRelToProdRoot,
        ];

        const command = ["javac", ...javacArgs];
        const cwd = prodRootAbs;

        const { stdout, stderr } = await execFileAsync(command[0], command.slice(1), {
            cwd,
            maxBuffer: MAX_PROCESS_BUFFER_BYTES
        });

        return {
            status: "compiled",
            cwd,
            command,
            files_compiled: javaFilesRelToProdRoot.length,
            stdout,
            stderr
        };
    }

    if (toolName === TOOL_WRITE_FILE) {
        const result = safeWriteFile(mode, args.path, args.content);
        return { status: "written", ...result };
    }

    if (toolName === TOOL_APPLY_PATCH) {
        const byteLength = Buffer.byteLength(args.patch, "utf-8");
        if (byteLength > MAX_PATCH_BYTES) {
            throw new Error(`Patch too large (${byteLength} bytes). Max is ${MAX_PATCH_BYTES}.`);
        }

        assertWriteAllowed(mode, args.path);

        const resolved = normalizeAndValidatePath(args.path);
        const original = fs.existsSync(resolved) ? fs.readFileSync(resolved, "utf-8") : "";

        ensureSingleFileDiff(args.patch);
        const updated = applyUnifiedDiffToText(original, args.patch);

        fs.mkdirSync(path.dirname(resolved), { recursive: true });
        fs.writeFileSync(resolved, updated, "utf-8");

        return { status: "patched", path: args.path, bytes_written: Buffer.byteLength(updated, "utf-8") };
    }

    throw new Error(`Unknown tool: ${toolName}`);
};

const buildSystemInstructions = (mode) => {
    const base = [
        "You are a senior software engineer assisting with a Java repository.",
        "Use tools to inspect and validate behavior instead of guessing.",
        "Cite file paths and line numbers when available from search results.",
    ];

    const readOnly = [
        "You cannot modify files in this mode.",
        "Prefer: search_repo then read_file.",
    ];

    const plan = [
        "You are in PLAN mode: produce an implementation plan without modifying files.",
        "You cannot compile or run tests in this mode.",
        "Required output sections: Understanding, Proposed change, Implementation steps (with files/classes), Test plan, Risks/open questions.",
        "Use tools to ground the plan: search_repo/list_files to locate entry points, then read_file to confirm control flow.",
        "Avoid vague steps; every step must name specific files/classes/methods and a verification idea.",
    ];

    const debug = [
        "You may compile and run tests to reproduce issues.",
        "You cannot modify files in this mode.",
        "Prefer: compile_prod, compile_tests, run_tests when debugging test failures.",
    ];

    const test = [
        "You may create/modify test files only under src/test/java and src/test/resources.",
        "When you add or change tests, you must run compile_prod, compile_tests, then run_tests and report results.",
        "Do not modify production source files in this mode.",
    ];

    const refactor = [
        "You may modify production and test files using apply_patch/write_file.",
        "Refactor goal: improve readability/structure without changing externally observable behavior.",
        "Do NOT change public interfaces/APIs (public/protected method signatures, public fields, class names, package names).",
        "Do NOT change file formats, CLI/UI behavior, persistence schemas, or outputs unless explicitly instructed.",
        "Allowed: rename local variables, extract private methods, reorder helpers, simplify conditionals, remove dead code, add small internal comments.",
        "After changes, run compile_prod, compile_tests, then run_tests and report results.",
    ];

    if (mode === MODE_EXPLAIN) {
        return base.concat(readOnly).join("\n");
    }

    if (mode === MODE_PLAN) {
        return base.concat(plan).join("\n");
    }

    if (mode === MODE_DEBUG) {
        return base.concat(debug).join("\n");
    }

    if (mode === MODE_TEST) {
        return base.concat(test).join("\n");
    }

    if (mode === MODE_REFACTOR) {
        return base.concat(refactor).join("\n");
    }

    return base.concat(readOnly).join("\n");
};

const runAgent = async (userPrompt) => {
    const mode = parseMode(userPrompt);
    const promptWithoutMode = stripModePrefix(userPrompt);

    console.log("Mode:", mode);

    const activeTools = toolsForMode(mode, tools);
    console.log("Tools:", activeTools.map(t => t.function?.name).join(", "));

    const input = [
        { role: "system", content: buildSystemInstructions(mode) },
        { role: ROLE_USER, content: promptWithoutMode },
    ];

    const EMPTY_TOOL_CALLS_LENGTH = 0;

    let turns = 0;
    let done = false;

    const printSearchResults = (result) => {
        const matches = Array.isArray(result?.matches) ? result.matches : [];
        const queryText = typeof result?.query === "string" ? result.query : "";
        console.log(`[tool result] ${TOOL_SEARCH_REPO}: "${queryText}" (${matches.length} matches)`);

        for (let i = 0; i < matches.length; i += 1) {
            const m = matches[i];
            console.log(`${m.file}:${m.line}:${m.column}: ${m.text}`);
        }
    };

    const printToolResult = (toolName, result) => {
        if (result === undefined || result === null) {
            console.log(`[tool result] ${toolName}: <no result>`);
            return;
        }
        if (toolName === TOOL_READ_FILE) {
            console.log(`[tool result] ${toolName}: ${result.path}`);
            console.log("----- FILE START -----");
            printWithLineNumbers(result.content);
            console.log("----- FILE END -----");
        } else if (toolName === TOOL_SEARCH_REPO) {
            printSearchResults(result);
        } else {
            const pretty = JSON.stringify(result, null, 2);
            console.log(`[tool result] ${toolName}\n${pretty}`);
        }
    };

    while (!done && turns < MAX_TOOL_TURNS) {
        const response = await openai.chat.completions.create({
            model: MODEL_NAME,
            messages: input,
            tools: activeTools,
            tool_choice: "auto",
        });

        const assistantMessage = response.choices[0].message;
        const assistantText = (assistantMessage.content ?? "").trim();

        if (assistantText.length > 0) {
            console.log(assistantText);
        } else {
            console.log("[assistant returned no text]");
        }

        const toolCalls = assistantMessage.tool_calls ?? [];

        if (toolCalls.length > EMPTY_TOOL_CALLS_LENGTH) {
            input.push(assistantMessage);

            for (let i = 0; i < toolCalls.length; i += 1) {
                const call = toolCalls[i];
                const toolName = call.function.name;
                const rawArgs = call.function.arguments ?? "{}";

                console.log(`[tool call] ${toolName} args=${rawArgs}`);

                let args;
                try {
                    args = JSON.parse(rawArgs);
                } catch (e) {
                    throw new Error(`Invalid JSON tool args for ${toolName}: ${rawArgs}`);
                }

                const result = await callLocalTool(mode, toolName, args);

                printToolResult(toolName, result);

                input.push({
                    role: ROLE_TOOL,
                    tool_call_id: call.id,
                    content: JSON.stringify(result),
                });
            }
        } else {
            if (assistantText.length === 0) {
                const finalPrompt = [
                    ...input,
                    {
                        role: ROLE_USER,
                        content:
                            "Provide your final output now. Summarize what you found and give the plan/answer in bullets. Do not call tools.",
                    },
                ];

                const finalResponse = await openai.chat.completions.create({
                    model: MODEL_NAME,
                    messages: finalPrompt,
                    tool_choice: TOOL_CHOICE_NONE,
                });

                const finalText = (finalResponse.choices[0]?.message?.content ?? "").trim();
                if (finalText.length > 0) {
                    console.log(finalText);
                } else {
                    const debugMsg = JSON.stringify(assistantMessage, null, 2);
                    console.log(`[final response still empty] assistantMessage=${debugMsg}`);
                }
            }

            done = true;
        }

        turns += 1;
    }
};

const printWithLineNumbers = (text) => {
    const lines = text.split("\n");
    const width = String(lines.length).length;

    for (let i = 0; i < lines.length; i += 1) {
        const lineNumber = String(i + 1).padStart(width, " ");
        console.log(`${lineNumber} | ${lines[i]}`);
    }
};

const main = async () => {
    const userPrompt = process.argv.slice(2).join(" ").trim();
    if (userPrompt.length === 0) {
        throw new Error("Provide a prompt, e.g. node agent.mjs \"Explain how auth works\".");
    }

    await runAgent(userPrompt);
};

main().catch((err) => {
    console.error(err?.stack ?? String(err));
    process.exitCode = 1;
});
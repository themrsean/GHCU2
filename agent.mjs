import fs from "fs";
import path from "path";
import {execFile} from "child_process";
import {promisify} from "util";
import dotenv from "dotenv";
import OpenAI from "openai";

dotenv.config();

const execFileAsync = promisify(execFile);

const MODEL_NAME = "gpt-5-mini";
const MAX_TOOL_TURNS = 10;
const MAX_FILE_BYTES = 200_000;
const MAX_PROCESS_BUFFER_BYTES = 10_000_000;
const MAX_SEARCH_RESULTS = 50;

const MODE_EXPLAIN = "explain";
const MODE_PLAN = "plan";
const MODE_DEBUG = "debug";
const MODE_TEST = "test";

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
const WRITE_ALLOWED_PREFIXES = [
    "src/test/java/",
    "src/test/resources/"
];
const TOOL_LIST_FILES = "list_files";
const TOOL_READ_FILE = "read_file";
const TOOL_SEARCH_REPO = "search_repo";
const TOOL_COMPILE_PROD = "compile_prod";
const TOOL_COMPILE_TESTS = "compile_tests";
const TOOL_RUN_TESTS = "run_tests";
const TOOL_WRITE_FILE = "write_file";
const TOOL_APPLY_PATCH = "apply_patch";

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

const safeWriteFile = (filePath, content) => {
    const resolved = normalizeAndValidatePath(filePath);

    const isAllowed = WRITE_ALLOWED_PREFIXES.some((p) => filePath.startsWith(p));
    if (!isAllowed) {
        throw new Error(`Write not allowed outside test directories: ${filePath}`);
    }

    const byteLength = Buffer.byteLength(content, "utf-8");
    if (byteLength > MAX_WRITE_BYTES) {
        throw new Error(`Write too large (${byteLength} bytes). Max is ${MAX_WRITE_BYTES}.`);
    }

    fs.mkdirSync(path.dirname(resolved), { recursive: true });
    fs.writeFileSync(resolved, content, "utf-8");

    return { path: filePath, bytes_written: byteLength };
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

const searchRepoRipgrep = async (query, maxResults) => {
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
        query,
        ".",
    ];

    try {
        const { stdout } = await execFileAsync("rg", rgArgs, { cwd: REPO_ROOT, maxBuffer: MAX_PROCESS_BUFFER_BYTES });
        const lines = stdout.split("\n").filter((l) => l.trim().length > 0);

        return lines.map((line) => {
            // rg format: path:line:col:text
            const first = line.indexOf(":");
            const second = line.indexOf(":", first + 1);
            const third = line.indexOf(":", second + 1);

            const file = line.slice(0, first);
            const lineNum = Number(line.slice(first + 1, second));
            const colNum = Number(line.slice(second + 1, third));
            const text = line.slice(third + 1);

            return {file, line: lineNum, column: colNum, text};
        });
    } catch (err) {
        // rg exits with code 1 when no matches; treat that as empty results
        const hasNoMatches =
            typeof err?.code === "number" && err.code === 1;

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

const assertWriteAllowed = (relativePath) => {
    const isAllowed = WRITE_ALLOWED_PREFIXES.some((p) => relativePath.startsWith(p));
    if (!isAllowed) {
        throw new Error(`Write not allowed outside test directories: ${relativePath}`);
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
    const prefix = "MODE=";
    const idx = rawPrompt.indexOf(prefix);
    if (idx !== 0) {
        return DEFAULT_MODE;
    }

    const after = rawPrompt.slice(prefix.length);
    const space = after.indexOf(" ");
    const token = space >= 0 ? after.slice(0, space) : after;
    const mode = token.trim().toLowerCase();

    const isKnown =
        mode === MODE_EXPLAIN ||
        mode === MODE_PLAN ||
        mode === MODE_DEBUG ||
        mode === MODE_TEST;

    return isKnown ? mode : DEFAULT_MODE;
};

const stripModePrefix = (rawPrompt) => {
    const prefix = "MODE=";
    const idx = rawPrompt.indexOf(prefix);
    if (idx !== 0) {
        return rawPrompt;
    }

    const firstSpace = rawPrompt.indexOf(" ");
    if (firstSpace < 0) {
        return "";
    }

    return rawPrompt.slice(firstSpace + 1).trim();
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
                    query: { type: "string", description: "Ripgrep query string (plain text or regex)." },
                    max_results: { type: "integer", description: "Maximum number of matches to return." },
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
    } else {
        allowedNames.push(TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_REPO);
    }

    return allTools.filter((t) => allowedNames.includes(t.function?.name));
};

const callLocalTool = async (toolName, args) => {
    if (toolName === "read_file") {
        const content = safeReadFile(args.path);
        return { path: args.path, content };
    }

    if (toolName === "list_files") {
        const maxCount = Number.isInteger(args.max_count) ? args.max_count : MAX_SEARCH_RESULTS;
        const files = listFilesRecursive(args.dir, maxCount);
        return { dir: args.dir, files };
    }

    if (toolName === "search_repo") {
        const maxResults = Number.isInteger(args.max_results) ? args.max_results : MAX_SEARCH_RESULTS;
        const matches = await searchRepoRipgrep(args.query, maxResults);
        return { query: args.query, matches };
    }

    if (toolName === "compile_tests") {
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



        const { stdout, stderr } = await execFileAsync("javac", javacArgs, {
            cwd: testSourceRoot,
            maxBuffer: 10_000_000
        });

        return { status: "compiled", files_compiled: javaFilesRel.length, stdout, stderr };
    }

    if (toolName === "run_tests") {
        const testAbs = path.resolve(REPO_ROOT, TEST_OUTPUT_DIR);
        const classPath = buildResolvedRuntimeClassPath();

        const javaArgs = [
            "-jar",
            JUNIT_JAR,
            "execute",
            "--class-path",
            classPath,
            `--scan-classpath=${testAbs}`,
        ];

        const { stdout, stderr } = await execFileAsync("java", javaArgs, {
            cwd: REPO_ROOT,
            maxBuffer: MAX_PROCESS_BUFFER_BYTES
        });

        return { status: "ran", stdout, stderr };
    }

    if (toolName === "compile_prod") {
        const prodSourceDir = "src/main/java";
        const maxJavaFiles = MAX_JAVA_FILES;

        const prodRootAbs = path.resolve(REPO_ROOT, prodSourceDir);
        const outDirAbs = path.resolve(REPO_ROOT, PROD_OUTPUT_DIR);

        if (!fs.existsSync(prodRootAbs)) {
            return { status: "no_prod_dir", message: "src/main/java does not exist." };
        }

        fs.mkdirSync(outDirAbs, { recursive: true });

        const allAbsFiles = listFilesRecursiveFromAbsoluteRoot(prodRootAbs, maxJavaFiles);
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

        const { stdout, stderr } = await execFileAsync("javac", javacArgs, {
            cwd: prodRootAbs,
            maxBuffer: MAX_PROCESS_BUFFER_BYTES
        });

        return { status: "compiled", files_compiled: javaFilesRelToProdRoot.length, stdout, stderr };
    }

    if (toolName === "write_file") {
        const result = safeWriteFile(args.path, args.content);
        return { status: "written", ...result };
    }

    if (toolName === "apply_patch") {
        const byteLength = Buffer.byteLength(args.patch, "utf-8");
        if (byteLength > MAX_PATCH_BYTES) {
            throw new Error(`Patch too large (${byteLength} bytes). Max is ${MAX_PATCH_BYTES}.`);
        }

        assertWriteAllowed(args.path);

        const resolved = normalizeAndValidatePath(args.path);
        const original = fs.existsSync(resolved) ? fs.readFileSync(resolved, "utf-8") : "";

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

    if (mode === MODE_EXPLAIN || mode === MODE_PLAN) {
        return base.concat(readOnly).join("\n");
    }

    if (mode === MODE_DEBUG) {
        return base.concat(debug).join("\n");
    }

    if (mode === MODE_TEST) {
        return base.concat(test).join("\n");
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
        { role: "user", content: promptWithoutMode },
    ];

    const EMPTY_TOOL_CALLS_LENGTH = 0;

    let turns = 0;
    let done = false;

    while (!done && turns < MAX_TOOL_TURNS) {
        const response = await openai.chat.completions.create({
            model: MODEL_NAME,
            messages: input,
            tools: activeTools,
            tool_choice: "auto",
        });

        const choice = response.choices[0];
        const assistantMessage = choice.message;

        const assistantText = (assistantMessage.content ?? "").trim();
        if (assistantText.length > 0) {
            console.log(assistantText);
        }

        const toolCalls = assistantMessage.tool_calls ?? [];

        if (toolCalls.length > EMPTY_TOOL_CALLS_LENGTH) {
            // Save the assistant message that contains tool calls
            input.push(assistantMessage);

            for (const call of toolCalls) {
                const toolName = call.function.name;
                const rawArgs = call.function.arguments ?? "{}";

                const args = JSON.parse(rawArgs);
                const result = await callLocalTool(toolName, args);

                input.push({
                    role: "tool",
                    tool_call_id: call.id,
                    content: JSON.stringify(result),
                });
            }
        } else {
            done = true;
        }

        turns += 1;
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